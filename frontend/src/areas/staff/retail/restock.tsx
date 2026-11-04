import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useNavigate, useRouterState } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { RETAIL_CURRENCY, businessToday, retail, type Product, type Supplier, type Purchase, type PurchasePayment, type PurchaseRequest } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { parseMinor } from '../../../components/money';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { lineTotalMinor, parseQty, qtyString, showDate, showQty } from './maths';
import { chosenUnitHint, listIsStale, redraftOf } from './restock-correct-state';
import { Gate, Money, Note, Problem, ProductPicker, money, useProfitAccess } from './ui';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne, scanInto, touchedLine } from './barcode-state';
import { ScreenToast, sayDone, useItemAdded } from './toast';
import { BACKDATE, PROFIT, TRANSFER, UNITS, canUse, capabilityOn, holdsAt } from './permissions';
import { entryDate } from './cash-state';
import { DateField } from './cash-ui';
import { NextSteps } from './next-steps';
import { usualBuyingPack } from './ladder-state';
import { OWN_PRICE_KEEPS_SIZE, baseCostMinor, baseQtyMilli, buyingPacks, chosenPack, deliveredFactor, mayKeepSize, packPriceMinor, quantityIn, sameFactor, unitWord, unitWords } from './units-state';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field, FieldsNeededNote } from '../../../app/ui/Field';
import { WORDS, howManyInEach, useFromNowOn } from '../../../app/glossary';
import { NewItemHere, type AddedItem } from './new-item-here';
import { RowChevron } from '../../../app/ui/ListRow';
import { entryWarnings, type EntryWarning } from './entry-checks-state';
import { useEntryChecks, useEntryNorms } from './entry-checks';
import { AddByName } from '../../../app/ui/EmptyChoice';
import { PickerWithAdd } from '../../../app/ui/PickerWithAdd';
import { EditNeedsInternet, KeptOnPhone, OfflineFormNote, isKept, useEntrySave } from '../../../offline/keep-entry';
import { RECEIPT_LATER, ReceiptPhotoChoice, bounded, sendReceiptPhotoAfter } from './receipt-photo';

// Restock (FR-RET-06): supplier, lines with cost, sell price and a quantity per branch. Saving sets
// the product's cost and sell price in the same transaction and leaves a price history row.

export interface RestockLine {
  product: Product;
  cost: string;
  sell: string;
  qtyByBranch: Record<string, string>;
  /** A pack unit of the product (ADR-033, units capability): cost and quantities are then in that unit. Absent means the base unit. */
  unitId?: string;
  /** D23 (#554): how many base units each pack held in this delivery, typed; starts at the pack's usual size. */
  packSize?: string;
  /** D23 (#554): make packSize the pack's usual size from now on. */
  fromNowOn?: boolean;
}

/**
 * The line in another unit: the cost goes to that unit's equivalent of the current cost (a pack costs factor times a piece),
 * and the size each pack held starts at the pack's usual size, which the person changes when this delivery's differs (D23).
 */
export function withUnit(line: RestockLine, unitId: string): RestockLine {
  const pack = chosenPack(line.product, unitId || undefined);
  const base = line.product.cost_minor;
  const { packSize: _size, fromNowOn: _now, ...rest } = line;
  return {
    ...rest,
    unitId: pack ? unitId : undefined,
    ...(pack ? { packSize: showQty(pack.factor ?? '') } : {}),
    cost: base === undefined ? '' : String(pack ? packPriceMinor(base, pack.factor) : base),
  };
}

/** The base units in one pack of the line for this delivery: the typed size, else the usual; null when the typed size is wrong. */
export function lineFactor(l: RestockLine): string | null | undefined {
  const pack = chosenPack(l.product, l.unitId);
  if (!pack) return undefined;
  return l.packSize === undefined ? (pack.factor ?? null) : deliveredFactor(l.packSize);
}

/** The quantity entered for a branch, as thousandths; blank or zero counts as none, bad text as null. */
function branchQty(text: string | undefined): number | null {
  if (text === undefined || text.trim() === '' || /^0(\.0{1,3})?$/.test(text.trim())) return 0;
  return parseQty(text);
}

export function restockLineProblem(l: RestockLine): string | null {
  if (parseMinor(l.cost, RETAIL_CURRENCY) === null) return 'Enter the cost.';
  if (parseMinor(l.sell, RETAIL_CURRENCY) === null) return 'Enter the sell price.';
  const qtys = Object.values(l.qtyByBranch).map(branchQty);
  if (qtys.some((q) => q === null)) return 'Check the quantities.';
  if (!qtys.some((q) => q !== null && q > 0)) return 'Enter a quantity for at least one branch.';
  const pack = chosenPack(l.product, l.unitId);
  const factor = lineFactor(l);
  if (pack && factor === null) return `Enter how many ${unitWords(l.product.unit ?? 'item')} were in each ${unitWord(pack.unit)}: 1 or more.`;
  if (pack && qtys.some((q) => q !== null && baseQtyMilli(q, factor ?? undefined) === null)) {
    return `A quantity does not convert exactly to ${l.product.unit ? unitWords(l.product.unit) : 'items'}. Change it.`;
  }
  return null;
}

