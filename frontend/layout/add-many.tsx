import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { ImportPage } from '../src/areas/staff/retail/catalogue-import';
import { StaffTabBar } from '../src/areas/staff/tabs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page add-many.layout.test.ts lays out in a real browser (issue #870): Import items of an owner, open on Type or paste
// (Add many items), inside the staff layout and under the app's own stylesheets, with its groups and units already read.
// ?font= scales the root font size as a phone's large text setting does. Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? null;
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
client.setQueryData(['retail', 'catalogue', 'categories'], [{ id: 'c1', name: 'Test Household Goods', active: true, product_count: 0, version: 1 }]);
client.setQueryData(['retail', 'catalogue', 'units'], [{ id: 'u1', name: 'Piece', active: true, product_count: 0, version: 1 }, { id: 'u2', name: 'Bottle', active: true, product_count: 0, version: 1 }]);
const router = layoutRouter('/staff/retail/catalogue/import');

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        {/* As the app draws a staff screen: the shell's content gutter, which the sticky Save bar reaches across. */}
        <div className="app-shell"><div className="app-content">
          <div className="nav-layout">
            <div className="nav-layout-main">
              <ImportPage />
            </div>
            <StaffTabBar me={me} />
          </div>
        </div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
