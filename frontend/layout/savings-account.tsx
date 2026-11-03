import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { mockMe } from '../src/api/retail-mock';
import type { SavingsAccount, SavingsTransaction } from '../src/api/savings';
import { StaffContext } from '../src/areas/staff/context';
import { SavingsTransactions } from '../src/areas/staff/lending/savings-account';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page savings-account.layout.test.ts lays out in a real browser (issue #314): the transactions of a savings account, with a
// deposit paid by mobile money or card showing its payment code, a long code with no spaces, beside a cash deposit. ?font= scales
// the root font size as a phone's large text setting does. Every figure and code is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const me = { ...mockMe('admin'), permissions: ['lending.savings.read'], tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const account: SavingsAccount = { id: '00000000-0000-4000-8000-000000000314', account_no: 'SV000314', currency: 'UGX', status: 'active', balance_minor: 123_470_000 };
const txns: SavingsTransaction[] = [
  {
    id: 't2', seq: 2, txn_type: 'deposit', amount_minor: 123_450_000, credit: true, currency: 'UGX', balance_after_minor: 123_470_000,
    value_date: '2026-10-20', receipt_no: 'RC-HQ-000002', payment_method_key: 'gateway',
    external_reference: '00000000-0000-4000-8000-0000000003a1', payment_code: 'TESTCODE314ABCDEFGHIJKLMNOPQRSTUV',
  },
  {
    id: 't1', seq: 1, txn_type: 'deposit', amount_minor: 20_000, credit: true, currency: 'UGX', balance_after_minor: 20_000,
    value_date: '2026-10-01', receipt_no: 'RC-HQ-000001', payment_method_key: 'cash',
  },
];
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter(`/staff/lending/savings/accounts/${account.id}`)}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: null }}>
        <div className="app-shell"><div className="app-content"><main className="rt ln">
          <SavingsTransactions account={account} items={txns} />
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
