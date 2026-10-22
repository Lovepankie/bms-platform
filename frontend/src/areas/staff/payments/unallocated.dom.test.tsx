// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { UnallocatedReceipt } from '../../../api/payments';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// The "Unallocated payments" screen in a browser (extras slice A5, issue #224), with the API client replaced: the queue shows what
// waits and why in plain words, a cashier can ask to put a payment on a loan or record a refund (each needs a reason and the right
// identifier), the form asks once however often it is submitted, a refusal is shown in words, a reader without the allocate
// permission sees no buttons, and nobody without the read permission sees the page. Every identifier and reference is invented.

const api = { list: vi.fn(), allocate: vi.fn(), refund: vi.fn() };

vi.mock('../../../api/payments', async (original) => {
  const real = await original<typeof import('../../../api/payments')>();
  const forward = (name: string) => (...args: unknown[]) => ((api as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, receipts: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});

const { PaymentsError } = await import('../../../api/payments');
const { UnallocatedPage } = await import('./unallocated');

const receipt: UnallocatedReceipt = {
  id: '11111111-1111-4111-8111-111111111111',
  branch_id: '22222222-2222-4222-8222-222222222222',
  status: 'open',
  amount_minor: 520000,
  currency: 'UGX',
  value_date: '2026-10-16',
  created_at: '2026-10-16T08:00:00Z',
  refusal_code: 'invalid_status_transition',
  confirmation_code: 'CONF-TEST-0001',
  order_reference: 'BMS-ST-00000000000000000000000000000001',
  order_tracking_id: '33333333-3333-4333-8333-333333333333',
  reported_method: 'MTN',
  paid_at: '2026-10-16T07:55:00Z',
  intended_subject: { purpose: 'lending.loan_repayment', subject_type: 'loan', subject_id: '44444444-4444-4444-8444-444444444444' },
  pending_requests: [],
  version: 0,
};
const LOAN = '55555555-5555-4555-8555-555555555555';

function shell(permissions: string[]) {
  const me = { ...mockMe('admin'), permissions };
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/payments/unallocated'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <UnallocatedPage />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const CASHIER = ['core.payments.read', 'core.payments.allocate'];

beforeEach(() => {
  api.list.mockResolvedValue({ items: [receipt] });
});

afterEach(() => {
  cleanup();
  Object.values(api).forEach((m) => m.mockReset());
});

describe('the queue', () => {
  it('shows what waits, why in plain words, and never a phone number', async () => {
    shell(CASHIER);
    expect(await screen.findByText(/The loan was already closed or written off/)).toBeTruthy();
    expect(screen.getByText('CONF-TEST-0001')).toBeTruthy();
    expect(screen.getByText('BMS-ST-00000000000000000000000000000001')).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/\+?2567\d{8}|0700/);
  });

  it('says so when nothing is waiting', async () => {
    api.list.mockResolvedValue({ items: [] });
    shell(CASHIER);
    expect(await screen.findByText('Nothing is waiting.')).toBeTruthy();
  });

  it('offers no buttons to someone who may only read, and nothing to someone who may not read', async () => {
    shell(['core.payments.read']);
    await screen.findByText('CONF-TEST-0001');
    expect(screen.queryByRole('button', { name: 'Put on a loan or account' })).toBeNull();
    cleanup();
    shell(['lending.loans.read']);
    expect(await screen.findByText('You do not have access to this page.')).toBeTruthy();
    expect(api.list).toHaveBeenCalledTimes(1);
  });

  it('says a request is waiting for a second person', async () => {
    api.list.mockResolvedValue({ items: [{ ...receipt, pending_requests: [{ approval_request_id: '66666666-6666-4666-8666-666666666666', action_type: 'unallocated_allocation' }] }] });
    shell(CASHIER);
    expect(await screen.findByText(/waiting for a second person/)).toBeTruthy();
  });
});

describe('asking', () => {
  it('asks to put the payment on a loan, once, only with a valid identifier and a reason', async () => {
    api.allocate.mockResolvedValue({ receipt_id: receipt.id, approval_request_id: '66666666-6666-4666-8666-666666666666' });
    shell(CASHIER);
    fireEvent.click(await screen.findByRole('button', { name: 'Put on a loan or account' }));
    const ask = screen.getByRole('button', { name: 'Ask for approval' }) as HTMLButtonElement;
    expect(ask.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Loan or savings account identifier'), { target: { value: 'not an id' } });
    expect(screen.getByText(/Paste the loan or account identifier/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Loan or savings account identifier'), { target: { value: LOAN } });
    fireEvent.change(screen.getByLabelText('Why'), { target: { value: 'Paid by the borrower\'s sister' } });
    expect(ask.disabled).toBe(false);
    fireEvent.click(ask);
    fireEvent.click(ask);
    await waitFor(() => expect(api.allocate).toHaveBeenCalledTimes(1));
    const [id, purpose, subject, reason, key] = api.allocate.mock.calls[0] as string[];
    expect([id, purpose, subject, reason]).toEqual([receipt.id, 'lending.loan_repayment', LOAN, "Paid by the borrower's sister"]);
    expect(key).toMatch(/^ur-/);
    expect(await screen.findByText(/A second person now decides it/)).toBeTruthy();
  });

  it('records a refund with the way it went and the reference', async () => {
    api.refund.mockResolvedValue({ receipt_id: receipt.id, approval_request_id: '66666666-6666-4666-8666-666666666666' });
    shell(CASHIER);
    fireEvent.click(await screen.findByRole('button', { name: 'Record a refund' }));
    fireEvent.change(screen.getByLabelText('It went back'), { target: { value: 'bank' } });
    fireEvent.change(screen.getByLabelText('Your reference for the refund'), { target: { value: 'BANK-TEST-77' } });
    fireEvent.change(screen.getByLabelText('Why'), { target: { value: 'Paid twice by mistake' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask for approval' }));
    await waitFor(() => expect(api.refund).toHaveBeenCalledTimes(1));
    expect((api.refund.mock.calls[0] as string[]).slice(0, 4)).toEqual([receipt.id, 'bank', 'BANK-TEST-77', 'Paid twice by mistake']);
  });

  it('shows a refusal in words and lets the form be corrected', async () => {
    api.allocate.mockRejectedValue(new PaymentsError('That loan cannot take a payment now.', 422, 'subject_not_payable'));
    shell(CASHIER);
    fireEvent.click(await screen.findByRole('button', { name: 'Put on a loan or account' }));
    fireEvent.change(screen.getByLabelText('Loan or savings account identifier'), { target: { value: LOAN } });
    fireEvent.change(screen.getByLabelText('Why'), { target: { value: 'Paid by a relative' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask for approval' }));
    expect(await screen.findByText('That loan cannot take a payment now.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Ask for approval' }) as HTMLButtonElement).disabled).toBe(false);
  });
});
