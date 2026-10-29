// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState, type ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { businessToday, type Product, type SupplierOpenItem, type SupplierPayables, type SupplierStatement } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { WORDS, offendingWords } from '../../../app/glossary';
import { StaffContext } from '../context';

// Buy stock on credit, see what you owe suppliers, pay a supplier and read the supplier statement (issue #557, owner decision
// D15, ADR-084), with the purchasing capability off: Paid how on Buy stock (On credit needs a supplier), the list of what each
// supplier is owed with the total, the payment's date (never in the future), and the statement with its running balance and the
// text that is shared. Names and amounts are fabricated.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to, ...rest }: { children: unknown; to: string; 'aria-label'?: string; className?: string }) => <a href={to} aria-label={rest['aria-label']} className={rest.className}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listProducts: () => Promise.resolve([]), listSuppliers: () => Promise.resolve(suppliers), createSupplier: (...a: unknown[]) => createSupplier(...a) } };
});

let suppliers: Record<string, unknown>[] = [];
const createSupplier = vi.fn();

const { METHODS, RestockForm, buildPurchase, creditNeedsSupplier } = await import('./restock');
const { OwedList, PayablesReport } = await import('./supplier-payables');
const { Money } = await import('./ui');
const { moneyLinks } = await import('../tabs');
const { PaymentFormView } = await import('./supplier-payment-new');
const { SupplierStatementView } = await import('./supplier-statement');
const { blankPayment, buildPaymentRequest, owedOn, paymentProblem, statementLineWords, statementText } = await import('./payables-state');
type PaymentForm = ReturnType<typeof blankPayment>;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  suppliers = [];
  createSupplier.mockReset();
});

/** An owner with the purchasing capability off: buys, pays and reads what is owed. */
const owner = { ...mockMe('admin'), module_capabilities: { retail: [] } };
const branch = owner.branches?.[0]?.id ?? '';
const item: Product = { id: 'p1', code: 'T-RICE', description: 'Test rice', unit: 'bag', cost_minor: 1000, sell_minor: 1500, active: true };

