import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useRouterState } from '@tanstack/react-router';
import { useState } from 'react';
import { RETAIL_CURRENCY, businessToday, retail, type Product, type Supplier, type Purchase, type PurchasePayment, type PurchaseRequest } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { parseMinor } from '../../../components/money';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { lineTotalMinor, parseQty, qtyString, showDate, showQty } from './maths';
import { chosenUnitHint, redraftOf } from './restock-correct-state';
import { Gate, Note, Problem, ProductPicker, money, useProfitAccess } from './ui';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne, scanInto } from './barcode-state';
import { UNITS, capabilityOn } from './permissions';
import { activePacks, baseCostMinor, baseQtyMilli, chosenPack, deliveredFactor, packPriceMinor, sameFactor } from './units-state';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field, FieldsNeededNote } from '../../../app/ui/Field';
import { WORDS, howManyInEach, plural, useFromNowOn } from '../../../app/glossary';
import { NewItemHere, type AddedItem } from './new-item-here';
import { RowChevron } from '../../../app/ui/ListRow';

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
  if (pack && factor === null) return `Enter how many ${plural(l.product.unit ?? 'item')} were in each ${pack.unit}: 1 or more.`;
  if (pack && qtys.some((q) => q !== null && baseQtyMilli(q, factor ?? undefined) === null)) {
    return `A quantity does not convert exactly to ${l.product.unit ?? 'items'}. Change it.`;
  }
  return null;
}

export function restockTotal(lines: RestockLine[]): number {
  return lines.reduce((sum, l) => {
    const cost = parseMinor(l.cost, RETAIL_CURRENCY) ?? 0;
    return sum + Object.values(l.qtyByBranch).reduce((s, q) => s + lineTotalMinor(cost, branchQty(q) ?? 0), 0);
  }, 0);
}

