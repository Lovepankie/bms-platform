import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { businessToday, type AllBranchesStock, type DailyProfit, type Product, type Purchase, type Sale, type Stocktake, type StockRow, type Transfer, type Valuation } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { Receipt, SaleForm } from './sale';
import { AllBranchesList, AllBranchesTable, StockPage, StockTable } from './stock';
import { StocktakeReview } from './stocktake';
import { BranchTotals, ProfitTable, ValuationTable } from './profit';
import { RestockForm, RestockSaved } from './restock';
import { TransferForm, TransferSummary } from './transfer';
import { TransferList, showDate } from './transfers';
import { UsageForm } from './usage';
import { BranchRequired, Gate, NoStockHere, Problem } from './ui';
import { ALL_BRANCHES } from '../../../auth/branch';

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

  it('shows the category of each item in a Category column', () => {
    const html = renderToString(<StockTable rows={rows.map((r) => ({ ...r, category: 'Lighting' }))} showCost={false} />);
    expect(html).toContain('<th>Category</th>');
    expect(html).toContain('<td>Lighting</td>');
  });

  it('offers a category select with All categories', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'categories'], [{ id: 'c1', name: 'Cables' }, { id: 'c2', name: 'Lighting' }]);
    const html = page('sales', <StockPage />, client);
    expect(html).toContain('for="stock-category"');
    expect(html).toContain('All categories');
    expect(html).toContain('>Lighting</option>');
  });
});

describe('category in the pickers', () => {
  it('shows the category as a small grey label in the sale picker and the usage picker', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'products', branch, ''], products.map((p) => ({ ...p, category: 'Lighting' })));
    expect(page('sales', <SaleForm branchId={branch} />, client)).toContain('<span class="hint">Lighting</span>');
    expect(page('sales', <UsageForm branchId={branch} />, client)).toContain('<span class="hint">Lighting</span>');
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
    rows: [{ branch_id: branch, product_id: 'p1', description: 'LED bulb 9W screw', qty: '2.000', sell_minor: 6000, expected_sales_minor: 12000, cost_minor: 3500, value_at_cost_minor: 7000, expected_profit_minor: 5000, expected_profit_bp: 7143, category: 'Lighting' }],
    categories: [{ category_id: 'c2', category: 'Lighting', expected_sales_minor: 12000, value_at_cost_minor: 7000, expected_profit_minor: 5000, expected_profit_bp: 7143 }],
    expected_sales_minor: 12000, value_at_cost_minor: 7000, expected_profit_minor: 5000, expected_profit_bp: 7143,
  };

  it('shows totals at cost and at price when the server sent the cost', () => {
    const html = renderToString(<ValuationTable valuation={v} />);
    expect(html).toContain('UGX 7,000');
    expect(html).toContain('UGX 12,000');
    expect(html).toContain('At cost');
  });

  it('shows expected profit and its percent over cost in the total, per item and by category', () => {
    const html = renderToString(<ValuationTable valuation={v} />);
    expect(html).toContain('Expected profit UGX 5,000 (71.43% of cost)');
    expect(html).toContain('<h2>By category</h2>');
    expect(html).toContain('<td>Lighting</td>');
    expect((html.match(/71\.43%/g) ?? []).length).toBe(3);
  });

  it('shows no cost column when the server sent no cost (no retail.profit.read)', () => {
    const noCost: Valuation = {
      ...v, value_at_cost_minor: undefined, expected_profit_minor: undefined, expected_profit_bp: undefined,
      categories: [{ category_id: 'c2', category: 'Lighting', expected_sales_minor: 12000 }],
      rows: [{ branch_id: branch, product_id: 'p1', description: 'LED bulb 9W screw', qty: '2.000', sell_minor: 6000, expected_sales_minor: 12000 }],
    };
    const html = renderToString(<ValuationTable valuation={noCost} />);
    expect(html).toContain('UGX 12,000');
    expect(html).not.toMatch(/cost|profit/i);
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

describe('stock moves', () => {
  const [from, to] = mockMe('admin').branches ?? [];
  const moved: Transfer = {
    id: 'tr1', from_branch_id: from?.id, to_branch_id: to?.id, transfer_date: '2026-10-06', status: 'completed', currency: 'UGX',
    lines: [{ line_no: 1, product_id: 'p1', code: 'P003', description: 'LED bulb 9W screw', qty: '2.000', unit_cost_minor: 3500, line_cost_minor: 7000 }],
    cost_total_minor: 7000,
  };

  it('shows a sales user no Move stock screen', () => {
    expect(page('sales', <Gate screen="transfer" title="Move stock"><p>FORM</p></Gate>)).toContain('You do not have access');
  });

  it('offers the other branches as destinations and the source stock with each item', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'products', branch, ''], products);
    const html = page('admin', <TransferForm fromBranchId={branch} />, client);
    expect(html).toContain('for="transfer-to"');
    expect(html).toContain('Test Branch B');
    expect(html).not.toContain('>BR1 Test Branch A</option>');
    expect(html).toContain('in stock here');
    expect(html).toContain('for="transfer-search"');
    expect(html).not.toMatch(/each,/);
  });

  it('lists moves in words and shows the value at cost only with retail.profit.read', () => {
    const list = page('sales', <TransferList items={[moved, { ...moved, id: 'tr2', status: 'voided' }]} onOpen={() => undefined} />);
    expect(list).toContain('From Test Branch A (BR1) to Test Branch B (BR2)');
    expect(list).toContain('cancelled');
    expect(page('sales', <TransferSummary transfer={moved} />)).not.toMatch(/cost/i);
    expect(page('admin', <TransferSummary transfer={moved} />)).toContain('Value at cost: UGX 7,000');
  });
});

