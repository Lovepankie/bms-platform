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

const staffRoute = createRoute({ getParentRoute: () => rootRoute, path: '/staff' }).lazy(() =>
  import('../areas/staff/route').then((m) => m.Route),
);

const staffHomeRoute = createRoute({ getParentRoute: () => staffRoute, path: '/' }).lazy(() =>
  import('../areas/staff/home').then((m) => m.Route),
);

const staffApprovalsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/approvals' }).lazy(() =>
  import('../areas/staff/approvals').then((m) => m.Route),
);

const staffSetupRoute = createRoute({ getParentRoute: () => staffRoute, path: '/setup' }).lazy(() =>
  import('../areas/staff/setup').then((m) => m.Route),
);

const staffRetailRoute = createRoute({ getParentRoute: () => staffRoute, path: '/retail', component: Outlet });

const retailScreen = <P extends string>(path: P) => createRoute({ getParentRoute: () => staffRetailRoute, path });
const retailHomeRoute = retailScreen('/').lazy(() => import('../areas/staff/retail/home').then((m) => m.Route));
const retailSaleRoute = retailScreen('/sale').lazy(() => import('../areas/staff/retail/sale').then((m) => m.Route));
const retailCreditSalesRoute = retailScreen('/credit-sales').lazy(() => import('../areas/staff/retail/sales').then((m) => m.CreditRoute));
const retailSalesRoute = retailScreen('/sales').lazy(() => import('../areas/staff/retail/sales').then((m) => m.Route));
const retailRestockRoute = retailScreen('/restock').lazy(() => import('../areas/staff/retail/restock').then((m) => m.Route));
const retailUsageRoute = retailScreen('/usage').lazy(() => import('../areas/staff/retail/usage').then((m) => m.Route));
const retailStockRoute = retailScreen('/stock').lazy(() => import('../areas/staff/retail/stock').then((m) => m.Route));
const retailStocktakeRoute = retailScreen('/stocktake').lazy(() => import('../areas/staff/retail/stocktake').then((m) => m.Route));
const retailTransferRoute = retailScreen('/transfer').lazy(() => import('../areas/staff/retail/transfer').then((m) => m.Route));
const retailTransfersRoute = retailScreen('/transfers').lazy(() => import('../areas/staff/retail/transfers').then((m) => m.Route));
const retailValuationRoute = retailScreen('/valuation').lazy(() => import('../areas/staff/retail/profit').then((m) => m.ValuationRoute));
const retailProfitRoute = retailScreen('/profit').lazy(() => import('../areas/staff/retail/profit').then((m) => m.Route));
const retailSalesAnalysisRoute = retailScreen('/sales-analysis').lazy(() => import('../areas/staff/retail/sales-analysis').then((m) => m.Route));
const retailMarginsRoute = retailScreen('/margins').lazy(() => import('../areas/staff/retail/margins').then((m) => m.Route));
const retailStockHealthRoute = retailScreen('/stock-health').lazy(() => import('../areas/staff/retail/stock-health').then((m) => m.Route));
const retailCreditControlRoute = retailScreen('/credit-control').lazy(() => import('../areas/staff/retail/credit-control').then((m) => m.Route));
const retailEvaluationRoute = retailScreen('/evaluation').lazy(() => import('../areas/staff/retail/evaluation').then((m) => m.Route));
const retailCatalogueRoute = retailScreen('/catalogue').lazy(() => import('../areas/staff/retail/catalogue').then((m) => m.Route));
const retailProductsRoute = retailScreen('/catalogue/products').lazy(() => import('../areas/staff/retail/catalogue-products').then((m) => m.ProductsRoute));
const retailCategoriesRoute = retailScreen('/catalogue/categories').lazy(() => import('../areas/staff/retail/catalogue-lists').then((m) => m.CategoriesRoute));
const retailUnitsRoute = retailScreen('/catalogue/units').lazy(() => import('../areas/staff/retail/catalogue-lists').then((m) => m.UnitsRoute));
const retailSuppliersRoute = retailScreen('/catalogue/suppliers').lazy(() => import('../areas/staff/retail/catalogue-people').then((m) => m.SuppliersRoute));
const retailImportRoute = retailScreen('/catalogue/import').lazy(() => import('../areas/staff/retail/catalogue-import').then((m) => m.ImportRoute));
const retailBuyersRoute = retailScreen('/catalogue/buyers').lazy(() => import('../areas/staff/retail/catalogue-people').then((m) => m.BuyersRoute));
const retailBankingRoute = retailScreen('/banking').lazy(() => import('../areas/staff/retail/banking').then((m) => m.Route));
const retailBankingReportRoute = retailScreen('/banking-report').lazy(() => import('../areas/staff/retail/banking').then((m) => m.ReportRoute));
const retailExpensesRoute = retailScreen('/expenses').lazy(() => import('../areas/staff/retail/expenses').then((m) => m.Route));
const retailExpensesReportRoute = retailScreen('/expenses-report').lazy(() => import('../areas/staff/retail/expenses').then((m) => m.ReportRoute));
const retailExpenseListsRoute = retailScreen('/expense-lists').lazy(() => import('../areas/staff/retail/expense-lists').then((m) => m.Route));
const retailWithdrawalsRoute = retailScreen('/withdrawals').lazy(() => import('../areas/staff/retail/withdrawals').then((m) => m.Route));
const retailAdvancesRoute = retailScreen('/advances').lazy(() => import('../areas/staff/retail/advances').then((m) => m.Route));
const retailCashSummaryRoute = retailScreen('/cash-summary').lazy(() => import('../areas/staff/retail/cash-summary').then((m) => m.Route));
const retailSavingsRoute = retailScreen('/savings').lazy(() => import('../areas/staff/retail/savings').then((m) => m.Route));

