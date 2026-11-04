import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { StockMovement } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { UndoFormView, UndoUsageAction, UndoneMark, undoneWords } from '../src/areas/staff/retail/usage-undo';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page usage-undo.layout.test.ts lays out in a real browser (issue #818): a usage row with Undo this entry, the reason
// form open with its two buttons, and an undone row with the Undone mark and who, when and why. Nothing is fetched. Every
// name and reason is invented.

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const live: StockMovement = { id: 'm1', at: '2026-11-03T08:00:00Z', branch_id: branch, product_id: 'p1', code: 'PA-05', description: 'Test paint in a long name for the phone', unit: 'litre', kind: 'damage', qty: '-0.500', note: 'Tin fell', source_type: 'retail.usage', source_id: 'r1' };
const undone: StockMovement = { ...live, id: 'm2', source_id: 'r2', undone: { at: '2026-11-04T08:00:00Z', by: 'u1', by_name: 'Test Admin 01 with a long name', reason: 'Counted twice by mistake at the end of the day' } };
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter('/staff/retail/usage-history')}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><main>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            <li className="rt-card" data-testid="live-row">
              <p><strong>{live.description}</strong> <span className="badge badge-warning">Damaged</span></p>
              <UndoUsageAction m={live} />
            </li>
            <li className="rt-card" data-testid="form-row">
              <UndoFormView onUndo={() => undefined} onCancel={() => undefined} />
            </li>
            <li className="rt-card" data-testid="undone-row">
              <p><strong>{undone.description}</strong> <span className="badge badge-warning">Damaged</span> <UndoneMark /></p>
              <p className="hint" data-testid="undone-words">{undoneWords(undone)}. The stock went back.</p>
            </li>
          </ul>
        </main></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
