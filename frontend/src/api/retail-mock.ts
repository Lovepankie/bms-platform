import type {
  Customer, DailyProfit, DailyProfitRow, Product, Purchase, RetailApi, Sale, SaleLine, Stocktake, StockRow, Supplier, Usage, Valuation, ValuationRow,
} from './retail';
import { RetailError } from './retail';
import { retailMessage } from './retail-errors';
import type { Me } from './client';

// Fabricated retail data for VITE_RETAIL_MOCK and for tests: invented products, branches and
// balances, held in memory, with the REAL snake_case shapes of docs/sdd/07-api-design.md section
// 7.11.20 (cost, cost snapshot and profit fields are absent unless the session holds
// retail.profit.read; a repeated Idempotency-Key replays the first answer; a sale beyond the
// branch balance is refused with the server's insufficient_stock code). Nothing here is real.
// Quantities are integer thousandths inside the store.

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';

const SALES_PERMISSIONS = [
  'retail.sale.create', 'retail.sale.read', 'retail.stock.read', 'retail.usage.report', 'retail.customer.manage',
];
const ADMIN_PERMISSIONS = [
  ...SALES_PERMISSIONS, 'retail.catalogue.manage', 'retail.price.edit', 'retail.sale.void',
  'retail.stocktake.commit', 'retail.purchase.create', 'retail.profit.read',
];

/** A fake signed-in user for the mock session, so the app runs without a backend. */
export function mockMe(role: string | undefined): Me & { modules: string[] } {
  return {
    user_id: '00000000-0000-4000-8000-0000000000f1',
    full_name: role === 'sales' ? 'Test Seller 01' : 'Test Admin 01',
    kind: 'staff',
    permissions: role === 'sales' ? SALES_PERMISSIONS : ADMIN_PERMISSIONS,
    all_branches: role !== 'sales',
    default_branch_id: BRANCH_A,
    branches: [
      { id: BRANCH_A, code: 'BR1', name: 'Test Branch A', is_head_office: true },
      { id: BRANCH_B, code: 'BR2', name: 'Test Branch B', is_head_office: false },
    ],
    mfa_enabled: false,
    unused_recovery_codes: 8,
    modules: ['retail'],
  };
}

let profitAccess = true;
/** The staff layout tells the mock what the session may see; the real API decides by itself. */
export function setMockProfitAccess(allowed: boolean): void {
  profitAccess = allowed;
}

interface MockProduct { id: string; code: string; description: string; unit: string; costMinor: number; sellMinor: number }

const PRODUCTS: MockProduct[] = [
  ['P001', '2.5mm twin cable 100m roll', 'roll', 185000, 230000],
  ['P002', '1.5mm single cable 100m roll', 'roll', 95000, 120000],
  ['P003', 'LED bulb 9W screw', 'piece', 3500, 6000],
  ['P004', 'LED bulb 15W screw', 'piece', 6000, 9500],
  ['P005', 'Double socket 13A', 'piece', 8000, 12000],
  ['P006', 'Single switch 1 gang', 'piece', 2500, 4500],
  ['P007', 'MCB 20A single pole', 'piece', 7500, 12000],
  ['P008', 'Consumer unit 8 way', 'piece', 65000, 90000],
  ['P009', 'Insulation tape black', 'piece', 1200, 2500],
  ['P010', 'Conduit pipe 20mm 3m', 'piece', 2800, 4500],
  ['P011', 'Extension board 4 way', 'piece', 14000, 22000],
  ['P012', 'Solar panel 100W', 'piece', 210000, 290000],
].map(([code, description, unit, costMinor, sellMinor], i) => ({
  id: `00000000-0000-4000-8000-0000000b${String(i + 1).padStart(4, '0')}`,
  code: code as string, description: description as string, unit: unit as string,
  costMinor: costMinor as number, sellMinor: sellMinor as number,
}));

