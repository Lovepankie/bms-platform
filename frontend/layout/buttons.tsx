import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import { businessToday, type ExpenseCategory, type Product, type SavingsSuggestion, type StockRow } from '../src/api/retail';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { ExpenseForm } from '../src/areas/staff/retail/expenses';
import { ProductList } from '../src/areas/staff/retail/catalogue-products';
import { LabelsPage } from '../src/areas/staff/retail/labels';
import { RestockForm, type RestockDraft } from '../src/areas/staff/retail/restock';
import { SaleForm } from '../src/areas/staff/retail/sale';
import { SavingsForm } from '../src/areas/staff/retail/savings';
import { ShopSetupPage } from '../src/areas/staff/retail/shop-setup';
import { StockTable } from '../src/areas/staff/retail/stock';
import { Gate } from '../src/areas/staff/retail/ui';
import { StaffBar } from '../src/areas/staff/route';
import { SignOutEverywhere } from '../src/areas/staff/top-bar';
import { PeriodPicker } from '../src/app/ui/PeriodPicker';
import { MoneyPage } from '../src/areas/staff/tab-pages';
import { Sheet } from '../src/app/ui/Sheet';
import { TourProvider } from '../src/tour/host';
import { StaffTabBar } from '../src/areas/staff/tabs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page buttons.layout.test.ts lays out in a real browser (issue #825): one daily screen of an owner, chosen by ?screen= (sell,
// stock, buy, money, expenses, savings, setup, items, labels for a bar of two buttons, period and signout for two real sheets of
// two buttons, sheet for a Sheet of the kit), under the staff top bar, the staff layout with the bottom tabs, under the app's own stylesheets, so
// the test can measure every button on a phone and on a laptop. The lists are in the query cache, so nothing is fetched. Every
// name and figure is invented.

const query = new URLSearchParams(window.location.search);
const screen = query.get('screen') ?? 'sell';
const me = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';
const date = businessToday();
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
const categories: ExpenseCategory[] = [
  { id: 'c1', name: 'Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 }] },
];
const suggestion: SavingsSuggestion = { branch_id: branch, business_date: date, total_sold_minor: 1250000, suggestion_token: 'tok-1', suggested_minor: 240000 };
const rows: StockRow[] = [
  { product_id: 'p1', code: 'EX-1', description: 'Test extension cable 4 way', category: 'Extensions', unit: 'piece', qty: '8.000', negative: false, sell_minor: 0, cost_minor: 0 },
  { product_id: 'p2', code: 'BL-1', description: 'Test LED bulb 9W screw', category: 'Bulbs', unit: 'piece', qty: '40.000', negative: false, sell_minor: 0, cost_minor: 0 },
];
// Buy stock opens with one line, so Clear this purchase shows under the bar (#825). Its amounts are left empty.
const item: Product = { id: 'p1', code: 'EX-1', description: 'Test extension cable 4 way', unit: 'piece', cost_minor: 0, sell_minor: 0, active: true };
const purchase: RestockDraft = { supplierId: '', purchasedOn: date, method: 'cash', lines: [{ product: item, cost: '', sell: '', qtyByBranch: { [branch]: '5' } }] };
client.setQueryData(['retail', 'expense-categories', true], categories);
client.setQueryData(['retail', 'cash-parties'], []);
client.setQueryData(['retail', 'savings-suggestion', branch, date], suggestion);
client.setQueryData(['retail', 'suppliers'], [{ id: 's1', name: 'Test Supplies', active: true, version: 1 }]);
client.setQueryData(['retail', 'catalogue', 'categories'], []);
client.setQueryData(['retail', 'catalogue', 'units'], [{ id: 'u1', name: 'Piece', active: true, product_count: 0, version: 1 }]);
// The Items list, read for the whole business and for the branch in use alike.
const listed = { pages: [{ items: [item, { ...item, id: 'p2', code: 'BL-1', description: 'Test LED bulb 9W screw' }] }], pageParams: [''] };
for (const at of ['', branch]) client.setQueryData(['retail', 'catalogue', 'products', '', '', 'active', at], listed);

const SCREENS: Record<string, { path: string; body: ReactNode }> = {
  sell: { path: '/staff/retail/sale', body: <Gate screen="sale" title="Record a sale"><SaleForm branchId={branch} /></Gate> },
  stock: { path: '/staff/retail/stock', body: <Gate screen="stock" title="My stock"><StockTable rows={rows} showCost correctable={{ branchId: branch }} /></Gate> },
  buy: { path: '/staff/retail/restock', body: <Gate screen="restock" title="Buy stock"><RestockForm draftName="layout-buy" initial={purchase} /></Gate> },
  money: { path: '/staff/money', body: <MoneyPage /> },
  expenses: { path: '/staff/retail/expenses', body: <Gate screen="expenses" title="Expenses"><ExpenseForm branchId={branch} /></Gate> },
  savings: { path: '/staff/retail/savings', body: <Gate screen="savings" title="Savings"><SavingsForm branchId={branch} /></Gate> },
  setup: { path: '/staff/retail/catalogue/set-up', body: <ShopSetupPage /> },
  // The catalogue Items list: on a phone the ways to add sizes and colours follow its last row (#825).
  items: {
    path: '/staff/retail/catalogue/products',
    body: <Gate screen="products" title="Items"><ProductList onEdit={() => undefined} onPrice={() => undefined} onAdd={() => undefined} /></Gate>,
  },
  // Not a daily screen: the one ActionBar with two buttons, Print and Make the labels.
  labels: { path: '/staff/retail/catalogue/labels', body: <LabelsPage /> },
  // Two real sheets of more than one button: the PeriodPicker's days (the test opens it from Custom) and Sign out everywhere.
  period: {
    path: '/staff/retail/sales',
    body: <PeriodPicker from="" to={date} openStart onChange={() => undefined} />,
  },
  signout: {
    path: '/staff',
    body: <SignOutEverywhere open waiting={0} busy={false} onConfirm={() => undefined} onClose={() => undefined} />,
  },
  // A confirmation in the kit's Sheet, its primary button last as Sheet asks.
  sheet: {
    path: '/staff/retail/stock',
    body: (
      <Sheet open onClose={() => undefined} title="Remove this item?" actions={<><button type="button">Keep it</button><button type="button" className="btn-danger">Remove</button></>}>
        <p>Test LED bulb 9W screw leaves this sale.</p>
      </Sheet>
    ),
  },
};
const shown = SCREENS[screen] ?? (SCREENS.sell as { path: string; body: ReactNode });

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={layoutRouter(shown.path)}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <TourProvider me={me} saveProgress={false}>
        <div className="app-shell">
          <StaffBar me={me} branch={branch} onBranch={() => undefined} onSignOut={() => undefined} />
          <div className="app-content"><div className="nav-layout">
          <div className="nav-layout-main">
            <main data-screen={screen}>{shown.body}</main>
          </div>
          <StaffTabBar me={me} />
          </div></div>
        </div>
        </TourProvider>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
