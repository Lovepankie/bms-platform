// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { countKey, type SavedCount } from './count-save-state';

// Issue #681 (round 2 of its review): Count stock keeps each count on this device as it is typed and says so, brings it back
// after a reload or a lost connection, merges two tabs item by item, keeps each branch its own count, and ends with a clear
// success screen after Commit the count. Nothing goes to the server before Review the difference. Fabricated names and amounts.

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
  // Back online while the page is still mounted, as React Query hears the event only then.
  setOnline(true);
  cleanup();
  listStock.mockReset();
  createStocktake.mockReset();
  commitStocktake.mockReset();
  discardStocktake.mockReset();
  localStorage.clear();
});

const row = (code: string, description: string, qty: string): StockRow => ({ product_id: code, code, description, category: 'Lamps', unit: 'piece', qty, negative: false, sell_minor: 1500 });
const sheet = [row('A-1', 'Test Lamp', '5.000'), row('A-2', 'Test Plug', '8.000')];
const me = mockMe('admin');
const branchA = me.branches?.[0]?.id ?? '';
const branchB = me.branches?.[1]?.id ?? '';
const keyOf = (branch: string) => countKey(window.location.host, branch, me.user_id ?? '');

function page(branch = branchA) {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>
        <StocktakePage />
      </StaffContext.Provider>
    </QueryClientProvider>
  );
}

const countBox = (id: string) => document.getElementById(`count-${id}`) as HTMLInputElement;
const status = () => screen.getByTestId('count-saved-all').textContent;
async function ready() {
  listStock.mockResolvedValue(sheet);
  render(page());
  await screen.findByText(/^Not counted yet/);
}
/** Review the difference, answering the question about items not counted. */
async function reviewIt(n: number) {
  fireEvent.click(screen.getByRole('button', { name: `Review the difference (${n} counted)` }));
  const anyway = screen.queryByRole('button', { name: 'Review the difference anyway' });
  if (anyway) fireEvent.click(anyway);
  await screen.findByRole('heading', { name: 'Review the difference' });
}
/** What another tab of the same count writes. */
const otherTab = (key: string, value: SavedCount | null) =>
  act(() => {
    window.dispatchEvent(new StorageEvent('storage', { key, newValue: value === null ? null : JSON.stringify(value) }));
  });

describe('a count kept on this phone as it is typed', () => {
  it('says Saved under each count at once and Saved on this phone at the top, and sends nothing before the review', async () => {
    await ready();
    expect(status()).toBe('');
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    expect(screen.getByTestId('count-saved-A-1').textContent).toBe('Saved');
    expect(screen.queryByTestId('count-saved-A-2')).toBeNull();
    expect(status()).toBe('Saved on this phone, 1 item');
    fireEvent.change(countBox('A-2'), { target: { value: '6' } });
    expect(status()).toBe('Saved on this phone, 2 items');
    await new Promise((r) => setTimeout(r, 2000));
    expect(createStocktake).not.toHaveBeenCalled();
    expect(discardStocktake).not.toHaveBeenCalled();
    expect(commitStocktake).not.toHaveBeenCalled();
  });

  it('brings every count back after a reload', async () => {
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

  it('keeps the counts when the connection is lost, and after a reload', async () => {
    await ready();
    setOnline(false);
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    expect(status()).toBe('Saved on this phone, 1 item');
    // The sheet itself needs the connection to load, so the page opens again once it is back.
    setOnline(true);
    cleanup();
    await ready();
    expect(countBox('A-1').value).toBe('4');
  });

  it('never says Saved when the phone cannot store the count', async () => {
    await ready();
    const full = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('QuotaExceededError'); });
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    expect(status()).toBe('Not saved on this phone. Keep this page open.');
    expect(screen.queryByTestId('count-saved-A-1')).toBeNull();
    full.mockRestore();
  });

  it('never says Saved for a count that is not a quantity', async () => {
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: 'abc' } });
    expect(screen.queryByTestId('count-saved-A-1')).toBeNull();
  });
});

