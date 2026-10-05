import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { DailyProfit, Product, Sale, Stocktake, StockRow, Valuation } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { Receipt, SaleForm } from './sale';
import { StockTable } from './stock';
import { StocktakeReview } from './stocktake';
import { ProfitTable, ValuationTable } from './profit';
import { RestockForm } from './restock';
import { UsageForm } from './usage';
import { Gate } from './ui';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

// Component tests render to static markup (the test setup has no DOM): they prove what each screen
// shows and, above all, what a user without retail.profit.read never sees.

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const page = (role: 'sales' | 'admin', node: ReactElement, client = new QueryClient()) =>
  renderToString(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: mockMe(role), branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );

const products: Product[] = [
  { id: 'p1', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', sell_minor: 6000, active: true, qty: '12.000' },
  { id: 'p2', code: 'P004', description: 'LED bulb 15W screw', unit: 'piece', sell_minor: 9500, active: true, qty: '-2.000', negative: true },
];

describe('gating', () => {
  it('shows a sales user nothing of the profit and restock screens', () => {
    for (const screen of ['profit', 'restock', 'stocktake'] as const) {
      const html = page('sales', <Gate screen={screen} title="Secret"><p>SECRET-CONTENT</p></Gate>);
      expect(html).toContain('You do not have access');
      expect(html).not.toContain('SECRET-CONTENT');
    }
  });

  it('shows an admin the content', () => {
    expect(page('admin', <Gate screen="profit" title="Daily profit"><p>CONTENT</p></Gate>)).toContain('CONTENT');
  });
});

describe('sale screen', () => {
  const client = new QueryClient();
  client.setQueryData(['retail', 'products', branch, ''], products);

  it('lists items with the branch balance and a negative flag, labelled inputs', () => {
    const html = page('sales', <SaleForm branchId={branch} />, client);
    expect(html).toContain('LED bulb 9W screw');
    expect(html).toContain('in stock here');
    expect(html).toContain('(negative)');
    expect(html).toContain('for="sale-search"');
    expect(html).toContain('Total UGX 0');
    expect(html).not.toMatch(/cost|profit/i);
  });

  const sale: Sale = {
    id: 's1', branch_id: branch, sale_date: '2026-10-05', payment_method: 'credit', buyer_name: 'Test Buyer 01', due_date: '2026-11-01',
    lines: [{ product_id: 'p1', description: 'LED bulb 9W screw', qty: '2.000', unit_price_minor: 6000, line_total_minor: 12000, unit_cost_minor: 3500 }],
    total_minor: 12000, paid_minor: 0, balance_minor: 12000, profit_minor: 5000,
  };

  it('shows the receipt with what is still owed, and profit only with the permission', () => {
    const sales = page('sales', <Receipt sale={sale} />);
    expect(sales).toContain('UGX 12,000');
    expect(sales).toContain('Still to pay');
    expect(sales).not.toContain('Profit on this sale');
    expect(page('admin', <Receipt sale={sale} />)).toContain('Profit on this sale: UGX 5,000');
  });
});

describe('stock screen', () => {
  const rows: StockRow[] = [
    { product_id: 'p1', description: 'LED bulb 9W screw', unit: 'piece', qty: '12.000', negative: false, sell_minor: 6000, cost_minor: 3500 },
    { product_id: 'p2', description: 'LED bulb 15W screw', unit: 'piece', qty: '-2.000', negative: true, sell_minor: 9500, cost_minor: 6000 },
  ];

  it('flags negative stock in words and hides the cost column without the permission', () => {
    const sales = renderToString(<StockTable rows={rows} showCost={false} />);
    expect(sales).toContain('Negative');
    expect(sales).not.toContain('Cost');
    expect(sales).not.toContain('UGX 3,500');
    const admin = renderToString(<StockTable rows={rows} showCost />);
    expect(admin).toContain('UGX 3,500');
  });
});

describe('restock screen', () => {
  it('says that saving updates the product prices', () => {
    const html = page('admin', <RestockForm />);
    expect(html).toMatch(/changes the cost and sell price/);
    expect(html).toContain('for="supplier"');
  });
});

describe('usage screen', () => {
  it('asks for the kind and the reason', () => {
    const html = page('sales', <UsageForm branchId={branch} />);
    expect(html).toContain('Damaged or lost');
    expect(html).toContain('for="usage-reason"');
  });
});

describe('stock-take review', () => {
  const st: Stocktake = {
    id: 't1', branch_id: branch, status: 'draft',
    lines: [
      { product_id: 'p1', description: 'LED bulb 9W screw', expected_qty: '12.000', counted_qty: '10.000', variance_qty: '-2.000' },
      { product_id: 'p2', description: 'LED bulb 15W screw', expected_qty: '5.000', counted_qty: '5.000', variance_qty: '0.000' },
    ],
  };

  it('lists only items that differ, in words, with a commit button for a draft', () => {
    const html = renderToString(<StocktakeReview stocktake={st} onCommit={() => undefined} />);
    expect(html).toContain('LED bulb 9W screw');
    expect(html).not.toContain('LED bulb 15W screw');
    expect(html).toContain('(less)');
    expect(html).toContain('Commit the count');
  });

  it('has no commit button once committed', () => {
    expect(renderToString(<StocktakeReview stocktake={{ ...st, status: 'committed' }} onCommit={() => undefined} />)).not.toContain('Commit the count');
  });
});

describe('valuation and profit', () => {
  const v: Valuation = {
    as_of: '2026-10-05', currency: 'UGX',
    rows: [{ branch_id: branch, product_id: 'p1', description: 'LED bulb 9W screw', qty: '2.000', sell_minor: 6000, expected_sales_minor: 12000, cost_minor: 3500, value_at_cost_minor: 7000 }],
    expected_sales_minor: 12000, value_at_cost_minor: 7000,
  };

  it('shows totals at cost and at price when the server sent the cost', () => {
    const html = renderToString(<ValuationTable valuation={v} />);
    expect(html).toContain('UGX 7,000');
    expect(html).toContain('UGX 12,000');
    expect(html).toContain('At cost');
  });

  it('shows no cost column when the server sent no cost (no retail.profit.read)', () => {
    const noCost: Valuation = {
      ...v, value_at_cost_minor: undefined,
      rows: [{ branch_id: branch, product_id: 'p1', description: 'LED bulb 9W screw', qty: '2.000', sell_minor: 6000, expected_sales_minor: 12000 }],
    };
    const html = renderToString(<ValuationTable valuation={noCost} />);
    expect(html).toContain('UGX 12,000');
    expect(html).not.toMatch(/cost/i);
    expect(html).not.toContain('UGX 7,000');
  });

  it('marks a loss in words', () => {
    const report: DailyProfit = {
      from: '2026-10-05', to: '2026-10-05', currency: 'UGX', sales_minor: 1000, cost_of_sales_minor: 3000, usage_cost_minor: 500, profit_minor: -2500,
      rows: [{ branch_id: branch, date: '2026-10-05', sales_minor: 1000, cost_of_sales_minor: 3000, gross_profit_minor: -2000, usage_cost_minor: 500, profit_minor: -2500 }],
    };
    const html = renderToString(<ProfitTable report={report} />);
    expect(html).toContain('(loss)');
    expect(html).toContain('-UGX 2,500');
  });
});
