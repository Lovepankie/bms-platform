import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { OfflineProvider } from '../offline/offline-context';
import { testOffline } from '../offline/test-kit';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me, TenantSettings } from '../api/client';
import type { Loan } from '../api/lending';
import { mockMe } from '../api/retail-mock';
import { router as appRouter } from '../app/router';
import { StaffContext } from '../areas/staff/context';
import { StaffHome } from '../areas/staff/home';
import { CollateralForm } from '../areas/staff/lending/collateral-form';
import { CollateralList } from '../areas/staff/lending/collateral';
import { CollateralView } from '../areas/staff/lending/collateral-page';
import { MemberForm } from '../areas/staff/lending/member-form';
import { MemberView } from '../areas/staff/lending/member-page';
import { Members } from '../areas/staff/lending/members';
import { LoanView } from '../areas/staff/lending/loan';
import { Loans } from '../areas/staff/lending/loans';
import { BranchRoles } from '../areas/staff/retail/branch-roles';
import { PriceLists } from '../areas/staff/retail/price-lists';
import { AccountsPage } from '../areas/staff/retail/accounts';
import { DocumentsPage } from '../areas/staff/retail/documents';
import { QuotationsPage } from '../areas/staff/retail/quotations';
import { ReturnsPage } from '../areas/staff/retail/returns';
import { PurchaseOrdersPage } from '../areas/staff/retail/purchase-orders';
import { LandedCostsPage } from '../areas/staff/retail/landed-costs';
import { CataloguePage } from '../areas/staff/retail/catalogue';
import { ControlsPage } from '../areas/staff/retail/controls';
import { RetailHome } from '../areas/staff/retail/home';
import { canUse, showRetail } from '../areas/staff/retail/permissions';
import { RecordSale } from '../areas/staff/retail/sale';
import { InTransitPage } from '../areas/staff/retail/in-transit';
import { RetailSettings } from '../areas/staff/retail/settings';
import { RestockPage } from '../areas/staff/retail/restock';
import { RestocksPage } from '../areas/staff/retail/restocks';
import { StockPage } from '../areas/staff/retail/stock';
import { AllSales, CreditSales } from '../areas/staff/retail/sales';
import { StockByLocationPage } from '../areas/staff/retail/stock-by-location';
import { StocktakePage } from '../areas/staff/retail/stocktake';
import { StaffBar, StaffSideNav } from '../areas/staff/route';
import { MoneyPage, MorePage } from '../areas/staff/tab-pages';
import { TabBar, staffTabs } from '../areas/staff/tabs';
import { AssistantSettingsPage } from '../areas/staff/assistant/page';
import { Setup } from '../areas/staff/setup';
import { BranchesScreen } from '../areas/staff/branches/page';
import { NotificationsPage } from '../areas/staff/notifications/page';
import { FeedbackPage } from '../areas/staff/feedback/page';
import { ReferralsPage } from '../areas/staff/referrals/page';
import { BusinessNotificationsScreen } from '../areas/staff/notifications/business';
import { BillingScreen } from '../areas/staff/billing/page';
import { CloseScreen } from '../areas/staff/close/page';
import { TwoStepPolicyScreen } from '../areas/staff/two-step/page';
import { UsersScreen } from '../areas/staff/users/page';
import { FEATURES, type Tour, stepsFor } from './model';
import { CORE_TOURS, TOURS, tabNamesFor } from './tours';

// One test per tour: render the screen each step points at (under the shell's brand bar, the sidebar and the staff bar, as the staff
// layout draws them), as the user the tour is for, and prove its data-tour target is there. Renaming a screen or dropping an anchor fails here, not on
// a customer's phone. Steps behind a feature flag have no screen yet and are checked separately.

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });

const settings: TenantSettings = {
  version: 1,
  display_name: 'Test Trading Co',
  display_name_set: true,
  setup_dismissed: false,
} as TenantSettings;

// Two fabricated loans, so the loan page shows the payout of an approved loan and the payment of
// an active one.
const loan = (id: string, status: string): Loan => ({
  id,
  loan_no: `LN-BR1-00000${id.slice(-1)}`,
  member_no: 'M-000001',
  product_code: 'TEST-MONTHLY',
  status,
  currency: 'UGX',
  approved_principal_minor: 1000000,
  balances: status === 'active' ? { total_outstanding_minor: 500000, days_past_due: 0 } : undefined,
});

