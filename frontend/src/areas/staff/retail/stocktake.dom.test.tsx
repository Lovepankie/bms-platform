// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// The count sheet (issue #342, ADR-045): an inactive item that still holds stock stays on the sheet, since a shelf is
// counted whatever the catalogue says; an inactive item holding nothing does not. Fabricated names and amounts.

vi.setConfig({ testTimeout: 20000 });

const listStock = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listStock: (...a: unknown[]) => listStock(...a) } };
});

const { StocktakePage } = await import('./stocktake');

afterEach(() => {
  cleanup();
  listStock.mockReset();
});

const row = (code: string, description: string, qty: string): StockRow => ({ product_id: code, code, description, category: 'Lamps', unit: 'piece', qty, negative: false, sell_minor: 1500 });
const active = [row('A-1', 'Test Lamp Active', '5.000')];
const inactiveHeld = row('I-1', 'Test Lamp Inactive Held', '4.000');
const inactiveEmpty = row('I-2', 'Test Lamp Inactive Empty', '0.000');

describe('the count sheet', () => {
  it('keeps an inactive item that holds stock and leaves out one that holds none', async () => {
    listStock.mockImplementation(({ includeInactive }: { includeInactive?: boolean }) =>
      Promise.resolve(includeInactive ? [...active, inactiveHeld, inactiveEmpty] : active));
    const me = mockMe('admin');
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>
          <StocktakePage />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(screen.getByText(/Test Lamp Active/)).toBeTruthy());
    expect(screen.getByText(/Test Lamp Inactive Held/)).toBeTruthy();
    expect(screen.queryByText(/Test Lamp Inactive Empty/)).toBeNull();
  });
});
