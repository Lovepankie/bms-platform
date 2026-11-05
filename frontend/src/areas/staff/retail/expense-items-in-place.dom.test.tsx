// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RetailError, type ExpenseCategory } from '../../../api/retail';
import { createMockRetail, mockMe, setMockProfitAccess } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #802, ADR-118: whoever records expenses adds a missing item in place under the Item picker, and the form comes back
// with it picked. A name the category has already picks that item. Fabricated data.

const createExpenseItem = vi.fn();
const createExpense = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      createExpenseItem: (...a: unknown[]) => createExpenseItem(...a),
      createExpense: (...a: unknown[]) => createExpense(...a),
      // The refetch after an add stays on its way, so the lists in the cache are what the form shows.
      listExpenseCategories: () => new Promise(() => undefined),
    },
  };
});

const { ExpenseForm } = await import('./expenses');

afterEach(() => {
  cleanup();
  createExpenseItem.mockReset();
  createExpense.mockReset();
  sessionStorage.clear();
});

const seller = mockMe('cashier');
const branch = seller.branches?.[0]?.id ?? '';
const categories = (): ExpenseCategory[] => [
  { id: 'c1', name: 'Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 }] },
  { id: 'c2', name: 'Cleaning', active: true, version: 1, items: [] },
];

function form(me = seller) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['retail', 'expense-categories', true], categories());
  client.setQueryData(['retail', 'cash-parties'], []);
  render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <ExpenseForm branchId={branch} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const addItem = (name: string) => {
  fireEvent.change(screen.getByLabelText(/Name of the new item/), { target: { value: name } });
  fireEvent.click(screen.getByRole('button', { name: 'Add to the list' }));
};

