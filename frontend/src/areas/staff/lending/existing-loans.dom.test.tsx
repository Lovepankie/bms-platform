// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { ExistingLoanError, type ExistingLoan, type ExistingLoanImportResult, type ExistingLoansApi } from '../../../api/existing-loans';
import { LendingApiContext } from '../../../api/lending-context';
import { createCollateralMock, createMembersMock } from '../../../api/members-mock';
import type { MemberRecord } from '../../../api/members';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { ExistingLoansImport } from './existing-loans-import';
import { ExistingLoanForm, ExistingLoansPage } from './existing-loans';

// The existing-loans screens as a person uses them (issue #466, FR-EXL-01, FR-EXL-02, FR-EXL-06): the form checks the loan
// first, shows the schedule from the real date and the true position, then records it once with an Idempotency-Key; a refusal
// lands on the field it is about; the import checks the file, shows the rows with a problem, records nothing while one has a
// problem, and records the loans of a clean file. The API is replaced. Fabricated names, numbers and amounts.

afterEach(cleanup);

const BRANCH = '00000000-0000-4000-8000-0000000000b1';
const manager: Me = {
  user_id: 'u1', full_name: 'Test Manager 01', kind: 'staff', all_branches: false,
  permissions: ['lending.loans.read', 'lending.members.read', 'lending.loans.bring_in'],
  branches: [{ id: BRANCH, code: 'BR1', name: 'Test Branch A' }],
};
const officer: Me = { ...manager, permissions: ['lending.loans.read', 'lending.members.read'] };
const borrower: MemberRecord = { id: '00000000-0000-4000-8000-000000000201', branch_id: BRANCH, member_no: 'M000201', full_name: 'Test Borrower 21', phone_e164: '+256700000021', kyc_status: 'verified', status: 'active', id_type: 'nin', national_id: 'CMTEST0000021A', version: 1 };
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/lending/existing-loans'] }) });

const preview: ExistingLoan = {
  status: undefined,
  currency: 'UGX',
  member_id: borrower.id,
  principal_minor: 1_200_000,
  disbursement_date: '2026-01-15',
  schedule: [
    { no: 1, due_date: '2026-02-15', principal_due_minor: 400_000, interest_due_minor: 120_000, paid_minor: 520_000, unpaid_minor: 0, status: 'paid' },
    { no: 2, due_date: '2026-03-15', principal_due_minor: 400_000, interest_due_minor: 120_000, paid_minor: 0, unpaid_minor: 520_000, status: 'overdue' },
  ],
  position: { total_outstanding_minor: 1_040_000, principal_outstanding_minor: 800_000, arrears_minor: 520_000, days_past_due: 47, next_due_date: '2026-03-15', total_paid_minor: 520_000, settled: false },
};

function fakeApi(over: Partial<Record<keyof ExistingLoansApi, unknown>> = {}) {
  const api = {
    goLive: vi.fn(async () => ({ go_live_date: '2026-04-01', source: 'set' })),
    products: vi.fn(async () => [{ id: 'p1', code: 'LOAN1', name: 'Test monthly loan', status: 'active', current_version: { currency: 'UGX', term_unit: 'month', default_term_count: 3 } }]),
    check: vi.fn(async () => preview),
    record: vi.fn(async () => ({ ...preview, loan_id: 'l1', loan_no: 'LN000101', status: 'brought_in', approval_request_id: 'a1' })),
    list: vi.fn(async () => []),
    get: vi.fn(),
    cancel: vi.fn(),
    askAgain: vi.fn(),
    importCsv: vi.fn(),
    ...over,
  };
  return api as unknown as ExistingLoansApi & Record<string, ReturnType<typeof vi.fn>>;
}

