import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { AccountPayment, Customer, CustomerAccount, OpenSale, Statement } from '../../../api/retail';
import type { CreditControl } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { AccountScreen, AccountSummary, PaymentPanel, ReceiptList, StatementView, blankPayment } from './account';
import { AccountRow, AccountsPage } from './accounts';
import { BuyerAccount } from './buyer-account';
import { CreditControlView } from './credit-control';

// Customer accounts on the screens (ADR-033, issue #196). With the capability off nothing is drawn: "all off equals today".
// Names and amounts are fabricated.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
const branch = mockMe('admin').branches?.[0]?.id ?? '';
const on = (me: ReturnType<typeof mockMe>) => ({ ...me, module_capabilities: { retail: ['customer_accounts'] } });
const admin = on(mockMe('admin'));
const seller = on(mockMe('sales'));

const page = (me: ReturnType<typeof mockMe>, node: ReactElement) =>
  renderToString(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );

const account: CustomerAccount = { customer_id: 'c1', name: 'Test Buyer 01', currency: 'UGX', balance_minor: 3000, credit_limit_minor: 5000, terms_days: 30, exposure_minor: 3000, available_minor: 2000, over_limit: false, version: 4 };
const sale = (id: string, date: string, balance: number, over: Partial<OpenSale> = {}): OpenSale => ({
  sale_id: id, sale_no: `RS-${id}`, branch_id: branch, sale_date: date, total_minor: balance, paid_minor: 0, balance_minor: balance, ...over,
});
const open: OpenSale[] = [sale('b', '2026-01-05', 1000), sale('c', '2026-02-05', 800), sale('old', '2025-11-01', 700, { historical: true })];
const noop = () => undefined;

describe('the pages are closed unless the feature is on', () => {
  it('refuses the accounts page and an account while the feature is off', () => {
    expect(page(mockMe('admin'), <AccountsPage />)).toContain('You do not have access');
    expect(page(mockMe('admin'), <AccountScreen customerId="c1" me={mockMe('admin')} branch={branch} />)).toContain('You do not have access');
  });

  it('keeps the accounts page for whoever sets limits, and opens an account to whoever reads sales', () => {
    expect(page(seller, <AccountsPage />)).toContain('You do not have access');
    const html = page(admin, <AccountsPage />);
    expect(html).not.toContain('You do not have access');
    expect(html).toContain('data-tour="accounts-help"');
    expect(html).toContain('data-tour="accounts-list"');
    expect(html).toContain('Search by name or phone');
    expect(page(seller, <AccountScreen customerId="c1" me={seller} branch={branch} />)).not.toContain('You do not have access');
  });
});

describe('a buyer in the lists', () => {
  const buyer: Customer = { id: 'c1', name: 'Test Buyer 01', balance_minor: 3000, credit_limit_minor: 5000, terms_days: 30, version: 4 };

  it('shows what is owed, the limit and the terms, and the way to the account', () => {
    const html = renderToString(<RouterContextProvider router={router}><ul><AccountRow row={buyer} /></ul></RouterContextProvider>);
    expect(html).toContain('UGX 3,000');
    expect(html).toContain('Credit limit UGX 5,000');
    expect(html).toContain('30 days after the sale');
    expect(html).toContain('aria-label="Open the account of Test Buyer 01"');
    expect(html).toContain('/staff/retail/accounts/c1');
  });

  it('adds the limit and the link to Credit buyers only while the feature is on', () => {
    expect(page(mockMe('sales'), <BuyerAccount row={buyer} />)).toBe('');
    expect(page(seller, <BuyerAccount row={buyer} />)).toContain('data-tour="buyer-account"');
    expect(page(seller, <BuyerAccount row={buyer} />)).toContain('Open account');
  });
});

