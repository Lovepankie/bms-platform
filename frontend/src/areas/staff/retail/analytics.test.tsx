import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { CreditControl, Dashboard, Evaluation, Margins, SalesAnalysis, StockHealth } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { Bars, Sparkline, percentOrNone, rangeProblem } from './analytics-ui';
import { showPercent } from './maths';
import { CreditControlView } from './credit-control';
import { DashboardView } from './dashboard';
import { EvaluationView } from './evaluation';
import { canUse } from './permissions';
import { MarginsView } from './margins';
import { SalesAnalysisView } from './sales-analysis';
import { StockHealthView } from './stock-health';
import { Gate } from './ui';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

// Static markup tests, as screens.test.tsx: what each analytics screen shows and, above all, what a
// caller without retail.profit.read never sees.

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const page = (role: 'sales' | 'admin', node: ReactElement) =>
  renderToString(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me: mockMe(role), branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );

const withProfit: SalesAnalysis = {
  from: '2026-09-08', to: '2026-10-07', currency: 'UGX', group: 'day', top: 10, profit_visible: true,
  totals: { sales_minor: 5400, sale_count: 3, qty: '6.000', gross_profit_minor: 1800, margin_bp: 3333 },
  series: [{ period_start: '2026-10-06', sales_minor: 3000, sale_count: 1, gross_profit_minor: 1000, margin_bp: 3333 }],
  by_branch: [{ id: 'b1', label: 'Head office', qty: '5.000', sales_minor: 3900, sale_count: 2, gross_profit_minor: 1300, margin_bp: 3333 }],
  by_category: [{ id: 'c1', label: 'Lighting', qty: '3.000', sales_minor: 4500, sale_count: 2, gross_profit_minor: 1500, margin_bp: 3333 }],
  by_product: [
    { id: 'p1', code: 'ANA-1', label: 'Test Product ANA-1', unit: 'piece', qty: '3.000', sales_minor: 4500, sale_count: 2, gross_profit_minor: 1500, margin_bp: 3333 },
    { id: 'p2', code: 'ANA-2', label: 'Test Product ANA-2', unit: 'piece', qty: '3.000', sales_minor: 900, sale_count: 1, gross_profit_minor: 300, margin_bp: 3333 },
  ],
  top_by_quantity: [],
  by_seller: [{ id: 'u1', qty: '6.000', sales_minor: 5400, sale_count: 3, gross_profit_minor: 1800, margin_bp: 3333 }],
  slow_days: 30,
  slow_movers: [{ product_id: 'p3', code: 'ANA-3', description: 'Test Product ANA-3', unit: 'piece', qty_on_hand: '4.000', stock_at_price_minor: 2800 }],
  slow_movers_total: 1,
  no_sales: [],
  no_sales_total: 0,
};

/** The same body as the server sends a caller without retail.profit.read: no cost, profit or margin key. */
function stripped(): SalesAnalysis {
  const text = JSON.stringify({ ...withProfit, profit_visible: false }, (key, value) =>
    /profit_minor|margin_bp|cost/.test(key) ? undefined : value);
  return JSON.parse(text) as SalesAnalysis;
}

describe('sales analysis', () => {
  it('shows profit and margin columns when the server sent them', () => {
    const html = renderToString(<SalesAnalysisView data={withProfit} />);
    expect(html).toContain('UGX 5,400');
    expect(html).toContain('Margin (profit over sales)');
    expect(html).toContain('33.33%');
    expect(html).toContain('UGX 1,800');
    expect(html).toContain('Test Product ANA-1');
    expect(html).toContain('Slow movers: no sale in 30 days');
    expect(html).toContain('Unknown');
  });

  it('leaves out the stocked lists when the server sent none (no stock read)', () => {
    const noStock = { ...withProfit, slow_movers: undefined, slow_movers_total: undefined, no_sales: undefined, no_sales_total: undefined };
    const html = renderToString(<SalesAnalysisView data={noStock} />);
    expect(html).not.toContain('Slow movers');
    expect(html).not.toContain('No sales at all in this range');
  });

  it('shows nothing of cost, profit or margin without them', () => {
    const html = renderToString(<SalesAnalysisView data={stripped()} />);
    expect(html).toContain('UGX 5,400');
    expect(html).not.toMatch(/profit|margin|cost/i);
    expect(html).not.toContain('1,800');
  });

  it('is open to a seller and closed to a user without sale read', () => {
    expect(canUse(mockMe('sales'), 'salesAnalysis')).toBe(true);
    expect(canUse({ permissions: ['retail.stock.read'] }, 'salesAnalysis')).toBe(false);
    const closed = renderToString(
      <StaffContext.Provider value={{ me: { ...mockMe('sales'), permissions: ['retail.stock.read'] }, branch }}>
        <Gate screen="salesAnalysis" title="Sales analysis"><p>SECRET-CONTENT</p></Gate>
      </StaffContext.Provider>,
    );
    expect(closed).toContain('You do not have access');
    expect(closed).not.toContain('SECRET-CONTENT');
    expect(page('sales', <Gate screen="salesAnalysis" title="Sales analysis"><p>CONTENT</p></Gate>)).toContain('CONTENT');
  });
});

