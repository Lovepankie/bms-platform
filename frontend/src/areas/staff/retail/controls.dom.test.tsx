// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Sale } from '../../../api/retail';
import type { ControlsGroups, ControlsPatterns, ControlsRecords, ControlsSummary, Metric } from '../../../api/retail-controls';
import { mockMe } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { StaffContext } from '../context';

// The Controls page (issue #357, group 1): the cards with the trend, the tables that open from the shop to the person to the
// records, the patterns in words, the CSV of what is on screen, and the page shut to people without the permission. Fabricated
// names and amounts.

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const summary = vi.fn<(range: { from: string; to: string; withinMinutes?: number }) => Promise<ControlsSummary>>();
const groups = vi.fn<(metric: Metric, chain: string[], level: string, range: Record<string, unknown>, offset: number) => Promise<ControlsGroups>>();
const records = vi.fn<(metric: Metric, range: Record<string, unknown>, offset: number) => Promise<ControlsRecords>>();
const patterns = vi.fn<() => Promise<ControlsPatterns>>();
const getSale = vi.fn();
vi.mock('../../../api/retail-controls', async (original) => {
  const real = await original<typeof import('../../../api/retail-controls')>();
  return {
    ...real,
    retailControls: {
      summary: (r: { from: string; to: string; withinMinutes?: number }) => summary(r),
      groups: (m: Metric, c: string[], l: string, r: Record<string, unknown>, o: number) => groups(m, c, l, r, o),
      records: (m: Metric, r: Record<string, unknown>, o: number) => records(m, r, o),
      patterns: () => patterns(),
    },
  };
});
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { getSale: (id: string) => getSale(id), listSaleReturns: async () => [], listProducts: async () => [] } };
});

const { ControlsPage } = await import('./controls');

const me = mockMe('admin');
const GAYAZA = 'Test Branch Gayaza';

const card = (metric: string, count: number, over: Record<string, unknown> = {}) => ({
  metric, tracked: true, count, sales_affected: count, value_minor: count * 300, previous_count: 2, previous_value_minor: 600, rate_per_100: '12.5', previous_rate_per_100: '4.0', share_of_sales_bp: 125, ...over,
});

const SUMMARY: ControlsSummary = {
  currency: 'UGX',
  period: { from: '2026-10-19', to: '2026-10-21', sales_count: 48, sales_minor: 14400 },
  previous: { from: '2026-10-16', to: '2026-10-18', sales_count: 40, sales_minor: 12000 },
  within_minutes: 10,
  cost_visible: false,
  cards: [card('voided_sales', 6), card('quick_cancels', 6), card('returns', 0, { tracked: false, previous_count: 0, previous_value_minor: 0 }), card('price_overrides', 3, { sales_affected: 2, value_minor: 1600 }), card('discounts', 1)],
};

const group = (key: string, label: string, over: Record<string, unknown> = {}) => ({ key, label, count: 5, sales_affected: 5, value_minor: 1500, sales_count: 18, rate_per_100: '27.8', ...over });
const LEVELS: Record<string, ControlsGroups['items']> = {
  branch: [group('b1', GAYAZA, { count: 6, rate_per_100: '14.0' })],
  user: [group('u1', 'Test Seller One', { user_status: 'active' }), group('u2', 'Test Seller Two', { user_status: 'deactivated', count: 1, sales_affected: 1, value_minor: 300, rate_per_100: '3.3' })],
  approver: [group('u9', 'Test Admin One', { user_status: 'active', count: 6, sales_affected: 6, sales_count: 0, rate_per_100: undefined })],
  day: [group('2026-10-19', '2026-10-19', { day: '2026-10-19' })],
  reason: [group('test wrong item', 'Test wrong item')],
};
const answer = async (metric: Metric, chain: string[], level: string): Promise<ControlsGroups> => {
  const next = chain[chain.indexOf(level) + 1];
  return { metric, group_by: chain.join(','), level, levels: chain, ...(next ? { next_level: next } : {}), currency: 'UGX', has_more: false, items: LEVELS[level] ?? [] };
};

