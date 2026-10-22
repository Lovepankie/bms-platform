import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useRouterState } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { RETAIL_CURRENCY, businessToday, retail, type Product, type Supplier, type Purchase, type PurchasePayment, type PurchaseRequest } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { parseMinor } from '../../../components/money';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { lineTotalMinor, parseQty, qtyString, showDate, showQty } from './maths';
import { chosenUnitHint, redraftOf } from './restock-correct-state';
import { CategoryLabel, Gate, Note, Problem, money, useProfitAccess } from './ui';
import { ScanBar } from './scan';
import { PACK_UNUSABLE, plusOne, scanInto } from './barcode-state';
import { UNITS, capabilityOn } from './permissions';
import { activePacks, baseCostMinor, baseQtyMilli, chosenPack, packPriceMinor } from './units-state';

// Restock (FR-RET-06): supplier, lines with cost, sell price and a quantity per branch. Saving sets
// the product's cost and sell price in the same transaction and leaves a price history row.

export interface RestockLine {
  product: Product;
  cost: string;
  sell: string;
  qtyByBranch: Record<string, string>;
  /** A pack unit of the product (ADR-033, units capability): cost and quantities are then in that unit. Absent means the base unit. */
  unitId?: string;
}

/** The line in another unit: the cost goes to that unit's equivalent of the current cost (a pack costs factor times a piece). */
export function withUnit(line: RestockLine, unitId: string): RestockLine {
  const pack = chosenPack(line.product, unitId || undefined);
  const base = line.product.cost_minor;
  return { ...line, unitId: pack ? unitId : undefined, cost: base === undefined ? '' : String(pack ? packPriceMinor(base, pack.factor) : base) };
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
  if (pack && qtys.some((q) => q !== null && baseQtyMilli(q, pack.factor) === null)) {
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
      ...(chosenPack(l.product, l.unitId) ? { unit_id: l.unitId } : {}),
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
  const search = useLiveSearch();

  const products = useQuery({
    queryKey: ['retail', 'products', 'all', search.term],
    queryFn: ({ signal }) => retail.listProducts({ query: search.term, signal }),
    placeholderData: keepPreviousData,
  });
  const finding = products.isPlaceholderData || products.isFetching;
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
  const add = (p: Product) =>
    setLines((ls) =>
      ls.some((l) => l.product.id === p.id) && !(unitsOn && activePacks(p).length > 0)
        ? ls
        : [...ls, { product: p, cost: p.cost_minor !== undefined ? String(p.cost_minor) : '', sell: p.sell_minor !== undefined ? String(p.sell_minor) : '', qtyByBranch: { ...(branch && branches.some((b) => b.id === branch) ? { [branch]: '' } : {}) } }],
    );
  // A scan (issue #345): the item at 1 for this branch, or one more on its line in the pack the barcode is on.
  const here = branch && branches.some((b) => b.id === branch) ? branch : undefined;
  const scanAdd = (p: Product, unitId?: string) => {
    const unit = unitsOn && activePacks(p).some((k) => k.unit_id === unitId) ? unitId : undefined;
    if (unitId && !unit) return PACK_UNUSABLE;
    // Without a stocking branch chosen at the top, the scanned quantity would go nowhere.
    if (!here) return 'Choose the branch at the top of the page first, so the scanned item is counted there.';
    const fresh: RestockLine = { product: p, cost: p.cost_minor !== undefined ? String(p.cost_minor) : '', sell: p.sell_minor !== undefined ? String(p.sell_minor) : '', qtyByBranch: here ? { [here]: '1' } : {} };
    setLines((ls) => scanInto(ls, p, unit, (l) => ({ ...l, qtyByBranch: { ...l.qtyByBranch, [here]: plusOne(l.qtyByBranch[here]) } }), () => (unit ? withUnit(fresh, unit) : fresh)));
    return undefined;
  };
  const patch = (i: number, change: Partial<RestockLine>) => setLines((ls) => ls.map((l, n) => (n === i ? { ...l, ...change } : l)));
  const patchWhole = (i: number, line: RestockLine) => setLines((ls) => ls.map((l, n) => (n === i ? line : l)));

  const problems = lines.map(restockLineProblem);
  const blocked = lines.length === 0 || problems.some((p) => p !== null);

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked && !save.isPending) save.mutate(); }}>
      <Note>Saving a restock changes the cost and sell price of every item below to the values you enter. The change is recorded in the price history.</Note>

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
      <input id="bought-on" type="date" value={purchasedOn} onChange={(e) => setPurchasedOn(e.target.value)} />
      {again && purchasedOn < businessToday() && (
        <p role="note" className="alert alert-warning" data-testid="date-warning">
          This restock is dated {showDate(purchasedOn)}. The cash book of that day may be banked already, and saving will change its figures. Use today's date unless the goods really arrived then.
        </p>
      )}

      <div data-tour="restock-search">
      <SearchBox id="restock-search" label="Find an item by name, code or category" search={search} fetching={finding} count={products.data ? Math.min(products.data.length, 20) : undefined} more={(products.data?.length ?? 0) > 20} />
      </div>
      <ScanBar id="restock-scan" onFound={scanAdd} />
      {products.data && products.data.length === 0 && search.term && <NoResults term={search.term} onClear={search.clear} noun="items" />}
      <SearchResults fetching={finding}>
      <ul style={{ listStyle: 'none', padding: 0, maxHeight: 180, overflowY: 'auto' }}>
        {(products.data ?? []).slice(0, 20).map((p) => (
          <li key={p.id} className="rt-card rt-row">
            <span><strong>{p.description}</strong> ({p.code})<br /><CategoryLabel category={p.category} /></span>
            <button type="button" onClick={() => add(p)} aria-label={`Add ${p.description}`}>Add</button>
          </li>
        ))}
      </ul>
      </SearchResults>

      <h2>Items</h2>
      {lines.length === 0 && <p className="empty-state">No items yet.</p>}
      {lines.map((l, i) => {
        const newSell = parseMinor(l.sell, RETAIL_CURRENCY);
        const enteredCost = parseMinor(l.cost, RETAIL_CURRENCY);
        const packs = unitsOn ? activePacks(l.product) : [];
        const pack = unitsOn ? chosenPack(l.product, l.unitId) : undefined;
        // The product's cost is per base unit: a pack cost is divided by the factor, rounded half up, as the server does.
        const newCost = enteredCost !== null && pack ? baseCostMinor(enteredCost, pack.factor) : enteredCost;
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
            <div className="rt-row">
              <div>
                <label htmlFor={`cost-${i}`}>Cost each{pack ? ` (per ${pack.unit})` : ''}</label>
                <input id={`cost-${i}`} inputMode="numeric" value={l.cost} onChange={(e) => patch(i, { cost: e.target.value })} />
              </div>
              <div>
                <label htmlFor={`sell-${i}`}>Sell price each{pack ? ` (per ${l.product.unit})` : ''}</label>
                <input id={`sell-${i}`} inputMode="numeric" value={l.sell} disabled={!canPrice} onChange={(e) => patch(i, { sell: e.target.value })} />
                {!canPrice && <p className="hint">Only the owner changes selling prices.</p>}
              </div>
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
              {canProfit && newCost !== null && l.product.cost_minor !== undefined && newCost !== l.product.cost_minor ? ` Cost will change from ${money(l.product.cost_minor)} to ${money(newCost)}.` : ''}
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
      <button type="submit" className="rt-primary" data-tour="restock-save" disabled={blocked || save.isPending}>{save.isPending ? 'Saving' : 'Save restock'}</button>
      {lines.length > 0 && !save.isPending && (
        <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this restock</button>
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
      out.push(`${l.description}: cost changed from ${money(was.cost)} to ${money(l.cost_minor)}.`);
    }
    return out;
  });
}

export function RestockSaved({ purchase, context, onNew }: { purchase: Purchase; context: RestockContext; onNew?: () => void }) {
  const changes = priceChanges(purchase, context.before, useProfitAccess());
  const count = (purchase.lines ?? []).length;
  return (
    <section aria-label="Restock saved">
      <h2>Restock saved{purchase.purchase_no ? ` (${purchase.purchase_no})` : ''}</h2>
      <p>
        From {context.supplierName || 'no supplier'}, bought on {purchase.purchased_on}: {count} {count === 1 ? 'item' : 'items'}, total cost{' '}
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
      {onNew && <button type="button" className="rt-primary" onClick={onNew}>New restock</button>}
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
        You are entering restock {loaded.data.purchase.purchase_no} again{loaded.data.purchase.status === 'voided' ? ', which you voided' : ''}. Check each quantity, unit and price, then save.
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
    <Gate screen="restock" title="Restock">
      <p data-tour="restock-past"><Link to="/staff/retail/restocks">Past restocks, to correct a mistake</Link></p>
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
