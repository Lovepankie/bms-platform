// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { StockMovement } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Undo a wrong usage or damage entry (issue #818, FR-RET-88): the button shows only to whoever may delete a sale at the
// entry's shop, asks for a reason before anything is sent, says it worked in the app's toast, and an undone entry shows
// Undone in words with an icon and who, when and why, with no button. Fabricated items, names and reasons.

vi.setConfig({ testTimeout: 20000 });

const usageHistory = vi.fn();
const undoUsage = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      usageHistory: (...a: unknown[]) => usageHistory(...a),
      undoUsage: (...a: unknown[]) => undoUsage(...a),
      listProducts: () => Promise.resolve([]),
    },
  };
});

const { UsageHistoryPage, firstLines } = await import('./usage-history');
const { undoOffered, undoneWords } = await import('./usage-undo');
const { DoneToast, doneToasts } = await import('./toast');
const { RetailError } = await import('../../../api/retail');
const { movementWords } = await import('./item-page');

afterEach(() => {
  cleanup();
  usageHistory.mockReset();
  undoUsage.mockReset();
});

const admin = mockMe('admin');
const seller = mockMe('sales');
const branch = admin.branches?.[0]?.id ?? '';

const damaged: StockMovement = { id: 'm1', at: '2026-10-21T08:00:00Z', branch_id: branch, product_id: 'p1', code: 'PA-05', description: 'Test paint', unit: 'litre', kind: 'damage', qty: '-0.500', note: 'Tin fell', source_type: 'retail.usage', source_id: 'r1' };
const undone: StockMovement = { ...damaged, id: 'm2', source_id: 'r2', undone: { at: '2026-10-22T08:00:00Z', by: 'u1', by_name: 'Test Admin 01', reason: 'Counted twice' } };