describe('analytics pieces', () => {
  it('limits a range to 366 days, both ends counted', () => {
    expect(rangeProblem('2026-01-01', '2026-01-01')).toBeNull();
    expect(rangeProblem('2025-10-07', '2026-10-07')).toBeNull();
    expect(rangeProblem('2025-10-06', '2026-10-07')).toMatch(/366/);
    expect(rangeProblem('2026-02-01', '2026-01-01')).toMatch(/must not be after/);
    expect(rangeProblem('', '2026-01-01')).toMatch(/both dates/);
  });

  it('draws bars scaled to the largest, with the figures in text and the drawing hidden from readers', () => {
    const html = renderToString(<Bars label="Top" rows={[{ key: 'a', name: 'A', value: 200, text: 'UGX 200' }, { key: 'b', name: 'B', value: 50, text: 'UGX 50' }]} />);
    expect(html).toContain('UGX 200');
    expect(html).toContain('width="100"');
    expect(html).toContain('width="25"');
    expect(html).toContain('aria-hidden="true"');
    expect(html).not.toContain('style=');
  });

  it('draws a sparkline as a labelled polyline without inline style', () => {
    const html = renderToString(<Sparkline points={[0, 5, 10]} label="Sales, last 3 days" />);
    expect(html).toContain('aria-label="Sales, last 3 days"');
    expect(html).toContain('points="0.00,22.00 50.00,12.00 100.00,2.00"');
    expect(html).not.toContain('style=');
  });
});

describe('margins', () => {
  const margins: Margins = {
    from: '2026-09-08', to: '2026-10-07', currency: 'UGX', top: 10, target_bp: 2000,
    totals: { sales_minor: 6400, cost_minor: 4500, profit_minor: 1900, margin_bp: 2969 },
    by_product: [{ id: 'p1', code: 'ANA-1', label: 'Test Product ANA-1', unit: 'piece', qty: '3.000', sales_minor: 4500, cost_minor: 3000, profit_minor: 1500, margin_bp: 3333 }],
    by_category: [{ id: 'c1', label: 'Lighting', qty: '3.000', sales_minor: 4500, cost_minor: 3000, profit_minor: 1500, margin_bp: 3333 }],
    below_target: { total: 2, items: [{ id: 'p5', code: 'ANA-5', label: 'Test Product ANA-5', unit: 'piece', qty: '1.000', sales_minor: 1000, cost_minor: 900, profit_minor: 100, margin_bp: 1000 }] },
    price_changes: {
      total: 1,
      items: [{
        product_id: 'p1', code: 'ANA-1', description: 'Test Product ANA-1', unit: 'piece', changed_on: '2026-10-07', old_sell_minor: 1500, new_sell_minor: 2000,
        days_before: 29, days_after: 1, units_before: '2.000', units_after: '2.000', sales_before_minor: 3000, sales_after_minor: 3500, margin_before_bp: 3333, margin_after_bp: 2000,
      }],
    },
  };

  it('shows margins, the target, the items under it and the price change impact', () => {
    const html = renderToString(<MarginsView data={margins} />);
    expect(html).toContain('Sold below 20% margin');
    expect(html).toContain('29.69%');
    expect(html).toContain('Test Product ANA-5');
    expect(html).toContain('10%');
    expect(html).toContain('UGX 1,500 to UGX 2,000 on 2026-10-07');
    expect(html).toContain('33.33%');
    expect(html).toContain('Showing 1 of 2');
  });

  it('treats a null margin like an absent one: no 0% where there is no base', () => {
    const nulled = JSON.parse(JSON.stringify(margins)) as Margins;
    const item = nulled.below_target?.items?.[0] as unknown as Record<string, unknown>;
    item.margin_bp = null;
    const change = nulled.price_changes?.items?.[0] as unknown as Record<string, unknown>;
    change.margin_before_bp = null;
    change.margin_after_bp = null;
    const html = renderToString(<MarginsView data={nulled} />);
    expect(html).not.toMatch(/>0%</);
    expect(html).toContain('<span class="rt-flag">n/a</span>');
    expect(percentOrNone(null)).toBe('');
    expect(showPercent(null as unknown as undefined)).toBe('n/a');
  });

  it('is closed without retail.profit.read', () => {
    expect(canUse(mockMe('sales'), 'margins')).toBe(false);
    expect(canUse(mockMe('admin'), 'margins')).toBe(true);
    const html = page('sales', <Gate screen="margins" title="Margins"><p>SECRET-CONTENT</p></Gate>);
    expect(html).toContain('You do not have access');
    expect(html).not.toContain('SECRET-CONTENT');
  });
});

