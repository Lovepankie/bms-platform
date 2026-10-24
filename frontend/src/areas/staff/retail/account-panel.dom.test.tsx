// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AccountPayment, CustomerAccount, OpenSale } from '../../../api/retail';
import { AccountSummary, PaymentPanel, ReceiptList, blankPayment } from './account';
import { buildPaymentRequest, settledPayment, type PaymentForm } from './account-state';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// The account forms as a person uses them (ADR-033, issue #196): the preview of where a payment goes follows what is typed,
// the save button waits for a form the server would accept, a chosen split is checked, and a reversal needs its reason.
// Fabricated names and amounts.

afterEach(cleanup);

const sale = (id: string, date: string, balance: number): OpenSale => ({
  sale_id: id, sale_no: `RS-${id}`, branch_id: 'b1', sale_date: date, total_minor: balance, paid_minor: 0, balance_minor: balance,
});
const open = [sale('b', '2026-01-05', 1000), sale('c', '2026-02-05', 800)];
const branches = [{ id: 'b1', name: 'Test Branch A', code: 'BR1' }];

function Panel({ onSubmit }: { onSubmit: (form: PaymentForm) => void }) {
  const [form, setForm] = useState(blankPayment('b1', '2026-04-01'));
  return <PaymentPanel form={form} open={open} branches={branches} today="2026-04-01" onChange={setForm} onSubmit={() => onSubmit(form)} />;
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const saveButton = () => screen.getByRole('button', { name: 'Save payment' }) as HTMLButtonElement;

function CountedPanel({ open: sales, paidOn, onSubmit }: { open: OpenSale[]; paidOn: string; onSubmit: (form: PaymentForm) => void }) {
  const [form, setForm] = useState({ ...blankPayment('b1', '2026-04-01'), paidOn });
  return <PaymentPanel form={form} open={sales} branches={branches} today="2026-04-01" onChange={setForm} onSubmit={() => onSubmit(form)} />;
}

describe('the date of a payment on account', () => {
  const counted: OpenSale[] = [{ ...sale('d', '2026-03-05', 1000), counted_when_paid: true }];

  it('is locked to today when the payment would pay a sale counted when the buyer pays, even with a stale date', () => {
    const sent = vi.fn();
    render(<CountedPanel open={counted} paidOn="2026-03-30" onSubmit={sent} />);
    type('Amount received', '500');
    const date = screen.getByLabelText('Date received') as HTMLInputElement;
    expect(date.value).toBe('2026-04-01');
    expect(date.readOnly).toBe(true);
    expect(screen.getByTestId('account-pay-today-only').textContent).toMatch(/dated today/);
    fireEvent.click(saveButton());
    const form = sent.mock.calls[0]?.[0] as PaymentForm;
    expect(form.paidOn).toBe('2026-03-30');
    expect(buildPaymentRequest(settledPayment(form, counted, '2026-04-01')).paid_on).toBe('2026-04-01');
  });

  it('keeps its freedom when every sale it pays is of the earlier era', () => {
    render(<CountedPanel open={open} paidOn="2026-03-30" onSubmit={() => undefined} />);
    type('Amount received', '500');
    expect((screen.getByLabelText('Date received') as HTMLInputElement).value).toBe('2026-03-30');
    expect(screen.queryByTestId('account-pay-today-only')).toBeNull();
  });

  it('is locked only when the chosen sales include a counted one', () => {
    const mixed = [...open, ...counted];
    expect(settledPayment({ ...blankPayment('b1', '2026-04-01'), paidOn: '2026-03-30', amount: '1500' }, mixed, '2026-04-01').paidOn)
      .toBe('2026-03-30');
    expect(settledPayment({ ...blankPayment('b1', '2026-04-01'), paidOn: '2026-03-30', amount: '2500' }, mixed, '2026-04-01').paidOn)
      .toBe('2026-04-01');
  });
});

describe('taking a payment on account', () => {
  it('waits for an amount, previews the oldest sales first, and refuses more than can be paid', () => {
    render(<Panel onSubmit={() => undefined} />);
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    type('Amount received', '1500');
    expect(saveButton().getAttribute('data-not-ready')).toBeNull();
    const preview = screen.getByTestId('pay-preview').textContent ?? '';
    expect(preview.indexOf('RS-b')).toBeLessThan(preview.indexOf('RS-c'));
    expect(preview).toContain('UGX 1,000');
    expect(preview).toContain('UGX 500');
    type('Amount received', '1801');
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    expect(screen.getByRole('alert').textContent).toContain('At most UGX 1,800');
  });

  it('sends no allocations when the oldest sales are paid first', () => {
    const sent = vi.fn();
    render(<Panel onSubmit={sent} />);
    type('Amount received', '900');
    fireEvent.click(saveButton());
    const form = sent.mock.calls[0]?.[0] as PaymentForm;
    expect(buildPaymentRequest(form)).toEqual({ branch_id: 'b1', amount_minor: 900, method: 'cash', paid_on: '2026-04-01' });
  });

  it('checks a chosen split against the sales and the payment, then sends it', () => {
    const sent = vi.fn();
    render(<Panel onSubmit={sent} />);
    type('Amount received', '900');
    fireEvent.click(screen.getByLabelText('Choose which sales it pays'));
    expect(screen.getByTestId('pay-manual')).toBeTruthy();
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    type(/RS-c, 5 Feb 2026/, '600');
    type(/RS-b, 5 Jan 2026/, '200');
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    expect(screen.getByRole('alert').textContent).toMatch(/add up/i);
    type(/RS-b, 5 Jan 2026/, '300');
    expect(saveButton().getAttribute('data-not-ready')).toBeNull();
    fireEvent.click(saveButton());
    expect(buildPaymentRequest(sent.mock.calls[0]?.[0] as PaymentForm).allocations).toEqual([
      { sale_id: 'c', amount_minor: 600 },
      { sale_id: 'b', amount_minor: 300 },
    ]);
  });

  it('refuses a payment dated in the future', () => {
    render(<Panel onSubmit={() => undefined} />);
    type('Amount received', '900');
    type('Date received', '2026-04-02');
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
  });
});

describe('reversing a payment', () => {
  const receipt: AccountPayment = {
    id: 'r1', receipt_no: 'RC00000001', customer_id: 'c1', branch_id: 'b1', amount_minor: 900, method: 'cash', paid_on: '2026-04-01', allocation: 'oldest_first', status: 'posted',
    allocations: [{ sale_id: 'b', sale_no: 'RS-b', amount_minor: 900 }],
  };

  it('asks for a reason of at least five characters, then reverses', () => {
    const reversed = vi.fn();
    render(<ReceiptList items={[receipt]} canReverse={() => true} nameOf={() => 'Test Branch A'} onReverse={reversed} />);
    fireEvent.click(screen.getByRole('button', { name: 'Reverse payment RC00000001' }));
    const go = screen.getByRole('button', { name: 'Reverse this payment' }) as HTMLButtonElement;
    expect(go.disabled).toBe(true);
    type(/Why is it reversed/, 'oops');
    expect(go.disabled).toBe(true);
    type(/Why is it reversed/, ' Wrong buyer ');
    expect(ready(go)).toBe(true);
    fireEvent.click(go);
    expect(reversed).toHaveBeenCalledWith(receipt, 'Wrong buyer');
  });
});

describe('setting the limit and the terms', () => {
  const account: CustomerAccount = { customer_id: 'c1', name: 'Test Buyer 01', balance_minor: 0, credit_limit_minor: 5000, terms_days: 30, version: 2 };

  it('starts from what is saved, refuses what the server would, and sends the form', () => {
    const saved = vi.fn();
    render(<AccountSummary account={account} canManage onSave={saved} />);
    fireEvent.click(screen.getByRole('button', { name: 'Change the limit and terms' }));
    expect((screen.getByLabelText(/Credit limit/) as HTMLInputElement).value).toBe('5000');
    expect((screen.getByLabelText(/Payment terms/) as HTMLInputElement).value).toBe('30');
    const save = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    type(/Payment terms/, '400');
    expect(save.disabled).toBe(true);
    type(/Payment terms/, '45');
    type(/Credit limit/, '');
    expect(ready(save)).toBe(true);
    fireEvent.click(save);
    expect(saved).toHaveBeenCalledWith({ limit: '', terms: '45' });
  });
});
