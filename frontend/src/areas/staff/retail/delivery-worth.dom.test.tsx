// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { businessToday, type Product, type Purchase } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// What a delivery is worth (issue #821, ADR-126), as the owner's old delivery form showed it: under the lines of Buy stock the
// total cost, what the goods should sell for and the expected profit, live as the lines change, and the same three on each row of
// Past purchases. Only a person with retail.profit.read sees them; a buyer without it sees Buy stock as before. Every price and
// quantity is fabricated.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useNavigate: () => vi.fn(), Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listProducts: () => Promise.resolve([]), listSuppliers: () => Promise.resolve([]) } };
});

const { RestockForm, restockWorth } = await import('./restock');
const { RestockCard } = await import('./restocks');
const { PROFIT } = await import('./permissions');

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

/** The owner, with retail.profit.read and the units capability off. */
const owner = { ...mockMe('admin'), module_capabilities: { retail: [] } };
/** A buyer who may buy stock and change prices but not read profit. */
const buyer = { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.profit.read') };
const branch = owner.branches?.[0]?.id ?? '';
const rice: Product = { id: 'p1', code: 'T-RICE', description: 'Test rice', unit: 'bag', cost_minor: 1000, sell_minor: 1500, active: true };
const soap: Product = { id: 'p2', code: 'T-SOAP', description: 'Test soap', unit: 'bar', cost_minor: 300, sell_minor: 500, active: true };

function screenOf(me: typeof owner, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const draft = () => ({
  supplierId: '', purchasedOn: businessToday(), method: 'cash' as const,
  lines: [
    { product: rice, cost: '1000', sell: '1600', qtyByBranch: { [branch]: '5' } },
    { product: soap, cost: '300', sell: '', qtyByBranch: { [branch]: '10' } },
  ],
});

/** The three figures as their words and amounts, in order. */
function figures(within_: HTMLElement): string[] {
  return [...within_.querySelectorAll('[data-testid="delivery-worth"] > div')].map((row) => `${row.querySelector('dt')?.textContent}: ${row.querySelector('dd')?.textContent}`);
}

describe('the worth of a delivery on the form', () => {
  it('uses the selling price on the line, else the item\'s current price', () => {
    const lines = draft().lines;
    // 5 x 1,600 on the line, and 10 x 500 from the item.
    expect(restockWorth(lines)).toBe(13_000);
    expect(restockWorth([{ ...lines[1]!, sell: '450' }])).toBe(4_500);
    expect(restockWorth([])).toBe(0);
  });

  it('is not known while an item has no selling price yet', () => {
    const lines = draft().lines;
    expect(restockWorth([lines[0]!, { ...lines[1]!, product: { ...soap, sell_minor: 0 } }])).toBeNull();
    expect(restockWorth([{ ...lines[0]!, sell: '0' }])).toBeNull();
  });

  it('counts a line bought in packs in the item\'s own unit, at the price per unit', () => {
    const packed: Product = { ...soap, packs: [{ unit_id: 'u-box', unit: 'box', factor: '12', active: true, purpose: 'both' }] } as Product;
    // 2 boxes of 12 bars at 500 a bar.
    expect(restockWorth([{ product: packed, cost: '3000', sell: '500', unitId: 'u-box', qtyByBranch: { [branch]: '2' } }])).toBe(12_000);
  });
});

describe('Buy stock (issue #821)', () => {
  it('shows the owner the total cost, what it should sell for and the expected profit, in tabular money', () => {
    screenOf(owner, <RestockForm initial={draft()} />);
    const total = screen.getByTestId('restock-total');
    // Cost 5 x 1,000 + 10 x 300 = 8,000; worth 13,000.
    expect(figures(total)).toEqual(['Total cost: UGX 8,000', 'Should sell for: UGX 13,000', 'Expected profit: UGX 5,000']);
    expect(total.querySelectorAll('dd .num')).toHaveLength(3);
    expect(within(total).getByLabelText('This delivery')).toBeTruthy();
  });

  it('updates the figures as the lines change', () => {
    screenOf(owner, <RestockForm initial={draft()} />);
    fireEvent.change(document.getElementById(`q-0-${branch}`) as HTMLInputElement, { target: { value: '6' } });
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 9,000', 'Should sell for: UGX 14,600', 'Expected profit: UGX 5,600']);
    fireEvent.change(document.getElementById('sell-1') as HTMLInputElement, { target: { value: '200' } });
    // The soap now sells below its cost: 6 x 1,600 + 10 x 200 = 11,600 against 9,000.
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 9,000', 'Should sell for: UGX 11,600', 'Expected profit: UGX 2,600']);
    fireEvent.change(document.getElementById('cost-0') as HTMLInputElement, { target: { value: '2000' } });
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 15,000', 'Should sell for: UGX 11,600', 'Expected loss: UGX 3,400']);
    fireEvent.click(screen.getByRole('button', { name: 'Remove Test rice' }));
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 3,000', 'Should sell for: UGX 2,000', 'Expected loss: UGX 1,000']);
  });

  it('says so instead of the two figures while an item has no selling price yet', () => {
    const lines = draft().lines;
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [lines[0]!, { ...lines[1]!, product: { ...soap, sell_minor: 0 } }] }} />);
    const total = screen.getByTestId('restock-total');
    expect(figures(total)).toEqual(['Total cost: UGX 8,000']);
    expect(within(total).getByTestId('unpriced-note').textContent).toBe('Some items have no selling price yet.');
    // A price typed for the soap brings the figures back: 5 x 1,600 + 10 x 450.
    fireEvent.change(document.getElementById('sell-1') as HTMLInputElement, { target: { value: '450' } });
    expect(figures(total)).toEqual(['Total cost: UGX 8,000', 'Should sell for: UGX 12,500', 'Expected profit: UGX 4,500']);
    expect(screen.queryByTestId('unpriced-note')).toBeNull();
  });

  it('works nothing out for someone who reads profit only at a branch the delivery does not go to', () => {
    const other = owner.branches?.find((b) => b.id !== branch)?.id ?? 'b-other';
    const elsewhere = { ...owner, permission_scopes: { ...(owner.permission_scopes ?? {}), [PROFIT]: { all_branches: false, branch_ids: [other] } } };
    screenOf(elsewhere, <RestockForm initial={draft()} />);
    expect(screen.queryByTestId('delivery-worth')).toBeNull();
    expect(screen.getByTestId('restock-total').textContent).toBe('Total cost UGX 8,000');
  });

  it('shows a buyer without the profit permission the total cost only, as before', () => {
    screenOf(buyer, <RestockForm initial={draft()} />);
    const total = screen.getByTestId('restock-total');
    expect(total.tagName).toBe('P');
    expect(total.textContent).toBe('Total cost UGX 8,000');
    expect(screen.queryByTestId('delivery-worth')).toBeNull();
    expect(screen.queryByText(/Should sell for|Expected profit|Expected loss/)).toBeNull();
  });
});

