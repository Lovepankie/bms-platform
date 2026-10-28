import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useNavigate, useSearch } from '@tanstack/react-router';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { SEARCH_DELAY_MS, createDebouncer } from '../../../app/ui/useLiveSearch';
import { Stepper } from '../../../app/ui/Stepper';
import { RetailError, retail, type Product, type ResolvedLine, type Sale, type SalePayment, type SaleRequest } from '../../../api/retail';
import { mayKeepOnPhone, offlineProblem, offlineSale } from '../../../offline/catalogue';
import { useOffline, useOnline } from '../../../offline/offline-context';
import { OfflineNote, OfflineProductPicker, ProvisionalReceipt } from '../../../offline/offline-sale';
import type { OutboxItem } from '../../../offline/outbox';
import { newIdempotencyKey } from './idempotency';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { bigDrop, parseQty, qtyString, showQty } from './maths';
import { buildSaleRequest, defaultPrice, draftProblem, draftTotal, lineFigures, lineHint, newLine, resolveRequestOf, withUnit, type Accounts, type Draft, type DraftLine, type Pricing } from './sale-state';
import { MIN_REASON, hasNoPrice, isOverride, pricedLines, receiptNote, reasonOk, sourceNote } from './price-state';
import { chosenPack, enteredPriceMinor, enteredQty, sellingPacks } from './units-state';
import { EmptyState } from '../../../components/states';
import { BranchRequired, Gate, NoStockHere, Note, ProductPicker, Problem, money, useBranchRoles, useProfitAccess, useStockingBranch } from './ui';
import { branchLabel } from '../../../auth/branch';
import type { BranchRole } from '../../../api/retail';
import { BRANCH_ROLES, CREDIT_OVERRIDE, CUSTOMER_ACCOUNTS, PRICELIST_MANAGE, PRICELIST_USE, PRICE_LISTS, PRICE_OVERRIDE, UNITS, branchesWhere, capabilityOn, holds, holdsAt, needsOf, sellingBranches } from './permissions';
import { exceededBy, isLimitRefusal, limitWords, termsWords } from './account-state';
import { SaleDocuments } from './documents-ui';
import { ShareReceipt, useShareReady } from './receipt-share';
import { ChangePanel, ReceiptChange } from './sale-change';
import { receivedFor, receivedProblem } from './sale-change-state';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne, scanInto, touchedLine } from './barcode-state';
import { ScreenToast, addedMessage, useToast } from './toast';
import { NewItemHere, type AddedItem } from './new-item-here';
import { ActionBar } from '../../../app/ui/ActionBar';
import { showDate } from './maths';
import { Hint } from '../../../app/ui/Hint';
import { WORDS } from '../../../app/glossary';
import { useUnsavedWork } from '../../../app/unsaved-work';
import { entryWarnings, type EntryWarning } from './entry-checks-state';
import { useEntryChecks, useEntryNorms } from './entry-checks';
import { FirstItemsCard, useNoItemsYet } from './first-items';
import { EmptyChoice, showsEmptyChoice } from '../../../app/ui/EmptyChoice';
import { ScreenLink, addOn } from './screen-link';
import { dropWords } from './stocktake';
import { useBranchHold } from '../branch-picker';
import { icons } from '../../../components/icons';

// Record a sale (FR-RET-04, FR-RET-05). One idempotency key per draft, kept with the draft in this
// tab until the sale is saved or the draft is cleared: a double tap, a retry after a lost answer or a
// reload posts once (#77). After saving, the receipt summary replaces the form.

const METHODS: { value: SalePayment; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
  { value: 'credit', label: 'Credit (pay later)' },
];

/** What someone who may not add items reads under a search that found nothing (issue #615). */
export const ASK_OWNER_ITEM = 'Ask the owner to add it.';

/** The value once it has stopped changing for the search delay, with the shared debouncer of the live search. */
function useSettled<T>(value: T): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const debouncer = createDebouncer(SEARCH_DELAY_MS, setSettled);
    debouncer.push(value);
    return debouncer.cancel;
  }, [value]);
  return settled;
}

