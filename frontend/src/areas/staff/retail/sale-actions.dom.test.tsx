// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// The record-payment and void actions as a person uses them (issues #326 and #327): who sees them, what the forms refuse, one
// request however often the button is pressed, a retry that repeats its Idempotency-Key, and each refusal in plain words.
// Fabricated names and amounts.

// The first render in a cold worker is slow on a busy machine.
vi.setConfig({ testTimeout: 20000 });

const paySale = vi.fn();
const voidSale = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { paySale: (...a: unknown[]) => paySale(...a), voidSale: (...a: unknown[]) => voidSale(...a) } };
});

const { RetailError } = await import('../../../api/retail');
const { retailMessage } = await import('../../../api/retail-errors');
const { PayFormView, PaySaleAction, VoidFormView, VoidSaleAction } = await import('./sale-actions');
const { SaleDetail } = await import('./sales');

afterEach(() => {
  cleanup();
  paySale.mockReset();
  voidSale.mockReset();
  sessionStorage.clear();
});

const admin = mockMe('admin');
const seller = mockMe('sales');
const branch = admin.branches?.[0]?.id ?? '';
const today = '2026-10-19';
const credit: Sale = { id: 's1', sale_no: 'S-000001', branch_id: branch, sale_date: '2026-10-10', payment_method: 'credit', status: 'completed', buyer_name: 'Test Buyer 01', total_minor: 12000, paid_minor: 2000, balance_minor: 10000, lines: [] };
const unpaid: Sale = { ...credit, paid_minor: 0, balance_minor: 12000 };
const cash: Sale = { ...credit, payment_method: 'cash', paid_minor: 12000, balance_minor: 0 };
const refusal = (code: string, status = 422) => new RetailError(retailMessage({ code }, status), status, code);

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const save = () => screen.getByRole('button', { name: 'Save payment' }) as HTMLButtonElement;
const amount = () => screen.getByLabelText('Amount received') as HTMLInputElement;

