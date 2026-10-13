import { Outlet, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { useShellBrand } from './branding';
import { Landing } from './landing';
import { Shell } from './shell';

// Route-level split (ADR-009): /staff and /member are separate lazily loaded route trees, so a
// member never downloads staff code; sign-in and invitation acceptance are small chunks of their
// own. The platform host (BMS_PLATFORM_HOST) serves the public sign-up page and applicant page and
// the operator portal under /platform (ADR-024), each lazily loaded as well.

// The root layout is the shared shell (brand bar, theme colour, Powered-by footer): every route
// below it inherits it, so no area draws its own.
function RootLayout() {
  return (
    <Shell brand={useShellBrand()}>
      <Outlet />
    </Shell>
  );
}

const rootRoute = createRootRoute({ component: RootLayout });

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: Landing });

const signInRoute = createRoute({ getParentRoute: () => rootRoute, path: '/sign-in' }).lazy(() =>
  import('../areas/auth/sign-in').then((m) => m.Route),
);

const acceptInvitationRoute = createRoute({ getParentRoute: () => rootRoute, path: '/accept-invitation' }).lazy(() =>
  import('../areas/auth/accept-invitation').then((m) => m.Route),
);

const staffRoute = createRoute({ getParentRoute: () => rootRoute, path: '/staff', staticData: { crumb: 'Home' } }).lazy(() =>
  import('../areas/staff/route').then((m) => m.Route),
);

const staffHomeRoute = createRoute({ getParentRoute: () => staffRoute, path: '/' }).lazy(() =>
  import('../areas/staff/home').then((m) => m.Route),
);

const staffApprovalsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/approvals', staticData: { crumb: 'Approvals' } }).lazy(() =>
  import('../areas/staff/approvals').then((m) => m.Route),
);

const staffSetupRoute = createRoute({ getParentRoute: () => staffRoute, path: '/setup', staticData: { crumb: 'Business set-up' } }).lazy(() =>
  import('../areas/staff/setup').then((m) => m.Route),
);

const staffRetailRoute = createRoute({ getParentRoute: () => staffRoute, path: '/retail', component: Outlet, staticData: { crumb: 'Retail' } });

