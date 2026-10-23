import { Outlet, createRootRoute, createRoute, createRouter, useRouterState } from '@tanstack/react-router';
import { useShellBrand } from './branding';
import { Landing } from './landing';
import { RefreshButton } from './refresh-button';
import { Shell } from './shell';
import { RouteError } from './stale-app';

// Route-level split (ADR-009): /staff and /member are separate lazily loaded route trees, so a
// member never downloads staff code; sign-in and invitation acceptance are small chunks of their
// own. The platform host (BMS_PLATFORM_HOST) serves the public sign-up page and applicant page and
// the operator portal under /platform (ADR-024), each lazily loaded as well.

// The root layout is the shared shell (brand bar, theme colour, Powered-by footer): every route
// below it inherits it, so no area draws its own.
// The header controls show where there is data to refresh: the staff, member and operator areas, not the public pages.
// The operator sign-in and first-run setup sit under /platform but are plain forms with no data, so they get no button.
const DATA_AREA = /^\/(staff|member|platform)(\/|$)/;
const NO_DATA = /^\/platform\/(sign-in|setup)\/?$/;

/** Whether the header shows the refresh button on `pathname`. */
export const showsRefresh = (pathname: string) => DATA_AREA.test(pathname) && !NO_DATA.test(pathname);

function RootLayout() {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  return (
    <Shell brand={useShellBrand()} actions={showsRefresh(pathname) ? <RefreshButton key={pathname} /> : undefined}>
      <Outlet />
    </Shell>
  );
}

const rootRoute = createRootRoute({ component: RootLayout });

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: Landing });

const signInRoute = createRoute({ getParentRoute: () => rootRoute, path: '/sign-in' }).lazy(() =>
  import('../areas/auth/sign-in').then((m) => m.Route),
);

const forgotPasswordRoute = createRoute({ getParentRoute: () => rootRoute, path: '/forgot-password' }).lazy(() =>
  import('../areas/auth/forgot-password').then((m) => m.Route),
);

const resetPasswordRoute = createRoute({ getParentRoute: () => rootRoute, path: '/reset-password' }).lazy(() =>
  import('../areas/auth/reset-password').then((m) => m.Route),
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

// Branches: close or reopen one, mark a head office as keeping no goods (issue #329). Under Business set-up in the trail.
const staffBranchesRoute = createRoute({ getParentRoute: () => staffRoute, path: '/setup/branches', staticData: { crumb: 'Branches' } }).lazy(() =>
  import('../areas/staff/branches/page').then((m) => m.Route),
);

// Two-step sign-in: the administrator's policy under Business set-up, and a person's own set-up (issue #348, ADR-046).
const staffTwoStepPolicyRoute = createRoute({ getParentRoute: () => staffRoute, path: '/setup/two-step', staticData: { crumb: 'Two-step sign-in' } }).lazy(() =>
  import('../areas/staff/two-step/page').then((m) => m.Route),
);
// What the business sends by push and when the daily summary goes (issue #412). Under Business set-up in the trail.
const staffBusinessNotificationsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/setup/notifications', staticData: { crumb: 'Notifications for the business' } }).lazy(() =>
  import('../areas/staff/notifications/business').then((m) => m.Route),
);
// Referrals (issue #464, ADR-069): the business's own link to the sign-up and the businesses it referred. Under Business set-up.
const staffReferralsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/setup/referrals', staticData: { crumb: 'Referrals' } }).lazy(() =>
  import('../areas/staff/referrals/page').then((m) => m.Route),
);
const staffOwnTwoStepRoute = createRoute({ getParentRoute: () => staffRoute, path: '/two-step', staticData: { crumb: 'Your two-step sign-in' } }).lazy(() =>
  import('../areas/staff/two-step/own').then((m) => m.Route),
);

const staffRetailRoute = createRoute({ getParentRoute: () => staffRoute, path: '/retail', component: Outlet, staticData: { crumb: 'Retail' } });

