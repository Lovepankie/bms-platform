// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AdvisorSettings } from '../../api/advisor';
import type { Me } from '../../api/client';
import type { InsightsPortfolio, InsightsRevenue } from '../../api/insights';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { StaffContext } from './context';

// Rule 3 of #336 on the screens issue #798 names: one brand button per state, and a help tip on each word a shop owner or a
// lender would not use (KYC, Tokens a day, Portfolio at risk, From posted journal lines only). Every name and figure is made up.

const { never } = vi.hoisted(() => ({ never: () => new Promise<never>(() => undefined) }));
vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()),
  listPendingApprovals: never,
  listMembers: () => Promise.resolve({ items: [{ id: 'm-1', member_no: 'M-000001', full_name: 'Test Member 01', phone_e164_masked: '+256 7** *** 001', kyc_status: 'verified' }] }),
}));
vi.mock('../../api/lending', async (original) => {
  const real = await original<typeof import('../../api/lending')>();
  return { ...real, lending: { ...real.lending, homeSummary: never } };
});

const { StaffHome } = await import('./home');
const { AssistantCaps } = await import('./assistant/page');
const { PortfolioView, RevenueView } = await import('./insights/page');

afterEach(cleanup);

const LENDING = ['lending.loans.read', 'lending.members.read'];
const lender = { ...mockMe('admin'), permissions: LENDING, modules: ['lending'] } as unknown as Me;

function show(ui: ReactElement, me: Me = lender) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>{ui}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

// The full button name, as the help tips guard looks for it in this file.
const tip = (name: string) => screen.queryAllByRole('button', { name });
const brand = () => document.querySelectorAll('.rt-primary, .btn-primary');

const settings = (enabled: boolean) => ({ enabled, allowed: true, provider_configured: true, daily_token_cap: 20000, tokens_used_today: 0, version: 1, acknowledged_at: null, acknowledgement: { text: 'Test notice', text_version: 'v1' } }) as unknown as AdvisorSettings;

describe('rule 3 on the screens of #798', () => {
  it('puts a tip on the KYC column of the member list', async () => {
    show(<StaffHome />);
    await screen.findByText('Test Member 01');
    expect(tip('More about KYC')).toHaveLength(1);
    expect(screen.getByRole('columnheader', { name: 'KYC' })).toBeTruthy();
  });

  it('keeps Save the limit plain while the assistant is off, and puts a tip on Tokens a day', () => {
    show(<AssistantCaps saved={settings(false)} busy={false} error={null} onSave={() => undefined} />);
    expect(tip('More about Tokens a day')).toHaveLength(1);
    expect(brand()).toHaveLength(0);
    cleanup();
    show(<AssistantCaps saved={settings(true)} busy={false} error={null} onSave={() => undefined} />);
    expect(brand()).toHaveLength(1);
  });

  it('puts a tip on Portfolio at risk and on From posted journal lines only', () => {
    const portfolio = { currency: 'UGX', grain: 'month', stock_source: 'live', series: [], metrics: [], ageing: [], breakdowns: [] } as unknown as InsightsPortfolio;
    show(<PortfolioView query={{ data: portfolio, error: null, isPending: false }} onDrill={() => undefined} />);
    expect(tip('More about Portfolio at risk')).toHaveLength(1);
    cleanup();
    const revenue = { currency: 'UGX', metrics: [], series: [], by_product: [], by_branch: [] } as unknown as InsightsRevenue;
    show(<RevenueView query={{ data: revenue, error: null, isPending: false }} onDrill={() => undefined} />);
    expect(tip('More about From posted journal lines only')).toHaveLength(1);
  });
});
