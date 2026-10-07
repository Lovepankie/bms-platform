import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import type { SavingsAccount, SavingsStatement, SavingsTransaction } from '../../../api/savings';
import { savingsMessage } from '../../../api/savings';
import { StaffContext } from '../context';
import { AccountView } from './savings-account';
import { confirmation, MoneyForm } from './savings-forms';
import { draftOf, EMPTY_PRODUCT, percentToBp, termsOf } from './savings-products';
import { StatementTable, threeMonthsBefore } from './savings-statement';
import { SavingsGate } from './savings';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

// Component tests render to static markup (the test setup has no DOM), as the loan ones do: they
// prove what the savings page shows, which actions each permission set is offered, and what the
// confirmation step reads back before money moves. All figures fabricated.

const ACCOUNT_ID = '00000000-0000-4000-8000-0000000000d1';

const me = (permissions: string[]): Me => ({
  user_id: '00000000-0000-4000-8000-0000000000f2',
  full_name: 'Test Cashier 01',
  kind: 'staff',
  permissions,
  all_branches: true,
  branches: [{ id: '00000000-0000-4000-8000-0000000000b1', code: 'BR1', name: 'Test Branch A', is_head_office: true }],
});

const cashier = me(['lending.savings.read', 'lending.savings.deposit', 'lending.savings.withdraw']);
const auditor = me(['lending.savings.read']);

const account: SavingsAccount = {
  id: ACCOUNT_ID,
  account_no: 'SV000001',
  member_id: '00000000-0000-4000-8000-0000000000c1',
  member_no: 'M000001',
  member_name: 'Test Saver 01',
  product_code: 'TEST-SAVE',
  product_name: 'Test passbook savings',
  currency: 'UGX',
  status: 'active',
  balance_minor: 150000,
  available_minor: 144000,
  min_balance_minor: 5000,
  withdrawal_fee_minor: 1000,
  interest_rate_bp: 500,
  interest_calc: 'daily_balance',
  interest_posting: 'monthly',
  accrued_interest_minor: 312,
  opened_on: '2026-01-05',
  last_member_txn_on: '2026-09-30',
};

const txns: SavingsTransaction[] = [
  { id: 't3', seq: 3, txn_type: 'interest', amount_minor: 600, credit: true, currency: 'UGX', balance_after_minor: 150000, value_date: '2026-09-30', reason: 'Interest 2026-09-01 to 2026-09-30' },
  { id: 't2', seq: 2, txn_type: 'withdrawal', amount_minor: 50000, credit: false, currency: 'UGX', balance_after_minor: 149400, value_date: '2026-09-20', receipt_no: 'VC-BR1-000001', payment_method_key: 'cash' },
  { id: 't1', seq: 1, txn_type: 'deposit', amount_minor: 200000, credit: true, currency: 'UGX', balance_after_minor: 200000, value_date: '2026-01-05', receipt_no: 'RC-BR1-000001', payment_method_key: 'mtn_momo' },
];