const RECORD = { sale_id: 'sale-1', sale_no: 'S-0001', at: '2026-10-19T09:15:00Z', branch_id: 'b1', branch_name: GAYAZA, user_id: 'u1', user_name: 'Test Seller One', user_status: 'active', approver_id: 'u9', approver_name: 'Test Admin One', approver_status: 'active', reason: 'Test wrong item', value_minor: 300 };

const sale = (): Sale => ({
  id: 'sale-1', sale_no: 'S-0001', branch_id: me.branches?.[0]?.id, sale_date: '2026-10-19', payment_method: 'cash', status: 'voided', void_reason: 'Test wrong item',
  total_minor: 300, paid_minor: 300, balance_minor: 0, historical: false,
});

function show(who: Me = me, branch: string = ALL_BRANCHES) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: who, branch }}><ControlsPage /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  summary.mockImplementation(async () => SUMMARY);
  groups.mockImplementation(answer);
  records.mockImplementation(async () => ({ metric: 'voided_sales', currency: 'UGX', items: [RECORD], has_more: false }));
  patterns.mockImplementation(async () => ({ from: '2026-10-19', to: '2026-10-21', min_events: 3, min_out_of: 10, items: [] }));
  getSale.mockImplementation(async () => sale());
});
afterEach(() => {
  cleanup();
  for (const m of [summary, groups, records, patterns, getSale]) m.mockReset();
  vi.unstubAllGlobals();
});

const head = (name: RegExp | string) => screen.findByRole('button', { name });

describe('the cards', () => {
  it('show the count, what it is worth, the rate and the trend against the days before', async () => {
    show();
    const voided = (await screen.findByText('Sales cancelled', { selector: '.an-stat-label' })).closest('button') as HTMLElement;
    expect(voided.textContent).toContain('6');
    expect(voided.textContent).toContain('Worth UGX 1,800');
    expect(voided.textContent).toContain('12.5 per 100 sales');
    expect(voided.textContent).toContain('Up 4, from 2 in the 3 days before');
    expect(voided.textContent).toContain('1.25% of the value of sales');
    expect(await screen.findByText(/48 sales entered, UGX 14,400 sold/)).toBeTruthy();
  });

  it('say so when a card depends on a capability that is off, and leave out the card of cost for someone who may not read profit', async () => {
    show();
    const returns = (await screen.findByText('Goods taken back', { selector: '.an-stat-label' })).closest('button') as HTMLElement;
    expect(returns.textContent).toContain('Not switched on for this business, so nothing is recorded.');
    expect(screen.queryByText('Sold below cost', { selector: '.an-stat-label' })).toBeNull();
    expect(screen.getByText(/Sales below cost are shown only to people who may read profit/)).toBeTruthy();
  });

  it('ask for this week by default and for the days chosen after a tap', async () => {
    show();
    await screen.findByText('Sales cancelled', { selector: '.an-stat-label' });
    const first = summary.mock.calls[0]?.[0];
    expect(first?.withinMinutes).toBe(10);
    expect((first?.from ?? '') <= (first?.to ?? '')).toBe(true);
    // The days are the one PeriodPicker (issue #526): a Segmented, whose options are radios.
    fireEvent.click(screen.getByRole('radio', { name: 'Today' }));
    await waitFor(() => {
      const last = summary.mock.calls.at(-1)?.[0];
      expect(last?.from).toBe(last?.to);
    });
  });
});