describe('stock health', () => {
  const health: StockHealth = {
    from: '2026-09-08', to: '2026-10-07', currency: 'UGX', top: 10, velocity_days: 30, lead_days: 14, cover_days: 30, profit_visible: true,
    cover: { total: 1, items: [{ branch_id: 'b1', product_id: 'p5', code: 'ANA-5', description: 'Test Product ANA-5', unit: 'piece', qty_on_hand: '1.000', sold_last30: '5.000', avg_daily: '0.167', days_of_cover: '6.0', capped: false }] },
    reorder: { total: 1, items: [{ branch_id: 'b1', product_id: 'p5', code: 'ANA-5', description: 'Test Product ANA-5', unit: 'piece', qty_on_hand: '1.000', sold_last30: '5.000', avg_daily: '0.167', days_of_cover: '6.0', capped: false, suggested_qty: '4.000' }] },
    dead_stock: {
      days: 90, items_total: 1, value_at_price_minor: 2100, value_at_cost_minor: 1200,
      branches: [{ branch_id: 'b1', item_count: 1, value_at_price_minor: 2100, value_at_cost_minor: 1200 }],
      items: [{ branch_id: 'b1', product_id: 'p3', code: 'ANA-3', description: 'Test Product ANA-3', unit: 'piece', qty_on_hand: '3.000', value_at_price_minor: 2100, value_at_cost_minor: 1200 }],
    },
    shrinkage: [{ branch_id: 'b1', used_reports: 1, damaged_reports: 1, short_lines: 1, over_lines: 0, used_cost_minor: 1000, damaged_cost_minor: 400, stocktake_loss_minor: 400, stocktake_gain_minor: 0, net_cost_minor: 1800 }],
  };
  const nameOf = (id?: string) => (id === 'b1' ? 'Head office' : 'Branch');

  /** The body of a caller without retail.profit.read: no key that holds a cost. */
  function plain(): StockHealth {
    return JSON.parse(JSON.stringify({ ...health, profit_visible: false }, (key, value) => (/cost|loss|gain/.test(key) ? undefined : value))) as StockHealth;
  }

  it('shows reorder, cover, dead stock and shrinkage with cost where sent', () => {
    const html = renderToString(<StockHealthView data={health} nameOf={nameOf} />);
    expect(html).toContain('Reorder: under 14 days of cover');
    expect(html).toContain('6.0 days');
    expect(html).toContain('4 piece');
    expect(html).toContain('Dead stock: no sale in 90 days');
    expect(html).toContain('UGX 1,200');
    expect(html).toContain('UGX 1,800');
    expect(html).toContain('Head office');
  });

  it('shows no cost column and no cost figure without them', () => {
    const html = renderToString(<StockHealthView data={plain()} nameOf={nameOf} />);
    expect(html).toContain('UGX 2,100');
    expect(html).not.toMatch(/cost/i);
    expect(html).not.toContain('1,200');
    expect(html).not.toContain('1,800');
  });

  it('is open to stock readers and says over 365 days when capped', () => {
    expect(canUse(mockMe('sales'), 'stockHealth')).toBe(true);
    expect(canUse({ permissions: ['retail.sale.read'] }, 'stockHealth')).toBe(false);
    const capped: StockHealth = { ...health, cover: { total: 1, items: [{ ...(health.cover?.items?.[0] ?? {}), days_of_cover: '365.0', capped: true }] } };
    expect(renderToString(<StockHealthView data={capped} nameOf={nameOf} />)).toContain('over 365.0 days');
  });
});

