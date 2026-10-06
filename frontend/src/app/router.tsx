import { useQuery } from '@tanstack/react-query';
import { Link, Outlet, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { useShellBrand } from './branding';
import { classifyHost, loadHostConfig } from './hosts';
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

function AreaChooser() {
  // The host decides the area (ADR-018). The Vite dev server's X-Tenant slug counts as a tenant host.
  const hosts = useQuery({ queryKey: ['app-config'], queryFn: loadHostConfig, staleTime: Infinity });
  if (!hosts.data) return null;
  const area = import.meta.env.DEV && import.meta.env.VITE_DEV_TENANT
    ? { kind: 'tenant' as const }
    : classifyHost(window.location.hostname, hosts.data);
  if (area.kind === 'platform') {
    return (
      <main className="landing">
        <section className="card landing-hero">
          <BrandMark />
          <h1>BMS by Rincoltech</h1>
          <p className="lead">Run your shop or lending business from your phone: sales, stock, members and loans.</p>
          <ul className="landing-actions">
            <li>
              <Link to="/sign-up" className="btn btn-primary btn-lg btn-block">
                Apply to start
              </Link>
            </li>
            <li>
              <Link to="/platform/sign-in" className="btn btn-lg btn-block">
                Operator sign-in
              </Link>
            </li>
          </ul>
        </section>
        <div className="landing-help">
          <div className="card card-muted">
            <h2>New business</h2>
            <p>Apply in a few minutes. We check every application and email you a link to sign in.</p>
          </div>
          <div className="card card-muted">
            <h2>Already a customer</h2>
            <p>Sign in at the address in your activation email.</p>
          </div>
        </div>
      </main>
    );
  }
  if (area.kind === 'unknown') {
    return (
      <main className="landing">
        <section className="card landing-hero">
          <BrandMark />
          <h1>BMS Platform</h1>
          <p className="lead">No BMS tenant is served at this address. Check the address you were given.</p>
        </section>
      </main>
    );
  }
  return (
    <main className="landing">
      <section className="card landing-hero">
        <BrandMark />
        <h1>Welcome</h1>
        <p className="lead">Sales, stock, members and loans for your business, in one place.</p>
        <ul className="landing-actions">
          <li>
            <Link to="/sign-in" className="btn btn-primary btn-lg btn-block">
              Staff sign-in
            </Link>
          </li>
          <li>
            <Link to="/member" className="btn btn-lg btn-block">
              Member portal
            </Link>
          </li>
        </ul>
      </section>
      <div className="landing-help">
        <div className="card card-muted">
          <h2>Staff</h2>
          <p>Use the email or phone and password from your invitation.</p>
        </div>
        <div className="card card-muted">
          <h2>Members</h2>
          <p>See your balances, loan schedule and payments.</p>
        </div>
      </div>
    </main>
  );
}

// The brand on the landing page: the host's logo when it has one, else a wordmark of its name.
function BrandMark() {
  const brand = useShellBrand();
  if (brand.logoSrc) return <img className="brand-logo" src={brand.logoSrc} alt={brand.name} />;
  return (
    <div className="wordmark">
      <span className="wordmark-mark" aria-hidden="true">
        {brand.name.charAt(0)}
      </span>
      <span>{brand.name}</span>
    </div>
  );
}

const rootRoute = createRootRoute({ component: RootLayout });

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: AreaChooser });

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
      ])]),
    memberRoute,
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