// #112 items 1, 2, 3, 10 and 11, #103 and #105.
describe('walk-through fixes (#112)', () => {
  const [from, to] = mockMe('admin').branches ?? [];
  const adminId = mockMe('admin').user_id;
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('stock moves rows read as date, route and what moved, with no leading colon (item 1)', () => {
    const t: Transfer = {
      id: 'tr9', from_branch_id: from?.id, to_branch_id: to?.id, transfer_date: '2026-10-06', status: 'completed', created_by: adminId,
      lines: ['LED bulb 9W screw', 'Socket double', 'Test cable 10m'].map((d, n) => ({ line_no: n + 1, product_id: `p${n}`, code: `P00${n}`, description: d, qty: '1.000' })),
    };
    const html = page('admin', <TransferList items={[t]} onOpen={() => undefined} />);
    expect(html).toContain('6 Oct 2026');
    expect(html).not.toContain('2026-10-06');
    expect(html).not.toMatch(/>:\s/);
    expect(html).toContain('From Test Branch A (BR1) to Test Branch B (BR2)');
    expect(html).toContain('3 items: LED bulb 9W screw, Socket double, and 1 more, by you');
    expect(html).toContain('white-space:nowrap');
    expect(showDate('2026-01-31')).toBe('31 Jan 2026');
  });

  it('move stock hints in the sale screen words and disables the button above the source stock (item 2)', () => {
    const draft = { toBranchId: to?.id, transferDate: '2026-10-06', note: '', lines: [{ product: products[0], qty: '13' }] };
    const store = new Map([[`retail-draft:transfer:${adminId}:${branch}`, JSON.stringify({ key: 'test-key-1', draft })]]);
    vi.stubGlobal('sessionStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: () => undefined, removeItem: () => undefined });
    const html = page('admin', <TransferForm fromBranchId={branch} />);
    expect(html).toContain('Only 12 piece in stock at this branch.');
    expect(html).toMatch(/<button type="submit" class="rt-primary" disabled="">Move stock<\/button>/);
  });

  it('shows the server refusal for stock in plain words when the form let it through (item 2)', async () => {
    const mock = createMockRetail();
    const [item] = await mock.listProducts({ branchId: branch });
    const refused = await mock.createTransfer(
      { from_branch_id: branch, to_branch_id: to?.id ?? '', lines: [{ product_id: item?.id ?? '', qty: '99999' }] }, 'test-key-3',
    ).then(() => null, (e: unknown) => e);
    const html = renderToString(<Problem error={refused} />);
    expect(html).toContain('role="alert"');
    expect(html).toContain('There is not enough stock at this branch for one of the items.');
    expect(html).not.toContain('insufficient_stock');
  });

  it('defaults the move date to today in Africa/Kampala, not UTC (item 3)', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-06T22:30:00Z'));
    expect(businessToday()).toBe('2026-10-07');
    const html = page('admin', <TransferForm fromBranchId={branch} />);
    expect(html).toContain('value="2026-10-07"');
    expect(html).not.toContain('leave empty for today');
  });

  it('says plainly when the chosen branch holds no stock and offers the branches that do (#103)', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'stocked-branches'], [to?.id]);
    const empty = page('admin', <NoStockHere branchId={from?.id ?? ''} />, client);
    expect(empty).toContain('This branch holds no stock. Switch branch?');
    expect(page('admin', <NoStockHere branchId={to?.id ?? ''} />, client)).toBe('');
    expect(page('admin', <NoStockHere branchId={from?.id ?? ''} />)).toBe('');
  });

  it('prints branches as Name (CODE) in the move form (#105)', () => {
    const html = page('admin', <TransferForm fromBranchId={branch} />);
    expect(html).toContain('From: Test Branch A (BR1)');
    expect(html).toContain('>Test Branch B (BR2)</option>');
    expect(html).not.toContain('BR2 Test Branch B');
  });

  it('confirms a restock with the supplier, number, date and each price change (items 10 and 11)', () => {
    const purchase: Purchase = {
      id: 'pu1', purchase_no: 'RP00000042', purchased_on: '2026-10-06', total_minor: 85000, currency: 'UGX',
      lines: [
        { product_id: 'p1', description: 'LED bulb 9W screw', cost_minor: 850, sell_minor: 1400 },
        { product_id: 'p2', description: 'LED bulb 15W screw', cost_minor: 6000, sell_minor: 9500 },
      ],
    };
    const context = { supplierName: 'Test Supplier 01', before: { p1: { sell: 1200, cost: 800 }, p2: { sell: 9500, cost: 6000 } } };
    const admin = page('admin', <RestockSaved purchase={purchase} context={context} />);
    expect(admin).toContain('Restock saved (RP00000042)');
    expect(admin).toContain('From Test Supplier 01, bought on 2026-10-06: 2 items');
    expect(admin).toContain('LED bulb 9W screw: sell price changed from UGX 1,200 to UGX 1,400.');
    expect(admin).toContain('LED bulb 9W screw: cost changed from UGX 800 to UGX 850.');
    expect(admin).not.toContain('LED bulb 15W screw:');
    expect(page('sales', <RestockSaved purchase={purchase} context={context} />)).not.toContain('cost changed');
  });
});

