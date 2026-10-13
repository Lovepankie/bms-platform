// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AccountPayment } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Reversing two payments on one account page (ADR-033, issue #196): a failed reversal of the first must not hand its
// Idempotency-Key to the second, which goes to a different path. Fabricated names and amounts.

const reverse = vi.fn();
const receipt = (id: string, no: string, branch: string): AccountPayment => ({
  id, receipt_no: no, customer_id: 'c1', branch_id: branch, amount_minor: 500, method: 'cash', paid_on: '2026-04-01', allocation: 'oldest_first', status: 'posted',
  allocations: [{ sale_id: 'b', sale_no: 'RS-b', amount_minor: 500 }],
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  const branch = 'b1';
  return {
    ...real,
    retail: {
      customerAccount: async () => ({ customer_id: 'c1', name: 'Test Buyer 01', currency: 'UGX', balance_minor: 0, version: 1 }),
      customerBalance: async () => ({ customer_id: 'c1', currency: 'UGX', balance_minor: 0, open_sales: [] }),
      listAccountPayments: async () => ({ items: [receipt('r-a', 'RC00000001', branch), receipt('r-b', 'RC00000002', branch)] }),
      customerStatement: async () => ({ customer_id: 'c1', name: 'Test Buyer 01', currency: 'UGX', from: '2026-01-01', to: '2026-04-01', opening_minor: 0, lines: [], closing_minor: 0, balance_minor: 0 }),
      reverseAccountPayment: (...args: unknown[]) => reverse(...args),
    },
  };
});

const { AccountScreen } = await import('./account');

afterEach(() => {
  cleanup();
  reverse.mockReset();
});

describe('reversing payments on the account page', () => {
  it('sends a key of its own for each receipt, and repeats a receipt\'s key on its retry', async () => {
    const me = { ...mockMe('admin'), module_capabilities: { retail: ['customer_accounts'] }, branches: [{ id: 'b1', name: 'Test Branch A', code: 'BR1' }] };
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
    reverse.mockRejectedValueOnce(new Error('the network dropped'));
    reverse.mockImplementation(async (_customer: string, id: string) => ({ ...receipt(id, id === 'r-a' ? 'RC00000001' : 'RC00000002', 'b1'), status: 'reversed' }));
    render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <StaffContext.Provider value={{ me, branch: 'b1' }}>
            <AccountScreen customerId="c1" me={me} branch="b1" />
          </StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
    const reverseOne = async (no: string, why: string) => {
      fireEvent.click(await screen.findByRole('button', { name: `Reverse payment ${no}` }));
      fireEvent.change(screen.getByLabelText(/Why is it reversed/), { target: { value: why } });
      fireEvent.click(screen.getByRole('button', { name: 'Reverse this payment' }));
    };
    // The first receipt fails on the network.
    await reverseOne('RC00000001', 'Wrong buyer');
    await waitFor(() => expect(reverse).toHaveBeenCalledTimes(1));
    // The second receipt is reversed next: its key is not the first one's.
    await reverseOne('RC00000002', 'Wrong buyer');
    await waitFor(() => expect(reverse).toHaveBeenCalledTimes(2));
    // The first is tried again with the same reason: it repeats its own key.
    await reverseOne('RC00000001', 'Wrong buyer');
    await waitFor(() => expect(reverse).toHaveBeenCalledTimes(3));
    const keyOf = (call: number) => reverse.mock.calls[call]?.[3] as string;
    const idOf = (call: number) => reverse.mock.calls[call]?.[1] as string;
    expect([idOf(0), idOf(1), idOf(2)]).toEqual(['r-a', 'r-b', 'r-a']);
    expect(keyOf(1)).not.toBe(keyOf(0));
    expect(keyOf(2)).toBe(keyOf(0));
  });
});