describe('Past purchases (issue #821)', () => {
  const purchase: Purchase = {
    id: 'pu1', purchase_no: 'RP00000001', purchased_on: '2026-10-30', payment_method: 'cash', currency: 'UGX', total_minor: 8_000, status: 'posted',
    lines: [{ id: 'l1', description: 'Test rice', qty_total: '5.000', qty_by_branch: [{ branch_id: branch, qty: '5.000' }], base_unit: 'bag' }],
    sell_worth_minor: 13_000, expected_profit_minor: 5_000,
  };

  it('shows the owner the three figures the server sent on each row', () => {
    screenOf(owner, <ul><RestockCard purchase={purchase} onDone={() => undefined} /></ul>);
    const card = screen.getByTestId('restock-card');
    expect(figures(card)).toEqual(['Total cost: UGX 8,000', 'Should sell for: UGX 13,000', 'Expected profit: UGX 5,000']);
    expect(card.querySelectorAll('dd .num')).toHaveLength(3);
    expect(within(card).getByLabelText('Purchase RP00000001')).toBeTruthy();
    // The total is said once, in the figures.
    expect(card.textContent).not.toContain('Total UGX');
  });

  it('shows a voided delivery with its total alone, since it will never sell', () => {
    screenOf(owner, <ul><RestockCard purchase={{ ...purchase, status: 'voided', voided_on: '2026-10-31', void_reason: 'Test wrong delivery' }} onDone={() => undefined} /></ul>);
    const card = screen.getByTestId('restock-card');
    expect(screen.queryByTestId('delivery-worth')).toBeNull();
    expect(card.textContent).toContain('Total UGX 8,000.');
  });

  it('says an item has no selling price yet when the server says so', () => {
    const { sell_worth_minor: _w, expected_profit_minor: _p, ...unknown } = purchase;
    screenOf(owner, <ul><RestockCard purchase={{ ...unknown, has_unpriced_items: true }} onDone={() => undefined} /></ul>);
    const card = screen.getByTestId('restock-card');
    expect(figures(card)).toEqual(['Total cost: UGX 8,000']);
    expect(within(card).getByTestId('unpriced-note').textContent).toBe('Some items have no selling price yet.');
  });

  it('says a loss as one', () => {
    screenOf(owner, <ul><RestockCard purchase={{ ...purchase, sell_worth_minor: 7_500, expected_profit_minor: -500 }} onDone={() => undefined} /></ul>);
    expect(figures(screen.getByTestId('restock-card'))).toContain('Expected loss: UGX 500');
  });

  it('shows a buyer without the profit permission the total as before and works nothing out', () => {
    const { sell_worth_minor: _w, expected_profit_minor: _p, ...asSent } = purchase;
    screenOf(buyer, <ul><RestockCard purchase={asSent} onDone={() => undefined} /></ul>);
    const card = screen.getByTestId('restock-card');
    expect(card.textContent).toContain('Total UGX 8,000.');
    expect(screen.queryByTestId('delivery-worth')).toBeNull();
    expect(card.textContent).not.toMatch(/Should sell for|Expected profit|Expected loss/);
  });
});