describe('recording a payment', () => {
  it('offers the buttons to someone who may sell at the branch, and to nobody else', () => {
    screenOf(seller, <PaySaleAction sale={credit} today={today} />);
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Mark as fully paid' })).toBeTruthy();
    cleanup();
    const noSell = { ...seller, permissions: ['retail.sale.read'], permission_scopes: {} };
    screenOf(noSell, <PaySaleAction sale={credit} today={today} />);
    expect(screen.queryByTestId('pay-sale')).toBeNull();
    cleanup();
    const otherBranch = { ...seller, permission_scopes: { 'retail.sale.create': { all_branches: false, branch_ids: ['some-other-branch'] } } };
    screenOf(otherBranch, <PaySaleAction sale={credit} today={today} />);
    expect(screen.queryByRole('button', { name: 'Record payment' })).toBeNull();
  });

  it('offers nothing on a cash sale, a paid sale or a voided sale', () => {
    for (const sale of [cash, { ...credit, balance_minor: 0, paid_minor: 12000 }, { ...credit, status: 'voided' }]) {
      screenOf(admin, <PaySaleAction sale={sale} today={today} />);
      expect(screen.queryByRole('button', { name: 'Record payment' })).toBeNull();
      cleanup();
    }
  });

  it('keeps an imported sale off the action and says why', () => {
    screenOf(admin, <PaySaleAction sale={{ ...credit, historical: true }} today={today} />);
    expect(screen.queryByRole('button', { name: 'Record payment' })).toBeNull();
    expect(screen.getByTestId('imported-note').textContent).toMatch(/imported from the old records.*accountant/i);
    expect(screen.getByTestId('imported-note').textContent).not.toMatch(/debtor/i);
  });

  it('says it in one short line in a list row', () => {
    screenOf(admin, <PaySaleAction compact sale={{ ...credit, historical: true }} today={today} />);
    expect(screen.getByTestId('imported-note').textContent).toBe('Imported from the old records; record this payment with your accountant.');
  });

  it('starts with the balance and refuses an amount above it, nothing, or a date outside the sale and today', () => {
    screenOf(admin, <PaySaleAction sale={credit} today={today} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    expect(amount().value).toBe('10000');
    expect(save().disabled).toBe(false);
    fireEvent.change(amount(), { target: { value: '10001' } });
    expect(save().disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toMatch(/owes only UGX 10,000/);
    fireEvent.change(amount(), { target: { value: '' } });
    expect(save().disabled).toBe(true);
    fireEvent.change(amount(), { target: { value: '4000' } });
    fireEvent.change(screen.getByLabelText('Date received'), { target: { value: '2026-10-09' } });
    expect(save().disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toMatch(/before the sale/i);
    fireEvent.change(screen.getByLabelText('Date received'), { target: { value: today } });
    expect(save().disabled).toBe(false);
  });

  it('sends one request however many times Save is pressed, then says the sale is paid in part', async () => {
    let finish: (v: unknown) => void = () => undefined;
    paySale.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    screenOf(admin, <PaySaleAction sale={credit} today={today} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    fireEvent.change(amount(), { target: { value: '4000' } });
    fireEvent.change(screen.getByLabelText('Paid by'), { target: { value: 'mobile_money' } });
    fireEvent.click(save());
    fireEvent.submit(screen.getByRole('form', { name: 'Record a payment' }));
    fireEvent.submit(screen.getByRole('form', { name: 'Record a payment' }));
    await waitFor(() => expect(paySale).toHaveBeenCalledTimes(1));
    expect((await screen.findByRole('button', { name: 'Saving' }) as HTMLButtonElement).disabled).toBe(true);
    const [id, body, key] = paySale.mock.calls[0] as [string, Record<string, unknown>, string];
    expect(id).toBe('s1');
    expect(body).toEqual({ amount_minor: 4000, method: 'mobile_money', paid_on: today });
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    finish({ payment: { amount_minor: 4000 }, sale_paid_minor: 6000, sale_balance_minor: 6000 });
    expect((await screen.findByRole('status')).textContent).toBe('Recorded UGX 4,000. Still owes UGX 6,000.');
    expect(screen.queryByLabelText('Amount received')).toBeNull();
  });

  it('marks a sale as fully paid with the balance, whatever else was typed', async () => {
    paySale.mockResolvedValue({ payment: { amount_minor: 12000 }, sale_paid_minor: 12000, sale_balance_minor: 0 });
    screenOf(admin, <PaySaleAction sale={unpaid} today={today} />);
    fireEvent.click(screen.getByRole('button', { name: 'Mark as fully paid' }));
    expect(amount().readOnly).toBe(true);
    expect(amount().value).toBe('12000');
    fireEvent.change(screen.getByLabelText('Paid by'), { target: { value: 'bank' } });
    fireEvent.click(screen.getByRole('button', { name: 'Mark as fully paid' }));
    await waitFor(() => expect(paySale).toHaveBeenCalledTimes(1));
    expect(paySale.mock.calls[0]?.[1]).toEqual({ amount_minor: 12000, method: 'bank', paid_on: today });
    expect((await screen.findByRole('status')).textContent).toBe('Recorded UGX 12,000. This sale is now paid in full.');
  });

  it('says each refusal in plain words and repeats the key on a retry', async () => {
    paySale.mockRejectedValueOnce(refusal('payment_exceeds_balance')).mockRejectedValueOnce(refusal('sale_not_payable')).mockRejectedValueOnce(new Error('the network dropped'));
    screenOf(admin, <PaySaleAction sale={credit} today={today} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    fireEvent.click(save());
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('That is more than the buyer still owes on this sale. Lower the amount.'));
    fireEvent.click(save());
    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/can no longer take a payment.*reloaded/));
    fireEvent.click(save());
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('the network dropped'));
    const keys = paySale.mock.calls.map((c) => c[2] as string);
    expect(keys).toHaveLength(3);
    expect(new Set(keys).size).toBe(1);
  });

  it('takes a fresh key for the next payment', async () => {
    paySale.mockResolvedValue({ payment: { amount_minor: 1000 }, sale_paid_minor: 3000, sale_balance_minor: 9000 });
    screenOf(admin, <PaySaleAction sale={credit} today={today} />);
    for (let i = 0; i < 2; i += 1) {
      fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
      fireEvent.change(amount(), { target: { value: '1000' } });
      fireEvent.click(save());
      await waitFor(() => expect(paySale).toHaveBeenCalledTimes(i + 1));
      await waitFor(() => expect(screen.queryByLabelText('Amount received')).toBeNull());
    }
    expect(paySale.mock.calls[0]?.[2]).not.toBe(paySale.mock.calls[1]?.[2]);
  });

  it('reloads the sale when the balance has moved, and in the fully paid form asks to check the new amount', async () => {
    paySale.mockRejectedValueOnce(refusal('payment_exceeds_balance'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const view = render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: admin, branch }}><PaySaleAction sale={credit} today={today} /></StaffContext.Provider>
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Mark as fully paid' }));
    fireEvent.click(screen.getByRole('button', { name: 'Mark as fully paid' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('The balance has changed; check the new amount.'));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['retail'] });
    // The reloaded sale owes less: the fixed amount follows it, and a part payment typed above it goes back to the balance.
    view.rerender(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: admin, branch }}><PaySaleAction sale={{ ...credit, paid_minor: 6000, balance_minor: 6000 }} today={today} /></StaffContext.Provider>
      </QueryClientProvider>,
    );
    expect(amount().value).toBe('6000');
  });

  it('reloads and says so when the sale can no longer take a payment', async () => {
    paySale.mockRejectedValueOnce(refusal('sale_not_payable'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: admin, branch }}><PaySaleAction sale={credit} today={today} /></StaffContext.Provider>
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    fireEvent.click(save());
    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/can no longer take a payment.*reloaded/));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['retail'] });
  });

  it('keeps a separate key for each way of paying, so a lost answer on a part payment never poisons Mark as fully paid', async () => {
    paySale.mockRejectedValueOnce(new Error('the network dropped')).mockResolvedValue({ payment: { amount_minor: 10000 }, sale_paid_minor: 12000, sale_balance_minor: 0 });
    screenOf(admin, <PaySaleAction sale={credit} today={today} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    fireEvent.change(amount(), { target: { value: '4000' } });
    fireEvent.click(save());
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('the network dropped'));
    // The answer may have been lost: Cancel keeps that draft and its key for the retry.
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Mark as fully paid' }));
    fireEvent.click(screen.getByRole('button', { name: 'Mark as fully paid' }));
    await waitFor(() => expect(paySale).toHaveBeenCalledTimes(2));
    expect(paySale.mock.calls[1]?.[2]).not.toBe(paySale.mock.calls[0]?.[2]);
  });

  it('forgets a form that was cancelled before anything was sent', () => {
    screenOf(admin, <PaySaleAction sale={credit} today={today} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    fireEvent.change(amount(), { target: { value: '1234' } });
    fireEvent.change(screen.getByLabelText('Date received'), { target: { value: '2026-10-12' } });
    expect(sessionStorage.length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(sessionStorage.length).toBe(0);
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    expect(amount().value).toBe('10000');
    expect((screen.getByLabelText('Date received') as HTMLInputElement).value).toBe(today);
  });

  it('resets the form with a new key when the key was already used for a different payment', async () => {
    paySale.mockRejectedValueOnce(refusal('idempotency_key_reused')).mockResolvedValue({ payment: { amount_minor: 10000 }, sale_paid_minor: 12000, sale_balance_minor: 0 });
    screenOf(admin, <PaySaleAction sale={credit} today={today} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    fireEvent.click(save());
    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/form has been reset/));
    expect(screen.getByRole('alert').textContent).not.toMatch(/reload the page/i);
    fireEvent.click(save());
    await waitFor(() => expect(paySale).toHaveBeenCalledTimes(2));
    expect(paySale.mock.calls[1]?.[2]).not.toBe(paySale.mock.calls[0]?.[2]);
  });

  it('never submits a form the rules refuse', () => {
    const onSubmit = vi.fn();
    render(<PayFormView sale={credit} today={today} form={{ amount: '99999', method: 'cash', paidOn: today }} full={false} onChange={() => undefined} onSubmit={onSubmit} onCancel={() => undefined} />);
    expect(screen.getByText('Still owes UGX 10,000.')).toBeTruthy();
    fireEvent.submit(screen.getByRole('form', { name: 'Record a payment' }));
    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe('voiding a sale', () => {
  it('shows the button only to a holder of retail.sale.void at the branch, on a completed sale', () => {
    screenOf(seller, <VoidSaleAction sale={cash} />);
    expect(screen.queryByRole('button', { name: 'Void this sale' })).toBeNull();
    cleanup();
    screenOf(admin, <VoidSaleAction sale={{ ...cash, status: 'voided' }} />);
    expect(screen.queryByRole('button', { name: 'Void this sale' })).toBeNull();
    cleanup();
    const otherBranch = { ...admin, permission_scopes: { ...admin.permission_scopes, 'retail.sale.void': { all_branches: false, branch_ids: ['some-other-branch'] } } };
    screenOf(otherBranch, <VoidSaleAction sale={cash} />);
    expect(screen.queryByRole('button', { name: 'Void this sale' })).toBeNull();
    cleanup();
    screenOf(admin, <VoidSaleAction sale={cash} />);
    expect(screen.getByRole('button', { name: 'Void this sale' })).toBeTruthy();
  });

  it('says a credit sale with a payment cannot be voided, instead of offering the button', () => {
    screenOf(admin, <VoidSaleAction sale={credit} />);
    expect(screen.queryByRole('button', { name: 'Void this sale' })).toBeNull();
    expect(screen.getByTestId('void-blocked').textContent).toMatch(/had a payment/);
  });

  it('asks for a reason, warns what happens, and sends one request', async () => {
    let finish: (v: unknown) => void = () => undefined;
    voidSale.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    screenOf(admin, <VoidSaleAction sale={cash} branchName="Test Branch A" />);
    fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    expect(screen.getByTestId('void-sale-warning').textContent).toMatch(/go back into stock at Test Branch A.*stops counting.*cannot be undone/);
    const go = () => screen.getByRole('button', { name: /^(Void this sale|Voiding)$/ }) as HTMLButtonElement;
    expect(go().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Why is this sale being voided/), { target: { value: 'abcd' } });
    expect(go().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Why is this sale being voided/), { target: { value: '  Rung up twice ' } });
    expect(go().disabled).toBe(false);
    fireEvent.click(go());
    fireEvent.click(go());
    fireEvent.submit(screen.getByRole('form', { name: 'Void this sale' }));
    await waitFor(() => expect(voidSale).toHaveBeenCalledTimes(1));
    expect((await screen.findByRole('button', { name: 'Voiding' }) as HTMLButtonElement).disabled).toBe(true);
    expect(voidSale).toHaveBeenCalledWith('s1', 'Rung up twice');
    finish({ ...cash, status: 'voided' });
    await waitFor(() => expect(screen.queryByLabelText(/Why is this sale being voided/)).toBeNull());
  });

  it('says why a sale cannot be voided, in plain words', async () => {
    voidSale.mockRejectedValueOnce(refusal('sale_has_returns')).mockRejectedValueOnce(refusal('sale_voided', 409)).mockRejectedValueOnce(refusal('permission_denied', 403));
    screenOf(admin, <VoidSaleAction sale={cash} />);
    fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    fireEvent.change(screen.getByLabelText(/Why is this sale being voided/), { target: { value: 'Wrong buyer' } });
    const send = () => fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    send();
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('This sale has returns against it. Void the returns first.'));
    send();
    await waitFor(() => expect(screen.queryByLabelText(/Why is this sale being voided/)).toBeNull());
    cleanup();
    voidSale.mockRejectedValueOnce(refusal('permission_denied', 403));
    screenOf(admin, <VoidSaleAction sale={cash} />);
    fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    fireEvent.change(screen.getByLabelText(/Why is this sale being voided/), { target: { value: 'Wrong buyer' } });
    send();
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('You do not have permission to do this.'));
  });

  it('keeps an imported sale off the action with a short note', () => {
    screenOf(admin, <VoidSaleAction sale={{ ...cash, historical: true }} />);
    expect(screen.queryByRole('button', { name: 'Void this sale' })).toBeNull();
    expect(screen.getByTestId('void-blocked').textContent).toMatch(/imported from the old records.*cannot be voided here/);
  });

  it('treats a void that already went through as done, and hides the button until the sale reads voided', async () => {
    voidSale.mockRejectedValueOnce(refusal('sale_voided', 409));
    screenOf(admin, <VoidSaleAction sale={cash} />);
    fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    fireEvent.change(screen.getByLabelText(/Why is this sale being voided/), { target: { value: 'Wrong buyer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    await waitFor(() => expect(screen.queryByLabelText(/Why is this sale being voided/)).toBeNull());
    expect(screen.queryByRole('button', { name: 'Void this sale' })).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('hides the button after a successful void, before the reloaded sale arrives', async () => {
    voidSale.mockResolvedValue({ ...cash, status: 'voided' });
    screenOf(admin, <VoidSaleAction sale={cash} />);
    fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    fireEvent.change(screen.getByLabelText(/Why is this sale being voided/), { target: { value: 'Wrong buyer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    await waitFor(() => expect(voidSale).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByLabelText(/Why is this sale being voided/)).toBeNull());
    expect(screen.queryByRole('button', { name: 'Void this sale' })).toBeNull();
  });

  it('can be cancelled, and keeps the sale', () => {
    const onCancel = vi.fn();
    render(<VoidFormView onVoid={() => undefined} onCancel={onCancel} />);
    fireEvent.click(screen.getByRole('button', { name: 'Keep the sale' }));
    expect(onCancel).toHaveBeenCalled();
  });

  it('shows a voided sale as Voided with its reason', () => {
    screenOf(admin, <SaleDetail sale={{ ...cash, status: 'voided', void_reason: 'Rung up twice' }} today={today} />);
    expect(screen.getByText('Voided')).toBeTruthy();
    expect(screen.getByText('Voided because')).toBeTruthy();
    expect(screen.getByText('Rung up twice')).toBeTruthy();
  });
});