function mount(node: ReactElement, me: Me = manager) {
  const members = createMembersMock([borrower]);
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LendingApiContext.Provider value={{ members: members.api, collateral: createCollateralMock([]).api }}>
          <StaffContext.Provider value={{ me, branch: BRANCH }}>{node}</StaffContext.Provider>
        </LendingApiContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

async function fillTheLoan() {
  type(/^Find the member/, 'Borrower 21');
  fireEvent.click(await screen.findByRole('button', { name: /Test Borrower 21/ }, { timeout: 3000 }));
  await screen.findByRole('option', { name: /LOAN1/ });
  type('Loan product', 'p1');
  type('Amount lent', '1,200,000');
  type('Date the money was given out', '2026-01-15');
  fireEvent.click(screen.getByRole('button', { name: 'Add a repayment' }));
  type('Date paid', '2026-02-15');
  type('Amount paid', '520,000');
  type('Receipt number or note', 'Receipt 101');
}

describe('recording an existing loan', () => {
  it('shows a warning from the check without stopping the record', async () => {
    const warning = 'Loan LN000099 of this member for the same amount and date is already in the app. Check it is not the same loan entered twice.';
    const api = fakeApi({ check: vi.fn(async () => ({ ...preview, warnings: [warning] })) });
    mount(<ExistingLoanForm api={api} />);
    await fillTheLoan();
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    await screen.findByText(warning);
    expect(screen.getByRole('button', { name: 'Record and send for approval' })).toBeTruthy();
  });

  it('checks first, shows the true position from the real date, then records it once with an Idempotency-Key', async () => {
    const api = fakeApi();
    mount(<ExistingLoanForm api={api} />);
    await fillTheLoan();
    expect(screen.queryByRole('button', { name: /Record and send/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    await screen.findByText('What it would be');
    expect(api.check).toHaveBeenCalledWith({
      member_id: borrower.id,
      product_id: 'p1',
      principal_minor: 1_200_000,
      disbursement_date: '2026-01-15',
      term_count: undefined,
      agreed_total_minor: undefined,
      register_ref: undefined,
      repayments: [{ value_date: '2026-02-15', amount_minor: 520_000, payment_method_key: 'cash', note: 'Receipt 101' }],
    });
    expect(screen.getByText('47')).toBeTruthy();
    expect(screen.getAllByText('2026-03-15').length).toBeGreaterThan(0);
    // A change after the check hides the record button until the loan is checked again.
    type('Amount paid', '500,000');
    expect(screen.queryByRole('button', { name: /Record and send/ })).toBeNull();
    type('Amount paid', '520,000');
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Record and send for approval' }));
    await screen.findByText(/Recorded LN000101/);
    expect(api.record).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.record).mock.calls[0]?.[1]).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('puts the server\'s refusal on the field it is about, in its words', async () => {
    const api = fakeApi({
      check: vi.fn(async () => {
        throw new ExistingLoanError('Only a loan given out before the go-live date can be brought in.', 422, 'disbursement_not_before_go_live', {
          disbursement_date: 'Only a loan given out before the go-live date can be brought in. Record a new loan the usual way.',
        });
      }),
    });
    mount(<ExistingLoanForm api={api} />);
    await fillTheLoan();
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    await screen.findByText(/Record a new loan the usual way/);
    expect(screen.getByLabelText('Date the money was given out').getAttribute('aria-invalid')).toBe('true');
    expect(screen.queryByRole('button', { name: /Record and send/ })).toBeNull();
  });

  it('sends nothing while an entry cannot be right', async () => {
    const api = fakeApi();
    mount(<ExistingLoanForm api={api} />);
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    await screen.findByText('Choose the member.');
    expect(screen.getByText('Enter the amount lent.')).toBeTruthy();
    expect(api.check).not.toHaveBeenCalled();
  });

  it('is only for those who may bring loans in, and says the go-live date', async () => {
    mount(<ExistingLoansPage api={fakeApi()} />, officer);
    expect(screen.getByText(/^You do not have access to this page\. The owner or an administrator/)).toBeTruthy();
    cleanup();
    mount(<ExistingLoansPage api={fakeApi()} />);
    await screen.findByText(/The go-live date is 2026-04-01/);
    expect(screen.getByRole('link', { name: /Import many loans/ }).getAttribute('href')).toBe('/staff/lending/existing-loans/import');
  });
});

const header = 'kind,ref,member_no,product_code,principal,disbursement_date,term,agreed_total,purpose,repayment_date,amount,method,note';

describe('importing existing loans from a file', () => {
  it('shows the rows with a problem and records nothing while one has a problem', async () => {
    const withErrors: ExistingLoanImportResult = {
      dry_run: true, rows_read: 2, loans: 1, repayments: 1, added: 0, skipped: 0, errors: 2,
      rows: [
        { line: 2, ref: 'REG-001', outcome: 'error', message: 'A repayment row of this loan has an error.' },
        { line: 3, ref: 'REG-001', outcome: 'error', message: 'The repayment date must be year-month-day, for example 2026-04-30.' },
      ],
    };
    const api = fakeApi({ importCsv: vi.fn(async () => withErrors) });
    mount(<ExistingLoansImport api={api} />);
    type('Or paste the rows here', `${header}\nloan,REG-001,M000201,LOAN1,1000000,2026-01-15,3,,,,,,\nrepayment,REG-001,,,,,,,,15/02/2026,400000,cash,\n`);
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));
    await screen.findByText(/must be year-month-day/);
    expect(api.importCsv).toHaveBeenCalledWith(expect.stringContaining('REG-001'), true, expect.any(String));
    expect(screen.getByText(/Nothing is recorded while a row has a problem/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Record .* loans/ })).toBeNull();
  });

  it('records the loans of a clean file after the check, each waiting for approval', async () => {
    const clean: ExistingLoanImportResult = {
      dry_run: true, rows_read: 2, loans: 1, repayments: 1, added: 1, skipped: 0, errors: 0,
      rows: [{ line: 2, ref: 'REG-001', outcome: 'ready', message: '1 repayment(s); 12 day(s) past due' }, { line: 3, ref: 'REG-001', outcome: 'ready' }],
    };
    const recorded: ExistingLoanImportResult = { ...clean, dry_run: false, rows: [{ line: 2, ref: 'REG-001', outcome: 'added', message: '1 repayment(s); 12 day(s) past due', loan_id: 'l1' }] };
    const importCsv = vi.fn(async (_csv: string, dryRun: boolean) => (dryRun ? clean : recorded));
    mount(<ExistingLoansImport api={fakeApi({ importCsv })} />);
    expect(screen.getByRole('link', { name: 'Download the template' }).getAttribute('href')).toMatch(/^data:text\/csv/);
    type('Or paste the rows here', `${header}\nloan,REG-001,M000201,LOAN1,1000000,2026-01-15,3,,,,,,\nrepayment,REG-001,,,,,,,,2026-02-15,400000,cash,\n`);
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Record 1 loans and send them for approval' }));
    await screen.findByText(/Recorded 1 loans and skipped 0 already in/);
    await waitFor(() => expect(importCsv).toHaveBeenLastCalledWith(expect.any(String), false, expect.any(String)));
    expect(importCsv).toHaveBeenCalledTimes(2);
  });
});