export function Receipt({ sale, received }: { sale: Sale; /** Cash the customer gave, for the change line; display only. */ received?: number }) {
  const canProfit = useProfitAccess();
  return (
    <section aria-label="Sale receipt">
      <h2>Sale saved</h2>
      <p>
        Paid by {METHODS.find((m) => m.value === sale.payment_method)?.label ?? sale.payment_method} on {showDate(sale.sale_date)}
        {sale.buyer_name ? `, buyer ${sale.buyer_name}` : ''}
      </p>
      <div className="table-wrap" tabIndex={0}><table>
        <thead>
          <tr>
            <th>Item</th>
            <th className="num">Qty</th>
            <th className="num">Price</th>
            <th className="num">Total</th>
          </tr>
        </thead>
        <tbody>
          {(sale.lines ?? []).map((l) => (
            <tr key={l.product_id}>
              <td>
                {l.description}
                {l.price && <small className="hint" data-testid="price-note"><br />{receiptNote(l, money)}</small>}
              </td>
              <td className="num">{enteredQty(l)}</td>
              <td className="num">{money(enteredPriceMinor(l))}</td>
              <td className="num">{money(l.line_total_minor ?? 0)}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
      <p className="rt-total">Total {money(sale.total_minor ?? 0)}</p>
      <ReceiptChange sale={sale} received={received} />
      {(sale.balance_minor ?? 0) > 0 && (
        <p>
          Still to pay: <strong>{money(sale.balance_minor ?? 0)}</strong>
          {sale.due_date ? ` by ${showDate(sale.due_date)}` : ''}
        </p>
      )}
      {canProfit && sale.profit_minor !== undefined && <p>Profit on this sale: {money(sale.profit_minor)}</p>}
    </section>
  );
}

/** A short buzz on a phone that has one when an item goes on the sale (#508); nothing elsewhere. */
export function tapFeedback() {
  if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') navigator.vibrate(10);
}

/** The sale's one primary action (#508): the kit's ActionBar, its button carrying the live total. */
export function SaleBar({ children }: { children: ReactNode }) {
  return (
    <ActionBar className="sale-bar" testId="sale-bar">
      {children}
    </ActionBar>
  );
}

/**
 * One line of the sale being made. The unit choice is drawn only while the tenant has the units capability
 * (`unitsOn`) and the item has active pack sizes; otherwise the line is exactly what it was before packs.
 */
export function SaleLineCard({ line, index, unitsOn, price, offlinePrice, fresh, slide = false, notes, onFlashed, onQty, onPrice, onUnit, onReason, onReset, onRemove }: {
  /** Set on the line just added or topped up, a new number each time: it flashes once (Hillary, 23 Oct 2026). */
  fresh?: number;
  /** Set while the line just went on the sale as a new line: it slides in (#508). */
  slide?: boolean;
  /** Called when the flash has run, so it never stays on or moves to another line. */
  onFlashed?: () => void;
  line: DraftLine;
  index: number;
  unitsOn: boolean;
  /** Present while price lists are on: how the line was priced, and what the seller may do about it. */
  price?: { resolved?: ResolvedLine; listName?: string; canOverride: boolean };
  /**
   * Present while selling offline (ADR-067 decision 11): the price is the one saved on this phone, and only a holder of
   * retail.price.override may type another, with a reason, as online.
   */
  offlinePrice?: { canType: boolean };
  /** Issue #353: the entry warnings of this line, shown under its quantity and price. */
  notes?: ReactNode;
  onQty: (v: string) => void;
  onPrice: (v: string) => void;
  onUnit: (unitId: string) => void;
  onReason?: (v: string) => void;
  onReset?: () => void;
  onRemove: () => void;
}) {
  const i = index;
  const f = lineFigures(line);
  const hint = lineHint(line);
  const packs = unitsOn ? sellingPacks(line.product) : [];
  const pack = unitsOn ? chosenPack(line.product, line.unitId) : undefined;
  const override = price ? isOverride(line, price.resolved) : false;
  const noPrice = price ? hasNoPrice(price.resolved) : false;
  const changedOffline = offlinePrice !== undefined && f !== null && f.priceMinor !== defaultPrice(line.product, line.unitId);
  return (
    <div className={`rt-card line-card${slide ? ' line-new' : ''}`}>
      {fresh !== undefined && <span key={fresh} className="line-added" aria-hidden="true" onAnimationEnd={onFlashed} />}
      <strong>{line.product.description}</strong>
      {packs.length > 0 && (
        <div>
          <label htmlFor={`unit-${i}`}>Sell by</label>
          {/* empty: drawn only when the item has packs; its own unit is always there. */}
          <select id={`unit-${i}`} data-tour="sale-unit" value={pack?.unit_id ?? ''} onChange={(e) => onUnit(e.target.value)}>
            <option value="">{line.product.unit}</option>
            {packs.map((k) => <option key={k.unit_id} value={k.unit_id}>{k.unit} ({showQty(k.factor ?? '')} {line.product.unit})</option>)}
          </select>
        </div>
      )}
      <div className="rt-row">
        <Stepper id={`qty-${i}`} label={`Quantity (${pack?.unit ?? line.product.unit})`} name={line.product.description} value={line.qty} onChange={onQty} min={1} inputMode="decimal" invalid={f === null || hint !== null} />
        <div>
          <label htmlFor={`price-${i}`}>{pack ? `Price per ${pack.unit}` : 'Unit price'}</label>
          <input id={`price-${i}`} inputMode="numeric" value={line.price} onChange={(e) => onPrice(e.target.value)} aria-invalid={f === null} readOnly={price ? !price.canOverride : offlinePrice ? !offlinePrice.canType : false} />
        </div>
      </div>
      {notes}
      {price && price.resolved && (
        <p className="hint" data-tour="sale-price-source" data-testid={`price-source-${i}`}>
          {sourceNote(price.resolved, price.listName)}
          {override && ` Changed from ${money(price.resolved.unit_price_minor ?? 0)}.`}
        </p>
      )}
      {noPrice && <p role="alert" className="rt-flag">This item has no price in the list.</p>}
      {price && !price.canOverride && <Hint full>The price comes from the price list. You do not have permission to change it.</Hint>}
      {offlinePrice && !offlinePrice.canType && <Hint full>Offline, the price saved on this phone is used. You do not have permission to change it.</Hint>}
      {changedOffline && !offlinePrice?.canType && onReset && <p><button type="button" className="btn-sm" onClick={onReset}>Use the price saved on this phone</button></p>}
      {(override || (changedOffline && offlinePrice?.canType)) && onReason && (
        <div>
          <label htmlFor={`reason-${i}`}>Why is the price changed? (at least {MIN_REASON} characters)</label>
          <input id={`reason-${i}`} value={line.reason ?? ''} maxLength={300} onChange={(e) => onReason(e.target.value)} aria-invalid={!reasonOk(line.reason)} />
        </div>
      )}
      {line.typed && onReset && <p><button type="button" className="btn-sm" onClick={onReset}>Use the list price</button></p>}
      {hint && <p role="alert" className="rt-flag">{hint}</p>}
      <p className="line-foot">
        {f ? `Line total ${money(f.totalMinor)}` : <span className="rt-flag">Check quantity and price</span>}{' '}
        <button type="button" className="btn-ghost" onClick={onRemove} aria-label={`Remove ${line.product.description}`}>
          Remove
        </button>
      </p>
    </div>
  );
}

/** The sale form's draft: once sent with a client sale id, the request and when it was sent (ADR-067 decision 5). */
type SaleDraft = Draft & { sent?: { body: SaleRequest; at: string } };

export function SaleForm({ branchId, branchName = '', onSaved, onQueued, startItem, onStarted }: {
  branchId: string;
  branchName?: string;
  /** Issue #632, Sell it: the item Quick add just made, put on the sale once the form opens. */
  startItem?: string;
  /** Told once the start item is on the sale, or could not be put on it. */
  onStarted?: () => void;
  onSaved?: (sale: Sale, received?: number) => void;
  /** ADR-067: the sale was kept on this phone to send later. */
  onQueued?: (item: OutboxItem) => void;
}) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  // ADR-067: with no network a cash, mobile money or bank sale is kept on this phone; online the form is as before.
  const offline = useOffline();
  const online = useOnline();
  const offlineMode = offline !== null && !online;
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<SaleDraft>(`sale:${me.user_id ?? ''}:${branchId}`, {
    branchId, method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [],
  });
  const [addingBuyer, setAddingBuyer] = useState(draft.newBuyerName !== '');
  // What the customer handed over (cash only): kept in this form's state, never in the draft, so it goes with the form (#346).
  const [received, setReceived] = useState('');
  // Clearing a sale whose answer was lost is asked first: it may already be recorded (ADR-067 decision 5).
  const [confirmClear, setConfirmClear] = useState(false);

  const pricesOn = capabilityOn(me, 'retail', PRICE_LISTS) && !offlineMode;
  const customers = useQuery({ queryKey: ['retail', 'customers'], queryFn: () => retail.listCustomers(), enabled: !offlineMode && (draft.method === 'credit' || pricesOn) });
  // Reading the lists needs retail.pricelist.manage too (a list is a customer's negotiated price); the checks are the server's, per branch.
  const canChooseList = pricesOn && holdsAt(me, PRICELIST_USE, branchId) && holds(me, PRICELIST_MANAGE);
  const lists = useQuery({ queryKey: ['retail', 'price-lists'], queryFn: () => retail.listPriceLists(), enabled: canChooseList });
  // Price lists on: the server prices every line (the customer's list, the group's, the quantity break) and the form shows it.
  const asked = pricesOn ? JSON.stringify(resolveRequestOf(draft)) : 'null';
  const settled = useSettled(asked);
  const resolution = useQuery({
    queryKey: ['retail', 'prices', settled],
    queryFn: () => retail.resolvePrices(JSON.parse(settled)),
    enabled: settled !== 'null',
    retry: false,
  });
  const checking = asked !== 'null' && (settled !== asked || resolution.isFetching);
  const resolved = pricesOn && asked !== 'null' && !checking ? resolution.data?.lines : undefined;
  const pricing: Pricing | undefined = pricesOn ? { resolved, checking } : undefined;
  const shown: Draft = { ...draft, lines: pricedLines(draft.lines, resolved) };
  // Customer accounts on: a credit sale names a buyer from the list; the buyer's terms date it when no date is typed.
  const accountsOn = capabilityOn(me, 'retail', CUSTOMER_ACCOUNTS);
  const buyerRow = (customers.data ?? []).find((c) => c.id === draft.customerId);
  const accounts: Accounts | undefined = accountsOn ? { termsDays: buyerRow?.terms_days } : undefined;

  // The client sale id is the draft's key, so a sale whose answer was lost and that is kept to send later is never twice.
  // The request is kept with the draft once sent: until the server answers, the same request is all that can go again.
  const save = useMutation({
    mutationFn: (body: SaleRequest) => retail.createSale(body, key),
    onError: (e) => {
      // A clear answer means the sale was not recorded, so the form opens again.
      if (e instanceof RetailError) setDraft((d) => ({ ...d, sent: undefined }));
    },
    onSuccess: (sale) => {
      entry.saved();
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail', 'products'] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'stock'] });
      // Home's shop figures (issue #485) count the sale at once.
      void queryClient.invalidateQueries({ queryKey: ['retail', 'home'] });
      onSaved?.(sale, receivedFor(draft.method, sale.total_minor ?? 0, received));
    },
  });

  const addBuyer = useMutation({
    mutationFn: () => retail.createCustomer({ name: draft.newBuyerName.trim(), ...(draft.newBuyerContact.trim() ? { contact: draft.newBuyerContact.trim() } : {}) }),
    onSuccess: (c) => {
      void queryClient.invalidateQueries({ queryKey: ['retail', 'customers'] });
      setDraft((d) => ({ ...d, customerId: c.id ?? '', newBuyerName: '', newBuyerContact: '' }));
      setAddingBuyer(false);
    },
  });
  const update = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));
  const setLine = (i: number, patch: { qty?: string; price?: string; typed?: boolean; reason?: string }) =>
    setDraft((d) => ({ ...d, lines: d.lines.map((l, n) => (n === i ? { ...l, ...patch } : l)) }));
  const setUnit = (i: number, unitId: string) =>
    setDraft((d) => ({ ...d, lines: d.lines.map((l, n) => (n === i ? withUnit(l, unitId) : l)) }));
  const unitsOn = capabilityOn(me, 'retail', UNITS);
  // An item can be on the sale twice (cartons and loose pieces) only while it has pack sizes to choose from.
  // Every add says so on screen (Hillary, 23 Oct 2026): a short toast, and the new line flashes once.
  const { show, toast } = useToast(2500);
  // The line just added or topped up flashes once; `n` changes on every add so a repeat scan flashes again. A line just
  // appended (`created`) also slides in (#508).
  const [fresh, setFresh] = useState<{ index: number; n: number; created: boolean } | null>(null);
  // Counts the adds and top-ups, so the count beside Items pops on each and never on a removal (#508).
  const [pops, setPops] = useState(0);
  // The lines as of the last add, so two taps or scans before React renders again see each other.
  const lines = useRef(draft.lines);
  lines.current = draft.lines;
  const mark = (index: number, created: boolean) => {
    tapFeedback();
    setPops((n) => n + 1);
    setFresh((f) => ({ index, n: (f?.n ?? 0) + 1, created }));
  };
  const add = (p: Product) => {
    const now = lines.current;
    if (now.some((l) => l.product.id === p.id) && !(unitsOn && sellingPacks(p).length > 0)) {
      show(`${p.description} is already on the sale. Change its quantity below.`, 'info');
      return;
    }
    const next = [...now, newLine(p)];
    lines.current = next;
    setDraft((d) => ({ ...d, lines: next }));
    mark(next.length - 1, true);
    show(addedMessage(p.description, next[next.length - 1]?.qty), 'success');
  };
  // Issue #632, Sell it: the item Quick add made goes on the sale once, read with what this branch holds, as a search would.
  const started = useRef(false);
  useEffect(() => {
    if (!startItem || started.current) return;
    started.current = true;
    void retail.getProduct(startItem)
      .then(async (item) => {
        const found = await retail.listProducts({ query: item.code ?? item.description ?? '', branchId });
        const p = found.find((x) => x.id === startItem);
        // Not in what this branch's list returns (issue #641): said, never dropped in silence.
        if (p) add(p);
        else show(`${item.description ?? 'The item'} did not come up here. Search for it to add it.`, 'info');
      })
      .catch(() => show('The item could not be read. Search for it to add it.', 'error'))
      .finally(() => onStarted?.());
    // Once per form: a later change of the search parameter is the route's, not a second add.
  }, [startItem]);
  // Issue #615: an item the search did not find, added here. The server never sells what the branch does not hold, so a new
  // item goes on the sale only with stock: whoever may count stock here types how many there are (more than 0), kept as a
  // one-line stock count; anyone else is told to put stock in first. Only an item made here is counted: an existing item chosen
  // from a name warning (Use this item) keeps its stock and goes on the sale as a picked one would.
  const profit = useProfitAccess();
  const mayCount = holdsAt(me, 'retail.stocktake.commit', branchId);
  // Issue #656: a count that would be a big drop asks its tick before any draft is made. Each item has its own tick, its own
  // count and its own press. A draft made on Commit the count and not committed (its answer lost) is discarded when the sale is
  // cleared, the screen left or the branch switched; offline, Clear this sale, Not now and the branch switch wait until it is.
  const [held, setHeld] = useState<HeldCount[]>([]);
  const drop = (id: string | undefined) => setHeld((l) => l.filter((x) => x.product.id !== id));
  // The drafts made here and not committed or discarded yet, by item.
  const drafts = useRef(new Map<string, string>());
  const [stuck, setStuck] = useState(false);
  // A count pressed before Clear this sale (`round`) or before the screen closed (`gone`; a branch switch makes this form again)
  // is stale: its answer is ignored and its draft discarded.
  const round = useRef(0);
  const gone = useRef(false);
  useEffect(() => {
    gone.current = false;
    return () => {
      gone.current = true;
      for (const id of drafts.current.values()) discardCount(id);
      drafts.current.clear();
    };
  }, []);
  // A discard that could not be sent (the screen left offline, a reload) is sent again once Sell is open and online.
  useEffect(() => {
    if (!online) return;
    setStuck(false);
    const live = [...drafts.current.values()];
    for (const id of keptCounts()) if (!live.includes(id)) discardCount(id);
  }, [online]);
  const holdsDraft = () => {
    if (drafts.current.size === 0 || online) return false;
    setStuck(true);
    return true;
  };
  useBranchHold(holdsDraft);
  const commitCount = async (h: HeldCount) => {
    const id = h.product.id ?? '';
    const startedIn = round.current;
    const stale = () => gone.current || round.current !== startedIn;
    try {
      // A draft whose commit was lost is sent again, never made twice.
      let draftId = drafts.current.get(id);
      if (draftId === undefined) {
        draftId = (await retail.createStocktake({ branch_id: branchId, lines: [{ product_id: id, counted_qty: qtyString(h.counted) }], note: 'Counted when the item was added on Sell' })).id ?? '';
        keepCount(draftId, true);
        drafts.current.set(id, draftId);
      }
      if (stale()) {
        drafts.current.delete(id);
        discardCount(draftId);
        throw new StaleCount();
      }
      await commitMade(draftId, [id]);
      drafts.current.delete(id);
      keepCount(draftId, false);
    } catch (e) {
      throw stale() ? new StaleCount() : e;
    }
    // Committed after the sale was cleared or the screen closed: the count stands, the item goes on no sale.
    if (stale()) throw new StaleCount();
  };
  const countCommitted = (h: HeldCount) => {
    drop(h.product.id);
    for (const key of [['retail', 'products'], ['retail', 'stocked-branches']]) void queryClient.invalidateQueries({ queryKey: key });
    add({ ...h.product, qty: qtyString(h.counted) });
  };
  const countFailed = (h: HeldCount, e: unknown) => {
    if (e instanceof StaleCount) return;
    const name = h.product.description ?? 'the item';
    // A refusal never passes on a second press: the server's words, the draft discarded and the item left for Count stock.
    if (refused(e)) {
      const draftId = drafts.current.get(h.product.id ?? '');
      drafts.current.delete(h.product.id ?? '');
      if (draftId !== undefined) discardCount(draftId);
      drop(h.product.id);
      const words = typeof e.problem?.detail === 'string' ? e.problem.detail : e.message;
      show(`${words} ${name} is not on the sale: use ${WORDS.countStock}, then sell it.`, 'error');
    } else show(`The count of ${name} is not committed. Try again.`, 'error');
  };
  const notNow = (h: HeldCount) => {
    const draftId = drafts.current.get(h.product.id ?? '');
    if (draftId !== undefined) {
      if (!online) {
        setStuck(true);
        return;
      }
      drafts.current.delete(h.product.id ?? '');
      discardCount(draftId);
    }
    drop(h.product.id);
    show(`${h.product.description ?? 'The item'} is saved. It is not on the sale: count it on ${WORDS.countStock}, then sell it.`, 'info');
  };
  const addNew = async ({ product, qty, sell }: AddedItem, clear: () => void) => {
    const made = sell !== undefined;
    const name = product.description ?? 'The item';
    const counted = made && mayCount ? parseQty(qty) : null;
    let counts = false;
    let asks = false;
    let lost = false;
    // Issue #656: an answer that comes after Clear this sale or after the screen closed is ignored, as on a held count: a draft
    // not committed yet is discarded, a count that landed stands, and the item goes on no sale.
    const startedIn = round.current;
    const stale = () => gone.current || round.current !== startedIn;
    if (counted !== null && counted > 0) {
      try {
        // Issue #636: an item made here holds nothing, so its count is never a big drop. Should the records hold more, the tick is
        // asked first from the balance read here (issue #656), and the draft is made only when Commit the count is pressed.
        const here = (await retail.listProducts({ query: product.code ?? product.description ?? '', branchId })).find((p) => p.id === product.id);
        if (stale()) throw new StaleCount();
        const expected = here?.qty ?? '0';
        if (bigDrop({ expected_qty: expected, counted_qty: qtyString(counted) })) {
          setHeld((l) => [...l.filter((x) => x.product.id !== product.id), { product, counted, expected }]);
          asks = true;
        } else {
          const count = await retail.createStocktake({ branch_id: branchId, lines: [{ product_id: product.id ?? '', counted_qty: qtyString(counted) }], note: 'Counted when the item was added on Sell' });
          const draftId = count.id ?? '';
          keepCount(draftId, true);
          // Stock that arrived between the read and the count (or a balance the search did not return): never sent to be
          // refused, and the draft is discarded (issue #656), so the item is counted again on Count stock with its tick.
          if (stale() || (count.lines ?? []).some(bigDrop)) {
            discardCount(draftId);
            throw stale() ? new StaleCount() : new Error('big drop');
          }
          try {
            // No press to try again here: a commit whose answer was lost is sent once more on the same draft.
            await commitMade(draftId).catch((e: unknown) => {
              if (refused(e)) throw e;
              return commitMade(draftId);
            });
          } catch (e) {
            // Only a refusal is discarded. A commit whose answer is still lost may have landed: its draft stays kept, and is
            // discarded when Sell next opens online unless it was committed.
            if (refused(e)) discardCount(draftId);
            else lost = true;
            throw e;
          }
          keepCount(draftId, false);
          if (stale()) throw new StaleCount();
          counts = true;
        }
      } catch (e) {
        if (lost) show(`The count of ${name} may not be committed. Check it on ${WORDS.countStock} before you sell it.`, 'error');
        else if (!(e instanceof StaleCount)) show(`${name} is saved, but its count is not. Count it on ${WORDS.countStock}, then sell it.`, 'error');
      }
    }
    // The card of a shop with no items, the search and the branches that hold stock all read again with the new item.
    for (const key of [['retail', 'catalogue'], ['retail', 'products'], ['retail', 'stocked-branches']]) void queryClient.invalidateQueries({ queryKey: key });
    clear();
    if (!made) {
      // An item already in the list keeps its own stock (review of #616): with none here it stays off the sale, as a new one does,
      // so the sale is never refused for stock at Record sale. The search reads this branch's balance, 0 included.
      let here: Product | undefined;
      try {
        here = (await retail.listProducts({ query: product.code ?? product.description ?? '', branchId })).find((p) => p.id === product.id);
      } catch {
        show(`${name} is in your items, but its stock here could not be read. Search for it again.`, 'error');
        return;
      }
      if (stale()) return;
      if (!here || !(Number(here.qty ?? '0') > 0)) {
        show(`${name} is already in your items, but it has no stock here yet, so it is not on the sale. Use ${WORDS.buyStock} or ${WORDS.countStock} first.`, 'info');
        return;
      }
      add(here);
      if (qty.trim() !== '') show(`${name} is already in your items, so its stock is not changed here. If it is wrong, use ${WORDS.countStock}.`, 'info');
      return;
    }
    if (stale()) return;
    if (counts) add({ ...product, qty: qtyString(counted ?? 0) });
    else if (asks) return;
    else if (counted === null) show(`${name} is saved. It has no stock here yet: use ${WORDS.buyStock} or ${WORDS.countStock}, then sell it.`, 'info');
  };
  // A scan (issue #345) adds the item at 1, or one more to its line in the pack the barcode is on; a pack it cannot
  // use is refused, never sold as one piece: ScanBar shows the refusal as a warning and no toast is raised.
  const scanAdd = (p: Product, unitId?: string) => {
    const unit = unitsOn && sellingPacks(p).some((k) => k.unit_id === unitId) ? unitId : undefined;
    if (unitId && !unit) return PACK_UNUSABLE;
    const now = lines.current;
    const next = scanInto(now, p, unit, (l) => ({ ...l, qty: plusOne(l.qty) }), () => (unit ? withUnit(newLine(p), unit) : newLine(p)));
    const { index, created } = touchedLine(now, next);
    lines.current = next;
    setDraft((d) => ({ ...d, lines: next }));
    if (index >= 0) mark(index, created);
    // Issue #498: the toast says the line's quantity now, and a screen reader hears the same through the toast's live region.
    show(addedMessage(p.description, next[index]?.qty), 'success');
  };

  const total = draftTotal(shown.lines);
  const problem = (offlineMode ? offlineProblem(draft, holdsAt(me, PRICE_OVERRIDE, branchId)) : draftProblem(shown, pricing, accounts)) ?? receivedProblem(draft.method, total, received);
  // A sale whose request got no answer may or may not be recorded: the form is locked, and kept to send later or sent again,
  // it is the same request under the same client sale id, recorded once either way (ADR-067 decision 5).
  const locked = offline !== null && draft.sent !== undefined;
  // Issue #353: each line against the item's own sales, said before saving, never blocking. Offline, or for a request already
  // sent, nothing is read or asked. Only a price the seller typed is compared with the last one; the item's or a list's price is
  // the owner's own.
  const norms = useEntryNorms(offlineMode ? [] : draft.lines.map((l) => l.product.id));
  const warnings = offlineMode || draft.sent ? [] : shown.lines.flatMap((l, i): EntryWarning[] => {
    const pack = unitsOn ? chosenPack(l.product, l.unitId) : undefined;
    const f = lineFigures(l);
    return entryWarnings(norms(l.product.id), {
      screen: 'sell', product: l.product, lineKey: `${l.product.id}-${i}`, qtyMilli: f?.qtyMilli ?? null, qtyFieldId: `qty-${i}`,
      unitChoosable: unitsOn && sellingPacks(l.product).length > 0, unitFieldId: `unit-${i}`,
      ...(pack ? { unitId: pack.unit_id, unitName: pack.unit, factor: pack.factor } : {}),
      ...(l.typed ? { priceMinor: f?.priceMinor ?? null, priceFieldId: `price-${i}` } : {}),
    });
  });
  /** Sends the sale as the screen shows it now (or the request already sent, which is all that can go again). */
  const send = () => {
    const body = draft.sent?.body ?? { ...buildSaleRequest(shown, pricing, accounts), ...(offline ? { client_sale_id: key } : {}) };
    if (offline) setDraft((d) => ({ ...d, sent: d.sent ?? { body, at: new Date().toISOString() } }));
    save.mutate(body);
  };
  const entry = useEntryChecks('sell', branchId, warnings, send, draft.lines);
  const lost = locked && !save.isPending && !(save.error instanceof RetailError);
  const keep = useMutation({
    mutationFn: async () => {
      if (!offline) throw new Error('Offline sales are not available');
      const item = offlineSale({
        draft: shown,
        clientSaleId: key,
        sent: draft.sent,
        // A new key: the first one may already be stored with the online request, which this one is not.
        key: draft.sent ? newIdempotencyKey() : key,
        scope: offline.scope,
        branchName,
        pricesAsOf: offline.catalogue?.pricesAsOf,
        buyerName: draft.newBuyerName,
        now: new Date(),
      });
      await offline.engine.queue(item);
      return item;
    },
    onSuccess: (item) => {
      finish();
      if (offline?.connectivity.online()) void offline.engine.sync();
      onQueued?.(item);
    },
  });
  const clear = () => {
    // Issue #656: a tick still asked goes with the sale, and a draft made for it is discarded; offline the sale waits.
    if (holdsDraft()) {
      setConfirmClear(false);
      return;
    }
    round.current += 1;
    for (const id of drafts.current.values()) discardCount(id);
    drafts.current.clear();
    setHeld([]);
    setStuck(false);
    discard();
    setAddingBuyer(false);
    setReceived('');
    setConfirmClear(false);
    save.reset();
  };
  // A credit sale refused for the buyer's limit: say by how much, and offer the override to whoever holds it.
  const overBy = accountsOn && draft.method === 'credit' ? exceededBy(save.error) : null;
  const canOverride = holdsAt(me, CREDIT_OVERRIDE, branchId);

  return (
    <form
      className="sale-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (save.isPending || keep.isPending || (problem && !draft.sent)) return;
        // A credit sale whose answer was lost is never kept on the phone: it is only tried again (ADR-067 decision 1).
        if (offlineMode && mayKeepOnPhone(draft.sent)) {
          keep.mutate();
          return;
        }
        entry.check();
      }}
    >
      {offlineMode && <OfflineNote />}
      <fieldset disabled={locked} className="sale-lock" style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}>
      <div data-tour="sale-search">
        {offlineMode ? <OfflineProductPicker branchId={branchId} onAdd={add} /> : (
          <ProductPicker
            id="sale-search"
            branchId={branchId}
            showPrice
            onAdd={add}
            notFound={(term, clear, hold) => (
              <ScreenToast.Provider value={show}>
                <NewItemHere id="sale-new" description={term} ask={ASK_OWNER_ITEM} askCost={profit} qtyLabel={mayCount ? 'How many you have here' : undefined}
                  qtyError="Type how many you have here, more than 0." onOpenChange={hold} onAdded={(added) => addNew(added, clear)} />
                <ScreenLink screen="quickAdd" to="/staff/retail/quick-add" search={{ name: term }}>Quick add "{term}" with its prices and stock</ScreenLink>
              </ScreenToast.Provider>
            )}
          />
        )}
      </div>
      <ScanBar id="sale-scan" branchId={branchId} onFound={scanAdd} autoFocus toasts />
      {held.map((h) => (
        <HeldCountTick key={h.product.id} held={h} commit={commitCount} onCommitted={countCommitted} onFailed={countFailed} onCancel={notNow} />
      ))}
      {stuck && !online && held.length > 0 && (
        <p role="alert" className="alert alert-warning" data-testid="sale-count-offline">
          A count on this sale is not committed. Connect to the internet, then commit the count or press Not now, before you clear the sale or switch branch.
        </p>
      )}

      <h2>
        Items{' '}
        {shown.lines.length > 0 && (
          <span key={pops} className={pops > 0 ? 'badge count-badge count-pop' : 'badge count-badge'}>
            {shown.lines.length}
            <span className="visually-hidden"> on the sale</span>
          </span>
        )}
      </h2>
      {draft.lines.length === 0 && <EmptyState title="No items yet.">Search or scan to add.</EmptyState>}
      {pricesOn && resolution.data?.price_list && !checking && (
        <p className="hint" data-tour="sale-price-list" data-testid="price-list-in-force">
          Priced from the list {resolution.data.price_list}{resolution.data.origin === 'customer_group' ? ' (the customer group)' : resolution.data.origin === 'customer' ? ' (the customer)' : ''}.
        </p>
      )}
      {pricesOn && <Problem error={resolution.error} />}
      {shown.lines.map((l, i) => (
        <SaleLineCard
          key={`${l.product.id}-${i}`}
          line={l}
          index={i}
          unitsOn={unitsOn}
          fresh={fresh !== null && fresh.index === i ? fresh.n : undefined}
          slide={fresh !== null && fresh.index === i && fresh.created}
          onFlashed={() => setFresh(null)}
          price={pricesOn ? { resolved: resolved?.[i], listName: resolution.data?.price_list, canOverride: holdsAt(me, PRICE_OVERRIDE, branchId) } : undefined}
          offlinePrice={offlineMode ? { canType: holdsAt(me, PRICE_OVERRIDE, branchId) } : undefined}
          onQty={(v) => setLine(i, { qty: v })}
          onPrice={(v) => setLine(i, { price: v, typed: true })}
          onReason={(v) => setLine(i, { reason: v })}
          onReset={() => (offlineMode ? setLine(i, { price: String(defaultPrice(l.product, l.unitId)), typed: false, reason: '' }) : setLine(i, { typed: false, reason: '' }))}
          onUnit={(unitId) => setUnit(i, unitId)}
          onRemove={() => setDraft((d) => ({ ...d, lines: d.lines.filter((_, n) => n !== i) }))}
          notes={entry.notes(`${l.product.id}-${i}`)}
        />
      ))}

      {pricesOn && draft.method !== 'credit' && (
        <fieldset data-tour="sale-customer">
          <legend>Customer</legend>
          {showsEmptyChoice(customers) ? (
            <EmptyChoice id="price-customer" label="Customer (optional): sets the price list" load={customers} text="No customers yet." ask="Ask the owner to add your customers first."
              add={addOn(me, 'buyers', 'Add a customer', '/staff/retail/catalogue/buyers')} />
          ) : (
          <>
          <label htmlFor="price-customer">Customer (optional): sets the price list</label>
          <select id="price-customer" value={draft.customerId} onChange={(e) => update({ customerId: e.target.value })}>
            <option value="">No customer: retail prices</option>
            {(customers.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
          </>
          )}
        </fieldset>
      )}
      {canChooseList && (
        <div>
          {showsEmptyChoice(lists, lists.data?.filter((l) => l.active)) ? (
            <EmptyChoice id="price-list" label="Price list for this sale" load={lists} text="No price lists yet." ask="Ask the owner to add a price list first."
              add={addOn(me, 'priceLists', 'Add a price list', '/staff/retail/price-lists')} />
          ) : (
          <>
          <label htmlFor="price-list">Price list for this sale</label>
          <select id="price-list" value={draft.priceListId ?? ''} onChange={(e) => update({ priceListId: e.target.value || undefined })}>
            <option value="">The customer's own list</option>
            {(lists.data ?? []).filter((l) => l.active).map((l) => (
              <option key={l.id} value={l.id}>{l.name}</option>
            ))}
          </select>
          </>
          )}
        </div>
      )}

      <fieldset data-tour="sale-payment">
        <legend>How is it paid?</legend>
        {METHODS.map((m) => (
          <label key={m.value} style={{ fontWeight: 400 }}>
            <input type="radio" name="method" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={draft.method === m.value} disabled={offlineMode && m.value === 'credit'} onChange={() => update({ method: m.value })} />
            {m.label}
          </label>
        ))}
        {offlineMode && <p className="hint">A credit sale needs the internet.</p>}
      </fieldset>

      {offlineMode && (
        <fieldset>
          <legend>Buyer (optional)</legend>
          {(offline?.catalogue?.customers ?? []).length > 0 && (
          <>
          <label htmlFor="offline-customer">Customer saved on this phone</label>
          {/* empty: drawn only when this phone holds customers; otherwise the buyer's name is typed below. */}
          <select id="offline-customer" value={draft.customerId} onChange={(e) => update({ customerId: e.target.value })}>
            <option value="">No customer</option>
            {(offline?.catalogue?.customers ?? []).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
          </>
          )}
          {!draft.customerId && (
            <>
              <label htmlFor="offline-buyer-name">Or the buyer's name</label>
              <input id="offline-buyer-name" value={draft.newBuyerName} maxLength={120} onChange={(e) => update({ newBuyerName: e.target.value })} />
            </>
          )}
        </fieldset>
      )}

      {draft.method === 'credit' && (
        <fieldset>
          <legend>Customer from the list</legend>
          {!addingBuyer && showsEmptyChoice(customers) && (
            <EmptyChoice id="buyer" label="Buyer" load={customers} text="No buyers yet." add={{ label: 'Add a new buyer', onClick: () => { setAddingBuyer(true); update({ customerId: '' }); } }} />
          )}
          {!addingBuyer && !showsEmptyChoice(customers) && (
            <>
              <label htmlFor="buyer">Buyer</label>
              <select id="buyer" value={draft.customerId} onChange={(e) => update({ customerId: e.target.value })}>
                <option value="">Choose a buyer</option>
                {(customers.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
              {accountsOn && buyerRow && (
                <p className="hint" data-testid="buyer-account-note">{limitWords({ credit_limit_minor: buyerRow.credit_limit_minor }, money)} {termsWords(buyerRow.terms_days)}</p>
              )}
              <button type="button" onClick={() => { setAddingBuyer(true); update({ customerId: '' }); }}>
                Add a new buyer
              </button>
            </>
          )}
          {addingBuyer && (
            <>
              <label htmlFor="buyer-name">Buyer name</label>
              <input id="buyer-name" value={draft.newBuyerName} onChange={(e) => update({ newBuyerName: e.target.value })} />
              <label htmlFor="buyer-contact">Phone (optional)</label>
              <input id="buyer-contact" inputMode="tel" value={draft.newBuyerContact} onChange={(e) => update({ newBuyerContact: e.target.value })} />
              {accountsOn && (
                <>
                  <BusyButton type="button" className="rt-primary" busy={addBuyer.isPending} notReady={draft.newBuyerName.trim() === ''} onClick={() => addBuyer.mutate()}>
                    Add this buyer
                  </BusyButton>
                  <Problem error={addBuyer.error} />
                </>
              )}
              <button type="button" onClick={() => { setAddingBuyer(false); update({ newBuyerName: '', newBuyerContact: '' }); }}>
                Pick an existing buyer
              </button>
            </>
          )}
          <label htmlFor="due">Buyer will pay by{accountsOn && buyerRow?.terms_days !== undefined ? ' (leave empty to use their terms)' : ''}</label>
          <input id="due" type="date" value={draft.dueDate} onChange={(e) => update({ dueDate: e.target.value })} />
        </fieldset>
      )}

      </fieldset>

      <p className="rt-total rt-total-hero" aria-live="polite">
        <span>Total</span> <span className="rt-total-amount">{money(total)}</span>
      </p>
      <ChangePanel method={draft.method} total={total} text={received} onText={setReceived} />
      {problem && draft.lines.length > 0 && <Note>{problem}</Note>}
      {overBy !== null && (
        <div role="alert" className="alert alert-danger" data-testid="limit-refusal">
          <p>
            This sale takes {buyerRow?.name ?? 'the buyer'} {money(overBy)} above their credit limit{isLimitRefusal(save.error) ? '' : ', and a reason is needed'}.
          </p>
          {canOverride ? (
            <>
              <label htmlFor="credit-reason">Why is this sale allowed? (at least 5 characters)</label>
              <input id="credit-reason" value={draft.creditReason ?? ''} maxLength={300} onChange={(e) => update({ creditReason: e.target.value })} />
              <Hint full>Save the sale again to sell above the limit. The reason is kept with the sale.</Hint>
            </>
          ) : (
            <p>Ask someone who may allow it, take a payment first, or sell for less.</p>
          )}
        </div>
      )}
      {overBy === null && <Problem error={save.error} />}
      {lost && (
        <div role="alert" className="alert alert-warning" data-testid="lost-answer">
          {mayKeepOnPhone(draft.sent) ? (
            <>
              <p>The server did not answer. The sale may or may not be saved. Keep it on this phone and it is sent once.</p>
              <Hint full>The items and the payment cannot be changed now, so the same sale is sent. To sell something else, clear this sale.</Hint>
              <BusyButton type="button" busy={keep.isPending} onClick={() => keep.mutate()}>
                Keep it on this phone
              </BusyButton>
            </>
          ) : (
            <>
              <p>The server did not answer. The sale may or may not be saved. A credit sale needs the internet: tap Try again when you are online, and it is saved once.</p>
              <Hint full>The items and the payment cannot be changed now, so the same sale is sent. To sell something else, clear this sale.</Hint>
            </>
          )}
        </div>
      )}
      {keep.error && <p role="alert" className="alert alert-danger">This phone could not keep the sale: {keep.error.message}</p>}
      <SaleBar>
        <BusyButton type="submit" className="rt-primary sale-bar-action" busy={save.isPending || keep.isPending} notReady={problem !== null && !locked} data-tour="sale-save">
          {offlineMode && mayKeepOnPhone(draft.sent) ? 'Save on this phone' : lost ? 'Try again' : (
            <>
              <span>Record sale</span>
              {total > 0 && <> <span className="sale-bar-amount">{money(total)}</span></>}
            </>
          )}
        </BusyButton>
      </SaleBar>
      {draft.lines.length > 0 && !save.isPending && !confirmClear && (
        <button type="button" onClick={() => (draft.sent ? setConfirmClear(true) : clear())}>
          Clear this sale
        </button>
      )}
      {confirmClear && (
        <div role="alert" className="alert alert-warning" data-testid="confirm-clear">
          <p>This sale may already be saved on the server. If you clear it and sell again, the customer may be charged twice. Clear it?</p>
          <div className="cluster">
            <button type="button" className="btn-danger" onClick={clear}>
              Clear it
            </button>
            <button type="button" onClick={() => setConfirmClear(false)}>
              Keep it
            </button>
          </div>
        </div>
      )}
      {toast}
    </form>
  );
}

/** Issue #656: a new item counted on Sell, waiting for its tick. */
type HeldCount = { product: Product; counted: number; expected: string };

/** Issue #656: the answer of a count pressed before the sale was cleared or the screen closed, which is ignored. */
class StaleCount extends Error {}

// Issue #656: the drafts Sell made in this tab and has not seen committed or discarded, so a discard lost offline or to a reload
// is sent again. Kept per tab: another tab's Sell may still be committing its own.
const KEPT_COUNTS = 'sale-held-counts';

function keptCounts(): string[] {
  try {
    const kept: unknown = JSON.parse(sessionStorage.getItem(KEPT_COUNTS) ?? '[]');
    return Array.isArray(kept) ? kept.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

function keepCount(id: string, kept: boolean) {
  try {
    const rest = keptCounts().filter((x) => x !== id);
    sessionStorage.setItem(KEPT_COUNTS, JSON.stringify(kept ? [...rest, id] : rest));
  } catch {
    // Without storage the discard is tried once.
  }
}

/** A clear refusal from the server, which a second try would only meet again (a timeout or too many requests is not one). */
const refused = (e: unknown): e is RetailError => e instanceof RetailError && e.status >= 400 && e.status < 500 && e.status !== 408 && e.status !== 429;

/** Issue #656: commits a count Sell made; one the server already took (409 stocktake_committed, its first answer lost) stands. */
async function commitMade(id: string, acknowledged?: string[]) {
  await (acknowledged ? retail.commitStocktake(id, acknowledged) : retail.commitStocktake(id)).catch((e: unknown) => {
    if (!(e instanceof RetailError && e.code === 'stocktake_committed')) throw e;
  });
}

/** Issue #656: ends a draft Sell made; one already committed or discarded, or not found, is forgotten too. */
function discardCount(id: string) {
  keepCount(id, true);
  retail.discardStocktake(id).then(
    () => keepCount(id, false),
    (e: unknown) => {
      if (e instanceof RetailError && (e.status === 404 || e.status === 409)) keepCount(id, false);
    },
  );
}

/**
 * Issue #656: the count Sell would make of a new item is a big drop, so the records already hold more of it here. The line asks
 * its tick first, as on Count stock; Commit the count then makes the count and sends it acknowledged. Not now leaves nothing open.
 */
function HeldCountTick({ held, commit, onCommitted, onFailed, onCancel }: {
  held: HeldCount;
  commit: (h: HeldCount) => Promise<void>;
  onCommitted: (h: HeldCount) => void;
  onFailed: (h: HeldCount, e: unknown) => void;
  onCancel: (h: HeldCount) => void;
}) {
  const [ok, setOk] = useState(false);
  // Its own press: another item's count on its way never makes this one look busy or idle.
  const sending = useMutation({ mutationFn: () => commit(held), onSuccess: () => onCommitted(held), onError: (e) => onFailed(held, e) });
  const busy = sending.isPending;
  const wordsId = useId();
  const name = held.product.description ?? 'this item';
  const line = { expected_qty: held.expected, counted_qty: qtyString(held.counted) };
  return (
    <div className="alert alert-warning entry-warning" data-testid={`sale-count-drop-${held.product.id ?? ''}`}>
      <span className="entry-warning-icon" aria-hidden="true">{icons.warning}</span>
      <div>
        <p id={wordsId}>
          The records show {showQty(line.expected_qty)} of {name} here, and you counted {showQty(line.counted_qty)}. {dropWords(line, undefined)}
        </p>
        <label className="count-drop-tick">
          <input type="checkbox" checked={ok} aria-describedby={wordsId} data-needed={ok ? undefined : ''} onChange={(e) => setOk(e.target.checked)} />
          {`Yes, ${name} is right`}
        </label>
        <BusyButton type="button" className="rt-primary" busy={busy} notReady={!ok} onClick={() => sending.mutate()}>Commit the count</BusyButton>
        {/* Not now discards a draft whose commit was lost; while the count is on its way it waits. */}
        {!busy && <button type="button" onClick={() => onCancel(held)}>Not now</button>}
      </div>
    </div>
  );
}

/**
 * Says plainly that the chosen branch is a store, which never sells (ADR-033), with a button for each
 * branch the user can sell at; or that there is none, and where to go instead.
 */
export function StoreBranch({ branchName, roles }: { branchName: string; roles?: BranchRole[] }) {
  const { me, chooseBranch } = useStaff();
  const shops = sellingBranches(branchesWhere(me, 'retail.sale.create'), roles);
  return (
    <div role="status" className="alert alert-warning" data-testid="store-branch">
      <div className="stack">
        <p>
          <strong>{branchName}</strong> is a store, and a store does not sell.
          {shops.length > 0 ? ' Choose a shop to record this sale.' : ' None of your branches sells; use Stock or Move stock instead.'}
        </p>
        {chooseBranch &&
          shops.map((b) => (
            <button key={b.id} type="button" onClick={() => chooseBranch(b.id ?? '')}>
              Sell at {branchLabel(b)}
            </button>
          ))}
      </div>
    </div>
  );
}

/** New sale under the receipt actions: a secondary button once Share receipt is drawn, the main action until then (issue #534). */
function NewSale({ sale, onNew }: { sale: Sale; onNew: () => void }) {
  return (
    <button type="button" className={useShareReady(sale, true) ? 'btn rt-next' : 'rt-primary'} onClick={onNew}>
      New sale
    </button>
  );
}

/**
 * Sell it from Quick add (issue #632) named another branch than the one in use: the sale says where the item was added, offers
 * Switch to that branch where this person may sell there, and Sell here without it, which forgets the item, so the sale never
 * waits in silence (a reload or a shared link included).
 */
export function StartElsewhere({ branchId, onDrop }: { branchId: string; onDrop: () => void }) {
  const { me, chooseBranch } = useStaff();
  const there = (me.branches ?? []).find((b) => b.id === branchId);
  const name = there ? branchLabel(there) : 'another branch';
  const may = there !== undefined && there.sells_goods !== false && holdsAt(me, 'retail.sale.create', branchId);
  return (
    <div role="status" className="alert alert-info" data-testid="sale-start-elsewhere">
      <div className="stack">
        <p>{may ? `The item you just added is at ${name}. Switch to ${name} to sell it.` : `The item you just added is at ${name}, where you do not sell.`}</p>
        <p className="cluster">
          {may && chooseBranch && <button type="button" onClick={() => chooseBranch(branchId)}>Switch to {name}</button>}
          <button type="button" className="btn-ghost" onClick={onDrop}>Sell here without it</button>
        </p>
      </div>
    </div>
  );
}

export function RecordSale({ startItem, startBranch, onStarted }: { startItem?: string; startBranch?: string; onStarted?: () => void } = {}) {
  const { me } = useStaff();
  const roles = useBranchRoles(me);
  const { branchId, branchName } = useStockingBranch();
  // A store by role (ADR-033) or by the branch's own flag (ADR-048): either never sells.
  const store =
    branchId !== null &&
    ((roles !== undefined && roles.some((r) => r.branch_id === branchId && !r.can_sell)) || (me.branches ?? []).some((b) => b.id === branchId && b.sells_goods === false));
  // With branch roles on, the sale form waits for the roles, so it is never drawn at a store while they load.
  const online = useOnline();
  const waiting = online && capabilityOn(me, 'retail', BRANCH_ROLES) && (me.permissions ?? []).includes('retail.stock.read') && roles === undefined;
  const [sale, setSale] = useState<Sale | null>(null);
  const [queued, setQueued] = useState<OutboxItem | null>(null);
  const [received, setReceived] = useState<number>();
  const [round, setRound] = useState(0);
  // Sell here without it: the item named in the address is forgotten.
  const [dropped, setDropped] = useState(false);
  const elsewhere = !dropped && round === 0 && !!startItem && !!startBranch && startBranch !== branchId;
  const noItems = useNoItemsYet();
  // The receipt of the sale just made, with its number, stays until the cashier starts the next sale or leaves (#576, ADR-088).
  useUnsavedWork(sale !== null || queued !== null);
  return (
    <Gate screen="sale" title="Record a sale">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('sale')} stocking />
      ) : waiting ? (
        <p role="status">Loading</p>
      ) : store ? (
        <StoreBranch branchName={branchName} roles={roles} />
      ) : queued ? (
        <ProvisionalReceipt item={queued} onNew={() => { setQueued(null); setRound((n) => n + 1); }} />
      ) : sale ? (
        <>
          {/* Share receipt is the main action here (issue #534): New sale comes right after the receipt actions, and is the
              main action until Share receipt is drawn, or when this sale offers no receipt to share. */}
          <Receipt sale={sale} received={received} />
          <ShareReceipt sale={sale} madeHere />
          <NewSale sale={sale} onNew={() => { setSale(null); setReceived(undefined); setRound((n) => n + 1); }} />
          {sale.id && <SaleDocuments saleId={sale.id} status={sale.status} branchId={sale.branch_id} />}
        </>
      ) : (
        <>
          {/* A shop with no items yet starts from the card (issue #615); "This branch holds no stock" would only confuse it. */}
          <FirstItemsCard />
          {!noItems && <NoStockHere branchId={branchId} />}
          {elsewhere && startBranch && <StartElsewhere branchId={startBranch} onDrop={() => { setDropped(true); onStarted?.(); }} />}
          <SaleForm key={`${branchId}-${round}`} branchId={branchId} branchName={branchName} onSaved={(s, r) => { setSale(s); setReceived(r); }} onQueued={setQueued}
            // Sell it names the branch its stock was added at: the item waits until that branch is the one in use, so it is never looked
            // up (and the address cleared) at the branch the sale opened with before the switch landed.
            startItem={!dropped && round === 0 && (!startBranch || startBranch === branchId) ? startItem : undefined} onStarted={onStarted} />
        </>
      )}
    </Gate>
  );
}

/** The route's screen: the sale starts with the item of `?item=` (Quick add's Sell it), and the address forgets it once added. */
function RecordSaleRoute() {
  const { item, branch } = useSearch({ strict: false }) as { item?: string; branch?: string };
  const navigate = useNavigate();
  return <RecordSale startItem={item} startBranch={branch} onStarted={() => void navigate({ to: '/staff/retail/sale', search: {} as never, replace: true })} />;
}

export const Route = createLazyRoute('/staff/retail/sale')({ component: RecordSaleRoute });
