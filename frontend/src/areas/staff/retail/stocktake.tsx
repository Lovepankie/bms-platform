import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox } from '../../../app/ui/SearchBox';
import { matchesSearch, useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type Product, type Stocktake, type StocktakeLine } from '../../../api/retail';
import { milliOf, parseCount, parseQty, showQty } from './maths';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne } from './barcode-state';
import { SkeletonList } from '../../../components/states';
import { BranchRequired, CategoryLabel, Gate, Note, Problem, useStockingBranch } from './ui';
import { UNITS, capabilityOn, needsOf } from './permissions';
import { chosenPack } from './units-state';
import { useStaff } from '../context';
import { BusyButton } from '../../../app/ui/BusyButton';

// Stock-take (FR-RET-08): count the products of a branch, review the variance the server works
// out, then commit to write the adjustments. Only counted rows are sent.

/** The variance written at commit once committed, otherwise the draft's (counted less expected). */
const variance = (l: StocktakeLine): string => l.committed_variance_qty ?? l.variance_qty ?? '0';

export function StocktakeReview({ stocktake, onCommit, busy }: { stocktake: Stocktake; onCommit?: () => void; busy?: boolean }) {
  const changed = (stocktake.lines ?? []).filter((l) => milliOf(variance(l)) !== 0);
  return (
    <section aria-label="Stock-take review">
      <h2>{stocktake.status === 'committed' ? 'Stock-take committed' : 'Review the variance'}</h2>
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
  const stock = useQuery({ queryKey: ['retail', 'stock', branchId, '', false], queryFn: () => countableStock(branchId) });

  // A scan (issue #345) counts one more of the item, or a whole pack when the barcode is on a pack. The search is cleared
  // so the counted line shows; an item not on this branch's sheet is refused rather than counted out of sight.
  const scanAdd = (p: Product, unitId?: string) => {
    const id = p.id ?? '';
    if (!stock.data) return 'The count sheet is still loading. Scan again in a moment.';
    if (!stock.data.some((r) => r.product_id === id)) return `${p.description ?? p.code ?? 'This item'} is not on the count sheet of this branch. Restock it first, or count it at its own branch.`;
    const pack = unitsOn ? chosenPack(p, unitId) : undefined;
    if (unitId && !pack) return PACK_UNUSABLE;
    search.clear();
    setCounts((c) => ({ ...c, [id]: plusOne(c[id], pack ? (parseQty(pack.factor ?? '') ?? 1000) : 1000) }));
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
        {draft.status === 'committed' && <button type="button" onClick={() => { setDraft(null); setCounts({}); }}>Start another count</button>}
      </>
    );
  }
  // The whole sheet is loaded, so the list narrows as you type, on the text, ignoring case and accents.
  const rows = (stock.data ?? []).filter((r) => matchesSearch(`${r.description ?? ''} ${r.code ?? ''} ${r.category ?? ''}`, search.text));
  return (
    <>
      <Note>Type the counted quantity for each item you counted. Leave an item blank to skip it.</Note>
      <div data-tour="count-search">
        <SearchBox id="count-search" label="Search by name, code or category" search={search} fetching={false} count={stock.data ? rows.length : undefined} />
      </div>
      <ScanBar id="count-scan" branchId={branchId} onFound={scanAdd} autoFocus />
      {stock.isPending && <SkeletonList label="Loading stock" />}
      <Problem error={stock.error} />
      {stock.data && rows.length === 0 && search.term && <NoResults term={search.term} onClear={search.clear} noun="items" />}
      {rows.map((r) => {
        const id = r.product_id ?? '';
        return (
          <div key={id} className={id === scanned ? 'rt-card scan-hit' : 'rt-card'} data-testid={id === scanned ? 'scan-hit' : undefined}
            ref={id === scanned ? (el) => el?.scrollIntoView?.({ block: 'nearest' }) : undefined}>
            <label htmlFor={`count-${id}`}>{r.description} ({r.unit})</label>
            <CategoryLabel category={r.category} />
            <input id={`count-${id}`} inputMode="decimal" value={counts[id] ?? ''} placeholder="Counted"
              aria-invalid={(counts[id] ?? '').trim() !== '' && parseCount(counts[id] ?? '') === null}
              onChange={(e) => setCounts({ ...counts, [id]: e.target.value })} />
          </div>
        );
      })}
      {bad && <p role="alert" className="rt-flag">Some counts are not valid quantities.</p>}
      <Problem error={review.error} />
      <BusyButton type="button" className="rt-primary" busy={review.isPending} disabled={entered.length === 0 || bad} onClick={() => review.mutate()} data-tour="count-review">
        {`Review variance (${entered.length} counted)`}
      </BusyButton>

    </>
  );
}

export function StocktakePage() {
  const { branchId, branchName } = useStockingBranch();
  return (
    <Gate screen="stocktake" title="Stock-take">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('stocktake')} stocking />
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <CountSheet key={branchId} branchId={branchId} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stocktake')({ component: StocktakePage });
