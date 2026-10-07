import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import type { Loan, LoanSchedule, LoanTransaction, RepaymentResult } from '../../../api/lending';
import { StaffContext } from '../context';
import { RepaymentForm, RepaymentReceipt } from './actions';
import { LoanView } from './loan';
import { LendingGate } from './loans';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

// Component tests render to static markup (the test setup has no DOM), as the retail ones do:
// they prove what the loan page shows and which actions each permission set is offered.

const LOAN_ID = '00000000-0000-4000-8000-0000000000a1';

const me = (permissions: string[]): Me => ({
  user_id: '00000000-0000-4000-8000-0000000000f2',
  full_name: 'Test Officer 01',
  kind: 'staff',
  permissions,
  all_branches: true,
  branches: [{ id: '00000000-0000-4000-8000-0000000000b1', code: 'BR1', name: 'Test Branch A', is_head_office: true }],
});

const officer = me(['lending.loans.read', 'lending.repayments.create', 'lending.repayments.reverse_request', 'lending.disbursements.request']);
const viewer = me(['lending.loans.read']);

const loan: Loan = {
  id: LOAN_ID,
  loan_no: 'LN-BR1-000001',
  member_id: '00000000-0000-4000-8000-0000000000c1',
  member_no: 'M-000001',
  product_code: 'TEST-MONTHLY',
  status: 'active',
  currency: 'UGX',
  approved_principal_minor: 1000000,
  balances: {
    principal_outstanding_minor: 500000,
    interest_outstanding_minor: 50000,
    fees_outstanding_minor: 0,
    penalties_outstanding_minor: 10000,
    total_outstanding_minor: 560000,
    arrears_minor: 120000,
    days_past_due: 12,
    next_due_date: '2026-11-01',
    total_paid_minor: 600000,
    credit_balance_minor: 0,
    disbursed_on: '2026-05-01',
    maturity_date: '2027-05-01',
  },
};

const schedule: LoanSchedule = {
  currency: 'UGX',
  items: [
    { no: 1, due_date: '2026-06-01', principal_due_minor: 500000, interest_due_minor: 100000, fees_due_minor: 0, total_due_minor: 600000, total_paid_minor: 600000, outstanding_minor: 0, status: 'paid' },
    { no: 2, due_date: '2026-07-01', principal_due_minor: 500000, interest_due_minor: 50000, fees_due_minor: 0, total_due_minor: 550000, total_paid_minor: 0, outstanding_minor: 550000, status: 'overdue' },
  ],
  totals: { principal_due_minor: 1000000, interest_due_minor: 150000, fees_due_minor: 0, total_due_minor: 1150000, total_paid_minor: 600000, outstanding_minor: 550000 },
};

const txns: LoanTransaction[] = [
  { id: 't3', txn_type: 'repayment', amount_minor: 100000, currency: 'UGX', value_date: '2026-06-20', receipt_no: 'RC-BR1-000002', payment_method_key: 'mtn_momo', reversed_by_txn_id: 't4' },
  { id: 't2', txn_type: 'repayment', amount_minor: 600000, currency: 'UGX', value_date: '2026-06-01', receipt_no: 'RC-BR1-000001', payment_method_key: 'cash' },
  { id: 't1', txn_type: 'disbursement', amount_minor: 1000000, currency: 'UGX', value_date: '2026-05-01', receipt_no: 'VC-BR1-000001', payment_method_key: 'bank' },
];