export function restockTotal(lines: RestockLine[]): number {
  return lines.reduce((sum, l) => {
    const cost = parseMinor(l.cost, RETAIL_CURRENCY) ?? 0;
    return sum + Object.values(l.qtyByBranch).reduce((s, q) => s + lineTotalMinor(cost, branchQty(q) ?? 0), 0);
  }, 0);
}

// JUSTIFICATION-A3: issue #821 adds the delivery's worth and the block that shows it on Buy stock and Past purchases.
/**
 * Issue #821 (ADR-126): what the goods on the form should sell for, as the server reckons a saved delivery: each line's quantity
 * in the item's own unit times the selling price on the line, else the item's current price. A line not yet right counts what
 * can be read of it. Null when an item has no selling price yet (review of #850): the worth is then not known.
 */
export function restockWorth(lines: RestockLine[]): number | null {
  if (lines.some((l) => (parseMinor(l.sell, RETAIL_CURRENCY) ?? l.product.sell_minor ?? 0) <= 0)) return null;
  return lines.reduce((sum, l) => {
    const sell = parseMinor(l.sell, RETAIL_CURRENCY) ?? l.product.sell_minor ?? 0;
    const factor = lineFactor(l);
    const milli = Object.values(l.qtyByBranch).reduce((s, q) => {
      const entered = branchQty(q) ?? 0;
      return s + (factor === undefined ? entered : (baseQtyMilli(entered, factor ?? undefined) ?? 0));
    }, 0);
    return sum + lineTotalMinor(sell, milli);
  }, 0);
}

/**
 * Issue #821: the delivery's cost, what it should sell for and the expected profit (a loss said as one), one line each. With an
 * item that has no selling price yet (a null worth), the cost and that sentence instead of the other two.
 */
export function DeliveryWorth({ paid, worth, label }: { paid: number; worth: number | null; label: string }) {
  const profit = worth === null ? 0 : worth - paid;
  return (
    <>
      <dl className="rt-figures" aria-label={label} data-testid="delivery-worth">
        <div><dt>Total cost</dt><dd><Money minor={paid} /></dd></div>
        {worth !== null && <div><dt>Should sell for</dt><dd><Money minor={worth} /></dd></div>}
        {worth !== null && <div><dt>{profit < 0 ? 'Expected loss' : 'Expected profit'}</dt><dd><strong><Money minor={Math.abs(profit)} /></strong></dd></div>}
      </dl>
      {worth === null && <p className="hint" data-testid="unpriced-note">Some items have no selling price yet.</p>}
    </>
  );
}

export function buildPurchase(input: { supplierId: string; purchasedOn?: string; dateReason?: string; method: PurchasePayment; lines: RestockLine[] }): PurchaseRequest {
  return {
    ...(input.supplierId ? { supplier_id: input.supplierId } : {}),
    // ADR-120: no date for today, so the server dates the purchase itself; an owner's earlier day goes with its reason.
    ...(input.purchasedOn ? { purchased_on: input.purchasedOn } : {}),
    ...(input.dateReason ? { date_reason: input.dateReason } : {}),
    payment_method: input.method,
    lines: input.lines.map((l) => ({
      product_id: l.product.id ?? '',
      ...(chosenPack(l.product, l.unitId) ? { unit_id: l.unitId, ...packSizeOf(l) } : {}),
      cost_minor: parseMinor(l.cost, RETAIL_CURRENCY) ?? 0,
      // ADR-055: no price is sent when none is known (someone who may not see prices); the item keeps its price.
      ...((parseMinor(l.sell, RETAIL_CURRENCY) ?? l.product.sell_minor) !== undefined ? { sell_minor: parseMinor(l.sell, RETAIL_CURRENCY) ?? l.product.sell_minor } : {}),
      qty_by_branch: Object.entries(l.qtyByBranch)
        .map(([branchId, q]) => ({ branchId, milli: branchQty(q) ?? 0 }))
        .filter((q) => q.milli > 0)
        .map((q) => ({ branch_id: q.branchId, qty: qtyString(q.milli) })),
    })),
  };
}

/** D23 (#554): the size each pack held in this delivery, and "use from now on" only when it differs from the usual size. */
function packSizeOf(l: RestockLine): { pack_size?: string; use_from_now_on?: boolean } {
  const factor = lineFactor(l);
  if (!factor) return {};
  const usual = chosenPack(l.product, l.unitId)?.factor;
  return { pack_size: factor, ...(l.fromNowOn && !sameFactor(factor, usual) ? { use_from_now_on: true } : {}) };
}

/** Paid how (issue #557, D15): the money left the till, a mobile money account or the bank, or the supplier is owed it. */
export const METHODS: { value: PurchasePayment; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
  { value: 'credit', label: `${WORDS.onCredit} (owed to the supplier)` },
];

/** On credit is owed to someone, so it needs a supplier chosen in the list (+ Add a supplier adds one there, issue #688). */
export function creditNeedsSupplier(method: PurchasePayment, supplierId: string): string | null {
  return method === 'credit' && supplierId === '' ? 'On credit needs a supplier. Choose one above, or + Add a supplier.' : null;
}

