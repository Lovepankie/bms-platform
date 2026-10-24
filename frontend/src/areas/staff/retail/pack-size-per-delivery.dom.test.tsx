// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product, Purchase, PurchaseOrder } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { howManyInEach, offendingWords, plural, useFromNowOn } from '../../../app/glossary';

// A buying pack's size per delivery (owner decision D23, issue #554; docs/specs/units-packs-and-costing.md section 3): a line
// bought in a pack asks how many pieces each pack held, filled with the usual size and marked required; a different size
// offers "Use 30 from now on" to whoever manages items; the request carries the size and the tick only when it differs; the
// order's cartons are counted whatever each held; with pack sizes off nothing is asked. Names and amounts are fabricated.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listProducts: () => Promise.resolve([]), listSuppliers: () => Promise.resolve([]) } };
});

const { RestockForm, buildPurchase, lineFactor, restockLineProblem, withUnit } = await import('./restock');
const { redraftOf } = await import('./restock-correct-state');
const { ReceiveFormView } = await import('./goods-receive');
const { blindLineFor, buildReceiptRequest, receiveFormFor, receiveLineProblem, stillToComeMilli, withReceiveUnit } = await import('./purchasing-state');

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

const admin = { ...mockMe('admin'), module_capabilities: { retail: ['units', 'purchasing'] } };
const branch = admin.branches?.[0]?.id ?? '';
/** Buys stock but does not manage items: records the delivery's size, never changes the usual one. */
const buyer = { ...admin, permissions: (admin.permissions ?? []).filter((p) => p !== 'retail.catalogue.manage') };
const CARTON = 'u-carton';
const bulb: Product = {
  id: 'p1', code: 'T-BULB', description: 'Test bulb', unit: 'piece', cost_minor: 750, sell_minor: 1000, active: true,
  packs: [{ unit_id: CARTON, unit: 'carton', factor: '24.000', active: true }],
};
const line = (over: Partial<Parameters<typeof withUnit>[0]> = {}) =>
  ({ ...withUnit({ product: bulb, cost: '', sell: '1000', qtyByBranch: { [branch]: '2' } }, CARTON), ...over });

