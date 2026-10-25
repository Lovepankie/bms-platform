// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BusinessDetail, Plan } from '../../api/operator';
import { router as appRouter } from '../../app/router';
import { TourProvider } from '../../tour/host';
import { OPERATOR } from '../../tour/tours-operator';

// The operator screens in a browser (issue #407) with the API replaced: the businesses list and its filters, the Business page's
// suspend with a reason (asked once, sent once), a payment reviewed in plain words and sent once with one Idempotency-Key, and the
// plans page's price, which says what it does before it is saved. Every name and figure is invented.

const api = {
  listBusinesses: vi.fn(),
  businessOverview: vi.fn(),
  listPricePlans: vi.fn(),
  suspendBusiness: vi.fn(),
  resumeBusiness: vi.fn(),
  recordPayment: vi.fn(),
  addPlanPrice: vi.fn(),
  savePlan: vi.fn(),
  setBusinessPilot: vi.fn(),
  resetOwnerSignIn: vi.fn(),
};

const settle = () => act(() => new Promise<void>((resolve) => setTimeout(() => setTimeout(() => setTimeout(resolve)))));

vi.mock('../../api/operator', async (original) => {
  const real = await original<typeof import('../../api/operator')>();
  const forward =
    (name: keyof typeof api) =>
    (...args: unknown[]) =>
      (api[name] as (...a: unknown[]) => unknown)(...args);
  return {
    ...real,
    listBusinesses: forward('listBusinesses'),
    businessOverview: forward('businessOverview'),
    listPricePlans: forward('listPricePlans'),
    suspendBusiness: forward('suspendBusiness'),
    resumeBusiness: forward('resumeBusiness'),
    recordPayment: forward('recordPayment'),
    addPlanPrice: forward('addPlanPrice'),
    savePlan: forward('savePlan'),
    setBusinessPilot: forward('setBusinessPilot'),
    resetOwnerSignIn: forward('resetOwnerSignIn'),
  };
});

const { Businesses } = await import('./businesses');
const { BusinessScreen } = await import('./business');
const { PlansScreen } = await import('./plans');

const PLAN: Plan = {
  code: 'test-small',
  name: 'Test Small',
  allowed_modules: ['retail'],
  max_branches: 1,
  max_staff_users: 3,
  active: true,
  sort_order: 1,
  tenant_count: 1,
  current_price: { currency: 'UGX', monthly_minor: 10000, annual_months: 11, annual_minor: 110000, effective_from: '2026-10-01' },
};

const DETAIL: BusinessDetail = {
  tenant: {
    id: 't-1',
    name: 'Test Shop One',
    slug: 'test-shop-one',
    status: 'active',
    subscription_status: 'trial',
    plan_code: 'test-small',
    plan_name: 'Test Small',
    currency: 'UGX',
    timezone: 'Africa/Kampala',
    modules: ['retail'],
    trial_ends_on: '2099-01-30',
    created_at: '2026-10-01T08:00:00Z',
    owner_email: 'owner01@example.test',
    address: 'https://test-shop-one-bms-staging.example.test',
  },
  plan_price: PLAN.current_price,
  admins: [{ id: 'u-1', full_name: 'Test Owner 01', email: 'owner01@example.test', status: 'active', mfa_enabled: true }],
  payments: [],
  messages: [{ id: 'm-1', channel: 'email', recipient: '****test', template_key: 'onboarding.activation', status: 'sent', sent_at: '2026-10-01T08:01:00Z' }],
  history: [{ action: 'platform.tenant.created', operator_name: 'Test Operator', occurred_at: '2026-10-01T08:00:00Z' }],
};

function shell(node: ReactNode) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/platform/businesses'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <TourProvider me={OPERATOR} saveProgress={false}>
          {node}
        </TourProvider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

vi.setConfig({ testTimeout: 15000 });