function screen(path: string | undefined): ReactElement {
  switch (path) {
    case undefined:
      return <></>;
    case '/staff':
      return <StaffHome />;
    case '/staff/setup':
      return <Setup />;
    case '/staff/users':
      return <UsersScreen />;
    case '/staff/setup/branches':
      return <BranchesScreen />;
    case '/staff/notifications':
      return <NotificationsPage />;
    case '/staff/feedback':
      return <FeedbackPage />;
    case '/staff/money':
      return <MoneyPage />;
    case '/staff/more':
      return <MorePage />;
    case '/staff/setup/referrals':
      return <ReferralsPage />;
    case '/staff/setup/two-step':
      return <TwoStepPolicyScreen />;
    case '/staff/setup/notifications':
      return <BusinessNotificationsScreen />;
    case '/staff/setup/billing':
      return <BillingScreen />;
    case '/staff/setup/close':
      return <CloseScreen />;
    case '/staff/retail':
      return <RetailHome />;
    case '/staff/retail/sale':
      return <RecordSale />;
    case '/staff/retail/stock':
      return <StockPage />;
    case '/staff/retail/restock':
      return <RestockPage />;
    case '/staff/retail/restocks':
      return <RestocksPage />;
    case '/staff/retail/sales':
      return <AllSales />;
    case '/staff/retail/credit-sales':
      return <CreditSales />;
    case '/staff/retail/stocktake':
      return <StocktakePage />;
    case '/staff/retail/stock-by-location':
      return <StockByLocationPage />;
    case '/staff/retail/in-transit':
      return <InTransitPage />;
    case '/staff/retail/catalogue':
      return <CataloguePage />;
    case '/staff/retail/settings':
      return <RetailSettings />;
    case '/staff/retail/branch-roles':
      return <BranchRoles />;
    case '/staff/retail/price-lists':
      return <PriceLists />;
    case '/staff/retail/accounts':
      return <AccountsPage />;
    case '/staff/retail/documents':
      return <DocumentsPage />;
    case '/staff/retail/quotations':
      return <QuotationsPage />;
    case '/staff/retail/returns':
      return <ReturnsPage />;
    case '/staff/retail/purchase-orders':
      return <PurchaseOrdersPage />;
    case '/staff/retail/landed-costs':
      return <LandedCostsPage />;
    case '/staff/retail/controls':
      return <ControlsPage />;
    case '/staff/assistant':
      return <AssistantSettingsPage />;
    case '/staff/lending':
      return <Loans />;
    case '/staff/lending/members':
      return <Members />;
    case '/staff/lending/members/new':
      return <MemberForm />;
    case '/staff/lending/members/$memberId':
      return <MemberView member={{ id: 'm1', branch_id: branches?.[0]?.id, member_no: 'M000001', full_name: 'Test Member 01', kyc_status: 'incomplete', status: 'active', id_type: 'nin', version: 1 }} />;
    case '/staff/lending/collateral':
      return <CollateralList />;
    case '/staff/lending/collateral/new':
      return <CollateralForm />;
    case '/staff/lending/collateral/$collateralId':
      return <CollateralView collateralId="c1" detail={{ item: { id: 'c1', branch_id: branches?.[0]?.id, member_id: 'm1', collateral_type: 'land_title', description: 'Test plot', custody_status: 'pledged', currency: 'UGX', version: 1 }, valuations: [], events: [] }} />;
    case '/staff/lending/loans/$loanId':
      return (
        <>
          <LoanView loan={loan('00000000-0000-4000-8000-0000000000a1', 'approved')} />
          <LoanView loan={loan('00000000-0000-4000-8000-0000000000a2', 'active')} />
        </>
      );
    default:
      throw new Error(`No screen is mapped for ${path}: add it to tours.test.tsx`);
  }
}

let current: Me;
function StaffBarForTest() {
  return <StaffBar me={current} branch={current.default_branch_id ?? null} onBranch={() => undefined} onSignOut={() => undefined} />;
}
function StaffSideNavForTest() {
  return <StaffSideNav me={current} />;
}

