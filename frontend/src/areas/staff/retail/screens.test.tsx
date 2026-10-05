import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Product, Sale, Stocktake, StockRow, Valuation } from '../../../api/retail';
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
  { id: 'p1', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', sellMinor: 6000, active: true, qty: '12.000' },
  { id: 'p2', code: 'P004', description: 'LED bulb 15W screw', unit: 'piece', sellMinor: 9500, active: true, qty: '-2.000' },
];

describe('gating', () => {
  it('shows a sales user nothing of the profit and valuation screens', () => {
    for (const screen of ['profit', 'valuation'] as const) {
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
    id: 's1', branchId: branch, saleDate: '2026-10-05', paymentMethod: 'credit', buyerName: 'Test Buyer 01', dueDate: '2026-11-01',
    lines: [{ productId: 'p1', description: 'LED bulb 9W screw', qty: '2.000', unitPriceMinor: 6000, lineTotalMinor: 12000, unitCostMinor: 3500 }],
    totalMinor: 12000, balanceMinor: 12000, profitMinor: 5000,
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
    { productId: 'p1', description: 'LED bulb 9W screw', unit: 'piece', qty: '12.000', negative: false, sellMinor: 6000, costMinor: 3500 },
    { productId: 'p2', description: 'LED bulb 15W screw', unit: 'piece', qty: '-2.000', negative: true, sellMinor: 9500, costMinor: 6000 },
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
    id: 't1', branchId: branch, status: 'draft',
    lines: [
      { productId: 'p1', description: 'LED bulb 9W screw', expectedQty: '12.000', countedQty: '10.000', varianceQty: '-2.000' },
      { productId: 'p2', description: 'LED bulb 15W screw', expectedQty: '5.000', countedQty: '5.000', varianceQty: '0.000' },
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
  it('shows totals at cost and at price', () => {
    const v: Valuation = {
      branchId: branch, asOf: '2026-10-05',
      rows: [{ productId: 'p1', description: 'LED bulb 9W screw', qty: '2.000', sellMinor: 6000, expectedSalesMinor: 12000, costMinor: 3500, valueAtCostMinor: 7000 }],
      totals: { expectedSalesMinor: 12000, valueAtCostMinor: 7000 },
    };
    const html = renderToString(<ValuationTable valuation={v} />);
    expect(html).toContain('UGX 7,000');
    expect(html).toContain('UGX 12,000');
  });

  it('marks a loss in words', () => {
    const html = renderToString(<ProfitTable rows={[{ branchId: branch, date: '2026-10-05', salesMinor: 1000, costMinor: 3000, usageMinor: 500, profitMinor: -2500 }]} />);
    expect(html).toContain('(loss)');
    expect(html).toContain('-UGX 2,500');
  });
});