/** What the restock form keeps with its key in this tab until saved or cleared (#77). */
export interface RestockDraft {
  supplierId: string;
  purchasedOn: string;
  /** Why the owner chose an earlier day (ADR-120). */
  dateReason?: string;
  method: PurchasePayment;
  lines: RestockLine[];
}

/**
 * A draft kept before issue #688 may carry a typed `newSupplier`, which the form no longer shows: it is dropped when the draft is
 * restored, so nothing is made that the form does not show. A draft without one is given back as it was.
 */
export function withoutTypedSupplier(draft: RestockDraft): RestockDraft {
  if (!('newSupplier' in draft)) return draft;
  const { newSupplier: _dropped, ...rest } = draft as RestockDraft & { newSupplier?: string };
  return rest;
}

/** What the confirmation needs besides the server's answer: who supplied it and the prices before. */
export interface RestockContext {
  supplierName: string;
  before: Record<string, { sell?: number; cost?: number }>;
}

/** Suppliers a restock may choose: switched-off ones are left out, except one already chosen on the draft, shown with a note. */
export function supplierOptions(all: Supplier[], chosenId: string): Supplier[] {
  return all.filter((s) => s.active !== false || s.id === chosenId);
}

export function RestockForm({ onSaved, draftName, fixSearch, fromEdit = false, initial, again = false, replaces }: {
  onSaved?: (p: Purchase, context: RestockContext) => void;
  draftName?: string;
  /** Opened from Edit (issue #830): online only, like an edit, even when the delivery was voided already. */
  fromEdit?: boolean;
  /** Where Fix finds this form again for an entry kept on the phone (issue #812): Buy again opens on its purchase. */
  fixSearch?: Record<string, string>;
  initial?: RestockDraft;
  again?: boolean;
  /** Edit a delivery (issue #830): saving voids this purchase (the correct-this-purchase path) and saves the form as a new one that names it. */
  replaces?: Purchase;
}) {
  const { me, branch } = useStaff();
  const canProfit = useProfitAccess();
  // A new selling price applies in every shop, so only someone who may change prices sets it here (ADR-055); Purchasing given
  // to a seller keeps the item's price, which the form sends back unchanged.
  const canPrice = (me.permissions ?? []).includes('retail.price.edit');
  // Changing how many usually come in a pack changes the item, so only someone who manages items is offered it (D23).
  const canManageItems = (me.permissions ?? []).includes('retail.catalogue.manage');
  const canChangePrices = (me.permissions ?? []).includes('retail.price.edit');
  const queryClient = useQueryClient();
  const branches = (me.branches ?? []).filter((b) => b.id && b.stocks_goods !== false);
  // A restock entered again after a void (issue #351) has a draft of its own, so a restock in progress is never overwritten.
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<RestockDraft>(draftName ?? `restock:${me.user_id ?? ''}`, initial ? withoutTypedSupplier(initial) : {
    supplierId: '', purchasedOn: businessToday(), method: 'cash', lines: [],
  }, withoutTypedSupplier);
  const { supplierId, purchasedOn, method, lines } = draft;
  const field = <K extends keyof RestockDraft>(k: K) => (v: RestockDraft[K]) => setDraft((d) => ({ ...d, [k]: v }));
  const setSupplierId = field('supplierId');
  const setPurchasedOn = field('purchasedOn');
  const setMethod = field('method');
  const setLines = (f: (ls: RestockLine[]) => RestockLine[]) => setDraft((d) => ({ ...d, lines: f(d.lines) }));
  const suppliers = useQuery({ queryKey: ['retail', 'suppliers'], queryFn: () => retail.listSuppliers() });
  // ADR-120: only the owner, at every branch the goods go into, sees the date and may choose an earlier one.
  const into = [...new Set(lines.flatMap((l) => Object.keys(l.qtyByBranch)))];
  // Issue #812: offline, or when the network drops, a new delivery is kept on the phone; an edit stays online only. Offline the
  // phone dates it and no date box shows, so a draft's earlier day never holds the save (second review of #839).
  const entries = useEntrySave();
  const dated = entryDate(into.every((b) => holdsAt(me, BACKDATE, b)) && holdsAt(me, BACKDATE, undefined), entries.offlineMode ? businessToday() : purchasedOn, draft.dateReason, businessToday());
  // Issue #819: the optional receipt photo goes after the delivery is saved and said, never holding the form, and never with one
  // kept on the phone; it is cleared on every save, so it never goes with the next delivery.
  const [receipt, setReceipt] = useState<File | null>(null);
  // success-screen: the purchase saved screen that replaces the form; an edited delivery goes back to Past purchases (issue #830)
  const save = useMutation({
    meta: { success: ({ saved }: { saved: Purchase }) => (replaces ? 'Delivery updated' : saved?.purchase_no ? `Purchase ${saved.purchase_no} saved` : 'Purchase saved') },
    mutationFn: async () => {
      const supplierName = (suppliers.data ?? []).find((x) => x.id === supplierId)?.name ?? '';
      const before = Object.fromEntries(lines.map((l) => [l.product.id ?? '', { sell: l.product.sell_minor, cost: l.product.cost_minor }]));
      // Edit (issue #830): one call voids the old delivery and saves this one naming it, in one transaction on the server, so
      // any refusal (its stock moved, an item switched off) leaves the old delivery as it was. The draft's key covers a retry.
      const request = buildPurchase({ supplierId, purchasedOn: dated.sent?.date, dateReason: dated.sent?.reason, method, lines });
      if (replaces?.id) return { saved: await retail.replacePurchase(replaces.id, request, key), context: { supplierName, before } };
      // Opened from Edit (a delivery voided already, entered again): online only like an edit, so a dropped connection is an
      // error on the form, never an entry kept on the phone (fifth review of #839). The draft's key covers a retry.
      if (fromEdit) return { saved: await retail.createPurchase(request, key), context: { supplierName, before } };
      const saved = await entries.save(
        {
          kind: 'purchase', body: request, key, branchId: here ?? branches[0]?.id ?? '',
          detail: [supplierName, `${lines.length} ${lines.length === 1 ? 'item' : 'items'}`].filter(Boolean).join(', '),
          fix: { to: '/staff/retail/restock', ...(fixSearch ? { search: fixSearch } : {}), draftName: draftName ?? `restock:${me.user_id ?? ''}`, draft },
        },
        () => retail.createPurchase(request, key),
      );
      return isKept(saved) ? saved : { saved, context: { supplierName, before } };
    },
    onSuccess: (result) => {
      setReceipt(null);
      if (!isKept(result)) {
        // Edit (issue #830): with no new photo chosen, the edited delivery keeps the photo of the one it replaces, as an expense does.
        if (receipt || !replaces?.id || !replaces.has_receipt_photo) sendReceiptPhotoAfter('delivery', result.saved.id, receipt);
        else void bounded((signal) => retail.receiptPhoto('delivery', replaces.id ?? '', signal)).then((b) => sendReceiptPhotoAfter('delivery', result.saved.id, new File([b], 'receipt', { type: b.type })), () => sayDone(RECEIPT_LATER));
      }
      entry.saved();
      if (isKept(result)) {
        discard();
        return;
      }
      const { saved, context } = result;
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(saved, context);
    },
    onError: (e) => { if (replaces && listIsStale(e)) void queryClient.invalidateQueries({ queryKey: ['retail'] }); },
  });

  const unitsOn = capabilityOn(me, 'retail', UNITS);
  // A scan (issue #345): the item at 1 for this branch, or one more on its line in the pack the barcode is on.
  const here = branch && branches.some((b) => b.id === branch) ? branch : undefined;
  /** A new line of the item at its current prices, with the quantity at this branch. */
  const lineOf = (p: Product, qty: string): RestockLine => ({ product: p, cost: p.cost_minor !== undefined ? String(p.cost_minor) : '', sell: p.sell_minor !== undefined ? String(p.sell_minor) : '', qtyByBranch: here ? { [here]: qty } : {} });
  // Every add says so (issue #498): a short toast with the quantity at this branch, and the line flashes once.
  const { added, already, flashOf, show, toast } = useItemAdded();
  // The lines as of the last add, as on the sale: a scan answered after a render, or two before one, sees the latest.
  const current = useRef(lines);
  current.current = lines;
  /** Puts the new lines on the draft and says which line changed and what it now holds here. */
  const land = (p: Product, next: RestockLine[]) => {
    const { index } = touchedLine(current.current, next);
    current.current = next;
    setLines(() => next);
    if (index >= 0) added(String(index), p.description, here ? next[index]?.qtyByBranch[here] : undefined);
  };
  const add = (p: Product) => {
    if (current.current.some((l) => l.product.id === p.id) && !(unitsOn && buyingPacks(p).length > 0)) {
      already(`${p.description} is already on the purchase. Change its quantity below.`);
      return;
    }
    // Issue #699 (ADR-107): the line opens on the level the shop usually buys the item in, with its price per that level.
    const usual = unitsOn ? usualBuyingPack(p) : undefined;
    land(p, [...current.current, usual?.unit_id ? withUnit(lineOf(p, ''), usual.unit_id) : lineOf(p, '')]);
  };
  const scanAdd = (p: Product, unitId?: string) => {
    const unit = unitsOn && buyingPacks(p).some((k) => k.unit_id === unitId) ? unitId : undefined;
    if (unitId && !unit) return PACK_UNUSABLE;
    // Without a stocking branch chosen at the top, the scanned quantity would go nowhere.
    if (!here) return 'Choose the branch at the top of the page first, so the scanned item is counted there.';
    const fresh = lineOf(p, '1');
    land(p, scanInto(current.current, p, unit, (l) => ({ ...l, qtyByBranch: { ...l.qtyByBranch, [here]: plusOne(l.qtyByBranch[here]) } }), () => (unit ? withUnit(fresh, unit) : fresh)));
    return undefined;
  };
  // Issue #505: an item added here goes on the purchase with what was typed for it and its quantity at this branch. An item already
  // on the purchase (one chosen from the name warning) keeps its line and prices, and the quantity is added to it, as a scan adds.
  const addNew = ({ product, qty, cost, sell }: AddedItem) => {
    const milli = qty ? parseQty(qty) : null;
    const fresh = { ...lineOf(product, qty), ...(cost ? { cost } : {}), ...(sell !== undefined ? { sell } : {}) };
    land(product, scanInto(current.current, product, undefined, (l) => (here && milli ? { ...l, qtyByBranch: { ...l.qtyByBranch, [here]: plusOne(l.qtyByBranch[here], milli) } } : l), () => fresh));
  };
  const hereBranch = branches.find((b) => b.id === here);
  const patch = (i: number, change: Partial<RestockLine>) => setLines((ls) => ls.map((l, n) => (n === i ? { ...l, ...change } : l)));
  const patchWhole = (i: number, line: RestockLine) => setLines((ls) => ls.map((l, n) => (n === i ? line : l)));

  const problems = lines.map(restockLineProblem);
  const supplierProblem = creditNeedsSupplier(method, supplierId);
  const blocked = lines.length === 0 || problems.some((p) => p !== null) || supplierProblem !== null || dated.reasonMissing;
  // Issue #353: each line against the item's own purchases, said before saving, never blocking.
  const norms = useEntryNorms(lines.map((l) => l.product.id));
  const warnings = lines.flatMap((l, i): EntryWarning[] => {
    const pack = unitsOn ? chosenPack(l.product, l.unitId) : undefined;
    const total = Object.values(l.qtyByBranch).reduce((sum, q) => sum + (branchQty(q) ?? 0), 0);
    const first = branches.find((b) => (branchQty(l.qtyByBranch[b.id ?? '']) ?? 0) > 0);
    return entryWarnings(norms(l.product.id), {
      screen: 'buy', product: l.product, lineKey: `${l.product.id}-${i}`, qtyMilli: total > 0 ? total : null, qtyFieldId: `q-${i}-${first?.id ?? ''}`,
      unitChoosable: unitsOn && buyingPacks(l.product).length > 0, unitFieldId: `unit-${i}`,
      ...(pack ? { unitId: pack.unit_id, unitName: pack.unit, factor: lineFactor(l) ?? undefined } : {}),
    });
  });
  const entry = useEntryChecks('buy', here, warnings, () => save.mutate(), lines);
  // An edit is never kept on the phone (PR #832), also one of a delivery voided already that Edit enters again (lead's
  // decision on the fourth review of #839): offline it never becomes a new purchase in the outbox.
  const editOffline = (replaces !== undefined || fromEdit) && entries.offlineMode;

  if (entries.kept) return <KeptOnPhone item={entries.kept} onNew={entries.another} newLabel="Record another delivery" />;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked && !save.isPending && !editOffline) entry.check(); }}>
      {editOffline ? <EditNeedsInternet /> : entries.offlineMode && <OfflineFormNote what="delivery" />}
      <Note>Saving a purchase changes the buying and selling price of every item below to the values you enter. The change is recorded in the price history.</Note>
      <FieldsNeededNote />

      <div data-tour="restock-supplier">
      {/* Issue #688: a supplier who is not in the list is added in a sheet over the form and chosen; the lines stay. */}
      <PickerWithAdd
        id="supplier"
        label="Supplier"
        thing="supplier"
        placeholder="No supplier"
        options={supplierOptions(suppliers.data ?? [], supplierId).map((s) => ({ value: s.id ?? '', label: s.active === false ? `${s.name ?? ''} (switched off)` : s.name ?? '' }))}
        value={supplierId}
        onChange={setSupplierId}
        canAdd={canUse(me, 'suppliers')}
        required={method === 'credit'}
        loading={suppliers.data === undefined}
        load={suppliers}
        empty="No suppliers yet."
        ask="Ask the owner to add a supplier first."
        form={(done, cancel) => (
          <AddByName
            id="supplier-add"
            onCancel={cancel}
            add={{
              label: 'Add a supplier',
              name: 'Name of the new supplier',
              send: 'Add supplier',
              maxLength: 120,
              create: (name) => retail.createSupplier({ name }),
              onAdded: (s: Supplier) => {
                queryClient.setQueryData<Supplier[]>(['retail', 'suppliers'], (was) => was && [...was.filter((x) => x.id !== s.id), s]);
                void queryClient.invalidateQueries({ queryKey: ['retail', 'suppliers'] });
                show(`Added the supplier ${s.name ?? ''}.`, 'success');
                done({ value: s.id ?? '', label: s.name ?? '' });
              },
            }}
          />
        )}
      />
      </div>
      {/* Issue #812: a delivery kept on the phone is dated when it was made, so no date is asked offline. */}
      {!entries.offlineMode && <DateField id="bought-on" label="Bought on" entry={dated} value={dated.day} reason={draft.dateReason} onChange={setPurchasedOn} onReason={(dateReason) => setDraft((d) => ({ ...d, dateReason }))} />}
      {again && dated.earlier && (
        <p role="note" className="alert alert-warning" data-testid="date-warning">
          This purchase is dated {showDate(dated.day)}. The cash book of that day may be banked already, and saving will change its figures. Use today's date unless the goods really arrived then.
        </p>
      )}

      <div data-tour="restock-search">
      <ProductPicker id="restock-search" showStock={false} onAdd={add} />
      </div>
      <ScreenToast.Provider value={show}>
        <NewItemHere id="restock-new" askCost qtyLabel={hereBranch ? `Quantity bought (${branchLabel(hereBranch)})` : undefined} onAdded={addNew} />
      </ScreenToast.Provider>
      <ScanBar id="restock-scan" onFound={scanAdd} autoFocus toasts />

      <h2>Items</h2>
      {lines.length === 0 && <p className="empty-state">No items yet. Type an item above and tap it in the list, or scan it.</p>}
      {lines.map((l, i) => {
        const newSell = parseMinor(l.sell, RETAIL_CURRENCY);
        const enteredCost = parseMinor(l.cost, RETAIL_CURRENCY);
        const packs = unitsOn ? buyingPacks(l.product) : [];
        const pack = unitsOn ? chosenPack(l.product, l.unitId) : undefined;
        // The product's cost is per base unit: a pack cost is divided by this delivery's size, rounded half up, as the server does.
        const factor = lineFactor(l);
        const newCost = enteredCost !== null && pack ? (factor ? baseCostMinor(enteredCost, factor) : null) : enteredCost;
        const sizeDiffers = pack !== undefined && factor !== null && factor !== undefined && !sameFactor(factor, pack.factor);
        return (
          <div key={`${l.product.id}-${i}`} className="rt-card line-card">
            {flashOf(String(i))}
            <strong>{l.product.description}</strong>
            {packs.length > 0 && (
              <div>
                <label htmlFor={`unit-${i}`}>Bought by</label>
                {/* empty: drawn only when the item has packs; its own unit is always there. */}
                <select id={`unit-${i}`} value={pack?.unit_id ?? ''} onChange={(e) => patchWhole(i, withUnit(l, e.target.value))}>
                  <option value="">{unitWord(l.product.unit)}</option>
                  {packs.map((k) => <option key={k.unit_id} value={k.unit_id}>{unitWord(k.unit)} ({quantityIn(k.factor ?? '', l.product.unit ?? '')})</option>)}
                </select>
              </div>
            )}
            {pack && chosenUnitHint(pack.unit, l.product.unit) && <p className="hint" data-testid="unit-hint">{chosenUnitHint(pack.unit, l.product.unit)}</p>}
            {pack && (
              <div data-testid={`pack-size-${i}`}>
                <Field id={`size-${i}`} required inputMode="decimal" label={howManyInEach(unitWord(l.product.unit) || 'item', unitWord(pack.unit) || 'pack')}
                  hint={`Usually ${showQty(pack.factor ?? '')}. Change it when this delivery's ${unitWord(pack.unit)} held a different number.`}
                  error={factor === null ? 'Enter 1 or more, with at most three decimals.' : null}
                  value={l.packSize ?? showQty(pack.factor ?? '')} onChange={(e) => patch(i, { packSize: e.target.value })} />
                {sizeDiffers && canManageItems && !mayKeepSize(pack, canManageItems, canChangePrices) && <p className="hint">{OWN_PRICE_KEEPS_SIZE}</p>}
                {sizeDiffers && mayKeepSize(pack, canManageItems, canChangePrices) && (
                  <label>
                    <input type="checkbox" checked={l.fromNowOn === true} onChange={(e) => patch(i, { fromNowOn: e.target.checked })} />
                    <span>{useFromNowOn(showQty(factor ?? ''))}</span>
                  </label>
                )}
                {sizeDiffers && (
                  <p className="hint" data-testid={`pack-size-note-${i}`}>
                    {l.fromNowOn && mayKeepSize(pack, canManageItems, canChangePrices)
                      ? `Every ${unitWord(pack.unit)} bought or sold after this one counts as ${showQty(factor ?? '')}.`
                      : `Only this delivery counts as ${showQty(factor ?? '')} in each ${unitWord(pack.unit)}. A ${unitWord(pack.unit)} sold is still ${showQty(pack.factor ?? '')}.`}
                  </p>
                )}
              </div>
            )}
            <div className="rt-row">
              <Field id={`cost-${i}`} required inputMode="numeric" label={`${WORDS.buyingPrice} each${pack ? ` (per ${unitWord(pack.unit)})` : ''}`} value={l.cost} onChange={(e) => patch(i, { cost: e.target.value })} />
              <Field id={`sell-${i}`} required={canPrice} inputMode="numeric" label={`${WORDS.sellingPrice} each${pack ? ` (per ${unitWord(l.product.unit)})` : ''}`} value={l.sell} disabled={!canPrice}
                hint={canPrice ? undefined : 'Only the owner changes selling prices.'} onChange={(e) => patch(i, { sell: e.target.value })} />
            </div>
            <fieldset>
              <legend>Quantity bought per branch ({unitWords(pack?.unit ?? l.product.unit)})</legend>
              {branches.map((b) => (
                <div key={b.id}>
                  <label htmlFor={`q-${i}-${b.id}`}>{branchLabel(b)}</label>
                  <input id={`q-${i}-${b.id}`} inputMode="decimal" value={l.qtyByBranch[b.id ?? ''] ?? ''} placeholder="0"
                    onChange={(e) => patch(i, { qtyByBranch: { ...l.qtyByBranch, [b.id ?? '']: e.target.value } })} />
                </div>
              ))}
            </fieldset>
            {entry.notes(`${l.product.id}-${i}`)}
            <p>
              {newSell !== null && newSell !== l.product.sell_minor ? `Sell price will change from ${money(l.product.sell_minor ?? 0)} to ${money(newSell)}.` : 'Sell price stays the same.'}
              {canProfit && newCost !== null && l.product.cost_minor !== undefined && newCost !== l.product.cost_minor ? ` Last paid will change from ${money(l.product.cost_minor)} to ${money(newCost)}.` : ''}
            </p>
            {problems[i] && <p className="rt-flag">{problems[i]}</p>}
            <button type="button" className="btn-ghost" onClick={() => setLines((ls) => ls.filter((_, n) => n !== i))} aria-label={`Remove ${l.product.description}`}>Remove</button>
          </div>
        );
      })}

      <fieldset data-tour="restock-paid">
        <legend>{WORDS.paidHow}<span className="field-star" aria-hidden="true"> *</span></legend>
        {METHODS.map((m) => (
          <label key={m.value} style={{ fontWeight: 400 }}>
            <input type="radio" name="purchase-method" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={method === m.value} onChange={() => setMethod(m.value)} />
            {m.label}
          </label>
        ))}
        {method === 'credit' && <p className="hint">The supplier is owed this. Pay them later from Money, {WORDS.whatIOweSuppliers}.</p>}
      </fieldset>
      {supplierProblem && <p role="alert" className="rt-flag" data-testid="credit-needs-supplier">{supplierProblem}</p>}

      {/* Issue #821: who reads profit at every branch on the form sees what the delivery should sell for; anyone else, the total cost as before. */}
      {canProfit && into.every((b) => holdsAt(me, PROFIT, b)) ? (
        <div className="rt-total rt-total-figures" aria-live="polite" data-testid="restock-total"><DeliveryWorth paid={restockTotal(lines)} worth={restockWorth(lines)} label="This delivery" /></div>
      ) : (
        <p className="rt-total" aria-live="polite" data-testid="restock-total">Total cost <Money minor={restockTotal(lines)} /></p>
      )}
      <ReceiptPhotoChoice id="restock-receipt" file={receipt} onChange={setReceipt} />
      <Problem error={save.error} />
      <ActionBar>
        <BusyButton type="submit" className="rt-primary" data-tour="restock-save" busy={save.isPending} notReady={blocked}>{replaces ? 'Save changes' : 'Save the purchase'}</BusyButton>
      </ActionBar>

      {lines.length > 0 && !save.isPending && (
        <button type="button" onClick={() => { discard(); setReceipt(null); save.reset(); }}>Clear this purchase</button>
      )}
      {toast}
    </form>
  );
}

