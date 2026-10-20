// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Investment } from '../../../api/investments';
import type { SavingsProduct } from '../../../api/savings';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { InvestmentHeader } from './investment';
import { SubscribeForm } from './investment-new';
import { ProductRow } from './savings-products';

// The cross links of the savings and investment screens (issue #336): a count opens the rows it counts, a member's name opens the
// member. Fabricated names and ids.

afterEach(cleanup);

// A loan officer who may read members.
const ME = { ...mockMe('admin'), modules: ['retail', 'lending'], permissions: ['lending.members.read'] };
const MEMBER = '00000000-0000-4000-8000-000000000101';
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/lending/savings'] }) });

function mount(node: ReactElement, me = ME) {
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StaffContext.Provider value={{ me, branch: null }}>{node}</StaffContext.Provider></QueryClientProvider>
    </RouterContextProvider>,
  );
}

describe('cross links on savings and investments', () => {
  it('opens the savings accounts from the number of accounts on a product', () => {
    const product: SavingsProduct = { id: 'p1', code: 'TEST-SAVE', name: 'Test passbook savings', status: 'active', currency: 'UGX', interest_calc: 'none', accounts: 7 };
    mount(<ProductRow p={product} />);
    expect(screen.getByRole('link', { name: '7 accounts' }).getAttribute('href')).toBe('/staff/lending/savings');
  });

  it('opens the member from the name on the new investment form', () => {
    mount(
      <SubscribeForm
        member={{ id: MEMBER, label: 'Test Investor 01 (M000001)' }}
        products={[{ id: 'p1', code: 'FIX12', name: 'Test fixed term', status: 'active', currency: 'UGX', return_rate_bp: 1200, allowed_terms_months: [12], min_amount_minor: 100000 }]}
      />,
    );
    expect(screen.getByRole('link', { name: 'Test Investor 01 (M000001)' }).getAttribute('href')).toBe(`/staff/lending/members/${MEMBER}`);
  });

  it('opens the member, and all their investments, from an investment', () => {
    const inv: Investment = { id: 'i1', account_no: 'IV000001', member_id: MEMBER, member_no: 'M000001', member_name: 'Test Investor 01', status: 'active', term_months: 12, return_rate_bp: 1200 };
    mount(<InvestmentHeader inv={inv} />);
    expect(screen.getByRole('link', { name: 'Test Investor 01 (M000001)' }).getAttribute('href')).toBe(`/staff/lending/members/${MEMBER}`);
    expect(screen.getByRole('link', { name: 'All their investments' }).getAttribute('href')).toBe(`/staff/lending/investments/member/${MEMBER}`);
  });

  it('shows the member name as plain text to a user who may not read members', () => {
    const inv: Investment = { id: 'i1', account_no: 'IV000001', member_id: MEMBER, member_no: 'M000001', member_name: 'Test Investor 01', status: 'active', term_months: 12, return_rate_bp: 1200 };
    mount(<InvestmentHeader inv={inv} />, { ...ME, permissions: ['lending.investments.read'] });
    expect(screen.queryByRole('link', { name: 'Test Investor 01 (M000001)' })).toBeNull();
    expect(screen.getByText(/Test Investor 01 \(M000001\)/)).toBeTruthy();
  });
});
