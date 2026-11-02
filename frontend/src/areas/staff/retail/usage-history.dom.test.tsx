// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockMovement } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Past usage and damage (issue #145): the list asks for used and damaged stock in the days chosen, newest first, shows each
// line with its item, quantity, unit, day and reason, the cost only when the server sent it, and says so when there is none.
// Fabricated items and amounts.

vi.setConfig({ testTimeout: 20000 });

const usageHistory = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, usageHistory: (...a: unknown[]) => usageHistory(...a), listProducts: () => Promise.resolve([]) } };
});

const { UsageHistoryPage, costOf, dayOf, takenOut } = await import('./usage-history');
const { setBusinessZone } = await import('../../../api/retail');

afterEach(() => {
  cleanup();
  usageHistory.mockReset();
});

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';

const used: StockMovement = { id: 'm1', at: '2026-10-20T08:00:00Z', branch_id: branch, product_id: 'p1', code: 'BU-01', description: 'Test bulb', unit: 'piece', kind: 'usage', qty: '-2.000', note: 'Shop lights', unit_cost_minor: 1500, line_cost_minor: 3000 };
const damaged: StockMovement = { id: 'm2', at: '2026-10-21T08:00:00Z', branch_id: branch, product_id: 'p2', code: 'PA-05', description: 'Test paint', unit: 'litre', kind: 'damage', qty: '-0.500', note: 'Tin fell' };

function wrap(ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: admin, branch } as never}>{ui}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('Past usage and damage', () => {
  it('words the quantity with its unit and no minus sign, and costs it only with a unit cost', () => {
    expect(takenOut(damaged)).toBe('0.5 litre');
    expect(costOf(used)).toBe(3000);
    expect(costOf(damaged)).toBeNull();
  });

  it('asks for the chosen branch and lists each line with its reason', async () => {
    usageHistory.mockResolvedValue({ items: [damaged, used] });
    wrap(<UsageHistoryPage />);
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(2));
    expect(usageHistory.mock.calls[0]?.[0]).toMatchObject({ branchId: branch });
    const [first, second] = screen.getAllByTestId('usage-row');
    expect(first?.textContent).toContain('Test paint');
    expect(first?.textContent).toContain('Damaged');
    expect(first?.textContent).toContain('Reason: Tin fell');
    expect(first?.textContent).not.toContain('at buying price');
    expect(second?.textContent).toContain('2 piece');
    expect(second?.textContent).toContain('Used');
    expect(second?.textContent).toContain('at buying price');
  });

  it('says plainly when nothing was used or damaged, with one action that records it', async () => {
    usageHistory.mockResolvedValue({ items: [] });
    wrap(<UsageHistoryPage />);
    expect(await screen.findByText('Nothing was used or damaged in these dates.')).toBeTruthy();
    expect(screen.getByText('Record usage or damage').getAttribute('href')).toBe('/staff/retail/usage');
  });

  it('dates a line on the business clock, the day the from and to filter by, not the UTC day', () => {
    setBusinessZone('Africa/Kampala');
    // 22:30 UTC on 20 Oct is 01:30 on 21 Oct in Kampala (UTC+3).
    expect(dayOf({ ...damaged, at: '2026-10-20T22:30:00Z' })).toBe('2026-10-21');
    expect(dayOf({ ...damaged, at: '' })).toBe('');
  });
});