function screenOf(me: typeof admin, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const draft = (lines: ReturnType<typeof line>[]) => ({ supplierId: '', newSupplier: '', purchasedOn: '2026-10-08', method: 'cash' as const, lines });

describe('the words', () => {
  it('asks in plain glossary words, with the unit in the plural', () => {
    expect(howManyInEach('piece', 'carton')).toBe('How many pieces in each carton?');
    expect(howManyInEach('box', 'crate')).toBe('How many boxes in each crate?');
    expect(plural('battery')).toBe('batteries');
    expect(useFromNowOn('30')).toBe('Use 30 from now on');
    expect(offendingWords(`${howManyInEach('piece', 'carton')} ${useFromNowOn('30')}`)).toEqual([]);
  });
});

describe('a Buy stock line in a pack', () => {
  it('starts at the usual size, and the size moves the stock and the cost per piece', () => {
    const l = line({ cost: '24000' });
    expect(l.packSize).toBe('24');
    expect(lineFactor(l)).toBe('24');
    const thirty = { ...l, packSize: '30' };
    expect(lineFactor(thirty)).toBe('30');
    expect(restockLineProblem(thirty)).toBeNull();
    const sent = buildPurchase({ supplierId: '', purchasedOn: '2026-10-08', method: 'cash', lines: [thirty] }).lines[0];
    expect(sent).toMatchObject({ unit_id: CARTON, pack_size: '30', cost_minor: 24000 });
    expect(sent).not.toHaveProperty('use_from_now_on');
    // The base unit line sends no size at all.
    const pieces = withUnit(l, '');
    expect(pieces.packSize).toBeUndefined();
    expect(buildPurchase({ supplierId: '', purchasedOn: '2026-10-08', method: 'cash', lines: [pieces] }).lines[0]).not.toHaveProperty('pack_size');
  });

  it('sends use from now on only when ticked and the size differs from the usual one', () => {
    const body = (over: Partial<ReturnType<typeof line>>) => buildPurchase({ supplierId: '', purchasedOn: '2026-10-08', method: 'cash', lines: [line({ cost: '24000', ...over })] }).lines[0];
    expect(body({ packSize: '30', fromNowOn: true })).toMatchObject({ pack_size: '30', use_from_now_on: true });
    expect(body({ packSize: '24', fromNowOn: true })).not.toHaveProperty('use_from_now_on');
    expect(body({ packSize: '30', fromNowOn: false })).not.toHaveProperty('use_from_now_on');
  });

  it('refuses a size below one or that is not a number, in plain words', () => {
    expect(restockLineProblem(line({ cost: '24000', packSize: '0.5' }))).toBe('Enter how many pieces were in each carton: 1 or more.');
    expect(restockLineProblem(line({ cost: '24000', packSize: 'many' }))).toBe('Enter how many pieces were in each carton: 1 or more.');
    expect(restockLineProblem(line({ cost: '24000', packSize: '' }))).toBe('Enter how many pieces were in each carton: 1 or more.');
  });

  it('keeps the size of the delivery when a voided purchase is entered again', () => {
    const purchase: Purchase = {
      id: 'r1', purchase_no: 'RP00000001', purchased_on: '2026-10-05', payment_method: 'cash', currency: 'UGX', total_minor: 24000, status: 'voided',
      lines: [{ id: 'l1', line_no: 1, product_id: 'p1', unit_id: CARTON, unit: 'carton', unit_factor: '30.000', unit_qty_total: '1.000', unit_cost_minor: 24000, cost_minor: 800, qty_total: '30.000', line_total_minor: 24000, qty_by_branch: [{ branch_id: branch, qty: '30.000', unit_qty: '1.000' }] }],
    };
    expect(redraftOf(purchase, [bulb], '2026-10-08').lines[0]).toMatchObject({ unitId: CARTON, packSize: '30' });
  });
});

describe('the Buy stock form', () => {
  it('asks how many pieces in each carton, required with a red asterisk, filled with the usual size', () => {
    screenOf(admin, <RestockForm initial={draft([line({ cost: '18000' })])} />);
    const size = screen.getByLabelText(/How many pieces in each carton\?/) as HTMLInputElement;
    expect(size.value).toBe('24');
    expect(size.getAttribute('aria-required')).toBe('true');
    expect(document.querySelector(`label[for="size-0"] .field-star`)?.textContent?.trim()).toBe('*');
    expect(document.querySelector(`label[for="cost-0"] .field-star`)).toBeTruthy();
    expect(screen.getByText(/are needed\./)).toBeTruthy();
    expect(screen.getByText(/Usually 24\./)).toBeTruthy();
    // The usual size offers nothing more.
    expect(screen.queryByLabelText(/from now on/)).toBeNull();
  });

  it('offers Use 30 from now on for a different size, and says what each choice does', () => {
    screenOf(admin, <RestockForm initial={draft([line({ cost: '24000' })])} />);
    fireEvent.change(screen.getByLabelText(/How many pieces in each carton\?/), { target: { value: '30' } });
    expect(screen.getByTestId('pack-size-note-0').textContent).toBe('Only this delivery counts as 30 in each carton. A carton sold is still 24.');
    const tick = screen.getByLabelText('Use 30 from now on') as HTMLInputElement;
    expect(tick.checked).toBe(false);
    fireEvent.click(tick);
    expect(screen.getByTestId('pack-size-note-0').textContent).toBe('Every carton bought or sold after this one counts as 30.');
    // 24,000 for a carton of 30 is 800 a piece, which is what Last paid becomes.
    expect(screen.getByText(/Last paid will change from UGX 750 to UGX 800\./)).toBeTruthy();
  });

  it('records the size but never offers to change the usual one to someone who does not manage items', () => {
    screenOf(buyer, <RestockForm initial={draft([line({ cost: '24000', packSize: '30' })])} />);
    expect((screen.getByLabelText(/How many pieces in each carton\?/) as HTMLInputElement).value).toBe('30');
    expect(screen.queryByLabelText(/from now on/)).toBeNull();
    expect(screen.getByTestId('pack-size-note-0').textContent).toContain('Only this delivery counts as 30');
  });

  it('asks nothing while pack sizes are switched off', () => {
    screenOf({ ...admin, module_capabilities: { retail: [] } }, <RestockForm initial={draft([{ product: bulb, cost: '750', sell: '1000', qtyByBranch: { [branch]: '2' } }])} />);
    expect(screen.queryByLabelText(/How many pieces in each/)).toBeNull();
    expect(document.getElementById('unit-0')).toBeNull();
  });
});

describe('goods received in a pack', () => {
  const order: PurchaseOrder = {
    id: 'o1', order_no: 'POHQ-000001', status: 'partly_received', supplier_id: 's1', supplier_name: 'Test Supplier 01',
    lines: [{ id: 'ol1', line_no: 1, product_id: 'p1', code: 'T-BULB', description: 'Test bulb', unit_id: CARTON, unit: 'carton', base_unit: 'piece', unit_factor: '24.000', unit_qty: '10.000', qty: '240.000', unit_cost_minor: 18000, cost_minor: 750, line_total_minor: 180000, received_qty: '180.000', received_unit_qty: '6.000', received_minor: 108000 }],
  };

  it('counts the cartons still to come whatever each held, and asks for this delivery size', () => {
    // Six cartons of 30 came: 180 pieces, but four of the ten cartons are still to come.
    expect(stillToComeMilli(order.lines?.[0] ?? {})).toBe(96_000);
    const form = receiveFormFor(order, branch);
    const l = form.lines[0] ?? blindLineFor(bulb, true, 'unused');
    expect(l).toMatchObject({ unitId: CARTON, unitLabel: 'carton', baseUnit: 'piece', packSize: '24', usualSize: '24.000', qty: '4' });
    const thirty = { ...l, packSize: '30', fromNowOn: true };
    expect(receiveLineProblem(thirty)).toBeNull();
    expect(buildReceiptRequest({ ...form, lines: [thirty] }, 'o1').lines[0]).toMatchObject({ order_line_id: 'ol1', qty: '4.000', pack_size: '30', use_from_now_on: true });
    expect(receiveLineProblem({ ...l, packSize: '0' })).toBe('Enter how many pieces were in each carton: 1 or more.');
  });

  it('fills a blind line in a carton with the usual size and sends no size for pieces', () => {
    const blind = withReceiveUnit({ ...blindLineFor(bulb, true, 'k'), qty: '2', cost: '18000' }, CARTON);
    expect(blind).toMatchObject({ packSize: '24', usualSize: '24.000' });
    expect(buildReceiptRequest({ supplierId: 's1', branchId: branch, receivedOn: '2026-10-08', deliveryRef: '', note: '', lines: [blind] }).lines[0]).toMatchObject({ unit_id: CARTON, pack_size: '24' });
    const pieces = withReceiveUnit(blind, '');
    expect(pieces.packSize).toBeUndefined();
    expect(buildReceiptRequest({ supplierId: 's1', branchId: branch, receivedOn: '2026-10-08', deliveryRef: '', note: '', lines: [{ ...pieces, qty: '5', cost: '750' }] }).lines[0]).not.toHaveProperty('pack_size');
  });

  it('shows the question with a red asterisk on the line and the tick for a different size', () => {
    const form = receiveFormFor(order, branch);
    const view = (f: typeof form) => <ReceiveFormView form={f} blind={false} order={order} suppliers={[]} branches={admin.branches ?? []} onChange={() => undefined} onSubmit={() => undefined} />;
    const { rerender } = screenOf(admin, view(form));
    const size = screen.getByLabelText(/How many pieces in each carton\?/) as HTMLInputElement;
    expect(size.value).toBe('24');
    expect(size.getAttribute('aria-required')).toBe('true');
    expect(document.querySelector('label[for="rc-size-0"] .field-star')).toBeTruthy();
    // On an order line the size it started at is the one ordered, and the hint says so.
    expect(screen.getByText(/^Ordered at 24\./)).toBeTruthy();
    expect(screen.getByText(/are needed\./)).toBeTruthy();
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me: admin, branch }}>{view({ ...form, lines: form.lines.map((l) => ({ ...l, packSize: '30' })) })}</StaffContext.Provider>
      </QueryClientProvider>,
    );
    expect(screen.getByLabelText('Use 30 from now on')).toBeTruthy();
    expect(screen.getByTestId('receive-size-note-0').textContent).toBe('Only this delivery counts as 30 in each carton.');
  });
});
