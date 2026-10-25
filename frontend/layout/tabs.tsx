import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { mockMe } from '../src/api/retail-mock';
import { router as appRouter } from '../src/app/router';
import { ActionBar } from '../src/app/ui/ActionBar';
import { StaffContext } from '../src/areas/staff/context';
import { homeSummaryKey } from '../src/areas/staff/home-cards';
import { StaffTabBar } from '../src/areas/staff/tabs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page tabs.layout.test.ts lays out in a real browser (issue #544): the staff tabs of an owner on the sale page, a badge on My
// stock, a page long enough to scroll and the sticky action bar, inside the app shell and under the app's own stylesheets. ?font= scales the root font
// size as a phone's large text setting does, and ?low= sets the count on the badge. Every name and figure is invented.

const query = new URLSearchParams(window.location.search);
const font = Number(query.get('font') ?? '1');
document.documentElement.style.fontSize = `${16 * font}px`;

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? null;
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(homeSummaryKey(branch ?? undefined), { low_stock: Number(query.get('low') ?? '12') });
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/sale'] }) });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell">
        <main className="app-content tab-page">
          <h1>Sale</h1>
          {Array.from({ length: 30 }, (_, i) => (
            <p key={i} className="layout-line">
              Line {i + 1}
            </p>
          ))}
          <ActionBar>
            <button type="button" className="btn-primary">
              Record sale
            </button>
          </ActionBar>
        </main>
        </div>
        <StaffTabBar me={me} />
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
