// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ExpenseCategory, Product, SavingsSuggestion, StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #824: the owner wants a short toast after every save on the daily shop screens, which says in plain words what
// happened and closes by itself: stock correction, Buy stock, Move stock, usage and damage, expenses, savings and banking.
// Each form is used as its page uses it, with the app's one DoneToast mounted, so the toast is the one the person sees.
// A save that waits for approval shows no success toast. Fabricated names, codes and amounts.

vi.setConfig({ testTimeout: 20000 });

const api = vi.hoisted(() => ({
  correctStock: vi.fn(),
  createPurchase: vi.fn(),
  createTransfer: vi.fn(),
  createUsage: vi.fn(),
  createExpense: vi.fn(),
  savingsSuggestion: vi.fn(),
  createSavings: vi.fn(),
  listSavings: vi.fn(),
  bankingExpected: vi.fn(),
  createBanking: vi.fn(),
  createWithdrawal: vi.fn(),
  createAdvance: vi.fn(),
}));

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useNavigate: () => vi.fn(), Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  const pass = (name: keyof typeof api) => (...a: unknown[]) => api[name](...a);
  return {
    ...real,
    retail: {
      ...real.retail,
      ...Object.fromEntries(Object.keys(api).map((name) => [name, pass(name as keyof typeof api)])),
      entryNorms: () => Promise.resolve([]),
      listProducts: () => Promise.resolve([]),
      listSuppliers: () => Promise.resolve([]),
      listCategories: () => Promise.resolve([]),
      stockHistory: () => Promise.resolve([]),
      // The lists read again after a save stay on their way, so what the form shows comes from the save.
      listExpenseCategories: () => new Promise(() => undefined),
    },
  };
});

const { businessToday } = await import('../../../api/retail');
const { CorrectItem } = await import('./stock-correct');
const { RestockForm } = await import('./restock');
const { TransferForm } = await import('./transfer');
const { UsageForm } = await import('./usage');
const { ExpenseForm } = await import('./expenses');
const { SavingsScreen } = await import('./savings');
const { BankingForm } = await import('./banking');
const { WithdrawalForm } = await import('./withdrawals');
const { AdvanceForm } = await import('./advances');
const { DoneToast, TOAST_MS, doneToasts } = await import('./toast');

afterEach(() => {
  cleanup();
  Object.values(api).forEach((f) => f.mockReset());
  sessionStorage.clear();
  localStorage.clear();
});

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';
const other = admin.branches?.[1];
const today = businessToday();
const soap: Product = { id: 'p1', code: 'TS-1', description: 'Test Soap', unit: 'piece', unit_id: 'u1', sell_minor: 1500, cost_minor: 1000, active: true, qty: '20.000' };

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() });
}

function screenOf(node: ReactElement, queries = client()) {
  return render(
    <QueryClientProvider client={queries}>
      <StaffContext.Provider value={{ me: admin, branch }}>{node}</StaffContext.Provider>
      <DoneToast />
    </QueryClientProvider>,
  );
}

/** A draft kept in this tab, as the form left it, so the test starts at Save. */
function drafted(name: string, draft: unknown) {
  sessionStorage.setItem(`retail-draft:${name}`, JSON.stringify({ key: `test-key-${name}`, draft }));
}

const successToasts = () => Array.from(document.querySelectorAll('.toast-success')).map((t) => t.textContent ?? '');

/** The one success toast, a polite status with its check icon, saying `words`. */
async function toastSays(words: string | RegExp) {
  await waitFor(() => expect(successToasts()).toHaveLength(1));
  const toast = document.querySelector('.toast-success');
  expect(toast?.getAttribute('role')).toBe('status');
  expect(toast?.getAttribute('aria-live')).toBe('polite');
  expect(toast?.querySelector('.toast-icon svg')).toBeTruthy();
  if (typeof words === 'string') expect(toast?.textContent).toBe(words);
  else expect(toast?.textContent).toMatch(words);
}

