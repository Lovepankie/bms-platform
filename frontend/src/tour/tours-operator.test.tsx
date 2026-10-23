// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { BusinessDetail, Plan } from '../api/operator';
import { mockMe } from '../api/retail-mock';
import { router as appRouter } from '../app/router';
import { TourProvider } from './host';
import { autoTourFor, pageTourFor, stepsFor } from './model';
import { OPERATOR, OPERATOR_TOURS } from './tours-operator';
import { TOURS } from './tours';

// The page tours of the operator portal (issue #407): each is in the registry, is found only for the operator on its own page, never
// lets a staff tour start by itself in the portal, and every step's data-tour anchor is on its page. Invented data only.

const PLAN: Plan = {
  code: 'test-small',
  name: 'Test Small',
  allowed_modules: ['retail'],
  max_branches: 1,
  active: true,
  tenant_count: 0,
  current_price: { currency: 'UGX', monthly_minor: 10000, annual_months: 11, annual_minor: 110000, effective_from: '2026-10-01' },
};

const DETAIL: BusinessDetail = {
  tenant: { id: 't-1', name: 'Test Shop One', slug: 'test-shop-one', subscription_status: 'active', currency: 'UGX', modules: ['retail'] },
  admins: [{ id: 'u-1', full_name: 'Test Owner 01', email: 'owner01@example.test', status: 'active', mfa_enabled: true }],
  payments: [],
  messages: [],
  history: [],
};

vi.mock('../api/operator', async (original) => {
  const real = await original<typeof import('../api/operator')>();
  return {
    ...real,
    listBusinesses: () => Promise.resolve([DETAIL.tenant]),
    businessOverview: () => Promise.resolve(DETAIL),
    listPricePlans: () => Promise.resolve([PLAN]),
  };
});

const { Businesses } = await import('../areas/platform/businesses');
const { BusinessScreen } = await import('../areas/platform/business');
const { PlansScreen } = await import('../areas/platform/plans');
const { FeedbackInbox } = await import('../areas/platform/feedback');

vi.mock('../api/billing', async (original) => {
  const real = await original<typeof import('../api/billing')>();
  return {
    ...real,
    dueForSuspension: () =>
      Promise.resolve({
        grace_days: 7,
        items: [
          {
            tenant_id: 't-2',
            name: 'Test Shop Two',
            slug: 'test-shop-two',
            plan_name: 'Test Small',
            due_on: '2026-11-01',
            overdue_since: '2026-11-02',
            grace_ended_on: '2026-11-08',
            days_overdue: 19,
            amount_due_minor: 10000,
            currency: 'UGX',
          },
        ],
      }),
    billingSettings: () => Promise.resolve({ grace_days: 7, how_to_pay: 'Test: pay to the test account' }),
  };
});

const { DueForSuspensionPage } = await import('../areas/platform/billing');

const FEEDBACK = {
  id: 'f-1',
  tenant_id: 't-1',
  tenant_slug: 'test-shop-one',
  tenant_name: 'Test Shop One',
  sender_name: 'Test Staff 01',
  kind: 'problem',
  message: 'Test message: the till froze.',
  page_path: '/staff/retail/sale',
  app_version: '0.1.0 (abc123)',
  user_agent: 'TestBrowser/1.0',
  has_screenshot: true,
  status: 'new',
  created_at: '2026-10-22T08:00:00Z',
};

function shell(node: ReactNode) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/platform'] }) });
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

afterEach(cleanup);

describe('operator page tours', () => {
  it('are in the registry, start only from the page, and point at portal routes', () => {
    for (const tour of OPERATOR_TOURS) {
      expect(TOURS).toContain(tour);
      expect(tour.autoStart).toBe(false);
      expect(Object.keys(appRouter.routesByPath)).toContain(tour.page);
    }
  });

  it('are found for the operator on their pages and never for staff', () => {
    expect(pageTourFor(TOURS, OPERATOR, '/platform/businesses')?.id).toBe('page-operator-businesses');
    expect(pageTourFor(TOURS, OPERATOR, '/platform/businesses/t-1')?.id).toBe('page-operator-business');
    expect(pageTourFor(TOURS, OPERATOR, '/platform/plans')?.id).toBe('page-operator-plans');
    expect(pageTourFor(TOURS, OPERATOR, '/platform/feedback')?.id).toBe('page-operator-feedback');
    expect(pageTourFor(TOURS, OPERATOR, '/platform/billing')?.id).toBe('page-operator-billing');
    expect(pageTourFor(TOURS, mockMe('admin'), '/platform/plans')).toBeUndefined();
  });

  it('let no tour start by itself in the portal', () => {
    expect(autoTourFor(TOURS, OPERATOR, OPERATOR.tours as never)).toBeUndefined();
  });

  const pages: [string, () => ReactNode, string][] = [
    ['page-operator-businesses', () => <Businesses />, 'Test Shop One'],
    ['page-operator-business', () => <BusinessScreen tenantId="t-1" />, 'Make a reset link'],
    ['page-operator-plans', () => <PlansScreen />, 'Test Small'],
    ['page-operator-billing', () => <DueForSuspensionPage />, 'Test Shop Two'],
    [
      'page-operator-feedback',
      () => <FeedbackInbox load={() => Promise.resolve([FEEDBACK])} save={() => Promise.resolve(FEEDBACK)} loadScreenshot={() => Promise.resolve(new Blob())} />,
      'Test message: the till froze.',
    ],
  ];

  it.each(pages)('%s has every anchor on its page', async (id, page, loaded) => {
    shell(page());
    await screen.findAllByText(loaded);
    const tour = OPERATOR_TOURS.find((t) => t.id === id);
    expect(tour).toBeDefined();
    const steps = stepsFor(tour as (typeof OPERATOR_TOURS)[number], OPERATOR);
    expect(steps.length).toBeGreaterThan(2);
    for (const step of steps) {
      expect(document.querySelector(`[data-tour="${step.target}"]`), `${id}/${step.id}`).not.toBeNull();
    }
  });
});