/** "Sell price changed from UGX 1,200 to UGX 1,400." for each line whose price moved (#112 item 11). */
export function priceChanges(p: Purchase, before: RestockContext['before'], showCost: boolean): string[] {
  return (p.lines ?? []).flatMap((l) => {
    const was = before[l.product_id ?? ''] ?? {};
    const out: string[] = [];
    if (l.sell_minor !== undefined && was.sell !== undefined && l.sell_minor !== was.sell) {
      out.push(`${l.description}: sell price changed from ${money(was.sell)} to ${money(l.sell_minor)}.`);
    }
    if (showCost && l.cost_minor !== undefined && was.cost !== undefined && l.cost_minor !== was.cost) {
      out.push(`${l.description}: last paid changed from ${money(was.cost)} to ${money(l.cost_minor)}.`);
    }
    return out;
  });
}

export function RestockSaved({ purchase, context, onNew }: { purchase: Purchase; context: RestockContext; onNew?: () => void }) {
  const changes = priceChanges(purchase, context.before, useProfitAccess());
  const count = (purchase.lines ?? []).length;
  const { me, chooseBranch } = useStaff();
  // The first store (keeps goods, sells none) this purchase put stock into.
  const into = new Set((purchase.lines ?? []).flatMap((l) => (l.qty_by_branch ?? []).map((q) => q.branch_id)));
  const store = (me.branches ?? []).find((b) => b.id && into.has(b.id) && b.stocks_goods !== false && b.sells_goods === false);
  return (
    <section aria-label="Purchase saved">
      <h2>Purchase saved{purchase.purchase_no ? ` (${purchase.purchase_no})` : ''}</h2>
      <p>
        From {context.supplierName || 'no supplier'}, bought on {showDate(purchase.purchased_on)}: {count} {count === 1 ? 'item' : 'items'}, total cost{' '}
        {money(purchase.total_minor ?? 0)}.
      </p>
      {(purchase.lines ?? []).filter((l) => l.size_kept && l.size_kept_note).map((l) => (
        <Note key={`kept-${l.id}`}>{`${l.description}: ${l.size_kept_note}`}</Note>
      ))}
      {(purchase.lines ?? []).some((l) => l.unit) && (
        <ul>
          {(purchase.lines ?? []).filter((l) => l.unit).map((l) => (
            <li key={l.id}>{l.description}: {showQty(l.unit_qty_total ?? '0')} {l.unit} at {money(l.unit_cost_minor ?? 0)} each</li>
          ))}
        </ul>
      )}
      {changes.length > 0 ? (
        <ul>{changes.map((c) => <li key={c}>{c}</li>)}</ul>
      ) : (
        <p>No price changed.</p>
      )}
      {/* Issue #700: the next jobs. Goods bought into a store are moved to a shop from that store, so the store is chosen first. */}
      {/* Buy more stock stays the primary: a clear form here, or Buy stock where the purchase is only shown. */}
      <NextSteps at="buyDone" lead steps={[
        onNew ? { label: 'Buy more stock', onClick: onNew } : { label: 'Buy more stock', screen: 'restock', to: '/staff/retail/restock' },
        { label: 'Move to the shop', screen: 'transfer', to: '/staff/retail/transfer', onClick: () => chooseBranch?.(store?.id ?? ''), show: store !== undefined && chooseBranch !== undefined && holdsAt(me, TRANSFER, store.id) },
        { label: 'My stock', screen: 'stock', to: '/staff/retail/stock' },
      ]} />
    </section>
  );
}

