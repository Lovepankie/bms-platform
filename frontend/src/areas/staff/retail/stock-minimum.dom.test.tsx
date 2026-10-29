// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockMinimum } from '../../../api/stock-minimum';

// "Warn me when stock is below" says a saved level once (review of #685): one toast, no status line beside it. Fabricated
// item, places and levels.

vi.setConfig({ testTimeout: 20000 });

const get = vi.fn();
const setEveryBranch = vi.fn();
const setBranch = vi.fn();

vi.mock('../../../api/stock-minimum', async (original) => {
  const real = await original<typeof import('../../../api/stock-minimum')>();
  return {
    ...real,
    stockMinimumApi: {
      get: (...a: unknown[]) => get(...a),
      setEveryBranch: (...a: unknown[]) => setEveryBranch(...a),
      setBranch: (...a: unknown[]) => setBranch(...a),
    },
  };
});

const { StockMinimumEditor } = await import('./stock-minimum');
const { DoneToast, doneToasts } = await import('./toast');

afterEach(() => {
  cleanup();
  get.mockReset();
  setEveryBranch.mockReset();
  setBranch.mockReset();
});

const level: StockMinimum = {
  product_id: 'p1',
  min_qty: '4.000',
  can_set_every_branch: true,
  lead_days: 14,
  branches: [{ branch_id: 'b1', branch_name: 'Test place 01', min_qty: '2.000', applies_qty: '2.000', can_set: true }],
} as StockMinimum;

function screenOf() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() })}>
      <StockMinimumEditor productId="p1" unit="piece" />
      <DoneToast />
    </QueryClientProvider>,
  );
}

const toasts = () => Array.from(document.querySelectorAll('.toast')).map((t) => t.textContent ?? '');

describe('a level saved', () => {
  it('says the level for every place in one toast and nothing else', async () => {
    get.mockResolvedValue(level);
    setEveryBranch.mockResolvedValue({ ...level, min_qty: '6.000' });
    screenOf();
    fireEvent.change(await screen.findByLabelText(/^Every shop and store/), { target: { value: '6' } });
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' })[0] as HTMLElement);
    await waitFor(() => expect(toasts()).toEqual(['Level set to 6']));
    expect(screen.queryByText(/warns below 6/)).toBeNull();
  });

  it('says a place\'s own level taken off in one toast and nothing else', async () => {
    get.mockResolvedValue(level);
    setBranch.mockResolvedValue({ ...level, branches: [{ ...level.branches?.[0], min_qty: undefined, applies_qty: '4.000' }] });
    screenOf();
    fireEvent.click(await screen.findByRole('button', { name: 'Use the level for every branch' }));
    await waitFor(() => expect(toasts()).toEqual(['Level removed']));
    expect(setBranch).toHaveBeenCalledWith('p1', 'b1', null);
    expect(screen.queryByText(/follows the level/)).toBeNull();
  });
});
