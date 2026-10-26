// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Loan, LoanSchedule } from '../../../api/lending';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Waive fee or interest on a schedule row (issue #265, FR-ARR-04) in a browser, with the lending client replaced: the action
// shows only for someone who may ask for a waiver and only on a row with fees or interest still due; the form refuses an amount
// above what is due before anything is sent; the review shows who asks, for whom, how much and why; sending posts the chosen
// component, amount and reason once and says plainly that it waits for the accountant. All names and figures are invented.

const lend = { getSchedule: vi.fn(), listTransactions: vi.fn(), waiveItem: vi.fn() };

vi.mock('../../../api/lending', async (original) => {
  const real = await original<typeof import('../../../api/lending')>();
  return { ...real, lending: new Proxy({}, { get: (_t, name: string) => (name in lend ? lend[name as keyof typeof lend] : (real.lending as never)[name]) }) };
});

const { LoanView } = await import('./loan');

const loan: Loan = {
  id: '11111111-1111-4111-8111-111111111111',
  loan_no: 'LN-HQ-000007',
  member_no: 'M-0042',
  status: 'active',
  currency: 'UGX',
  balances: { total_outstanding_minor: 1_590_000 },
};

const schedule: LoanSchedule = {
  currency: 'UGX',
  items: [
    { no: 1, due_date: '2026-11-23', principal_due_minor: 400_000, interest_due_minor: 120_000, fees_due_minor: 10_000, total_due_minor: 530_000, total_paid_minor: 530_000, outstanding_minor: 0, status: 'paid', interest_outstanding_minor: 0, fees_outstanding_minor: 0 },
    { no: 2, due_date: '2026-12-23', principal_due_minor: 400_000, interest_due_minor: 120_000, fees_due_minor: 10_000, total_due_minor: 530_000, total_paid_minor: 0, outstanding_minor: 530_000, status: 'pending', interest_outstanding_minor: 120_000, fees_outstanding_minor: 10_000 },
  ],
};

const MANAGER = ['lending.loans.read', 'lending.charges.waive_request', 'lending.charges.waive_approve'];

function shell(permissions: string[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const me = { ...mockMe('admin'), full_name: 'Test Manager 01', modules: ['lending'], permissions, permission_scopes: undefined };
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: null }}>
        <LoanView loan={loan} memberName="Test Borrower 42" />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  sessionStorage.clear();
  lend.getSchedule.mockResolvedValue(schedule);
  lend.listTransactions.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  Object.values(lend).forEach((m) => m.mockReset());
});

describe('Waive fee or interest', () => {
  it('shows only for someone who may ask, and only on a row with something still due', async () => {
    shell(MANAGER);
    const buttons = await screen.findAllByRole('button', { name: /^Waive fee or interest on item/ });
    expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual(['Waive fee or interest on item 2']);
    cleanup();
    shell(['lending.loans.read']);
    await screen.findByText('2026-12-23');
    expect(screen.queryByRole('button', { name: /^Waive fee or interest/ })).toBeNull();
  });

  it('refuses more than is due, reviews who, how much and why, then sends it once', async () => {
    lend.waiveItem.mockResolvedValue({ executed: false, outcome: { executed: false, approval_request_id: 'a1' } });
    shell(MANAGER);
    fireEvent.click(await screen.findByRole('button', { name: 'Waive fee or interest on item 2' }));
    const sheet = await screen.findByRole('dialog');
    fireEvent.click(within(sheet).getByRole('radio', { name: /Fees/ }));
    fireEvent.change(within(sheet).getByLabelText(/Amount to waive/), { target: { value: '12000' } });
    fireEvent.change(within(sheet).getByLabelText(/Why/), { target: { value: 'The fee was charged twice' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Check the waiver' }));
    expect(await within(sheet).findByText('At most UGX 10,000 of the fees are still due.')).toBeTruthy();
    expect(lend.waiveItem).not.toHaveBeenCalled();

    // A press holds its button for two ticks (BusyButton); a person corrects the amount well after that.
    await new Promise((resolve) => setTimeout(resolve, 10));
    fireEvent.change(within(sheet).getByLabelText(/Amount to waive/), { target: { value: '10000' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Check the waiver' }));
    const review = await screen.findByRole('dialog', { name: 'Check the waiver' });
    const facts = within(review).getByLabelText('The waiver');
    expect(facts.textContent).toContain('Test Borrower 42 (M-0042)');
    expect(facts.textContent).toContain('LN-HQ-000007');
    expect(facts.textContent).toContain('Test Manager 01');
    expect(facts.textContent).toContain('The fee was charged twice');
    expect(within(review).getByText('Fees to waive')).toBeTruthy();
    expect(within(review).getAllByText('UGX 10,000').length).toBeGreaterThan(0);
    expect(within(review).getByText(/accountant's permission approves it before anything changes/)).toBeTruthy();

    fireEvent.click(within(review).getByRole('button', { name: 'Send waiver of UGX 10,000' }));
    await waitFor(() => expect(lend.waiveItem).toHaveBeenCalledTimes(1));
    const [loanId, itemNo, body, key] = lend.waiveItem.mock.calls[0] as [string, number, unknown, string];
    expect([loanId, itemNo, body]).toEqual([loan.id, 2, { component: 'fee', amount_minor: 10_000, reason: 'The fee was charged twice' }]);
    expect(key).toMatch(/.+/);
    expect(await screen.findByText(/Sent for approval. Nothing changes until someone with the accountant's permission approves it/)).toBeTruthy();
  });

  it('says it is waived when it took effect at once', async () => {
    lend.waiveItem.mockResolvedValue({ executed: true, outcome: { executed: true } });
    shell([...MANAGER, 'lending.charges.waive_fee_interest']);
    fireEvent.click(await screen.findByRole('button', { name: 'Waive fee or interest on item 2' }));
    const sheet = await screen.findByRole('dialog');
    fireEvent.change(within(sheet).getByLabelText(/Amount to waive/), { target: { value: '5000' } });
    fireEvent.change(within(sheet).getByLabelText(/Why/), { target: { value: 'Goodwill after a late disbursement' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Check the waiver' }));
    const review = await screen.findByRole('dialog', { name: 'Check the waiver' });
    expect(within(review).getByText(/Below the business's approval amount it is waived at once/)).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Send waiver of UGX 5,000' }));
    expect(await screen.findByText('Waived UGX 5,000 of the interest on item 2.')).toBeTruthy();
    expect((lend.waiveItem.mock.calls[0] as unknown[])[2]).toMatchObject({ component: 'interest', amount_minor: 5_000 });
  });
});
