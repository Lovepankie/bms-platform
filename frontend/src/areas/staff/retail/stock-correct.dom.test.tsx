// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StockMovement, StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Correcting the quantity on the stock list as a person does it (issue #351): the Correct button only for someone who may,
// the right quantity with a reason, one request however often it is pressed, a retry that repeats its Idempotency-Key, the
// category choice, the history with who and why, and each refusal in plain words. Fabricated names and amounts.

vi.setConfig({ testTimeout: 20000 });

const correctStock = vi.fn();
const stockHistory = vi.fn();
const getProduct = vi.fn();
const updateProduct = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      correctStock: (...a: unknown[]) => correctStock(...a),
      stockHistory: (...a: unknown[]) => stockHistory(...a),
      getProduct: (...a: unknown[]) => getProduct(...a),
      updateProduct: (...a: unknown[]) => updateProduct(...a),
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Cables', active: true }, { id: 'c2', name: 'Fittings', active: true }, { id: 'c3', name: 'Old', active: false }]),
    },
  };
});

const { RetailError } = await import('../../../api/retail');
const { retailMessage } = await import('../../../api/retail-errors');
const { StockTable } = await import('./stock');
const { CorrectItem } = await import('./stock-correct');
const { correctionWords, differenceWords, movementTitle, quantityProblem, signedQty } = await import('./stock-correct-state');

afterEach(() => {
  cleanup();
  correctStock.mockReset();
  stockHistory.mockReset();
  getProduct.mockReset();
  updateProduct.mockReset();
  sessionStorage.clear();
});

const admin = mockMe('admin');
const seller = mockMe('sales');
const branch = admin.branches?.[0]?.id ?? '';
const row: StockRow = { product_id: 'p1', code: 'CB-25', description: 'Cable 2.5mm', category: 'Cables', unit: 'roll', qty: '10.000', negative: false, sell_minor: 1500 };
const refusal = (code: string, detail: string) => new RetailError(retailMessage({ code, detail }, 422), 422, code);

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const open = (name: string) => fireEvent.click(screen.getByRole('radio', { name }));
const qty = () => screen.getByLabelText(/Right quantity/) as HTMLInputElement;
const reason = () => screen.getByLabelText('Why is it being corrected?') as HTMLSelectElement;
const save = () => screen.getByRole('button', { name: /Save the right quantity/ }) as HTMLButtonElement;