/** Renders inside a memory router, since the account page links to the member and the statement. */
async function page(session: Me, node: ReactElement, seed: (c: QueryClient) => void = () => undefined) {
  const client = new QueryClient();
  seed(client);
  const tree = (
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: session, branch: null }}>{node}</StaffContext.Provider>
    </QueryClientProvider>
  );
  const router = createRouter({
    routeTree: createRootRoute({ component: () => tree }),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  await router.load();
  return renderToString(<RouterProvider router={router} />);
}

const withTxns = (c: QueryClient) =>
  c.setQueryData(['savings', 'account', ACCOUNT_ID, 'transactions'], { pages: [{ items: txns }], pageParams: [undefined] });

describe('the savings account page', () => {
  it('shows the balance, what can be paid out now, the accrued interest and the running balances', async () => {
    const html = await page(cashier, <AccountView account={account} />, withTxns);
    expect(html).toContain('SV000001');
    expect(html).toContain('Test Saver 01 (M000001)');
    expect(html).toContain('UGX 150,000');
    expect(html).toContain('UGX 144,000');
    expect(html).toContain('UGX 312');
    expect(html).toContain('5% a year');
    expect(html).toContain('Balance after UGX 149,400');
    expect(html).toContain('+UGX 200,000');
    expect(html).toContain('-UGX 50,000');
    expect(html).toContain('Statement');
  });

  it('offers a cashier deposit, withdrawal and closure, and reversal of deposits and withdrawals only', async () => {
    const html = await page(cashier, <AccountView account={account} />, withTxns);
    expect(html).toContain('>Deposit<');
    expect(html).toContain('>Withdraw<');
    expect(html).toContain('>Close account<');
    expect(html).not.toContain('>Freeze<');
    expect(html).toContain('aria-label="Reverse VC-BR1-000001"');
    expect(html).toContain('aria-label="Reverse RC-BR1-000001"');
    expect(html.match(/>Reverse</g)).toHaveLength(2);
  });

  it('offers a read-only user no action', async () => {
    const html = await page(auditor, <AccountView account={account} />, withTxns);
    expect(html).not.toContain('role="group" aria-label="Actions"');
    expect(html).not.toContain('>Reverse<');
  });

  it('gates the savings pages on lending.savings.read', async () => {
    expect(await page(me(['lending.loans.read']), <SavingsGate title="Savings">body</SavingsGate>)).toContain(
      'You do not have access to this page.',
    );
    expect(await page(auditor, <SavingsGate title="Savings">body</SavingsGate>)).toContain('body');
  });

  it('starts a money form on its details step, with the most that can be paid out', async () => {
    const html = await page(cashier, <MoneyForm account={account} kind="withdraw" />);
    expect(html).toContain('At most UGX 144,000 can be paid out now.');
    expect(html).toContain('Review withdrawal');
  });
});

describe('the confirmation step', () => {
  it('reads a deposit back with the account, member, method and balance after', () => {
    const c = confirmation('deposit', account, 25000, 'mtn_momo');
    expect(c.title).toBe('Deposit UGX 25,000');
    expect(c.lines).toEqual(['Into SV000001, Test Saver 01 (M000001).', 'Received by MTN Mobile Money.', 'Balance after: UGX 175,000.']);
  });

  it('reads a withdrawal back with its fee, the balance after and the approval rule', () => {
    const c = confirmation('withdraw', account, 40000, 'cash');
    expect(c.title).toBe('Pay out UGX 40,000');
    expect(c.lines).toContain('Withdrawal fee: UGX 1,000, taken from the account.');
    expect(c.lines).toContain('Balance after: UGX 109,000.');
    expect(c.lines.join(' ')).toContain('checker must approve');
  });
});

describe('the statement', () => {
  const s: SavingsStatement = {
    account_no: 'SV000001',
    member_no: 'M000001',
    member_name: 'Test Saver 01',
    product_name: 'Test passbook savings',
    currency: 'UGX',
    from: '2026-07-08',
    to: '2026-10-07',
    opening_balance_minor: 100000,
    total_credits_minor: 50600,
    total_debits_minor: 51000,
    closing_balance_minor: 99600,
    lines: [
      { seq: 4, value_date: '2026-08-01', txn_type: 'deposit', receipt_no: 'RC-BR1-000002', description: 'Deposit', debit_minor: 0, credit_minor: 50000, balance_minor: 150000 },
      { seq: 5, value_date: '2026-08-31', txn_type: 'interest', description: 'Interest', debit_minor: 0, credit_minor: 600, balance_minor: 150600 },
      { seq: 6, value_date: '2026-09-02', txn_type: 'withdrawal', receipt_no: 'VC-BR1-000002', description: 'Withdrawal', debit_minor: 50000, credit_minor: 0, balance_minor: 100600 },
      { seq: 7, value_date: '2026-09-02', txn_type: 'fee', description: 'Withdrawal fee', debit_minor: 1000, credit_minor: 0, balance_minor: 99600 },
    ],
  };

  it('shows the opening balance, each line with its running balance, and the totals', async () => {
    const html = await page(auditor, <StatementTable s={s} />);
    expect(html).toContain('Opening balance');
    expect(html).toContain('UGX 100,000');
    expect(html).toContain('Deposit RC-BR1-000002');
    expect(html).toContain('UGX 150,600');
    expect(html).toContain('UGX 99,600');
    expect(html).toContain('UGX 51,000');
  });

  it('defaults to three months back from today', () => {
    expect(threeMonthsBefore('2026-10-07')).toBe('2026-07-08');
    expect(threeMonthsBefore('2026-03-31')).toBe('2026-01-01');
  });
});

describe('product set-up', () => {
  it('reads a rate typed as a percentage a year into basis points', () => {
    expect(percentToBp('5')).toBe(500);
    expect(percentToBp('7.25')).toBe(725);
    expect(percentToBp('0.5')).toBe(50);
    expect(percentToBp('101')).toBeNull();
    expect(percentToBp('five')).toBeNull();
  });

  it('builds the terms in minor units, leaving optional rules out when empty', () => {
    const terms = termsOf({ ...EMPTY_PRODUCT, name: 'Test passbook', calc: 'daily_balance', rate: '5', minBalance: '5,000', fee: '1000', dormancyDays: '180' }, 'UGX');
    expect(terms).toEqual({
      name: 'Test passbook',
      interest_rate_bp: 500,
      interest_calc: 'daily_balance',
      interest_posting: 'monthly',
      min_balance_for_interest_minor: undefined,
      min_opening_balance_minor: undefined,
      min_balance_minor: 5000,
      withdrawal_fee_minor: 1000,
      max_withdrawal_minor: undefined,
      max_withdrawals_per_month: undefined,
      dormancy_days: 180,
    });
    expect(termsOf({ ...EMPTY_PRODUCT, name: 'X', calc: 'daily_balance', rate: '0' }, 'UGX')).toContain('rate');
    expect(termsOf({ ...EMPTY_PRODUCT, name: 'X', fee: 'abc' }, 'UGX')).toContain('amounts');
  });

  it('round-trips a saved product into its form', () => {
    const d = draftOf({ code: 'SAVE', name: 'Test', currency: 'UGX', interest_rate_bp: 725, interest_calc: 'daily_balance', interest_posting: 'quarterly', min_balance_minor: 5000, withdrawal_fee_minor: 0, dormancy_days: 90 });
    expect(d.rate).toBe('7.25');
    expect(d.minBalance).toBe('5000');
    expect(d.fee).toBe('');
    expect(d.dormancyDays).toBe('90');
  });
});

describe('savings refusals', () => {
  it('are explained in plain words', () => {
    expect(savingsMessage('account_dormant')).toContain('reactivate');
    expect(savingsMessage('insufficient_balance')).toContain('minimum');
  });
});
