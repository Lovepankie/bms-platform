// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Savings, SavingsSuggestion } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Saving the day's savings as a person does it (issue #538): the three figures (profit, expenses, available), an amount above the
// available refused under the field in plain words before anything is sent, and after a save the dark toast with the amount and
// the day, the saved state and the record in Savings records at once. Fabricated names and amounts.

vi.setConfig({ testTimeout: 20000 });

const savingsSuggestion = vi.fn();
const createSavings = vi.fn();
const listSavings = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useNavigate: () => vi.fn(), Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      savingsSuggestion: (...a: unknown[]) => savingsSuggestion(...a),
      createSavings: (...a: unknown[]) => createSavings(...a),
      listSavings: (...a: unknown[]) => listSavings(...a),
    },
  };
});

const { businessToday } = await import('../../../api/retail');
const { SavingsScreen } = await import('./savings');

afterEach(() => {
  cleanup();
  savingsSuggestion.mockReset();
  createSavings.mockReset();
  listSavings.mockReset();
  sessionStorage.clear();
  localStorage.clear();
});

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';
const today = businessToday();
const day: SavingsSuggestion = {
  branch_id: branch, business_date: today, total_sold_minor: 50000, suggestion_token: 'tok-1',
  daily_profit_minor: 12000, day_expenses_minor: 4000, available_minor: 8000, suggested_minor: 4000,
};
const saved: Savings = {
  id: 'sv-1', branch_id: branch, business_date: today, currency: 'UGX', amount_minor: 4000, suggested_minor: 4000, overwritten: false,
  total_sold_minor: 50000, by_name: 'Test User 01', at: new Date().toISOString(), voided: false, historical: false,
};

function open() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: admin, branch }}>
        <SavingsScreen />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const amount = () => screen.getByLabelText(/Amount to save/) as HTMLInputElement;
const saveButton = () => screen.getByRole('button', { name: 'Save savings' }) as HTMLButtonElement;

describe('the savings screen', () => {
  it("shows the day's profit, expenses and what is available to save", async () => {
    savingsSuggestion.mockResolvedValue(day);
    listSavings.mockResolvedValue([]);
    open();
    const figures = await screen.findByLabelText("The day's figures");
    expect(figures.textContent).toContain('Profit for the day');
    expect(figures.textContent).toContain('UGX 12,000');
    expect(figures.textContent).toContain('Expenses for the day');
    expect(figures.textContent).toContain('UGX 4,000');
    expect(figures.textContent).toContain('Available to save');
    expect(figures.textContent).toContain('UGX 8,000');
    expect(screen.getByText('UGX 4,000', { selector: '.key-figure-value' })).toBeTruthy();
  });

  it('refuses an amount above the available under the field, in plain words, and sends nothing', async () => {
    savingsSuggestion.mockResolvedValue(day);
    listSavings.mockResolvedValue([]);
    open();
    await screen.findByText('UGX 4,000', { selector: '.key-figure-value' });
    fireEvent.change(amount(), { target: { value: '9000' } });
    // Nothing is said while typing (rule 10); the words come on blur.
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.blur(amount());
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toBe('You can save at most UGX 8,000 today (profit UGX 12,000 minus expenses UGX 4,000).');
    expect(amount().getAttribute('aria-describedby')).toBe(alert.id);
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    fireEvent.click(saveButton());
    expect(createSavings).not.toHaveBeenCalled();

    fireEvent.change(amount(), { target: { value: '8000' } });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(saveButton().getAttribute('data-not-ready')).toBeNull();
  });

  it('offers the reason only as an optional pick list, and saves a changed amount without one', async () => {
    savingsSuggestion.mockResolvedValue(day);
    listSavings.mockResolvedValue([]);
    createSavings.mockResolvedValue({ ...saved, amount_minor: 6000, overwritten: true });
    open();
    await screen.findByText('UGX 4,000', { selector: '.key-figure-value' });
    fireEvent.change(amount(), { target: { value: '6000' } });
    expect(screen.getByRole('button', { name: 'Add a reason (optional)' })).toBeTruthy();
    expect(screen.queryByLabelText(/Why are you changing/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add a reason (optional)' }));
    const pick = screen.getByLabelText('Reason (optional)') as HTMLSelectElement;
    expect([...pick.options].map((o) => o.text)).toEqual(['No reason', 'Paying a debt', 'Buying stock', 'Owner took money', 'Slow day', 'Other']);
    fireEvent.change(pick, { target: { value: 'Other' } });
    expect(screen.getByLabelText('Say it in a few words (optional)')).toBeTruthy();
    fireEvent.change(pick, { target: { value: '' } });
    fireEvent.click(saveButton());
    await waitFor(() => expect(createSavings).toHaveBeenCalledTimes(1));
    const body = createSavings.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body).toMatchObject({ amount_minor: 6000, suggestion_token: 'tok-1' });
    expect('overwrite_reason' in body).toBe(false);
  });

  it('after a save shows the dark toast with the amount and the day, the saved state and the record at once', async () => {
    savingsSuggestion.mockResolvedValue(day);
    // The refetch after the save is held back, so the record can only come from the save itself.
    let release: (rows: Savings[]) => void = () => {};
    listSavings.mockResolvedValueOnce([]).mockImplementation(() => new Promise<Savings[]>((r) => { release = r; }));
    createSavings.mockResolvedValue(saved);
    open();
    await screen.findByText('UGX 4,000', { selector: '.key-figure-value' });
    await screen.findByText('No savings recorded in this period.');
    fireEvent.click(saveButton());

    const words = `Savings of UGX 4,000 saved for ${today.slice(8, 10).replace(/^0/, '')} `;
    const toast = await screen.findByText((t) => t.startsWith(words));
    expect(toast.closest('.toast')?.className).toContain('toast-success');
    expect(screen.getByRole('region', { name: 'Savings recorded' })).toBeTruthy();
    expect(screen.getByText('Record another day')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Save savings' })).toBeNull();
    const records = screen.getByRole('region', { name: 'Savings records' });
    await waitFor(() => expect(records.textContent).toContain('Saved UGX 4,000'));
    expect(records.textContent).not.toContain('No savings recorded in this period.');
    release([saved]);
  });
});
