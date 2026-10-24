// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// Writing off a debt (issue #340, ADR-043) as a person uses it: who sees the action, what the form refuses, one request however
// often the button is pressed, the two answers (done, waiting for a second administrator) and the refusals in plain words.
// Fabricated names and amounts.

vi.setConfig({ testTimeout: 20000 });

const writeOffSale = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { writeOffSale: (...a: unknown[]) => writeOffSale(...a) } };
});

const { RetailError } = await import('../../../api/retail');
const { retailMessage } = await import('../../../api/retail-errors');
const { WriteOffFormView, WriteOffSaleAction } = await import('./sale-actions');
const { WRITE_OFF_CHECKER, canWriteOff, writeOffWarning, writeOffWords } = await import('./sale-actions-state');
const { saleState, SaleDetail } = await import('./sales');

afterEach(() => {
  cleanup();
  writeOffSale.mockReset();
  sessionStorage.clear();
});

const admin = mockMe('admin');
const seller = mockMe('sales');
const branch = admin.branches?.[0]?.id ?? '';
const today = '2026-10-20';
const owing: Sale = { id: 's1', sale_no: 'S-000001', branch_id: branch, sale_date: '2026-10-10', payment_method: 'credit', status: 'completed', buyer_name: 'Test Buyer 01', total_minor: 12000, paid_minor: 2000, balance_minor: 10000, lines: [] };
const refusal = (code: string, status = 422) => new RetailError(retailMessage({ code }, status), status, code);

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('who is offered the write-off', () => {
  it('is an administrator with retail.credit.manage, on a live credit sale that still owes', () => {
    screenOf(admin, <WriteOffSaleAction sale={owing} />);
    expect(screen.getByRole('button', { name: 'Write off' })).toBeTruthy();
  });

  it('is nobody else, and not a holder of retail.credit.manage alone', () => {
    screenOf(seller, <WriteOffSaleAction sale={owing} />);
    expect(screen.queryByRole('button', { name: 'Write off' })).toBeNull();
    cleanup();
    const manageOnly = { ...admin, permissions: (admin.permissions ?? []).filter((p) => p !== 'retail.credit.write_off'), permission_scopes: Object.fromEntries(Object.entries(admin.permission_scopes ?? {}).filter(([p]) => p !== 'retail.credit.write_off')) };
    screenOf(manageOnly, <WriteOffSaleAction sale={owing} />);
    expect(screen.queryByRole('button', { name: 'Write off' })).toBeNull();
  });

  it('is not offered on a cash sale, a paid sale, a voided sale, an imported sale or one already written off', () => {
    const none: Sale[] = [
      { ...owing, payment_method: 'cash', paid_minor: 12000, balance_minor: 0 },
      { ...owing, paid_minor: 12000, balance_minor: 0 },
      { ...owing, status: 'voided' },
      { ...owing, historical: true },
      { ...owing, paid_minor: 12000, balance_minor: 0, write_off: { written_off_on: today, unpaid_minor: 10000, reason: 'Buyer left town' } },
    ];
    for (const sale of none) {
      expect(canWriteOff(sale)).toBe(false);
      screenOf(admin, <WriteOffSaleAction sale={sale} />);
      expect(screen.queryByRole('button', { name: 'Write off' })).toBeNull();
      cleanup();
    }
    expect(canWriteOff(owing)).toBe(true);
  });
});

