import { useQuery } from '@tanstack/react-query';
import { Link, Outlet, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { useShellBrand } from './branding';
import { classifyHost, loadHostConfig } from './hosts';
import { Shell } from './shell';

// Route-level split (ADR-009): /staff and /member are separate lazily loaded route trees, so a
// member never downloads staff code; sign-in and invitation acceptance are small chunks of their
// own. The platform console (on the platform host, BMS_PLATFORM_HOST) joins as a further tree
// when built; until then its host shows a placeholder.

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
          <span className="badge badge-info">Coming soon</span>
          <h1>BMS Platform console</h1>
          <p className="lead">The platform console is not built yet. Tenants sign in at their own address.</p>
        </section>
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
const retailRestockRoute = retailScreen('/restock').lazy(() => import('../areas/staff/retail/restock').then((m) => m.Route));
const retailUsageRoute = retailScreen('/usage').lazy(() => import('../areas/staff/retail/usage').then((m) => m.Route));
const retailStockRoute = retailScreen('/stock').lazy(() => import('../areas/staff/retail/stock').then((m) => m.Route));
const retailStocktakeRoute = retailScreen('/stocktake').lazy(() => import('../areas/staff/retail/stocktake').then((m) => m.Route));
const retailValuationRoute = retailScreen('/valuation').lazy(() => import('../areas/staff/retail/profit').then((m) => m.ValuationRoute));
const retailProfitRoute = retailScreen('/profit').lazy(() => import('../areas/staff/retail/profit').then((m) => m.Route));

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
        retailRestockRoute,
        retailUsageRoute,
        retailStockRoute,
        retailStocktakeRoute,
        retailValuationRoute,
        retailProfitRoute,
      ])]),
    memberRoute,
  ]),
});

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