describe('credit control', () => {
  const ageing = { owed_minor: 11000, not_due_minor: 3000, days1_to30_minor: 4500, days31_to60_minor: 1500, days61_to90_minor: 0, over90_minor: 2000 };
  const credit: CreditControl = {
    from: '2026-09-08', to: '2026-10-07', as_of: '2026-10-07', currency: 'UGX', top: 20, totals: ageing, buyer_count: 2, sort: 'amount',
    buyers: [
      { name: 'Test Buyer 01', sale_count: 3, ageing: { ...ageing, owed_minor: 7500, over90_minor: 0, days31_to60_minor: 0 }, oldest_overdue_days: 5 },
      { customer_id: 'c1', name: 'Test Customer Zed', sale_count: 2, ageing: { ...ageing, owed_minor: 3500, not_due_minor: 0, days1_to30_minor: 0 }, oldest_overdue_days: 95 },
    ],
    overdue_count: 3,
    overdue: [{ sale_id: 's1', sale_no: 'S-0001', branch_id: 'b1', buyer_name: 'Test Buyer 01', sale_date: '2026-09-27', due_date: '2026-10-02', days_overdue: 5, total_minor: 4500, outstanding_minor: 4500 }],
    payments: [{ date: '2026-10-07', method: 'cash', count: 1, amount_minor: 1000 }, { date: '2026-10-07', method: 'mobile_money', count: 1, amount_minor: 1500 }],
    payments_by_method: [{ method: 'cash', count: 1, amount_minor: 1000 }, { method: 'mobile_money', count: 1, amount_minor: 1500 }],
    payments_minor: 2500,
  };
  const nameOf = (id?: string) => (id === 'b1' ? 'Head office' : 'Branch');

  it('shows what is owed with the ageing, the overdue list and the payments by method', () => {
    const html = renderToString(<CreditControlView data={credit} nameOf={nameOf} sort="amount" />);
    expect(html).toContain('Owed by credit buyers UGX 11,000');
    expect(html).toContain('Over 90 days late');
    expect(html).toContain('Test Customer Zed');
    expect(html).toContain('S-0001, Head office');
    expect(html).toContain('Showing 1 of 3');
    expect(html).toContain('Mobile money');
    expect(html).toContain('Received UGX 2,500');
  });

  it('says plainly when nobody owes and nothing was paid, and never shows cost or profit', () => {
    const none: CreditControl = { ...credit, totals: { owed_minor: 0 }, buyer_count: 0, buyers: [], overdue: [], overdue_count: 0, payments: [], payments_by_method: [], payments_minor: 0 };
    const html = renderToString(<CreditControlView data={none} nameOf={nameOf} sort="age" />);
    expect(html).toContain('Nobody owes anything.');
    expect(html).toContain('Nothing is overdue.');
    expect(html).toContain('No payment was received in this range.');
    expect(html).not.toMatch(/cost|profit|margin/i);
    expect(canUse(mockMe('sales'), 'creditControl')).toBe(true);
    expect(canUse({ permissions: ['retail.stock.read'] }, 'creditControl')).toBe(false);
  });
});

describe('business evaluation', () => {
  const stockOf = { at_cost_minor: 23000, at_price_minor: 34900, expected_profit_minor: 11900, over_cost_bp: 5174 };
  const run = { sales_minor: 3900, profit_minor: 1300, avg_daily_sales_minor: 130, avg_daily_profit_minor: 43, days_of_stock: '268.5', days_of_stock_capped: false };
  const data: Evaluation = {
    from: '2026-09-08', to: '2026-10-07', currency: 'UGX', top: 10, run_rate_days: 30,
    note: 'An estimate from today\'s prices and the last 30 days of sales. It is not a forecast or a promise.',
    total: { period: { sales_minor: 5400, profit_minor: 1800, margin_bp: 3333 }, stock: { ...stockOf, at_price_minor: 48400 }, run_rate: run },
    branches: [{
      branch_id: 'b1', period: { sales_minor: 3900, profit_minor: 1300 }, stock: stockOf, run_rate: run,
      categories: [{
        category_id: 'c1', category: 'Lighting', period: { sales_minor: 3000, profit_minor: 1000 }, stock: stockOf, products_total: 2,
        products: [{ product_id: 'p1', code: 'ANA-1', description: 'Test Product ANA-1', unit: 'piece', qty_on_hand: '18.000', period: { sales_minor: 3000, profit_minor: 1000 }, stock: { at_cost_minor: 18000, at_price_minor: 27000, expected_profit_minor: 9000, over_cost_bp: 5000 } }],
      }],
    }],
  };
  const nameOf = (id?: string) => (id === 'b1' ? 'Head office' : 'Branch');

  it('says it is an estimate and shows profit to date, expected profit and the pace', () => {
    const html = renderToString(<EvaluationView data={data} nameOf={nameOf} />);
    expect(html).toContain('It is not a forecast or a promise.');
    expect(html).toContain('Head office');
    expect(html).toContain('UGX 11,900');
    expect(html).toContain('51.74%');
    expect(html).toContain('268.5 days');
    expect(html).toContain('Lighting');
    expect(html).toContain('Test Product ANA-1');
    expect(html).toContain('Showing 1 of 2 items');
  });

  it('is for retail.profit.read only', () => {
    expect(canUse(mockMe('sales'), 'evaluation')).toBe(false);
    expect(canUse({ permissions: ['retail.sale.read', 'retail.stock.read'] }, 'evaluation')).toBe(false);
    expect(canUse({ permissions: ['retail.profit.read'] }, 'evaluation')).toBe(true);
    const html = page('sales', <Gate screen="evaluation" title="Business evaluation"><p>SECRET-CONTENT</p></Gate>);
    expect(html).toContain('You do not have access');
    expect(html).not.toContain('SECRET-CONTENT');
  });
});

