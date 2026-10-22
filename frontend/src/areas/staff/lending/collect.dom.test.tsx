// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Loan } from '../../../api/lending';
import type { PaymentIntent } from '../../../api/payments';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// "Collect by mobile money or card" on a loan (issue #216) in a browser, with the API clients replaced: the form refuses a number that
// is not a Ugandan mobile before anything is sent; a refusal for a business with no verified own account is shown in plain words and
// no link is made; a made link shows the QR code, the open link and a copy button; the screen follows the payment to a recorded
// repayment and shows the receipt number, and says plainly where an unallocated payment is. All names, numbers and links are invented.

const pay = { startLoanRepayment: vi.fn(), intent: vi.fn(), cancelIntent: vi.fn() };
const lend = { listTransactions: vi.fn(), repay: vi.fn() };

vi.mock('../../../api/payments', async (original) => {
  const real = await original<typeof import('../../../api/payments')>();
  const forward = (name: string) => (...args: unknown[]) => ((pay as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, payments: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});
vi.mock('../../../api/lending', async (original) => {
  const real = await original<typeof import('../../../api/lending')>();
  return { ...real, lending: new Proxy({}, { get: (_t, name: string) => (name in lend ? lend[name as keyof typeof lend] : (real.lending as never)[name]) }) };
});

const { PaymentsError } = await import('../../../api/payments');
const { CollectPanel } = await import('./collect');
const { RepaymentForm } = await import('./actions');

const loan: Loan = {
  id: '11111111-1111-4111-8111-111111111111',
  loan_no: 'LN-HQ-000001',
  status: 'active',
  currency: 'UGX',
  balances: { arrears_minor: 0, total_outstanding_minor: 1_560_000 },
};
const schedule = { items: [{ no: 1, outstanding_minor: 520_000 }] };

const pending: PaymentIntent = {
  id: '22222222-2222-4222-8222-222222222222',
  status: 'pending',
  amount_minor: 520_000,
  currency: 'UGX',
  payer_phone: '********0216',
  redirect_url: 'https://pay.example.test/pay?OrderTrackingId=abc',
  flags: [],
};

function shell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: { ...mockMe('admin'), permissions: ['lending.loans.read', 'core.payments.collect'] }, branch: null }}>
        <CollectPanel loan={loan} schedule={schedule} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  Object.values(pay).forEach((m) => m.mockReset());
  lend.listTransactions.mockReset();
  lend.repay.mockReset();
});

describe('starting a collection', () => {
  it('offers the instalment due, says the money goes to the business\'s own account and waits for a valid mobile number', async () => {
    shell();
    expect((screen.getByLabelText(/Amount/) as HTMLInputElement).value).toBe('520000');
    expect(screen.getAllByText(/own payment account/).length).toBeGreaterThan(0);
    const button = screen.getByRole('button', { name: 'Make the payment link' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '12345' } });
    expect(button.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '0700000216' } });
    expect(button.disabled).toBe(false);
  });

  it('shows a refusal for a business without a verified own account in plain words and makes no link', async () => {
    pay.startLoanRepayment.mockRejectedValue(new PaymentsError('This business has not connected and tested its own payment account yet, so nothing can be collected.', 422, 'merchant_account_not_verified'));
    shell();
    fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '0700000216' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make the payment link' }));
    await waitFor(() => expect(pay.startLoanRepayment).toHaveBeenCalledTimes(1));
    expect(pay.startLoanRepayment.mock.calls[0]?.slice(0, 3)).toEqual([loan.id, 520_000, '0700000216']);
    expect(await screen.findByText(/has not connected and tested its own payment account/)).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'QR code of the payment link' })).toBeNull();
    expect(screen.queryByText(/Waiting for the payer/)).toBeNull();
  });

  it('shows the link, a QR code and a copy button once the order exists, and can cancel while unpaid', async () => {
    pay.startLoanRepayment.mockResolvedValue(pending);
    pay.intent.mockResolvedValue(pending);
    pay.cancelIntent.mockResolvedValue({ ...pending, status: 'cancelled', redirect_url: undefined });
    shell();
    fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '0700000216' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make the payment link' }));
    expect(await screen.findByText(/Waiting for the payer to pay/)).toBeTruthy();
    expect(screen.getByRole('img', { name: 'QR code of the payment link' })).toBeTruthy();
    expect((screen.getByRole('link', { name: 'Open the payment page' }) as HTMLAnchorElement).href).toBe(pending.redirect_url);
    expect(screen.getByRole('button', { name: 'Copy the link' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel this payment' }));
    expect(await screen.findByText('This payment was cancelled.')).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'QR code of the payment link' })).toBeNull();
  });
});

describe('following the payment', () => {
  it('shows the receipt number of the repayment once it is recorded', async () => {
    pay.startLoanRepayment.mockResolvedValue(pending);
    pay.intent.mockResolvedValue({ ...pending, status: 'booked', booked_transaction_id: '33333333-3333-4333-8333-333333333333', paid_amount_minor: 520_000 });
    lend.listTransactions.mockResolvedValue([{ id: '33333333-3333-4333-8333-333333333333', txn_type: 'repayment', receipt_no: 'RC-HQ-000042', amount_minor: 520_000, currency: 'UGX' }]);
    shell();
    fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '0700000216' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make the payment link' }));
    // The screen asks again after 3 seconds.
    expect(await screen.findByText('RC-HQ-000042', undefined, { timeout: 6000 })).toBeTruthy();
    expect(screen.getByText('Paid and recorded on the loan.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Collect another payment' })).toBeTruthy();
  }, 10000);

  it('says plainly that a payment the loan could not take is held as an unallocated receipt', async () => {
    pay.startLoanRepayment.mockResolvedValue({ ...pending, status: 'booked_unallocated', refusal_code: 'invalid_status_transition', redirect_url: undefined });
    shell();
    fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '0700000216' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make the payment link' }));
    expect(await screen.findByText(/unallocated receipt/)).toBeTruthy();
  });
});

describe('recording a repayment by hand', () => {
  it('shows the record button working while the request runs and sends a double click once', async () => {
    let answer: (r: unknown) => void = () => undefined;
    lend.repay.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: { ...mockMe('admin'), permissions: ['lending.loans.read', 'lending.loans.repay'] }, branch: null }}>
          <RepaymentForm loan={loan} onDone={() => undefined} />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: '52000' } });
    const button = screen.getByRole('button', { name: 'Record repayment' }) as HTMLButtonElement;
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(button.textContent).toBe('Record repayment');
    expect(button.querySelector('.busy-spinner')).not.toBeNull();
    fireEvent.click(button);
    expect(lend.repay).toHaveBeenCalledTimes(1);
    answer({ transaction: { receipt_no: 'RC-HQ-000043', amount_minor: 5_200_000 }, loan_status: 'active' });
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBeNull());
    expect(lend.repay).toHaveBeenCalledTimes(1);
  });
});
