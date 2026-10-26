// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { LendingApiContext } from '../../api/lending-context';
import { createCollateralMock, createMembersMock } from '../../api/members-mock';
import { What } from './approvals';

// A checker must see what a collateral release is about before approving it (issue #280): the item, what it is worth and where it is
// now, not the identifier of the action. Other types keep their identifier. Fabricated item.

afterEach(cleanup);

const items = () => createCollateralMock([{ id: 'c1', collateral_type: 'land_title', description: 'Test plot at Test Village', custody_status: 'in_custody', currency: 'UGX', estimated_value_minor: 25_000_000, version: 1 }]);

const show = (type: string, subject?: string) =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <LendingApiContext.Provider value={{ members: createMembersMock().api, collateral: items().api }}>
        <What item={{ action_type: type, subject_id: subject }} />
      </LendingApiContext.Provider>
    </QueryClientProvider>,
  );

describe('the approvals inbox action column', () => {
  it('describes a collateral release by its item', async () => {
    show('collateral_release', 'c1');
    expect(await screen.findByText(/Test plot at Test Village/)).toBeTruthy();
    expect(document.body.textContent).toContain('Give collateral back');
    expect(document.body.textContent).toContain('UGX 25,000,000');
    expect(document.body.textContent).toContain('We hold it');
    expect(document.body.textContent).not.toContain('collateral_release');
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