describe('the Correct button on a stock row', () => {
  it('is offered on the list of one branch to someone who may correct there, and opens the choices', () => {
    screenOf(admin, <StockTable rows={[row]} showCost={false} correctable={{ branchId: branch }} />);
    expect(screen.getByRole('button', { name: 'Correct Cable 2.5mm' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Correct Cable 2.5mm' }));
    expect(screen.getByTestId('correct-item')).toBeTruthy();
    for (const label of ['The quantity is wrong', 'The category is wrong', 'The unit, name or price is wrong', 'See its history']) {
      expect(screen.getByRole('radio', { name: label })).toBeTruthy();
    }
    fireEvent.click(screen.getByRole('button', { name: 'Correct Cable 2.5mm' }));
    expect(screen.queryByTestId('correct-item')).toBeNull();
  });

  it('is not on the table when nothing may be corrected', () => {
    screenOf(seller, <StockTable rows={[row]} showCost={false} />);
    expect(screen.queryByRole('button', { name: /^Correct / })).toBeNull();
  });

  it('offers a seller the history and the pointers but not the quantity or the category', () => {
    screenOf(seller, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    expect(screen.queryByRole('radio', { name: 'The quantity is wrong' })).toBeNull();
    expect(screen.queryByRole('radio', { name: 'The category is wrong' })).toBeNull();
    open('The unit, name or price is wrong');
    expect(screen.getByTestId('correct-other').textContent).toContain('Past restocks');
    expect(screen.getByTestId('correct-other').textContent).toContain('Items');
  });
});

describe('the right quantity', () => {
  it('starts with the quantity on the list and waits for a different quantity and a reason', () => {
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('The quantity is wrong');
    expect(qty().value).toBe('10');
    expect(save().disabled).toBe(true);
    fireEvent.change(qty(), { target: { value: '6' } });
    expect(save().disabled).toBe(true);
    expect(screen.getByText('That takes away 4 roll.')).toBeTruthy();
    fireEvent.change(reason(), { target: { value: 'entry_mistake' } });
    expect(save().disabled).toBe(false);
    fireEvent.change(qty(), { target: { value: '10.0' } });
    expect(save().disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toContain('It is 10 already');
    fireEvent.change(qty(), { target: { value: 'abc' } });
    expect(save().disabled).toBe(true);
    fireEvent.change(qty(), { target: { value: '12.5' } });
    expect(screen.getByText('That adds 2.5 roll.')).toBeTruthy();
  });

  it('sends one request however often Save is pressed, then says what happened', async () => {
    let finish: (v: unknown) => void = () => undefined;
    correctStock.mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('The quantity is wrong');
    fireEvent.change(qty(), { target: { value: '6' } });
    fireEvent.change(reason(), { target: { value: 'entry_mistake' } });
    fireEvent.change(screen.getByLabelText('A note (if you like)'), { target: { value: 'Typed ten, really six' } });
    fireEvent.click(save());
    fireEvent.submit(screen.getByRole('form', { name: 'Correct the quantity' }));
    fireEvent.submit(screen.getByRole('form', { name: 'Correct the quantity' }));
    await waitFor(() => expect(correctStock).toHaveBeenCalledTimes(1));
    expect(correctStock.mock.calls[0]?.[0]).toEqual({ branch_id: branch, product_id: 'p1', new_qty: '6.000', expected_qty: '10.000', reason_code: 'entry_mistake', note: 'Typed ten, really six' });
    expect(typeof correctStock.mock.calls[0]?.[1]).toBe('string');
    finish({ id: 'c1', old_qty: '10.000', new_qty: '6.000', reason_code: 'entry_mistake' });
    const done = await waitFor(() => {
      const shown = screen.getAllByRole('status').find((s) => (s.textContent ?? '').includes('is now'));
      expect(shown).toBeDefined();
      return shown as HTMLElement;
    });


    expect(done.textContent).toContain('Cable 2.5mm is now 6 roll (it was 10)');
    expect(done.textContent).toContain('does not count as shrinkage');
  });

  it('says a refusal in plain words and a retry keeps the same key', async () => {
    correctStock.mockRejectedValueOnce(refusal('quantity_unchanged', 'The quantity is 6 roll already. Type the right quantity.'));
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('The quantity is wrong');
    fireEvent.change(qty(), { target: { value: '6' } });
    fireEvent.change(reason(), { target: { value: 'miscount' } });
    fireEvent.click(save());
    expect((await screen.findByText(/already\. Type the right quantity/)).textContent).toContain('6 roll already');
    correctStock.mockResolvedValueOnce({ id: 'c2', old_qty: '10.000', new_qty: '6.000' });
    fireEvent.click(save());
    await waitFor(() => expect(correctStock).toHaveBeenCalledTimes(2));
    expect(correctStock.mock.calls[1]?.[1]).toBe(correctStock.mock.calls[0]?.[1]);
  });
});

describe('a quantity that moved meanwhile', () => {
  it('starts again from the list when a draft kept from an earlier opening was typed against another quantity', () => {
    sessionStorage.setItem(`retail-draft:stock-correct:${admin.user_id}:${branch}:p1`, JSON.stringify({ key: 'old-key', draft: { qty: '6', reason: 'miscount', note: '', expected: '12.000' } }));
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('The quantity is wrong');
    expect(qty().value).toBe('10');
    expect(reason().value).toBe('');
  });

  it('keeps a draft typed against the quantity on the list now', () => {
    sessionStorage.setItem(`retail-draft:stock-correct:${admin.user_id}:${branch}:p1`, JSON.stringify({ key: 'old-key', draft: { qty: '6', reason: 'miscount', note: '', expected: '10.000' } }));
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('The quantity is wrong');
    expect(qty().value).toBe('6');
    expect(reason().value).toBe('miscount');
  });

  it('says so in plain words when the shelf moved, and the retry is typed against the new quantity with a new key', async () => {
    correctStock.mockRejectedValueOnce(refusal('stock_moved', 'The quantity changed while you were typing: it is 8 roll now, not 10. Look at the list again and type the right quantity.'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const tree = (rowQty: string) => (
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: admin, branch }}>
          <CorrectItem row={{ ...row, qty: rowQty }} branchId={branch} onClose={() => undefined} />
        </StaffContext.Provider>
      </QueryClientProvider>
    );
    const view = render(tree('10.000'));
    open('The quantity is wrong');
    fireEvent.change(qty(), { target: { value: '6' } });
    fireEvent.change(reason(), { target: { value: 'entry_mistake' } });
    fireEvent.click(save());
    expect((await screen.findByRole('alert')).textContent).toContain('The quantity changed while you were typing');
    expect(correctStock.mock.calls[0]?.[0]).toMatchObject({ expected_qty: '10.000' });
    // The list is read again and now says 8: the form starts again from it.
    view.rerender(tree('8.000'));
    await waitFor(() => expect(qty().value).toBe('8'));
    expect(reason().value).toBe('');
    expect(screen.getByText(/In stock now/).textContent).toContain('8 roll');
    correctStock.mockResolvedValueOnce({ id: 'c3', old_qty: '8.000', new_qty: '6.000' });
    fireEvent.change(qty(), { target: { value: '6' } });
    fireEvent.change(reason(), { target: { value: 'entry_mistake' } });
    fireEvent.click(save());
    await waitFor(() => expect(correctStock).toHaveBeenCalledTimes(2));
    expect(correctStock.mock.calls[1]?.[0]).toMatchObject({ expected_qty: '8.000', new_qty: '6.000' });
    expect(correctStock.mock.calls[1]?.[1]).not.toBe(correctStock.mock.calls[0]?.[1]);
  });
});

describe('a 409 after the list already moved', () => {
  it('restarts from the list quantity and the retry sends it with a new key', async () => {
    correctStock.mockRejectedValueOnce(refusal('stock_moved', 'The quantity changed while you were typing: it is 8 roll now.'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const tree = (q: string) => (
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: admin, branch }}>
          <CorrectItem row={{ ...row, qty: q }} branchId={branch} onClose={() => undefined} />
        </StaffContext.Provider>
      </QueryClientProvider>
    );
    const view = render(tree('10.000'));
    open('The quantity is wrong');
    fireEvent.change(qty(), { target: { value: '6' } });
    fireEvent.change(reason(), { target: { value: 'miscount' } });
    view.rerender(tree('8.000'));
    await waitFor(() => expect(qty().value).toBe('8'));
    expect(screen.getAllByRole('status').some((s) => (s.textContent ?? '').includes('changed to 8'))).toBe(true);

    fireEvent.change(reason(), { target: { value: 'miscount' } });
    fireEvent.change(qty(), { target: { value: '5' } });
    fireEvent.click(save());
    await screen.findByRole('alert');
    expect(correctStock.mock.calls[0]?.[0]).toMatchObject({ expected_qty: '8.000' });
    expect(qty().value).toBe('8');
    correctStock.mockResolvedValueOnce({ id: 'c4', old_qty: '8.000', new_qty: '5.000' });
    fireEvent.change(qty(), { target: { value: '5' } });
    fireEvent.change(reason(), { target: { value: 'miscount' } });
    fireEvent.click(save());
    await waitFor(() => expect(correctStock).toHaveBeenCalledTimes(2));
    expect(correctStock.mock.calls[1]?.[0]).toMatchObject({ expected_qty: '8.000' });
    expect(correctStock.mock.calls[1]?.[1]).not.toBe(correctStock.mock.calls[0]?.[1]);
  });
});

describe('a unit in use', () => {
  it('sends nobody to change it in Items: a unit is never converted', () => {
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('The unit, name or price is wrong');
    const other = screen.getByTestId('correct-other').textContent ?? '';
    expect(other).toContain('never converted');
    expect(other).toContain('Add a new item with the right unit');
    expect(other).not.toContain('change it in Items: unit');
  });
});

describe('the category', () => {
  it('moves the item to the right category and says from where to where', async () => {
    getProduct.mockResolvedValue({ id: 'p1', description: 'Cable 2.5mm', category_id: 'c1', category: 'Cables', version: 4 });
    updateProduct.mockResolvedValue({ id: 'p1', description: 'Cable 2.5mm', category: 'Fittings' });
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('The category is wrong');
    const select = (await screen.findByLabelText('The right category')) as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.text)).toEqual(['Cables', 'Fittings']);
    const move = () => screen.getByRole('button', { name: /Move to this category/ }) as HTMLButtonElement;
    expect(move().disabled).toBe(true);
    fireEvent.change(select, { target: { value: 'c2' } });
    fireEvent.click(move());
    fireEvent.submit(screen.getByRole('form', { name: 'Change the category' }));
    await waitFor(() => expect(updateProduct).toHaveBeenCalledTimes(1));
    expect(updateProduct).toHaveBeenCalledWith('p1', 4, { category_id: 'c2' });
    expect((await screen.findByRole('status')).textContent).toContain('Cable 2.5mm is now in Fittings. It was in Cables.');
  });
});

describe('the history', () => {
  const lines: StockMovement[] = [
    { id: 'm3', at: '2026-10-08T09:00:00Z', branch_id: branch, product_id: 'p1', kind: 'adjustment', qty: '-4.000', source_type: 'retail.stock_correction', note: 'Entry mistake: Typed ten, really six', by: admin.user_id, historical: false, correction: { reason_code: 'entry_mistake', old_qty: '10.000', new_qty: '6.000' } },
    { id: 'm2', at: '2026-10-07T09:00:00Z', branch_id: branch, product_id: 'p1', kind: 'sale', qty: '-1.000', source_type: 'retail.sale', by: 'someone', historical: false },
    { id: 'm1', at: '2026-10-05T09:00:00Z', branch_id: branch, product_id: 'p1', kind: 'purchase', qty: '10.000', source_type: 'retail.purchase', by: admin.user_id, historical: false },
  ];

  it('shows each line with who and why, and the correction with the old and new quantity', async () => {
    stockHistory.mockResolvedValue({ items: lines });
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('See its history');
    const shown = await screen.findAllByTestId('history-line');
    expect(shown).toHaveLength(3);
    expect(shown[0]?.textContent).toContain('Quantity corrected');
    expect(shown[0]?.textContent).toContain('-4 roll');
    expect(shown[0]?.textContent).toContain('Corrected from 10 to 6. Entry mistake.');
    expect(shown[0]?.textContent).toContain('By you');
    expect(shown[1]?.textContent).toContain('Sale');
    expect(shown[1]?.textContent).toContain('By a colleague');
    expect(shown[2]?.textContent).toContain('Restock');
    expect(shown[2]?.textContent).toContain('+10 roll');
  });

  it('says so when nothing has moved the item', async () => {
    stockHistory.mockResolvedValue({ items: [] });
    screenOf(admin, <CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    open('See its history');
    expect(await screen.findByText('Nothing has moved this item here yet.')).toBeTruthy();
  });
});

describe('the words', () => {
  it('names the kinds of movement', () => {
    const m = (over: Partial<StockMovement>): StockMovement => ({ kind: 'adjustment', qty: '1.000', ...over });
    expect(movementTitle(m({ source_type: 'retail.stocktake' }))).toBe('Stock-take');
    expect(movementTitle(m({ source_type: 'retail.purchase_void' }))).toBe('Restock voided');
    expect(movementTitle(m({ kind: 'usage', qty: '-1.000' }))).toBe('Used');
    expect(signedQty(m({ qty: '2.500' }))).toBe('+2.5');
    expect(signedQty(m({ qty: '-2.000' }))).toBe('-2');
    expect(correctionWords(m({}))).toBeNull();
    expect(differenceWords('10', '10.000', 'roll')).toBeNull();
    expect(quantityProblem('2.5', '10.000')).toBeNull();
  });
});
