// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, configure, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SalesSummary, SalesSummaryGroup, SalesSummaryQuery } from '../../../api/retail';

// The grouped sales lists as a person uses them (issue #328): the current month is open by day, every header shows its
// own figures, a day loads its sales only when opened, profit shows only when the server sent it, and a failed or empty
// level says so plainly. Fabricated dates and amounts.

// Three levels load one after the other; a busy machine needs more than the default second.
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 15_000 });

const summary = vi.fn<(q: SalesSummaryQuery) => Promise<SalesSummary>>();
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { salesSummary: (q: SalesSummaryQuery) => summary(q) } };
});

const { SalesGrouped, useOpenGroups } = await import('./sales-grouped');

const none = { count: 0, total_minor: 0 };
const group = (period: string, from: string, to: string, over: Partial<SalesSummaryGroup> = {}): SalesSummaryGroup => ({
  period, from, to, sales_count: 3, total_minor: 9000, profit_minor: 2500, voided_count: 0,
  by_method: { cash: { count: 2, total_minor: 6000 }, mobile_money: { count: 1, total_minor: 3000 }, bank: none, credit: none }, ...over,
});
const LEVELS: Record<string, SalesSummaryGroup[]> = {
  year: [group('2026', '2026-01-01', '2026-12-31', { sales_count: 5, total_minor: 20000, profit_minor: 6000 })],
  month: [group('2026-10', '2026-10-01', '2026-10-31', { sales_count: 4, total_minor: 15000, profit_minor: 4500 }), group('2026-09', '2026-09-01', '2026-09-30', { sales_count: 1, total_minor: 5000, profit_minor: 1500 })],
  day: [group('2026-10-19', '2026-10-19', '2026-10-19', { voided_count: 1 }), group('2026-10-18', '2026-10-18', '2026-10-18')],
};
const answer = (level: string, withProfit = true): SalesSummary => ({
  group_by: level, currency: 'UGX', has_more: false,
  items: (LEVELS[level] ?? []).map((g) => (withProfit ? g : { ...g, profit_minor: undefined })),
});

const renderDay = vi.fn((day: string) => <p>Sales of {day}</p>);
function Screen({ credit = false, empty }: { credit?: boolean; empty?: boolean }) {
  const groups = useOpenGroups('2026-10-19');
  return (
    <SalesGrouped rootLabel="All sales" filter={{ buyer: 'ka' }} range={{ from: '2026-01-01', to: '2026-10-19' }} credit={credit} enabled groups={groups} renderDay={renderDay}
      empty={empty ? <p>Nothing matches ka</p> : undefined} />
  );
}
const show = (props: Parameters<typeof Screen>[0] = {}) =>
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Screen {...props} /></QueryClientProvider>);

beforeEach(() => {
  summary.mockImplementation(async (q) => answer(q.level));
  renderDay.mockClear();
});
afterEach(() => {
  cleanup();
  summary.mockReset();
});

