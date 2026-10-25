import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { Highlight } from '../../../app/ui/SearchBox';
import { ItemCombobox } from '../../../app/ui/ItemCombobox';
import { matchesSearch, useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type Product, type StockRow, type Stocktake, type StocktakeLine } from '../../../api/retail';
import { milliOf, parseCount, parseQty, showQty } from './maths';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne } from './barcode-state';
import { SkeletonList } from '../../../components/states';
import { BranchRequired, CategoryLabel, Gate, Note, Problem, useProfitAccess, useStockingBranch } from './ui';
import { UNITS, capabilityOn, needsOf } from './permissions';
import { chosenPack } from './units-state';
import { useStaff } from '../context';
import { BusyButton } from '../../../app/ui/BusyButton';
import { WORDS } from '../../../app/glossary';
import { NewItemHere, type AddedItem } from './new-item-here';

// Stock-take (FR-RET-08): count the products of a branch, review the variance the server works
// out, then commit to write the adjustments. Only counted rows are sent.

/** The variance written at commit once committed, otherwise the draft's (counted less expected). */
const variance = (l: StocktakeLine): string => l.committed_variance_qty ?? l.variance_qty ?? '0';

export function StocktakeReview({ stocktake, onCommit, busy }: { stocktake: Stocktake; onCommit?: () => void; busy?: boolean }) {
  const changed = (stocktake.lines ?? []).filter((l) => milliOf(variance(l)) !== 0);
  return (
    <section aria-label="Stock count review">
      <h2>{stocktake.status === 'committed' ? 'Stock count committed' : 'Review the difference'}</h2>
      {changed.length === 0 ? (
        <p>Every counted item matches the system.</p>
      ) : (
        <div className="table-wrap" tabIndex={0}><table>
          <thead>
            <tr>
              <th>Item</th>
              <th className="num">System</th>
              <th className="num">Counted</th>
              <th className="num">Difference</th>
            </tr>
          </thead>
          <tbody>
            {changed.map((l) => {
              const v = milliOf(variance(l));
              return (
                <tr key={l.product_id}>
                  <td>{l.description ?? l.product_id}</td>
                  <td className="num">{showQty(l.expected_qty ?? '0')}</td>
                  <td className="num">{showQty(l.counted_qty ?? '0')}</td>
                  <td className="num">
                    <strong>{v > 0 ? '+' : ''}{showQty(variance(l))}</strong> {v > 0 ? '(more)' : '(less)'}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table></div>
      )}
      {stocktake.status === 'draft' && onCommit && (
        <BusyButton type="button" className="rt-primary" busy={busy === true} onClick={onCommit}>
          Commit the count
        </BusyButton>
      )}
    </section>
  );
}

/**
 * The count sheet lists the active items and every inactive item that still holds stock (issue #342): a shelf is counted
 * whatever the catalogue says, so a held quantity never drops off the sheet and hides a shortage.
 */
export async function countableStock(branchId: string, api: Pick<typeof retail, 'listStock'> = retail) {
  const [active, withInactive] = await Promise.all([api.listStock({ branchId }), api.listStock({ branchId, includeInactive: true })]);
  const shown = new Set(active.map((r) => r.product_id));
  return [...active, ...withInactive.filter((r) => !shown.has(r.product_id) && Number(r.qty) !== 0)].sort((x, y) => (x.code ?? '').localeCompare(y.code ?? ''));
}

function CountSheet({ branchId }: { branchId: string }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  const search = useLiveSearch();
  const [counts, setCounts] = useState<Record<string, string>>({});
  const [draft, setDraft] = useState<Stocktake | null>(null);
  // The line the last scan counted, marked so the counter sees which one went up; the cursor stays in the Scan box.
  const [scanned, setScanned] = useState<string | null>(null);
  // The items on the count, in the order they were picked or scanned (issue #534): the sheet is searched, not shown whole.
  const [listed, setListed] = useState<string[]>([]);
  const list = (id: string) => setListed((l) => (l.includes(id) ? l : [...l, id]));
  // The lines whose count is entered, in the order they were done; and the question asked before a count with lines left.
  const [done, setDone] = useState<string[]>([]);
  // The question answers the counts it was asked about: it shows only while the counts are those it was asked on, so any
  // change to a count withdraws it in the same render and Finish anyway never sends counts it was not asked about (#535).
  const [askedOn, setAskedOn] = useState<Record<string, string> | null>(null);
  const confirming = askedOn !== null && askedOn === counts;
  const setConfirming = (on: boolean) => setAskedOn(on ? counts : null);
  // A picked item's count box takes the cursor, after the picker has put it back in the search (review of #535).
  const [focusOn, setFocusOn] = useState<{ id: string; pick: number } | null>(null);
  useEffect(() => {
    if (focusOn) document.getElementById(`count-${focusOn.id}`)?.focus();
  }, [focusOn]);
  /** A line leaves Not counted yet once its count is in, and goes back if the count is emptied. */
  const settle = (id: string) => setDone((d) => ((counts[id] ?? '').trim() === '' ? d.filter((x) => x !== id) : d.includes(id) ? d : [...d, id]));
  const stock = useQuery({ queryKey: ['retail', 'stock', branchId, '', false], queryFn: () => countableStock(branchId) });
  const profit = useProfitAccess();
  // Issue #505: a first count of an item not in the list. The new item joins the sheet once it is read again, with its count done.
  // An item already counted (one chosen from the name warning) has the count added to it, as a scan adds; an item the sheet of
  // this branch does not hold is refused, as a scan is, rather than counted out of sight.
  const addNew = async ({ product, qty }: AddedItem) => {
    const id = product.id ?? '';
    const sheet = await stock.refetch();
    if (!(sheet.data ?? []).some((r) => r.product_id === id)) {
      return `${product.description ?? 'The item'} is saved, but it is not on the count sheet of this branch${sheet.isError ? ', which could not be read again' : ''}. Find it in the search to count it, or count it at its own branch.`;
    }
    list(id);
    setCounts((c) => ({ ...c, [id]: plusOne(c[id], parseCount(qty) ?? 0) }));
    setDone((d) => (d.includes(id) ? d : [...d, id]));
    setScanned(id);
  };

  // A scan (issue #345) counts one more of the item, or a whole pack when the barcode is on a pack, and puts it on the count.
  // The search is cleared so the counted line shows; an item not on this branch's sheet is refused rather than counted out of sight.
  const scanAdd = (p: Product, unitId?: string) => {
    const id = p.id ?? '';
    if (!stock.data) return 'The count sheet is still loading. Scan again in a moment.';
    if (!stock.data.some((r) => r.product_id === id)) return `${p.description ?? p.code ?? 'This item'} is not on the count sheet of this branch. Buy stock of it first, or count it at its own branch.`;
    const pack = unitsOn ? chosenPack(p, unitId) : undefined;
    if (unitId && !pack) return PACK_UNUSABLE;
    search.clear();
    setCounts((c) => ({ ...c, [id]: plusOne(c[id], pack ? (parseQty(pack.factor ?? '') ?? 1000) : 1000) }));
    setDone((d) => (d.includes(id) ? d : [...d, id]));
    setScanned(id);
    return undefined;
  };
  const entered = Object.entries(counts).filter(([, v]) => v.trim() !== '');
  const bad = entered.some(([, v]) => parseCount(v) === null);
  const review = useMutation({
    mutationFn: () => retail.createStocktake({ branch_id: branchId, lines: entered.map(([productId, countedQty]) => ({ product_id: productId, counted_qty: countedQty.trim() })) }),
    onSuccess: setDraft,
  });
  const commit = useMutation({
    mutationFn: () => retail.commitStocktake(draft?.id ?? ''),
    onSuccess: (st) => {
      setDraft(st);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });

  if (draft) {
    return (
      <>
        <StocktakeReview stocktake={draft} onCommit={() => commit.mutate()} busy={commit.isPending} />
        <Problem error={commit.error} />
        {draft.status === 'draft' && <button type="button" onClick={() => setDraft(null)}>Back to the count</button>}
        {draft.status === 'committed' && <button type="button" onClick={() => { setDraft(null); setCounts({}); setListed([]); setDone([]); setConfirming(false); }}>Start another count</button>}
      </>
    );
  }
  // The whole sheet is loaded, so the matches are found in the browser, on the settled term, ignoring case and accents.
  const found = search.term === '' || !stock.data ? undefined : stock.data.filter((r) => matchesSearch(`${r.description ?? ''} ${r.code ?? ''} ${r.category ?? ''}`, search.term));
  const byId = new Map((stock.data ?? []).map((r) => [r.product_id ?? '', r]));
  const rows = listed.flatMap((id) => byId.get(id) ?? []);
  // Owner decision on #535: the whole sheet stays below the search, as "Not counted yet", so a full count walks the shelf list.
  // A line leaves it once its count is entered (on leaving the box, on Enter, or on 0), and "Counted items" holds the done
  // ones. An item found or scanned and not yet counted heads the list, marked, for its count.
  const isCounted = (id: string) => (counts[id] ?? '').trim() !== '';
  // Sets, so a sheet of thousands of lines is not walked line by line for every key in a count box.
  const doneSet = new Set(done);
  const listedSet = new Set(listed);
  const notCounted = [...rows.filter((r) => !doneSet.has(r.product_id ?? '')), ...(stock.data ?? []).filter((r) => !doneSet.has(r.product_id ?? '') && !listedSet.has(r.product_id ?? ''))];
  const counted = done.flatMap((id) => byId.get(id) ?? []);
  const uncounted = (stock.data ?? []).filter((r) => !isCounted(r.product_id ?? '')).length;
  const finish = () => {
    setDone((d) => [...d, ...Object.keys(counts).filter((id) => isCounted(id) && !d.includes(id))]);
    if (uncounted > 0 && !confirming) return setConfirming(true);
    setConfirming(false);
    review.mutate();
  };
  const line = (r: StockRow, waiting: boolean) => {
    const id = r.product_id ?? '';
    return (
      <div key={id} className={id === scanned ? 'rt-card scan-hit' : 'rt-card'} data-testid={id === scanned ? 'scan-hit' : undefined}
        ref={id === scanned ? (el) => el?.scrollIntoView?.({ block: 'nearest' }) : undefined}>
        <label htmlFor={`count-${id}`}>{r.description} ({r.unit})</label>
        <CategoryLabel category={r.category} />
        <div className="count-entry">
          <input id={`count-${id}`} inputMode="decimal" value={counts[id] ?? ''} placeholder="Counted"
            aria-invalid={(counts[id] ?? '').trim() !== '' && parseCount(counts[id] ?? '') === null}
            onChange={(e) => { const v = e.target.value; setCounts((c) => ({ ...c, [id]: v })); }}
            onBlur={() => settle(id)}
            onKeyDown={(e) => {
              if (e.key !== 'Enter') return;
              settle(id);
              // A keypad counter goes on down the list: the line moves up to Counted items, the cursor to the next line.
              const next = waiting ? notCounted[notCounted.findIndex((x) => x.product_id === id) + 1]?.product_id : undefined;
              if (next) setFocusOn((f) => ({ id: next, pick: (f?.pick ?? 0) + 1 }));
            }} />
          {waiting && (
            <button type="button" className="btn-sm" aria-label={`Count 0 of ${r.description ?? ''}`} onClick={() => { setCounts((c) => ({ ...c, [id]: '0' })); setDone((d) => (d.includes(id) ? d : [...d, id])); }}>
              0
            </button>
          )}
        </div>
      </div>
    );
  };
  return (
    <>
      <Note>Find or scan each item you count, or work down the list Not counted yet. Type how many you counted, or 0 for an item that is gone.</Note>
      <div data-tour="count-search">
        <ItemCombobox
          id="count-search"
          label="Find an item by name, code or category"
          search={search}
          results={found}
          optionKey={(r) => r.product_id ?? ''}
          onPick={(r) => { list(r.product_id ?? ''); setScanned(r.product_id ?? ''); setFocusOn((f) => ({ id: r.product_id ?? '', pick: (f?.pick ?? 0) + 1 })); }}
          renderOption={(r) => (
            <>
              <span className="cb-text">
                <strong><Highlight text={r.description ?? ''} term={search.term} /></strong> (<Highlight text={r.code ?? ''} term={search.term} />)
                <span className="cb-meta">{[r.category, r.unit].filter(Boolean).join(', ')}</span>
              </span>
              <span className="cb-verb" aria-hidden="true">{listed.includes(r.product_id ?? '') ? 'On the count' : 'Count'}</span>
            </>
          )}
        />
      </div>
      <NewItemHere id="count-new" counting askCost={profit} qtyLabel="How many you counted" onAdded={addNew} />
      <ScanBar id="count-scan" branchId={branchId} onFound={scanAdd} autoFocus />
      {stock.isPending && <SkeletonList label="Loading stock" />}
      <Problem error={stock.error} />
      {stock.data && (
        <>
          <h2>Counted items ({counted.length})</h2>
          {counted.length === 0 && <p className="empty-state">No items yet. Find an item above, scan it, or count it in the list below.</p>}
          {counted.map((r) => line(r, false))}
          <h2>Not counted yet ({notCounted.length})</h2>
          {notCounted.length === 0 ? <p className="empty-state">Every item on the sheet is counted.</p> : <p className="hint">Type how many are on the shelf, or tap 0 for an item that is gone.</p>}
          {notCounted.map((r) => line(r, true))}
        </>
      )}
      {bad && <p role="alert" className="rt-flag">Some counts are not valid quantities.</p>}
      <Problem error={review.error} />
      {confirming && uncounted > 0 && (
        <div role="alert" className="alert alert-warning" data-testid="count-not-done">
          <p>{`${uncounted} ${uncounted === 1 ? 'item' : 'items'} not counted. Finish anyway?`}</p>
          <p className="cluster">
            <button type="button" className="btn-sm" onClick={() => setConfirming(false)}>Keep counting</button>
            <BusyButton type="button" className="btn-sm" busy={review.isPending} disabled={entered.length === 0 || bad} onClick={finish}>Finish anyway</BusyButton>
          </p>
        </div>
      )}
      <BusyButton type="button" className="rt-primary" busy={review.isPending} notReady={entered.length === 0 || bad} onClick={finish} data-tour="count-review">
        {`Review the difference (${entered.length} counted)`}
      </BusyButton>

    </>
  );
}

export function StocktakePage() {
  const { branchId } = useStockingBranch();
  return (
    <Gate screen="stocktake" title={WORDS.countStock}>
      {branchId === null ? (
        <BranchRequired permissions={needsOf('stocktake')} stocking />
      ) : (
        <>
          <CountSheet key={branchId} branchId={branchId} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stocktake')({ component: StocktakePage });