// Review of #681, must-fix 1 and 2: two tabs of one count, and a branch switch.
describe('two tabs and two branches', () => {
  it('merges a change from another tab item by item, the newest count of each item winning, and says the count is open there', async () => {
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    fireEvent.blur(countBox('A-1'));
    const stamped = readStored(branchA).at['A-1'] ?? 0;
    // The other tab counted A-2 now, and holds an older count of A-1.
    otherTab(keyOf(branchA), { counts: { 'A-1': '9', 'A-2': '6' }, packCounts: {}, listed: ['A-1', 'A-2'], done: ['A-1', 'A-2'], at: { 'A-1': stamped - 1000, 'A-2': stamped + 1000 } });
    expect(countBox('A-1').value).toBe('4');
    expect(countBox('A-2').value).toBe('6');
    expect(screen.getByTestId('count-other-tab').textContent).toBe('This count is also open in another tab');
    expect(screen.getByText('Counted items (2)')).toBeTruthy();
    expect(readStored(branchA).counts).toEqual({ 'A-1': '4', 'A-2': '6' });
  });

  it('starts empty and says so when the count is committed in another tab', async () => {
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    // The other tab's commit removes the key, which this tab hears.
    localStorage.removeItem(keyOf(branchA));
    otherTab(keyOf(branchA), null);
    expect(countBox('A-1').value).toBe('');
    expect(screen.getByTestId('count-other-tab').textContent).toBe('Counted and saved in another tab');
    expect(localStorage.getItem(keyOf(branchA))).toBeNull();
  });

  it('keeps each branch its own count when the branch is switched, and back', async () => {
    localStorage.setItem(keyOf(branchB), JSON.stringify({ counts: { 'A-2': '3' }, packCounts: {}, listed: ['A-2'], done: ['A-2'], at: { 'A-2': 1 } }));
    listStock.mockResolvedValue(sheet);
    const view = render(page(branchA));
    await screen.findByText(/^Not counted yet/);
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    view.rerender(page(branchB));
    await screen.findByText(/^Not counted yet/);
    expect(countBox('A-1').value).toBe('');
    expect(countBox('A-2').value).toBe('3');
    expect(readStored(branchB).counts).toEqual({ 'A-2': '3' });
    view.rerender(page(branchA));
    await screen.findByText(/^Not counted yet/);
    expect(countBox('A-1').value).toBe('4');
    expect(countBox('A-2').value).toBe('');
  });
});

function readStored(branch: string): SavedCount {
  return JSON.parse(localStorage.getItem(keyOf(branch)) ?? 'null') as SavedCount;
}