function render(me: Me, path: string | undefined): string {
  current = me;
  const client = new QueryClient();
  client.setQueryData(['settings'], settings);
  // What the Users page reads, so its list and search are in the static markup.
  client.setQueryData(['staff-users'], [{ id: 'u1', full_name: 'Test Seller 02', status: 'invited', roles: [{ role_key: 'retail_sales' }] }]);
  client.setQueryData(['staff-roles'], []);
  // What the business notifications screen reads (issue #412).
  client.setQueryData(['notification-settings'], {
    new_orders: true, order_updates: true, low_stock: true, credit_due: true, daily_summary: true,
    daily_summary_time: '20:00', timezone: 'Africa/Kampala', push_available: true, version: 1,
  });
  client.setQueryData(['staff-branches'], []);
  // What Plan and billing reads (issue #480): a paid business with one payment, fabricated.
  client.setQueryData(['billing'], {
    plan_code: 'test', plan_name: 'Test Plan', currency: 'UGX', monthly_minor: 10000, annual_months: 11, annual_minor: 110000,
    state: 'active', paid_until: '2026-11-30', grace_days: 7, how_to_pay: 'Test: pay to the test account',
    payments: [{ id: 'p1', amount_minor: 10000, currency: 'UGX', method: 'mtn_momo', paid_on: '2026-11-01', months: 1, period_start: '2026-11-01', period_end: '2026-11-30', receipt_no: 'RT-2026-000001' }],
  });
  // What Close the business reads (issue #571): no request yet, seen by the owner.
  client.setQueryData(['closure'], { status: 'none', cooling_off_days: 30, can_change: true });
  // What the retail settings screens read, so their targets are in the static markup.
  client.setQueryData(['retail', 'capabilities'], { capabilities: { profile: 'shop' }, version: 1 });
  client.setQueryData(['retail', 'branch-roles'], (me.branches ?? []).map((b) => ({ branch_id: b.id, code: b.code, name: b.name, role: 'both', can_sell: true })));
  const branch = path === undefined ? (me.default_branch_id ?? null) : (me.branches?.[0]?.id ?? null);
  return renderToString(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch, signOut: () => undefined }}>
          {/* Offline, so the Online dot (drawn only when it has news, issue #526) is on the page for its step. */}
          <OfflineProvider value={testOffline(me, { online: false })}>
            <StaffSideNavForTest />
            <StaffBarForTest />
            <TabBar me={me} />
            {screen(path)}
          </OfflineProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

// The users each tour is written for, all fabricated.
const branches = mockMe('sales').branches;
const ADMIN: Me = {
  ...mockMe('admin'),
  modules: ['retail', 'lending'],
  // Branch roles on, so the tile and the screen of that capability are on the pages its tour points at.
  module_capabilities: { retail: ['branch_roles'] },
  full_name: 'Test Owner 01',
  // The Owner of the business (ADR-062), so the Users page shows the owner card its tour points at.
  is_owner: true,
  all_branches: true,
  permissions: [
    ...(mockMe('admin').permissions ?? []),
    'core.settings.manage', 'core.settings.read', 'core.users.read', 'core.users.manage', 'core.branches.read',
    'core.approvals.read', 'lending.members.read', 'lending.members.create', 'lending.loans.read', 'lending.insights.read',
    'retail.customer.manage',
  ],
} as Me;
const SELLER: Me = mockMe('sales');
const CASHIER: Me = { ...mockMe('sales'), permissions: ['lending.members.read', 'core.approvals.read'], branches };
// A shop manager: restocks, counts and keeps the catalogue, but is not the tenant admin.
const MANAGER: Me = {
  ...mockMe('admin'),
  full_name: 'Test Manager 01',
  permissions: (mockMe('admin').permissions ?? []).filter((p) => p !== 'core.settings.manage'),
};
const OFFICER: Me = {
  ...mockMe('sales'),
  modules: ['lending'],
  full_name: 'Test Officer 01',
  permissions: [
    'lending.members.read', 'lending.loans.read', 'lending.disbursements.request', 'lending.repayments.create',
    'core.approvals.read',
  ],
  branches,
} as Me;

// A loan officer who adds members and collateral, and a branch manager who also checks the details and asks for a release.
const CLERK: Me = {
  ...OFFICER,
  full_name: 'Test Clerk 01',
  // A payload without scopes counts as every branch.
  permission_scopes: undefined,
  permissions: [
    ...(OFFICER.permissions ?? []), 'lending.members.create', 'lending.members.update', 'lending.collateral.read', 'lending.collateral.manage', 'lending.collateral.release_request',
  ],
};
const CHECKER: Me = { ...CLERK, full_name: 'Test Checker 01', permissions: [...(CLERK.permissions ?? []), 'lending.members.verify_kyc'] };

