import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { type ItemShop, type ItemStock, itemPageApi } from '../../../api/item-page';
import { businessToday, type StockMovement } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyState } from '../../../app/ui/EmptyState';
import { PeriodPicker } from '../../../app/ui/PeriodPicker';
import { Hint } from '../../../app/ui/Hint';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { canUse } from './permissions';
import { showDate, showQty } from './maths';
import { dayOf } from './usage-history';
import { Gate, Money, Problem } from './ui';

// The item page (issue #813, ADR-121): the owner checks that stock tallies by opening one item, as the old app allowed. At the top
// what each shop holds of it, then every delivery, sale, move in and out, use, damage, count and correction, newest first, with
// what the shop held just after it. A shop and dates narrow the list; the balances stay those of the whole history. Buying prices
// and profit show only when the server sends them (retail.profit.read). Kept for reading offline like My stock (ADR-116).

/** What a movement was, in plain words. */
export function movementWords(m: Pick<StockMovement, 'kind' | 'source_type' | 'reverses_movement_id' | 'correction'>): string {
  if (m.correction) return 'Correction';
  if (m.source_type === 'retail.sale_void') return 'Sale cancelled';
  if (m.source_type === 'retail.purchase_void' || m.source_type === 'retail.goods_receipt_void') return 'Delivery cancelled';
  if (m.source_type === 'retail.sale_return_void') return 'Customer return cancelled';
  if (m.source_type === 'retail.usage_undo') return 'Usage or damage undone';
  if (m.reverses_movement_id) return 'Cancelled';
  switch (m.kind) {
    case 'opening': return 'Opening stock';
    case 'purchase': return 'Delivery';
    case 'sale': return 'Sale';
    case 'usage': return 'Used in the shop';
    case 'damage': return 'Damaged';
    case 'adjustment': return 'Count';
    case 'return': return 'Customer brought back';
    case 'legacy_balance': return 'Stock brought in from the old records';
    case 'transfer_out': return 'Moved out';
    case 'transfer_in': return 'Moved in';
    case 'return_to_supplier': return 'Sent back to the supplier';
    default: return 'Stock change';
  }
}

/** The query key of the item's movements; `until` is empty for up to today, so the key never carries today's date. */
export function movementsKey(productId: string, branchId: string, from: string, until: string) {
  return ['retail', 'item-movements', productId, branchId || 'all', from, until] as const;
}

/** "In 5 pc" or "Out 2.5 pc": the quantity of a movement without a sign, and which way it went. */
export function inOrOut(m: Pick<StockMovement, 'qty' | 'unit'>): string {
  const qty = m.qty ?? '0';
  const out = qty.startsWith('-');
  const amount = showQty(out ? qty.slice(1) : qty);
  return `${out ? 'Out' : 'In'} ${amount}${m.unit ? ` ${m.unit}` : ''}`;
}

function withUnit(qty: string | undefined, unit: string | undefined): string {
  const q = showQty(qty ?? '0');
  return unit ? `${q} ${unit}` : q;
}

function ShopCard({ shop, unit }: { shop: ItemShop; unit?: string }) {
  return (
    <li className="rt-card item-shop" data-testid="item-shop">
      <p>
        <strong>{shop.name}</strong>
        {shop.closed && <span className="hint"> (closed)</span>}
      </p>
      <p>
        In stock <strong className={shop.negative ? 'num rt-flag' : 'num'}>{withUnit(shop.qty, unit)}</strong>
        {shop.negative && <span className="hint"> Below zero: count it.</span>}
      </p>
      {shop.avg_cost_minor != null && <p className="hint">Average buying price <Money minor={shop.avg_cost_minor} /> each</p>}
    </li>
  );
}

function MoveCard({ m, shop }: { m: StockMovement; shop: string }) {
  return (
    <li className="rt-card item-move" data-testid="item-move">
      <p>
        <strong>{movementWords(m)}</strong> <span className="hint">{showDate(dayOf(m))}, {shop}</span>
      </p>
      <p>
        <span className="num">{inOrOut(m)}</span>. In stock after <span className="num item-after" data-testid="item-after">{withUnit(m.balance_after, m.unit)}</span>
      </p>
      {m.line_cost_minor != null && <p className="hint">At buying price <Money minor={m.line_cost_minor} /></p>}
      {m.profit_minor != null && <p className="hint">Profit on the sale <Money minor={m.profit_minor} /></p>}
      {m.correction ? null : m.note && <p className="hint">{m.note}</p>}
    </li>
  );
}