describe('the end of a count', () => {
  const lines = [
    { product_id: 'A-1', description: 'Test Lamp', expected_qty: '5.000', counted_qty: '7.000', variance_qty: '2.000' },
    { product_id: 'A-2', description: 'Test Plug', expected_qty: '8.000', counted_qty: '6.000', variance_qty: '-2.000' },
  ];

  it('makes the draft only at Review, commits only on Commit the count, then shows Stock updated with the gains and losses', async () => {
    createStocktake.mockResolvedValue({ id: 'st-r', status: 'draft', lines });
    commitStocktake.mockResolvedValue({ id: 'st-r', status: 'committed', lines: lines.map((l) => ({ ...l, committed_variance_qty: l.variance_qty })) });
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '7' } });
    fireEvent.change(countBox('A-2'), { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (2 counted)' }));
    await screen.findByRole('heading', { name: 'Review the difference' });
    expect(createStocktake).toHaveBeenCalledTimes(1);
    expect(createStocktake.mock.calls[0]?.[0]).toEqual({ branch_id: branchA, lines: [{ product_id: 'A-1', counted_qty: '7.000' }, { product_id: 'A-2', counted_qty: '6.000' }] });
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
    // The counts that went in leave the device copy, marked committed, so another tab hears it and a reload starts afresh.
    expect(readStored(branchA).counts).toEqual({});
    expect(readStored(branchA).committed).toBeGreaterThan(0);
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

// Review of #686, round 3: a commit takes away only the counts that went in, and a count kept from before says when it was saved.
describe('a commit and an old count', () => {
  const line = (id: string, description: string, expected: string, counted: string) =>
    ({ product_id: id, description, expected_qty: expected, counted_qty: counted, variance_qty: (Number(counted) - Number(expected)).toFixed(3) });

  it('keeps a count changed in another tab after the review, and commits only what was reviewed', async () => {
    const lines = [line('A-1', 'Test Lamp', '5.000', '4.000'), line('A-2', 'Test Plug', '8.000', '6.000')];
    createStocktake.mockResolvedValue({ id: 'st-r', status: 'draft', lines });
    commitStocktake.mockResolvedValue({ id: 'st-r', status: 'committed', lines });
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    fireEvent.change(countBox('A-2'), { target: { value: '6' } });
    await reviewIt(2);
    // While this tab shows the review, the other tab changes Test Lamp to 5.
    const mine = readStored(branchA);
    otherTab(keyOf(branchA), { ...mine, counts: { 'A-1': '5', 'A-2': '6' }, at: { ...mine.at, 'A-1': (mine.at['A-1'] ?? 0) + 1000 } });
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    await screen.findByRole('heading', { name: 'Stock updated for 2 items' });
    expect(commitStocktake).toHaveBeenCalledWith('st-r', []);
    // Test Plug went in as counted and leaves; Test Lamp's newer 5 never went in, so it stays, on the page and on the device.
    expect(readStored(branchA).counts).toEqual({ 'A-1': '5' });
    fireEvent.click(screen.getByRole('button', { name: 'Start another count' }));
    expect(countBox('A-1').value).toBe('5');
    expect(countBox('A-2').value).toBe('');
  });

  it('in the other tab, drops only the counts that went in and says some were saved, then all', async () => {
    await ready();
    fireEvent.change(countBox('A-1'), { target: { value: '4' } });
    fireEvent.change(countBox('A-2'), { target: { value: '6' } });
    const mine = readStored(branchA);
    const later = Math.max(...Object.values(mine.at)) + 1000;
    // The other tab committed Test Lamp only.
    otherTab(keyOf(branchA), { counts: { 'A-2': '6' }, packCounts: {}, listed: ['A-2'], done: ['A-2'], at: { ...mine.at, 'A-1': later }, committed: later });
    expect(countBox('A-1').value).toBe('');
    expect(countBox('A-2').value).toBe('6');
    expect(screen.getByTestId('count-other-tab').textContent).toBe('Some counts were saved in another tab. The others are still here.');
    // Then the rest.
    otherTab(keyOf(branchA), { counts: {}, packCounts: {}, listed: [], done: [], at: { 'A-1': later, 'A-2': later + 1000 }, committed: later + 1000 });
    expect(countBox('A-2').value).toBe('');
    expect(screen.getByTestId('count-other-tab').textContent).toBe('Counted and saved in another tab');
  });

  it('says when a count kept from before was saved, warns when it is old, and starts again on request', async () => {
    const monday = Date.now() - 4 * 24 * 60 * 60 * 1000;
    localStorage.setItem(keyOf(branchA), JSON.stringify({ counts: { 'A-1': '10', 'A-2': '3' }, packCounts: {}, listed: ['A-1', 'A-2'], done: ['A-1', 'A-2'], at: { 'A-1': monday, 'A-2': monday + 60_000 } }));
    await ready();
    const kept = screen.getByTestId('count-kept');
    expect(kept.className).toContain('alert-warning');
    expect(kept.textContent).toMatch(/^These counts were saved on \d{1,2} \w{3} \d{4}, \d{2}:\d{2}\. Stock may have changed since, so check them or start again\./);
    fireEvent.click(screen.getByRole('button', { name: 'Start again' }));
    expect(screen.getByText('Clear all 2 counts? Your stock does not change.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Keep them' }));
    expect(countBox('A-1').value).toBe('10');
    fireEvent.click(screen.getByRole('button', { name: 'Start again' }));
    fireEvent.click(screen.getByRole('button', { name: 'Clear the counts' }));
    expect(countBox('A-1').value).toBe('');
    expect(countBox('A-2').value).toBe('');
    expect(screen.queryByTestId('count-kept')).toBeNull();
    expect(readStored(branchA).counts).toEqual({});
    expect(createStocktake).not.toHaveBeenCalled();
  });

  it('says a recent count kept from before was saved, without the warning', async () => {
    localStorage.setItem(keyOf(branchA), JSON.stringify({ counts: { 'A-1': '10' }, packCounts: {}, listed: ['A-1'], done: ['A-1'], at: { 'A-1': Date.now() - 60_000 } }));
    await ready();
    const kept = screen.getByTestId('count-kept');
    expect(kept.className).toContain('alert-info');
    expect(kept.textContent).not.toContain('Stock may have changed');
  });

  it('keeps an item found but not counted yet', async () => {
    await ready();
    fireEvent.change(screen.getByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'plug' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Plug/ }));
    expect(readStored(branchA).listed).toEqual(['A-2']);
  });
});