const retailScreen = <P extends string>(path: P, crumb?: string) => createRoute({ getParentRoute: () => staffRetailRoute, path, staticData: { crumb } });
const retailHomeRoute = retailScreen('/').lazy(() => import('../areas/staff/retail/home').then((m) => m.Route));
const retailSaleRoute = retailScreen('/sale', 'Record a sale').lazy(() => import('../areas/staff/retail/sale').then((m) => m.Route));
const retailCreditSalesRoute = retailScreen('/credit-sales', 'Credit sales').lazy(() => import('../areas/staff/retail/sales').then((m) => m.CreditRoute));
const retailSalesRoute = retailScreen('/sales', 'All sales').lazy(() => import('../areas/staff/retail/sales').then((m) => m.Route));
// Customer orders with delivery (issue #385): the list by step, one order with its history, and the fees and cancellations report.
const retailOrdersRoute = retailScreen('/orders', 'Orders').lazy(() => import('../areas/staff/retail/orders').then((m) => m.Route));
const retailOrderRoute = retailScreen('/orders/$orderId', 'Order').lazy(() => import('../areas/staff/retail/order').then((m) => m.Route));
const retailOrdersReportRoute = retailScreen('/orders-report', 'Delivery fees and cancellations').lazy(() => import('../areas/staff/retail/orders-report').then((m) => m.Route));
const retailRestockRoute = retailScreen('/restock', 'Restock').lazy(() => import('../areas/staff/retail/restock').then((m) => m.Route));
const retailRestocksRoute = retailScreen('/restocks', 'Past restocks').lazy(() => import('../areas/staff/retail/restocks').then((m) => m.Route));
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
const retailControlsRoute = retailScreen('/controls', 'Controls').lazy(() => import('../areas/staff/retail/controls').then((m) => m.Route));
const retailCatalogueRoute = retailScreen('/catalogue', 'Catalogue').lazy(() => import('../areas/staff/retail/catalogue').then((m) => m.Route));
const retailProductsRoute = retailScreen('/catalogue/products', 'Items').lazy(() => import('../areas/staff/retail/catalogue-products').then((m) => m.ProductsRoute));
// Items with sizes and colours (issue #384): under Items, so the trail reads Catalogue, Items, then the item.
const retailSizesNewRoute = retailScreen('/catalogue/products/sizes/new', 'Add an item with sizes and colours').lazy(() => import('../areas/staff/retail/catalogue-variants').then((m) => m.NewFamilyRoute));
const retailSizesJoinRoute = retailScreen('/catalogue/products/sizes/join', 'Join items as sizes').lazy(() => import('../areas/staff/retail/catalogue-variants').then((m) => m.JoinRoute));
const retailSizesRoute = retailScreen('/catalogue/products/sizes/$familyId', 'Sizes and colours').lazy(() => import('../areas/staff/retail/catalogue-family').then((m) => m.FamilyRoute));
const retailCategoriesRoute = retailScreen('/catalogue/categories', 'Categories').lazy(() => import('../areas/staff/retail/catalogue-lists').then((m) => m.CategoriesRoute));
const retailUnitsRoute = retailScreen('/catalogue/units', 'Units').lazy(() => import('../areas/staff/retail/catalogue-lists').then((m) => m.UnitsRoute));
const retailSuppliersRoute = retailScreen('/catalogue/suppliers', 'Suppliers').lazy(() => import('../areas/staff/retail/catalogue-people').then((m) => m.SuppliersRoute));
const retailImportRoute = retailScreen('/catalogue/import', 'Import items').lazy(() => import('../areas/staff/retail/catalogue-import').then((m) => m.ImportRoute));
// Barcode labels (issue #345): an A4 sheet of labels with the name, the price and the barcode.
const retailLabelsRoute = retailScreen('/catalogue/labels', 'Barcode labels').lazy(() => import('../areas/staff/retail/labels').then((m) => m.LabelsRoute));
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
const retailDocumentsRoute = retailScreen('/documents', 'Documents').lazy(() => import('../areas/staff/retail/documents').then((m) => m.Route));
const retailQuotationsRoute = retailScreen('/quotations', 'Quotations').lazy(() => import('../areas/staff/retail/quotations').then((m) => m.Route));
const retailReturnsRoute = retailScreen('/returns', 'Customer returns').lazy(() => import('../areas/staff/retail/returns').then((m) => m.Route));
const retailReturnNewRoute = retailScreen('/returns/new/$saleId', 'Take goods back').lazy(() => import('../areas/staff/retail/return-new').then((m) => m.Route));
const retailReturnRoute = retailScreen('/returns/$returnId', 'Credit note').lazy(() => import('../areas/staff/retail/credit-note').then((m) => m.Route));
const retailAccountRoute = retailScreen('/accounts/$customerId', 'Account').lazy(() => import('../areas/staff/retail/account').then((m) => m.Route));
// Optional purchasing (ADR-033, issue #198): each route is lazy-loaded and shown only while the purchasing capability is on.
const retailPurchaseOrdersRoute = retailScreen('/purchase-orders', 'Purchase orders').lazy(() => import('../areas/staff/retail/purchase-orders').then((m) => m.Route));
const retailPurchaseOrderNewRoute = retailScreen('/purchase-orders/new', 'New purchase order').lazy(() => import('../areas/staff/retail/purchase-order-form').then((m) => m.NewRoute));
const retailPurchaseOrderRoute = retailScreen('/purchase-orders/$orderId', 'Purchase order').lazy(() => import('../areas/staff/retail/purchase-order').then((m) => m.Route));
const retailPurchaseOrderEditRoute = retailScreen('/purchase-orders/$orderId/edit', 'Change order').lazy(() => import('../areas/staff/retail/purchase-order-form').then((m) => m.EditRoute));
const retailPurchaseOrderReceiveRoute = retailScreen('/purchase-orders/$orderId/receive', 'Receive goods').lazy(() => import('../areas/staff/retail/goods-receive').then((m) => m.OrderReceiveRoute));
const retailGoodsReceivedRoute = retailScreen('/goods-received', 'Goods received').lazy(() => import('../areas/staff/retail/goods-received').then((m) => m.Route));
const retailGoodsReceivedNewRoute = retailScreen('/goods-received/new', 'Receive without an order').lazy(() => import('../areas/staff/retail/goods-receive').then((m) => m.BlindRoute));
const retailGoodsReceiptRoute = retailScreen('/goods-received/$receiptId', 'Delivery').lazy(() => import('../areas/staff/retail/goods-received').then((m) => m.DetailRoute));
const retailSupplierBillsRoute = retailScreen('/supplier-bills', 'Supplier invoices').lazy(() => import('../areas/staff/retail/supplier-bills').then((m) => m.Route));
const retailSupplierBillNewRoute = retailScreen('/supplier-bills/new', 'New supplier invoice').lazy(() => import('../areas/staff/retail/supplier-bill-new').then((m) => m.Route));
const retailSupplierBillRoute = retailScreen('/supplier-bills/$billId', 'Supplier invoice').lazy(() => import('../areas/staff/retail/supplier-bills').then((m) => m.DetailRoute));
const retailSupplierPaymentsRoute = retailScreen('/supplier-payments', 'Supplier payments').lazy(() => import('../areas/staff/retail/supplier-payments').then((m) => m.Route));
const retailSupplierPaymentNewRoute = retailScreen('/supplier-payments/new', 'Pay a supplier').lazy(() => import('../areas/staff/retail/supplier-payment-new').then((m) => m.Route));
const retailSupplierPaymentRoute = retailScreen('/supplier-payments/$paymentId', 'Supplier payment').lazy(() => import('../areas/staff/retail/supplier-payments').then((m) => m.DetailRoute));
const retailSupplierPayablesRoute = retailScreen('/supplier-payables', 'What you owe suppliers').lazy(() => import('../areas/staff/retail/supplier-payables').then((m) => m.Route));
// Landed costs and supplier credit notes (issue #199), also shown only while the purchasing capability is on.
const retailLandedCostsRoute = retailScreen('/landed-costs', 'Landed costs').lazy(() => import('../areas/staff/retail/landed-costs').then((m) => m.Route));
const retailLandedCostNewRoute = retailScreen('/landed-costs/new', 'New landed cost').lazy(() => import('../areas/staff/retail/landed-cost-new').then((m) => m.Route));
const retailLandedCostRoute = retailScreen('/landed-costs/$landedCostId', 'Landed cost').lazy(() => import('../areas/staff/retail/landed-costs').then((m) => m.DetailRoute));
const retailSupplierCreditsRoute = retailScreen('/supplier-credits', 'Supplier credit notes').lazy(() => import('../areas/staff/retail/supplier-credits').then((m) => m.Route));
const retailSupplierCreditNewRoute = retailScreen('/supplier-credits/new', 'New credit note').lazy(() => import('../areas/staff/retail/supplier-credit-new').then((m) => m.Route));
const retailSupplierCreditRoute = retailScreen('/supplier-credits/$creditId', 'Supplier credit note').lazy(() => import('../areas/staff/retail/supplier-credits').then((m) => m.DetailRoute));

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
// The AI assistant's settings (extras slice C3, issue #226).
const staffAssistantRoute = createRoute({ getParentRoute: () => staffRoute, path: '/assistant', staticData: { crumb: 'AI assistant' } }).lazy(() =>
  import('../areas/staff/assistant/page').then((m) => m.Route),
);
// Receive payments: the business's own Pesapal account (extras slice A7, issue #233).
const staffPaymentsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/payments', staticData: { crumb: 'Receive payments' } }).lazy(() =>
  import('../areas/staff/payments/page').then((m) => m.Route),
);
// Notifications and app: install BMS and turn notifications on for this device (ADR-050).
const staffNotificationsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/notifications', staticData: { crumb: 'Notifications and app' } }).lazy(() =>
  import('../areas/staff/notifications/page').then((m) => m.Route),
);
// Feedback (issue #447, ADR-065): any member of staff sends an idea, a problem or a question to the BMS team.
const staffFeedbackRoute = createRoute({ getParentRoute: () => staffRoute, path: '/feedback', staticData: { crumb: 'Feedback' } }).lazy(() =>
  import('../areas/staff/feedback/page').then((m) => m.Route),
);

