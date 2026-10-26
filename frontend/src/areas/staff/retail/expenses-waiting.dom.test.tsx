// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ExpenseCategory } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #343, ADR-091: an expense worth the business's approval amount waits for an administrator. The form does not say it was
// recorded; it says it waits, that nothing is in the cash book yet, and where to see or cancel it. Fabricated data.

const createExpense = vi.fn();
const onSaved = vi.fn();
const onWaiting = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, createExpense: (...a: unknown[]) => createExpense(...a) } };
});

const { ExpenseForm } = await import('./expenses');

afterEach(() => {
  cleanup();
  createExpense.mockReset();
  onSaved.mockReset();
  onWaiting.mockReset();
  sessionStorage.clear();
});

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';
const categories: ExpenseCategory[] = [
  { id: 'c1', name: 'Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 }] },
];

function form() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['retail', 'expense-categories', true], categories);
  client.setQueryData(['retail', 'cash-parties'], []);
  render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: admin, branch }}>
        <ExpenseForm branchId={branch} onSaved={onSaved} onWaiting={onWaiting} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
  fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
  fireEvent.change(screen.getByLabelText('Item'), { target: { value: 'i1' } });
  fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '80000' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save expense' }));
}

describe('an expense that waits for approval', () => {
  it('hands the screen the amount that waits, and never reports it as recorded', async () => {
    createExpense.mockResolvedValueOnce({ approval_id: 'a1', action_type: 'retail_expense', amount_minor: 80000, currency: 'UGX' });
    form();
    await waitFor(() => expect(onWaiting).toHaveBeenCalledWith(80000));
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('reports a recorded expense as before while approval is off', async () => {
    createExpense.mockResolvedValueOnce({ id: 'e1', amount_minor: 80000, category_name: 'Transport', item_name: 'Fuel' });
    form();
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(onWaiting).not.toHaveBeenCalled();
  });
});
