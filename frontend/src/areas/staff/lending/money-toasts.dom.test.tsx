// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Investment } from '../../../api/investments';
import type { Loan } from '../../../api/lending';
import type { SavingsAccount } from '../../../api/savings';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #824, review of #828: the lending money forms say it in the toast after a save that went through, and say nothing in
// a success toast when it waits for a checker (the screen in place of the form says so). Each pattern once each way: an
// answer whose `executed` the client reads (funding, disbursement), one the form builds itself (a savings deposit, a
// withdrawal that waits), and a payment that is never sent for approval. The clients are replaced; every figure is invented.

vi.setConfig({ testTimeout: 20000 });

const { calls, replaced } = vi.hoisted(() => {
  const fns = { fund: vi.fn(), payReturn: vi.fn(), deposit: vi.fn(), withdraw: vi.fn(), disburse: vi.fn() };
  /** The real client with the named calls replaced. */
  const swap = (real: object, names: (keyof typeof fns)[]) =>
    new Proxy({}, { get: (_t, name: string) => ((names as string[]).includes(name) ? fns[name as keyof typeof fns] : (real as Record<string, unknown>)[name]) });
  return { calls: fns, replaced: swap };
});

vi.mock('../../../api/investments', async (original) => {
  const real = await original<typeof import('../../../api/investments')>();
  return { ...real, investments: replaced(real.investments, ['fund', 'payReturn']) };
});
vi.mock('../../../api/savings', async (original) => {
  const real = await original<typeof import('../../../api/savings')>();
  return { ...real, savings: replaced(real.savings, ['deposit', 'withdraw']) };
});
vi.mock('../../../api/lending', async (original) => {
  const real = await original<typeof import('../../../api/lending')>();
  return { ...real, lending: replaced(real.lending, ['disburse']) };
});

const { FundForm, PayForm } = await import('./investment-actions');
const { MoneyForm } = await import('./savings-forms');
const { DisburseForm } = await import('./actions');
const { DoneToast, doneToasts } = await import('../retail/toast');

afterEach(() => {
  cleanup();
  Object.values(calls).forEach((f) => f.mockReset());
  sessionStorage.clear();
});

const me = { ...mockMe('admin'), modules: ['lending'] };
const inv: Investment = { id: 'iv-1', account_no: 'IV-000001', currency: 'UGX', principal_minor: 500_000, status: 'pending' };
const account: SavingsAccount = { id: 'sa-1', account_no: 'SV-000001', currency: 'UGX', balance_minor: 80_000, available_minor: 80_000, status: 'active' };
const loan: Loan = { id: 'ln-1', loan_no: 'LN-000001', currency: 'UGX', approved_principal_minor: 900_000, status: 'approved' };

function screenOf(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() })}>
      <StaffContext.Provider value={{ me, branch: null }}>{node}</StaffContext.Provider>
      <DoneToast />
    </QueryClientProvider>,
  );
}

const successToasts = () => Array.from(document.querySelectorAll('.toast-success')).map((t) => t.textContent ?? '');

/** No success toast, asserted after the screen in place of the form has shown and the toast has had time to come. */
async function noSuccessToast(shown: string | RegExp) {
  await screen.findByText(shown);
  await new Promise((done) => setTimeout(done, 100));
  expect(successToasts()).toEqual([]);
}

/** A savings deposit or withdrawal: the amount, Review, then Confirm. */
function moneyForm(kind: 'deposit' | 'withdraw', amount: string) {
  screenOf(<MoneyForm account={account} kind={kind} />);
  fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: amount } });
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^Review ${kind === 'deposit' ? 'deposit' : 'withdrawal'}`) }));
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^Confirm ${kind === 'deposit' ? 'deposit' : 'withdrawal'}`) }));
}

describe('the lending money forms say it in a toast only when it went through (#824)', () => {
  it('Record funding that went through says Investment funded', async () => {
    calls.fund.mockResolvedValue({ executed: true, outcome: { executed: true } });
    screenOf(<FundForm inv={inv} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record funding' }));
    await waitFor(() => expect(successToasts()).toEqual(['Investment funded']));
  });

  it('Record funding that waits for a checker says so on screen, with no success toast', async () => {
    calls.fund.mockResolvedValue({ executed: false, outcome: { executed: false, approval_request_id: 'ap-1' } });
    screenOf(<FundForm inv={inv} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record funding' }));
    await noSuccessToast(/Sent for approval/);
  });

  it('Pay now says Return paid, a payment having no approval step', async () => {
    calls.payReturn.mockResolvedValue({ investment: inv, transaction: { amount_minor: 20_000, receipt_no: 'PV-000001' } });
    screenOf(<PayForm inv={inv} kind="pay_return" />);
    fireEvent.click(screen.getByRole('button', { name: 'Pay now' }));
    await waitFor(() => expect(successToasts()).toEqual(['Return paid']));
  });

  it('a savings deposit says Deposit recorded beside its receipt', async () => {
    calls.deposit.mockResolvedValue({ transaction: { amount_minor: 30_000, receipt_no: 'RC-000001' }, account: { ...account, balance_minor: 110_000 } });
    moneyForm('deposit', '30000');
    await waitFor(() => expect(successToasts()).toEqual(['Deposit recorded']));
    expect(screen.getByRole('status', { name: 'Recorded' })).toBeTruthy();
  });

  it('a savings withdrawal that waits for a checker raises no success toast', async () => {
    calls.withdraw.mockResolvedValue({ executed: false, outcome: { executed: false, approval_request_id: 'ap-2' } });
    moneyForm('withdraw', '50000');
    await noSuccessToast(/Sent for approval/);
  });

  it('Disburse that went through says Loan disbursed, and one that waits says nothing in a toast', async () => {
    calls.disburse.mockResolvedValueOnce({ executed: true, outcome: { executed: true } });
    screenOf(<DisburseForm loan={loan} />);
    fireEvent.click(screen.getByRole('button', { name: 'Disburse' }));
    await waitFor(() => expect(successToasts()).toEqual(['Loan disbursed']));
    cleanup();
    sessionStorage.clear();
    calls.disburse.mockResolvedValueOnce({ executed: false, outcome: { executed: false, approval_request_id: 'ap-3' } });
    screenOf(<DisburseForm loan={loan} />);
    fireEvent.click(screen.getByRole('button', { name: 'Disburse' }));
    await noSuccessToast(/Sent for approval/);
  });
});