beforeEach(() => {
  api.listBusinesses.mockResolvedValue([DETAIL.tenant]);
  api.businessOverview.mockResolvedValue(DETAIL);
  api.listPricePlans.mockResolvedValue([PLAN, { ...PLAN, code: 'test-big', name: 'Test Big', max_branches: undefined, current_price: undefined }]);
});

afterEach(() => {
  cleanup();
  Object.values(api).forEach((m) => m.mockReset());
});

describe('Businesses', () => {
  it('lists each business with its status, plan and owner, and sends the filters', async () => {
    shell(<Businesses />);
    expect(await screen.findByText('Test Shop One')).toBeTruthy();
    expect(screen.getByText('Free month', { selector: '.badge' })).toBeTruthy();
    expect(screen.getByText('Owner: owner01@example.test')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'suspended' } });
    await waitFor(() => expect(api.listBusinesses).toHaveBeenLastCalledWith({ q: '', statuses: ['suspended'], module: '' }));
    fireEvent.change(screen.getByLabelText('Module'), { target: { value: 'lending' } });
    await waitFor(() => expect(api.listBusinesses).toHaveBeenLastCalledWith({ q: '', statuses: ['suspended'], module: 'lending' }));
  });
});

describe('the kind of business (issue #424)', () => {
  it('shows the kind the administrator chose in the list and on the Business page', async () => {
    const wholesaler = { ...DETAIL.tenant, business_type: 'wholesale_and_shops' };
    api.listBusinesses.mockResolvedValue([wholesaler]);
    api.businessOverview.mockResolvedValue({ ...DETAIL, tenant: wholesaler });
    shell(<Businesses />);
    expect(await screen.findByText('Kind of business: Wholesale and shops')).toBeTruthy();
    cleanup();
    shell(<BusinessScreen tenantId="t-1" />);
    expect((await screen.findByTestId('business-type')).textContent).toBe('Wholesale and shops');
  });

  it('shows a value it does not know as it is, never a guessed Shops only', async () => {
    api.businessOverview.mockResolvedValue({ ...DETAIL, tenant: { ...DETAIL.tenant, business_type: 'test_kind' } });
    shell(<BusinessScreen tenantId="t-1" />);
    expect((await screen.findByTestId('business-type')).textContent).toBe('test_kind');
  });

  it('says Shops only, not chosen yet, for a shop business that never chose, and nothing for a lending business', async () => {
    shell(<BusinessScreen tenantId="t-1" />);
    expect((await screen.findByTestId('business-type')).textContent).toBe('Shops only (not chosen yet)');
    cleanup();
    api.businessOverview.mockResolvedValue({ ...DETAIL, tenant: { ...DETAIL.tenant, modules: ['lending'] } });
    shell(<BusinessScreen tenantId="t-1" />);
    expect(await screen.findByText('Test Shop One', { selector: 'h1' })).toBeTruthy();
    expect(screen.queryByTestId('business-type')).toBeNull();
  });
});

