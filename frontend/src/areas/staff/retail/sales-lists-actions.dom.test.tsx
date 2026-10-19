// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, configure, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Sale, SalesSummary, SalesSummaryQuery } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// The grouped All sales and Credit sales lists must still take a payment (issues #326 and #328): the real PaySaleAction is
// under each sale of an opened day, and under each sale of the Still owing list. An owing live credit sale has Record payment;
// a paid sale has no button; an imported sale has a note and no button. Fabricated names and amounts.

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const summary = vi.fn<(q: SalesSummaryQuery) => Promise<SalesSummary>>();
const listSales = vi.fn();
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: { salesSummary: (q: SalesSummaryQuery) => summary(q), listSales: (q: unknown) => listSales(q), listProducts: async () => [] },
  };
});

const { businessToday } = await import('../../../api/retail');
const { AllSales, CreditSales } = await import('./sales');

const me = mockMe('admin');
const branch = me.branches?.[0]?.id ?? '';
const today = businessToday();
const none = { count: 0, total_minor: 0 };
const group = (period: string, from: string, to: string) => ({
  period, from, to, sales_count: 3, total_minor: 9000, voided_count: 0,
  by_method: { cash: none, mobile_money: none, bank: none, credit: { count: 3, total_minor: 9000 } },
});
const answer = (q: SalesSummaryQuery): SalesSummary => {
  const g = q.level === 'year' ? group(today.slice(0, 4), `${today.slice(0, 4)}-01-01`, `${today.slice(0, 4)}-12-31`)
    : q.level === 'month' ? group(today.slice(0, 7), `${today.slice(0, 7)}-01`, `${today.slice(0, 7)}-28`)
      : group(today, today, today);
  return { group_by: q.level, currency: 'UGX', has_more: false, items: [g] };
};

const sale = (id: string, buyer: string, over: Partial<Sale>): Sale => ({
  id, sale_no: `S-${id}`, branch_id: branch, sale_date: today, payment_method: 'credit', status: 'completed', buyer_name: buyer,
  total_minor: 3000, paid_minor: 0, balance_minor: 3000, due_date: today, historical: false, ...over,
});
const sales = [
  sale('owing', 'Test Buyer Owing', {}),
  sale('paid', 'Test Buyer Paid', { paid_minor: 3000, balance_minor: 0 }),
  sale('imported', 'Test Buyer Imported', { historical: true }),
];

function show(node: React.ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}
const card = (buyer: string) => screen.getByText(buyer).closest('li') as HTMLElement;

function expectActionsOnEachKindOfSale() {
  const owing = within(card('Test Buyer Owing'));
  expect(owing.getByRole('button', { name: 'Record payment' })).toBeTruthy();
  const paid = within(card('Test Buyer Paid'));
  expect(paid.queryByRole('button', { name: 'Record payment' })).toBeNull();
  expect(paid.queryByRole('button', { name: 'Mark as fully paid' })).toBeNull();
  const imported = within(card('Test Buyer Imported'));
  expect(imported.queryByRole('button', { name: 'Record payment' })).toBeNull();
  expect(imported.getByTestId('imported-note')).toBeTruthy();
  expect(screen.getAllByRole('button', { name: 'Record payment' })).toHaveLength(1);
}

beforeEach(() => {
  summary.mockImplementation(async (q) => answer(q));
  listSales.mockImplementation(async () => ({ items: sales }));
});
afterEach(() => {
  cleanup();
  summary.mockReset();
  listSales.mockReset();
});

describe('the grouped lists keep the payment buttons', () => {
  it('All sales: an opened day shows Record payment on an owing live credit sale only', async () => {
    show(<AllSales />);
    expect(screen.queryByRole('button', { name: 'Record payment' })).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(`^[A-Z][a-z]+ ${Number(today.slice(8))} `) }));
    await screen.findByText('Test Buyer Owing');
    expectActionsOnEachKindOfSale();
    expect(listSales).toHaveBeenCalledWith(expect.objectContaining({ from: today, to: today }));
  });

  it('Credit sales: an opened day shows the same, and Still owing lists the debts together with the button', async () => {
    show(<CreditSales />);
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(`^[A-Z][a-z]+ ${Number(today.slice(8))} `) }));
    await screen.findByText('Test Buyer Owing');
    expectActionsOnEachKindOfSale();
    cleanup();

    show(<CreditSales />);
    fireEvent.change(await screen.findByLabelText('Show'), { target: { value: 'owing' } });
    await screen.findByText('Test Buyer Owing');
    expectActionsOnEachKindOfSale();
    expect(screen.queryByRole('button', { name: /^2\d{3}/ })).toBeNull();
    expect(listSales).toHaveBeenLastCalledWith(expect.objectContaining({ owing: 'owing', paymentMethod: 'credit', status: 'completed', from: undefined, to: undefined }));
  });

  it('a day that fails to load says so and can be tried again', async () => {
    listSales.mockRejectedValueOnce(new Error('The server is busy.'));
    show(<AllSales />);
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(`^[A-Z][a-z]+ ${Number(today.slice(8))} `) }));
    expect((await screen.findByRole('alert')).textContent).toContain('The server is busy.');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByText('Test Buyer Owing');
    expect(screen.getAllByRole('button', { name: 'Record payment' })).toHaveLength(1);
  });

  it('the search box shows how many sales the groups hold', async () => {
    show(<AllSales />);
    await screen.findByRole('button', { name: new RegExp(`^${today.slice(0, 4)} `) });
    expect(document.body.textContent).toMatch(/\b3\b.*(result|sale|match|found)/i);
  });
});
