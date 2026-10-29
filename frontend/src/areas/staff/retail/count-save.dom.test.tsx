// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #681: Count stock keeps each count as it is typed, on the device at once and in a server draft once typing rests, says
// so, brings it back after a reload or a lost connection, and ends with a clear success screen after Commit the count. Only the
// person's Commit ever commits. Fabricated names and amounts.

vi.setConfig({ testTimeout: 20000 });

const listStock = vi.fn();
const createStocktake = vi.fn();
const commitStocktake = vi.fn();
const discardStocktake = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to, className }: { children: unknown; to: string; className?: string }) => <a href={to} className={className}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listStock: (...a: unknown[]) => listStock(...a),
      createStocktake: (...a: unknown[]) => createStocktake(...a),
      commitStocktake: (...a: unknown[]) => commitStocktake(...a),
      discardStocktake: (...a: unknown[]) => discardStocktake(...a),
    },
  };
});

const { StocktakePage } = await import('./stocktake');

const setOnline = (on: boolean) => {
  Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => on });
  window.dispatchEvent(new Event(on ? 'online' : 'offline'));
};

afterEach(() => {
  cleanup();
  listStock.mockReset();
  createStocktake.mockReset();
  commitStocktake.mockReset();
  discardStocktake.mockReset();
  localStorage.clear();
  setOnline(true);
});

const row = (code: string, description: string, qty: string): StockRow => ({ product_id: code, code, description, category: 'Lamps', unit: 'piece', qty, negative: false, sell_minor: 1500 });
const sheet = [row('A-1', 'Test Lamp', '5.000'), row('A-2', 'Test Plug', '8.000')];
const SENT = { timeout: 5000 };

function open() {
  const me = mockMe('admin');
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>
        <StocktakePage />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const countBox = (id: string) => document.getElementById(`count-${id}`) as HTMLInputElement;
const status = () => screen.getByTestId('count-saved-all').textContent;
async function ready() {
  listStock.mockResolvedValue(sheet);
  open();
  await screen.findByText(/^Not counted yet/);
}
/** Review the difference, answering the question about items not counted. */
async function reviewIt(n: number) {
  fireEvent.click(screen.getByRole('button', { name: `Review the difference (${n} counted)` }));
  const anyway = screen.queryByRole('button', { name: 'Review the difference anyway' });
  if (anyway) fireEvent.click(anyway);
  await screen.findByRole('heading', { name: 'Review the difference' });
}

describe('a count kept as it is typed', () => {
  it('says Saved under each count at once, then All counts saved once the server draft holds them', async () => {
    createStocktake.mockResolvedValue({ id: 'st-a', status: 'draft', lines: [] });
    await ready();
    expect(status()).toBe('');
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    expect(screen.getByTestId('count-saved-A-1').textContent).toBe('Saved');
    expect(screen.queryByTestId('count-saved-A-2')).toBeNull();
    expect(status()).toBe('Saving...');
    await waitFor(() => expect(status()).toBe('All counts saved, 1 item'), SENT);
    expect(createStocktake).toHaveBeenCalledTimes(1);
    expect(createStocktake.mock.calls[0]?.[0]).toEqual({ branch_id: expect.any(String), lines: [{ product_id: 'A-1', counted_qty: '4.000' }] });
    expect(commitStocktake).not.toHaveBeenCalled();
  });

  it('replaces the server draft with a new one as counting goes on, and discards the one before', async () => {
    createStocktake.mockResolvedValueOnce({ id: 'st-a', status: 'draft', lines: [] }).mockResolvedValueOnce({ id: 'st-b', status: 'draft', lines: [] });
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    await waitFor(() => expect(status()).toBe('All counts saved, 1 item'), SENT);
    fireEvent.change(countBox('A-2'), { target: { value: '6' } });
    await waitFor(() => expect(status()).toBe('All counts saved, 2 items'), SENT);
    expect(createStocktake.mock.calls[1]?.[0].lines).toEqual([{ product_id: 'A-1', counted_qty: '4.000' }, { product_id: 'A-2', counted_qty: '6.000' }]);
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('st-a'));
    expect(discardStocktake).not.toHaveBeenCalledWith('st-b');
    expect(commitStocktake).not.toHaveBeenCalled();
  });

  it('brings every count back after a reload', async () => {
    createStocktake.mockResolvedValue({ id: 'st-a', status: 'draft', lines: [] });
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    fireEvent.blur(countBox('A-1'));
    cleanup();
    await ready();
    expect(countBox('A-1').value).toBe('4');
    expect(screen.getByText('Counted items (1)')).toBeTruthy();
    expect(screen.getByTestId('count-saved-A-1').textContent).toBe('Saved');
    expect(screen.getByRole('button', { name: 'Review the difference (1 counted)' })).toBeTruthy();
  });

  it('keeps the counts on the phone while offline, says so, and sends them when the connection is back', async () => {
    createStocktake.mockResolvedValue({ id: 'st-a', status: 'draft', lines: [] });
    await ready();
    setOnline(false);
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    expect(status()).toBe('Saved on this phone, will send when online');
    expect(screen.getByTestId('count-saved-A-1').textContent).toBe('Saved');
    expect(createStocktake).not.toHaveBeenCalled();
    setOnline(true);
    await waitFor(() => expect(status()).toBe('All counts saved, 1 item'), SENT);
    expect(createStocktake).toHaveBeenCalledTimes(1);
  });

  it('loses nothing when the connection drops while a count is sent, and still has it after a reload', async () => {
    createStocktake.mockRejectedValue(new TypeError('Failed to fetch'));
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    await waitFor(() => expect(status()).toBe('Saved on this phone, will send when online'), SENT);
    cleanup();
    await ready();
    expect(countBox('A-1').value).toBe('4');
  });

  it('never says Saved for a count that is not a quantity', async () => {
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: 'abc' } });
    expect(screen.queryByTestId('count-saved-A-1')).toBeNull();
  });
});

