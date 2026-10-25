// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Sale, SaleReturn } from '../../../api/retail';
import { CreditNoteView, VoidPanel } from './credit-note';
import { ReturnsThresholdWarning } from './settings';
import { ReturnFormView } from './return-new';
import { backOf, blankReturn, buildReturnRequest, type ReturnForm } from './returns-state';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// The return form as a person uses it (ADR-033, issue #201): the credit shown follows what is typed, the save button waits for
// a form the server would accept, an account refund asks how the rest is paid out, damaged goods name no branch, and a void
// needs its reason. Fabricated names and amounts.

afterEach(cleanup);

const branches = [{ id: 'b1', name: 'Test Branch A', code: 'BR1' }, { id: 'b2', name: 'Test Store B', code: 'ST2' }];
const sale = (over: Partial<Sale> = {}): Sale => ({
  id: 's1', sale_no: 'RS00000001', branch_id: 'b1', payment_method: 'cash', status: 'completed', total_minor: 1000, paid_minor: 1000, balance_minor: 0,
  lines: [{ id: 'l1', line_no: 1, product_id: 'p1', description: 'Test Item 01', qty: '10.000', unit_price_minor: 100, line_total_minor: 1000 }], ...over,
});

function Form({ s, onSubmit, back = backOf([]) }: { s: Sale; onSubmit: (form: ReturnForm) => void; back?: ReturnType<typeof backOf> }) {
  const [form, setForm] = useState<ReturnForm>(blankReturn());
  return <ReturnFormView sale={s} back={back} form={form} branches={branches} onChange={setForm} onSubmit={() => onSubmit(form)} />;
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const save = () => screen.getByRole('button', { name: 'Record the return' }) as HTMLButtonElement;

describe('a sale made at a store (ADR-048)', () => {
  const shops = [{ id: 'b1', name: 'Test Branch A', code: 'BR1' }, { id: 'b2', name: 'Test Store B', code: 'ST2', sells_goods: false }];

  it('offers only shops to pay the refund from, and no usual branch', () => {
    const atStore = sale({ branch_id: 'b2' });
    function StoreForm() {
      const [form, setForm] = useState<ReturnForm>(blankReturn());
      return <ReturnFormView sale={atStore} back={backOf([])} form={form} branches={shops} usualIsStore onChange={setForm} onSubmit={() => undefined} />;
    }
    render(<StoreForm />);
    type(/Quantity to return/, '2');
    const select = screen.getByLabelText(/Pay the money out of the till of/) as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.textContent)).toEqual(['Choose a branch', 'Test Branch A (BR1)']);
  });

  it('keeps the usual branch for a sale made at a shop', () => {
    function ShopForm() {
      const [form, setForm] = useState<ReturnForm>(blankReturn());
      return <ReturnFormView sale={sale()} back={backOf([])} form={form} branches={shops} onChange={setForm} onSubmit={() => undefined} />;
    }
    render(<ShopForm />);
    type(/Quantity to return/, '2');
    expect(screen.queryByLabelText(/Pay the money out of the till of/)).toBeNull();
  });
});

