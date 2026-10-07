import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Product, type Sale, type SalePayment } from '../../../api/retail';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { showQty } from './maths';
import { buildSaleRequest, draftProblem, draftTotal, lineFigures, lineHint, newLine, type Draft } from './sale-state';
import { EmptyState } from '../../../components/states';
import { BranchRequired, Gate, NoStockHere, Note, ProductPicker, Problem, money, useProfitAccess, useSingleBranch } from './ui';
import { needsOf } from './permissions';

// Record a sale (FR-RET-04, FR-RET-05). One idempotency key per draft, kept with the draft in this
// tab until the sale is saved or the draft is cleared: a double tap, a retry after a lost answer or a
// reload posts once (#77). After saving, the receipt summary replaces the form.

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
              <td>{l.description}</td>
              <td className="num">{showQty(l.qty ?? '0')}</td>
              <td className="num">{money(l.unit_price_minor ?? 0)}</td>
              <td className="num">{money(l.line_total_minor ?? 0)}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
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
  const { me } = useStaff();
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<Draft>(`sale:${me.user_id ?? ''}:${branchId}`, {
    branchId, method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [],
  });
  const [addingBuyer, setAddingBuyer] = useState(draft.newBuyerName !== '');

  const customers = useQuery({ queryKey: ['retail', 'customers'], queryFn: () => retail.listCustomers(), enabled: draft.method === 'credit' });

  const save = useMutation({
    mutationFn: () => retail.createSale(buildSaleRequest(draft), key),
    onSuccess: (sale) => {
      finish();
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
      <div data-tour="sale-search">
        <ProductPicker id="sale-search" branchId={branchId} showPrice onAdd={add} />
      </div>

      <h2>Items</h2>
      {draft.lines.length === 0 && <EmptyState art="noSales" title="No items yet.">Search above and tap Add.</EmptyState>}
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
            <p className="line-foot">
              {f ? `Line total ${money(f.totalMinor)}` : <span className="rt-flag">Check quantity and price</span>}{' '}
              <button type="button" className="btn-ghost" onClick={() => setDraft((d) => ({ ...d, lines: d.lines.filter((_, n) => n !== i) }))} aria-label={`Remove ${l.product.description}`}>
                Remove
              </button>
            </p>
          </div>
        );
      })}

      <fieldset data-tour="sale-payment">
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
      <button type="submit" className="rt-primary" disabled={problem !== null || save.isPending} data-tour="sale-save">
        {save.isPending ? 'Saving' : 'Save sale'}
      </button>
      {draft.lines.length > 0 && !save.isPending && (
        <button type="button" onClick={() => { discard(); setAddingBuyer(false); save.reset(); }}>
          Clear this sale
        </button>
      )}
    </form>
  );
}

export function RecordSale() {
  const { branchId, branchName } = useSingleBranch();
  const [sale, setSale] = useState<Sale | null>(null);
  const [round, setRound] = useState(0);
  return (
    <Gate screen="sale" title="Record a sale">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('sale')} />
      ) : sale ? (
        <Receipt sale={sale} onNew={() => { setSale(null); setRound((n) => n + 1); }} />
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <NoStockHere branchId={branchId} />
          <SaleForm key={`${branchId}-${round}`} branchId={branchId} onSaved={setSale} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/sale')({ component: RecordSale });
