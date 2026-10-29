import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { Fragment, useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Highlight } from '../../../app/ui/SearchBox';
import { ItemCombobox } from '../../../app/ui/ItemCombobox';
import { matchesSearch, useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type Product, type StockRow, type Stocktake, type StocktakeLine } from '../../../api/retail';
import { bigDrop, milliOf, parseCount, parseQty, qtyString, showQty } from './maths';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne } from './barcode-state';
import { SkeletonList } from '../../../components/states';
import { BranchRequired, CategoryLabel, Gate, Note, PacksAndPiecesFields, Problem, useProfitAccess, useStockingBranch } from './ui';
import { UNITS, capabilityOn, needsOf } from './permissions';
import { chosenPack, packsAndPieces, packsAndPiecesMilli, samePack, type PackSize } from './units-state';
import { useStaff } from '../context';
import { BusyButton } from '../../../app/ui/BusyButton';
import { WORDS, plural } from '../../../app/glossary';
import { icons } from '../../../components/icons';
import { NewItemHere, type AddedItem } from './new-item-here';
import { ScreenToast, useItemAdded } from './toast';
import { entryWarnings } from './entry-checks-state';
import { useEntryChecks, useEntryNorms } from './entry-checks';
import { SuccessScreen } from '../../../app/ui/SuccessScreen';
import { OLD_COUNT_MS, changeWords, countKey, readCount, savedSince, saveWords, useCountSave, type CountState, type OtherTab } from './count-save-state';
import { showWhen } from './catalogue-products';

/** What the page says when the same count is open in another tab (issue #681). */
const OTHER_TAB_WORDS: Record<Exclude<OtherTab, null>, string> = {
  open: 'This count is also open in another tab',
  committed: 'Counted and saved in another tab',
  partly: 'Some counts were saved in another tab. The others are still here.',
};

// Stock-take (FR-RET-08): count the products of a branch, review the variance the server works
// out, then commit to write the adjustments. Only counted rows are sent.

/** The variance written at commit once committed, otherwise the draft's (counted less expected). */
const variance = (l: StocktakeLine): string => l.committed_variance_qty ?? l.variance_qty ?? '0';

/**
 * "This removes 8 pieces. Is that right?" for a big drop, in the item's unit when the sheet gives it, and in its standard pack
 * and pieces when it has one (issue #650): "This removes 2 Cartons and 3 Pieces (51 Pieces). Is that right?".
 */
export function dropWords(l: StocktakeLine, unit: string | undefined, pack?: PackSize): string {
  const removed = milliOf(l.expected_qty ?? '0') - milliOf(l.counted_qty ?? '0');
  if (unit && pack) return `This removes ${packsAndPieces(qtyString(removed), unit, pack)}. Is that right?`;
  const word = unit ? ` ${removed === 1000 ? unit : plural(unit)}` : '';
  return `This removes ${showQty(qtyString(removed))}${word}. Is that right?`;
}

