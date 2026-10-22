// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product, Purchase } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Correcting a mistaken restock as a person does it (issue #351): who is offered the button, the reason it needs, one request
// however often it is pressed, a retry that repeats its Idempotency-Key, "void and enter it again" opening the form with the same
// lines, a voided restock shown as voided, the hint about a unit, and each refusal in plain words. Fabricated names and amounts.

vi.setConfig({ testTimeout: 20000 });

const voidPurchase = vi.fn();
const navigate = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useNavigate: () => navigate, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      voidPurchase: (...a: unknown[]) => voidPurchase(...a),
      listProducts: () => Promise.resolve([]),
      listSuppliers: () => Promise.resolve([]),
    },
  };
});

const { RetailError } = await import('../../../api/retail');
const { retailMessage } = await import('../../../api/retail-errors');
const { CorrectPanel, RestockCard } = await import('./restocks');
const { RestockForm } = await import('./restock');
const { chosenUnitHint, redraftOf, unitHint, voidReasonOf } = await import('./restock-correct-state');

afterEach(() => {
  cleanup();
  voidPurchase.mockReset();
  navigate.mockReset();
  sessionStorage.clear();
});

const admin = mockMe('admin');
const seller = mockMe('sales');
const branch = admin.branches?.[0]?.id ?? '';
const other = admin.branches?.[1]?.id ?? '';
const restock: Purchase = {
  id: 'r1', purchase_no: 'RP00000001', purchased_on: '2026-10-05', payment_method: 'cash', currency: 'UGX', total_minor: 20000, status: 'posted',
  lines: [{ id: 'l1', line_no: 1, product_id: 'p1', code: 'CB-25', description: 'Cable 2.5mm', cost_minor: 1000, sell_minor: 1500, qty_total: '20.000', line_total_minor: 20000, base_unit: 'roll', qty_by_branch: [{ branch_id: branch, qty: '20.000' }] }],
};
const refusal = (code: string, detail: string, status = 422) => new RetailError(retailMessage({ code, detail }, status), status, code);

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const reason = () => screen.getByLabelText(/Why is it being corrected/) as HTMLInputElement;
const again = () => screen.getByRole('button', { name: 'Void and enter it again' }) as HTMLButtonElement;
const only = () => screen.getByRole('button', { name: 'Void only' }) as HTMLButtonElement;

describe('the restock card', () => {
  it('offers Correct this restock to someone who manages every branch it touched, and to nobody else', () => {
    screenOf(admin, <ul><RestockCard purchase={restock} supplier="Test Supplier 01" onDone={() => undefined} /></ul>);
    expect(screen.getByRole('button', { name: 'Correct restock RP00000001' })).toBeTruthy();
    expect(screen.getByText(/Test Supplier 01/)).toBeTruthy();
    cleanup();
    const twoBranches: Purchase = { ...restock, lines: [{ ...(restock.lines?.[0] ?? {}), qty_by_branch: [{ branch_id: branch, qty: '10.000' }, { branch_id: other, qty: '10.000' }] }] };
    const oneBranch = { ...admin, permission_scopes: { ...admin.permission_scopes, 'retail.purchase.create': { all_branches: false, branch_ids: [branch] } } };
    screenOf(oneBranch, <ul><RestockCard purchase={twoBranches} onDone={() => undefined} /></ul>);
    expect(screen.queryByRole('button', { name: 'Correct restock RP00000001' })).toBeNull();
    expect(screen.getByText(/Only someone who manages every branch/)).toBeTruthy();
    cleanup();
    screenOf(seller, <ul><RestockCard purchase={restock} onDone={() => undefined} /></ul>);
    expect(screen.queryByRole('button', { name: /Correct restock/ })).toBeNull();
  });

  it('offers no button on a copy that hides other branches, since the server would refuse the void', () => {
    screenOf(admin, <ul><RestockCard purchase={{ ...restock, partial: true }} onDone={() => undefined} /></ul>);
    expect(screen.queryByRole('button', { name: /Correct restock/ })).toBeNull();
    expect(screen.getByText(/Only someone who manages every branch/)).toBeTruthy();
  });

  it('shows a voided restock as voided with its reason and offers nothing', () => {
    const voided: Purchase = { ...restock, status: 'voided', voided_on: '2026-10-06', void_reason: 'Entered twenty, there were ten' };
    screenOf(admin, <ul><RestockCard purchase={voided} onDone={() => undefined} /></ul>);
    expect(screen.getByText('Voided', { selector: 'span' })).toBeTruthy();
    expect(screen.getByTestId('void-note').textContent).toContain('Entered twenty, there were ten');
    expect(screen.queryByRole('button', { name: /Correct restock/ })).toBeNull();
  });

  it('hints when a line was entered in a unit that is not the item\'s usual one', () => {
    const metres: Purchase = { ...restock, lines: [{ ...(restock.lines?.[0] ?? {}), unit_id: 'u2', unit: 'metre', unit_qty_total: '200.000', base_unit: 'roll' }] };
    screenOf(admin, <ul><RestockCard purchase={metres} onDone={() => undefined} /></ul>);
    expect(screen.getByTestId('unit-hint').textContent).toContain('Entered in metre but this item is kept in roll?');
    expect(screen.getByText(/200 metre/)).toBeTruthy();
    expect(unitHint({ unit: 'roll', base_unit: 'roll' })).toBeNull();
    expect(unitHint({ base_unit: 'roll' })).toBeNull();
    // The same words on the restock form, where the unit is the one chosen for the line.
    expect(chosenUnitHint('metre', 'roll')).toBe('Entered in metre but this item is kept in roll? If that is wrong, correct this restock and enter it again in the right unit.');
    expect(chosenUnitHint(undefined, 'roll')).toBeNull();
    expect(chosenUnitHint('roll', 'roll')).toBeNull();
  });
});

