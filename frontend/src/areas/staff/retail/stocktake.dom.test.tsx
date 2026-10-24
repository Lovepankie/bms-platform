// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// The count sheet (issue #342, ADR-045, issue #534): an inactive item that still holds stock stays on the sheet, since a shelf is
// counted whatever the catalogue says; an inactive item holding nothing does not. Fabricated names and amounts.

vi.setConfig({ testTimeout: 20000 });

const listStock = vi.fn();
const createStocktake = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listStock: (...a: unknown[]) => listStock(...a), createStocktake: (...a: unknown[]) => createStocktake(...a) } };
});

const { StocktakePage } = await import('./stocktake');

afterEach(() => {
  cleanup();
  listStock.mockReset();
  createStocktake.mockReset();
});

const row = (code: string, description: string, qty: string): StockRow => ({ product_id: code, code, description, category: 'Lamps', unit: 'piece', qty, negative: false, sell_minor: 1500 });
const active = [row('A-1', 'Test Lamp Active', '5.000')];
const inactiveHeld = row('I-1', 'Test Lamp Inactive Held', '4.000');
const inactiveEmpty = row('I-2', 'Test Lamp Inactive Empty', '0.000');

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

const box = () => screen.getByRole('combobox', { name: 'Find an item by name, code or category' }) as HTMLInputElement;

const EMPTY = 'No items yet. Find an item above, scan it, or count it in the list below.';
const heading = (name: RegExp) => screen.getByRole('heading', { name });
const inside = (section: RegExp, id: string) => {
  // The section a count line sits in: the nearest heading before it.
  const el = document.getElementById(`count-${id}`);
  const headings = [...document.querySelectorAll('h2')].filter((h) => el && h.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING);
  return section.test(headings[headings.length - 1]?.textContent ?? '');
};

describe('the count sheet', () => {
  it('keeps an inactive item that holds stock and leaves out one that holds none', async () => {
    listStock.mockImplementation(({ includeInactive }: { includeInactive?: boolean }) =>
      Promise.resolve(includeInactive ? [...active, inactiveHeld, inactiveEmpty] : active));
    open();
    await screen.findByText(EMPTY);
    expect(document.getElementById('count-A-1')).not.toBeNull();
    expect(document.getElementById('count-I-1')).not.toBeNull();
    expect(document.getElementById('count-I-2')).toBeNull();
    fireEvent.change(box(), { target: { value: 'test lamp' } });
    expect(await screen.findByRole('option', { name: /^Test Lamp Active/ })).toBeTruthy();
    expect(screen.getByRole('option', { name: /^Test Lamp Inactive Held/ })).toBeTruthy();
    expect(screen.queryByRole('option', { name: /Test Lamp Inactive Empty/ })).toBeNull();
  });
});

