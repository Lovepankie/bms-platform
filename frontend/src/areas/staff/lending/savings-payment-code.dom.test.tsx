// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import type { SavingsAccount, SavingsTransaction } from '../../../api/savings';
import { StaffContext } from '../context';
import { AccountView } from './savings-account';

// The payment code on the savings account screen (issue #314) in a browser: a deposit paid by mobile money or card shows the
// provider's code the member has on their text message, as the collect screen does for a loan; a cash deposit shows none, and
// the payer's number is never on the page. The transactions are in the query cache, so nothing is fetched. All invented.

const ACCOUNT_ID = '00000000-0000-4000-8000-000000000314';

const account: SavingsAccount = {
  id: ACCOUNT_ID,
  account_no: 'SV000314',
  member_id: '00000000-0000-4000-8000-0000000003c1',
  member_no: 'M000314',
  member_name: 'Test Saver 14',
  product_code: 'TEST-SAVE',
  product_name: 'Test passbook savings',
  currency: 'UGX',
  status: 'active',
  balance_minor: 70000,
  interest_calc: 'none',
  opened_on: '2026-01-05',
};

const txns: SavingsTransaction[] = [
  {
    id: 't2', seq: 2, txn_type: 'deposit', amount_minor: 50000, credit: true, currency: 'UGX', balance_after_minor: 70000,
    value_date: '2026-10-20', receipt_no: 'RC-HQ-000002', payment_method_key: 'gateway',
    external_reference: '00000000-0000-4000-8000-0000000003a1', payment_code: 'TEST314CODE',
    // Not in the API: here only to prove the screen shows the code alone, whatever else a row might carry.
    ...({ payer_phone: '+256700000314', payer_phone_masked: '********0314' } as object),
  },
  {
    id: 't1', seq: 1, txn_type: 'deposit', amount_minor: 20000, credit: true, currency: 'UGX', balance_after_minor: 20000,
    value_date: '2026-10-01', receipt_no: 'RC-HQ-000001', payment_method_key: 'cash',
  },
];

async function shell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(['savings', 'account', ACCOUNT_ID, 'transactions'], { pages: [{ items: txns }], pageParams: [undefined] });
  const me = { ...mockMe('admin'), permissions: ['lending.savings.read'] };
  const tree = (
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: null }}>
        <AccountView account={account} />
      </StaffContext.Provider>
    </QueryClientProvider>
  );
  const router = createRouter({
    routeTree: createRootRoute({ component: () => tree }),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  await router.load();
  render(<RouterProvider router={router} />);
  return screen.findByText('Transactions');
}

const rowOf = (receipt: string) => screen.getByText(new RegExp(receipt)).closest('li') as HTMLElement;

afterEach(() => cleanup());

describe('the payment code on the savings account screen (#314)', () => {
  it('shows the code next to a deposit paid by mobile money or card', async () => {
    await shell();
    const row = rowOf('RC-HQ-000002');
    // The same markup as the loan collect screen (collect.tsx): <p className="ln-muted">Payment code <strong>code</strong></p>.
    const code = within(row).getByText('TEST314CODE');
    expect(code.tagName).toBe('STRONG');
    const line = code.parentElement as HTMLElement;
    expect(line.tagName).toBe('P');
    expect(line.className).toBe('ln-muted');
    expect(line.textContent).toBe('Payment code TEST314CODE');
  });

  it('shows no code on a cash deposit', async () => {
    await shell();
    expect(within(rowOf('RC-HQ-000001')).queryByText(/Payment code/)).toBeNull();
    expect(screen.getAllByText(/Payment code/)).toHaveLength(1);
  });

  it('never shows the payer\'s number, even when a row carries one', async () => {
    await shell();
    const text = document.body.textContent ?? '';
    expect(text).not.toContain('700000314');
    expect(text).not.toContain('*0314');
  });
});