describe('correcting a restock', () => {
  it('needs a reason of at least five characters before either button works', () => {
    screenOf(admin, <CorrectPanel purchase={restock} onDone={() => undefined} onCancel={() => undefined} />);
    expect(again().disabled).toBe(true);
    expect(only().disabled).toBe(true);
    fireEvent.change(reason(), { target: { value: 'no' } });
    expect(again().disabled).toBe(true);
    fireEvent.change(reason(), { target: { value: 'Entered twenty, there were ten' } });
    expect(again().disabled).toBe(false);
    expect(only().disabled).toBe(false);
    expect(voidReasonOf('  abcde ')).toBe('abcde');
    expect(voidReasonOf('abcd')).toBeNull();
  });

  it('voids once however often the button is pressed, says so, and keeps one key for a retry', async () => {
    let finish: (v: unknown) => void = () => undefined;
    voidPurchase.mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    const done = vi.fn();
    screenOf(admin, <CorrectPanel purchase={restock} onDone={done} onCancel={() => undefined} />);
    fireEvent.change(reason(), { target: { value: 'Entered twenty, there were ten' } });
    fireEvent.click(only());
    fireEvent.click(only());
    fireEvent.submit(screen.getByRole('form', { name: 'Correct this restock' }));
    await waitFor(() => expect(voidPurchase).toHaveBeenCalledTimes(1));
    expect(voidPurchase.mock.calls[0]?.slice(0, 2)).toEqual(['r1', 'Entered twenty, there were ten']);
    const firstKey = voidPurchase.mock.calls[0]?.[2];
    expect(typeof firstKey).toBe('string');
    finish({ ...restock, status: 'voided' });
    await waitFor(() => expect(done).toHaveBeenCalledWith(expect.stringContaining('RP00000001 is voided')));
    expect(navigate).not.toHaveBeenCalled();
  });

  it('opens the restock form again with this restock when asked to void and enter it again', async () => {
    voidPurchase.mockResolvedValue({ ...restock, status: 'voided' });
    screenOf(admin, <CorrectPanel purchase={restock} onDone={() => undefined} onCancel={() => undefined} />);
    fireEvent.change(reason(), { target: { value: 'Entered twenty, there were ten' } });
    fireEvent.click(again());
    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/staff/retail/restock', search: { again: 'r1' } }));
  });

  it('says a refusal in the server\'s plain words and lets the person try again', async () => {
    voidPurchase.mockRejectedValueOnce(refusal('purchase_stock_moved', 'Cable 2.5mm: this restock added 20 roll but only 12 are in stock there now. Use Correct the quantity on the stock list.'));
    screenOf(admin, <CorrectPanel purchase={restock} onDone={() => undefined} onCancel={() => undefined} />);
    fireEvent.change(reason(), { target: { value: 'Entered twenty, there were ten' } });
    fireEvent.click(only());
    expect((await screen.findByRole('alert')).textContent).toContain('only 12 are in stock there now');
    voidPurchase.mockResolvedValueOnce({ ...restock, status: 'voided' });
    await new Promise((r) => setTimeout(r));
    await new Promise((r) => setTimeout(r));
    fireEvent.click(only());

    await waitFor(() => expect(voidPurchase).toHaveBeenCalledTimes(2));
    expect(voidPurchase.mock.calls[1]?.[2]).toBe(voidPurchase.mock.calls[0]?.[2]);
  });

  it('treats a restock found voided already as done', async () => {
    voidPurchase.mockRejectedValue(refusal('purchase_voided', 'This restock has been voided already.', 409));
    const done = vi.fn();
    screenOf(admin, <CorrectPanel purchase={restock} onDone={done} onCancel={() => undefined} />);
    fireEvent.change(reason(), { target: { value: 'Entered twenty, there were ten' } });
    fireEvent.click(only());
    await waitFor(() => expect(done).toHaveBeenCalledWith(expect.stringContaining('was voided already')));
  });
});

