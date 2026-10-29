// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { StockTable } from './stock';

// Issue #680: My stock on a phone is a card per item: the name, "In stock: 8 pieces" (or "8 pc" for a short unit) large, the buying and selling price in small
// words under it, and Correct as a secondary button. A wide screen keeps the table with the new headers. Every name is invented.

const rows: StockRow[] = [
  { product_id: 'p1', code: 'TE-1', description: 'Test extension cable 4 way', category: 'Extensions', unit: 'piece', qty: '8.000', negative: false, sell_minor: 8000, cost_minor: 6000 },
];

function screenAt(phone: boolean) {
  vi.stubGlobal('matchMedia', (media: string) => ({ matches: phone && media === '(max-width: 719px)', media, addEventListener: () => undefined, removeEventListener: () => undefined }));
}

function show(node: ReactNode) {
  const me = mockMe('admin');
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('My stock on a phone (issue #680)', () => {
  it('shows each item as a card: the name, the quantity large, the two prices and Correct', () => {
    screenAt(true);
    show(<StockTable rows={rows} showCost correctable={{ branchId: 'b1' }} />);
    expect(screen.queryByRole('table')).toBeNull();
    const card = screen.getByRole('listitem');
    expect(within(card).getByText('Test extension cable 4 way')).toBeTruthy();
    expect(card.querySelector('.stock-card-qty')?.textContent).toBe('In stock: 8 pieces');
    expect(card.textContent).toContain('Buying price UGX 6,000 · Selling price UGX 8,000');
    const correct = within(card).getByRole('button', { name: 'Correct Test extension cable 4 way' });
    expect(correct.className).toBe('btn-sm');
    fireEvent.click(correct);
    expect(correct.getAttribute('aria-expanded')).toBe('true');
  });

  it('keeps a short unit code as typed, and one of a unit in the singular', () => {
    screenAt(true);
    show(<StockTable rows={[{ ...rows[0], unit: 'pc' }, { ...rows[0], product_id: 'p2', description: 'Test plug', qty: '1.000' }]} showCost={false} />);
    expect([...document.querySelectorAll('.stock-card-qty')].map((q) => q.textContent)).toEqual(['In stock: 8 pc', 'In stock: 1 piece']);
    expect(screen.getByText('2 items, worth UGX 72,000 at selling price')).toBeTruthy();
  });

  it('shows only the selling price to a seller who may not see buying prices', () => {
    screenAt(true);
    show(<StockTable rows={[{ ...rows[0], cost_minor: undefined } as StockRow]} showCost={false} />);
    const card = screen.getByRole('listitem');
    expect(card.textContent).toContain('Selling price UGX 8,000');
    expect(card.textContent).not.toContain('Buying');
    expect(within(card).queryByRole('button', { name: /Correct/ })).toBeNull();
  });

  it('keeps the table on a wide screen, with the new headers', () => {
    screenAt(false);
    show(<StockTable rows={rows} showCost />);
    const headers = within(screen.getByRole('table')).getAllByRole('columnheader').map((h) => h.textContent);
    // Grouped by category, as the list starts, so the category is the group's header and not a column.
    expect(headers).toEqual(['Item', 'In stock', 'Average buying price', 'Selling price']);
  });
});
