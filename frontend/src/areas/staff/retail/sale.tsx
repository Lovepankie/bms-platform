import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { SEARCH_DELAY_MS, createDebouncer } from '../../../app/ui/useLiveSearch';
import { RetailError, retail, type Product, type ResolvedLine, type Sale, type SalePayment } from '../../../api/retail';
import { offlineProblem, offlineSale } from '../../../offline/catalogue';
import { useOffline, useOnline } from '../../../offline/offline-context';
import { OfflineNote, OfflineProductPicker, ProvisionalReceipt } from '../../../offline/offline-sale';
import type { OutboxItem } from '../../../offline/outbox';
import { newIdempotencyKey } from './idempotency';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { showQty } from './maths';
import { buildSaleRequest, draftProblem, draftTotal, lineFigures, lineHint, newLine, resolveRequestOf, withUnit, type Accounts, type Draft, type DraftLine, type Pricing } from './sale-state';
import { MIN_REASON, hasNoPrice, isOverride, pricedLines, receiptNote, reasonOk, sourceNote } from './price-state';
import { activePacks, chosenPack, enteredPriceMinor, enteredQty } from './units-state';
import { EmptyState } from '../../../components/states';
import { BranchRequired, Gate, NoStockHere, Note, ProductPicker, Problem, money, useBranchRoles, useProfitAccess, useStockingBranch } from './ui';
import { branchLabel } from '../../../auth/branch';
import type { BranchRole } from '../../../api/retail';
import { BRANCH_ROLES, CREDIT_OVERRIDE, CUSTOMER_ACCOUNTS, PRICELIST_MANAGE, PRICELIST_USE, PRICE_LISTS, PRICE_OVERRIDE, UNITS, branchesWhere, capabilityOn, holds, holdsAt, needsOf, sellingBranches } from './permissions';
import { exceededBy, isLimitRefusal, limitWords, termsWords } from './account-state';
import { SaleDocuments } from './documents-ui';
import { ShareReceipt } from './receipt-share';
import { ChangePanel, ReceiptChange } from './sale-change';
import { receivedFor, receivedProblem } from './sale-change-state';

// Record a sale (FR-RET-04, FR-RET-05). One idempotency key per draft, kept with the draft in this
// tab until the sale is saved or the draft is cleared: a double tap, a retry after a lost answer or a
// reload posts once (#77). After saving, the receipt summary replaces the form.

const METHODS: { value: SalePayment; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
  { value: 'credit', label: 'Credit (pay later)' },
];

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

