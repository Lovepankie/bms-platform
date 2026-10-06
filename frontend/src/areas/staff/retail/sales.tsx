import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, daysBefore, retail, type Sale, type SalePayment, type SalesQuery } from '../../../api/retail';
import { showQty } from './maths';
import { showDate } from './transfers';
import { Gate, Problem, money, useBranchName, useBranchView, useIsPhone, useProfitAccess } from './ui';

// Credit sales and All sales (#145): the lists the pilot's app has, 'Credit sales' and the sales
// history, read from the sales list API in the branch chosen at the top of the page (every branch the
// session may read with "All branches"), newest first. Tapping a sale opens it with its lines. Cost and
// profit show only when the server sent them (retail.profit.read).

const METHODS: { value: SalePayment; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
  { value: 'credit', label: 'Credit' },
];
const methodLabel = (m: string | undefined) => METHODS.find((x) => x.value === m)?.label ?? m ?? '';

export type SaleState = { label: 'Paid' | 'Part paid' | 'Unpaid' | 'Overdue' | 'Voided'; tone: 'success' | 'warning' | 'danger' | 'info' };

/** What a sale is, in words: voided, paid, or (credit) unpaid, part paid or overdue on the business date `today`. */
export function saleState(sale: Sale, today: string): SaleState {
  if (sale.status === 'voided') return { label: 'Voided', tone: 'warning' };
  const owed = sale.balance_minor ?? 0;
  if (sale.payment_method !== 'credit' || owed <= 0) return { label: 'Paid', tone: 'success' };
  if (sale.due_date && sale.due_date < today) return { label: 'Overdue', tone: 'danger' };
  return (sale.paid_minor ?? 0) > 0 ? { label: 'Part paid', tone: 'warning' } : { label: 'Unpaid', tone: 'info' };
}

