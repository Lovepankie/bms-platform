import type { QueryClient } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import type { Me } from '../api/client';
import type { StockHealth } from '../api/retail-analytics';
import { defaultRange } from '../areas/staff/retail/analytics-ui';
import { mockMe } from '../api/retail-mock';
import { CreditControlPage } from '../areas/staff/retail/credit-control';
import { EvaluationPage } from '../areas/staff/retail/evaluation';
import { RetailHome } from '../areas/staff/retail/home';
import { MarginsPage } from '../areas/staff/retail/margins';
import { ProfitPage, ValuationPage } from '../areas/staff/retail/profit';
import { RestockPage } from '../areas/staff/retail/restock';
import { SalesAnalysisPage } from '../areas/staff/retail/sales-analysis';
import { StockHealthPage } from '../areas/staff/retail/stock-health';
import { MoveStock } from '../areas/staff/retail/transfer';
import { StockMoves } from '../areas/staff/retail/transfers';
import { UsagePage } from '../areas/staff/retail/usage';
import { RETAIL_REPORT_TOURS } from './tours-retail-reports';
import { describeTours } from './test-kit';

// Every page tour of the retail reports slice (issue #337): the screen each step points at is rendered as the user the tour
// is for, and the step's data-tour anchor must be in its markup.

const ADMIN = mockMe('admin');
const SELLER = mockMe('sales');

function screen(path: string): ReactElement {
  switch (path) {
    case '/staff/retail': return <RetailHome />;
    case '/staff/retail/restock': return <RestockPage />;
    case '/staff/retail/usage': return <UsagePage />;
    case '/staff/retail/transfer': return <MoveStock />;
    case '/staff/retail/transfers': return <StockMoves />;
    case '/staff/retail/valuation': return <ValuationPage />;
    case '/staff/retail/profit': return <ProfitPage />;
    case '/staff/retail/sales-analysis': return <SalesAnalysisPage />;
    case '/staff/retail/margins': return <MarginsPage />;
    case '/staff/retail/stock-health': return <StockHealthPage />;
    case '/staff/retail/credit-control': return <CreditControlPage />;
    case '/staff/retail/evaluation': return <EvaluationPage />;
    default: throw new Error(`no screen for ${path}`);
  }
}

/** Stock health with its report loaded, so the sections drawn from the data (slow movers, issue #414) are on the page. */
function seed(client: QueryClient, me: Me): void {
  const { from, to } = defaultRange();
  const health: StockHealth = {
    from, to, currency: 'UGX', top: 10, velocity_days: 30, lead_days: 14, cover_days: 30, profit_visible: false,
    cover: { items: [], total: 0 }, reorder: { items: [], total: 0 },
    dead_stock: { days: 90, branches: [], items: [], items_total: 0, value_at_price_minor: 0 },
    slow_movers: { days: 90, slow_months: 4, min_sold: 0, items: [], total: 0 },
    shrinkage: [],
  };
  client.setQueryData(['retail', 'stock-health', me.branches?.[0]?.id ?? null, from, to, 10, 14, 30], health);
}

describeTours('retail reports', RETAIL_REPORT_TOURS, {
  'page-retail-home': [ADMIN, SELLER],
  'page-restock': [ADMIN],
  'page-usage': [ADMIN],
  'page-transfer': [ADMIN],
  'page-transfers': [ADMIN],
  'page-valuation': [ADMIN],
  'page-profit': [ADMIN],
  'page-sales-analysis': [ADMIN],
  'page-margins': [ADMIN],
  'page-stock-health': [ADMIN],
  'page-credit-control': [ADMIN],
  'page-evaluation': [ADMIN],
}, screen, seed);
