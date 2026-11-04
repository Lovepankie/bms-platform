// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { BankingExpected } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #814, ADR-115 decision 11: as the owner's old app did, with the owner's savings switch on, a person who banks without
// profit access (the Till role) sees the day's savings set aside and "Expected to bank", the cash expected less the savings, and
// the amount is filled with it less what is already banked. The server decides: the screen shows what the answer carries, and
// with the switch off the answer carries no savings, so Banking is as before. Stock bought with cash never shows to them.
// Fabricated amounts.

const api = vi.hoisted(() => ({ bankingExpected: vi.fn(), createBanking: vi.fn() }));

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
      bankingExpected: (...a: unknown[]) => api.bankingExpected(...a),
      createBanking: (...a: unknown[]) => api.createBanking(...a),
      entryNorms: () => Promise.resolve([]),
    },
  };
});

const { businessToday } = await import('../../../api/retail');
const { BankingForm } = await import('./banking');
const { OfflineProvider } = await import('../../../offline/offline-context');
const { testOffline } = await import('../../../offline/test-kit');

afterEach(() => {
  cleanup();
  Object.values(api).forEach((f) => f.mockReset());
  sessionStorage.clear();
  localStorage.clear();
});

const till = mockMe('cashier');
const branch = till.branches?.[0]?.id ?? '';
const today = businessToday();
const off: BankingExpected = { branch_id: branch, business_date: today, cash_expected_minor: 150000, banked_so_far_minor: 10000 };
const on: BankingExpected = { ...off, savings_minor: 20000, savings_voids_minor: 0, expected_after_savings_minor: 130000 };

function open() {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queries}>
      <StaffContext.Provider value={{ me: till, branch }}>
        <BankingForm branchId={branch} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const row = (label: string) => screen.getByText(label).closest('.rt-row')?.textContent ?? '';

describe('Banking for the Till role and the savings switch (#814)', () => {
  it('with the switch on shows the savings set aside and Expected to bank, and fills the amount with it less what is banked', async () => {
    api.bankingExpected.mockResolvedValue(on);
    api.createBanking.mockResolvedValue({ id: 'b1', branch_id: branch, business_date: today, amount_minor: 120000 });
    open();
    await screen.findByText('Savings set aside');
    expect(row('Savings set aside')).toContain('UGX 20,000');
    expect(row('Expected to bank')).toContain('UGX 130,000');
    expect(screen.getByText('Cash expected less the savings set aside.')).toBeTruthy();
    expect(screen.queryByText(/Stock bought with cash/)).toBeNull();
    expect(screen.queryByText(/Profit/)).toBeNull();
    const amount = screen.getByLabelText('Amount banked') as HTMLInputElement;
    expect(amount.value).toBe('120000');
    fireEvent.click(screen.getByRole('button', { name: 'Save banking' }));
    await waitFor(() => expect(api.createBanking).toHaveBeenCalledTimes(1));
    expect(api.createBanking.mock.calls[0]?.[0]).toMatchObject({ branch_id: branch, amount_minor: 120000 });
  });

  it('with the switch off shows the cash expected only and fills the amount with it, as before', async () => {
    api.bankingExpected.mockResolvedValue(off);
    open();
    await screen.findByText('Cash expected');
    expect(screen.queryByText('Savings set aside')).toBeNull();
    expect(screen.queryByText('Expected to bank')).toBeNull();
    expect(screen.queryByText(/Stock bought with cash/)).toBeNull();
    expect((screen.getByLabelText('Amount banked') as HTMLInputElement).value).toBe('140000');
  });

  it('offline, from the answer kept on the phone with the switch on, shows the same lines and fills the amount (#812)', async () => {
    api.bankingExpected.mockReturnValue(new Promise(() => undefined));
    const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queries.setQueryData(['retail', 'banking-expected', branch, today], on);
    render(
      <QueryClientProvider client={queries}>
        <StaffContext.Provider value={{ me: till, branch }}>
          <OfflineProvider value={testOffline(till, { online: false })}><BankingForm branchId={branch} /></OfflineProvider>
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    await screen.findByText('Savings set aside');
    expect(row('Expected to bank')).toContain('UGX 130,000');
    expect(screen.queryByText(/Stock bought with cash/)).toBeNull();
    expect((screen.getByLabelText('Amount banked') as HTMLInputElement).value).toBe('120000');
  });
});