// A wholesaler's manager: the trade capabilities are on (ADR-033).
const TRADER: Me = { ...MANAGER, full_name: 'Test Trader 01', module_capabilities: { retail: ['branch_roles', 'transfer_receipt'] } };

// An administrator of a business that switched price lists on (ADR-033, issue #195).
const PRICER: Me = { ...ADMIN, full_name: 'Test Pricer 01', module_capabilities: { retail: ['branch_roles', 'price_lists'] } };

// An administrator of a business that switched customer accounts on (ADR-033, issue #196).
const LENDER: Me = { ...ADMIN, full_name: 'Test Lender 01', module_capabilities: { retail: ['branch_roles', 'customer_accounts'] } };

// An administrator of a business that switched documents on (ADR-033, issue #197).
const DOCUMENTER: Me = { ...ADMIN, full_name: 'Test Documenter 01', module_capabilities: { retail: ['branch_roles', 'documents'] } };
// An administrator of a business that switched customer returns on (ADR-033, issue #201).
const RETURNER: Me = { ...ADMIN, full_name: 'Test Returner 01', module_capabilities: { retail: ['branch_roles', 'returns'] } };

// An administrator of a business that switched optional purchasing on (ADR-033, issue #198).
const BUYER: Me = { ...ADMIN, full_name: 'Test Buyer 01', module_capabilities: { retail: ['branch_roles', 'purchasing'] } };

// An administrator of a business with lending, who may configure the AI assistant (extras slice C3, issue #226).
const ASSISTANT: Me = { ...ADMIN, full_name: 'Test Assistant Admin 01', permissions: [...(ADMIN.permissions ?? []), 'lending.advisor.configure'], modules: ['retail', 'lending'] } as Me;

// The owner or an administrator, who reads the business's referrals (issue #464, ADR-069).
const REFERRER: Me = { ...ADMIN, full_name: 'Test Referrer 01', permissions: [...(ADMIN.permissions ?? []), 'core.referrals.read'] } as Me;

const AUDIENCE: Record<string, Me[]> = {
  'page-money': [ADMIN, SELLER],
  'page-more': [ADMIN, SELLER, OFFICER],
  'page-assistant': [ASSISTANT],
  'page-users': [ADMIN],
  'page-branches': [ADMIN],
  'page-notifications': [ADMIN, CASHIER],
  'page-feedback': [ADMIN, CASHIER],
  'page-referrals': [REFERRER],
  'page-two-step': [ADMIN],
  'page-business-notifications': [ADMIN],
  'page-billing': [ADMIN],
  'page-close': [ADMIN],
  'page-members': [CLERK, OFFICER],
  'page-member-new': [CLERK],
  'page-member': [CLERK, CHECKER],
  'page-collateral': [CLERK],
  'page-collateral-new': [CLERK],
  'page-collateral-item': [CLERK],
  'admin-welcome': [ADMIN],
  'retail-sales': [SELLER, MANAGER],
  'staff-welcome': [CASHIER, OFFICER],
  'page-sale': [SELLER],
  'page-stock': [SELLER],
  'page-restocks': [ADMIN, MANAGER],
  'page-sales': [SELLER],
  'page-credit-sales': [SELLER],
  'page-setup': [ADMIN],
  'page-loans': [OFFICER, ADMIN],
  'page-loan': [OFFICER],
  'page-catalogue': [ADMIN, MANAGER],
  'page-stocktake': [ADMIN, MANAGER],
  'page-retail-settings': [ADMIN],
  'capability-branch-roles': [ADMIN],
  'page-stock-by-location': [TRADER],
  'page-in-transit': [TRADER],
  'capability-transfer-receipt': [TRADER],
  'capability-units': [ADMIN],
  'capability-price-lists': [PRICER],
  'page-price-lists': [PRICER],
  'capability-customer-accounts': [LENDER],
  'page-accounts': [LENDER],
  'capability-documents': [DOCUMENTER],
  'page-documents': [DOCUMENTER],
  'page-quotations': [DOCUMENTER],
  'capability-returns': [RETURNER],
  'page-returns': [RETURNER],
  'capability-purchasing': [BUYER],
  'page-purchase-orders': [BUYER],
  'page-landed-costs': [BUYER],
  'page-controls': [ADMIN],
};

