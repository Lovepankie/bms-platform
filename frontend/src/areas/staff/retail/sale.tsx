import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Product, type Sale, type SalePayment } from '../../../api/retail';
import { useIdempotencyKey } from './idempotency';
import { showQty } from './maths';
import { buildSaleRequest, draftProblem, draftTotal, lineFigures, lineHint, newLine, type Draft } from './sale-state';
import { BranchRequired, Gate, Note, Problem, money, useProfitAccess, useSingleBranch } from './ui';

// Record a sale (FR-RET-04, FR-RET-05). One idempotency key per form open: a double tap or a retry
// after a lost answer posts once. After saving, the receipt summary replaces the form.

const METHODS: { value: SalePayment; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
  { value: 'credit', label: 'Credit (pay later)' },
];

export function Receipt({ sale, onNew }: { sale: Sale; onNew?: () => void }) {
  const canProfit = useProfitAccess();
  return (
    <section aria-label="Sale receipt">
      <h2>Sale saved</h2>
      <p>
        Paid by {METHODS.find((m) => m.value === sale.payment_method)?.label ?? sale.payment_method} on {sale.sale_date}
        {sale.buyer_name ? `, buyer ${sale.buyer_name}` : ''}
      </p>
      <table>
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
              <td>{l.description}</td>
              <td className="num">{showQty(l.qty ?? '0')}</td>
              <td className="num">{money(l.unit_price_minor ?? 0)}</td>
              <td className="num">{money(l.line_total_minor ?? 0)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="rt-total">Total {money(sale.total_minor ?? 0)}</p>
      {(sale.balance_minor ?? 0) > 0 && (
        <p>
          Still to pay: <strong>{money(sale.balance_minor ?? 0)}</strong>
          {sale.due_date ? ` by ${sale.due_date}` : ''}
        </p>
      )}
      {canProfit && sale.profit_minor !== undefined && <p>Profit on this sale: {money(sale.profit_minor)}</p>}
      {onNew && (
        <button type="button" className="rt-primary" onClick={onNew}>
          New sale
        </button>
      )}
    </section>
  );
}

export function SaleForm({ branchId, onSaved }: { branchId: string; onSaved?: (sale: Sale) => void }) {
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const [search, setSearch] = useState('');
  const [draft, setDraft] = useState<Draft>({
    branchId, method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [],
  });
  const [addingBuyer, setAddingBuyer] = useState(false);

  const products = useQuery({
    queryKey: ['retail', 'products', branchId, search],
    queryFn: () => retail.listProducts({ query: search, branchId }),
  });
  const customers = useQuery({ queryKey: ['retail', 'customers'], queryFn: () => retail.listCustomers(), enabled: draft.method === 'credit' });

  const save = useMutation({
    mutationFn: () => retail.createSale(buildSaleRequest(draft), key),
    onSuccess: (sale) => {
      void queryClient.invalidateQueries({ queryKey: ['retail', 'products'] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'stock'] });
      onSaved?.(sale);
    },
  });

  const update = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));
  const setLine = (i: number, patch: { qty?: string; price?: string }) =>
    setDraft((d) => ({ ...d, lines: d.lines.map((l, n) => (n === i ? { ...l, ...patch } : l)) }));
  const add = (p: Product) =>
    setDraft((d) => (d.lines.some((l) => l.product.id === p.id) ? d : { ...d, lines: [...d.lines, newLine(p)] }));

  const problem = draftProblem(draft);
  const total = draftTotal(draft.lines);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!problem && !save.isPending) save.mutate();
      }}
    >
      <label htmlFor="sale-search">Find an item by name or code</label>
      <input id="sale-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} autoComplete="off" />
      {products.isError && <Problem error={products.error} />}
      <ul style={{ listStyle: 'none', padding: 0, maxHeight: 220, overflowY: 'auto' }}>
        {(products.data ?? []).slice(0, 20).map((p) => (
          <li key={p.id} className="rt-card">
            <div className="rt-row">
              <span>
                <strong>{p.description}</strong> ({p.code})
                <br />
                {money(p.sell_minor ?? 0)} each, in stock here:{' '}
                {p.qty !== undefined && p.qty.startsWith('-') ? <span className="rt-flag">{showQty(p.qty)} (negative)</span> : showQty(p.qty ?? '0')}{' '}
                {p.unit}
              </span>
              <button type="button" onClick={() => add(p)} aria-label={`Add ${p.description}`}>
                Add
              </button>
            </div>
          </li>
        ))}
      </ul>

      <h2>Items</h2>
      {draft.lines.length === 0 && <p>No items yet. Search above and tap Add.</p>}
      {draft.lines.map((l, i) => {
        const f = lineFigures(l);
        const hint = lineHint(l);
        return (
          <div key={l.product.id} className="rt-card">
            <strong>{l.product.description}</strong>
            <div className="rt-row">
              <div>
                <label htmlFor={`qty-${i}`}>Quantity ({l.product.unit})</label>
                <input id={`qty-${i}`} inputMode="decimal" value={l.qty} onChange={(e) => setLine(i, { qty: e.target.value })} aria-invalid={f === null || hint !== null} />
              </div>
              <div>
                <label htmlFor={`price-${i}`}>Unit price</label>
                <input id={`price-${i}`} inputMode="numeric" value={l.price} onChange={(e) => setLine(i, { price: e.target.value })} aria-invalid={f === null} />
              </div>
            </div>
            {hint && <p role="alert" className="rt-flag">{hint}</p>}
            <p>
              {f ? `Line total ${money(f.totalMinor)}` : <span className="rt-flag">Check quantity and price</span>}{' '}
              <button type="button" onClick={() => setDraft((d) => ({ ...d, lines: d.lines.filter((_, n) => n !== i) }))} aria-label={`Remove ${l.product.description}`}>
                Remove
              </button>
            </p>
          </div>
        );
      })}

      <fieldset>
        <legend>How is it paid?</legend>
        {METHODS.map((m) => (
          <label key={m.value} style={{ fontWeight: 400 }}>
            <input type="radio" name="method" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={draft.method === m.value} onChange={() => update({ method: m.value })} />
            {m.label}
          </label>
        ))}
      </fieldset>

      {draft.method === 'credit' && (
        <fieldset>
          <legend>Credit buyer</legend>
          {!addingBuyer && (
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
              <button type="button" onClick={() => { setAddingBuyer(false); update({ newBuyerName: '', newBuyerContact: '' }); }}>
                Pick an existing buyer
              </button>
            </>
          )}
          <label htmlFor="due">Buyer will pay by</label>
          <input id="due" type="date" value={draft.dueDate} onChange={(e) => update({ dueDate: e.target.value })} />
        </fieldset>
      )}

      <p className="rt-total" aria-live="polite">
        Total {money(total)}
      </p>
      {problem && draft.lines.length > 0 && <Note>{problem}</Note>}
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={problem !== null || save.isPending}>
        {save.isPending ? 'Saving' : 'Save sale'}
      </button>
    </form>
  );
}

function RecordSale() {
  const { branchId, branchName } = useSingleBranch();
  const [sale, setSale] = useState<Sale | null>(null);
  const [round, setRound] = useState(0);
  return (
    <Gate screen="sale" title="Record a sale">
      {branchId === null ? (
        <BranchRequired />
      ) : sale ? (
        <Receipt sale={sale} onNew={() => { setSale(null); setRound((n) => n + 1); }} />
      ) : (
        <>
          <p>Branch: {branchName}</p>
          <SaleForm key={`${branchId}-${round}`} branchId={branchId} onSaved={setSale} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/sale')({ component: RecordSale });
