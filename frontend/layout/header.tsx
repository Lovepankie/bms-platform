import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { mockMe } from '../src/api/retail-mock';
import { router as appRouter } from '../src/app/router';
import { StaffContext } from '../src/areas/staff/context';
import { ASK_STATUS_KEY } from '../src/areas/staff/help/ask';
import { StaffBar } from '../src/areas/staff/route';
import { TourProvider } from '../src/tour/host';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page header.layout.test.ts lays out in a real browser (issue #603): the staff top bar of an owner on the sale page, with
// a long branch name, the Ask button (?ask=1: Ask BMS on, else How do I?) and its New dot, under the app's own stylesheets.
// ?font= scales the root font size as a phone's large text setting does. Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const owner = mockMe('admin');
const me = {
  ...owner,
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
  branches: (owner.branches ?? []).map((b, i) => (i === 0 ? { ...b, name: 'Nakawa Market Road Wholesale and Retail Shop' } : b)),
} as unknown as Me;
const branch = me.branches?.[0]?.id ?? null;
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(ASK_STATUS_KEY, { enabled: query.get('ask') === '1', revision: 'layout' });
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/sale'] }) });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <TourProvider me={me} saveProgress={false}>
          <div className="app-shell">
            <StaffBar me={me} branch={branch} onBranch={() => undefined} onSignOut={() => undefined} />
          </div>
        </TourProvider>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