describe('every tour target exists on its screen', () => {
  it('has an audience for every registered tour', () => {
    expect(Object.keys(AUDIENCE).sort()).toEqual(CORE_TOURS.map((t) => t.id).sort());
  });

  for (const tour of CORE_TOURS as Tour[]) {
    it(`${tour.id}`, () => {
      for (const user of AUDIENCE[tour.id] ?? []) {
        expect(tour.audience(user), `${tour.id} is for ${user.full_name}`).toBe(true);
        const steps = stepsFor(tour, user);
        expect(steps.length).toBeGreaterThan(1);
        for (const step of steps) {
          if (!step.target) continue;
          const html = render(user, step.route ?? tour.page);
          expect(html, `${tour.id}/${step.id}: data-tour="${step.target}" on ${step.route ?? tour.page ?? 'the staff bar'}`).toContain(
            `data-tour="${step.target}"`,
          );
        }
      }
    });
  }
});

describe('steps waiting for a feature', () => {
  it('point at a page that does not exist yet, so switching the flag on needs the screen first', () => {
    const waiting = TOURS.flatMap((t) => t.steps).filter((s) => s.feature && !FEATURES[s.feature]);
    expect(waiting.length).toBeGreaterThan(0);
    for (const step of waiting) {
      expect(step.target).toBeTruthy();
      expect(appRouter.routesByPath[step.route as keyof typeof appRouter.routesByPath], step.id).toBeUndefined();
    }
  });

  it('are only switched on once their page is routed (staff users, issue #319)', () => {
    expect(FEATURES.staffAdmin).toBe(true);
    for (const step of TOURS.flatMap((t) => t.steps).filter((s) => s.feature && FEATURES[s.feature])) {
      expect(appRouter.routesByPath[step.route as keyof typeof appRouter.routesByPath], step.id).toBeDefined();
    }
  });
});

// Issue #544: the bottom tabs step names only the tabs the person has, so a lending-only session is not told of Sell or Buy.
describe('the bottom tabs step', () => {
  const lending = (permissions: string[]) => ({ user_id: 'u-1', full_name: 'Test Officer 02', kind: 'staff', permissions, modules: ['lending'], branches: [] }) as unknown as Me;
  const people: [string, Me][] = [
    ['an owner', mockMe('admin')],
    ['a seller', mockMe('sales')],
    ['a loan officer', lending(['lending.loans.read', 'lending.members.read'])],
    ['a savings officer', lending(['lending.loans.read', 'lending.savings.read'])],
    ['a storekeeper', { ...mockMe('admin'), permissions: ['retail.stock.read', 'retail.purchase.create'] } as Me],
  ];

  it.each(people)('names the same tabs the bar draws for %s', (_who, me) => {
    const drawn = staffTabs(me, { sells: canSell(me) }).map((t) => t.label).filter((l) => l !== 'More');
    expect(tabNamesFor(me)).toEqual(drawn);
  });

  const wordsOf = (tourId: string, me: Me) => stepsFor(TOURS.find((t) => t.id === tourId) as Tour, me).find((s) => s.id === 'tabs')?.body;

  it('words the step for the tabs the person has', () => {
    expect(wordsOf('admin-welcome', mockMe('admin'))).toBe('On a phone, the bar at the bottom holds Sell, My stock, Buy and Money. More has everything else, and Sign out.');
    expect(wordsOf('retail-sales', mockMe('sales'))).toBe('On a phone, the bar at the bottom holds Sell, My stock and Money. More has everything else, and Sign out.');
    expect(wordsOf('staff-welcome', lending(['lending.loans.read', 'lending.savings.read']))).toBe('On a phone, the bar at the bottom holds Money. More has everything else, and Sign out.');
    expect(wordsOf('staff-welcome', lending(['lending.loans.read']))).toBe('On a phone, the bar at the bottom holds More: every page your role allows, and Sign out.');
  });

  it('on the Money page names the other tabs to go back to, and none a lending-only session lacks', () => {
    expect(wordsOf('page-money', mockMe('admin'))).toBe('Money is one of the tabs at the bottom. Tap Sell, My stock or Buy to get back to the daily jobs.');
    expect(wordsOf('page-money', lending(['lending.savings.read']))).toBe('Money is one of the tabs at the bottom. More has everything else.');
  });
});

// useSells without branch roles: may the session sell at all.
function canSell(me: Me) {
  return showRetail(me) && canUse(me, 'sale');
}
