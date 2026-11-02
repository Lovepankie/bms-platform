// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Dashboard } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Issue #732: the sales summary says once, at the top, what its red and green change lines are set against, and each change
// line reads in words ("Down 20 (95%)", "Up 3 (12%)"), the arrow kept, so the colour is never the only sign. Fabricated figures.

const dashboard = vi.fn();
const cashDaily = vi.fn();

vi.mock('../../../api/retail-analytics', async (original) => {
  const real = await original<typeof import('../../../api/retail-analytics')>();
  return { ...real, retailAnalytics: { dashboard: (...args: unknown[]) => dashboard(...args) } };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { cashDaily: (...args: unknown[]) => cashDaily(...args) } };
});

const { RetailDashboard } = await import('./dashboard');

afterEach(() => {
  cleanup();
  dashboard.mockReset();
  cashDaily.mockReset();
  localStorage.clear();
});

/** 1 sale of UGX 2,800 now against 21 sales of UGX 2,500 before: up UGX 300 (12%) in money, down 20 (95%) in sales. */
const body = (period: '7d' | '30d', from: string, previousFrom: string, previousTo: string): Dashboard => ({
  date: '2026-10-21', currency: 'UGX', profit_visible: false, shops: [],
  today: { sales_minor: 0, sale_count: 0, cash_minor: 0, credit_minor: 0 },
  range: {
    period, from, to: '2026-10-21', previous_from: previousFrom, previous_to: previousTo, group: 'day',
    current: { sales_minor: 2800, sale_count: 1, average_sale_minor: 2800 },
    previous: { sales_minor: 2500, sale_count: 21, average_sale_minor: 119 },
  },
});

function show(data: Dashboard) {
  dashboard.mockResolvedValue(data);
  cashDaily.mockResolvedValue({ items: [] });
  const me = mockMe('admin');
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: 'all' }}>
          <RetailDashboard />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const changeOf = (label: string) => {
  const stat = screen.getByText(label, { selector: '.an-stat-label' }).closest('li') as HTMLElement;
  return stat.querySelector('.rc-change') as HTMLElement;
};

describe('what the sales summary compares with (#732)', () => {
  it('says the 7 days before once at the top, and each change line in words', async () => {
    show(body('7d', '2026-10-15', '2026-10-08', '2026-10-14'));
    const line = await screen.findByTestId('compare-line');
    expect(line.textContent).toBe('Compared with the 7 days before');
    expect(screen.getAllByTestId('compare-line')).toHaveLength(1);
    // The line sits above the total and its change line, under the range picker.
    const headline = screen.getByText('UGX 2,800', { selector: '.rc-total' }).parentElement as HTMLElement;
    expect(line.compareDocumentPosition(headline) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    // Up 3 (12%): UGX 2,800 against UGX 2,500, rounded to whole percent.
    const total = within(headline).getByText(/^Up/);
    expect(total.className).toContain('rc-up');
    expect(total.className).toContain('num');
    expect(total.firstChild?.textContent).toBe('▲ ');
    expect((total.firstChild as HTMLElement).getAttribute('aria-hidden')).toBe('true');
    expect(total.childNodes[1]?.textContent).toBe('Up UGX 300 (12%)');

    // Down 20 (95%): 1 sale against 21.
    const sales = changeOf('Number of sales');
    expect(sales.className).toContain('rc-down');
    expect(sales.firstChild?.textContent).toBe('▼ ');
    expect(sales.childNodes[1]?.textContent).toBe('Down 20 (95%)');
    // A screen reader that lands on one line alone still hears what it is set against.
    expect(sales.querySelector('.visually-hidden')?.textContent).toBe(', compared with the 7 days before');
  });

  it('says the 30 days before for a 30 day range', async () => {
    show(body('30d', '2026-09-22', '2026-08-23', '2026-09-21'));
    const line = await screen.findByTestId('compare-line');
    expect(line.textContent).toBe('Compared with the 30 days before');
    const sales = changeOf('Number of sales');
    expect(sales.childNodes[1]?.textContent).toBe('Down 20 (95%)');
    expect(sales.querySelector('.visually-hidden')?.textContent).toBe(', compared with the 30 days before');
    // The words of the line are never the old "on the 30 days before" tail on each line.
    expect(document.body.textContent).not.toContain('% on the');
  });

  it('shows no compare line when there is no period before', async () => {
    const data = body('7d', '2026-10-15', '2026-10-08', '2026-10-14');
    delete data.range?.previous;
    show(data);
    await screen.findByText('UGX 2,800', { selector: '.rc-total' });
    expect(screen.queryByTestId('compare-line')).toBeNull();
    expect(document.querySelector('.rc-change')).toBeNull();
  });
});