const retailScreen = <P extends string>(path: P, crumb?: string) => createRoute({ getParentRoute: () => staffRetailRoute, path, staticData: { crumb } });
const retailHomeRoute = retailScreen('/').lazy(() => import('../areas/staff/retail/home').then((m) => m.Route));
const retailSaleRoute = retailScreen('/sale', 'Record a sale').lazy(() => import('../areas/staff/retail/sale').then((m) => m.Route));
const retailCreditSalesRoute = retailScreen('/credit-sales', 'Credit sales').lazy(() => import('../areas/staff/retail/sales').then((m) => m.CreditRoute));
const retailSalesRoute = retailScreen('/sales', 'All sales').lazy(() => import('../areas/staff/retail/sales').then((m) => m.Route));
const retailRestockRoute = retailScreen('/restock', 'Restock').lazy(() => import('../areas/staff/retail/restock').then((m) => m.Route));
const retailUsageRoute = retailScreen('/usage', 'Usage and damage').lazy(() => import('../areas/staff/retail/usage').then((m) => m.Route));
const retailStockRoute = retailScreen('/stock', 'Stock').lazy(() => import('../areas/staff/retail/stock').then((m) => m.Route));
const retailStocktakeRoute = retailScreen('/stocktake', 'Stock-take').lazy(() => import('../areas/staff/retail/stocktake').then((m) => m.Route));
const retailTransferRoute = retailScreen('/transfer', 'Move stock').lazy(() => import('../areas/staff/retail/transfer').then((m) => m.Route));
const retailTransfersRoute = retailScreen('/transfers', 'Stock moves').lazy(() => import('../areas/staff/retail/transfers').then((m) => m.Route));
const retailStockByLocationRoute = retailScreen('/stock-by-location', 'Stores and shops').lazy(() => import('../areas/staff/retail/stock-by-location').then((m) => m.Route));
const retailInTransitRoute = retailScreen('/in-transit', 'On the way').lazy(() => import('../areas/staff/retail/in-transit').then((m) => m.Route));
const retailValuationRoute = retailScreen('/valuation', 'Stock value').lazy(() => import('../areas/staff/retail/profit').then((m) => m.ValuationRoute));
const retailProfitRoute = retailScreen('/profit', 'Daily profit').lazy(() => import('../areas/staff/retail/profit').then((m) => m.Route));
const retailSalesAnalysisRoute = retailScreen('/sales-analysis', 'Sales analysis').lazy(() => import('../areas/staff/retail/sales-analysis').then((m) => m.Route));
const retailMarginsRoute = retailScreen('/margins', 'Margins').lazy(() => import('../areas/staff/retail/margins').then((m) => m.Route));
const retailStockHealthRoute = retailScreen('/stock-health', 'Stock health').lazy(() => import('../areas/staff/retail/stock-health').then((m) => m.Route));
const retailCreditControlRoute = retailScreen('/credit-control', 'Credit control').lazy(() => import('../areas/staff/retail/credit-control').then((m) => m.Route));
const retailEvaluationRoute = retailScreen('/evaluation', 'Business evaluation').lazy(() => import('../areas/staff/retail/evaluation').then((m) => m.Route));
const retailCatalogueRoute = retailScreen('/catalogue', 'Catalogue').lazy(() => import('../areas/staff/retail/catalogue').then((m) => m.Route));
const retailProductsRoute = retailScreen('/catalogue/products', 'Items').lazy(() => import('../areas/staff/retail/catalogue-products').then((m) => m.ProductsRoute));
const retailCategoriesRoute = retailScreen('/catalogue/categories', 'Categories').lazy(() => import('../areas/staff/retail/catalogue-lists').then((m) => m.CategoriesRoute));
const retailUnitsRoute = retailScreen('/catalogue/units', 'Units').lazy(() => import('../areas/staff/retail/catalogue-lists').then((m) => m.UnitsRoute));
const retailSuppliersRoute = retailScreen('/catalogue/suppliers', 'Suppliers').lazy(() => import('../areas/staff/retail/catalogue-people').then((m) => m.SuppliersRoute));
const retailImportRoute = retailScreen('/catalogue/import', 'Import items').lazy(() => import('../areas/staff/retail/catalogue-import').then((m) => m.ImportRoute));
const retailBuyersRoute = retailScreen('/catalogue/buyers', 'Credit buyers').lazy(() => import('../areas/staff/retail/catalogue-people').then((m) => m.BuyersRoute));
const retailBankingRoute = retailScreen('/banking', 'Banking').lazy(() => import('../areas/staff/retail/banking').then((m) => m.Route));
const retailBankingReportRoute = retailScreen('/banking-report', 'Banking report').lazy(() => import('../areas/staff/retail/banking').then((m) => m.ReportRoute));
const retailExpensesRoute = retailScreen('/expenses', 'Expenses').lazy(() => import('../areas/staff/retail/expenses').then((m) => m.Route));
const retailExpensesReportRoute = retailScreen('/expenses-report', 'Expenses report').lazy(() => import('../areas/staff/retail/expenses').then((m) => m.ReportRoute));
const retailExpenseListsRoute = retailScreen('/expense-lists', 'Expense lists').lazy(() => import('../areas/staff/retail/expense-lists').then((m) => m.Route));
const retailWithdrawalsRoute = retailScreen('/withdrawals', 'Cash withdrawals').lazy(() => import('../areas/staff/retail/withdrawals').then((m) => m.Route));
const retailAdvancesRoute = retailScreen('/advances', 'Advances').lazy(() => import('../areas/staff/retail/advances').then((m) => m.Route));
const retailCashSummaryRoute = retailScreen('/cash-summary', 'Cash summary').lazy(() => import('../areas/staff/retail/cash-summary').then((m) => m.Route));
const retailSavingsRoute = retailScreen('/savings', 'Savings').lazy(() => import('../areas/staff/retail/savings').then((m) => m.Route));
const retailSettingsRoute = retailScreen('/settings', 'Retail settings').lazy(() => import('../areas/staff/retail/settings').then((m) => m.Route));
const retailBranchRolesRoute = retailScreen('/branch-roles', 'Branch roles').lazy(() => import('../areas/staff/retail/branch-roles').then((m) => m.Route));
const retailPriceListsRoute = retailScreen('/price-lists', 'Price lists').lazy(() => import('../areas/staff/retail/price-lists').then((m) => m.Route));
const retailCustomerGroupsRoute = retailScreen('/customer-groups', 'Customer groups').lazy(() => import('../areas/staff/retail/customer-groups').then((m) => m.Route));
const retailAccountsRoute = retailScreen('/accounts', 'Customer accounts').lazy(() => import('../areas/staff/retail/accounts').then((m) => m.Route));
const retailCustomerAgeingRoute = retailScreen('/customer-ageing', 'Customer ageing').lazy(() => import('../areas/staff/retail/customer-ageing').then((m) => m.Route));
const retailReturnsRoute = retailScreen('/returns', 'Customer returns').lazy(() => import('../areas/staff/retail/returns').then((m) => m.Route));
const retailReturnNewRoute = retailScreen('/returns/new/$saleId', 'Take goods back').lazy(() => import('../areas/staff/retail/return-new').then((m) => m.Route));
const retailReturnRoute = retailScreen('/returns/$returnId', 'Credit note').lazy(() => import('../areas/staff/retail/credit-note').then((m) => m.Route));
const retailAccountRoute = retailScreen('/accounts/$customerId', 'Account').lazy(() => import('../areas/staff/retail/account').then((m) => m.Route));