describe('the end of a count', () => {
  const lines = [
    { product_id: 'A-1', description: 'Test Lamp', expected_qty: '5.000', counted_qty: '7.000', variance_qty: '2.000' },
    { product_id: 'A-2', description: 'Test Plug', expected_qty: '8.000', counted_qty: '6.000', variance_qty: '-2.000' },
  ];

  it('commits only on Commit the count, then shows Stock updated with the gains and losses, a toast and the next steps', async () => {
    createStocktake.mockResolvedValue({ id: 'st-r', status: 'draft', lines });
    commitStocktake.mockResolvedValue({ id: 'st-r', status: 'committed', lines: lines.map((l) => ({ ...l, committed_variance_qty: l.variance_qty })) });
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '7' } });
    fireEvent.change(countBox('A-2'), { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (2 counted)' }));
    await screen.findByRole('heading', { name: 'Review the difference' });
    expect(commitStocktake).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    await screen.findByRole('heading', { name: 'Stock updated for 2 items' });
    expect(commitStocktake).toHaveBeenCalledWith('st-r', []);
    const detail = document.querySelector('.success-detail')?.textContent ?? '';
    expect(detail).toContain('1 went up, 1 went down.');
    expect(detail).toContain('Test Lamp: 2 pieces more');
    expect(detail).toContain('Test Plug: 2 pieces less');
    expect(document.querySelector('.toast-success')?.textContent).toContain('Stock updated for 2 items');
    expect(screen.getByRole('link', { name: 'Back to My stock' }).getAttribute('href')).toBe('/staff/retail/stock');
    // The count is done, so a reload starts a new one.
    cleanup();
    await ready();
    expect(countBox('A-1').value).toBe('');
    expect(screen.getByText('Counted items (0)')).toBeTruthy();
  });

  it('starts another count, empty, from the success screen', async () => {
    createStocktake.mockResolvedValue({ id: 'st-r', status: 'draft', lines: [lines[0]] });
    commitStocktake.mockResolvedValue({ id: 'st-r', status: 'committed', lines: [lines[0]] });
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '7' } });
    await reviewIt(1);
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    await screen.findByRole('heading', { name: 'Stock updated for 1 item' });
    fireEvent.click(screen.getByRole('button', { name: 'Start another count' }));
    expect(countBox('A-1').value).toBe('');
    expect(screen.getByText('Not counted yet (2)')).toBeTruthy();
  });

  it('keeps the review draft as the one held, discarding the draft saved while typing', async () => {
    createStocktake.mockResolvedValueOnce({ id: 'st-a', status: 'draft', lines: [] }).mockResolvedValueOnce({ id: 'st-r', status: 'draft', lines: [lines[0]] });
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '7' } });
    await waitFor(() => expect(status()).toBe('All counts saved, 1 item'), SENT);
    await reviewIt(1);
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('st-a'));
    expect(discardStocktake).not.toHaveBeenCalledWith('st-r');
    // Back to the count with nothing changed sends nothing new.
    fireEvent.click(screen.getByRole('button', { name: 'Back to the count' }));
    await new Promise((r) => setTimeout(r, 2000));
    expect(createStocktake).toHaveBeenCalledTimes(2);
  });

  it('still asks the big drop tick on the review of a restored count', async () => {
    const drop = { product_id: 'A-2', description: 'Test Plug', expected_qty: '8.000', counted_qty: '0.000', variance_qty: '-8.000' };
    createStocktake.mockResolvedValue({ id: 'st-r', status: 'draft', lines: [drop] });
    await ready();
    fireEvent.change(countBox('A-2'), { target: { value: '0' } });
    cleanup();
    await ready();
    await reviewIt(1);
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    expect(commitStocktake).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Yes, Test Plug is right' }));
    commitStocktake.mockResolvedValue({ ...drop, id: 'st-r', status: 'committed', lines: [drop] });
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('st-r', ['A-2']));
  });
});