describe('the tables', () => {
  it('open from the shop to the person to the records, each level asking with what was opened above it, and name a deactivated person', async () => {
    show();
    fireEvent.click(await head(new RegExp(`^${GAYAZA}`)));
    const two = await head(/^Test Seller Two \(deactivated\)/);
    expect(two.textContent).toContain('1 sale');
    expect(two.textContent).toContain('3.3 per 100 sales');
    fireEvent.click(await head(/^Test Seller One/));
    // The default chain is shop, person, day, reason, approver: the day comes next.
    fireEvent.click(await head(/^Monday 19 October 2026/));
    fireEvent.click(await head(/^Test wrong item/));
    fireEvent.click(await head(/^Test Admin One/));
    const row = (await screen.findByText('S-0001')).closest('li') as HTMLElement;
    expect(row.textContent).toContain('UGX 300');
    expect(row.textContent).toContain('Sale by Test Seller One at Test Branch Gayaza');
    expect(row.textContent).toContain('Cancelled by Test Admin One');
    expect(row.textContent).toContain('Reason: Test wrong item');
    expect(groups).toHaveBeenLastCalledWith('voided_sales', ['branch', 'user', 'day', 'reason', 'approver'], 'approver', expect.objectContaining({ branchId: 'b1', userId: 'u1', from: '2026-10-19', to: '2026-10-19', reason: 'test wrong item' }), 0);
    expect(records).toHaveBeenLastCalledWith('voided_sales', expect.objectContaining({ branchId: 'b1', userId: 'u1', approverId: 'u9', reason: 'test wrong item' }), 0);
    // The way back up is a breadcrumb.
    expect(screen.getByRole('navigation', { name: 'Where you are' }).textContent).toContain(GAYAZA);
  });

  it('follow the card that was tapped', async () => {
    show();
    await screen.findByText('Sales cancelled', { selector: '.an-stat-label' });
    fireEvent.click((screen.getByText('Prices changed', { selector: '.an-stat-label' })).closest('button') as HTMLElement);
    await waitFor(() => expect(groups).toHaveBeenLastCalledWith('price_overrides', expect.anything(), 'branch', expect.anything(), 0));
    expect(screen.getByRole('heading', { name: 'Prices changed' })).toBeTruthy();
  });

  it('leave a group to its records on request even when the chain goes deeper', async () => {
    show();
    fireEvent.click(await head(new RegExp(`^${GAYAZA}`)));
    fireEvent.click(await screen.findByRole('button', { name: 'Show the records here' }));
    await screen.findByText('S-0001');
    expect(records).toHaveBeenLastCalledWith('voided_sales', expect.objectContaining({ branchId: 'b1' }), 0);
  });

  it('keep the order the person chose, per user, and start again from the top when it changes', async () => {
    show();
    fireEvent.click(await head(new RegExp(`^${GAYAZA}`)));
    fireEvent.click(screen.getByRole('button', { name: 'Move Reason up' }));
    fireEvent.click(screen.getByRole('button', { name: 'Move Reason up' }));
    fireEvent.click(screen.getByRole('button', { name: 'Move Reason up' }));
    await waitFor(() => expect(groups).toHaveBeenLastCalledWith('voided_sales', ['reason', 'branch', 'user', 'day', 'approver'], 'reason', expect.anything(), 0));
    expect(JSON.parse(localStorage.getItem(`bms.controlsChain.${me.user_id}`) ?? '[]')).toEqual(['reason', 'branch', 'user', 'day', 'approver']);
  });

  it('say so plainly when there is nothing', async () => {
    groups.mockImplementation(async (metric, chain, level) => ({ ...(await answer(metric, chain, level)), items: [] }));
    show();
    expect(await screen.findByText('No sales cancelled in these days.')).toBeTruthy();
  });
});

describe('what the cards and rows do not hide', () => {
  it('says lines are lines, in how many sales, and what the lines are worth', async () => {
    show();
    const changed = (await screen.findByText('Prices changed', { selector: '.an-stat-label' })).closest('button') as HTMLElement;
    expect(changed.textContent).toContain('3 lines in 2 sales, lines worth UGX 1,600');
  });

  it('does not ask for the Approver level for the lines of a sale, which have no approver', async () => {
    show();
    await screen.findByText('Sales cancelled', { selector: '.an-stat-label' });
    fireEvent.click((screen.getByText('Prices changed', { selector: '.an-stat-label' })).closest('button') as HTMLElement);
    await waitFor(() => expect(groups).toHaveBeenLastCalledWith('price_overrides', ['branch', 'user', 'day', 'reason'], 'branch', expect.anything(), 0));
  });

  it('shows a person the server could not name but does not open it, so it never lists everybody', async () => {
    groups.mockImplementation(async (metric, chain, level) => ({
      ...(await answer(metric, chain, level)),
      items: level === 'user' ? [group('', 'Unknown person', { count: 1, sales_affected: 1 })] : (LEVELS[level] ?? []),
    }));
    show();
    fireEvent.click(await head(new RegExp(`^${GAYAZA}`)));
    expect(await screen.findByText('Unknown person')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Unknown person/ })).toBeNull();
    expect(screen.getByText(/The records are not listed because this one is not known/)).toBeTruthy();
  });

  it('offers Try again when the figures or the patterns could not be read', async () => {
    summary.mockImplementationOnce(async () => { throw new Error('Test failure of the figures'); });
    patterns.mockImplementationOnce(async () => { throw new Error('Test failure of the patterns'); });
    show();
    expect(await screen.findByText('Test failure of the figures')).toBeTruthy();
    expect(await screen.findByText('Test failure of the patterns')).toBeTruthy();
    const retries = await screen.findAllByRole('button', { name: 'Try again' });
    expect(retries.length).toBeGreaterThanOrEqual(2);
    for (const r of retries) fireEvent.click(r);
    expect(await screen.findByText('Sales cancelled', { selector: '.an-stat-label' })).toBeTruthy();
    expect(await screen.findByText('Nothing stands out in these days.')).toBeTruthy();
  });
});