// The living style page (#106): every component and illustration, for development builds and the
// platform host only (the page itself checks); a chunk of its own, so no tenant downloads it.
const styleRoute = createRoute({ getParentRoute: () => rootRoute, path: '/style' }).lazy(() =>
  import('../areas/style/route').then((m) => m.Route),
);

const staffLendingRoute = createRoute({ getParentRoute: () => staffRoute, path: '/lending', component: Outlet, staticData: { crumb: 'Lending' } });
const lendingLoansRoute = createRoute({ getParentRoute: () => staffLendingRoute, path: '/' }).lazy(() =>
  import('../areas/staff/lending/loans').then((m) => m.Route),
);
const staffInsightsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/insights', staticData: { crumb: 'Insights' } }).lazy(() =>
  import('../areas/staff/insights/page').then((m) => m.Route),
);
const lendingLoanRoute = createRoute({ getParentRoute: () => staffLendingRoute, path: '/loans/$loanId', staticData: { crumb: 'Loan' } }).lazy(() =>
  import('../areas/staff/lending/loan').then((m) => m.Route),
);
const lendingScreen = <P extends string>(path: P, crumb: string) => createRoute({ getParentRoute: () => staffLendingRoute, path, staticData: { crumb } });
const savingsRoute = lendingScreen('/savings', 'Savings').lazy(() => import('../areas/staff/lending/savings').then((m) => m.Route));
const savingsProductsRoute = lendingScreen('/savings/products', 'Savings products').lazy(() => import('../areas/staff/lending/savings-products').then((m) => m.Route));
const savingsAccountRoute = lendingScreen('/savings/accounts/$accountId', 'Account').lazy(() => import('../areas/staff/lending/savings-account').then((m) => m.Route));
const savingsStatementRoute = lendingScreen('/savings/accounts/$accountId/statement', 'Statement').lazy(() =>
  import('../areas/staff/lending/savings-statement').then((m) => m.Route),
);
const memberSavingsRoute = lendingScreen('/members/$memberId/savings', 'Member savings').lazy(() => import('../areas/staff/lending/member-savings').then((m) => m.Route));
const investmentsRoute = lendingScreen('/investments', 'Investments').lazy(() => import('../areas/staff/lending/investments').then((m) => m.Route));
const investmentNewRoute = lendingScreen('/investments/new', 'New investment').lazy(() => import('../areas/staff/lending/investment-new').then((m) => m.Route));
const investmentMaturitiesRoute = lendingScreen('/investments/maturities', 'Maturities').lazy(() => import('../areas/staff/lending/maturities').then((m) => m.Route));
const investmentProductsRoute = lendingScreen('/investments/products', 'Investment products').lazy(() => import('../areas/staff/lending/investment-products').then((m) => m.Route));
const memberInvestmentsRoute = lendingScreen('/investments/member/$memberId', 'Member investments').lazy(() => import('../areas/staff/lending/investments').then((m) => m.MemberRoute));
const memberInvestmentNewRoute = lendingScreen('/investments/member/$memberId/new', 'New investment').lazy(() => import('../areas/staff/lending/investment-new').then((m) => m.MemberRoute));
const investmentRoute = lendingScreen('/investments/$investmentId', 'Investment').lazy(() => import('../areas/staff/lending/investment').then((m) => m.Route));
const investmentCertificateRoute = lendingScreen('/investments/$investmentId/certificate', 'Certificate').lazy(() =>
  import('../areas/staff/lending/investment-certificate').then((m) => m.Route),
);