export function buildPurchase(input: { supplierId: string; purchasedOn: string; method: PurchasePayment; lines: RestockLine[] }): PurchaseRequest {
  return {
    ...(input.supplierId ? { supplier_id: input.supplierId } : {}),
    purchased_on: input.purchasedOn,
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

const METHODS: { value: PurchasePayment; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'bank', label: 'Bank' },
  { value: 'credit', label: 'Credit (pay the supplier later)' },
];

/** What the restock form keeps with its key in this tab until saved or cleared (#77). */
export interface RestockDraft {
  supplierId: string;
  newSupplier: string;
  purchasedOn: string;
  method: PurchasePayment;
  lines: RestockLine[];
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

export function RestockForm({ onSaved, draftName, initial, again = false }: { onSaved?: (p: Purchase, context: RestockContext) => void; draftName?: string; initial?: RestockDraft; again?: boolean }) {
  const { me, branch } = useStaff();
  const canProfit = useProfitAccess();
  // A new selling price applies in every shop, so only someone who may change prices sets it here (ADR-055); Purchasing given
  // to a seller keeps the item's price, which the form sends back unchanged.
  const canPrice = (me.permissions ?? []).includes('retail.price.edit');
  // Changing how many usually come in a pack changes the item, so only someone who manages items is offered it (D23).
  const canManageItems = (me.permissions ?? []).includes('retail.catalogue.manage');
  const queryClient = useQueryClient();
  const branches = (me.branches ?? []).filter((b) => b.id && b.stocks_goods !== false);
  // A restock entered again after a void (issue #351) has a draft of its own, so a restock in progress is never overwritten.
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<RestockDraft>(draftName ?? `restock:${me.user_id ?? ''}`, initial ?? {
    supplierId: '', newSupplier: '', purchasedOn: businessToday(), method: 'cash', lines: [],
  });
  const { supplierId, newSupplier, purchasedOn, method, lines } = draft;
  const field = <K extends keyof RestockDraft>(k: K) => (v: RestockDraft[K]) => setDraft((d) => ({ ...d, [k]: v }));
  const setSupplierId = field('supplierId');
  const setNewSupplier = field('newSupplier');
  const setPurchasedOn = field('purchasedOn');
  const setMethod = field('method');
  const setLines = (f: (ls: RestockLine[]) => RestockLine[]) => setDraft((d) => ({ ...d, lines: f(d.lines) }));
  const suppliers = useQuery({ queryKey: ['retail', 'suppliers'], queryFn: () => retail.listSuppliers() });

  const save = useMutation({
    mutationFn: async () => {
      let id = supplierId;
      let supplierName = (suppliers.data ?? []).find((x) => x.id === supplierId)?.name ?? '';
      const before = Object.fromEntries(lines.map((l) => [l.product.id ?? '', { sell: l.product.sell_minor, cost: l.product.cost_minor }]));
      if (!id && newSupplier.trim()) {
        const created = await retail.createSupplier({ name: newSupplier.trim() });
        id = created.id ?? '';
        supplierName = created.name ?? newSupplier.trim();
        // Kept in the draft, so a retry after a failed restock does not add the supplier twice.
        setDraft((d) => ({ ...d, supplierId: id, newSupplier: '' }));
      }
      const saved = await retail.createPurchase(buildPurchase({ supplierId: id, purchasedOn, method, lines }), key);
      return { saved, context: { supplierName, before } };
    },
    onSuccess: ({ saved, context }) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(saved, context);
    },
  });

  const unitsOn = capabilityOn(me, 'retail', UNITS);
  // A scan (issue #345): the item at 1 for this branch, or one more on its line in the pack the barcode is on.
  const here = branch && branches.some((b) => b.id === branch) ? branch : undefined;
  /** A new line of the item at its current prices, with the quantity at this branch. */
  const lineOf = (p: Product, qty: string): RestockLine => ({ product: p, cost: p.cost_minor !== undefined ? String(p.cost_minor) : '', sell: p.sell_minor !== undefined ? String(p.sell_minor) : '', qtyByBranch: here ? { [here]: qty } : {} });
  const add = (p: Product) =>
    setLines((ls) => (ls.some((l) => l.product.id === p.id) && !(unitsOn && activePacks(p).length > 0) ? ls : [...ls, lineOf(p, '')]));
  const scanAdd = (p: Product, unitId?: string) => {
    const unit = unitsOn && activePacks(p).some((k) => k.unit_id === unitId) ? unitId : undefined;
    if (unitId && !unit) return PACK_UNUSABLE;
    // Without a stocking branch chosen at the top, the scanned quantity would go nowhere.
    if (!here) return 'Choose the branch at the top of the page first, so the scanned item is counted there.';
    const fresh = lineOf(p, '1');
    setLines((ls) => scanInto(ls, p, unit, (l) => ({ ...l, qtyByBranch: { ...l.qtyByBranch, [here]: plusOne(l.qtyByBranch[here]) } }), () => (unit ? withUnit(fresh, unit) : fresh)));
    return undefined;
  };
  // Issue #505: an item added here goes on the purchase with what was typed for it and its quantity at this branch. An item already
  // on the purchase (one chosen from the name warning) keeps its line and prices, and the quantity is added to it, as a scan adds.
  const addNew = ({ product, qty, cost, sell }: AddedItem) => {
    const milli = qty ? parseQty(qty) : null;
    const fresh = { ...lineOf(product, qty), ...(cost ? { cost } : {}), ...(sell !== undefined ? { sell } : {}) };
    setLines((ls) => scanInto(ls, product, undefined, (l) => (here && milli ? { ...l, qtyByBranch: { ...l.qtyByBranch, [here]: plusOne(l.qtyByBranch[here], milli) } } : l), () => fresh));
  };
  const hereBranch = branches.find((b) => b.id === here);
  const patch = (i: number, change: Partial<RestockLine>) => setLines((ls) => ls.map((l, n) => (n === i ? { ...l, ...change } : l)));
  const patchWhole = (i: number, line: RestockLine) => setLines((ls) => ls.map((l, n) => (n === i ? line : l)));

  const problems = lines.map(restockLineProblem);
  const blocked = lines.length === 0 || problems.some((p) => p !== null);

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked && !save.isPending) save.mutate(); }}>
      <Note>Saving a purchase changes the buying and selling price of every item below to the values you enter. The change is recorded in the price history.</Note>
      <FieldsNeededNote />

      <div data-tour="restock-supplier">
      <label htmlFor="supplier">Supplier</label>
      <select id="supplier" value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
        <option value="">No supplier</option>
        {supplierOptions(suppliers.data ?? [], supplierId).map((s) => (
          <option key={s.id} value={s.id}>{s.active === false ? `${s.name} (switched off)` : s.name}</option>
        ))}
      </select>
      </div>
      {supplierId === '' && (
        <>
          <label htmlFor="new-supplier">Or add a new supplier</label>
          <input id="new-supplier" value={newSupplier} onChange={(e) => setNewSupplier(e.target.value)} />
        </>
      )}
      <label htmlFor="bought-on">Bought on</label>
      <input id="bought-on" type="date" max={businessToday()} value={purchasedOn} onChange={(e) => setPurchasedOn(e.target.value)} />
      {again && purchasedOn < businessToday() && (
        <p role="note" className="alert alert-warning" data-testid="date-warning">
          This purchase is dated {showDate(purchasedOn)}. The cash book of that day may be banked already, and saving will change its figures. Use today's date unless the goods really arrived then.
        </p>
      )}

      <div data-tour="restock-search">
      <ProductPicker id="restock-search" showStock={false} onAdd={add} />
      </div>
      <NewItemHere id="restock-new" askCost qtyLabel={hereBranch ? `Quantity bought (${branchLabel(hereBranch)})` : undefined} onAdded={addNew} />
      <ScanBar id="restock-scan" onFound={scanAdd} autoFocus />

      <h2>Items</h2>
      {lines.length === 0 && <p className="empty-state">No items yet. Type an item above and tap it in the list, or scan it.</p>}
      {lines.map((l, i) => {
        const newSell = parseMinor(l.sell, RETAIL_CURRENCY);
        const enteredCost = parseMinor(l.cost, RETAIL_CURRENCY);
        const packs = unitsOn ? activePacks(l.product) : [];
        const pack = unitsOn ? chosenPack(l.product, l.unitId) : undefined;
        // The product's cost is per base unit: a pack cost is divided by this delivery's size, rounded half up, as the server does.
        const factor = lineFactor(l);
        const newCost = enteredCost !== null && pack ? (factor ? baseCostMinor(enteredCost, factor) : null) : enteredCost;
        const sizeDiffers = pack !== undefined && factor !== null && factor !== undefined && !sameFactor(factor, pack.factor);
        return (
          <div key={`${l.product.id}-${i}`} className="rt-card">
            <strong>{l.product.description}</strong>
            {packs.length > 0 && (
              <div>
                <label htmlFor={`unit-${i}`}>Bought by</label>
                <select id={`unit-${i}`} value={pack?.unit_id ?? ''} onChange={(e) => patchWhole(i, withUnit(l, e.target.value))}>
                  <option value="">{l.product.unit}</option>
                  {packs.map((k) => <option key={k.unit_id} value={k.unit_id}>{k.unit} ({showQty(k.factor ?? '')} {l.product.unit})</option>)}
                </select>
              </div>
            )}
            {pack && chosenUnitHint(pack.unit, l.product.unit) && <p className="hint" data-testid="unit-hint">{chosenUnitHint(pack.unit, l.product.unit)}</p>}
            {pack && (
              <div data-testid={`pack-size-${i}`}>
                <Field id={`size-${i}`} required inputMode="decimal" label={howManyInEach(l.product.unit ?? 'item', pack.unit ?? 'pack')}
                  hint={`Usually ${showQty(pack.factor ?? '')}. Change it when this delivery's ${pack.unit} held a different number.`}
                  error={factor === null ? 'Enter 1 or more, with at most three decimals.' : null}
                  value={l.packSize ?? showQty(pack.factor ?? '')} onChange={(e) => patch(i, { packSize: e.target.value })} />
                {sizeDiffers && canManageItems && (
                  <label>
                    <input type="checkbox" checked={l.fromNowOn === true} onChange={(e) => patch(i, { fromNowOn: e.target.checked })} />
                    <span>{useFromNowOn(showQty(factor ?? ''))}</span>
                  </label>
                )}
                {sizeDiffers && (
                  <p className="hint" data-testid={`pack-size-note-${i}`}>
                    {l.fromNowOn && canManageItems
                      ? `Every ${pack.unit} bought or sold after this one counts as ${showQty(factor ?? '')}.`
                      : `Only this delivery counts as ${showQty(factor ?? '')} in each ${pack.unit}. A ${pack.unit} sold is still ${showQty(pack.factor ?? '')}.`}
                  </p>
                )}
              </div>
            )}
            <div className="rt-row">
              <Field id={`cost-${i}`} required inputMode="numeric" label={`Cost each${pack ? ` (per ${pack.unit})` : ''}`} value={l.cost} onChange={(e) => patch(i, { cost: e.target.value })} />
              <Field id={`sell-${i}`} required={canPrice} inputMode="numeric" label={`Sell price each${pack ? ` (per ${l.product.unit})` : ''}`} value={l.sell} disabled={!canPrice}
                hint={canPrice ? undefined : 'Only the owner changes selling prices.'} onChange={(e) => patch(i, { sell: e.target.value })} />
            </div>
            <fieldset>
              <legend>Quantity bought per branch ({pack?.unit ?? l.product.unit})</legend>
              {branches.map((b) => (
                <div key={b.id}>
                  <label htmlFor={`q-${i}-${b.id}`}>{branchLabel(b)}</label>
                  <input id={`q-${i}-${b.id}`} inputMode="decimal" value={l.qtyByBranch[b.id ?? ''] ?? ''} placeholder="0"
                    onChange={(e) => patch(i, { qtyByBranch: { ...l.qtyByBranch, [b.id ?? '']: e.target.value } })} />
                </div>
              ))}
            </fieldset>
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
        <legend>How is it paid?</legend>
        {METHODS.map((m) => (
          <label key={m.value} style={{ fontWeight: 400 }}>
            <input type="radio" name="purchase-method" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={method === m.value} onChange={() => setMethod(m.value)} />
            {m.label}
          </label>
        ))}
      </fieldset>

      <p className="rt-total" aria-live="polite">Total cost {money(restockTotal(lines))}</p>
      <Problem error={save.error} />
      <BusyButton type="submit" className="rt-primary" data-tour="restock-save" busy={save.isPending} notReady={blocked}>Save the purchase</BusyButton>

      {lines.length > 0 && !save.isPending && (
        <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this purchase</button>
      )}
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
  return (
    <section aria-label="Purchase saved">
      <h2>Purchase saved{purchase.purchase_no ? ` (${purchase.purchase_no})` : ''}</h2>
      <p>
        From {context.supplierName || 'no supplier'}, bought on {showDate(purchase.purchased_on)}: {count} {count === 1 ? 'item' : 'items'}, total cost{' '}
        {money(purchase.total_minor ?? 0)}.
      </p>
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
      {onNew && <button type="button" className="rt-primary" onClick={onNew}>Buy more stock</button>}
    </section>
  );
}

/**
 * A voided restock entered again (issue #351): the form opens with its lines, supplier, date and payment, in a draft of its own, and
 * a line says which restock it replaces. Nothing is saved until the person saves it.
 */
function Again({ purchaseId, onSaved }: { purchaseId: string; onSaved: (p: Purchase, context: RestockContext) => void }) {
  const { me } = useStaff();
  const loaded = useQuery({
    queryKey: ['retail', 'restock-again', purchaseId],
    queryFn: async () => {
      const purchase = await retail.getPurchase(purchaseId);
      const products = await Promise.all((purchase.lines ?? []).map((l) => retail.getProduct(l.product_id ?? '')));
      return { purchase, draft: redraftOf(purchase, products, businessToday()) };
    },
    gcTime: 0,
  });
  if (loaded.isPending) return <p className="loading">Loading</p>;
  if (!loaded.data) return <Problem error={loaded.error} />;
  return (
    <>
      <Note>
        You are entering purchase {loaded.data.purchase.purchase_no} again{loaded.data.purchase.status === 'voided' ? ', which you voided' : ''}. Check each quantity, unit and price, then save.
      </Note>
      <RestockForm draftName={`restock-again:${me.user_id ?? ''}:${purchaseId}`} initial={loaded.data.draft} onSaved={onSaved} again />
    </>
  );
}

export function RestockPage() {
  const [done, setDone] = useState<{ purchase: Purchase; context: RestockContext } | null>(null);
  const [round, setRound] = useState(0);
  const again = (useRouterState({ select: (state) => state.location.search }) as { again?: string }).again;
  return (
    <Gate screen="restock" title={WORDS.buyStock}>
      <Link className="link-row" data-tour="restock-past" to="/staff/retail/restocks">Past purchases, to correct a mistake<RowChevron /></Link>
      {done ? (
        <RestockSaved purchase={done.purchase} context={done.context} onNew={() => { setDone(null); setRound((n) => n + 1); }} />
      ) : typeof again === 'string' && again !== '' ? (
        <Again purchaseId={again} onSaved={(purchase, context) => setDone({ purchase, context })} />
      ) : (
        <RestockForm key={round} onSaved={(purchase, context) => setDone({ purchase, context })} />
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/restock')({ component: RestockPage });