// Issue #534 and the owner's decision on #535: the search box on top lists items only while something is typed; below it the
// whole sheet stays as Not counted yet, a line moves to Counted items once its count is in, and finishing with lines left asks.
describe('counting the sheet', () => {
  it('shows every item of the sheet as not counted yet, and no search list until something is typed', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText(EMPTY);
    expect(heading(/^Not counted yet \(2\)$/)).toBeTruthy();
    expect(heading(/^Counted items \(0\)$/)).toBeTruthy();
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(box().getAttribute('aria-expanded')).toBe('false');
    fireEvent.change(box(), { target: { value: 'A-1' } });
    expect(await screen.findAllByRole('option')).toHaveLength(1);
    expect(box().getAttribute('aria-expanded')).toBe('true');
  });

  it('puts a picked item at the head of the list, marked, clears and closes the search and puts the cursor in its count box', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText(EMPTY);
    fireEvent.change(box(), { target: { value: 'held' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Lamp Inactive Held/ }));
    expect(box().value).toBe('');
    expect(screen.queryByRole('listbox')).toBeNull();
    // The point of the pick is its count (review of #535), so its box takes the cursor rather than the search.
    expect(document.activeElement?.id).toBe('count-I-1');
    expect(screen.getByTestId('scan-hit').querySelector('input')?.id).toBe('count-I-1');
    const lines = [...document.querySelectorAll('.rt-card input[id^="count-"]')].map((i) => i.id);
    expect(lines).toEqual(['count-I-1', 'count-A-1']);
  });

  it('moves a line to Counted items once its count is in, by a typed count or the 0 button, and back when emptied', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText(EMPTY);
    const lamp = document.getElementById('count-A-1') as HTMLInputElement;
    fireEvent.change(lamp, { target: { value: '4' } });
    expect(inside(/^Not counted yet/, 'A-1')).toBe(true);
    fireEvent.blur(lamp);
    expect(inside(/^Counted items/, 'A-1')).toBe(true);
    expect(heading(/^Not counted yet \(1\)$/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Count 0 of Test Lamp Inactive Held' }));
    expect((document.getElementById('count-I-1') as HTMLInputElement).value).toBe('0');
    expect(inside(/^Counted items/, 'I-1')).toBe(true);
    expect(screen.getByText('Every item on the sheet is counted.')).toBeTruthy();
    fireEvent.change(document.getElementById('count-A-1') as HTMLInputElement, { target: { value: '' } });
    fireEvent.blur(document.getElementById('count-A-1') as HTMLInputElement);
    expect(inside(/^Not counted yet/, 'A-1')).toBe(true);
  });

  it('says how many are not counted before finishing, and sends only the counted lines when finished anyway', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    createStocktake.mockResolvedValue({ id: 'st1', status: 'draft', lines: [] });
    open();
    await screen.findByText(EMPTY);
    fireEvent.click(screen.getByRole('button', { name: 'Count 0 of Test Lamp Inactive Held' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review variance (1 counted)' }));
    expect(screen.getByTestId('count-not-done').textContent).toContain('1 item not counted. Finish anyway?');
    expect(createStocktake).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Finish anyway' }));
    await waitFor(() => expect(createStocktake).toHaveBeenCalledTimes(1));
    expect(createStocktake.mock.calls[0]?.[0]).toEqual({ branch_id: expect.any(String), lines: [{ product_id: 'I-1', counted_qty: '0' }] });
  });

  it('on Enter moves the counted line up and the cursor to the next line not counted yet', async () => {
    const third = row('A-2', 'Test Lamp Second', '2.000');
    listStock.mockResolvedValue([...active, third, inactiveHeld]);
    open();
    await screen.findByText(EMPTY);
    const order = [...document.querySelectorAll('.rt-card input[id^="count-"]')].map((i) => i.id);
    const first = document.getElementById(order[0] ?? '') as HTMLInputElement;
    first.focus();
    fireEvent.change(first, { target: { value: '5' } });
    fireEvent.keyDown(first, { key: 'Enter' });
    await waitFor(() => expect(document.activeElement?.id).toBe(order[1]));
    expect(inside(/^Counted items/, (order[0] ?? '').replace('count-', ''))).toBe(true);
  });

  // Review of #535: the question answers the counts it was asked about, and Finish anyway keeps the Review button's guards.
  it('withdraws the question when a count changes, so Finish anyway never sends an invalid or empty count', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText(EMPTY);
    const held = document.getElementById('count-I-1') as HTMLInputElement;
    fireEvent.change(held, { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review variance (1 counted)' }));
    expect(screen.getByTestId('count-not-done')).toBeTruthy();
    fireEvent.change(document.getElementById('count-I-1') as HTMLInputElement, { target: { value: '4x' } });
    expect(screen.queryByTestId('count-not-done')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Finish anyway' })).toBeNull();
    expect(createStocktake).not.toHaveBeenCalled();
  });

  it('keeps Finish anyway to one request however often it is tapped', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    createStocktake.mockImplementation(() => new Promise(() => {}));
    open();
    await screen.findByText(EMPTY);
    fireEvent.click(screen.getByRole('button', { name: 'Count 0 of Test Lamp Inactive Held' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review variance (1 counted)' }));
    const go = screen.getByRole('button', { name: 'Finish anyway' });
    fireEvent.click(go);
    fireEvent.click(go);
    await waitFor(() => expect(createStocktake).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 50));
    expect(createStocktake).toHaveBeenCalledTimes(1);
  });

  it('goes back to the count on Keep counting, and sends nothing', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText(EMPTY);
    fireEvent.click(screen.getByRole('button', { name: 'Count 0 of Test Lamp Inactive Held' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review variance (1 counted)' }));
    fireEvent.click(screen.getByRole('button', { name: 'Keep counting' }));
    expect(screen.queryByTestId('count-not-done')).toBeNull();
    expect(createStocktake).not.toHaveBeenCalled();
  });

  it('finishes at once when every line is counted', async () => {
    listStock.mockResolvedValue(active);
    createStocktake.mockResolvedValue({ id: 'st1', status: 'draft', lines: [] });
    open();
    await screen.findByText(EMPTY);
    fireEvent.click(screen.getByRole('button', { name: 'Count 0 of Test Lamp Active' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review variance (1 counted)' }));
    await waitFor(() => expect(createStocktake).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('count-not-done')).toBeNull();
  });

  it('says so when no item matches', async () => {
    listStock.mockResolvedValue(active);
    open();
    await screen.findByText(EMPTY);
    fireEvent.change(box(), { target: { value: 'fridge' } });
    await waitFor(() => expect(screen.getAllByText('No item matches "fridge".').length).toBeGreaterThan(0));
    expect(screen.queryAllByRole('option')).toHaveLength(0);
  });
});