/**
 * A voided restock entered again (issue #351): the form opens with its lines, supplier, date and payment, in a draft of its own, and
 * a line says which restock it replaces. Nothing is saved until the person saves it.
 */
export function Again({ purchaseId, onSaved, edit = false, onEdited }: {
  purchaseId: string;
  onSaved: (p: Purchase, context: RestockContext) => void;
  edit?: boolean;
  /** An edit saved (issue #830); a delivery found voided already is entered again instead and ends in onSaved. */
  onEdited?: (p: Purchase, context: RestockContext) => void;
}) {
  const { me } = useStaff();
  const loaded = useQuery({
    queryKey: ['retail', edit ? 'restock-edit' : 'restock-again', purchaseId],
    queryFn: async () => {
      const purchase = await retail.getPurchase(purchaseId);
      const products = await Promise.all((purchase.lines ?? []).map((l) => retail.getProduct(l.product_id ?? '')));
      // Edit keeps the delivery's own date, as the old app did; the date warning says when that day may be banked.
      const draft = redraftOf(purchase, products, businessToday());
      return { purchase, draft: edit && purchase.purchased_on ? { ...draft, purchasedOn: purchase.purchased_on } : draft };
    },
    gcTime: 0,
  });
  if (loaded.isPending) return <p className="loading">Loading</p>;
  if (!loaded.data) return <Problem error={loaded.error} />;
  const { purchase } = loaded.data;
  // Issue #830: Edit a delivery that is still recorded; one voided already is entered again as before.
  if (edit && purchase.status !== 'voided') {
    return (
      <>
        <Note>
          You are editing delivery {purchase.purchase_no}. Change what was wrong, then save. The old entry is kept in Past purchases, marked Voided, and the new one says it replaces it.
        </Note>
        <RestockForm draftName={`restock-edit:${me.user_id ?? ''}:${purchaseId}`} initial={loaded.data.draft} onSaved={onEdited ?? onSaved} again replaces={purchase} />
      </>
    );
  }
  return (
    <>
      <Note>
        You are entering purchase {purchase.purchase_no} again{purchase.status === 'voided' ? ', which you voided' : ''}. Check each quantity, unit and price, then save.
      </Note>
      <RestockForm draftName={`restock-again:${me.user_id ?? ''}:${purchaseId}`} fixSearch={edit ? { edit: purchaseId } : { again: purchaseId }} fromEdit={edit} initial={loaded.data.draft} onSaved={onSaved} again />
    </>
  );
}