describe('the summary of an account', () => {
  const summary = (a: CustomerAccount, canManage: boolean) => renderToString(<AccountSummary account={a} canManage={canManage} onSave={noop} />);

  it('says what is owed, the limit with room left, and the terms', () => {
    const html = summary(account, false);
    expect(html).toContain('Owes UGX 3,000');
    expect(html).toContain('UGX 2,000 still available');
    expect(html).toContain('30 days after the sale');
  });

  it('says plainly when the buyer is over the limit, and when there is none', () => {
    expect(summary({ ...account, exposure_minor: 5600, available_minor: 0, over_limit: true }, false)).toContain('Over the limit by UGX 600');
    const none = summary({ customer_id: 'c1', name: 'Test Buyer 01', balance_minor: 0, version: 1 }, false);
    expect(none).toContain('Owes nothing');
    expect(none).toContain('No credit limit.');
    expect(none).toContain('No payment terms.');
  });

  it('offers the change to retail.credit.manage only, and shows no figure it was not sent for another branch', () => {
    expect(summary(account, true)).toContain('Change the limit and terms');
    expect(summary(account, false)).not.toContain('Change the limit and terms');
    expect(summary({ ...account, exposure_minor: undefined, available_minor: undefined, over_limit: undefined }, false)).not.toContain('still available');
  });
});

describe('the payment on account', () => {
  const panel = (form = blankPayment(branch, '2026-04-01'), list: OpenSale[] = open) =>
    renderToString(
      <PaymentPanel form={form} open={list} branches={mockMe('admin').branches ?? []} today="2026-04-01" onChange={noop} onSubmit={noop} />,
    );

  it('says how much can be paid and leaves the imported sale out', () => {
    const html = panel();
    expect(html).toContain('Up to UGX 1,800 can be paid here');
    expect(html).not.toContain('700');
    expect(html).toContain('Branch that received the money');
    expect(html).toContain('Choose which sales it pays');
  });

  it('previews where the money goes, the oldest sale first, before anything is saved', () => {
    const html = panel({ ...blankPayment(branch, '2026-04-01'), amount: '1500' });
    expect(html).toContain('data-testid="pay-preview"');
    expect(html.indexOf('RS-b')).toBeLessThan(html.indexOf('RS-c'));
    expect(html).toContain('RS-b: UGX 1,000');
    expect(html).toContain('RS-c: UGX 500');
  });

  it('warns about an amount above what can be paid', () => {
    expect(panel({ ...blankPayment(branch, '2026-04-01'), amount: '2000' })).toContain('At most UGX 1,800');
  });

  it('lists the sales to choose from when the cashier chooses', () => {
    const html = panel({ ...blankPayment(branch, '2026-04-01'), amount: '900', manual: true });
    expect(html).toContain('data-testid="pay-manual"');
    expect(html).toContain('RS-b, 5 Jan 2026, owes UGX 1,000');
    expect(html).not.toContain('RS-old');
  });

  it('says what the buyer owes when it is all imported, or nothing', () => {
    expect(panel(undefined, [sale('old', '2025-11-01', 700, { historical: true })])).toContain('imported sales');
    expect(panel(undefined, [])).toContain('This buyer owes nothing');
  });
});

describe('the payments taken', () => {
  const receipt = (over: Partial<AccountPayment> = {}): AccountPayment => ({
    id: 'r1', receipt_no: 'RC00000001', customer_id: 'c1', branch_id: branch, amount_minor: 1500, method: 'cash', paid_on: '2026-04-01', allocation: 'oldest_first', status: 'posted',
    allocations: [{ sale_id: 'b', sale_no: 'RS-b', amount_minor: 1000 }, { sale_id: 'c', sale_no: 'RS-c', amount_minor: 500 }], ...over,
  });
  const list = (items: AccountPayment[], canReverse = true) =>
    renderToString(<ReceiptList items={items} canReverse={() => canReverse} nameOf={() => 'Test Branch A'} onReverse={noop} />);

  it('lists each payment with where it went and offers a reversal to whoever may', () => {
    const html = list([receipt()]);
    expect(html).toContain('RC00000001');
    expect(html).toContain('UGX 1,500 by Cash on 1 Apr 2026, received at Test Branch A');
    expect(html).toContain('RS-b UGX 1,000, RS-c UGX 500');
    expect(html).toContain('aria-label="Reverse payment RC00000001"');
    expect(list([receipt()], false)).not.toContain('Reverse payment');
  });

  it('marks a reversed payment with its reason and offers nothing more', () => {
    const html = list([receipt({ status: 'reversed', reverse_reason: 'Wrong buyer' })]);
    expect(html).toContain('Reversed');
    expect(html).toContain('Wrong buyer');
    expect(html).not.toContain('Reverse payment');
  });

  it('says so when none was taken', () => {
    expect(list([])).toContain('No payment has been taken');
  });
});

