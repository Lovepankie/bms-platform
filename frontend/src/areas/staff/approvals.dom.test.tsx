// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Approval, Me } from '../../api/client';
import { LendingApiContext } from '../../api/lending-context';
import { createCollateralMock, createMembersMock } from '../../api/members-mock';
import { StaffContext } from './context';

// A checker must see what a collateral release is about before approving it (issues #280 and #289): the item, what it is worth and
// where it is now, the member and who collects it, not the identifier of the action. Other types keep their identifier. Fabricated
// item, member and collector.

const requests: Record<string, Approval> = {
  r1: { id: 'r1', action_type: 'collateral_release', subject_id: 'c1', payload: { collected_by: 'Test Collector 01', note: 'Loan paid in full' } as unknown as Approval['payload'] },
};

vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()),
  getApproval: async (id: string) => {
    const r = requests[id];
    if (!r) throw new Error('Not found.');
    return r;
  },
}));

const { What } = await import('./approvals');

afterEach(cleanup);

const items = () =>
  createCollateralMock([
    { id: 'c1', member_id: 'm1', collateral_type: 'land_title', description: 'Test plot at Test Village', custody_status: 'in_custody', currency: 'UGX', estimated_value_minor: 25_000_000, version: 1 },
    { id: 'c2', collateral_type: 'vehicle', description: 'Test car', custody_status: 'in_custody', estimated_value_minor: 1_500_000, version: 1 },
  ]);

const show = (type: string, subject?: string, id?: string, me?: Partial<Me>) => {
  const node = (
    <LendingApiContext.Provider value={{ members: createMembersMock([{ id: 'm1', full_name: 'Test Borrower 01' }]).api, collateral: items().api }}>
      <What item={{ id, action_type: type, subject_id: subject }} />
    </LendingApiContext.Provider>
  );
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      {me ? <StaffContext.Provider value={{ me: me as Me, branch: null }}>{node}</StaffContext.Provider> : node}
    </QueryClientProvider>,
  );
};

describe('the approvals inbox action column', () => {
  it('describes a collateral release by its item', async () => {
    show('collateral_release', 'c1');
    expect(await screen.findByText(/Test plot at Test Village/)).toBeTruthy();
    expect(document.body.textContent).toContain('Give collateral back');
    expect(document.body.textContent).toContain('UGX 25,000,000');
    expect(document.body.textContent).toContain('We hold it');
    expect(document.body.textContent).not.toContain('collateral_release');
  });
  it('names the member, who collects the item and the note (issue #289)', async () => {
    show('collateral_release', 'c1', 'r1');
    expect(await screen.findByText(/Member: Test Borrower 01\./)).toBeTruthy();
    expect(await screen.findByText(/Collected by Test Collector 01\./)).toBeTruthy();
    expect(document.body.textContent).toContain('Note: Loan paid in full');
  });
  it('says who collects it could not be loaded when the request cannot be read', async () => {
    show('collateral_release', 'c1', 'gone');
    expect(await screen.findByText(/Member: Test Borrower 01\./)).toBeTruthy();
    expect(await screen.findByText(/Who collects it could not be loaded\./)).toBeTruthy();
    expect(document.body.textContent).not.toContain('Collected by');
  });
  it('shows an item with no currency in the business currency (issue #289)', async () => {
    show('collateral_release', 'c2', undefined, { tenant_currency: 'KES' });
    expect(await screen.findByText(/Test car/)).toBeTruthy();
    expect(document.body.textContent).toContain('KES 15,000.00');
    expect(document.body.textContent).not.toContain('UGX');
  });
  it('says so, and where to look, when the item cannot be loaded', async () => {
    show('collateral_release', 'missing');
    expect(await screen.findByText(/could not be loaded here/)).toBeTruthy();
  });
  it('names the other action types in words (issue #343)', () => {
    show('loan_disbursement');
    expect(document.body.textContent).toBe('Pay out a loan');
  });
  it('names the new retail approvals in words', () => {
    show('retail_sale_void');
    expect(document.body.textContent).toBe('Void a sale');
  });
  it('keeps the type of an action it does not know', () => {
    show('test_action');
    expect(document.body.textContent).toBe('test_action');
  });
});