describe('entering a voided restock again', () => {
  const cable: Product = { id: 'p1', code: 'CB-25', description: 'Cable 2.5mm', unit: 'roll', unit_id: 'u1', sell_minor: 1500, cost_minor: 1000, active: true };

  it('fills the form with the same supplier, date, payment, items and quantities', () => {
    const draft = redraftOf({ ...restock, supplier_id: 's1', payment_method: 'credit' }, [cable], '2026-10-20');
    // Dated today, not on the day of the voided restock, which may be banked in the cash book already.
    expect(draft).toMatchObject({ supplierId: 's1', purchasedOn: '2026-10-20', method: 'credit' });
    expect(draft.lines).toHaveLength(1);
    expect(draft.lines[0]).toMatchObject({ cost: '1000', sell: '1500', qtyByBranch: { [branch]: '20' } });
    expect(draft.lines[0]?.unitId).toBeUndefined();
  });

  it('keeps the unit and its quantity for a line entered in a pack, and leaves out an item that is gone', () => {
    const inPack: Purchase = {
      ...restock,
      lines: [
        { ...(restock.lines?.[0] ?? {}), unit_id: 'u2', unit: 'metre', unit_cost_minor: 10, unit_qty_total: '200.000', qty_by_branch: [{ branch_id: branch, qty: '2.000', unit_qty: '200.000' }] },
        { id: 'l2', line_no: 2, product_id: 'gone', description: 'Gone', cost_minor: 5, qty_by_branch: [] },
      ],
    };
    const draft = redraftOf(inPack, [cable], '2026-10-20');
    expect(draft.lines).toHaveLength(1);
    expect(draft.lines[0]).toMatchObject({ unitId: 'u2', cost: '10', qtyByBranch: { [branch]: '200' } });
  });
});

describe('the date of a restock entered again', () => {
  const draft = { supplierId: '', newSupplier: '', purchasedOn: '2020-01-01', method: 'cash' as const, lines: [] };

  it('warns in plain words when it is dated before today', () => {
    screenOf(admin, <RestockForm draftName="restock-again:test:r1" initial={draft} again />);
    expect(screen.getByTestId('date-warning').textContent).toMatch(/banked already/);
    expect(screen.getByTestId('date-warning').textContent).toContain('Use today\'s date');
  });

  it('is quiet for a new restock and for today', () => {
    screenOf(admin, <RestockForm draftName="restock-plain:test" initial={draft} />);
    expect(screen.queryByTestId('date-warning')).toBeNull();
  });
});
