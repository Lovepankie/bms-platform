// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { ItemStock } from '../../../api/item-page';
import { businessToday, type StockMovement } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { ItemPageLink } from './ui';
import { keptForReading } from '../../../offline/read-cache';
import { ItemPageView, inOrOut, movementWords, movementsKey } from './item-page';

// The item page (issue #813, FR-RET-85): the stock in each shop, then every movement newest first with what the shop held after
// it. Buying figures and profit are drawn only when the server sent them, which it does only for retail.profit.read; a seller's
// answer has none, so the page shows none. Every name and figure is invented.

const HQ = 'b-hq';
const TWO = 'b-two';

function item(cost: boolean): ItemStock {
  return {
    product_id: 'p1', code: 'TST-0001', description: 'Test lamp', unit: 'pc', category: 'Test lights', active: true, total_qty: '16.000',
    shops: [
      { branch_id: HQ, code: 'HQ', name: 'Test Shop One', closed: false, qty: '10.000', negative: false, ...(cost ? { avg_cost_minor: 600 } : {}) },
      { branch_id: TWO, code: 'T2', name: 'Test Shop Two', closed: false, qty: '6.000', negative: false, ...(cost ? { avg_cost_minor: 600 } : {}) },
    ],
  };
}

function moves(cost: boolean): StockMovement[] {
  const at = `${businessToday()}T09:00:00Z`;
  const extra = (line: number, profit?: number) => (cost ? { unit_cost_minor: 600, line_cost_minor: line, ...(profit !== undefined ? { profit_minor: profit } : {}) } : {});
  return [
    { id: 'm4', at, branch_id: HQ, product_id: 'p1', unit: 'pc', kind: 'adjustment', qty: '-3.000', balance_after: '10.000', source_type: 'retail.stocktake', historical: false, ...extra(1800) },
    { id: 'm3', at, branch_id: TWO, product_id: 'p1', unit: 'pc', kind: 'sale', qty: '-2.000', balance_after: '6.000', source_type: 'retail.sale', historical: false, ...extra(1200, 800) },
    { id: 'm2', at, branch_id: TWO, product_id: 'p1', unit: 'pc', kind: 'transfer_in', qty: '4.000', balance_after: '8.000', source_type: 'retail.transfer', historical: false, ...extra(2400) },
    { id: 'm1', at, branch_id: HQ, product_id: 'p1', unit: 'pc', kind: 'purchase', qty: '20.000', balance_after: '20.000', source_type: 'retail.purchase', historical: false, ...extra(12000) },
  ];
}

function show(role: 'admin' | 'sales', cost: boolean) {
  const me = mockMe(role);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  // The key the screen builds for every day up to today: no date in it, so a copy kept on an earlier day is found (ADR-116).
  client.setQueryData(movementsKey('p1', '', '', ''), { pages: [{ items: moves(cost) }], pageParams: [''] });
  render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>
        <ItemPageView item={item(cost)} productId="p1" />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

afterEach(cleanup);

describe('the item page (issue #813)', () => {
  it('shows each shop and every movement newest first with the balance after it', () => {
    show('admin', true);
    const shops = screen.getAllByTestId('item-shop');
    expect(shops.map((s) => within(s).getByText(/Test Shop/).textContent)).toEqual(['Test Shop One', 'Test Shop Two']);
    expect(screen.getByTestId('item-total').textContent).toBe('All shops 16 pc');
    const rows = screen.getAllByTestId('item-move');
    expect(rows.map((r) => within(r).getByTestId('item-after').textContent)).toEqual(['10 pc', '6 pc', '8 pc', '20 pc']);
    const text = rows.map((r) => r.textContent ?? '');
    expect(text[0]).toContain('Count');
    expect(text[0]).toContain('Out 3 pc');
    expect(text[3]).toContain('Delivery');
    expect(text[3]).toContain('In 20 pc');
    expect(text[1]).toContain('Test Shop Two');
  });

  it('shows buying figures and profit to the owner, as the server sent them', () => {
    show('admin', true);
    expect(screen.getAllByText(/Average buying price/)).toHaveLength(2);
    expect(screen.getAllByText(/At buying price/)).toHaveLength(4);
    expect(screen.getAllByText(/Profit on the sale/)).toHaveLength(1);
  });

  it('shows a seller the quantities and no buying figure or profit', () => {
    show('sales', false);
    expect(screen.getAllByTestId('item-move')).toHaveLength(4);
    expect(screen.getAllByTestId('item-after')).toHaveLength(4);
    const page = document.body.textContent ?? '';
    expect(page).not.toMatch(/buying price/i);
    expect(page).not.toMatch(/profit/i);
    expect(page).not.toMatch(/UGX/);
  });

  it('reads the movements kept offline on an earlier day: the key of every day up to today holds no date', () => {
    const key = movementsKey('p1', '', '', '');
    expect(key).toEqual(['retail', 'item-movements', 'p1', 'all', '', '']);
    expect(keptForReading(key)).toBe(true);
    expect(JSON.stringify(key)).not.toContain(businessToday());
    // The page above was drawn from a copy under exactly this key, with no request.
    show('sales', false);
    expect(screen.getAllByTestId('item-move')).toHaveLength(4);
  });

  it('says each kind of movement in plain words', () => {
    expect(movementWords({ kind: 'transfer_out' })).toBe('Moved out');
    expect(movementWords({ kind: 'damage' })).toBe('Damaged');
    expect(movementWords({ kind: 'usage' })).toBe('Used in the shop');
    expect(movementWords({ kind: 'adjustment', correction: { reason_code: 'typo', old_qty: '1.000', new_qty: '2.000' } })).toBe('Correction');
    expect(movementWords({ kind: 'return', source_type: 'retail.sale_void', reverses_movement_id: 'm0' })).toBe('Sale cancelled');
    expect(inOrOut({ qty: '-2.500', unit: 'litre' })).toBe('Out 2.5 litre');
  });

  it('is opened by a full size link for whoever may read stock, and by no link for anyone else', () => {
    const me = mockMe('sales');
    const { unmount } = render(<StaffContext.Provider value={{ me, branch: '' }}><ItemPageLink productId="p1" name="Test lamp" /></StaffContext.Provider>);
    const link = screen.getByRole('link', { name: 'Open Test lamp: stock in each shop and every movement' });
    expect(link.getAttribute('href')).toBe('/staff/retail/items/p1');
    expect(link.className).toContain('btn');
    unmount();
    const none = { ...me, permissions: (me.permissions ?? []).filter((p) => p !== 'retail.stock.read') };
    render(<StaffContext.Provider value={{ me: none, branch: '' }}><ItemPageLink productId="p1" name="Test lamp" /></StaffContext.Provider>);
    expect(screen.queryByRole('link')).toBeNull();
  });
});