describe('adding an expense item in place', () => {
  it('lets a sales user add an item to an empty category and records the expense with it', async () => {
    createExpenseItem.mockResolvedValueOnce({ id: 'i9', name: 'Soap', requires_explanation: false, active: true, version: 1 });
    createExpense.mockResolvedValueOnce({ id: 'e1', amount_minor: 5000, category_name: 'Cleaning', item_name: 'Soap' });
    form();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c2' } });
    expect(screen.getByText('This category has no items yet.')).toBeTruthy();
    expect(screen.queryByText(/Ask the owner to add its items first/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add a new item' }));
    addItem('Soap');
    await waitFor(() => expect((screen.getByLabelText('Item') as HTMLSelectElement).value).toBe('i9'));
    expect(createExpenseItem).toHaveBeenCalledWith('c2', { name: 'Soap', reuse_existing: true });
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '5000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save expense' }));
    await waitFor(() => expect(createExpense).toHaveBeenCalledTimes(1));
    expect(createExpense.mock.calls[0]?.[0]).toMatchObject({ category_id: 'c2', item_id: 'i9', amount_minor: 5000 });
  });

  it('picks the existing item when the name is already in the category', async () => {
    createExpenseItem.mockResolvedValueOnce({ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 });
    form();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add a new item' }));
    addItem('fuel');
    await waitFor(() => expect((screen.getByLabelText('Item') as HTMLSelectElement).value).toBe('i1'));
    expect(screen.getAllByRole('option', { name: 'Fuel' })).toHaveLength(1);
  });

  // Review of #803. JUSTIFICATION-A3: tests the review asked for, not new mechanism.
  it('says in plain words when the owner switched that item off, and picks nothing', async () => {
    createExpenseItem.mockRejectedValueOnce(new RetailError(
      'This category has an item with that name that the owner switched off. Ask the owner to switch it on again.', 409, 'item_switched_off'));
    form();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add a new item' }));
    addItem('Old route');
    expect((await screen.findByRole('alert')).textContent).toMatch(/switched off\. Ask the owner to switch it on again/);
    expect(screen.queryByLabelText('Item')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect((screen.getByLabelText('Item') as HTMLSelectElement).value).toBe('');
  });

  it('adds the item to the category it was sent for when the category changes before the answer', async () => {
    let answer: (v: unknown) => void = () => undefined;
    createExpenseItem.mockReturnValueOnce(new Promise((r) => { answer = r; }));
    form();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add a new item' }));
    addItem('Soap');
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    answer({ id: 'i9', name: 'Soap', requires_explanation: false, active: true, version: 1 });
    await waitFor(() => expect(createExpenseItem).toHaveBeenCalledWith('c2', { name: 'Soap', reuse_existing: true }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Add to the list' })).toBeNull());
    expect(screen.queryByRole('option', { name: 'Soap' })).toBeNull();
    expect((screen.getByLabelText('Item') as HTMLSelectElement).value).toBe('');
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c2' } });
    expect(screen.getByRole('option', { name: 'Soap' })).toBeTruthy();
  });

  it('keeps the way to the owner for a user who may not add items here', () => {
    const without = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.expense.record') };
    form(without);
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c2' } });
    expect(screen.getByText('This category has no items yet. Ask the owner to add its items first.')).toBeTruthy();
  });

  // Issue #810 (the PASS review of #803). JUSTIFICATION-A3: tests the review asked for, not new mechanism.
  it('closes the sheet when the category changes, so choosing a category later never opens it unasked', () => {
    form();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add a new item' }));
    expect(screen.getByLabelText(/Name of the new item/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c2' } });
    expect(screen.queryByLabelText(/Name of the new item/)).toBeNull();
    expect(screen.getByText('This category has no items yet.')).toBeTruthy();
  });

  it('keeps the way to the owner for a user who records expenses at another shop but not this one', () => {
    const other = (seller.branches ?? []).find((b) => b.id !== branch)?.id ?? 'another-shop';
    const elsewhere = {
      ...seller,
      permission_scopes: { ...(seller.permission_scopes ?? {}), 'retail.expense.record': { all_branches: false, branch_ids: [other] } },
    };
    form(elsewhere);
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c2' } });
    expect(screen.getByText('This category has no items yet. Ask the owner to add its items first.')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    expect(screen.queryByRole('button', { name: 'Add a new item' })).toBeNull();
  });

  it('shows no Add a new item to a user who may not close the till, as the server would refuse it', () => {
    const withoutTill = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.till.close') };
    form(withoutTill);
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    expect(screen.queryByRole('button', { name: 'Add a new item' })).toBeNull();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c2' } });
    expect(screen.queryByRole('button', { name: 'Add a new item' })).toBeNull();
  });

  it('shows no Add a new item to a user without the expense permission', () => {
    const without = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.expense.record') };
    form(without);
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    expect(screen.queryByRole('button', { name: 'Add a new item' })).toBeNull();
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c2' } });
    expect(screen.queryByRole('button', { name: 'Add a new item' })).toBeNull();
  });
});

describe('the mock answers like the API', () => {
  it('reuses an active item of the same name and refuses a switched off one', async () => {
    setMockProfitAccess(true);
    const mock = createMockRetail();
    const [transport] = await mock.listExpenseCategories({ activeOnly: true });
    const added = await mock.createExpenseItem(transport!.id!, { name: 'Test Item 01', reuse_existing: true });
    expect((await mock.createExpenseItem(transport!.id!, { name: 'test item 01', reuse_existing: true })).id).toBe(added.id);
    expect((await mock.createExpenseItem(transport!.id!, { name: ' Test  item\t01 ', reuse_existing: true })).id).toBe(added.id);
    await mock.updateExpenseItem(transport!.id!, added.id!, { active: false }, added.version!);
    const refused = await mock.createExpenseItem(transport!.id!, { name: 'Test Item 01', reuse_existing: true }).catch((e: { code?: string }) => e);
    expect((refused as { code?: string }).code).toBe('item_switched_off');
  });

  // Issue #810. JUSTIFICATION-A3: the review of #803 asks the mock to apply the server's rules.
  it('refuses a switched off category and the explanation flag to a session that does not manage the lists', async () => {
    setMockProfitAccess(true);
    const mock = createMockRetail();
    const [transport] = await mock.listExpenseCategories({ activeOnly: true });
    setMockProfitAccess(false);
    const flagged = await mock.createExpenseItem(transport!.id!, { name: 'Test Item 02', requires_explanation: true, reuse_existing: true }).catch((e: { status?: number }) => e);
    expect((flagged as { status?: number }).status).toBe(403);
    setMockProfitAccess(true);
    await mock.updateExpenseCategory(transport!.id!, { active: false }, transport!.version!);
    setMockProfitAccess(false);
    const off = await mock.createExpenseItem(transport!.id!, { name: 'Test Item 03', reuse_existing: true }).catch((e: { code?: string }) => e);
    expect((off as { code?: string }).code).toBe('category_inactive');
    setMockProfitAccess(true);
  });

  it('stores a rename with one space between words and refuses a name the category has already', async () => {
    setMockProfitAccess(true);
    const mock = createMockRetail();
    const [transport] = await mock.listExpenseCategories({ activeOnly: true });
    const first = await mock.createExpenseItem(transport!.id!, { name: 'Test Item 04' });
    const second = await mock.createExpenseItem(transport!.id!, { name: 'Test Item 05' });
    expect((await mock.updateExpenseItem(transport!.id!, second.id!, { name: ' Test   Item\t06 ' }, second.version!)).name).toBe('Test Item 06');
    const taken = await mock.updateExpenseItem(transport!.id!, first.id!, { name: 'test  item 06' }, first.version!).catch((e: { code?: string }) => e);
    expect((taken as { code?: string }).code).toBe('duplicate_item');
  });
});