const toMilli = (text: string): number => Math.round(Number(text) * 1000);
const fromMilli = (m: number): string => `${m < 0 ? '-' : ''}${Math.floor(Math.abs(m) / 1000)}.${String(Math.abs(m) % 1000).padStart(3, '0')}`;
const lineTotal = (price: number, qtyMilli: number): number => Number((BigInt(price) * BigInt(qtyMilli) + 500n) / 1000n);
const today = (): string => new Date().toISOString().slice(0, 10);
const delay = <T>(value: T): Promise<T> => new Promise((resolve) => setTimeout(() => resolve(value), 120));
/** Runs a handler; what it throws (a refusal) becomes a rejected promise, as a failed request does. */
const run = <T>(fn: () => T): Promise<T> => {
  try {
    return delay(fn());
  } catch (e) {
    return Promise.reject(e);
  }
};

/** A refusal in the server's problem shape, so the screens show the same message as with the real API. */
const refuse = (status: number, code: string, detail: string): never => {
  throw new RetailError(retailMessage({ code, detail }, status), status, code);
};

export function createMockRetail(): RetailApi {
  const balances = new Map<string, number>();
  PRODUCTS.forEach((p, i) => {
    balances.set(`${BRANCH_A}|${p.id}`, (i % 5 === 3 ? -2 : 4 + i * 3) * 1000);
    balances.set(`${BRANCH_B}|${p.id}`, (i % 7 === 2 ? 0 : 2 + i) * 1000);
  });
  const products = new Map(PRODUCTS.map((p) => [p.id, { ...p }]));
  const customers: Customer[] = [{ id: 'c0000000-0000-4000-8000-000000000001', name: 'Test Buyer 01', contact: '+256700000001' }];
  const suppliers: Supplier[] = [{ id: 's0000000-0000-4000-8000-000000000001', name: 'Test Supplier 01', active: true }];
  const stocktakes = new Map<string, Stocktake>();
  const sales: (Sale & { costTotal: number })[] = [];
  const usageCostByDay = new Map<string, number>();
  const replay = new Map<string, { hash: string; value: unknown }>();
  let seq = 100;
  const nextId = (prefix: string) => `${prefix}0000000-0000-4000-8000-${String(++seq).padStart(12, '0')}`;
  // Cost, cost snapshot and profit fields exist only for a session holding retail.profit.read.
  const cost = <T extends object>(row: T, fields: Record<string, number>): T => (profitAccess ? { ...row, ...fields } : row);
  const once = <T>(key: string, request: unknown, make: () => T): T => {
    const hash = JSON.stringify(request);
    const seen = replay.get(key);
    if (seen) {
      if (seen.hash !== hash) refuse(422, 'idempotency_key_reused', 'This Idempotency-Key was used for a different request.');
      return seen.value as T;
    }
    const value = make();
    replay.set(key, { hash, value });
    return value;
  };
  const bal = (branch: string, product: string) => balances.get(`${branch}|${product}`) ?? 0;
  const move = (branch: string, product: string, milli: number) => balances.set(`${branch}|${product}`, bal(branch, product) + milli);
  const find = (id: string) => {
    const p = products.get(id);
    if (!p) return refuse(422, 'validation_failed', 'No such product.');
    return p;
  };
  const matches = (p: MockProduct, query?: string) => {
    const q = (query ?? '').trim().toLowerCase();
    return !q || p.description.toLowerCase().includes(q) || p.code.toLowerCase().includes(q);
  };
  // The tenant forbids negative stock (the default): a sale or usage beyond the balance is refused.
  const guard = (branch: string, productId: string, milli: number) => {
    const p = find(productId);
    if (bal(branch, p.id) - milli < 0) refuse(422, 'insufficient_stock', `Product ${p.id} has ${fromMilli(bal(branch, p.id))} in stock at this branch.`);
  };

  return {
    listProducts: ({ query, branchId }) =>
      delay([...products.values()].filter((p) => matches(p, query)).map((p): Product => {
        const row: Product = {
          id: p.id, code: p.code, description: p.description, unit: p.unit, sell_minor: p.sellMinor, currency: 'UGX', active: true,
        };
        if (branchId) {
          row.qty = fromMilli(bal(branchId, p.id));
          row.negative = bal(branchId, p.id) < 0;
        }
        return cost(row, { cost_minor: p.costMinor });
      })),

    listStock: ({ branchId, query, negativeOnly }) =>
      delay([...products.values()].filter((p) => matches(p, query)).map((p): StockRow => {
        const milli = bal(branchId, p.id);
        return cost({ product_id: p.id, code: p.code, description: p.description, unit: p.unit, qty: fromMilli(milli), negative: milli < 0, sell_minor: p.sellMinor }, { cost_minor: p.costMinor });
      }).filter((r) => !negativeOnly || r.negative)),

    listCustomers: () => delay([...customers]),
    listSuppliers: () => delay([...suppliers]),
    createSupplier: (body) => {
      const s: Supplier = { id: nextId('s'), name: body.name, active: true };
      suppliers.push(s);
      return delay(s);
    },

    createSale: (body, key) =>
      run(() => once(`sale|${key}`, body, (): Sale => {
      const branchId = body.branch_id ?? BRANCH_A;
      body.lines.forEach((l) => guard(branchId, l.product_id, toMilli(l.qty)));
      let costTotal = 0;
      const lines: SaleLine[] = body.lines.map((l, n) => {
        const p = find(l.product_id);
        const milli = toMilli(l.qty);
        const price = l.unit_price_minor ?? p.sellMinor;
        move(branchId, p.id, -milli);
        costTotal += lineTotal(p.costMinor, milli);
        return cost({ id: nextId('l'), line_no: n + 1, product_id: p.id, code: p.code, description: p.description, qty: l.qty, unit_price_minor: price, line_total_minor: lineTotal(price, milli) },
          { unit_cost_minor: p.costMinor, line_cost_minor: lineTotal(p.costMinor, milli) });
      });
      const total = lines.reduce((sum, l) => sum + (l.line_total_minor ?? 0), 0);
      const credit = body.payment_method === 'credit';
      const buyer = body.buyer_name ?? customers.find((c) => c.id === body.customer_id)?.name;
      const sale = cost({
        id: nextId('5'), sale_no: `S-${String(seq).padStart(6, '0')}`, branch_id: branchId, sale_date: body.sale_date ?? today(),
        payment_method: body.payment_method, status: 'completed', currency: 'UGX',
        ...(buyer ? { buyer_name: buyer } : {}), ...(body.due_date ? { due_date: body.due_date } : {}),
        lines, total_minor: total, paid_minor: credit ? 0 : total, balance_minor: credit ? total : 0,
      }, { cost_total_minor: costTotal, profit_minor: total - costTotal });
      sales.push({ ...sale, costTotal });
      return sale;
      })),

    createPurchase: (body, key) =>
      run(() => once(`purchase|${key}`, body, (): Purchase => {
      let total = 0;
      const lines = body.lines.map((l, n) => {
        const p = find(l.product_id);
        p.costMinor = l.cost_minor;
        if (l.sell_minor !== undefined) p.sellMinor = l.sell_minor;
        let qtyTotal = 0;
        l.qty_by_branch.forEach((q) => {
          move(q.branch_id, p.id, toMilli(q.qty));
          qtyTotal += toMilli(q.qty);
        });
        total += lineTotal(l.cost_minor, qtyTotal);
        return { id: nextId('p'), line_no: n + 1, product_id: p.id, code: p.code, description: p.description, cost_minor: l.cost_minor, sell_minor: p.sellMinor, qty_total: fromMilli(qtyTotal), qty_by_branch: l.qty_by_branch, line_total_minor: lineTotal(l.cost_minor, qtyTotal) };
      });
      return { id: nextId('6'), purchase_no: `P-${String(seq).padStart(6, '0')}`, purchased_on: body.purchased_on, payment_method: body.payment_method, currency: 'UGX', lines, total_minor: total };
      })),

    createUsage: (body, key) =>
      run(() => once(`usage|${key}`, body, (): Usage => {
      const branchId = body.branch_id ?? BRANCH_A;
      body.lines.forEach((l) => guard(branchId, l.product_id, toMilli(l.qty)));
      let costTotal = 0;
      const lines = body.lines.map((l, n) => {
        const p = find(l.product_id);
        const lineCost = lineTotal(p.costMinor, toMilli(l.qty));
        move(branchId, p.id, -toMilli(l.qty));
        costTotal += lineCost;
        return cost({ line_no: n + 1, product_id: p.id, code: p.code, description: p.description, qty: l.qty }, { unit_cost_minor: p.costMinor, line_cost_minor: lineCost });
      });
      usageCostByDay.set(today(), (usageCostByDay.get(today()) ?? 0) + costTotal);
      return cost({ id: nextId('7'), branch_id: branchId, kind: body.kind, reason: body.reason, occurred_on: body.occurred_on ?? today(), currency: 'UGX', lines }, { cost_total_minor: costTotal });
      })),

    createStocktake: (body) => {
      const branchId = body.branch_id ?? BRANCH_A;
      const st: Stocktake = {
        id: nextId('8'), branch_id: branchId, status: 'draft',
        lines: body.lines.map((l) => {
          const expected = bal(branchId, l.product_id);
          const p = find(l.product_id);
          return { product_id: l.product_id, code: p.code, description: p.description, expected_qty: fromMilli(expected), counted_qty: l.counted_qty, variance_qty: fromMilli(toMilli(l.counted_qty) - expected) };
        }),
      };
      stocktakes.set(st.id ?? '', st);
      return delay(st);
    },
    commitStocktake: (id) => {
      const st = stocktakes.get(id);
      if (!st) return Promise.reject(new RetailError('That could not be found.', 404, 'not_found'));
      if (st.status === 'committed') return Promise.reject(new RetailError(retailMessage({ code: 'stocktake_committed' }, 409), 409, 'stocktake_committed'));
      (st.lines ?? []).forEach((l) => move(st.branch_id ?? BRANCH_A, l.product_id ?? '', toMilli(l.variance_qty ?? '0')));
      st.status = 'committed';
      return delay({ ...st });
    },

    // Stock value is read with retail.stock.read; its cost columns need retail.profit.read.
    valuation: ({ branchId, asOf }) => {
      const rows = [...products.values()].map((p): ValuationRow => {
        const milli = bal(branchId, p.id);
        return cost({ branch_id: branchId, product_id: p.id, code: p.code, description: p.description, unit: p.unit, qty: fromMilli(milli), negative: milli < 0, sell_minor: p.sellMinor, expected_sales_minor: lineTotal(p.sellMinor, milli) },
          { cost_minor: p.costMinor, value_at_cost_minor: lineTotal(p.costMinor, milli) });
      });
      const sum = (pick: (r: ValuationRow) => number | undefined) => rows.reduce((s, r) => s + (pick(r) ?? 0), 0);
      const v: Valuation = cost({ as_of: asOf ?? today(), currency: 'UGX', rows, expected_sales_minor: sum((r) => r.expected_sales_minor) },
        { value_at_cost_minor: sum((r) => r.value_at_cost_minor) });
      return delay(v);
    },

    dailyProfit: ({ branchId, from, to }) => {
      if (!profitAccess) return Promise.reject(new RetailError(retailMessage({ code: 'permission_denied' }, 403), 403, 'permission_denied'));
      const rows: DailyProfitRow[] = [];
      for (let d = new Date(`${from}T00:00:00Z`); d <= new Date(`${to}T00:00:00Z`) && rows.length < 366; d.setUTCDate(d.getUTCDate() + 1)) {
        const date = d.toISOString().slice(0, 10);
        const own = sales.filter((s) => s.branch_id === branchId && s.sale_date === date);
        const salesMinor = own.reduce((s, x) => s + (x.total_minor ?? 0), 0);
        const costMinor = own.reduce((s, x) => s + x.costTotal, 0);
        const usageMinor = usageCostByDay.get(date) ?? 0;
        rows.push({ branch_id: branchId, date, sales_minor: salesMinor, cost_of_sales_minor: costMinor, gross_profit_minor: salesMinor - costMinor, usage_cost_minor: usageMinor, profit_minor: salesMinor - costMinor - usageMinor });
      }
      const sum = (pick: (r: DailyProfitRow) => number | undefined) => rows.reduce((s, r) => s + (pick(r) ?? 0), 0);
      const report: DailyProfit = {
        from, to, currency: 'UGX', rows, sales_minor: sum((r) => r.sales_minor), cost_of_sales_minor: sum((r) => r.cost_of_sales_minor),
        usage_cost_minor: sum((r) => r.usage_cost_minor), profit_minor: sum((r) => r.profit_minor),
      };
      return delay(report);
    },
  };
}
