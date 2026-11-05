// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { DamageShare } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { keptForReading } from '../../../offline/read-cache';
import { StaffContext } from '../context';

// Damage and use on Stock health (issue #822, ADR-127): each share in words and numbers with the quantities beside it, the
// highest first as the server sends it, the one-line hint on the base, no share when nothing came in, and nothing at all asked
// or shown for a person without retail.profit.read. Fabricated names and figures.

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
const damageShare = vi.fn();

vi.mock('../../../api/retail-analytics', async (original) => {
  const real = await original<typeof import('../../../api/retail-analytics')>();
  return { ...real, retailAnalytics: { ...real.retailAnalytics, damageShare: (...args: unknown[]) => damageShare(...args) } };
});

const { BASE_HINT, DamageShareSection, DamageShareView, percentWords } = await import('./damage-share');

afterEach(() => {
  cleanup();
  damageShare.mockReset();
});

const nameOf = (id?: string) => (id === BRANCH_A ? 'Test Branch A' : 'Test Branch B');

const data: DamageShare = {
  from: '2026-11-01',
  to: '2026-11-04',
  branches: [
    {
      branch_id: BRANCH_A, base_qty: '50.000', damaged_qty: '6.000', used_qty: '2.000', damaged_pct: 12, used_pct: 4,
      items: [
        { product_id: 'p1', code: 'TI-01', description: 'Test Item 01', unit: 'piece', opening_qty: '40.000', received_qty: '10.000', base_qty: '50.000', damaged_qty: '5.000', used_qty: '2.000', damaged_pct: 10, used_pct: 4 },
        { product_id: 'p3', code: 'TI-03', description: 'Test Item 03', unit: 'kg', opening_qty: '900.000', received_qty: '100.000', base_qty: '1000.000', damaged_qty: '1.000', used_qty: '0.000', damaged_pct: 0, used_pct: 0 },
        { product_id: 'p2', code: 'TI-02', description: 'Test Item 02', unit: 'piece', opening_qty: '0.000', received_qty: '0.000', base_qty: '0.000', damaged_qty: '1.000', used_qty: '0.000' },
      ],
    },
  ],
};

function show(ui: ReactElement, me: Me = mockMe('admin'), branch = BRANCH_A) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{ui}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('damage and use share (#822)', () => {
  it('says each share as a whole percent with the quantities beside it, highest first', () => {
    show(<DamageShareView data={data} nameOf={nameOf} />);
    const shop = screen.getByTestId('damage-share-shop');
    expect(shop.textContent).toContain('Test Branch A');
    expect(shop.textContent).toContain('Shop total');
    expect(shop.textContent).toContain('Damaged 12%, 6 of 50');
    expect(shop.textContent).toContain('Used 4%, 2 of 50');
    const items = screen.getAllByTestId('damage-share-item').map((i) => i.textContent ?? '');
    expect(items[0]).toContain('Test Item 01');
    expect(items[0]).toContain('Damaged 10%, 5 of 50 piece');
    expect(items[0]).toContain('Used 4%, 2 of 50 piece');
    expect(items[0]).toContain('40 at the start, 10 came in');
    expect(items[1]).toContain('Damaged under 1%, 1 of 1,000 kg');
    expect(items[1]).toContain('900 at the start, 100 came in');
    expect(items[2]).toContain('Damaged 1 piece, no stock came in to compare with');
    expect(items[2]).not.toContain('%');
  });

  it('shows the percent in words, never a bare zero for a loss', () => {
    expect(percentWords(10, '5.000')).toBe('10%');
    expect(percentWords(0, '1.000')).toBe('under 1%');
    expect(percentWords(0, '0.000')).toBe('0%');
    expect(percentWords(undefined, '1.000')).toBeNull();
  });

  it('asks for this month and shows the hint on the base to a profit reader', async () => {
    damageShare.mockResolvedValue(data);
    show(<DamageShareSection nameOf={nameOf} />);
    expect(screen.getByText(BASE_HINT)).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('damage-share')).toBeTruthy());
    const asked = damageShare.mock.calls[0]?.[0] as { from: string; to: string };
    expect(asked.from.endsWith('-01')).toBe(true);
    expect(asked.from.slice(0, 7)).toBe(asked.to.slice(0, 7));
  });

  it('asks nothing and shows nothing without retail.profit.read', () => {
    const me = mockMe('sales');
    expect(me.permissions).not.toContain('retail.profit.read');
    show(<DamageShareSection nameOf={nameOf} />, me);
    expect(screen.queryByText(BASE_HINT)).toBeNull();
    expect(screen.queryByText(/Damage and use/)).toBeNull();
    expect(damageShare).not.toHaveBeenCalled();
  });

  // JUSTIFICATION-A3: #854 items 3 and 4 ask for these three cases; they reuse this file's show and data.
  it('groups thousands in every quantity, as everywhere else (#854)', () => {
    const big: DamageShare = { ...data, branches: [{ branch_id: BRANCH_A, base_qty: '9247.000', damaged_qty: '0.000', used_qty: '12.500', damaged_pct: 0, used_pct: 0, items: [] }] };
    show(<DamageShareView data={big} nameOf={nameOf} />);
    const shop = screen.getByTestId('damage-share-shop').textContent ?? '';
    expect(shop).toContain('Damaged 0%, 0 of 9,247');
    expect(shop).toContain('Used under 1%, 12.5 of 9,247');
  });

  const profitAtA = (): Me => {
    const admin = mockMe('admin');
    return { ...admin, permission_scopes: { ...(admin.permission_scopes ?? {}), 'retail.profit.read': { all_branches: false, branch_ids: [BRANCH_A] } } };
  };

  it('shows nothing and asks nothing at a shop where the person may not see profit (#854)', () => {
    show(<DamageShareSection nameOf={nameOf} />, profitAtA(), BRANCH_B);
    expect(screen.queryByText(BASE_HINT)).toBeNull();
    expect(screen.queryByText(/Branch not found/)).toBeNull();
    expect(damageShare).not.toHaveBeenCalled();
  });

  it('shows the section at the shop where the person may see profit (#854)', async () => {
    damageShare.mockResolvedValue(data);
    show(<DamageShareSection nameOf={nameOf} />, profitAtA(), BRANCH_A);
    expect(screen.getByText(BASE_HINT)).toBeTruthy();
    await waitFor(() => expect(damageShare).toHaveBeenCalled());
    expect((damageShare.mock.calls[0]?.[0] as { branchId?: string }).branchId).toBe(BRANCH_A);
  });

  it('keeps the read for offline', () => {
    expect(keptForReading(['retail', 'damage-share', 'all', '2026-11-01', '2026-11-04'])).toBe(true);
  });
});
