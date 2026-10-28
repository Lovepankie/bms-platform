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
const commitStocktake = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listStock: (...a: unknown[]) => listStock(...a), createStocktake: (...a: unknown[]) => createStocktake(...a), commitStocktake: (...a: unknown[]) => commitStocktake(...a) } };
});

const { StocktakePage, StocktakeReview } = await import('./stocktake');

afterEach(() => {
  cleanup();
  listStock.mockReset();
  createStocktake.mockReset();
  commitStocktake.mockReset();
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
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (1 counted)' }));
    expect(screen.getByTestId('count-not-done').textContent).toContain('1 item not counted. Review the difference anyway?');
    expect(createStocktake).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference anyway' }));
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

  // Review of #535: the question answers the counts it was asked about, and Review the difference anyway keeps the Review button's guards.
  it('withdraws the question when a count changes, so Review the difference anyway never sends an invalid or empty count', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText(EMPTY);
    const held = document.getElementById('count-I-1') as HTMLInputElement;
    fireEvent.change(held, { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (1 counted)' }));
    expect(screen.getByTestId('count-not-done')).toBeTruthy();
    fireEvent.change(document.getElementById('count-I-1') as HTMLInputElement, { target: { value: '4x' } });
    expect(screen.queryByTestId('count-not-done')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Review the difference anyway' })).toBeNull();
    expect(createStocktake).not.toHaveBeenCalled();
  });

  it('keeps Review the difference anyway to one request however often it is tapped', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    createStocktake.mockImplementation(() => new Promise(() => {}));
    open();
    await screen.findByText(EMPTY);
    fireEvent.click(screen.getByRole('button', { name: 'Count 0 of Test Lamp Inactive Held' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (1 counted)' }));
    const go = screen.getByRole('button', { name: 'Review the difference anyway' });
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
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (1 counted)' }));
    fireEvent.click(screen.getByRole('button', { name: 'Keep counting' }));
    expect(screen.queryByTestId('count-not-done')).toBeNull();
    expect(createStocktake).not.toHaveBeenCalled();
  });

  // Issue #629: the question names the button it leads to, and nothing on the count page says Finish.
  it('asks in the words of Review the difference, and never says Finish', async () => {
    listStock.mockResolvedValue([...active, inactiveHeld]);
    open();
    await screen.findByText(EMPTY);
    fireEvent.click(screen.getByRole('button', { name: 'Count 0 of Test Lamp Inactive Held' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (1 counted)' }));
    const question = screen.getByTestId('count-not-done');
    expect(question.querySelector('p')?.textContent).toBe('1 item not counted. Review the difference anyway?');
    expect(screen.getByRole('button', { name: 'Review the difference anyway' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Keep counting' })).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/finish/i);
    expect([...document.querySelectorAll('[aria-label]')].map((e) => e.getAttribute('aria-label')).join(' ')).not.toMatch(/finish/i);
  });

  it('finishes at once when every line is counted', async () => {
    listStock.mockResolvedValue(active);
    createStocktake.mockResolvedValue({ id: 'st1', status: 'draft', lines: [] });
    open();
    await screen.findByText(EMPTY);
    fireEvent.click(screen.getByRole('button', { name: 'Count 0 of Test Lamp Active' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (1 counted)' }));
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

// Issue #629: a shop owner was one tap from committing a count that took an item from 8 to 0 by a 0 meant for another line.
describe('a count that wipes or halves stock', () => {
  const sheet = [row('A-1', 'Test Plug Four Way', '8.000'), row('A-2', 'Test Plug Two Way', '10.000'), row('A-3', 'Test Bulb', '10.000'), row('A-4', 'Test Tape', '5.000')];
  const line = (id: string, description: string, expected: string, counted: string) => ({
    product_id: id, description, expected_qty: expected, counted_qty: counted, variance_qty: (Number(counted) - Number(expected)).toFixed(3),
  });
  const draft = {
    id: 'st1', status: 'draft',
    lines: [
      line('A-1', 'Test Plug Four Way', '8.000', '0.000'),
      line('A-2', 'Test Plug Two Way', '10.000', '4.000'),
      line('A-3', 'Test Bulb', '10.000', '6.000'),
      line('A-4', 'Test Tape', '5.000', '7.000'),
    ],
  };

  async function review() {
    listStock.mockResolvedValue(sheet);
    createStocktake.mockResolvedValue(draft);
    commitStocktake.mockResolvedValue({ ...draft, status: 'committed' });
    open();
    await screen.findByText(EMPTY);
    for (const [id, qty] of [['A-1', '0'], ['A-2', '4'], ['A-3', '6'], ['A-4', '7']] as const) {
      fireEvent.change(document.getElementById(`count-${id}`) as HTMLInputElement, { target: { value: qty } });
    }
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (4 counted)' }));
    await screen.findByRole('heading', { name: 'Review the difference' });
  }

  it('notes in words, with a tick, each line that takes stock to 0 or below half, and no other line', async () => {
    await review();
    expect(screen.getByTestId('count-drop-A-1').textContent).toContain('This removes 8 pieces. Is that right?');
    expect(screen.getByTestId('count-drop-A-2').textContent).toContain('This removes 6 pieces. Is that right?');
    // A count typed above zero that keeps half or more, and a count that adds, are left as they are.
    expect(screen.queryByTestId('count-drop-A-3')).toBeNull();
    expect(screen.queryByTestId('count-drop-A-4')).toBeNull();
    // Not by colour alone: the words, an icon and a tick named for its item.
    const tick = screen.getByRole('checkbox', { name: 'Yes, Test Plug Four Way is right' }) as HTMLInputElement;
    expect(tick.checked).toBe(false);
    expect(screen.getByTestId('count-drop-A-1').querySelector('svg')).not.toBeNull();
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
  });

  it('commits only once every noted line is ticked, and a press before that goes to the first tick', async () => {
    await review();
    const commit = screen.getByRole('button', { name: 'Commit the count' });
    expect(screen.getByText('Tick the 2 lines marked above before you commit the count.')).toBeTruthy();
    // Issue #636: the hint is the button's description, so a screen reader hears why a press goes to a tick.
    expect(document.getElementById(commit.getAttribute('aria-describedby') ?? '')?.textContent).toBe('Tick the 2 lines marked above before you commit the count.');
    fireEvent.click(commit);
    expect(commitStocktake).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(screen.getByRole('checkbox', { name: 'Yes, Test Plug Four Way is right' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Yes, Test Plug Four Way is right' }));
    expect(screen.getByText('Tick the line marked above before you commit the count.')).toBeTruthy();
    fireEvent.click(commit);
    expect(commitStocktake).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(screen.getByRole('checkbox', { name: 'Yes, Test Plug Two Way is right' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Yes, Test Plug Two Way is right' }));
    expect(commit.getAttribute('aria-describedby')).toBeNull();
    fireEvent.click(commit);
    // Issue #636: the commit names the ticked lines, so the server can refuse a big drop nobody ticked.
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('st1', ['A-1', 'A-2']));
  });

  // Issue #636: no screen of the count says Finish, the review and the committed screen included.
  it('never says Finish on Review the difference or once committed', async () => {
    const noFinish = () => {
      expect(document.body.textContent).not.toMatch(/finish/i);
      expect([...document.querySelectorAll('[aria-label]')].map((e) => e.getAttribute('aria-label')).join(' ')).not.toMatch(/finish/i);
    };
    await review();
    noFinish();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Yes, Test Plug Four Way is right' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Yes, Test Plug Two Way is right' }));
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    await screen.findByRole('heading', { name: 'Stock count committed' });
    noFinish();
  });

  // Issue #636: lines without a product id never share a tick, and the warning shows the warning icon, not the error one.
  it('gives each line without a product id a tick of its own, under the warning icon', () => {
    const onCommit = vi.fn();
    const noId = (description: string) => ({ ...line('', description, '8.000', '0.000'), product_id: undefined });
    render(<StocktakeReview stocktake={{ id: 'st9', status: 'draft', lines: [noId('Test Fuse One'), noId('Test Fuse Two')] }} onCommit={onCommit} />);
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
    expect(document.querySelector('.entry-warning-icon path')?.getAttribute('d')).toBe('M12 3.5 2.5 20h19z');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Yes, Test Fuse One is right' }));
    expect((screen.getByRole('checkbox', { name: 'Yes, Test Fuse Two is right' }) as HTMLInputElement).checked).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    expect(onCommit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Yes, Test Fuse Two is right' }));
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    expect(onCommit).toHaveBeenCalledWith([]);
  });

  it('asks again after Back to the count, since the review is a new one', async () => {
    await review();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Yes, Test Plug Four Way is right' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back to the count' }));
    createStocktake.mockResolvedValue({ ...draft, id: 'st2' });
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (4 counted)' }));
    await screen.findByRole('heading', { name: 'Review the difference' });
    expect((screen.getByRole('checkbox', { name: 'Yes, Test Plug Four Way is right' }) as HTMLInputElement).checked).toBe(false);
  });
});

// Issue #629: a count changes only the items counted. The page says so, and an empty box is never sent.
describe('an item left empty', () => {
  it('says to leave an item empty if it was not counted, and never sends an empty or blank box', async () => {
    const sheet = [row('A-1', 'Test Plug Four Way', '8.000'), row('A-2', 'Test Plug Two Way', '10.000'), row('A-3', 'Test Bulb', '10.000')];
    listStock.mockResolvedValue(sheet);
    createStocktake.mockResolvedValue({ id: 'st1', status: 'draft', lines: [] });
    open();
    await screen.findByText(EMPTY);
    expect(screen.getByRole('note').textContent).toContain('Leave an item empty if you did not count it.');
    fireEvent.change(document.getElementById('count-A-1') as HTMLInputElement, { target: { value: '7' } });
    fireEvent.change(document.getElementById('count-A-2') as HTMLInputElement, { target: { value: '3' } });
    fireEvent.change(document.getElementById('count-A-2') as HTMLInputElement, { target: { value: '' } });
    fireEvent.change(document.getElementById('count-A-3') as HTMLInputElement, { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference (1 counted)' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review the difference anyway' }));
    await waitFor(() => expect(createStocktake).toHaveBeenCalledTimes(1));
    expect(createStocktake.mock.calls[0]?.[0]).toEqual({ branch_id: expect.any(String), lines: [{ product_id: 'A-1', counted_qty: '7' }] });
  });
});