describe('taking goods back', () => {
  it('waits for a quantity, shows the credit, and refuses more than was sold', () => {
    render(<Form s={sale()} onSubmit={() => undefined} />);
    expect(save().getAttribute('data-not-ready')).toBe('true');
    type(/Quantity to return/, '4');
    expect(save().getAttribute('data-not-ready')).toBeNull();
    expect(screen.getByTestId('credit-l1').textContent).toContain('UGX 400');
    expect(screen.getByTestId('return-plan').textContent).toContain('UGX 400 paid back');
    type(/Quantity to return/, '10.5');
    expect(save().getAttribute('data-not-ready')).toBe('true');
    expect(screen.getAllByRole('alert')[0]?.textContent).toMatch(/at most 10/i);
  });

  it('asks where restocked goods go and sends the branch only when it is not the sale\'s own', () => {
    const sent = vi.fn();
    render(<Form s={sale()} onSubmit={sent} />);
    type(/Quantity to return/, '2');
    type(/Put them back at/, 'b2');
    fireEvent.click(save());
    const form = sent.mock.calls[0]?.[0] as ReturnForm;
    expect(buildReturnRequest(sale(), form, backOf([])).lines[0]).toMatchObject({ sale_line_id: 'l1', qty: '2.000', disposition: 'restock', restock_branch_id: 'b2' });
  });

  it('names no branch for damaged goods', () => {
    render(<Form s={sale()} onSubmit={() => undefined} />);
    type(/Quantity to return/, '2');
    type(/What happens to the goods/, 'write_off');
    expect(screen.queryByLabelText(/Put them back at/)).toBeNull();
    expect(screen.getByText(/never go back into stock/i)).toBeTruthy();
    expect(save().getAttribute('data-not-ready')).toBeNull();
  });

  it('takes a return off what a credit buyer owes, and asks how the rest is paid out', () => {
    const owing = sale({ payment_method: 'credit', paid_minor: 900, balance_minor: 100 });
    const sent = vi.fn();
    render(<Form s={owing} onSubmit={sent} />);
    type(/Quantity to return/, '5');
    expect((screen.getByLabelText('Repay by') as HTMLSelectElement).value).toBe('account');
    expect(save().getAttribute('data-not-ready')).toBe('true');
    expect(screen.getByTestId('return-plan').textContent).toContain('UGX 100 taken off what they owe');
    expect(screen.getByTestId('return-plan').textContent).toContain('UGX 400 paid back');
    type(/not taken off the balance is paid back by/, 'mobile_money');
    expect(save().getAttribute('data-not-ready')).toBeNull();
    fireEvent.click(save());
    expect(buildReturnRequest(owing, sent.mock.calls[0]?.[0] as ReturnForm, backOf([]))).toMatchObject({ refund_method: 'account', money_method: 'mobile_money' });
  });

  it('does not offer to take it off the balance for a cash sale', () => {
    render(<Form s={sale()} onSubmit={() => undefined} />);
    const option = screen.getByRole('option', { name: 'Take it off what they owe' }) as HTMLOptionElement;
    expect(option.disabled).toBe(true);
  });

  it('does not offer to take it off the balance for an imported credit sale or one that is paid up', () => {
    const imported = sale({ payment_method: 'credit', paid_minor: 100, balance_minor: 900, historical: true });
    const { unmount } = render(<Form s={imported} onSubmit={() => undefined} />);
    expect((screen.getByRole('option', { name: 'Take it off what they owe' }) as HTMLOptionElement).disabled).toBe(true);
    expect((screen.getByLabelText('Repay by') as HTMLSelectElement).value).toBe('cash');
    unmount();
    render(<Form s={sale({ payment_method: 'credit', paid_minor: 1000, balance_minor: 0 })} onSubmit={() => undefined} />);
    expect((screen.getByRole('option', { name: 'Take it off what they owe' }) as HTMLOptionElement).disabled).toBe(true);
    expect(screen.getByText(/nothing is owed on this credit sale/i)).toBeTruthy();
  });

  it('offers the till the money comes out of when the person may use more than one branch', () => {
    const sent = vi.fn();
    render(<Form s={sale()} onSubmit={sent} />);
    type(/Quantity to return/, '2');
    type(/Pay the money out of the till of/, 'b2');
    fireEvent.click(save());
    expect(buildReturnRequest(sale(), sent.mock.calls[0]?.[0] as ReturnForm, backOf([]))).toMatchObject({ refund_branch_id: 'b2' });
  });

  it('shows what has come back already and what can still come back', () => {
    const earlier: SaleReturn = { id: 'r1', status: 'completed', lines: [{ sale_line_id: 'l1', qty: '6.000', line_total_minor: 600 }] } as SaleReturn;
    render(<Form s={sale()} onSubmit={() => undefined} back={backOf([earlier])} />);
    expect(screen.getByTestId('return-row-l1').textContent).toContain('4 can still come back');
    type(/Quantity to return/, '5');
    expect(save().getAttribute('data-not-ready')).toBe('true');
  });
});

describe('a credit note on screen', () => {
  const note = {
    id: 'r1', credit_note_no: 'CNBR1-000001', sale_no: 'RS00000001', branch_id: 'b1', returned_on: '2026-10-08', total_minor: 600, account_credit_minor: 200, refund_minor: 400, refund_method: 'cash',
    status: 'completed', customer_name: 'Test Buyer 01',
    lines: [{ id: 'x', line_no: 1, description: 'Test Item 01', qty: '12.000', unit: 'carton', unit_qty: '1.000', line_total_minor: 600, reason: 'defective', disposition: 'restock', restock_branch_id: 'b2' }],
  } as SaleReturn;

  it('shows the goods, where they went and how it was settled', () => {
    render(<CreditNoteView note={note} nameOf={(id) => (id === 'b2' ? 'Test Store B' : 'Test Branch A')} />);
    const text = screen.getByTestId('credit-note').textContent ?? '';
    expect(text).toContain('CNBR1-000001');
    expect(text).toContain('1 carton of Test Item 01');
    expect(text).toContain('Put back on the shelf, Test Store B');
    expect(text).toContain('UGX 200 taken off what the buyer owes and UGX 400 paid back by cash');
    expect(text).not.toContain('Cost of the goods');
  });

  it('needs a reason of five characters to void', () => {
    const onVoid = vi.fn();
    render(<VoidPanel onVoid={onVoid} />);
    fireEvent.click(screen.getByRole('button', { name: 'Void this credit note' }));
    const go = screen.getByRole('button', { name: 'Void it' }) as HTMLButtonElement;
    expect(go.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Why is it voided/), { target: { value: 'abcd' } });
    expect(go.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Why is it voided/), { target: { value: 'Sent twice' } });
    expect(ready(go)).toBe(true);
    fireEvent.click(go);
    expect(onVoid).toHaveBeenCalledWith('Sent twice');
  });
});

describe('the settings note', () => {
  it('says so in one neutral Hint line while returns are on and no approval limit is set, and stays quiet otherwise', () => {
    const { unmount } = render(<ReturnsThresholdWarning capabilities={{ returns: true } as never} />);
    const note = screen.getByTestId('returns-no-threshold');
    expect(note.textContent).toMatch(/^Returns need no second administrator yet\. /);
    expect(note.textContent).toMatch(/no approval limit/i);
    expect(note.getAttribute('role')).toBe('note');
    // No screen sets the limit yet, so the note offers no link that would lead nowhere.
    expect(note.querySelector('a')).toBeNull();
    expect(note.querySelector('.alert, .alert-warning')).toBeNull();
    unmount();
    render(<ReturnsThresholdWarning capabilities={{ returns: true, returns_approval_threshold_minor: 500 } as never} />);
    expect(screen.queryByTestId('returns-no-threshold')).toBeNull();
    cleanup();
    render(<ReturnsThresholdWarning capabilities={{ returns: false } as never} />);
    expect(screen.queryByTestId('returns-no-threshold')).toBeNull();
  });
});
