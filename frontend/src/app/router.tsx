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
      <main>
        <h1>BMS Platform console</h1>
        <p>The platform console is not built yet. Tenants sign in at their own address.</p>
      </main>
    );
  }
  if (area.kind === 'unknown') {
    return (
      <main>
        <h1>BMS Platform</h1>
        <p>No BMS tenant is served at this address. Check the address you were given.</p>
      </main>
    );
  }
  return (
    <main>
      <h1>BMS Platform</h1>
      <ul>
        <li>
          <Link to="/sign-in">Staff sign-in</Link>
        </li>
        <li>
          <Link to="/member">Member</Link>
        </li>
      </ul>
    </main>
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
const retailTransferRoute = retailScreen('/transfer').lazy(() => import('../areas/staff/retail/transfer').then((m) => m.Route));
const retailTransfersRoute = retailScreen('/transfers').lazy(() => import('../areas/staff/retail/transfers').then((m) => m.Route));
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
        retailTransferRoute,
        retailTransfersRoute,
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
