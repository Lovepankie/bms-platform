// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PaymentIntent } from '../../../api/payments';
import { mockMe } from '../../../api/retail-mock';
import type { SavingsAccount } from '../../../api/savings';
import { StaffContext } from '../context';

// "Collect by mobile money or card" on a savings account (issue #219) in a browser, with the API clients replaced: the form refuses a
// number that is not a Ugandan mobile before anything is sent, starts a savings deposit for the account, shows the link with a QR code,
// follows the payment to a recorded deposit with its receipt number and the provider's payment code, and says plainly where a deposit
// the account could not take is held. All names, numbers and links are invented.

const pay = { startSavingsDeposit: vi.fn(), intent: vi.fn(), cancelIntent: vi.fn() };
const sav = { listTransactions: vi.fn() };

vi.mock('../../../api/payments', async (original) => {
  const real = await original<typeof import('../../../api/payments')>();
  const forward = (name: string) => (...args: unknown[]) => ((pay as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, payments: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});
vi.mock('../../../api/savings', async (original) => {
  const real = await original<typeof import('../../../api/savings')>();
  return { ...real, savings: new Proxy({}, { get: (_t, name: string) => (name === 'listTransactions' ? sav.listTransactions : (real.savings as never)[name]) }) };
});

const { SavingsCollectPanel } = await import('./collect');

const account: SavingsAccount = {
  id: '44444444-4444-4444-8444-444444444444',
  account_no: 'SV000001',
  status: 'active',
  currency: 'UGX',
  balance_minor: 0,
};

const pending: PaymentIntent = {
  id: '22222222-2222-4222-8222-222222222222',
  status: 'pending',
  amount_minor: 50_000,
  currency: 'UGX',
  payer_phone: '********0219',
  redirect_url: 'https://pay.example.test/pay?OrderTrackingId=def',
  flags: [],
};

function shell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: { ...mockMe('admin'), permissions: ['lending.savings.read', 'core.payments.collect'] }, branch: null }}>
        <SavingsCollectPanel account={account} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

function fill() {
  fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: '50000' } });
  fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '0700000219' } });
  fireEvent.click(screen.getByRole('button', { name: 'Make the payment link' }));
}

beforeEach(() => {
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  Object.values(pay).forEach((m) => m.mockReset());
  sav.listTransactions.mockReset();
});

describe('starting a savings collection', () => {
  it('says the money goes to the business\'s own account and waits for an amount and a valid mobile number', () => {
    shell();
    expect(screen.getAllByText(/own payment account/).length).toBeGreaterThan(0);
    expect(screen.getByText(/the savings account/)).toBeTruthy();
    const button = screen.getByRole('button', { name: 'Make the payment link' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: '50000' } });
    fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '12345' } });
    expect(button.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Payer's mobile number"), { target: { value: '0700000219' } });
    expect(button.disabled).toBe(false);
  });

  it('starts a deposit for the account, shows the link and the QR code, and removes the payer number from the tab', async () => {
    pay.startSavingsDeposit.mockResolvedValue(pending);
    pay.intent.mockResolvedValue(pending);
    shell();
    fill();
    await waitFor(() => expect(pay.startSavingsDeposit).toHaveBeenCalledTimes(1));
    expect(pay.startSavingsDeposit.mock.calls[0]?.slice(0, 3)).toEqual([account.id, 50_000, '0700000219']);
    expect(await screen.findByText(/Waiting for the payer to pay/)).toBeTruthy();
    expect(screen.getByRole('img', { name: 'QR code of the payment link' })).toBeTruthy();
    expect(JSON.stringify(Object.entries(sessionStorage))).not.toContain('0700000219');
  });

  it('shows the refusal for a business without a verified own account in plain words and makes no link', async () => {
    const { PaymentsError } = await import('../../../api/payments');
    pay.startSavingsDeposit.mockRejectedValue(new PaymentsError('This business has not connected and tested its own payment account yet, so nothing can be collected.', 422, 'merchant_account_not_verified'));
    shell();
    fill();
    expect(await screen.findByText(/has not connected and tested its own payment account/)).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'QR code of the payment link' })).toBeNull();
  });
});

describe('following the payment', () => {
  it('shows the receipt number and the payment code once the deposit is recorded', async () => {
    pay.startSavingsDeposit.mockResolvedValue(pending);
    pay.intent.mockResolvedValue({ ...pending, status: 'booked', booked_transaction_id: '55555555-5555-4555-8555-555555555555', confirmation_code: 'CONF219', paid_amount_minor: 50_000 });
    sav.listTransactions.mockResolvedValue({ items: [{ id: '55555555-5555-4555-8555-555555555555', txn_type: 'deposit', receipt_no: 'RC-HQ-000007', amount_minor: 50_000, currency: 'UGX' }] });
    shell();
    fill();
    expect(await screen.findByText('RC-HQ-000007', undefined, { timeout: 6000 })).toBeTruthy();
    expect(screen.getByText('Paid and recorded on the account.')).toBeTruthy();
    expect(screen.getByText('CONF219')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Collect another payment' })).toBeTruthy();
  }, 10000);

  it('says plainly that a deposit a frozen account could not take is held as an unallocated receipt', async () => {
    pay.startSavingsDeposit.mockResolvedValue({ ...pending, status: 'booked_unallocated', refusal_code: 'account_frozen', redirect_url: undefined });
    shell();
    fill();
    expect(await screen.findByText(/the account is frozen/)).toBeTruthy();
    expect(screen.getByText(/unallocated receipt/)).toBeTruthy();
  });
});