function page(session: Me, node: ReactElement, seed: (c: QueryClient) => void = () => undefined) {
  const client = new QueryClient();
  seed(client);
  return renderToString(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: session, branch: null }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const seeded = (c: QueryClient) => {
  c.setQueryData(['lending', 'loan', LOAN_ID, 'schedule'], schedule);
  c.setQueryData(['lending', 'loan', LOAN_ID, 'transactions'], txns);
};

describe('loan page', () => {
  const html = page(officer, <LoanView loan={loan} memberName="Test Borrower 01" />, seeded);

  it('shows the header, the balances and the arrears in words', () => {
    expect(html).toContain('LN-BR1-000001');
    expect(html).toContain('Test Borrower 01 (M-000001)');
    expect(html).toContain('Product TEST-MONTHLY, principal UGX 1,000,000');
    expect(html).toContain('>Active<');
    expect(html).toContain('UGX 560,000');
    expect(html).toContain('Days past due');
    expect(html).toContain('2027-05-01');
  });

  it('shows the schedule in a focusable scroll region with its totals row', () => {
    expect(html).toMatch(/class="table-wrap" tabindex="0" role="region"/);
    expect(html).toContain('<tfoot>');
    expect(html).toContain('UGX 1,150,000');
    expect(html).toContain('Overdue');
  });

  it('lists transactions with receipts, the reversed marker and Reverse only where it applies', () => {
    expect(html).toContain('RC-BR1-000001');
    expect(html).toContain('VC-BR1-000001');
    expect(html).toContain('MTN Mobile Money');
    expect(html).toContain('Reversed');
    expect(html).toContain('aria-label="Reverse RC-BR1-000001"');
    expect(html).not.toContain('aria-label="Reverse RC-BR1-000002"');
    expect(html).not.toContain('aria-label="Reverse VC-BR1-000001"');
  });

  it('offers an officer repayment and payoff on an active loan, and disbursement only when approved', () => {
    expect(html).toContain('Record repayment');
    expect(html).toContain('Payoff quote');
    expect(html).not.toContain('>Disburse<');
    const approved = page(officer, <LoanView loan={{ ...loan, status: 'approved', balances: undefined }} />, seeded);
    expect(approved).toContain('>Disburse<');
    expect(approved).not.toContain('Record repayment');
    expect(approved).toContain('set when the loan is disbursed');
  });

  it('offers a read-only user nothing that moves money', () => {
    const read = page(viewer, <LoanView loan={loan} />, seeded);
    expect(read).toContain('Payoff quote');
    expect(read).not.toContain('Record repayment');
    expect(read).not.toContain('>Reverse<');
    expect(read).toContain('Member M-000001');
  });

  it('hides the pages from a user without lending.loans.read', () => {
    const none = page(me(['retail.sale.create']), <LendingGate title="Loans"><p>SECRET-CONTENT</p></LendingGate>);
    expect(none).toContain('You do not have access');
    expect(none).not.toContain('SECRET-CONTENT');
  });
});

describe('repayment', () => {
  it('has labelled inputs, today as the default date and the four payment methods', () => {
    const html = page(officer, <RepaymentForm loan={loan} onDone={() => undefined} />);
    expect(html).toContain('for="repay-amount"');
    expect(html).toContain('for="repay-date"');
    expect(html).toContain('for="repay-ref"');
    for (const m of ['Cash', 'Bank', 'MTN Mobile Money', 'Airtel Money']) expect(html).toContain(m);
    expect(html).toMatch(/id="repay-date" type="date" max="\d{4}-\d{2}-\d{2}" value="\d{4}-\d{2}-\d{2}"/);
    expect(html).not.toContain('style=');
  });

  it('says a payment on a written-off loan is a recovery', () => {
    expect(page(officer, <RepaymentForm loan={{ ...loan, status: 'written_off' }} onDone={() => undefined} />)).toContain('recorded as a recovery');
  });

  it('shows the receipt number and the allocation by component', () => {
    const result: RepaymentResult = {
      loan_status: 'active',
      balances: { total_outstanding_minor: 460000 },
      transaction: {
        txn_type: 'repayment', receipt_no: 'RC-BR1-000003', amount_minor: 100000, value_date: '2026-10-06',
        allocations: [
          { component: 'penalty', item_no: 2, amount_minor: 10000 },
          { component: 'interest', item_no: 2, amount_minor: 50000 },
          { component: 'principal', item_no: 2, amount_minor: 40000 },
        ],
      },
    };
    const html = renderToString(<RepaymentReceipt result={result} currency="UGX" />);
    expect(html).toContain('RC-BR1-000003');
    expect(html).toContain('Penalties');
    expect(html).toContain('UGX 40,000');
    expect(html).toContain('Still outstanding: <strong>UGX 460,000</strong>');
  });
});