describe('a toast after every save on the daily shop screens (#824)', () => {
  it('Correct stock says the new quantity', async () => {
    const row: StockRow = { product_id: 'p1', code: 'TS-1', description: 'Test Soap', category: 'Soap', unit: 'piece', qty: '10.000', negative: false, sell_minor: 1500 };
    api.correctStock.mockResolvedValue({ id: 'c1', old_qty: '10.000', new_qty: '4.000', reason_code: 'entry_mistake' });
    screenOf(<CorrectItem row={row} branchId={branch} onClose={() => undefined} />);
    fireEvent.click(screen.getByRole('radio', { name: 'The quantity is wrong' }));
    fireEvent.change(screen.getByLabelText(/Right quantity/), { target: { value: '4' } });
    fireEvent.change(screen.getByLabelText('Why is it being corrected?'), { target: { value: 'entry_mistake' } });
    fireEvent.click(screen.getByRole('button', { name: /Save the right quantity/ }));
    await toastSays('Stock of Test Soap corrected to 4 piece');
  });

  it('Buy stock says the purchase is saved, beside the purchase saved screen', async () => {
    api.createPurchase.mockResolvedValue({ id: 'r1', purchase_no: 'RP00000042', lines: [] });
    const saved = vi.fn();
    const draft = { supplierId: '', purchasedOn: today, method: 'cash' as const, lines: [{ product: soap, cost: '1000', sell: '1500', qtyByBranch: { [branch]: '2' } }] };
    screenOf(<RestockForm draftName="restock-toast:test" initial={draft} onSaved={saved} />);
    fireEvent.click(screen.getByRole('button', { name: 'Save the purchase' }));
    await toastSays('Purchase RP00000042 saved');
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it('Move stock says where the stock went', async () => {
    api.createTransfer.mockResolvedValue({ id: 't1', status: 'completed', from_branch_id: branch, to_branch_id: other?.id, lines: [] });
    drafted(`transfer:${admin.user_id}:${branch}`, { toBranchId: other?.id ?? '', transferDate: today, note: '', lines: [{ product: soap, qty: '3' }] });
    const saved = vi.fn();
    screenOf(<TransferForm fromBranchId={branch} onSaved={saved} />);
    fireEvent.click(screen.getByRole('button', { name: 'Move stock' }));
    await toastSays(new RegExp(`^Stock moved to ${other?.name ?? ''}`));
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it('Usage and damage says the damage is recorded, with its Saved screen', async () => {
    api.createUsage.mockResolvedValue({ id: 'g1', kind: 'damaged', lines: [{ product_id: 'p1', qty: '1.000' }] });
    drafted(`usage:${admin.user_id}:${branch}`, { kind: 'damaged', reason: 'Dropped on the floor', lines: [{ product: soap, qty: '1' }] });
    const saved = vi.fn();
    screenOf(<UsageForm branchId={branch} onSaved={saved} />);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await toastSays('Damage recorded, stock reduced');
    expect(saved).toHaveBeenCalledTimes(1);
  });

  const categories = (): ExpenseCategory[] => [
    { id: 'c1', name: 'Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 }] },
  ];
  function expenseForm(onSaved: () => void, onWaiting: () => void) {
    const queries = client();
    queries.setQueryData(['retail', 'expense-categories', true], categories());
    queries.setQueryData(['retail', 'cash-parties'], []);
    screenOf(<ExpenseForm branchId={branch} onSaved={onSaved} onWaiting={onWaiting} />, queries);
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    fireEvent.change(screen.getByLabelText('Item'), { target: { value: 'i1' } });
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '5000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save expense' }));
  }

  it('Expenses says the expense is saved', async () => {
    api.createExpense.mockResolvedValue({ id: 'e1', amount_minor: 5000, category_name: 'Transport', item_name: 'Fuel' });
    const saved = vi.fn();
    expenseForm(saved, () => undefined);
    await toastSays('Expense of UGX 5,000 saved');
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it('Expenses shows no success toast for an expense that waits for approval', async () => {
    api.createExpense.mockResolvedValue({ approval_id: 'a1', status: 'pending' });
    const waiting = vi.fn();
    expenseForm(() => undefined, waiting);
    await waitFor(() => expect(waiting).toHaveBeenCalledTimes(1));
    // Asserted after the toast has had time to come, so a late toast would fail it (review of #828).
    await new Promise((done) => setTimeout(done, 100));
    expect(successToasts()).toEqual([]);
  });

  it('Savings says the savings are saved, beside the Saved panel', async () => {
    const day: SavingsSuggestion = {
      branch_id: branch, business_date: today, total_sold_minor: 50000, suggestion_token: 'tok-1',
      daily_profit_minor: 12000, day_expenses_minor: 4000, available_minor: 8000, suggested_minor: 4000,
    };
    api.savingsSuggestion.mockResolvedValue(day);
    api.listSavings.mockResolvedValue([]);
    api.createSavings.mockResolvedValue({ id: 'sv-1', branch_id: branch, business_date: today, currency: 'UGX', amount_minor: 4000, suggested_minor: 4000, overwritten: false, voided: false });
    screenOf(<SavingsScreen />);
    await screen.findByText('UGX 4,000', { selector: '.key-figure-value' });
    fireEvent.click(screen.getByRole('button', { name: 'Save savings' }));
    await toastSays('Savings of UGX 4,000 saved');
    expect(screen.getByRole('region', { name: 'Savings recorded' })).toBeTruthy();
  });

  it('Banking says the banking is saved', async () => {
    api.bankingExpected.mockResolvedValue({ branch_id: branch, business_date: today });
    api.createBanking.mockResolvedValue({ id: 'b1', branch_id: branch, business_date: today, amount_minor: 30000 });
    const saved = vi.fn();
    screenOf(<BankingForm branchId={branch} onSaved={saved} />);
    await waitFor(() => expect(screen.queryByText('Loading')).toBeNull());
    fireEvent.change(screen.getByLabelText('Amount banked'), { target: { value: '30000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save banking' }));
    await toastSays('Banking of UGX 30,000 saved');
    expect(saved).toHaveBeenCalledTimes(1);
    // It closes by itself after about four seconds.
    await waitFor(() => expect(document.querySelector('.toast')).toBeNull(), { timeout: TOAST_MS + 2000 });
  });

  // JUSTIFICATION-A3: the review of #828 asks for DOM tests of withdrawals and advances, one test per screen.
  it('Withdrawals says the withdrawal is saved', async () => {
    api.createWithdrawal.mockResolvedValue({ id: 'w1', branch_id: branch, business_date: today, amount_minor: 20000 });
    screenOf(<WithdrawalForm branchId={branch} onSaved={() => undefined} />);
    fireEvent.change(screen.getByLabelText('Amount withdrawn'), { target: { value: '20000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save withdrawal' }));
    await toastSays('Withdrawal of UGX 20,000 saved');
  });

  it('Advances says the advance is saved', async () => {
    api.createAdvance.mockResolvedValue({ id: 'a1', branch_id: branch, business_date: today, principal_minor: 15000 });
    const queries = client();
    queries.setQueryData(['retail', 'cash-parties'], [{ id: 'cp1', name: 'Test Staff 07', kind: 'staff', active: true }]);
    screenOf(<AdvanceForm branchId={branch} onSaved={() => undefined} />, queries);
    fireEvent.change(screen.getByLabelText('Who is the advance for?'), { target: { value: 'cp1' } });
    fireEvent.change(screen.getByLabelText('Amount advanced'), { target: { value: '15000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save advance' }));
    await toastSays('Advance of UGX 15,000 saved');
  });
});