/** One sale as a card: buyer, date, amount, and for credit the due date and what is still owed, with an Open button. */
export function SaleList({ items, today, onOpen, credit, branchOf }: {
  items: Sale[];
  today: string;
  onOpen: (id: string) => void;
  credit?: boolean;
  branchOf?: (id: string | undefined) => string;
}) {
  if (items.length === 0) return <p className="empty-state">{credit ? 'No credit sales found.' : 'No sales found.'}</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {items.map((s) => {
        const state = saleState(s, today);
        const isCredit = s.payment_method === 'credit';
        return (
          <li key={s.id} className="rt-card">
            <p>
              <strong>{s.buyer_name || (isCredit ? 'No buyer name' : methodLabel(s.payment_method))}</strong>{' '}
              <span className={`badge badge-${state.tone}`}>{state.label}</span>
            </p>
            <p>{showDate(s.sale_date)}, <strong>{money(s.total_minor ?? 0)}</strong></p>
            {isCredit && (
              <p className="hint">
                {s.due_date ? `Due ${showDate(s.due_date)}` : 'No due date'}
                {(s.balance_minor ?? 0) > 0 ? `, still owes ${money(s.balance_minor ?? 0)}` : ''}
              </p>
            )}
            {!credit && !isCredit && <p className="hint">{methodLabel(s.payment_method)}</p>}
            {branchOf && <p className="hint">{branchOf(s.branch_id)}</p>}
            <button type="button" className="btn-sm" aria-label={`Open the sale of ${showDate(s.sale_date)}${s.buyer_name ? ` to ${s.buyer_name}` : ''}`} onClick={() => onOpen(s.id ?? '')}>
              Open
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/** A sale with its lines, what was paid and what is owed; profit only when the server sent it. */
export function SaleDetail({ sale, today, branchName }: { sale: Sale; today: string; branchName?: string }) {
  const state = saleState(sale, today);
  const canProfit = useProfitAccess();
  const phone = useIsPhone();
  const facts: [string, string][] = [
    ['Date', showDate(sale.sale_date)],
    ...(branchName ? [['Branch', branchName] as [string, string]] : []),
    ['Paid by', methodLabel(sale.payment_method)],
    ...(sale.buyer_name ? [['Buyer', sale.buyer_name] as [string, string]] : []),
    ...(sale.buyer_contact ? [['Contact', sale.buyer_contact] as [string, string]] : []),
    ...(sale.due_date ? [['Due', showDate(sale.due_date)] as [string, string]] : []),
    ...(sale.status === 'voided' && sale.void_reason ? [['Voided because', sale.void_reason] as [string, string]] : []),
  ];
  return (
    <section aria-label="Sale">
      <h2>
        Sale {sale.sale_no} <span className={`badge badge-${state.tone}`}>{state.label}</span>
      </h2>
      <div className="table-wrap"><table>
        <tbody>
          {facts.map(([k, v]) => <tr key={k}><td>{k}</td><td className="num">{v}</td></tr>)}
        </tbody>
      </table></div>
      <div className="table-wrap" tabIndex={0}><table>
        <thead>
          <tr>
            <th>Item</th>
            {!phone && <th className="num">Qty</th>}
            {!phone && <th className="num">Price</th>}
            <th className="num">Total</th>
          </tr>
        </thead>
        <tbody>
          {(sale.lines ?? []).map((l) => (
            <tr key={l.id ?? l.product_id}>
              <td>{l.description}{phone && <><br /><span className="hint">{showQty(l.qty ?? '0')} at {money(l.unit_price_minor ?? 0)}</span></>}</td>
              {!phone && <td className="num">{showQty(l.qty ?? '0')}</td>}
              {!phone && <td className="num">{money(l.unit_price_minor ?? 0)}</td>}
              <td className="num">{money(l.line_total_minor ?? 0)}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
      <div className="table-wrap"><table>
        <tbody>
          <tr><td>Total</td><td className="num">{money(sale.total_minor ?? 0)}</td></tr>
          {sale.payment_method === 'credit' && <tr><td>Paid</td><td className="num">{money(sale.paid_minor ?? 0)}</td></tr>}
          {sale.payment_method === 'credit' && <tr><td>Still owes</td><td className="num">{money(sale.balance_minor ?? 0)}</td></tr>}
          {canProfit && sale.profit_minor !== undefined && <tr><td>Profit on this sale</td><td className="num">{money(sale.profit_minor)}</td></tr>}
        </tbody>
      </table></div>
    </section>
  );
}

function OpenSale({ id, onClose }: { id: string; onClose: () => void }) {
  const nameOf = useBranchName();
  const sale = useQuery({ queryKey: ['retail', 'sale', id], queryFn: () => retail.getSale(id) });
  return (
    <>
      <button type="button" onClick={onClose}>Back to the list</button>
      {sale.isPending && <p className="loading">Loading</p>}
      <Problem error={sale.error} />
      {sale.data && <SaleDetail sale={sale.data} today={businessToday()} branchName={nameOf(sale.data.branch_id)} />}
    </>
  );
}

/** Pages of sales for a filter, newest first, with a Show more button. */
function useSales(filter: Omit<SalesQuery, 'cursor'>, enabled: boolean) {
  const query = useInfiniteQuery({
    queryKey: ['retail', 'sales', filter],
    queryFn: ({ pageParam }) => retail.listSales({ ...filter, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor,
    enabled,
  });
  return { query, items: (query.data?.pages ?? []).flatMap((p) => p.items ?? []) };
}

function More({ query }: { query: ReturnType<typeof useSales>['query'] }) {
  if (!query.hasNextPage) return null;
  return (
    <button type="button" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
      {query.isFetchingNextPage ? 'Loading' : 'Show more'}
    </button>
  );
}

type Owing = 'all' | 'owing' | 'overdue' | 'paid';
const OWING: { value: Owing; label: string }[] = [
  { value: 'all', label: 'All credit sales' },
  { value: 'owing', label: 'Still owing' },
  { value: 'overdue', label: 'Overdue' },
  { value: 'paid', label: 'Paid' },
];

/** The credit sales a state filter keeps. */
export function keepCredit(items: Sale[], owing: Owing, today: string): Sale[] {
  return items.filter((s) => {
    const label = saleState(s, today).label;
    return owing === 'all' || (owing === 'paid' ? label === 'Paid' : owing === 'overdue' ? label === 'Overdue' : label !== 'Paid');
  });
}

function CreditSales() {
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  const [buyer, setBuyer] = useState('');
  const [owing, setOwing] = useState<Owing>('all');
  const [open, setOpen] = useState<string | null>(null);
  const today = businessToday();
  const { query, items } = useSales({ branchId: all ? undefined : (branchId ?? undefined), buyer, paymentMethod: 'credit', status: 'completed' }, all || branchId !== null);
  return (
    <Gate screen="creditSales" title="Credit sales">
      {open ? (
        <OpenSale id={open} onClose={() => setOpen(null)} />
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
          <label htmlFor="credit-buyer">Buyer</label>
          <input id="credit-buyer" type="search" value={buyer} onChange={(e) => setBuyer(e.target.value)} autoComplete="off" />
          <label htmlFor="credit-owing">Show</label>
          <select id="credit-owing" value={owing} onChange={(e) => setOwing(e.target.value as Owing)}>
            {OWING.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          {query.isPending && query.fetchStatus !== 'idle' && <p className="loading">Loading</p>}
          <Problem error={query.error} />
          {query.data && <SaleList items={keepCredit(items, owing, today)} today={today} credit onOpen={setOpen} branchOf={all ? nameOf : undefined} />}
          <More query={query} />
        </>
      )}
    </Gate>
  );
}

function AllSales() {
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  const today = businessToday();
  const [from, setFrom] = useState(() => daysBefore(today, 29));
  const [to, setTo] = useState(today);
  const [buyer, setBuyer] = useState('');
  const [method, setMethod] = useState<SalePayment | ''>('');
  const [itemSearch, setItemSearch] = useState('');
  const [productId, setProductId] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const products = useQuery({ queryKey: ['retail', 'products', 'all', itemSearch], queryFn: () => retail.listProducts({ query: itemSearch }), enabled: itemSearch.trim() !== '' });
  const { query, items } = useSales(
    { branchId: all ? undefined : (branchId ?? undefined), from, to, buyer, paymentMethod: method || undefined, productId },
    (all || branchId !== null) && from <= to,
  );
  return (
    <Gate screen="salesHistory" title="All sales">
      {open ? (
        <OpenSale id={open} onClose={() => setOpen(null)} />
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
          <div className="rt-row">
            <div><label htmlFor="sales-from">From</label><input id="sales-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
            <div><label htmlFor="sales-to">To</label><input id="sales-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
          </div>
          {from > to && <p role="alert" className="rt-flag">The start date must not be after the end date.</p>}
          <label htmlFor="sales-buyer">Buyer</label>
          <input id="sales-buyer" type="search" value={buyer} onChange={(e) => setBuyer(e.target.value)} autoComplete="off" />
          <label htmlFor="sales-method">Paid by</label>
          <select id="sales-method" value={method} onChange={(e) => setMethod(e.target.value as SalePayment | '')}>
            <option value="">Any way</option>
            {METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
          </select>
          <label htmlFor="sales-item-search">Item (type part of its name or code)</label>
          <input id="sales-item-search" type="search" value={itemSearch} onChange={(e) => { setItemSearch(e.target.value); setProductId(''); }} autoComplete="off" />
          {itemSearch.trim() !== '' && (
            <>
              <label htmlFor="sales-item">Choose the item</label>
              <select id="sales-item" value={productId} onChange={(e) => setProductId(e.target.value)}>
                <option value="">Any of these</option>
                {(products.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.description} ({p.code})</option>)}
              </select>
            </>
          )}
          {query.isPending && query.fetchStatus !== 'idle' && <p className="loading">Loading</p>}
          <Problem error={query.error} />
          {query.data && <SaleList items={items} today={today} onOpen={setOpen} branchOf={all ? nameOf : undefined} />}
          <More query={query} />
        </>
      )}
    </Gate>
  );
}

export const CreditRoute = createLazyRoute('/staff/retail/credit-sales')({ component: CreditSales });
export const Route = createLazyRoute('/staff/retail/sales')({ component: AllSales });
