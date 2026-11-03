import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { StockRow } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { ShopHere, useBranchSwitch } from '../src/areas/staff/branch-picker';
import { StaffContext } from '../src/areas/staff/context';
import { StockTable } from '../src/areas/staff/retail/stock';
import { useToast } from '../src/areas/staff/retail/toast';
import { Gate } from '../src/areas/staff/retail/ui';
import { StaffTabBar } from '../src/areas/staff/tabs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page my-stock.layout.test.ts lays out in a real browser (issue #680): My stock on a phone as a card per item, below the shop
// label the staff layout draws above every page, and the toast a switch shows. ?font= scales the root font size as a phone's large text setting does,
// ?theme=dark draws the dark theme, ?screenToast=1 a toast of the screen's own. Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;
if (query.get('theme') === 'dark') document.documentElement.dataset.theme = 'dark';

const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const rows: StockRow[] = [
  { product_id: 'p1', code: 'EX-1', description: 'Test extension cable 4 way', category: 'Extensions', unit: 'piece', qty: '8.000', negative: false, sell_minor: 8000, cost_minor: 6000 },
  { product_id: 'p2', code: 'BL-1', description: 'Test LED bulb 9W screw, warm white', category: 'Bulbs', unit: 'piece', qty: '1127.000', negative: false, sell_minor: 4500, cost_minor: 3200, standard_pack: { unit: 'Box', factor: '10.000' } },
  { product_id: 'p3', code: 'BL-2', description: 'Test tube light 4 ft', category: 'Bulbs', unit: 'piece', qty: '-2.000', negative: true, sell_minor: 15000, cost_minor: 11000 },
];
// ?shops=1: every shop's quantity on each card (issue #807), with a long shop name, a shop with none and one below zero.
if (query.get('shops') === '1') {
  const [a, b] = me.branches ?? [];
  const names = [a?.name ?? 'Test Branch A', 'Another shop with a rather long name on the main road', 'Third Test Shop'];
  const ids = [a?.id ?? 'a', b?.id ?? 'b', 'c'];
  rows.forEach((r, i) => {
    const qtys = [r.qty ?? '0.000', i === 0 ? '0.000' : '12.000', i === 2 ? '-1.000' : '3.000'];
    r.shops = qtys.map((qty, k) => ({ branch_id: ids[k], name: names[k], qty, negative: qty.startsWith('-'), here: k === 0 }));
  });
}
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
const router = layoutRouter('/staff/retail/stock');

/** The screen's own toast, shown as the page opens, as a save just before a switch would leave it. */
function CorrectedToast() {
  const { show, toast } = useToast();
  useEffect(() => show('Stock corrected.', 'success'), [show]);
  return toast;
}

function Page() {
  const [branch, setBranch] = useState(me.branches?.[0]?.id ?? '');
  const { choose, toast } = useBranchSwitch(me, branch, (selection) => {
    setBranch(selection);
    return true;
  });
  return (
    <StaffContext.Provider value={{ me, branch, chooseBranch: choose }}>
      <div className="app-shell"><div className="app-content"><div className="nav-layout">
        <div className="nav-layout-main">
          <ShopHere me={me} branch={branch} onChoose={choose} />
          <Gate screen="stock" title="My stock">
            <StockTable rows={rows} showCost correctable={{ branchId: branch }} />
            {query.get('screenToast') === '1' && <CorrectedToast />}
          </Gate>
          {toast}
        </div>
        <StaffTabBar me={me} />
      </div></div></div>
    </StaffContext.Provider>
  );
}

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>
  </RouterContextProvider>,
);
