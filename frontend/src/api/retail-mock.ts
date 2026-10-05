import type {
  Customer, DailyProfitRow, Product, Purchase, RetailApi, Sale, SaleLine, Stocktake, Supplier, Usage, Valuation,
} from './retail';
import type { Me } from './client';

// Fabricated retail data for VITE_RETAIL_MOCK=1 and for tests: invented products, branches and
// balances, held in memory, behaving like docs/api/retail-contract-draft.md (cost and profit
// fields only when the session holds retail.profit.read, repeated Idempotency-Keys replay the
// first answer). Nothing here is real. Quantities are integer thousandths inside the store.

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

export function createMockRetail(): RetailApi {
  const balances = new Map<string, number>();
  PRODUCTS.forEach((p, i) => {
    balances.set(`${BRANCH_A}|${p.id}`, (i % 5 === 3 ? -2 : 4 + i * 3) * 1000);
    balances.set(`${BRANCH_B}|${p.id}`, (i % 7 === 2 ? 0 : 2 + i) * 1000);
  });
  const products = new Map(PRODUCTS.map((p) => [p.id, { ...p }]));
  const customers: Customer[] = [{ id: 'c0000000-0000-4000-8000-000000000001', name: 'Test Buyer 01', contact: '+256700000001' }];
  const suppliers: Supplier[] = [{ id: 's0000000-0000-4000-8000-000000000001', name: 'Test Supplier 01' }];
  const stocktakes = new Map<string, Stocktake>();
  const sales: Sale[] = [];
  const usageCostByDay = new Map<string, number>();
  const replay = new Map<string, unknown>();
  let seq = 100;
  const nextId = (prefix: string) => `${prefix}0000000-0000-4000-8000-${String(++seq).padStart(12, '0')}`;
  const cost = <T extends object>(row: T, fields: Partial<Record<string, number>>): T => (profitAccess ? { ...row, ...fields } : row);
  const once = <T>(key: string, make: () => T): T => {
    if (!replay.has(key)) replay.set(key, make());
    return replay.get(key) as T;
  };
  const bal = (branch: string, product: string) => balances.get(`${branch}|${product}`) ?? 0;
  const move = (branch: string, product: string, milli: number) => balances.set(`${branch}|${product}`, bal(branch, product) + milli);
  const find = (id: string) => {
    const p = products.get(id);
    if (!p) throw new Error('Unknown product');
    return p;
  };
  const matches = (p: MockProduct, query?: string) => {
    const q = (query ?? '').trim().toLowerCase();
    return !q || p.description.toLowerCase().includes(q) || p.code.toLowerCase().includes(q);
  };

  return {
    listProducts: ({ query, branchId }) =>
      delay([...products.values()].filter((p) => matches(p, query)).map((p): Product => {
        const row: Product = { id: p.id, code: p.code, description: p.description, unit: p.unit, sellMinor: p.sellMinor, active: true };
        if (branchId) row.qty = fromMilli(bal(branchId, p.id));
        return cost(row, { costMinor: p.costMinor });
      })),

    listStock: ({ branchId, query, negativeOnly }) =>
      delay([...products.values()].filter((p) => matches(p, query)).map((p) => {
        const milli = bal(branchId, p.id);
        return cost({ productId: p.id, description: p.description, unit: p.unit, qty: fromMilli(milli), negative: milli < 0, sellMinor: p.sellMinor }, { costMinor: p.costMinor });
      }).filter((r) => !negativeOnly || r.negative)),

    listCustomers: () => delay([...customers]),
    createCustomer: (body) => {
      const c = { id: nextId('c'), ...body };
      customers.push(c);
      return delay(c);
    },
    listSuppliers: () => delay([...suppliers]),
    createSupplier: (body) => {
      const s = { id: nextId('s'), ...body };
      suppliers.push(s);
      return delay(s);
    },

    createSale: (body, key) =>
      delay(once(`sale|${key}`, (): Sale => {
        const branchId = body.branchId ?? BRANCH_A;
        let profit = 0;
        const lines: SaleLine[] = body.lines.map((l) => {
          const p = find(l.productId);
          const milli = toMilli(l.qty);
          const price = l.unitPriceMinor ?? p.sellMinor;
          move(branchId, p.id, -milli);
          profit += lineTotal(price - p.costMinor, milli);
          return cost({ productId: p.id, description: p.description, qty: l.qty, unitPriceMinor: price, lineTotalMinor: lineTotal(price, milli) }, { unitCostMinor: p.costMinor });
        });
        const total = lines.reduce((sum, l) => sum + l.lineTotalMinor, 0);
        const buyer = body.buyerName ?? customers.find((c) => c.id === body.customerId)?.name;
        const sale = cost({
          id: nextId('5'), branchId, saleDate: body.saleDate ?? today(), paymentMethod: body.paymentMethod,
          ...(buyer ? { buyerName: buyer } : {}), ...(body.dueDate ? { dueDate: body.dueDate } : {}),
          lines, totalMinor: total, balanceMinor: body.paymentMethod === 'credit' ? total : 0,
        }, { profitMinor: profit });
        sales.push(sale);
        return sale;
      })),

    createPurchase: (body, key) =>
      delay(once(`purchase|${key}`, (): Purchase => {
        let total = 0;
        body.lines.forEach((l) => {
          const p = find(l.productId);
          p.costMinor = l.costMinor;
          if (l.sellMinor !== undefined) p.sellMinor = l.sellMinor;
          l.qtyByBranch.forEach((q) => {
            move(q.branchId, p.id, toMilli(q.qty));
            total += lineTotal(l.costMinor, toMilli(q.qty));
          });
        });
        return { id: nextId('6'), purchasedOn: body.purchasedOn, paymentMethod: body.paymentMethod, lineCount: body.lines.length, totalMinor: total, pricesUpdated: body.lines.length };
      })),

    createUsage: (body, key) =>
      delay(once(`usage|${key}`, (): Usage => {
        body.lines.forEach((l) => {
          const p = find(l.productId);
          move(body.branchId, p.id, -toMilli(l.qty));
          usageCostByDay.set(today(), (usageCostByDay.get(today()) ?? 0) + lineTotal(p.costMinor, toMilli(l.qty)));
        });
        return { id: nextId('7'), kind: body.kind, lineCount: body.lines.length };
      })),

    createStocktake: (body) => {
      const st: Stocktake = {
        id: nextId('8'), branchId: body.branchId, status: 'draft',
        lines: body.lines.map((l) => {
          const expected = bal(body.branchId, l.productId);
          return { productId: l.productId, description: find(l.productId).description, expectedQty: fromMilli(expected), countedQty: l.countedQty, varianceQty: fromMilli(toMilli(l.countedQty) - expected) };
        }),
      };
      stocktakes.set(st.id, st);
      return delay(st);
    },
    commitStocktake: (id) => {
      const st = stocktakes.get(id);
      if (!st) throw new Error('Unknown stock-take');
      if (st.status === 'draft') st.lines.forEach((l) => move(st.branchId, l.productId, toMilli(l.varianceQty)));
      st.status = 'committed';
      return delay({ ...st });
    },

    valuation: ({ branchId, asOf }) => {
      if (!profitAccess) return Promise.reject(new Error('You do not have access to this report.'));
      const rows = [...products.values()].map((p) => {
        const milli = bal(branchId, p.id);
        return { productId: p.id, description: p.description, qty: fromMilli(milli), sellMinor: p.sellMinor, expectedSalesMinor: lineTotal(p.sellMinor, milli), costMinor: p.costMinor, valueAtCostMinor: lineTotal(p.costMinor, milli) };
      });
      const v: Valuation = {
        branchId, asOf: asOf ?? today(), rows,
        totals: { expectedSalesMinor: rows.reduce((s, r) => s + r.expectedSalesMinor, 0), valueAtCostMinor: rows.reduce((s, r) => s + (r.valueAtCostMinor ?? 0), 0) },
      };
      return delay(v);
    },

    dailyProfit: ({ branchId, from, to }) => {
      if (!profitAccess) return Promise.reject(new Error('You do not have access to this report.'));
      const rows: DailyProfitRow[] = [];
      for (let d = new Date(`${from}T00:00:00Z`); d <= new Date(`${to}T00:00:00Z`) && rows.length < 62; d.setUTCDate(d.getUTCDate() + 1)) {
        const date = d.toISOString().slice(0, 10);
        const own = sales.filter((s) => s.branchId === branchId && s.saleDate === date);
        const salesMinor = own.reduce((s, x) => s + x.totalMinor, 0);
        const costMinor = own.reduce((s, x) => s + x.totalMinor - (x.profitMinor ?? 0), 0);
        const usageMinor = usageCostByDay.get(date) ?? 0;
        rows.push({ branchId, date, salesMinor, costMinor, usageMinor, profitMinor: salesMinor - costMinor - usageMinor });
      }
      return delay(rows);
    },
  };
}