// Staff users: who works in the business, invite and roles (issue #319).
const staffUsersRoute = createRoute({ getParentRoute: () => staffRoute, path: '/users', staticData: { crumb: 'Staff users' } }).lazy(() =>
  import('../areas/staff/users/page').then((m) => m.Route),
);
const staffUserInviteRoute = createRoute({ getParentRoute: () => staffRoute, path: '/users/new', staticData: { crumb: 'Invite someone' } }).lazy(() =>
  import('../areas/staff/users/invite').then((m) => m.Route),
);
const staffReportsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/reports', staticData: { crumb: 'Reports' } }).lazy(() =>
  import('../areas/staff/reports/page').then((m) => m.Route),
);
// Tenant SMS settings, wording and opt-outs (extras slice B3, issue #218): one heading, a small menu, three pages.
const staffSmsRoute = createRoute({ getParentRoute: () => staffRoute, path: '/sms', staticData: { crumb: 'SMS' } }).lazy(() =>
  import('../areas/staff/sms/layout').then((m) => m.Route),
);
const smsSettingsRoute = createRoute({ getParentRoute: () => staffSmsRoute, path: '/' }).lazy(() =>
  import('../areas/staff/sms/settings').then((m) => m.Route),
);
const smsWordingRoute = createRoute({ getParentRoute: () => staffSmsRoute, path: '/wording', staticData: { crumb: 'Wording' } }).lazy(() =>
  import('../areas/staff/sms/wording').then((m) => m.Route),
);
const smsOptOutsRoute = createRoute({ getParentRoute: () => staffSmsRoute, path: '/opt-outs', staticData: { crumb: 'Opt-outs' } }).lazy(() =>
  import('../areas/staff/sms/opt-outs').then((m) => m.Route),
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
// Members, next of kin, KYC documents and the collateral register (issue #280).
const existingLoansRoute = lendingScreen('/existing-loans', 'Existing loans').lazy(() => import('../areas/staff/lending/existing-loans').then((m) => m.Route));
const existingLoansImportRoute = lendingScreen('/existing-loans/import', 'Import').lazy(() =>
  import('../areas/staff/lending/existing-loans-import').then((m) => m.Route),
);
const membersRoute = lendingScreen('/members', 'Members').lazy(() => import('../areas/staff/lending/members').then((m) => m.Route));
const memberNewRoute = lendingScreen('/members/new', 'New member').lazy(() => import('../areas/staff/lending/member-form').then((m) => m.NewRoute));
const lendingMemberRoute = lendingScreen('/members/$memberId', 'Member').lazy(() => import('../areas/staff/lending/member-page').then((m) => m.Route));
const memberEditRoute = lendingScreen('/members/$memberId/edit', 'Change details').lazy(() => import('../areas/staff/lending/member-form').then((m) => m.EditRoute));
const collateralRoute = lendingScreen('/collateral', 'Collateral').lazy(() => import('../areas/staff/lending/collateral').then((m) => m.Route));
const collateralNewRoute = createRoute({
  getParentRoute: () => staffLendingRoute,
  path: '/collateral/new',
  staticData: { crumb: 'Register collateral' },
  // The member to start from, as an id (never a phone or an ID number).
  validateSearch: (s: Record<string, unknown>): { memberId?: string } => ({ memberId: typeof s.memberId === 'string' ? s.memberId : undefined }),
}).lazy(() => import('../areas/staff/lending/collateral-form').then((m) => m.NewRoute));
const collateralItemRoute = lendingScreen('/collateral/$collateralId', 'Item').lazy(() => import('../areas/staff/lending/collateral-page').then((m) => m.Route));
const collateralEditRoute = lendingScreen('/collateral/$collateralId/edit', 'Change details').lazy(() => import('../areas/staff/lending/collateral-form').then((m) => m.EditRoute));
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
// Staff of a business find their own sign-in page from the platform home (issue #383).
const findSignInRoute = createRoute({ getParentRoute: () => rootRoute, path: '/find-sign-in' }).lazy(() =>
  import('../areas/onboarding/find-sign-in').then((m) => m.Route),
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
// The operator screens of issue #407: every business, one business's page, and the price book.
const platformBusinessesRoute = createRoute({ getParentRoute: () => platformRoute, path: '/businesses' }).lazy(() =>
  import('../areas/platform/businesses').then((m) => m.Route),
);
const platformBusinessRoute = createRoute({ getParentRoute: () => platformRoute, path: '/businesses/$tenantId' }).lazy(() =>
  import('../areas/platform/business').then((m) => m.Route),
);
const platformPlansRoute = createRoute({ getParentRoute: () => platformRoute, path: '/plans' }).lazy(() =>
  import('../areas/platform/plans').then((m) => m.Route),
);
// Feedback from every business (issue #447, ADR-065).
const platformFeedbackRoute = createRoute({ getParentRoute: () => platformRoute, path: '/feedback' }).lazy(() =>
  import('../areas/platform/feedback').then((m) => m.Route),
);

const memberRoute = createRoute({ getParentRoute: () => rootRoute, path: '/member' }).lazy(() =>
  import('../areas/member/route').then((m) => m.Route),
);

export const router = createRouter({
  routeTree: rootRoute.addChildren([
    indexRoute,
    signInRoute,
    forgotPasswordRoute,
    resetPasswordRoute,
    acceptInvitationRoute,
    staffRoute.addChildren([staffHomeRoute, staffApprovalsRoute, staffSetupRoute, staffBranchesRoute, staffTwoStepPolicyRoute, staffBusinessNotificationsRoute, staffReferralsRoute, staffOwnTwoStepRoute, staffRetailRoute.addChildren([
        retailHomeRoute,
        retailSaleRoute,
        retailCreditSalesRoute,
        retailSalesRoute,
        retailOrdersRoute,
        retailOrderRoute,
        retailOrdersReportRoute,
        retailRestockRoute,
        retailRestocksRoute,
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
        retailControlsRoute,
        retailCatalogueRoute,
        retailProductsRoute,
        retailSizesNewRoute,
        retailSizesJoinRoute,
        retailSizesRoute,
        retailCategoriesRoute,
        retailUnitsRoute,
        retailSuppliersRoute,
        retailBuyersRoute,
        retailImportRoute,
        retailLabelsRoute,
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
        retailDocumentsRoute,
        retailQuotationsRoute,
        retailReturnsRoute,
        retailReturnNewRoute,
        retailReturnRoute,
        retailPurchaseOrdersRoute,
        retailPurchaseOrderNewRoute,
        retailPurchaseOrderRoute,
        retailPurchaseOrderEditRoute,
        retailPurchaseOrderReceiveRoute,
        retailGoodsReceivedRoute,
        retailGoodsReceivedNewRoute,
        retailGoodsReceiptRoute,
        retailSupplierBillsRoute,
        retailSupplierBillNewRoute,
        retailSupplierBillRoute,
        retailSupplierPaymentsRoute,
        retailSupplierPaymentNewRoute,
        retailSupplierPaymentRoute,
        retailSupplierPayablesRoute,
        retailLandedCostsRoute,
        retailLandedCostNewRoute,
        retailLandedCostRoute,
        retailSupplierCreditsRoute,
        retailSupplierCreditNewRoute,
        retailSupplierCreditRoute,
        retailBankingRoute,
        retailBankingReportRoute,
      ]), staffLendingRoute.addChildren([
        lendingLoansRoute,
        lendingLoanRoute,
        savingsRoute,
        savingsProductsRoute,
        savingsAccountRoute,
        savingsStatementRoute,
        existingLoansRoute,
        existingLoansImportRoute,
        membersRoute,
        memberNewRoute,
        lendingMemberRoute,
        memberEditRoute,
        collateralRoute,
        collateralNewRoute,
        collateralItemRoute,
        collateralEditRoute,
        memberSavingsRoute,
        investmentsRoute,
        investmentNewRoute,
        investmentMaturitiesRoute,
        investmentProductsRoute,
        memberInvestmentsRoute,
        memberInvestmentNewRoute,
        investmentRoute,
        investmentCertificateRoute,
      ]), staffInsightsRoute, staffAssistantRoute, staffPaymentsRoute, staffUsersRoute, staffUserInviteRoute, staffNotificationsRoute, staffFeedbackRoute, staffReportsRoute, staffSmsRoute.addChildren([smsSettingsRoute, smsWordingRoute, smsOptOutsRoute])]),
    memberRoute,
    styleRoute,
    signUpRoute,
    findSignInRoute,
    signUpVerifyRoute,
    applicationStatusRoute,
    operatorSignInRoute,
    operatorSetupRoute,
    platformRoute.addChildren([
      platformApplicationsRoute,
      platformApplicationRoute,
      platformOutboxRoute,
      platformBusinessesRoute,
      platformBusinessRoute,
      platformPlansRoute,
      platformFeedbackRoute,
    ]),
  ]),
  // A phone on the app from before a deploy reloads itself instead of showing the raw error.
  defaultErrorComponent: RouteError,
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