export function StocktakeReview({ stocktake, onCommit, busy, units, packs }: {
  stocktake: Stocktake;
  /** Called with the product ids of the big drops ticked as right, which the server checks too (issue #636). */
  onCommit?: (acknowledged: string[]) => void;
  busy?: boolean;
  /** Each item's unit by product id, for the words of a big drop. */
  units?: ReadonlyMap<string, string | undefined>;
  /** Each item's standard pack by product id (issue #650), for the same words in packs and pieces. */
  packs?: ReadonlyMap<string, PackSize | undefined>;
}) {
  const changed = (stocktake.lines ?? []).filter((l) => milliOf(variance(l)) !== 0);
  const draft = stocktake.status === 'draft';
  // The big drops ticked as right; Commit the count waits for every one of them. A line without a product id has a key of its
  // own, so one tick never answers for another (issue #636).
  const [ticked, setTicked] = useState<ReadonlySet<string>>(new Set());
  const hintId = useId();
  const keyOf = (l: StocktakeLine, i: number) => l.product_id ?? `line-${i}`;
  const asked = draft ? changed.flatMap((l, i) => (bigDrop(l) ? [keyOf(l, i)] : [])) : [];
  const open = asked.filter((id) => !ticked.has(id)).length;
  const tick = (id: string, on: boolean) => setTicked((t) => { const n = new Set(t); if (on) n.add(id); else n.delete(id); return n; });
  return (
    <form aria-label="Stock count review" noValidate onSubmit={(e) => e.preventDefault()}>
      {/* Issue #656: a discarded count never reads as committed. */}
      <h2>{stocktake.status === 'committed' ? 'Stock count committed' : stocktake.status === 'discarded' ? 'Stock count cancelled' : 'Review the difference'}</h2>
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
            {changed.map((l, i) => {
              const v = milliOf(variance(l));
              const id = keyOf(l, i);
              return (
                <Fragment key={id}>
                  <tr>
                    <td>{l.description ?? l.product_id}</td>
                    <td className="num">{showQty(l.expected_qty ?? '0')}</td>
                    <td className="num">{showQty(l.counted_qty ?? '0')}</td>
                    <td className="num">
                      <strong>{v > 0 ? '+' : ''}{showQty(variance(l))}</strong> {v > 0 ? '(more)' : '(less)'}
                    </td>
                  </tr>
                  {asked.includes(id) && (
                    <tr className="count-drop-row">
                      <td colSpan={4}>
                        <div className="alert alert-warning entry-warning" data-testid={`count-drop-${id}`}>
                          <span className="entry-warning-icon" aria-hidden="true">{icons.warning}</span>
                          <div>
                            <p id={`count-drop-${id}-words`}>{dropWords(l, units?.get(id), packs?.get(id))}</p>
                            <label className="count-drop-tick">
                              <input type="checkbox" checked={ticked.has(id)} aria-describedby={`count-drop-${id}-words`}
                                data-needed={ticked.has(id) ? undefined : ''} onChange={(e) => tick(id, e.target.checked)} />
                              {`Yes, ${l.description ?? 'this item'} is right`}
                            </label>
                          </div>
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table></div>
      )}
      {draft && onCommit && (
        <>
          {/* Issue #636: the button names its hint, so a press that goes to a tick says why. */}
          {open > 0 && <p className="hint" id={hintId}>{open === 1 ? 'Tick the line marked above before you commit the count.' : `Tick the ${open} lines marked above before you commit the count.`}</p>}
          <BusyButton type="button" className="rt-primary" busy={busy === true} notReady={open > 0}
            aria-describedby={open > 0 ? hintId : undefined} onClick={() => onCommit(changed.flatMap((l, i) => (l.product_id && ticked.has(keyOf(l, i)) ? [l.product_id] : [])))}>
            Commit the count
          </BusyButton>
        </>
      )}
    </form>
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
  // Issue #681: a count in progress is kept on this device for this business, branch and person, and comes back on a reload.
  const saveKey = countKey(typeof window === 'undefined' ? '' : window.location.host, branchId, me.user_id ?? me.email ?? 'staff');
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  const search = useLiveSearch();
  // Issue #681: a count in progress is kept on this device for this business, branch and person, and comes back on a reload.
  const [restored] = useState(() => readCount(saveKey));
  // When the counts brought back on opening were saved, said until they are committed or cleared (review of #686).
  const [keptSince, setKeptSince] = useState(() => savedSince(restored));
  const [clearing, setClearing] = useState(false);
  const [counts, setCounts] = useState<Record<string, string>>(restored?.counts ?? {});
  // Issue #650: the cartons typed for an item with a standard pack, with the pack they were typed in, so a pack changed or
  // removed while counting never reads the same number in another size; its pieces stay in counts, the total is what is sent.
  const [packCounts, setPackCounts] = useState<Record<string, { text: string; pack: PackSize }>>(restored?.packCounts ?? {});
  // Every pick, scan or new item says so (issue #498): a short toast with the count it now has, and the line flashes once.
  // The counts as of the last add are kept, as on the sale, so a scan answered after a render sees the latest.
  const { added, flashOf, show, toast } = useItemAdded();
  const current = useRef(counts);
  current.current = counts;
  /** Adds `milli` to the item's count and says so. */
  const countUp = (p: Pick<Product, 'id' | 'description'>, milli: number) => {
    const id = p.id ?? '';
    const now = plusOne(current.current[id], milli);
    current.current = { ...current.current, [id]: now };
    setCounts((c) => ({ ...c, [id]: now }));
    added(id, p.description, now);
  };
  const [draft, setDraft] = useState<Stocktake | null>(null);
  // The line the last scan counted, marked so the counter sees which one went up; the cursor stays in the Scan box.
  const [scanned, setScanned] = useState<string | null>(null);
  // The items on the count, in the order they were picked or scanned (issue #534): the sheet is searched, not shown whole.
  const [listed, setListed] = useState<string[]>(restored?.listed ?? []);
  const list = (id: string) => setListed((l) => (l.includes(id) ? l : [...l, id]));
  // The lines whose count is entered, in the order they were done; and the question asked before a count with lines left.
  const [done, setDone] = useState<string[]>(restored?.done ?? []);
  // The question answers the counts it was asked about: it shows only while the counts are those it was asked on, so any
  // change to a count withdraws it in the same render and Review the difference anyway never sends counts it was not asked about (#535).
  const [askedOn, setAskedOn] = useState<Record<string, string> | null>(null);
  // A picked item's count box takes the cursor, after the picker has put it back in the search (review of #535).
  const [focusOn, setFocusOn] = useState<{ id: string; pick: number } | null>(null);
  useEffect(() => {
    if (focusOn) (document.getElementById(`count-${focusOn.id}-packs`) ?? document.getElementById(`count-${focusOn.id}`))?.focus();
  }, [focusOn]);
  const stock = useQuery({ queryKey: ['retail', 'stock', branchId, '', false], queryFn: () => countableStock(branchId) });
  const packOf = useMemo(() => new Map((stock.data ?? []).map((r) => [r.product_id ?? '', r.standard_pack])), [stock.data]);
  // Each count as it is sent, in pieces (issue #650): the cartons and pieces of an item with a standard pack added up, or the
  // pieces typed. A count that cannot be read stays as typed, so it is marked as not valid and never sent.
  const totals = useMemo(() => {
    const out: Record<string, string> = { ...counts };
    for (const [id, { text, pack }] of Object.entries(packCounts)) {
      if (text.trim() === '') continue;
      const milli = packsAndPiecesMilli(text, counts[id] ?? '', pack);
      out[id] = milli === null ? `${text} ${counts[id] ?? ''}` : qtyString(milli);
    }
    return out;
  }, [counts, packCounts]);
  const confirming = askedOn !== null && askedOn === totals;
  const setConfirming = (on: boolean) => setAskedOn(on ? totals : null);
  /** A line leaves Not counted yet once its count is in, and goes back if the count is emptied. */
  const settle = (id: string) => setDone((d) => ((totals[id] ?? '').trim() === '' ? d.filter((x) => x !== id) : d.includes(id) ? d : [...d, id]));
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
    countUp(product, parseCount(qty) ?? 0);
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
    countUp(p, pack ? (parseQty(pack.factor ?? '') ?? 1000) : 1000);
    setDone((d) => (d.includes(id) ? d : [...d, id]));
    setScanned(id);
    return undefined;
  };
  // A count restored for an item no longer on the sheet is left out (issue #681), so it is never sent out of sight.
  const onSheet = useMemo(() => (stock.data ? new Set(stock.data.map((r) => r.product_id ?? '')) : null), [stock.data]);
  const entered = Object.entries(totals).filter(([id, v]) => v.trim() !== '' && (!onSheet || onSheet.has(id)));
  const bad = entered.some(([, v]) => parseCount(v) === null);
  const valid = entered.flatMap(([productId, v]) => { const c = parseCount(v); return c === null ? [] : [{ product_id: productId, counted_qty: qtyString(c) }]; });
  const review = useMutation({
    mutationFn: () => retail.createStocktake({ branch_id: branchId, lines: valid }),
    onSuccess: setDraft,
  });
  // Issue #681: the same count changed in another tab is merged in item by item; committed there, this one starts empty.
  const apply = useCallback((next: CountState | null) => {
    current.current = next?.counts ?? {};
    setCounts(next?.counts ?? {}); setPackCounts(next?.packCounts ?? {}); setListed(next?.listed ?? []); setDone(next?.done ?? []);
    if (!next) setDraft((d) => (d?.status === 'draft' ? null : d));
  }, []);
  const saving = useCountSave({ saveKey, restored, saved: { counts, packCounts, listed, done }, apply });
  const savedWords = saveWords(valid.length, saving.onDevice);
  // A restored count for an item no longer on the sheet is dropped once the sheet is read, so the device never keeps it.
  const pruned = useRef(restored === null);
  useEffect(() => {
    if (pruned.current || !onSheet) return;
    pruned.current = true;
    const keep = <T,>(m: Record<string, T>) => Object.fromEntries(Object.entries(m).filter(([id]) => onSheet.has(id)));
    setCounts((c) => keep(c)); setPackCounts((c) => keep(c)); setListed((l) => l.filter((id) => onSheet.has(id))); setDone((d) => d.filter((id) => onSheet.has(id)));
  }, [onSheet]);
  // Issue #353: each count against what the records hold here, or, where they hold nothing, against the item's usual purchase;
  // said before the review, never blocking. Only the counts whose records hold nothing read the item's history.
  const sheet = new Map((stock.data ?? []).map((r) => [r.product_id ?? '', r]));
  const recorded = new Map([...sheet].map(([id, r]) => [id, milliOf(r.qty ?? '0')]));
  const norms = useEntryNorms(entered.filter(([id]) => (recorded.get(id) ?? 0) <= 0).map(([id]) => id));
  const warnings = entered.flatMap(([id, v]) => {
    const row = sheet.get(id);
    if (!row) return [];
    const product: Product = { id, description: row.description, unit: row.unit };
    const here = recorded.get(id) ?? 0;
    // Records that hold some need no history: they are the comparison. A record of nothing reads the purchases.
    const usual = here > 0 ? { product_id: id, unit: row.unit, quantity_factor: norms(id)?.quantity_factor ?? 10 } : norms(id);
    return entryWarnings(usual, { screen: 'count', product, lineKey: id, qtyMilli: parseCount(v), qtyFieldId: `count-${id}`, recordedMilli: here });
  });
  const entry = useEntryChecks('count', branchId, warnings, () => review.mutate(), totals);
  const commit = useMutation({
    mutationFn: (acknowledged: string[]) => retail.commitStocktake(draft?.id ?? '', acknowledged),
    onSuccess: (st) => {
      // Issue #353: a count is saved when it is committed, so that is when its confirmed warnings are recorded.
      entry.saved();
      setDraft(st);
      // Issue #681: the count is done, so this device keeps nothing more of it, another tab of it hears so, and the screen says so.
      // Only the counts that went in leave the page; one changed or added since the review, here or in another tab, stays.
      saving.committed();
      const sent = new Map((st.lines ?? []).map((l) => [l.product_id ?? '', milliOf(l.counted_qty ?? '0')]));
      const left = (id: string) => !sent.has(id) || parseCount(totals[id] ?? '') !== sent.get(id);
      const keep = <T,>(m: Record<string, T>) => Object.fromEntries(Object.entries(m).filter(([id]) => left(id)));
      setCounts(keep(counts)); setPackCounts(keep(packCounts)); setListed(listed.filter(left)); setDone(done.filter(left)); setConfirming(false);
      setKeptSince(null);
      const n = st.lines?.length ?? 0;
      show(`Stock updated for ${n} ${n === 1 ? 'item' : 'items'}`, 'success');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  const units = new Map((stock.data ?? []).map((r) => [r.product_id ?? '', r.unit]));

  if (draft?.status === 'committed') {
    const n = draft.lines?.length ?? 0;
    const { summary, changes } = changeWords(draft.lines ?? [], units);
    return (
      <>
        <SuccessScreen level={2} title={`Stock updated for ${n} ${n === 1 ? 'item' : 'items'}`}
          detail={<>{summary}{changes.map((c, i) => <Fragment key={i}><br />{c}</Fragment>)}</>}
          actions={[{ label: 'Start another count', onClick: () => setDraft(null) }, { label: 'Back to My stock', to: '/staff/retail/stock' }]} />
        {toast}
      </>
    );
  }
  if (draft) {
    return (
      <>
        <StocktakeReview key={draft.id} stocktake={draft} onCommit={(acknowledged) => commit.mutate(acknowledged)} busy={commit.isPending} units={units} packs={packOf} />
        <Problem error={commit.error} />
        {draft.status === 'draft' && <button type="button" onClick={() => setDraft(null)}>Back to the count</button>}
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
  const isCounted = (id: string) => (totals[id] ?? '').trim() !== '';
  // Sets, so a sheet of thousands of lines is not walked line by line for every key in a count box.
  const doneSet = new Set(done);
  const listedSet = new Set(listed);
  const notCounted = [...rows.filter((r) => !doneSet.has(r.product_id ?? '')), ...(stock.data ?? []).filter((r) => !doneSet.has(r.product_id ?? '') && !listedSet.has(r.product_id ?? ''))];
  const counted = done.flatMap((id) => byId.get(id) ?? []);
  const uncounted = (stock.data ?? []).filter((r) => !isCounted(r.product_id ?? '')).length;
  const finish = () => {
    setDone((d) => [...d, ...Object.keys(totals).filter((id) => isCounted(id) && !d.includes(id))]);
    if (uncounted > 0 && !confirming) return setConfirming(true);
    setConfirming(false);
    entry.check();
  };
  // Issue #681: each count kept on this device says so under its box, the moment it is stored.
  const savedOn = new Set(saving.savedHere ? valid.map((l) => l.product_id) : []);
  const savedMark = (id: string) => savedOn.has(id) && <p className="hint count-saved" data-testid={`count-saved-${id}`}>Saved</p>;
  const line = (r: StockRow, waiting: boolean) => {
    const id = r.product_id ?? '';
    // The pack the cartons were typed in, while any are typed; else the item's standard pack now.
    const typed = packCounts[id];
    const pack = typed && typed.text.trim() !== '' ? typed.pack : r.standard_pack;
    const enter = (e: KeyboardEvent<HTMLInputElement>) => {
      if (e.key !== 'Enter') return;
      settle(id);
      // A keypad counter goes on down the list: the line moves up to Counted items, the cursor to the next line.
      const next = waiting ? notCounted[notCounted.findIndex((x) => x.product_id === id) + 1]?.product_id : undefined;
      if (next) setFocusOn((f) => ({ id: next, pick: (f?.pick ?? 0) + 1 }));
    };
    const zero = waiting && (
      <button type="button" className="btn-sm" aria-label={`Count 0 of ${r.description ?? ''}`} onClick={() => { setCounts((c) => ({ ...c, [id]: '0' })); setPackCounts((c) => { const n = { ...c }; delete n[id]; return n; }); setDone((d) => (d.includes(id) ? d : [...d, id])); }}>
        0
      </button>
    );
    if (pack?.unit && r.unit) {
      // Issue #650: an item with a standard pack is counted in cartons and pieces, or pieces alone, and shows its total in pieces.
      return (
        <div key={id} className={id === scanned ? 'rt-card line-card scan-hit' : 'rt-card line-card'} data-testid={id === scanned ? 'scan-hit' : undefined}
          ref={id === scanned ? (el) => el?.scrollIntoView?.({ block: 'nearest' }) : undefined}>
          {flashOf(id)}
          <strong id={`count-${id}-name`}>{r.description}</strong>
          <CategoryLabel category={r.category} />
          <PacksAndPiecesFields id={`count-${id}`} nameId={`count-${id}-name`} unit={r.unit} pack={{ unit: pack.unit, factor: pack.factor }} totalWord="Counted"
            packs={typed?.text ?? ''} pieces={counts[id] ?? ''} onBlur={() => settle(id)} onPiecesKeyDown={enter} after={zero}
            onPacks={(v) => setPackCounts((c) => ({ ...c, [id]: { text: v, pack: c[id] && c[id].text.trim() !== '' ? c[id].pack : { unit: pack.unit, factor: pack.factor } } }))}
            onPieces={(v) => setCounts((c) => ({ ...c, [id]: v }))} />
          {typed && typed.text.trim() !== '' && !samePack(typed.pack, r.standard_pack) && (
            <p className="hint">{`Counted in ${plural(pack.unit ?? '')} of ${showQty(pack.factor ?? '')}, as when you started. This item's pack has changed since.`}</p>
          )}
          {entry.notes(id)}
          {savedMark(id)}
        </div>
      );
    }
    return (
      <div key={id} className={id === scanned ? 'rt-card line-card scan-hit' : 'rt-card line-card'} data-testid={id === scanned ? 'scan-hit' : undefined}
        ref={id === scanned ? (el) => el?.scrollIntoView?.({ block: 'nearest' }) : undefined}>
        {flashOf(id)}
        <label htmlFor={`count-${id}`}>{r.description} ({r.unit})</label>
        <CategoryLabel category={r.category} />
        <div className="count-entry">
          <input id={`count-${id}`} inputMode="decimal" value={counts[id] ?? ''} placeholder="Counted"
            aria-invalid={(counts[id] ?? '').trim() !== '' && parseCount(counts[id] ?? '') === null}
            onChange={(e) => { const v = e.target.value; setCounts((c) => ({ ...c, [id]: v })); }}
            onBlur={() => settle(id)}
            onKeyDown={enter} />
          {zero}
        </div>
        {entry.notes(id)}
        {savedMark(id)}
      </div>
    );
  };
  return (
    <>
      <Note>Find or scan each item you count, or work down the list Not counted yet. Type how many you counted, or 0 for an item that is gone. An item kept in cartons takes cartons and pieces, or pieces alone. Leave an item empty if you did not count it.</Note>
      {/* Issue #681: the person counting always sees that their work is kept, and where. */}
      <p className="hint count-saved-all" role="status" data-testid="count-saved-all">{savedWords}</p>
      {saving.other && <p className="hint" data-testid="count-other-tab">{OTHER_TAB_WORDS[saving.other]}</p>}
      {keptSince !== null && valid.length > 0 && (
        <div className={Date.now() - keptSince > OLD_COUNT_MS ? 'alert alert-warning' : 'alert alert-info'} data-testid="count-kept">
          <p>{Date.now() - keptSince > OLD_COUNT_MS
            ? `These counts were saved on ${showWhen(new Date(keptSince).toISOString())}. Stock may have changed since, so check them or start again.`
            : `These counts were saved on ${showWhen(new Date(keptSince).toISOString())}.`}</p>
          {clearing ? (
            <p className="cluster">
              <span>{`Clear all ${valid.length} ${valid.length === 1 ? 'count' : 'counts'}? Your stock does not change.`}</span>
              <button type="button" className="btn-sm" onClick={() => { current.current = {}; setCounts({}); setPackCounts({}); setListed([]); setDone([]); setConfirming(false); setKeptSince(null); setClearing(false); }}>Clear the counts</button>
              <button type="button" className="btn-sm" onClick={() => setClearing(false)}>Keep them</button>
            </p>
          ) : (
            <button type="button" className="btn-sm" onClick={() => setClearing(true)}>Start again</button>
          )}
        </div>
      )}
      <div data-tour="count-search">
        <ItemCombobox
          id="count-search"
          label="Find an item by name, code or category"
          search={search}
          results={found}
          optionKey={(r) => r.product_id ?? ''}
          onPick={(r) => { list(r.product_id ?? ''); added(r.product_id ?? '', r.description, current.current[r.product_id ?? '']); setScanned(r.product_id ?? ''); setFocusOn((f) => ({ id: r.product_id ?? '', pick: (f?.pick ?? 0) + 1 })); }}
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
      <ScreenToast.Provider value={show}>
        <NewItemHere id="count-new" counting askCost={profit} qtyLabel="How many you counted" onAdded={addNew} />
      </ScreenToast.Provider>
      <ScanBar id="count-scan" branchId={branchId} onFound={scanAdd} autoFocus toasts />
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
          <p>{`${uncounted} ${uncounted === 1 ? 'item' : 'items'} not counted. Review the difference anyway?`}</p>
          <p className="cluster">
            <button type="button" className="btn-sm" onClick={() => setConfirming(false)}>Keep counting</button>
            <BusyButton type="button" className="btn-sm" busy={review.isPending} disabled={entered.length === 0 || bad} onClick={finish}>Review the difference anyway</BusyButton>
          </p>
        </div>
      )}
      <BusyButton type="button" className="rt-primary" busy={review.isPending} notReady={entered.length === 0 || bad} onClick={finish} data-tour="count-review">
        {`Review the difference (${entered.length} counted)`}
      </BusyButton>
      {toast}

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
          {/* Keyed by branch, so a branch switch starts afresh from that branch's own saved count (review of #681). */}
          <CountSheet key={branchId} branchId={branchId} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stocktake')({ component: StocktakePage });