describe('the Business page', () => {
  it('asks once, needs a reason, and suspends once', async () => {
    api.suspendBusiness.mockResolvedValue({ ...DETAIL.tenant, subscription_status: 'suspended', status: 'suspended' });
    shell(<BusinessScreen tenantId="t-1" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Suspend' }));
    expect(screen.getByText(/Its staff can still sign in, look and export/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, suspend it' }));
    expect(screen.getByText(/Say why in a few words/)).toBeTruthy();
    expect(api.suspendBusiness).not.toHaveBeenCalled();
    await settle();
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: unpaid after the free month' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, suspend it' }));
    fireEvent.click(screen.getByRole('button', { name: /Yes, suspend it/ }));
    await waitFor(() => expect(api.suspendBusiness).toHaveBeenCalledTimes(1));
    expect(api.suspendBusiness).toHaveBeenCalledWith('t-1', 'Test: unpaid after the free month');
    expect(await screen.findByText(/is suspended: read only, nothing deleted/)).toBeTruthy();
  });

  it('reviews a payment in plain words and records it once with one key', async () => {
    api.recordPayment.mockResolvedValue({ id: 'p-1' });
    shell(<BusinessScreen tenantId="t-1" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Record a payment' }));
    expect((screen.getByLabelText('Amount received (UGX)') as HTMLInputElement).value).toBe('10000');
    fireEvent.change(screen.getByLabelText('Months it covers'), { target: { value: '12' } });
    expect((screen.getByLabelText('Amount received (UGX)') as HTMLInputElement).value).toBe('110000');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    expect(screen.getByText(/Record UGX 110,000 by MTN Mobile Money from Test Shop One, covering 31 Jan 2099/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, record it' }));
    expect(api.recordPayment).not.toHaveBeenCalled();
    await settle();
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: seen on the statement' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, record it' }));
    fireEvent.click(screen.getByRole('button', { name: /Yes, record it/ }));
    await waitFor(() => expect(api.recordPayment).toHaveBeenCalledTimes(1));
    const [tenant, key, body] = api.recordPayment.mock.calls[0] as [string, string, Record<string, unknown>];
    expect(tenant).toBe('t-1');
    expect(key).toMatch(/^pay-/);
    expect(body).toMatchObject({ amount_minor: 110000, method: 'mtn_momo', months: 12, reason: 'Test: seen on the statement' });
  });

  it('asks to confirm an unusual amount and sends it again with the same key', async () => {
    const { OperatorError } = await import('../../api/operator');
    api.recordPayment
      .mockRejectedValueOnce(new OperatorError('The plan price is far from this amount. Check it, then confirm it.', 'payment_amount_unusual'))
      .mockResolvedValueOnce({ id: 'p-2' });
    shell(<BusinessScreen tenantId="t-1" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Record a payment' }));
    fireEvent.change(screen.getByLabelText('Amount received (UGX)'), { target: { value: '90000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: two months paid at once' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, record it' }));
    await waitFor(() => expect(api.recordPayment).toHaveBeenCalledTimes(1));
    const box = await screen.findByLabelText('I checked it: this amount is right');
    fireEvent.click(box);
    fireEvent.click(box);
    fireEvent.click(box);
    fireEvent.click(screen.getByRole('button', { name: 'Yes, record it' }));
    await waitFor(() => expect(api.recordPayment).toHaveBeenCalledTimes(2));
    const [first, second] = api.recordPayment.mock.calls as [string, string, Record<string, unknown>][];
    // The same business and amount: the box toggled keeps the key, so a resend after a lost answer is recorded once.
    expect(second?.[1]).toBe(first?.[1]);
    expect(second?.[2]).toMatchObject({ amount_minor: 90000, confirm_unusual_amount: true });
  });

  it('marks a pilot with a reason', async () => {
    api.setBusinessPilot.mockResolvedValue({ ...DETAIL.tenant, pilot: true });
    shell(<BusinessScreen tenantId="t-1" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Mark as a pilot' }));
    expect(screen.getByText(/free with no end date/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: pilot until paid' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, mark as a pilot' }));
    await waitFor(() => expect(api.setBusinessPilot).toHaveBeenCalledWith('t-1', true, 'Test: pilot until paid'));
  });

  it('asks for a reason before it makes an owner reset link', async () => {
    api.resetOwnerSignIn.mockResolvedValue({ url: 'https://test-shop-one.example.test/reset-password#token=x' });
    shell(<BusinessScreen tenantId="t-1" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Make a reset link' }));
    fireEvent.change(screen.getByLabelText('Code from your own authenticator app'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, make the link' }));
    expect(screen.getByText(/Say why in a few words/)).toBeTruthy();
    expect(api.resetOwnerSignIn).not.toHaveBeenCalled();
    await settle();
    fireEvent.change(screen.getByLabelText('Why (kept in the history)'), { target: { value: 'Test: owner called, forgot it' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, make the link' }));
    await waitFor(() => expect(api.resetOwnerSignIn).toHaveBeenCalledWith('t-1', 'u-1', '123456', 'Test: owner called, forgot it'));
  });

  it('shows the owner sign-in help, the emails sent and the history', async () => {
    shell(<BusinessScreen tenantId="t-1" />);
    expect(await screen.findByRole('button', { name: 'Make a reset link' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Reset lost two-step sign-in' })).toBeTruthy();
    expect(screen.getByText('Activation link to the owner')).toBeTruthy();
    expect(screen.getByText('Business created')).toBeTruthy();
  });
});

describe('Plans and prices', () => {
  it('lists the plans and says what a new price does before it is saved', async () => {
    api.addPlanPrice.mockResolvedValue({ plan: PLAN, prices: [] });
    shell(<PlansScreen />);
    expect(await screen.findByText('Test Small')).toBeTruthy();
    expect(screen.getByText('UGX 10,000 a month, UGX 110,000 a year (11 months charged)')).toBeTruthy();
    expect(screen.getByText('No price entered yet')).toBeTruthy();
    fireEvent.click(screen.getAllByRole('button', { name: 'Set the price' })[0] as HTMLElement);
    fireEvent.change(screen.getByLabelText('Price a month (UGX)'), { target: { value: '12000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    expect(screen.getByText(/Test Small will cost UGX 12,000 a month and UGX 132,000 a year, from today/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, save the price' }));
    expect(api.addPlanPrice).not.toHaveBeenCalled();
    await settle();
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: new price agreed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, save the price' }));
    await waitFor(() => expect(api.addPlanPrice).toHaveBeenCalledTimes(1));
    expect(api.addPlanPrice.mock.calls[0]?.[1]).toMatchObject({
      currency: 'UGX',
      monthly_minor: 12000,
      annual_months: 11,
      reason: 'Test: new price agreed',
    });
  });

  it('asks for a second look at a price far from the one in force', async () => {
    api.addPlanPrice.mockResolvedValue({ plan: PLAN, prices: [] });
    shell(<PlansScreen />);
    fireEvent.click((await screen.findAllByRole('button', { name: 'Set the price' }))[0] as HTMLElement);
    fireEvent.change(screen.getByLabelText('Price a month (UGX)'), { target: { value: '100000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    expect(screen.getByText(/The price in force is UGX 10,000 a month, and the new price is UGX 100,000/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: new price agreed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, save the price' }));
    expect(api.addPlanPrice).not.toHaveBeenCalled();
    await settle();
    fireEvent.click(screen.getByLabelText('I checked it: this is right'));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, save the price' }));
    await waitFor(() => expect(api.addPlanPrice).toHaveBeenCalledTimes(1));
    expect(api.addPlanPrice.mock.calls[0]?.[1]).toMatchObject({ monthly_minor: 100000, confirm_large_change: true });
  });

  it('warns when a lower limit leaves businesses over it, and saves only once confirmed', async () => {
    const { OperatorError } = await import('../../api/operator');
    api.savePlan
      .mockRejectedValueOnce(new OperatorError('On this plan, 1 business has more staff than the new limit allows.', 'plan_limit_below_use'))
      .mockResolvedValueOnce({ plan: PLAN, prices: [] });
    shell(<PlansScreen />);
    fireEvent.click((await screen.findAllByRole('button', { name: 'Change limits' }))[0] as HTMLElement);
    fireEvent.change(screen.getByLabelText('Staff users at most'), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: smaller plan' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, save the plan' }));
    await waitFor(() => expect(api.savePlan).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/1 business has more staff than the new limit allows/)).toBeTruthy();
    fireEvent.click(screen.getByLabelText('I checked it: this is right'));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, save the plan' }));
    await waitFor(() => expect(api.savePlan).toHaveBeenCalledTimes(2));
    expect(api.savePlan.mock.calls[0]?.[1]).not.toHaveProperty('confirm_below_use', true);
    expect(api.savePlan.mock.calls[1]?.[1]).toMatchObject({ max_staff_users: 1, confirm_below_use: true });
  });
});
