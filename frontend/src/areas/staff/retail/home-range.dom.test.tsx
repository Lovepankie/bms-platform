// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Dashboard } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { readRange, writeRange } from './range-state';

// The range of the Retail home (issue #428): the choice is remembered per person on this device and every figure is asked for
// the chosen days; custom days are checked before they are asked for; the chart reads out the step picked; the cash book
// follows the range; a seller's own card follows it through the sales summary. Fabricated names and figures.

const dashboard = vi.fn();
const salesSummary = vi.fn();
const cashDaily = vi.fn();

vi.mock('../../../api/retail-analytics', async (original) => {
  const real = await original<typeof import('../../../api/retail-analytics')>();
  return { ...real, retailAnalytics: { dashboard: (...args: unknown[]) => dashboard(...args) } };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      salesSummary: (...args: unknown[]) => salesSummary(...args),
      cashDaily: (...args: unknown[]) => cashDaily(...args),
    },
  };
});

const { RetailDashboard } = await import('./dashboard');
const { OwnSales } = await import('./own-sales');

afterEach(() => {
  cleanup();
  dashboard.mockReset();
  salesSummary.mockReset();
  cashDaily.mockReset();
  localStorage.clear();
});

function show(ui: ReactElement, me: Me) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: 'all' }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const body = (period: NonNullable<Dashboard['range']>['period']): Dashboard => ({
  date: '2026-10-21', currency: 'UGX', profit_visible: true, shops: [],
  today: { sales_minor: 3600, sale_count: 3, cash_minor: 2100, credit_minor: 1500 },
  last7_sales_minor: 6600, last30_sales_minor: 9000,
  range: {
    period, from: '2026-10-19', to: '2026-10-21', previous_from: '2026-10-16', previous_to: '2026-10-18', group: 'day',
    current: { sales_minor: 6600, sale_count: 4, average_sale_minor: 1650 },
    previous: { sales_minor: 3000, sale_count: 2, average_sale_minor: 1500 },
    series: [
      { start: '2026-10-19', sales_minor: 1000, previous_sales_minor: 500 },
      { start: '2026-10-20', sales_minor: 2000, previous_sales_minor: 1000 },
      { start: '2026-10-21', sales_minor: 3600, previous_sales_minor: 1500 },
    ],
  },
});