function wrap(ui: ReactElement, me: Me = admin) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch } as never}>
        {ui}
        <DoneToast />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('Undo a usage or damage entry', () => {
  it('is offered only on a live entry made in BMS, to whoever may delete a sale at its shop', () => {
    expect(undoOffered(admin, damaged)).toBe(true);
    expect(undoOffered(seller, damaged)).toBe(false);
    expect(undoOffered(admin, undone)).toBe(false);
    expect(undoOffered(admin, { ...damaged, historical: true })).toBe(false);
    expect(undoOffered(admin, { ...damaged, source_type: 'retail.stocktake' })).toBe(false);
  });

  it('asks for a reason, sends it, and says it worked in the toast', async () => {
    usageHistory.mockResolvedValue({ items: [{ ...damaged, entry_lines: 1 }] });
    undoUsage.mockResolvedValue({ report_id: 'r1', reason: 'Wrong item', items_back: 1 });
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    fireEvent.click(within(row).getByRole('button', { name: 'Undo this entry' }));
    const form = within(row).getByRole('form', { name: 'Undo this entry' });
    // No reason, nothing sent: the press goes to the reason instead.
    fireEvent.click(within(form).getByRole('button', { name: 'Yes, undo it' }));
    await new Promise((r) => setTimeout(r, 50));
    expect(undoUsage).not.toHaveBeenCalled();
    expect(within(form).getByTestId('undo-what').textContent).toBe('This item goes back into stock.');
    fireEvent.change(within(form).getByLabelText('Why is this entry wrong?'), { target: { value: '  Wrong item ' } });
    fireEvent.click(within(row).getByRole('button', { name: 'Yes, undo it' }));
    await waitFor(() => expect(undoUsage).toHaveBeenCalledWith('r1', 'Wrong item'));
    expect(await screen.findByText('Entry undone, 1 item put back')).toBeTruthy();
    await waitFor(() => expect(within(screen.getByTestId('usage-row')).queryByRole('button', { name: 'Undo this entry' })).toBeNull());
  });

  it('offers the undo once per entry, on its first line, and names how many items go back', async () => {
    const first: StockMovement = { ...damaged, entry_lines: 3 };
    const second: StockMovement = { ...first, id: 'm3', product_id: 'p2', description: 'Test brush' };
    const third: StockMovement = { ...first, id: 'm4', product_id: 'p3', description: 'Test roller' };
    const other: StockMovement = { ...damaged, id: 'm5', source_id: 'r9', description: 'Test tape', entry_lines: 1 };
    usageHistory.mockResolvedValue({ items: [first, second, third, other] });
    undoUsage.mockResolvedValue({ report_id: 'r1', reason: 'Wrong entry', items_back: 3 });
    wrap(<UsageHistoryPage />);
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(4));
    expect(screen.getAllByRole('button', { name: 'Undo this entry' })).toHaveLength(2);
    const [row, two, three] = screen.getAllByTestId('usage-row') as HTMLElement[];
    expect(within(two!).queryByRole('button', { name: 'Undo this entry' })).toBeNull();
    expect(within(three!).queryByRole('button', { name: 'Undo this entry' })).toBeNull();
    fireEvent.click(within(row!).getByRole('button', { name: 'Undo this entry' }));
    expect(within(row!).getByTestId('undo-what').textContent).toBe('All 3 items of this entry go back into stock.');
    fireEvent.change(within(row!).getByLabelText('Why is this entry wrong?'), { target: { value: 'Wrong entry' } });
    fireEvent.click(within(row!).getByRole('button', { name: 'Yes, undo it' }));
    await waitFor(() => expect(undoUsage).toHaveBeenCalledWith('r1', 'Wrong entry'));
    expect(await screen.findByText('Entry undone, 3 items put back')).toBeTruthy();
  });

  // JUSTIFICATION-A3: the review of 56e3ad9 asks for a filtered list and an entry split across pages, each showing 3.
  it('shows the server count of 3 with the item filter on, one line of the entry in the list', async () => {
    // Past usage and damage narrowed to Test paint: one line of a three line entry shows; the server still says 3.
    usageHistory.mockResolvedValue({ items: [{ ...damaged, entry_lines: 3 }] });
    undoUsage.mockResolvedValue({ report_id: 'r1', reason: 'Wrong entry', items_back: 3 });
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    fireEvent.click(within(row).getByRole('button', { name: 'Undo this entry' }));
    expect(within(row).getByTestId('undo-what').textContent).toBe('All 3 items of this entry go back into stock.');
    fireEvent.change(within(row).getByLabelText('Why is this entry wrong?'), { target: { value: 'Wrong entry' } });
    fireEvent.click(within(row).getByRole('button', { name: 'Yes, undo it' }));
    expect(await screen.findByText('Entry undone, 3 items put back')).toBeTruthy();
  });

  it('shows the server count of 3 when the entry is split across pages, its first line ending page one', async () => {
    const lines = { ...damaged, entry_lines: 3 };
    const filler = Array.from({ length: 2 }, (_, i): StockMovement => ({ ...damaged, id: `f${i}`, source_id: `rf${i}`, entry_lines: 1 }));
    usageHistory.mockResolvedValueOnce({ items: [...filler, lines], next_cursor: 'c2' });
    usageHistory.mockResolvedValueOnce({ items: [{ ...lines, id: 'm3' }, { ...lines, id: 'm4' }] });
    wrap(<UsageHistoryPage />);
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(3));
    const row = screen.getAllByTestId('usage-row')[2] as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: 'Undo this entry' }));
    expect(within(row).getByTestId('undo-what').textContent).toBe('All 3 items of this entry go back into stock.');
    // Page two adds the other two lines; the entry is still offered once, on its first line.
    fireEvent.click(screen.getByRole('button', { name: 'Show older records' }));
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(5));
    expect(screen.getAllByTestId('usage-row').slice(3).every((r) => within(r).queryByRole('button', { name: 'Undo this entry' }) === null)).toBe(true);
    expect([...firstLines([...filler, lines, { ...lines, id: 'm3' }])]).toEqual(['f0', 'f1', 'm1']);
  });

  it('names the stock that came back on the item page as an undo, never as a customer return', () => {
    expect(movementWords({ kind: 'return', source_type: 'retail.usage_undo', reverses_movement_id: 'm1' })).toBe('Usage or damage undone');
  });

  it('keeps the entry when the person changes their mind, and sends nothing', async () => {
    usageHistory.mockResolvedValue({ items: [damaged] });
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    fireEvent.click(within(row).getByRole('button', { name: 'Undo this entry' }));
    fireEvent.click(within(row).getByRole('button', { name: 'Keep the entry' }));
    expect(within(row).getByRole('button', { name: 'Undo this entry' })).toBeTruthy();
    expect(undoUsage).not.toHaveBeenCalled();
  });

  it('takes an entry someone else undid first as done, not as an error', async () => {
    usageHistory.mockResolvedValue({ items: [damaged] });
    undoUsage.mockRejectedValue(new RetailError('This entry has been undone already.', 409, 'usage_undone'));
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    fireEvent.click(within(row).getByRole('button', { name: 'Undo this entry' }));
    fireEvent.change(within(row).getByLabelText('Why is this entry wrong?'), { target: { value: 'Wrong item' } });
    fireEvent.click(within(row).getByRole('button', { name: 'Yes, undo it' }));
    await waitFor(() => expect(within(screen.getByTestId('usage-row')).queryByRole('form')).toBeNull());
    expect(within(screen.getByTestId('usage-row')).queryByRole('button', { name: 'Undo this entry' })).toBeNull();
  });

  it('marks an undone entry Undone in words with an icon, says who, when and why, and offers no undo', async () => {
    usageHistory.mockResolvedValue({ items: [undone] });
    wrap(<UsageHistoryPage />);
    const row = await screen.findByTestId('usage-row');
    const mark = within(row).getByTestId('usage-undone');
    expect(mark.textContent).toBe('Undone');
    expect(mark.className).toContain('badge-warning');
    expect(mark.querySelector('svg')).not.toBeNull();
    expect(within(row).getByTestId('usage-undone-words').textContent).toBe('Undone by Test Admin 01 on 22 Oct 2026 because: Counted twice. The stock went back.');
    expect(within(row).queryByRole('button', { name: 'Undo this entry' })).toBeNull();
    expect(undoneWords(damaged)).toBe('');
  });

  it('shows a seller the Undone mark but never the button', async () => {
    usageHistory.mockResolvedValue({ items: [damaged, undone] });
    wrap(<UsageHistoryPage />, seller);
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(2));
    expect(screen.queryByRole('button', { name: 'Undo this entry' })).toBeNull();
    expect(screen.getAllByTestId('usage-undone')).toHaveLength(1);
  });
});