function screenOf(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: owner, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const restockDraft = (method: 'cash' | 'mobile_money' | 'bank' | 'credit') => ({
  supplierId: '', newSupplier: '', purchasedOn: businessToday(), method, lines: [{ product: item, cost: '1000', sell: '1500', qtyByBranch: { [branch]: '5' } }],
});

describe('Paid how on Buy stock', () => {
  it('offers cash, mobile money, bank and on credit, in plain glossary words', () => {
    expect(METHODS.map((m) => m.label)).toEqual(['Cash', 'Mobile money', 'Bank', 'On credit (owed to the supplier)']);
    expect(offendingWords(METHODS.map((m) => m.label).join(' '))).toEqual([]);
    screenOf(<RestockForm initial={restockDraft('cash')} />);
    const paid = screen.getByRole('group', { name: /Paid how/ });
    expect(paid.querySelector('legend .field-star')?.textContent?.trim()).toBe('*');
    expect(within(paid).getAllByRole('radio')).toHaveLength(4);
    expect((within(paid).getByLabelText('Mobile money') as HTMLInputElement).checked).toBe(false);
    // The total cost goes through the one money formatter, in tabular figures.
    expect(document.querySelector('.rt-total .num')?.textContent).toMatch(/^UGX /);
  });

  it('asks for a supplier when it is on credit, and marks the supplier needed', () => {
    suppliers = [{ id: 's1', name: 'Test Supplier 01', active: true }];
    screenOf(<RestockForm initial={restockDraft('credit')} />);
    expect(screen.getByTestId('credit-needs-supplier').textContent).toBe('On credit needs a supplier. Choose one above, or + Add a supplier.');
    expect(document.querySelector('label[for="supplier"] .field-star')).toBeTruthy();
    expect(screen.getByLabelText(/^Supplier/).getAttribute('aria-required')).toBe('true');
    expect(screen.getByRole('button', { name: 'Save the purchase' }).getAttribute('data-not-ready')).toBe('true');
    expect(screen.getByText(/Pay them later from Money, What I owe suppliers\./)).toBeTruthy();
  });

  it('adds a supplier in place (issue #688): chosen at once, the lines kept, and On credit is ready', async () => {
    createSupplier.mockResolvedValue({ id: 's9', name: 'Test Supplier 09', active: true });
    screenOf(<RestockForm initial={restockDraft('credit')} />);
    // With no supplier yet, Save goes to + Add a supplier (review of #619).
    const add = await screen.findByRole('button', { name: '+ Add a supplier' });
    expect(screen.getByText('No suppliers yet.')).toBeTruthy();
    expect(document.querySelector('[data-needed]')).toBe(add);
    fireEvent.click(screen.getByRole('button', { name: 'Save the purchase' }));
    expect(document.activeElement).toBe(add);
    fireEvent.click(add);
    const sheet = await screen.findByRole('dialog', { name: 'Add a supplier' });
    fireEvent.change(within(sheet).getByLabelText(/^Name of the new supplier/), { target: { value: 'Test Supplier 09' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Add supplier' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(createSupplier).toHaveBeenCalledWith({ name: 'Test Supplier 09' });
    expect((screen.getByLabelText(/^Supplier/) as HTMLSelectElement).selectedOptions[0]?.text).toBe('Test Supplier 09');
    expect(screen.queryByTestId('credit-needs-supplier')).toBeNull();
    expect(screen.getByTestId('restock-total').querySelector('.num')?.textContent).toBe('UGX 5,000');
  });

  it('draws the total cost in tabular figures', () => {
    screenOf(<RestockForm initial={restockDraft('cash')} />);
    const total = screen.getByTestId('restock-total');
    expect(total.querySelector('.num')?.textContent).toBe('UGX 5,000');
  });

  it('needs no supplier for cash, mobile money or bank, and sends the method as chosen', () => {
    for (const m of ['cash', 'mobile_money', 'bank'] as const) expect(creditNeedsSupplier(m, '', '')).toBeNull();
    expect(creditNeedsSupplier('credit', 's1', '')).toBeNull();
    expect(buildPurchase({ supplierId: 's1', purchasedOn: '2026-10-08', method: 'mobile_money', lines: restockDraft('cash').lines }).payment_method).toBe('mobile_money');
  });
});

describe('what I owe suppliers', () => {
  const report: SupplierPayables = {
    as_of: '2026-10-08', currency: 'UGX',
    totals: { owed_minor: 57000, not_due_minor: 0, days1_to30_minor: 0, days31_to60_minor: 0, days61_to90_minor: 0, over90_minor: 0, unbilled_receipts_minor: 0 },
    suppliers: [
      { supplier_id: 's1', supplier_name: 'Test Supplier 01', owed_minor: 50000 },
      { supplier_id: 's2', supplier_name: 'Test Supplier 02', owed_minor: 7000 },
    ],
  };

  it('shows the total first, then each supplier with what is owed, Pay and Statement', () => {
    render(<OwedList report={report} />);
    expect(screen.getByTestId('payables-total').textContent).toBe('You owe suppliers UGX 57,000 in all.');
    const first = screen.getByTestId('payable-s1');
    expect(first.textContent).toContain('Test Supplier 01');
    expect(first.textContent).toContain('Owed UGX 50,000');
    expect(within(first).getByRole('link', { name: 'Pay Test Supplier 01' }).className).toContain('btn');
    expect(within(first).getByRole('link', { name: 'Statement of Test Supplier 01' }).getAttribute('href')).toBe('/staff/retail/suppliers/$supplierId/statement');
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByText(/days late|invoiced/)).toBeNull();
  });

  it('says so when no supplier is owed anything', () => {
    render(<OwedList report={{ ...report, suppliers: [], totals: { ...report.totals, owed_minor: 0 } }} />);
    expect(screen.getByText(/You owe no supplier anything/).textContent).toContain(WORDS.buyStock);
  });
});

describe('Pay a supplier', () => {
  const items: SupplierOpenItem[] = [
    { id: 'r1', kind: 'restock', number: 'P-000001', date: '2026-10-01', due_date: '2026-10-01', open_minor: 30000, days_past_due: 7 },
    { id: 'r2', kind: 'restock', number: 'P-000002', date: businessToday(), due_date: businessToday(), open_minor: 12000, days_past_due: 0 },
  ];
  const suppliers = [{ id: 's1', name: 'Test Supplier 01' }];
  function Form({ onSubmit }: { onSubmit: (form: PaymentForm) => void }) {
    const [form, setForm] = useState<PaymentForm>(blankPayment('s1', branch));
    return <PaymentFormView form={form} suppliers={suppliers} branches={[owner.branches![0]!]} items={items} owed={42000} onChange={setForm} onSubmit={() => onSubmit(form)} />;
  }

  it('marks what is needed, starts the date at today and sends it', () => {
    const sent = vi.fn();
    render(<Form onSubmit={sent} />);
    expect(screen.getByText(/are needed\./)).toBeTruthy();
    for (const id of ['pay-supplier', 'pay-method', 'pay-amount', 'pay-date']) {
      expect(document.querySelector(`label[for="${id}"] .field-star`), id).toBeTruthy();
    }
    const date = screen.getByLabelText(/Date paid/) as HTMLInputElement;
    expect(date.value).toBe(businessToday());
    expect(date.max).toBe(businessToday());
    fireEvent.change(screen.getByLabelText(/Amount paid/), { target: { value: '40000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save payment' }));
    expect(buildPaymentRequest(sent.mock.calls[0]?.[0] as PaymentForm, items)).toMatchObject({ paid_on: businessToday(), amount_minor: 40000, method: 'cash' });
  });

  it('draws every amount on the form in tabular figures: what is owed, what is left to give out and the warnings', () => {
    render(<Form onSubmit={() => undefined} />);
    expect(screen.getByTestId('owed-total').querySelector('.num')?.textContent).toBe('UGX 42,000');
    for (const li of screen.getByTestId('owed-list').querySelectorAll('li')) expect(li.querySelector('.num')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/Amount paid/), { target: { value: '50000' } });
    const alert = screen.getAllByRole('alert')[0]!;
    expect(alert.textContent).toMatch(/UGX 42,000/);
    expect(alert.className).toContain('num');
    fireEvent.click(screen.getByLabelText('Let me choose'));
    expect(screen.getByTestId('pay-remaining').className).toContain('num');
  });

  it('refuses a date in the future, and an earlier day pays only what was owed then', () => {
    const tomorrow = new Date(Date.parse(`${businessToday()}T12:00:00Z`) + 86_400_000).toISOString().slice(0, 10);
    const form = { ...blankPayment('s1', branch), amount: '100' };
    expect(paymentProblem({ ...form, paidOn: tomorrow }, items, 42000)).toBe('A payment cannot be dated in the future.');
    // No day picked means today, read when it is sent: a draft left open overnight pays the next day, never the day it was started.
    expect(form.paidOn).toBe('');
    expect(paymentProblem(form, items, 42000)).toBeNull();
    expect(buildPaymentRequest(form, items).paid_on).toBe(businessToday());
    expect(owedOn(items, '2026-10-01').map((i) => i.number)).toEqual(['P-000001']);
    // A draft saved before the date existed pays today.
    const { paidOn: _old, ...older } = form;
    expect(buildPaymentRequest(older, items).paid_on).toBe(businessToday());
  });
});

describe('amounts on the screen', () => {
  it('Money draws an amount in tabular figures', () => {
    const { container } = render(<Money minor={1234500} />);
    const el = container.querySelector('span');
    expect(el?.className).toBe('num');
    expect(el?.textContent).toBe('UGX 1,234,500');
  });

  it('every amount on What I owe suppliers and Pay a supplier goes through it', () => {
    const report: SupplierPayables = { as_of: '2026-10-08', currency: 'UGX', totals: { owed_minor: 7000 }, suppliers: [{ supplier_id: 's2', supplier_name: 'Test Supplier 02', owed_minor: 7000, not_due_minor: 7000, unbilled_receipts_minor: 300 }] };
    const owed = render(<OwedList report={report} />);
    expect([...owed.container.querySelectorAll('.num')].map((n) => n.textContent)).toEqual(['UGX 7,000', 'UGX 7,000']);
    owed.unmount();
    const phone = render(<PayablesReport report={report} phone />);
    // Owed in all, the five ages, received not invoiced; and the same for the totals card.
    expect(phone.container.querySelectorAll('.num')).toHaveLength(14);
  });
});

describe('under the Money tab', () => {
  it('lists What I owe suppliers for an owner without the purchasing capability, and not for a seller', () => {
    const links = moneyLinks(owner);
    expect(links.map((l) => l.label)).toContain(WORDS.whatIOweSuppliers);
    expect(links.find((l) => l.label === WORDS.whatIOweSuppliers)?.to).toBe('/staff/retail/supplier-payables');
    expect(moneyLinks({ ...mockMe('sales'), module_capabilities: { retail: [] } }).map((l) => l.label)).not.toContain(WORDS.whatIOweSuppliers);
  });
});

describe('the supplier statement', () => {
  const statement: SupplierStatement = {
    supplier_id: 's1', supplier_name: 'Test Supplier 01', currency: 'UGX', from: '2026-10-01', to: '2026-10-08', opening_minor: 5000, closing_minor: 9000,
    lines: [
      { date: '2026-10-01', kind: 'purchase', number: 'P-000001', source_id: 'p1', increase_minor: 10000, decrease_minor: 0, balance_minor: 15000 },
      { date: '2026-10-04', kind: 'payment', number: 'SP-HQ-000001', reference: 'MM-77', method: 'mobile_money', source_id: 'sp1', increase_minor: 0, decrease_minor: 6000, balance_minor: 9000 },
    ],
  };

  it('shows the opening balance, each line with the balance after it, and the closing balance', () => {
    render(<SupplierStatementView statement={statement} />);
    expect(screen.getByTestId('statement-opening').textContent).toBe('UGX 5,000');
    expect(screen.getAllByTestId('statement-balance').map((b) => b.textContent)).toEqual(['UGX 15,000', 'UGX 9,000']);
    expect(screen.getAllByTestId('statement-line')[1]?.textContent).toContain('Paid by mobile money SP-HQ-000001 (MM-77)');
    expect(screen.getByTestId('statement-closing').textContent).toBe('UGX 9,000');
    // Every amount is drawn by Money, so in tabular figures.
    for (const el of [screen.getByTestId('statement-opening'), ...screen.getAllByTestId('statement-balance'), screen.getByTestId('statement-closing')]) expect(el.querySelector('.num')?.textContent).toBe(el.textContent);
    expect(screen.getAllByTestId('statement-line')[0]?.querySelectorAll('.num')).toHaveLength(2);
  });

  it('is shared as plain text with the running balance', () => {
    expect(statementLineWords(statement.lines![0]!)).toBe('Bought on credit P-000001');
    expect(statementText(statement).split('\n')).toEqual([
      'Statement for Test Supplier 01',
      '2026-10-01 to 2026-10-08',
      'Owed at the start: UGX 5,000',
      '2026-10-01 Bought on credit P-000001 +UGX 10,000, owed UGX 15,000',
      '2026-10-04 Paid by mobile money SP-HQ-000001 (MM-77) -UGX 6,000, owed UGX 9,000',
      'Owed at the end: UGX 9,000',
    ]);
    expect(offendingWords(statementText(statement))).toEqual([]);
  });
});

describe('the whole round in the mock, with purchasing off', () => {
  it('a credit purchase raises what is owed, a payment lowers it, and the statement agrees', async () => {
    const api = createMockRetail();
    const supplier = await api.createSupplier({ name: 'Test Supplier 05' });
    const [bulb] = await api.listProducts({ query: 'P003' });
    const today = businessToday();
    await api.createPurchase({ supplier_id: supplier.id, purchased_on: today, payment_method: 'credit', lines: [{ product_id: bulb?.id ?? '', cost_minor: 2000, qty_by_branch: [{ branch_id: branch, qty: '5' }] }] }, 'k-buy');
    const owed = (await api.supplierPayables()).suppliers?.find((s) => s.supplier_id === supplier.id);
    expect(owed?.owed_minor).toBe(10000);
    await api.createSupplierPayment({ supplier_id: supplier.id ?? '', branch_id: branch, method: 'cash', amount_minor: 4000, paid_on: today }, 'k-pay');
    expect((await api.supplierPayables()).suppliers?.find((s) => s.supplier_id === supplier.id)?.owed_minor).toBe(6000);
    const st = await api.supplierStatement(supplier.id ?? '', { from: today, to: today });
    expect(st.lines?.map((l) => [l.kind, l.balance_minor])).toEqual([['purchase', 10000], ['payment', 6000]]);
    expect(st.closing_minor).toBe(6000);
  });
});