describe('the owner dashboard range', () => {
  it('starts on the remembered choice and remembers a new one for this person only', async () => {
    const me = mockMe('admin');
    writeRange(me.user_id ?? '', { period: '30d' });
    dashboard.mockImplementation(async (q: { period: 'today' | '7d' }) => body(q.period));
    cashDaily.mockResolvedValue({ items: [] });
    show(<RetailDashboard />, me);
    await screen.findByText('UGX 6,600');
    expect(dashboard).toHaveBeenLastCalledWith(expect.objectContaining({ period: '30d' }));
    expect(screen.getByRole('button', { name: '30 days' }).getAttribute('aria-pressed')).toBe('true');

    fireEvent.click(screen.getByRole('button', { name: 'This year' }));
    await waitFor(() => expect(dashboard).toHaveBeenLastCalledWith(expect.objectContaining({ period: 'ytd' })));
    expect(readRange(me.user_id ?? '')).toEqual({ period: 'ytd' });
    expect(readRange('00000000-0000-4000-8000-0000000000f2')).toEqual({ period: '7d' });
  });

  it('keeps the figures on screen named for their own days while the next range loads', async () => {
    dashboard.mockImplementation((q: { period: 'today' | '7d' }) => (q.period === '7d' ? Promise.resolve(body('7d')) : new Promise(() => undefined)));
    cashDaily.mockResolvedValue({ items: [] });
    show(<RetailDashboard />, mockMe('admin'));
    await screen.findByText('Sales, Last 7 days');
    fireEvent.click(screen.getByRole('button', { name: 'Today' }));
    await screen.findByText('Loading the days you picked');
    expect(screen.getByText('Sales, Last 7 days')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Today' }).getAttribute('aria-pressed')).toBe('true');
  });

  it('checks custom days before asking for them', async () => {
    const me = mockMe('admin');
    dashboard.mockImplementation(async (q: { period: 'today' | '7d' }) => body(q.period));
    cashDaily.mockResolvedValue({ items: [] });
    show(<RetailDashboard />, me);
    await screen.findByText('UGX 6,600');
    fireEvent.click(screen.getByRole('button', { name: 'Choose days' }));
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-10' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Show these days' }));
    expect((await screen.findByRole('alert')).textContent).toBe('The first day is after the last day.');
    expect(dashboard).not.toHaveBeenCalledWith(expect.objectContaining({ period: 'custom' }));
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-12' } });
    fireEvent.click(screen.getByRole('button', { name: 'Show these days' }));
    await waitFor(() => expect(dashboard).toHaveBeenLastCalledWith(expect.objectContaining({ period: 'custom', from: '2026-10-10', to: '2026-10-12' })));
    expect(readRange(me.user_id ?? '')).toEqual({ period: 'custom', from: '2026-10-10', to: '2026-10-12' });
  });

  it('reads out the step picked on the chart, with the period before', async () => {
    dashboard.mockImplementation(async (q: { period: 'today' | '7d' }) => body(q.period));
    cashDaily.mockResolvedValue({ items: [] });
    show(<RetailDashboard />, mockMe('admin'));
    const chart = await screen.findByRole('slider');
    const readout = () => document.querySelector('.rc-readout')?.textContent ?? '';
    expect(readout()).toContain('Wed 21 Oct');
    expect(readout()).toContain('UGX 3,600');
    expect(readout()).toContain('before: UGX 1,500');
    fireEvent.keyDown(chart, { key: 'ArrowLeft' });
    expect(readout()).toContain('Tue 20 Oct');
    expect(readout()).toContain('UGX 2,000');
    fireEvent.keyDown(chart, { key: 'Home' });
    expect(readout()).toContain('Mon 19 Oct');
    expect(chart.getAttribute('aria-valuetext')).toContain('Mon 19 Oct');
    // The caption is not a live region (it changes on every move of a drag); the spoken one settles after a pause.
    expect(document.querySelector('.rc-readout')?.getAttribute('aria-live')).toBeNull();
  });

  it('adds up the cash book of the range for a person who closes the till', async () => {
    dashboard.mockImplementation(async (q: { period: 'today' | '7d' }) => body(q.period));
    cashDaily.mockResolvedValue({
      items: [
        { branch_id: 'b1', business_date: '2026-10-20', banked_minor: 1000, expected_to_bank_minor: 1200, savings_minor: 100, expenses_minor: 50 },
        { branch_id: 'b1', business_date: '2026-10-21', banked_minor: 2000, expected_to_bank_minor: 2000, savings_minor: 200, expenses_minor: 0 },
      ],
    });
    show(<RetailDashboard />, mockMe('admin'));
    const cards = await screen.findByTestId('home-cash');
    expect(cashDaily).toHaveBeenCalledWith(expect.objectContaining({ from: '2026-10-19', to: '2026-10-21' }));
    expect(cards.textContent).toContain('UGX 3,000');
    expect(cards.textContent).toContain('Expected to bank UGX 3,200');
    expect(cards.textContent).toContain('UGX 300');
  });
});

describe("a seller's own range", () => {
  it('follows the remembered choice through the sales summary and shows only their own sales', async () => {
    const me = mockMe('sales');
    writeRange(me.user_id ?? '', { period: '30d' });
    salesSummary.mockResolvedValue({ items: [{ key: '2026-10-21', from: '2026-10-21', total_minor: 7000, sales_count: 2 }], has_more: false });
    show(<OwnSales />, me);
    await waitFor(() => expect(document.querySelector('.rc-total')?.textContent).toBe('UGX 7,000'));
    expect(salesSummary).toHaveBeenCalledWith(expect.objectContaining({ level: 'day' }));
    expect(screen.getByText('Your sales, Last 30 days')).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/profit|cost|margin/i);
    fireEvent.click(screen.getByRole('button', { name: 'All' }));
    await waitFor(() => expect(salesSummary).toHaveBeenCalledWith(expect.objectContaining({ level: 'year', from: undefined })));
    expect(readRange(me.user_id ?? '')).toEqual({ period: 'all' });
  });
});