describe('the form', () => {
  it('asks for a reason, says what happens in plain words and sends one request', async () => {
    let finish: (v: unknown) => void = () => undefined;
    writeOffSale.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    screenOf(admin, <WriteOffSaleAction sale={owing} />);
    fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
    expect(screen.getByTestId('write-off-warning').textContent).toMatch(/UGX 10,000 owed will be cleared as a bad debt.*stays on record.*Written off.*cannot be undone/);
    const go = () => screen.getByRole('button', { name: /^(Write off|Writing off)$/ }) as HTMLButtonElement;
    expect(go().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Why will this buyer not pay/), { target: { value: 'abcd' } });
    expect(go().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Why will this buyer not pay/), { target: { value: '  Buyer left town ' } });
    expect(ready(go())).toBe(true);
    fireEvent.click(go());
    fireEvent.click(go());
    fireEvent.submit(screen.getByRole('form', { name: 'Write off this debt' }));
    await waitFor(() => expect(writeOffSale).toHaveBeenCalledTimes(1));
    expect(writeOffSale.mock.calls[0]?.[0]).toBe('s1');
    expect(writeOffSale.mock.calls[0]?.[1]).toBe('Buyer left town');
    expect(typeof writeOffSale.mock.calls[0]?.[2]).toBe('string');
    finish({ executed: true, write_off: { unpaid_minor: 10000 } });
    expect((await screen.findByText(/Cleared UGX 10,000 owed as a bad debt/)).textContent).toBeTruthy();
  });

  it('says when the write-off waits for a second administrator', async () => {
    writeOffSale.mockResolvedValue({ executed: false, approval_id: 'a1' });
    screenOf(admin, <WriteOffSaleAction sale={owing} />);
    fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
    fireEvent.change(screen.getByLabelText(/Why will this buyer not pay/), { target: { value: 'Buyer left town' } });
    fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
    expect(await screen.findByText(/Sent to a second administrator to approve/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Write off' })).toBeNull();
  });

  it('tells a refusal in plain words and keeps the form', async () => {
    writeOffSale.mockRejectedValueOnce(refusal('already_written_off', 409));
    screenOf(admin, <WriteOffSaleAction sale={owing} />);
    fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
    fireEvent.change(screen.getByLabelText(/Why will this buyer not pay/), { target: { value: 'Buyer left town' } });
    fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/changed while you were looking/);
  });

  it('hides the button and says so while the write-off waits for a second administrator', async () => {
    writeOffSale.mockRejectedValueOnce(refusal('approval_already_pending', 409));
    screenOf(admin, <WriteOffSaleAction sale={owing} />);
    fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
    fireEvent.change(screen.getByLabelText(/Why will this buyer not pay/), { target: { value: 'Buyer left town' } });
    fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
    expect((await screen.findByTestId('write-off-pending')).textContent).toMatch(/waiting for a second administrator/);
    expect(screen.queryByRole('button', { name: 'Write off' })).toBeNull();
  });

  it('can be left without writing anything off', () => {
    screenOf(admin, <WriteOffSaleAction sale={owing} />);
    fireEvent.click(screen.getByRole('button', { name: 'Write off' }));
    fireEvent.click(screen.getByRole('button', { name: 'Keep the debt' }));
    expect(screen.getByRole('button', { name: 'Write off' })).toBeTruthy();
    expect(writeOffSale).not.toHaveBeenCalled();
  });

  it('renders as a form with a disabled button until the reason is long enough', () => {
    render(<WriteOffFormView owed={5000} reason="" onReason={() => undefined} onWriteOff={() => undefined} onCancel={() => undefined} />);
    expect((screen.getByRole('button', { name: 'Write off' }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('the words', () => {
  it('warns with the amount and says the sale is kept', () => {
    expect(writeOffWarning(2500)).toContain('UGX 2,500');
    expect(writeOffWarning(2500)).toMatch(/not deleted|stays on record/);
    expect(writeOffWarning(2500)).toContain('the day it is approved');
    expect(WRITE_OFF_CHECKER).toContain('only active administrator');
    expect(`${writeOffWarning(1)} ${writeOffWords({ executed: false })}`).not.toMatch(/[\u2013\u2014]/);
  });

  it('shows a written off sale as Written off, with when and why, and no balance', () => {
    const sale: Sale = { ...owing, paid_minor: 12000, balance_minor: 0, write_off: { written_off_on: today, unpaid_minor: 10000, reason: 'Buyer left town' } };
    expect(saleState(sale, today).label).toBe('Written off');
    screenOf(admin, <SaleDetail sale={sale} today={today} />);
    expect(screen.getAllByText('Written off').length).toBeGreaterThan(0);
    expect(screen.getByText(/Buyer left town/)).toBeTruthy();
  });
});