describe('All branches (#144)', () => {
  const [a, b] = mockMe('admin').branches ?? [];
  const data: AllBranchesStock = {
    branches: [{ id: a?.id, code: a?.code, name: a?.name }, { id: b?.id, code: b?.code, name: b?.name }],
    items: [
      {
        product_id: 'p1', code: 'P003', description: 'LED bulb 9W screw', category: 'Lighting', unit: 'piece', total_qty: '10.000', negative: true, sell_minor: 6000, cost_minor: 3500,
        balances: [{ branch_id: a?.id, qty: '12.000', negative: false }, { branch_id: b?.id, qty: '-2.000', negative: true }],
      },
    ],
  };
  const inAll = (node: ReactElement, role: 'admin' | 'sales' = 'admin', client = new QueryClient()) =>
    renderToString(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: mockMe(role), branch: ALL_BRANCHES, chooseBranch: () => undefined }}>{node}</StaffContext.Provider>
      </QueryClientProvider>,
    );

  it('shows a column per branch, a total and the negative flag on the cell that is negative', () => {
    const html = renderToString(<AllBranchesTable data={data} showCost={false} />);
    expect(html).toContain('Test Branch A (BR1)');
    expect(html).toContain('Test Branch B (BR2)');
    expect(html).toContain('<th class="num">Total</th>');
    expect(html).toContain('<td class="num">12</td>');
    expect(html).toContain('<td class="num">-2<div class="rt-flag">Negative</div></td>');
    expect(html).toContain('10 piece');
    expect(html).not.toMatch(/cost/i);
  });

  it('shows cost only when asked and the server sent it', () => {
    expect(renderToString(<AllBranchesTable data={data} showCost />)).toContain('UGX 3,500');
    const without: AllBranchesStock = { ...data, items: data.items.map(({ cost_minor: _c, ...r }) => r) };
    expect(renderToString(<AllBranchesTable data={without} showCost />)).not.toMatch(/cost/i);
  });

  it('on a phone shows the total and a button for the per-branch breakdown', () => {
    const html = renderToString(<AllBranchesList data={data} showCost={false} />);
    expect(html).toContain('Total');
    expect(html).toContain('Show branches');
    expect(html).toContain('aria-expanded="false"');
    expect(html).not.toContain('Test Branch B');
    expect(html).toContain('Negative');
  });

  it('says there are no items when the list is empty', () => {
    expect(renderToString(<AllBranchesTable data={{ ...data, items: [] }} showCost={false} />)).toContain('No items found.');
  });

  it('the Stock page with All branches shows the table, not a request to choose a branch', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'stock', 'all-branches', '', '', false], data);
    const html = inAll(<StockPage />, 'admin', client);
    expect(html).toContain('All branches');
    expect(html).toContain('LED bulb 9W screw');
    expect(html).not.toContain('Choose a branch');
  });

  it('a write screen asks for a branch and offers each as a button', () => {
    const html = inAll(<BranchRequired />);
    expect(html).toContain('Choose a branch');
    expect(html).toContain('<button type="button" class="btn-sm">Test Branch A (BR1)</button>');
    expect(html).toContain('Test Branch B (BR2)');
    expect(html).not.toContain('Branch box');
  });

  it('Stock value shows each branch and the total', () => {
    const v: Valuation = {
      as_of: '2026-10-05', currency: 'UGX', rows: [], categories: [],
      branches: [
        { branch_id: a?.id, expected_sales_minor: 12000, value_at_cost_minor: 7000, expected_profit_minor: 5000, expected_profit_bp: 7143 },
        { branch_id: b?.id, expected_sales_minor: 6000, value_at_cost_minor: 3500, expected_profit_minor: 2500, expected_profit_bp: 7143 },
      ],
      expected_sales_minor: 18000, value_at_cost_minor: 10500, expected_profit_minor: 7500, expected_profit_bp: 7143,
    };
    const nameOf = (id: string | undefined) => (id === a?.id ? 'Test Branch A' : 'Test Branch B');
    const html = renderToString(<><BranchTotals valuation={v} nameOf={nameOf} /><ValuationTable valuation={v} /></>);
    expect(html).toContain('<h2>By branch</h2>');
    expect(html).toContain('Test Branch A');
    expect(html).toContain('UGX 12,000');
    expect(html).toContain('UGX 6,000');
    expect(html).toContain('At selling price UGX 18,000');
  });

  it('Daily profit shows each branch and the total of every branch per day', () => {
    const report: DailyProfit = {
      from: '2026-10-05', to: '2026-10-05', currency: 'UGX', sales_minor: 3000, cost_of_sales_minor: 1800, usage_cost_minor: 0, profit_minor: 1200,
      rows: [
        { branch_id: a?.id, date: '2026-10-05', sales_minor: 1000, cost_of_sales_minor: 600, gross_profit_minor: 400, usage_cost_minor: 0, profit_minor: 400 },
        { branch_id: b?.id, date: '2026-10-05', sales_minor: 2000, cost_of_sales_minor: 1200, gross_profit_minor: 800, usage_cost_minor: 0, profit_minor: 800 },
      ],
    };
    const nameOf = (id: string | undefined) => (id === a?.id ? 'Test Branch A' : 'Test Branch B');
    const html = renderToString(<ProfitTable report={report} nameOf={nameOf} />);
    expect(html).toContain('<h2>By branch</h2>');
    expect(html).toContain('<td>Test Branch A</td>');
    expect(html).toContain('<td>Test Branch B</td>');
    expect(html).toContain('<td>2026-10-05</td>');
    expect(html).toContain('UGX 3,000');
    expect(html).toContain('UGX 1,200');
  });
});