describe('the statement', () => {
  const statement: Statement = {
    customer_id: 'c1', name: 'Test Buyer 01', currency: 'UGX', from: '2026-01-01', to: '2026-04-01', opening_minor: 200,
    lines: [
      { date: '2026-01-05', kind: 'sale', reference: 'RS-b', sale_id: 'b', debit_minor: 1000, credit_minor: 0, balance_minor: 1200 },
      { date: '2026-02-01', kind: 'account_payment', reference: 'RC00000001', method: 'cash', receipt_id: 'r1', debit_minor: 0, credit_minor: 1000, balance_minor: 200 },
    ],
    closing_minor: 200, balance_minor: 900, before_system_start: { sales: [{ sale_id: 'old', sale_no: 'RS-old', branch_id: branch, sale_date: '2025-11-01', total_minor: 700 }], total_minor: 700 },
  };
  const view = (s: Statement) => renderToString(<StatementView statement={s} />);

  it('shows the opening balance, each line with its running balance and the closing balance', () => {
    const html = view(statement);
    expect(html).toContain('Opening balance');
    expect(html).toContain('Credit sale');
    expect(html).toContain('Payment on account');
    expect(html).toContain('RC00000001, Cash');
    expect(html).toContain('Closing balance');
    expect(html.indexOf('Opening balance')).toBeLessThan(html.indexOf('Credit sale'));
    expect(html.indexOf('Credit sale')).toBeLessThan(html.indexOf('Closing balance'));
  });

  it('lists imported sales apart, under their own title, and does not put them in the closing balance', () => {
    const html = view(statement);
    expect(html).toContain('Before system start');
    expect(html).toContain('RS-old, 1 Nov 2025: UGX 700');
    expect(html).toContain('not in the closing balance');
    expect(html).toContain('the buyer owes UGX 900');
    expect(view({ ...statement, before_system_start: undefined })).not.toContain('Before system start');
  });

  it('says so when nothing happened in the dates', () => {
    expect(view({ ...statement, lines: [] })).toContain('Nothing happened');
  });
});

describe('credit control', () => {
  const data: CreditControl = {
    from: '2026-03-01', to: '2026-04-01', as_of: '2026-04-01', currency: 'UGX', top: 20, buyer_count: 1, overdue_count: 0, sort: 'amount', overdue: [], payments: [], payments_by_method: [], payments_minor: 0,
    totals: { owed_minor: 6000 },
    buyers: [{ customer_id: 'c1', name: 'Test Buyer 01', sale_count: 2, ageing: { owed_minor: 6000 }, credit_limit_minor: 5000, over_limit: true }],
  };
  const view = (accounts: boolean, report: CreditControl = data) =>
    renderToString(<RouterContextProvider router={router}><CreditControlView data={report} nameOf={() => ''} sort="amount" accounts={accounts} /></RouterContextProvider>);

  it('adds the limit, a mark above it and the link to the account only while the feature is on', () => {
    const html = view(true);
    expect(html).toContain('Above the credit limit');
    expect(html).toContain('aria-label="Open the account of Test Buyer 01"');
    expect(html).toContain('UGX 5,000');
    const off = view(false);
    expect(off).not.toContain('Above the credit limit');
    expect(off).not.toContain('Open the account');
    expect(off).not.toContain('Limit');
  });

  it('marks a buyer above the limit only when the server says so, never from a branch\'s share of what they owe', () => {
    // A report over one branch: the buyer owes 6,000 here against a limit of 5,000 that is measured across every branch, and the
    // server sends no over_limit, so the screen says nothing about the limit being passed.
    const scoped: CreditControl = { ...data, buyers: [{ customer_id: 'c1', name: 'Test Buyer 01', sale_count: 2, ageing: { owed_minor: 6000 }, credit_limit_minor: 5000 }] };
    const html = view(true, scoped);
    expect(html).not.toContain('Above the credit limit');
    expect(html).toContain('UGX 5,000');
    // And a buyer the server says is within the limit is not marked either.
    const within: CreditControl = { ...data, buyers: [{ customer_id: 'c1', name: 'Test Buyer 01', sale_count: 2, ageing: { owed_minor: 3000 }, credit_limit_minor: 5000, over_limit: false }] };
    expect(view(true, within)).not.toContain('Above the credit limit');
  });
});
