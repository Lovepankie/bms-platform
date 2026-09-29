import { Link, Outlet, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';

// Route-level split (ADR-009): /staff and /member are separate lazily loaded route trees, so a
// member never downloads staff code. The platform console (/platform on app.<base domain>)
// joins as a third tree when it is built.

function RootLayout() {
  return (
    <div style={{ fontFamily: 'system-ui, sans-serif', maxWidth: 960, margin: '0 auto', padding: 16 }}>
      <Outlet />
    </div>
  );
}

function AreaChooser() {
  return (
    <main>
      <h1>BMS Platform</h1>
      <p>Sign in is not built yet. Choose an area:</p>
      <ul>
        <li>
          <Link to="/staff">Staff</Link>
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

const staffRoute = createRoute({ getParentRoute: () => rootRoute, path: '/staff' }).lazy(() =>
  import('../areas/staff/route').then((m) => m.Route),
);

const memberRoute = createRoute({ getParentRoute: () => rootRoute, path: '/member' }).lazy(() =>
  import('../areas/member/route').then((m) => m.Route),
);

export const router = createRouter({ routeTree: rootRoute.addChildren([indexRoute, staffRoute, memberRoute]) });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