// The living style page (#106): every component and illustration, for development builds and the
// platform host only (the page itself checks); a chunk of its own, so no tenant downloads it.
const styleRoute = createRoute({ getParentRoute: () => rootRoute, path: '/style' }).lazy(() =>
  import('../areas/style/route').then((m) => m.Route),
);

const staffLendingRoute = createRoute({ getParentRoute: () => staffRoute, path: '/lending', component: Outlet });
const lendingLoansRoute = createRoute({ getParentRoute: () => staffLendingRoute, path: '/' }).lazy(() =>
  import('../areas/staff/lending/loans').then((m) => m.Route),
);
const staffInsightsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/insights' }).lazy(() =>
  import('../areas/staff/insights/page').then((m) => m.Route),
);
const lendingLoanRoute = createRoute({ getParentRoute: () => staffLendingRoute, path: '/loans/$loanId' }).lazy(() =>
  import('../areas/staff/lending/loan').then((m) => m.Route),
);
const lendingScreen = <P extends string>(path: P) => createRoute({ getParentRoute: () => staffLendingRoute, path });
const savingsRoute = lendingScreen('/savings').lazy(() => import('../areas/staff/lending/savings').then((m) => m.Route));
const savingsProductsRoute = lendingScreen('/savings/products').lazy(() => import('../areas/staff/lending/savings-products').then((m) => m.Route));
const savingsAccountRoute = lendingScreen('/savings/accounts/$accountId').lazy(() => import('../areas/staff/lending/savings-account').then((m) => m.Route));
const savingsStatementRoute = lendingScreen('/savings/accounts/$accountId/statement').lazy(() =>
  import('../areas/staff/lending/savings-statement').then((m) => m.Route),
);
const memberSavingsRoute = lendingScreen('/members/$memberId/savings').lazy(() => import('../areas/staff/lending/member-savings').then((m) => m.Route));
const investmentsRoute = lendingScreen('/investments').lazy(() => import('../areas/staff/lending/investments').then((m) => m.Route));
const investmentNewRoute = lendingScreen('/investments/new').lazy(() => import('../areas/staff/lending/investment-new').then((m) => m.Route));
const investmentMaturitiesRoute = lendingScreen('/investments/maturities').lazy(() => import('../areas/staff/lending/maturities').then((m) => m.Route));
const investmentProductsRoute = lendingScreen('/investments/products').lazy(() => import('../areas/staff/lending/investment-products').then((m) => m.Route));
const memberInvestmentsRoute = lendingScreen('/investments/member/$memberId').lazy(() => import('../areas/staff/lending/investments').then((m) => m.MemberRoute));
const memberInvestmentNewRoute = lendingScreen('/investments/member/$memberId/new').lazy(() => import('../areas/staff/lending/investment-new').then((m) => m.MemberRoute));
const investmentRoute = lendingScreen('/investments/$investmentId').lazy(() => import('../areas/staff/lending/investment').then((m) => m.Route));
const investmentCertificateRoute = lendingScreen('/investments/$investmentId/certificate').lazy(() =>
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
  interface Register {
    router: typeof router;
  }
}
