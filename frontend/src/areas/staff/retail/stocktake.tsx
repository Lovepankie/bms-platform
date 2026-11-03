import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useRouter } from '@tanstack/react-router';
import { Fragment, useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Highlight } from '../../../app/ui/SearchBox';
import { ItemCombobox } from '../../../app/ui/ItemCombobox';
import { matchesSearch, useLiveSearch } from '../../../app/ui/useLiveSearch';
import { businessZone, retail, type Product, type StockRow, type Stocktake, type StocktakeLine } from '../../../api/retail';
import { bigDrop, milliOf, parseCount, parseQty, qtyString, showQty } from './maths';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne } from './barcode-state';
import { SkeletonList } from '../../../components/states';
import { BranchRequired, CategoryLabel, Gate, Note, PacksAndPiecesFields, Problem, useProfitAccess, useStockingBranch } from './ui';
import { UNITS, capabilityOn, needsOf } from './permissions';
import { chosenPack, packsAndPieces, packsAndPiecesMilli, samePack, unitWords, type PackSize } from './units-state';
import { useStaff } from '../context';
import { BusyButton } from '../../../app/ui/BusyButton';
import { WORDS, plural } from '../../../app/glossary';
import { icons } from '../../../components/icons';
import { NewItemHere, type AddedItem } from './new-item-here';
import { ScreenToast, useItemAdded } from './toast';
import { entryWarnings } from './entry-checks-state';
import { useEntryChecks, useEntryNorms } from './entry-checks';
import { SuccessScreen } from '../../../app/ui/SuccessScreen';
import { HelpTip } from '../../../app/ui/HelpTip';
import { UnfinishedCounts, savingCount, unfinishedKey } from './unfinished-counts';
import { refusedForCertain } from './quick-add';
import { NextSteps, mayCountAt } from './next-steps';
import { branchLabel } from '../../../auth/branch';
import { changeWords, clearCount, countKey, otherTabWords, readCount, savedAt, saveWords, useCountSave, whenSaved, type CountState } from './count-save-state';

// Stock-take (FR-RET-08): count the products of a branch, review the variance the server works
// out, then commit to write the adjustments. Only counted rows are sent.

/** The variance written at commit once committed, otherwise the draft's (counted less expected). */
const variance = (l: StocktakeLine): string => l.committed_variance_qty ?? l.variance_qty ?? '0';
/** Said when another tab committed this count while this tab's review was on its way or open (review of #686). */
export const CHANGED_ELSEWHERE = 'This count was just saved in another tab. Check what is left here, then review again.';

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
      <h2>{stocktake.status === 'committed' ? 'Stock count saved' : stocktake.status === 'discarded' ? 'Stock count cancelled' : 'Review the difference'}</h2>
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
          {open > 0 && <p className="hint" id={hintId}>{open === 1 ? 'Tick the line marked above before you save the count.' : `Tick the ${open} lines marked above before you save the count.`}</p>}
          {/* Issue #728: the word a shop worker knows, and that nothing changes before it. */}
          <span className="next-step">
            <BusyButton type="button" className="rt-primary" busy={busy === true} notReady={open > 0}
              aria-describedby={open > 0 ? hintId : undefined} onClick={() => onCommit(changed.flatMap((l, i) => (l.product_id && ticked.has(keyOf(l, i)) ? [l.product_id] : [])))}>
              Save the count
            </BusyButton>
            <HelpTip tip="saveCount" />
          </span>
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