describe('the sale behind a record', () => {
  it('opens in place and comes back to the same page', async () => {
    show();
    fireEvent.click(await head(new RegExp(`^${GAYAZA}`)));
    fireEvent.click(await screen.findByRole('button', { name: 'Show the records here' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Open sale S-0001' }));
    await waitFor(() => expect(getSale).toHaveBeenCalledWith('sale-1'));
    fireEvent.click(await screen.findByRole('button', { name: 'Back to the list' }));
    expect(await screen.findByText('Sales cancelled', { selector: '.an-stat-label' })).toBeTruthy();
  });

  it('is not offered to someone who may not read sales', async () => {
    const auditor = { ...me, permissions: ['retail.controls.read'], permission_scopes: { 'retail.controls.read': { all_branches: true, branch_ids: [] } } } as Me;
    show(auditor);
    fireEvent.click(await head(new RegExp(`^${GAYAZA}`)));
    fireEvent.click(await screen.findByRole('button', { name: 'Show the records here' }));
    await screen.findByText('S-0001');
    expect(screen.queryByRole('button', { name: 'Open sale S-0001' })).toBeNull();
  });
});

describe('the repeat patterns', () => {
  it('are said in plain words, and a quiet shop says nothing stands out', async () => {
    show();
    expect(await screen.findByText('Nothing stands out in these days.')).toBeTruthy();
    cleanup();
    patterns.mockImplementation(async () => ({
      from: '2026-10-19', to: '2026-10-21', min_events: 3, min_out_of: 10,
      items: [{ subject: 'user', key: 'u1', name: 'Test Seller One', user_status: 'active', branch_id: 'b1', branch_name: GAYAZA, metric: 'voided_sales', count: 5, out_of: 13, rate_per_100: '38.5', shop_rate_per_100: '14.0', times: '2.8' }],
    }));
    show();
    expect(await screen.findByText(`5 of the 13 sales Test Seller One entered at ${GAYAZA} were cancelled (38.5 per 100). The shop's own is 14.0, so 2.8 times.`)).toBeTruthy();
  });
});

describe('the CSV of what is on screen', () => {
  it('saves the groups as they are shown, the deactivated person named', async () => {
    const blobs: Blob[] = [];
    vi.stubGlobal('URL', { ...URL, createObjectURL: (b: Blob) => { blobs.push(b); return 'blob:test'; }, revokeObjectURL: () => undefined });
    show();
    fireEvent.click(await head(new RegExp(`^${GAYAZA}`)));
    await head(/^Test Seller Two/);
    const buttons = await screen.findAllByRole('button', { name: 'Export CSV' });
    fireEvent.click(buttons[0] as HTMLElement);
    expect(blobs).toHaveLength(1);
    const text = await (blobs[0] as Blob).text();
    expect(text).toContain('Person,Count,Sales with it,Value,Sales entered,Per 100 sales');
    expect(text).toContain('Test Seller Two (deactivated),1,1,300,18,3.3');
  });
});

describe('who may open the page', () => {
  it('shuts it to a sales person', () => {
    show(mockMe('sales'));
    expect(screen.getByText(/^You do not have access to this page\. The owner or an administrator/)).toBeTruthy();
    expect(summary).not.toHaveBeenCalled();
  });
});