describe('owner dashboard', () => {
  const days = Array.from({ length: 30 }, (_, i) => ({ date: `2026-09-${String(8 + i).padStart(2, '0')}`, sales_minor: i * 100, gross_profit_minor: i * 30 }));
  const full: Dashboard = {
    date: '2026-10-07', currency: 'UGX', profit_visible: true,
    today: { sales_minor: 3600, sale_count: 3, cash_minor: 2100, credit_minor: 1500, gross_profit_minor: 1200 },
    days, last7_sales_minor: 6600, last30_sales_minor: 7500, last7_gross_profit_minor: 2200, last30_gross_profit_minor: 2500,
    stock: { value_at_price_minor: 47050, value_at_cost_minor: 31100, out_of_stock: 1, low_stock: 2, low_stock_threshold: '5.000' },
    shops: [{ branch_id: 'b1', today_sales_minor: 2100, last7_sales_minor: 5100, last30_sales_minor: 6000, today_gross_profit_minor: 700, stock_at_price_minor: 33550, stock_at_cost_minor: 22100, out_of_stock: 0, low_stock: 2 }],
  };
  const nameOf = (id?: string) => (id === 'b1' ? 'Head office' : 'Branch');

  /** What a seller with sale and stock read gets: no key holding cost or profit. */
  function seller(): Dashboard {
    return JSON.parse(JSON.stringify({ ...full, profit_visible: false }, (key, value) => (/cost|profit/.test(key) && key !== 'profit_visible' ? undefined : value))) as Dashboard;
  }

  it('shows today, cash against credit, the sparklines, stock, shops and profit for a profit reader', () => {
    const html = renderToString(<DashboardView data={full} nameOf={nameOf} />);
    expect(html).toContain('Today, 2026-10-07');
    expect(html).toContain('UGX 3,600');
    expect(html).toContain('On credit');
    expect(html).toContain('Profit today');
    expect(html).toContain('UGX 1,200');
    expect(html).toContain('Stock at cost');
    expect(html).toContain('Head office');
    expect(html).toContain('Last 7 days');
    expect(html).toContain('Last 30 days');
    expect(html.match(/<svg/g)?.length).toBe(2);
    expect(html).not.toContain('style=');
  });

  it('leaves clearly marked placeholders for banked against expected and savings, with no number', () => {
    const html = renderToString(<DashboardView data={full} nameOf={nameOf} />);
    expect(html).toContain('Banked against expected');
    expect(html).toContain('Savings');
    expect(html.match(/data-placeholder="cash-book"/g)?.length).toBe(2);
    expect(html).toContain('It arrives with the cash book.');
  });

  it('shows the sales and stock parts and nothing of cost or profit to a seller', () => {
    const html = renderToString(<DashboardView data={seller()} nameOf={nameOf} />);
    expect(html).toContain('UGX 3,600');
    expect(html).toContain('Stock at selling price');
    expect(html).not.toMatch(/profit|cost/i);
    expect(html).not.toContain('1,200');
    expect(html).not.toContain('31,100');
  });

  it('shows no invented zeros for a shop outside the sales scope', () => {
    const html = renderToString(<DashboardView data={{ ...seller(), shops: [{ branch_id: 'b1', stock_at_price_minor: 33550, out_of_stock: 0, low_stock: 2 }] }} nameOf={nameOf} />);
    expect(html).toContain('n/a');
    expect(html).not.toContain('UGX 0');
  });

  it('shows only the sales parts when stock is not readable', () => {
    const html = renderToString(<DashboardView data={{ ...seller(), stock: undefined, shops: [{ branch_id: 'b1', today_sales_minor: 2100, last7_sales_minor: 5100, last30_sales_minor: 6000 }] }} nameOf={nameOf} />);
    expect(html).toContain('Sales today');
    expect(html).not.toContain('Stock at selling price');
    expect(html).not.toContain('Out / low');
  });
});