const signUpRoute = createRoute({ getParentRoute: () => rootRoute, path: '/sign-up' }).lazy(() =>
  import('../areas/onboarding/sign-up').then((m) => m.Route),
);
const signUpVerifyRoute = createRoute({ getParentRoute: () => rootRoute, path: '/sign-up/verify' }).lazy(() =>
  import('../areas/onboarding/applicant').then((m) => m.VerifyRoute),
);
const applicationStatusRoute = createRoute({ getParentRoute: () => rootRoute, path: '/application' }).lazy(() =>
  import('../areas/onboarding/applicant').then((m) => m.StatusRoute),
);

const operatorSignInRoute = createRoute({ getParentRoute: () => rootRoute, path: '/platform/sign-in' }).lazy(() =>
  import('../areas/platform/sign-in').then((m) => m.Route),
);
const operatorSetupRoute = createRoute({ getParentRoute: () => rootRoute, path: '/platform/setup' }).lazy(() =>
  import('../areas/platform/sign-in').then((m) => m.SetupRoute),
);
const platformRoute = createRoute({ getParentRoute: () => rootRoute, path: '/platform' }).lazy(() =>
  import('../areas/platform/route').then((m) => m.Route),
);
const platformApplicationsRoute = createRoute({ getParentRoute: () => platformRoute, path: '/' }).lazy(() =>
  import('../areas/platform/applications').then((m) => m.Route),
);
const platformApplicationRoute = createRoute({
  getParentRoute: () => platformRoute,
  path: '/applications/$applicationId',
}).lazy(() => import('../areas/platform/application').then((m) => m.Route));
const platformOutboxRoute = createRoute({ getParentRoute: () => platformRoute, path: '/outbox' }).lazy(() =>
  import('../areas/platform/outbox').then((m) => m.Route),
);

const memberRoute = createRoute({ getParentRoute: () => rootRoute, path: '/member' }).lazy(() =>
  import('../areas/member/route').then((m) => m.Route),
);

export const router = createRouter({
  routeTree: rootRoute.addChildren([
    indexRoute,
    signInRoute,
    acceptInvitationRoute,
    staffRoute.addChildren([staffHomeRoute, staffApprovalsRoute, staffSetupRoute, staffRetailRoute.addChildren([
        retailHomeRoute,
        retailSaleRoute,
        retailCreditSalesRoute,
        retailSalesRoute,
        retailRestockRoute,
        retailUsageRoute,
        retailStockRoute,
        retailStocktakeRoute,
        retailTransferRoute,
        retailTransfersRoute,
        retailStockByLocationRoute,
        retailInTransitRoute,
        retailValuationRoute,
        retailProfitRoute,
        retailSalesAnalysisRoute,
        retailMarginsRoute,
        retailStockHealthRoute,
        retailCreditControlRoute,
        retailEvaluationRoute,
        retailCatalogueRoute,
        retailProductsRoute,
        retailCategoriesRoute,
        retailUnitsRoute,
        retailSuppliersRoute,
        retailBuyersRoute,
        retailImportRoute,
        retailSavingsRoute,
        retailCashSummaryRoute,
        retailAdvancesRoute,
        retailWithdrawalsRoute,
        retailExpensesRoute,
        retailExpensesReportRoute,
        retailExpenseListsRoute,
        retailSettingsRoute,
        retailBranchRolesRoute,
        retailPriceListsRoute,
        retailCustomerGroupsRoute,
        retailAccountsRoute,
        retailAccountRoute,
        retailCustomerAgeingRoute,
        retailReturnsRoute,
        retailReturnNewRoute,
        retailReturnRoute,
        retailBankingRoute,
        retailBankingReportRoute,
      ]), staffLendingRoute.addChildren([
        lendingLoansRoute,
        lendingLoanRoute,
        savingsRoute,
        savingsProductsRoute,
        savingsAccountRoute,
        savingsStatementRoute,
        memberSavingsRoute,
        investmentsRoute,
        investmentNewRoute,
        investmentMaturitiesRoute,
        investmentProductsRoute,
        memberInvestmentsRoute,
        memberInvestmentNewRoute,
        investmentRoute,
        investmentCertificateRoute,
      ]), staffInsightsRoute]),
    memberRoute,
    styleRoute,
    signUpRoute,
    signUpVerifyRoute,
    applicationStatusRoute,
    operatorSignInRoute,
    operatorSetupRoute,
    platformRoute.addChildren([platformApplicationsRoute, platformApplicationRoute, platformOutboxRoute]),
  ]),
});

declare module '@tanstack/react-router' {
  // The breadcrumb label of a staff screen (components/breadcrumbs.tsx reads it; no label, no crumb).
  interface StaticDataRouteOption {
    crumb?: string;
  }
  interface Register {
    router: typeof router;
  }
}