describe('the grouped list', () => {
  it('opens this month by day, each header with its sales, total, profit and payment methods', async () => {
    show();
    const month = await screen.findByRole('button', { name: /^October 2026 UGX 15,000 4 sales/ });
    expect(month.getAttribute('aria-expanded')).toBe('true');
    expect(month.textContent).toContain('4 sales, profit UGX 4,500');
    const year = screen.getByRole('button', { name: /^2026 UGX 20,000 5 sales/ });
    expect(year.getAttribute('aria-expanded')).toBe('true');
    expect(year.textContent).toContain('profit UGX 6,000');
    const day = await screen.findByRole('button', { name: /^Monday 19 October 2026 UGX 9,000 3 sales/ });
    expect(day.getAttribute('aria-expanded')).toBe('false');
    expect(day.textContent).toContain('Cash 2, UGX 6,000. Mobile money 1, UGX 3,000');
    expect(day.textContent).toContain('1 voided (not counted)');
    expect(screen.getByRole('button', { name: /^September 2026/ }).getAttribute('aria-expanded')).toBe('false');
  });

  it('names a header by everything it shows, and points an open one at its contents', async () => {
    show();
    const day = await screen.findByRole('button', { name: /^Monday 19 October/ });
    expect(day.getAttribute('aria-label')).toBeNull();
    expect(day.getAttribute('aria-controls')).toBeNull();
    expect(day.getAttribute('name')).toBeNull();
    expect(screen.getByRole('button', { name: /^Monday 19 October 2026 UGX 9,000 3 sales, profit UGX 2,500, 1 voided \(not counted\) Cash 2, UGX 6,000/ })).toBeTruthy();
    const month = screen.getByRole('button', { name: /^October 2026/ });
    const controlled = month.getAttribute('aria-controls') ?? '';
    expect(controlled).not.toBe('');
    expect(document.getElementById(controlled)?.textContent).toContain('Monday 19 October 2026');
  });

  it('asks each level for its own days, inside the range the screen shows, with the list\'s filters', async () => {
    show();
    await screen.findByRole('button', { name: /^Monday 19 October/ });
    const asked = summary.mock.calls.map(([q]) => [q.level, q.from, q.to, q.buyer]);
    expect(asked).toContainEqual(['year', '2026-01-01', '2026-10-19', 'ka']);
    expect(asked).toContainEqual(['month', '2026-01-01', '2026-10-19', 'ka']);
    expect(asked).toContainEqual(['day', '2026-10-01', '2026-10-19', 'ka']);
  });

  it('loads a day\'s sales only when the day is opened, and keeps the figures above it', async () => {
    show();
    const day = await screen.findByRole('button', { name: /^Monday 19 October/ });
    expect(renderDay).not.toHaveBeenCalled();
    fireEvent.click(day);
    expect(screen.getByText('Sales of 2026-10-19')).toBeTruthy();
    expect(renderDay).toHaveBeenCalledTimes(1);
    expect(renderDay).toHaveBeenCalledWith('2026-10-19');
    expect(screen.getByRole('button', { name: /^October 2026 UGX 15,000 4 sales/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /^2026 UGX 20,000 5 sales/ })).toBeTruthy();
  });

  it('shows where an open day sits and goes back up from the trail', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: /^Monday 19 October/ }));
    const trail = screen.getByRole('navigation', { name: 'Where you are' });
    expect(within(trail).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['All sales', '2026', 'October 2026', 'Monday 19 October 2026']);
    fireEvent.click(within(trail).getByRole('button', { name: 'October 2026' }));
    expect(screen.queryByText('Sales of 2026-10-19')).toBeNull();
    expect(screen.getByRole('button', { name: /^October 2026/ }).getAttribute('aria-expanded')).toBe('true');
  });

  it('shuts a month with a tap and lists its days again when it is reopened', async () => {
    show();
    const month = await screen.findByRole('button', { name: /^October 2026/ });
    fireEvent.click(month);
    expect(screen.queryByRole('button', { name: /^Monday 19 October/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /^October 2026/ }));
    expect(await screen.findByRole('button', { name: /^Monday 19 October/ })).toBeTruthy();
  });

  it('leaves out profit when the server sent none', async () => {
    summary.mockImplementation(async (q) => answer(q.level, false));
    show();
    await screen.findByRole('button', { name: /^Monday 19 October/ });
    expect(document.body.textContent).not.toMatch(/profit/i);
  });

  it('shows no payment method breakdown or voided count on the credit sales list', async () => {
    show({ credit: true });
    await screen.findByRole('button', { name: /^Monday 19 October/ });
    expect(document.body.textContent).not.toMatch(/Cash 2|voided/);
  });

  it('says plainly when a level fails, and tries again', async () => {
    summary.mockRejectedValueOnce(new Error('The server is busy.'));
    show();
    expect((await screen.findByRole('alert')).textContent).toContain('The server is busy.');
    summary.mockImplementation(async (q) => answer(q.level));
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('button', { name: /^October 2026/ })).toBeTruthy();
  });

  it('says plainly when there is nothing, and uses the screen\'s own words for an empty search', async () => {
    summary.mockImplementation(async (q) => ({ ...answer(q.level), items: [] }));
    show();
    expect(await screen.findByText('No sales found.')).toBeTruthy();
    cleanup();
    show({ empty: true });
    expect(await screen.findByText('Nothing matches ka')).toBeTruthy();
  });

  it('offers older periods when the server says there are more', async () => {
    summary.mockImplementation(async (q) => (q.level === 'year' && !q.to?.startsWith('2025')
      ? { ...answer('year'), has_more: true, next_to: '2025-12-31' }
      : q.level === 'year' ? { group_by: 'year', has_more: false, items: [group('2025', '2025-01-01', '2025-12-31', { sales_count: 2, total_minor: 4000 })] } : answer(q.level)));
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Show earlier years' }));
    expect(await screen.findByRole('button', { name: /^2025 UGX 4,000 2 sales/ })).toBeTruthy();
    expect(summary.mock.calls.some(([q]) => q.level === 'year' && q.to === '2025-12-31')).toBe(true);
  });
});