export function Receipt({ sale, onNew, received }: { sale: Sale; onNew?: () => void; /** Cash the customer gave, for the change line; display only. */ received?: number }) {
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

/**
 * One line of the sale being made. The unit choice is drawn only while the tenant has the units capability
 * (`unitsOn`) and the item has active pack sizes; otherwise the line is exactly what it was before packs.
 */
export function SaleLineCard({ line, index, unitsOn, price, onQty, onPrice, onUnit, onReason, onReset, onRemove }: {
  line: DraftLine;
  index: number;
  unitsOn: boolean;
  /** Present while price lists are on: how the line was priced, and what the seller may do about it. */
  price?: { resolved?: ResolvedLine; listName?: string; canOverride: boolean };
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
  const packs = unitsOn ? activePacks(line.product) : [];
  const pack = unitsOn ? chosenPack(line.product, line.unitId) : undefined;
  const override = price ? isOverride(line, price.resolved) : false;
  const noPrice = price ? hasNoPrice(price.resolved) : false;
  return (
    <div className="rt-card">
      <strong>{line.product.description}</strong>
      {packs.length > 0 && (
        <div>
          <label htmlFor={`unit-${i}`}>Sell by</label>
          <select id={`unit-${i}`} data-tour="sale-unit" value={pack?.unit_id ?? ''} onChange={(e) => onUnit(e.target.value)}>
            <option value="">{line.product.unit}</option>
            {packs.map((k) => <option key={k.unit_id} value={k.unit_id}>{k.unit} ({showQty(k.factor ?? '')} {line.product.unit})</option>)}
          </select>
        </div>
      )}
      <div className="rt-row">
        <div>
          <label htmlFor={`qty-${i}`}>Quantity ({pack?.unit ?? line.product.unit})</label>
          <input id={`qty-${i}`} inputMode="decimal" value={line.qty} onChange={(e) => onQty(e.target.value)} aria-invalid={f === null || hint !== null} />
        </div>
        <div>
          <label htmlFor={`price-${i}`}>{pack ? `Price per ${pack.unit}` : 'Unit price'}</label>
          <input id={`price-${i}`} inputMode="numeric" value={line.price} onChange={(e) => onPrice(e.target.value)} aria-invalid={f === null} readOnly={price ? !price.canOverride : false} />
        </div>
      </div>
      {price && price.resolved && (
        <p className="hint" data-tour="sale-price-source" data-testid={`price-source-${i}`}>
          {sourceNote(price.resolved, price.listName)}
          {override && ` Changed from ${money(price.resolved.unit_price_minor ?? 0)}.`}
        </p>
      )}
      {noPrice && <p role="alert" className="rt-flag">This item has no price in the list.</p>}
      {price && !price.canOverride && <p className="hint">The price comes from the price list. You do not have permission to change it.</p>}
      {override && onReason && (
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

export function SaleForm({ branchId, branchName = '', onSaved, onQueued }: {
  branchId: string;
  branchName?: string;
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
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<Draft>(`sale:${me.user_id ?? ''}:${branchId}`, {
    branchId, method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [],
  });
  const [addingBuyer, setAddingBuyer] = useState(draft.newBuyerName !== '');
  // What the customer handed over (cash only): kept in this form's state, never in the draft, so it goes with the form (#346).
  const [received, setReceived] = useState('');

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
  const save = useMutation({
    mutationFn: () => retail.createSale({ ...buildSaleRequest(shown, pricing, accounts), ...(offline ? { client_sale_id: key } : {}) }, key),
    onSuccess: (sale) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail', 'products'] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'stock'] });
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
  const add = (p: Product) =>
    setDraft((d) => (d.lines.some((l) => l.product.id === p.id) && !(unitsOn && activePacks(p).length > 0) ? d : { ...d, lines: [...d.lines, newLine(p)] }));

  const total = draftTotal(shown.lines);
  const problem = (offlineMode ? offlineProblem(draft) : draftProblem(shown, pricing, accounts)) ?? receivedProblem(draft.method, total, received);
  // A sale whose request got no answer may or may not be recorded: kept to send later, it is recorded once either way.
  const lost = offline !== null && save.error !== null && !(save.error instanceof RetailError);
  const keep = useMutation({
    mutationFn: async () => {
      if (!offline) throw new Error('Offline sales are not available');
      const item = offlineSale({
        draft: shown,
        clientSaleId: key,
        // A new key: the first one may already be stored with the online request, which this one is not.
        key: lost ? newIdempotencyKey() : key,
        scope: offline.scope,
        branchName,
        pricesAsOf: offline.catalogue?.pricesAsOf,
        now: new Date(),
      });
      await offline.engine.queue(item);
      return item;
    },
    onSuccess: (item) => {
      finish();
      void offline?.engine.sync();
      onQueued?.(item);
    },
  });
  // A credit sale refused for the buyer's limit: say by how much, and offer the override to whoever holds it.
  const overBy = accountsOn && draft.method === 'credit' ? exceededBy(save.error) : null;
  const canOverride = holdsAt(me, CREDIT_OVERRIDE, branchId);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (problem || save.isPending || keep.isPending) return;
        if (offlineMode) keep.mutate();
        else save.mutate();
      }}
    >
      {offlineMode && <OfflineNote />}
      <div data-tour="sale-search">
        {offlineMode ? <OfflineProductPicker branchId={branchId} onAdd={add} /> : <ProductPicker id="sale-search" branchId={branchId} showPrice onAdd={add} />}
      </div>

      <h2>Items</h2>
      {draft.lines.length === 0 && <EmptyState art="noSales" title="No items yet.">Search above and tap Add.</EmptyState>}
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
          price={pricesOn ? { resolved: resolved?.[i], listName: resolution.data?.price_list, canOverride: holdsAt(me, PRICE_OVERRIDE, branchId) } : undefined}
          onQty={(v) => setLine(i, { qty: v })}
          onPrice={(v) => setLine(i, { price: v, typed: true })}
          onReason={(v) => setLine(i, { reason: v })}
          onReset={() => setLine(i, { typed: false, reason: '' })}
          onUnit={(unitId) => setUnit(i, unitId)}
          onRemove={() => setDraft((d) => ({ ...d, lines: d.lines.filter((_, n) => n !== i) }))}
        />
      ))}

      {pricesOn && draft.method !== 'credit' && (
        <fieldset data-tour="sale-customer">
          <legend>Customer</legend>
          <label htmlFor="price-customer">Customer (optional): sets the price list</label>
          <select id="price-customer" value={draft.customerId} onChange={(e) => update({ customerId: e.target.value })}>
            <option value="">No customer: retail prices</option>
            {(customers.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </fieldset>
      )}
      {canChooseList && (
        <div>
          <label htmlFor="price-list">Price list for this sale</label>
          <select id="price-list" value={draft.priceListId ?? ''} onChange={(e) => update({ priceListId: e.target.value || undefined })}>
            <option value="">The customer's own list</option>
            {(lists.data ?? []).filter((l) => l.active).map((l) => (
              <option key={l.id} value={l.id}>{l.name}</option>
            ))}
          </select>
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
                  <button type="button" className="rt-primary" disabled={draft.newBuyerName.trim() === '' || addBuyer.isPending} onClick={() => addBuyer.mutate()}>
                    {addBuyer.isPending ? 'Adding' : 'Add this buyer'}
                  </button>
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

      <p className="rt-total" aria-live="polite">
        Total {money(total)}
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
              <p className="hint">Save the sale again to sell above the limit. The reason is kept with the sale.</p>
            </>
          ) : (
            <p>Ask someone who may allow it, take a payment first, or sell for less.</p>
          )}
        </div>
      )}
      {overBy === null && <Problem error={save.error} />}
      {lost && (
        <div role="alert" className="alert alert-warning" data-testid="lost-answer">
          <p>The server did not answer. The sale may or may not be saved. Keep it on this phone and it is sent once.</p>
          <button type="button" disabled={keep.isPending} onClick={() => keep.mutate()}>
            Keep it on this phone
          </button>
        </div>
      )}
      {keep.error && <p role="alert" className="alert alert-danger">This phone could not keep the sale: {keep.error.message}</p>}
      <button type="submit" className="rt-primary" disabled={problem !== null || save.isPending || keep.isPending} data-tour="sale-save">
        {save.isPending || keep.isPending ? 'Saving' : offlineMode ? 'Save on this phone' : 'Save sale'}
      </button>
      {draft.lines.length > 0 && !save.isPending && (
        <button type="button" onClick={() => { discard(); setAddingBuyer(false); setReceived(''); save.reset(); }}>
          Clear this sale
        </button>
      )}
    </form>
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

export function RecordSale() {
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
          <Receipt sale={sale} received={received} onNew={() => { setSale(null); setReceived(undefined); setRound((n) => n + 1); }} />
          <ShareReceipt sale={sale} madeHere />
          {sale.id && <SaleDocuments saleId={sale.id} status={sale.status} branchId={sale.branch_id} />}
        </>
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <NoStockHere branchId={branchId} />
          <SaleForm key={`${branchId}-${round}`} branchId={branchId} branchName={branchName} onSaved={(s, r) => { setSale(s); setReceived(r); }} onQueued={setQueued} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/sale')({ component: RecordSale });