export function RestockPage() {
  const [done, setDone] = useState<{ purchase: Purchase; context: RestockContext } | null>(null);
  const [round, setRound] = useState(0);
  const { again, edit } = useRouterState({ select: (state) => state.location.search }) as { again?: string; edit?: string };
  const navigate = useNavigate();
  return (
    <Gate screen="restock" title={WORDS.buyStock}>
      <Link className="link-row" data-tour="restock-past" to="/staff/retail/restocks">Past purchases, to correct a mistake<RowChevron /></Link>
      {done ? (
        <RestockSaved purchase={done.purchase} context={done.context} onNew={() => { setDone(null); setRound((n) => n + 1); }} />
      ) : typeof edit === 'string' && edit !== '' ? (
        // Issue #830: as in the old app, saving an edit goes back to the list, where the new delivery names the one it replaces.
        <Again purchaseId={edit} edit onEdited={() => void navigate({ to: '/staff/retail/restocks' })} onSaved={(purchase, context) => setDone({ purchase, context })} />
      ) : typeof again === 'string' && again !== '' ? (
        <Again purchaseId={again} onSaved={(purchase, context) => setDone({ purchase, context })} />
      ) : (
        <RestockForm key={round} onSaved={(purchase, context) => setDone({ purchase, context })} />
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/restock')({ component: RestockPage });