function CountSheet({ branchId, item, from, reopen }: { branchId: string; item?: string; from?: string; reopen?: string }) {
  const queryClient = useQueryClient();
  const { me, chooseBranch } = useStaff();
  // Issue #700: once a count is committed, Count another branch offers the others that keep goods where this person counts.
  const others = (me.branches ?? []).filter((b) => b.id && b.id !== branchId && mayCountAt(me, b.id));
  const [choosing, setChoosing] = useState(false);
  // Issue #681: a count in progress is kept on this device for this business, branch and person, and comes back on a reload.
  const saveKey = countKey(typeof window === 'undefined' ? '' : window.location.host, branchId, me.user_id ?? me.email ?? 'staff');
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  const search = useLiveSearch();
  // Issue #681: a count in progress is kept on this device for this business, branch and person, and comes back on a reload.
  const [restored] = useState(() => readCount(saveKey));
  // Owner, round 3 of the review of #686: counts brought back on opening say when they were saved, in the business's zone.
  // Those of the same business day are filled in with a line saying so; those of an earlier day wait for Use them.
  const [kept, setKept] = useState(() => { const t = savedAt(restored); return t === null ? null : whenSaved(t, businessZone()); });
  const [goneElsewhere, setGoneElsewhere] = useState(false);
  const [asking, setAsking] = useState(() => kept?.earlier === true);
  const [keptLine, setKeptLine] = useState(() => kept !== null && !kept.earlier);
  const [clearing, setClearing] = useState(false);
  const [first] = useState(() => (kept?.earlier ? null : restored));
  const [counts, setCounts] = useState<Record<string, string>>(first?.counts ?? {});
  // Issue #650: the cartons typed for an item with a standard pack, with the pack they were typed in, so a pack changed or
  // removed while counting never reads the same number in another size; its pieces stay in counts, the total is what is sent.
  const [packCounts, setPackCounts] = useState<Record<string, { text: string; pack: PackSize }>>(first?.packCounts ?? {});
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
  // The reviews this sheet made (issue #728), told from a count opened with Finish it.
  const madeHere = useRef(new Set<string>());
  // The line the last scan counted, marked so the counter sees which one went up; the cursor stays in the Scan box.
  const [scanned, setScanned] = useState<string | null>(null);
  // The items on the count, in the order they were picked or scanned (issue #534): the sheet is searched, not shown whole.
  const [listed, setListed] = useState<string[]>(first?.listed ?? []);
  const list = (id: string) => setListed((l) => (l.includes(id) ? l : [...l, id]));
  // The lines whose count is entered, in the order they were done; and the question asked before a count with lines left.
  const [done, setDone] = useState<string[]>(first?.done ?? []);
  // The question answers the counts it was asked about: it shows only while the counts are those it was asked on, so any
  // change to a count withdraws it in the same render and Review the difference anyway never sends counts it was not asked about (#535).
  const [askedOn, setAskedOn] = useState<Record<string, string> | null>(null);
  // A picked item's count box takes the cursor, after the picker has put it back in the search (review of #535).
  const [focusOn, setFocusOn] = useState<{ id: string; pick: number } | null>(null);
  useEffect(() => {
    if (focusOn) (document.getElementById(`count-${focusOn.id}-packs`) ?? document.getElementById(`count-${focusOn.id}`))?.focus();
  }, [focusOn]);
  const stock = useQuery({ queryKey: ['retail', 'stock', branchId, '', false], queryFn: () => countableStock(branchId) });
  // Issue #700: Count it now brings the item just added; once the sheet is read it heads the list, its box focused to type.
  const brought = useRef(false);
  useEffect(() => {
    if (brought.current || !item || !stock.data?.some((r) => r.product_id === item)) return;
    brought.current = true;
    setListed((l) => (l.includes(item) ? l : [...l, item]));
    setScanned(item);
    setFocusOn({ id: item, pick: 1 });
  }, [item, stock.data]);
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
  // The last commit of this count that this tab had taken in when the review was asked for (review of #686), never the one in
  // storage: a tab that missed another tab's commit would otherwise review and commit counts that already went in. A review that
  // comes back after the mark moved, or a Commit pressed after it, would take the same difference again, so both check it.
  const reviewMark = useRef(0);
  const markMoved = () => (readCount(saveKey)?.committed ?? 0) > reviewMark.current;
  // toast-exempt: saves nothing: it opens the review of the count and changes no stock; Save the count after it says Stock updated
  const review = useMutation({
    mutationFn: async () => {
      // Storage holds a commit this tab missed: it is taken in first, so the sheet drops what went in, and no review is made.
      if (saving.catchUp()) return null;
      reviewMark.current = saving.heardCommit();
      return retail.createStocktake({ branch_id: branchId, lines: valid });
    },
    onSuccess: (st) => {
      if (!st) return show(CHANGED_ELSEWHERE, 'info');
      madeHere.current.add(st.id ?? '');
      // Issue #730: a save that failed on the last review says nothing about this one.
      commit.reset();
      if (!markMoved()) return setDraft(st);
      // Committed in another tab while this review was on its way: the review is thrown away, never shown.
      void retail.discardStocktake(st.id ?? '').catch(() => undefined);
      show(CHANGED_ELSEWHERE, 'info');
    },
  });
  // Issue #681: the same count changed in another tab is merged in item by item; committed there, this one starts empty.
  const apply = useCallback((next: CountState | null) => {
    current.current = next?.counts ?? {};
    setCounts(next?.counts ?? {}); setPackCounts(next?.packCounts ?? {}); setListed(next?.listed ?? []); setDone(next?.done ?? []);
    if (!next) setDraft((d) => (d?.status === 'draft' ? null : d));
  }, []);
  const saving = useCountSave({ saveKey, restored, saved: { counts, packCounts, listed, done }, apply, hold: asking });
  // A commit in another tab ends a review here (review of #686): its expected stock is gone, so committing it would take the
  // same difference again. Back on the sheet the person sees what went in there and reviews what is left afresh.
  const otherKind = saving.other?.kind;
  useEffect(() => {
    if (otherKind === 'committed' || otherKind === 'partly') setDraft((d) => (d?.status === 'draft' ? null : d));
  }, [otherKind, saving.other]);
  /** Takes in the counts of an earlier day the person chose to use. */
  const useThem = () => {
    const taken = saving.takeStored();
    const t = savedAt(taken);
    // Dated from the count as taken now, which another tab may have added to today; gone, the page says so.
    setKept(t === null ? null : whenSaved(t, businessZone()));
    setGoneElsewhere(t === null);
    setAsking(false);
    setKeptLine(t !== null);
  };
  /** Clears this sheet and its saved count (Start again), here and in another tab of it; stock does not change. */
  const startAgain = () => {
    clearCount(saveKey);
    apply(null);
    setConfirming(false); setAsking(false); setKeptLine(false); setClearing(false);
  };
  const savedWords = saveWords(valid.length, saving.onDevice);
  // A restored count for an item no longer on the sheet is dropped once the sheet is read, so the device never keeps it.
  const pruned = useRef(restored === null);
  useEffect(() => {
    // A count taken with Use them is pruned once the question is answered.
    if (pruned.current || !onSheet || asking) return;
    pruned.current = true;
    const keep = <T,>(m: Record<string, T>) => Object.fromEntries(Object.entries(m).filter(([id]) => onSheet.has(id)));
    setCounts((c) => keep(c)); setPackCounts((c) => keep(c)); setListed((l) => l.filter((id) => onSheet.has(id))); setDone((d) => d.filter((id) => onSheet.has(id)));
  }, [onSheet, asking]);
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
  // success-screen: the review turns into Stock updated for N items, with the toast issue #681 asks for too;
  // setDone there is the list of counted items, not a success note
  const commit = useMutation({
    mutationFn: (acknowledged: string[]) => savingCount(draft?.id ?? '', retail.commitStocktake(draft?.id ?? '', acknowledged)),
    onSuccess: (st) => {
      // Issue #353: a count is saved when it is committed, so that is when its confirmed warnings are recorded.
      entry.saved();
      setDraft(st);
      // Issue #681: the count is done, so this device keeps nothing more of it, another tab of it hears so, and the screen says so.
      // Only the counts that went in leave the page; one changed or added since the review, here or in another tab, stays.
      // A count opened with Finish it never held what is typed on this sheet, so the sheet keeps all of it (issue #730).
      if (madeHere.current.has(st.id ?? '')) {
        saving.committed();
        const sent = new Map((st.lines ?? []).map((l) => [l.product_id ?? '', milliOf(l.counted_qty ?? '0')]));
        const left = (id: string) => !sent.has(id) || parseCount(totals[id] ?? '') !== sent.get(id);
        const keep = <T,>(m: Record<string, T>) => Object.fromEntries(Object.entries(m).filter(([id]) => left(id)));
        setCounts(keep(counts)); setPackCounts(keep(packCounts)); setListed(listed.filter(left)); setDone(done.filter(left));
        setKeptLine(false);
      }
      setConfirming(false);
      const n = st.lines?.length ?? 0;
      show(`Stock updated for ${n} ${n === 1 ? 'item' : 'items'}`, 'success');
      // Everything retail is read again, the list of counts not saved among it (issue #728).
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void queryClient.invalidateQueries({ queryKey: unfinishedKey(branchId) });
    },
  });
  const units = new Map((stock.data ?? []).map((r) => [r.product_id ?? '', r.unit]));
  // JUSTIFICATION-A3: Finish it and Back to the count of issue #728 belong to the sheet that holds the review.
  // Issue #728: Finish it shows the review of a count left unsaved, here or from My stock, ready to save.
  // Issue #730: it is measured against the commits this tab has heard, as a review made here is, so a count saved here earlier
  // never reads as saved in another tab; and a failed save of an earlier review says nothing about it.
  const { reset: resetCommit } = commit;
  const openDraft = useCallback((id: string) => {
    retail.getStocktake(id).then(
      (st) => {
        if (st.status !== 'draft') return show('That count is no longer open. Count again.', 'info');
        saving.catchUp();
        reviewMark.current = saving.heardCommit();
        resetCommit();
        setDraft(st);
      },
      () => show('That count could not be opened. Try again.', 'error'),
    );
  }, [show, saving, resetCommit]);
  const finishing = useRef(false);
  useEffect(() => {
    if (!reopen || finishing.current) return;
    finishing.current = true;
    openDraft(reopen);
  }, [reopen, openDraft]);
  // Issue #728: a review made here and left with Back to the count is thrown away, so it never shows as a count not finished;
  // the next review is a new one. A count opened with Finish it stays as it was, and so does one whose save was sent (review of
  // #729): a discard that overtook it would cancel the save the person asked for. Only a save whose answer was lost may have
  // landed; one the server refused for certain did not, so its review is thrown away too (issue #730).
  const backToCount = () => {
    if (commit.isPending) return;
    const mayHaveLanded = commit.isError && !refusedForCertain(commit.error);
    if (draft?.id && madeHere.current.has(draft.id) && !mayHaveLanded) void retail.discardStocktake(draft.id).then(() => queryClient.invalidateQueries({ queryKey: unfinishedKey(branchId) }), () => undefined);
    setDraft(null);
  };

  if (draft?.status === 'committed') {
    const n = draft.lines?.length ?? 0;
    const { summary, changes } = changeWords(draft.lines ?? [], units);
    return (
      <>
        <SuccessScreen level={2} title={`Stock updated for ${n} ${n === 1 ? 'item' : 'items'}`}
          detail={<>{summary}{changes.map((c, i) => <Fragment key={i}><br />{c}</Fragment>)}</>}
          actions={[
            { label: 'Start another count', onClick: () => setDraft(null) },
            { label: 'Back to My stock', to: '/staff/retail/stock' },
            ...(chooseBranch && others.length > 0
              ? [{ label: 'Count another branch', onClick: () => (others.length === 1 ? chooseBranch(others[0]?.id ?? '') : setChoosing(true)) }]
              : []),
          ]} />
        {choosing && chooseBranch && (
          <div role="group" aria-label="Count another branch" className="next-steps">
            {others.map((b) => <button key={b.id} type="button" onClick={() => chooseBranch(b.id ?? '')}>{`Count stock at ${branchLabel(b)}`}</button>)}
          </div>
        )}
        {toast}
      </>
    );
  }
  if (draft) {
    return (
      <>
        <StocktakeReview key={draft.id} stocktake={draft} onCommit={(acknowledged) => {
          if (!markMoved()) return commit.mutate(acknowledged);
          // Another tab committed since this review was made, and this tab has not heard yet: that commit is taken in, so the
          // sheet drops what went in, and the review ends instead.
          saving.catchUp();
          setDraft(null);
          show(CHANGED_ELSEWHERE, 'info');
        }} busy={commit.isPending} units={units} packs={packOf} />
        <Problem error={commit.error} />
        {draft.status === 'draft' && !commit.isPending && <button type="button" onClick={backToCount}>Back to the count</button>}
      </>
    );
  }
  // Review of #686, round 4: the question about an earlier day stands alone, so nothing is typed, unsaved, before it is answered.
  if (asking && kept) {
    return (
      <div className="alert alert-warning" role="group" aria-label="Counts from an earlier day" data-testid="count-earlier">
        <p>{`You have counts from ${kept.day}. Use them or start again?`}</p>
        <p className="cluster">
          <button type="button" className="btn-sm" onClick={useThem}>Use them</button>
          <button type="button" className="btn-sm" onClick={startAgain}>Start again</button>
        </p>
      </div>
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
          <PacksAndPiecesFields id={`count-${id}`} nameId={`count-${id}-name`} unit={r.unit} pack={{ unit: pack.unit, factor: pack.factor, levels: pack.levels }} totalWord="Counted"
            packs={typed?.text ?? ''} pieces={counts[id] ?? ''} onBlur={() => settle(id)} onPiecesKeyDown={enter} after={zero}
            onPacks={(v) => setPackCounts((c) => ({ ...c, [id]: { text: v, pack: c[id] && c[id].text.trim() !== '' ? c[id].pack : { unit: pack.unit, factor: pack.factor, levels: pack.levels } } }))}
            onPieces={(v) => setCounts((c) => ({ ...c, [id]: v }))} />
          {typed && typed.text.trim() !== '' && !samePack(typed.pack, r.standard_pack) && (
            <p className="hint">{`Your count uses ${unitWords(pack.unit).toLowerCase()} of ${showQty(pack.factor ?? '')}, as when you started. This item's pack has changed since.`}</p>
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
      <UnfinishedCounts branchId={branchId} onFinish={openDraft} />
      <Note>Find or scan each item you count, or work down the list Not counted yet. Type how many you counted, or 0 for an item that is gone. An item kept in cartons takes cartons and pieces, or pieces alone. Leave an item empty if you did not count it.</Note>
      {/* Issue #681: the person counting always sees that their work is kept, and where. */}
      <p className="hint count-saved-all" role="status" data-testid="count-saved-all">{savedWords}</p>
      {saving.other && <p className="hint" data-testid="count-other-tab">{otherTabWords(saving.other)}</p>}
      {goneElsewhere && valid.length === 0 && <p className="hint" data-testid="count-gone">Those counts were saved or cleared in another tab, so this count starts empty.</p>}
      {keptLine && kept && valid.length > 0 && (
        <div className="alert alert-info" data-testid="count-kept">
          <p>{kept.earlier ? `Counted on ${kept.day} at ${kept.time}` : `Counted today at ${kept.time}`}</p>
          {clearing ? (
            <p className="cluster">
              <span>{`Clear all ${valid.length} ${valid.length === 1 ? 'count' : 'counts'}? Your stock does not change.`}</span>
              <button type="button" className="btn-sm" onClick={startAgain}>Clear the counts</button>
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
        <NewItemHere id="count-new" counting askCost={profit} qtyLabel="How many you counted" onAdded={addNew}
          at={{ value: branchId, lead: 'Counted at', places: (me.branches ?? []).filter((b) => b.stocks_goods !== false && mayCountAt(me, b.id)) }} />
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
      {from === 'items' && <NextSteps at="countFromAdd" steps={[{ label: 'Back to Items', screen: 'products', to: '/staff/retail/catalogue/products' }]} />}
      {toast}

    </>
  );
}

export function StocktakePage() {
  const { branchId } = useStockingBranch();
  const { item, from, finish } = (useRouter({ warn: false })?.state.location.search ?? {}) as { item?: string; from?: string; finish?: string };
  // The item and where it came from belong to the branch the count first opened at: Count another branch starts a plain count.
  const [opened, setOpened] = useState<string | null>(branchId);
  if (opened === null && branchId !== null) setOpened(branchId);
  const fresh = branchId !== null && branchId === (opened ?? branchId);
  return (
    <Gate screen="stocktake" title={WORDS.countStock}>
      {branchId === null ? (
        <BranchRequired permissions={needsOf('stocktake')} stocking />
      ) : (
        <>
          {/* Keyed by branch, so a branch switch starts afresh from that branch's own saved count (review of #681). */}
          <CountSheet key={branchId} branchId={branchId} item={fresh && typeof item === 'string' ? item : undefined} from={fresh && typeof from === 'string' ? from : undefined}
            reopen={fresh && typeof finish === 'string' ? finish : undefined} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stocktake')({ component: StocktakePage });
