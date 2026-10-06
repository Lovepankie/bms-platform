import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { RETAIL_CURRENCY, businessToday, retail, type Product, type Purchase, type PurchasePayment, type PurchaseRequest } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { parseMinor } from '../../../components/money';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { lineTotalMinor, parseQty, qtyString } from './maths';
import { Gate, Note, Problem, money, useProfitAccess } from './ui';

// Restock (FR-RET-06): supplier, lines with cost, sell price and a quantity per branch. Saving sets
// the product's cost and sell price in the same transaction and leaves a price history row.

export interface RestockLine {
  product: Product;
  cost: string;
  sell: string;
  qtyByBranch: Record<string, string>;
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
      cost_minor: parseMinor(l.cost, RETAIL_CURRENCY) ?? 0,
      sell_minor: parseMinor(l.sell, RETAIL_CURRENCY) ?? l.product.sell_minor ?? 0,
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
interface RestockDraft {
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

export function RestockForm({ onSaved }: { onSaved?: (p: Purchase, context: RestockContext) => void }) {
  const { me, branch } = useStaff();
  const canProfit = useProfitAccess();
  const queryClient = useQueryClient();
  const branches = (me.branches ?? []).filter((b) => b.id);
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<RestockDraft>(`restock:${me.user_id ?? ''}`, {
    supplierId: '', newSupplier: '', purchasedOn: businessToday(), method: 'cash', lines: [],
  });
  const { supplierId, newSupplier, purchasedOn, method, lines } = draft;
  const field = <K extends keyof RestockDraft>(k: K) => (v: RestockDraft[K]) => setDraft((d) => ({ ...d, [k]: v }));
  const setSupplierId = field('supplierId');
  const setNewSupplier = field('newSupplier');
  const setPurchasedOn = field('purchasedOn');
  const setMethod = field('method');
  const setLines = (f: (ls: RestockLine[]) => RestockLine[]) => setDraft((d) => ({ ...d, lines: f(d.lines) }));
  const [search, setSearch] = useState('');

  const products = useQuery({ queryKey: ['retail', 'products', 'all', search], queryFn: () => retail.listProducts({ query: search }) });
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

  const add = (p: Product) =>
    setLines((ls) =>
      ls.some((l) => l.product.id === p.id)
        ? ls
        : [...ls, { product: p, cost: p.cost_minor !== undefined ? String(p.cost_minor) : '', sell: String(p.sell_minor ?? 0), qtyByBranch: { ...(branch && branches.some((b) => b.id === branch) ? { [branch]: '' } : {}) } }],
    );
  const patch = (i: number, change: Partial<RestockLine>) => setLines((ls) => ls.map((l, n) => (n === i ? { ...l, ...change } : l)));

  const problems = lines.map(restockLineProblem);
  const blocked = lines.length === 0 || problems.some((p) => p !== null);

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked && !save.isPending) save.mutate(); }}>
      <Note>Saving a restock changes the cost and sell price of every item below to the values you enter. The change is recorded in the price history.</Note>

      <label htmlFor="supplier">Supplier</label>
      <select id="supplier" value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
        <option value="">No supplier</option>
        {(suppliers.data ?? []).map((s) => (
          <option key={s.id} value={s.id}>{s.name}</option>
        ))}
      </select>
      {supplierId === '' && (
        <>
          <label htmlFor="new-supplier">Or add a new supplier</label>
          <input id="new-supplier" value={newSupplier} onChange={(e) => setNewSupplier(e.target.value)} />
        </>
      )}
      <label htmlFor="bought-on">Bought on</label>
      <input id="bought-on" type="date" value={purchasedOn} onChange={(e) => setPurchasedOn(e.target.value)} />

      <label htmlFor="restock-search">Find an item by name or code</label>
      <input id="restock-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} autoComplete="off" />
      <ul style={{ listStyle: 'none', padding: 0, maxHeight: 180, overflowY: 'auto' }}>
        {(products.data ?? []).slice(0, 20).map((p) => (
          <li key={p.id} className="rt-card rt-row">
            <span><strong>{p.description}</strong> ({p.code})</span>
            <button type="button" onClick={() => add(p)} aria-label={`Add ${p.description}`}>Add</button>
          </li>
        ))}
      </ul>

      <h2>Items</h2>
      {lines.length === 0 && <p className="empty-state">No items yet.</p>}
      {lines.map((l, i) => {
        const newSell = parseMinor(l.sell, RETAIL_CURRENCY);
        const newCost = parseMinor(l.cost, RETAIL_CURRENCY);
        return (
          <div key={l.product.id} className="rt-card">
            <strong>{l.product.description}</strong>
            <div className="rt-row">
              <div>
                <label htmlFor={`cost-${i}`}>Cost each</label>
                <input id={`cost-${i}`} inputMode="numeric" value={l.cost} onChange={(e) => patch(i, { cost: e.target.value })} />
              </div>
              <div>
                <label htmlFor={`sell-${i}`}>Sell price each</label>
                <input id={`sell-${i}`} inputMode="numeric" value={l.sell} onChange={(e) => patch(i, { sell: e.target.value })} />
              </div>
            </div>
            <fieldset>
              <legend>Quantity bought per branch ({l.product.unit})</legend>
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

      <fieldset>
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
      <button type="submit" className="rt-primary" disabled={blocked || save.isPending}>{save.isPending ? 'Saving' : 'Save restock'}</button>
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
      {changes.length > 0 ? (
        <ul>{changes.map((c) => <li key={c}>{c}</li>)}</ul>
      ) : (
        <p>No price changed.</p>
      )}
      {onNew && <button type="button" className="rt-primary" onClick={onNew}>New restock</button>}
    </section>
  );
}

function Restock() {
  const [done, setDone] = useState<{ purchase: Purchase; context: RestockContext } | null>(null);
  const [round, setRound] = useState(0);
  return (
    <Gate screen="restock" title="Restock">
      {done ? (
        <RestockSaved purchase={done.purchase} context={done.context} onNew={() => { setDone(null); setRound((n) => n + 1); }} />
      ) : (
        <RestockForm key={round} onSaved={(purchase, context) => setDone({ purchase, context })} />
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/restock')({ component: Restock });