export function ItemPageView({ item, productId }: { item: ItemStock; productId: string }) {
  const { me } = useStaff();
  const shops = item.shops ?? [];
  const [branchId, setBranchId] = useState('');
  // Every day to start with (an empty first day), as All sales does; the balances are those of the whole history anyway.
  const today = businessToday();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState(today);
  // Up to today is sent as no last day, so the key holds no date and a copy kept yesterday is still found offline today.
  const until = to === today ? '' : to;
  const moves = useInfiniteQuery({
    queryKey: movementsKey(productId, branchId, from, until),
    queryFn: ({ pageParam, signal }) =>
      itemPageApi.movements({ productId, ...(branchId ? { branchId } : {}), ...(from ? { from } : {}), ...(until ? { to: until } : {}), ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
  });
  const items = (moves.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const nameOf = (id: string | undefined) => shops.find((s) => s.branch_id === id)?.name ?? '';
  const narrowed = branchId !== '' || from !== '' || to !== today;
  return (
    <>
      <p>
        {item.code && <span className="hint">Item code {item.code}</span>}
        {item.category && <span className="hint">. {item.category}</span>}
        {item.active === false && <span className="hint">. No longer sold</span>}
      </p>
      <section aria-label="In stock in each shop" data-tour="item-page-shops">
        <h2>In stock</h2>
        {shops.length === 0 ? (
          <p className="hint">You see no shop of this item.</p>
        ) : (
          <ul className="item-shops">
            {shops.map((s) => <ShopCard key={s.branch_id} shop={s} unit={item.unit} />)}
          </ul>
        )}
        {shops.length > 1 && <p className="rt-total" data-testid="item-total">All shops {withUnit(item.total_qty, item.unit)}</p>}
      </section>
      <section aria-label="Every movement of this item" data-tour="item-page-moves">
        <h2>Every movement, newest first</h2>
        <Hint>Each line shows what the shop held just after it, so you can check the count.</Hint>
        <div data-tour="item-page-filters">
          {shops.length > 1 && (
            <div>
              <label htmlFor="item-shop">Shop</label>
              {/* empty: shown only with two shops or more, and Every shop is always there. */}
              <select id="item-shop" value={branchId} onChange={(e) => setBranchId(e.target.value)}>
                <option value="">Every shop</option>
                {shops.map((s) => <option key={s.branch_id} value={s.branch_id}>{s.name}</option>)}
              </select>
            </div>
          )}
          <PeriodPicker from={from} to={to} openStart label="Days of the movements" onChange={(range) => { setFrom(range.from); setTo(range.to); }} />
        </div>
        {moves.isPending && <SkeletonList label="Loading the movements" />}
        <Problem error={moves.error} />
        {moves.data && items.length === 0 && (
          narrowed
            ? <EmptyState text="Nothing moved for this choice." action={{ label: 'Show everything', onClick: () => { setBranchId(''); setFrom(''); setTo(today); } }} />
            : <EmptyState text="Nothing has moved for this item yet." {...(canUse(me, 'stocktake') ? { action: { label: 'Count it now', to: '/staff/retail/stocktake' } } : {})} />
        )}
        <ul className="item-moves">
          {items.map((m) => <MoveCard key={m.id} m={m} shop={nameOf(m.branch_id)} />)}
        </ul>
        {moves.hasNextPage && (
          <BusyButton type="button" busy={moves.isFetchingNextPage} onClick={() => void moves.fetchNextPage()}>
            Show older movements
          </BusyButton>
        )}
      </section>
    </>
  );
}

const route = getRouteApi('/staff/retail/items/$productId');

export function ItemPage() {
  const { me } = useStaff();
  const { productId } = route.useParams();
  const item = useQuery({
    queryKey: ['retail', 'item-stock', productId],
    queryFn: ({ signal }) => itemPageApi.item(productId, signal),
    enabled: canUse(me, 'stock'),
  });
  useCrumbLabel(item.data?.description);
  return (
    <Gate screen="stock" title={item.data?.description ?? 'Item'}>
      {item.isPending && <SkeletonList label="Loading the item" />}
      <Problem error={item.error} />
      {item.data && <ItemPageView item={item.data} productId={productId} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/items/$productId')({ component: ItemPage });
