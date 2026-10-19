import type {
  AllBranchesRow, Category, CapabilitiesRead, Pack, Customer, InFlightReport, LocationRow, PriceChange, DailyProfit, DailyProfitRow, Product, Purchase, RetailApi, Sale, SaleLine, Stocktake, StockByLocation, StockRow,
  Supplier, Transfer, Unit, Usage, Valuation, ValuationRow,
} from './retail';
import type { SalesQuery } from './retail';
import { RetailError, businessToday, daysBefore } from './retail';
import { summariseSales } from './retail-summary-mock';
import { retailMessage } from './retail-errors';
import type { Me } from './client';
import { createCashbookMock } from './retail-cashbook-mock';
import { createPricingMock } from './retail-pricing-mock';
import { createAccountsMock } from './retail-accounts-mock';
import { createDocumentsMock } from './retail-documents-mock';
import { createReturnsMock } from './retail-returns-mock';
import { createPurchasingMock } from './retail-purchasing-mock';

// Fabricated retail data for VITE_RETAIL_MOCK and for tests: invented products, branches and
// balances, held in memory, with the REAL snake_case shapes of docs/sdd/07-api-design.md section
// 7.11.20 (cost, cost snapshot and profit fields are absent unless the session holds
// retail.profit.read; a repeated Idempotency-Key replays the first answer; a sale beyond the
// branch balance is refused with the server's insufficient_stock code). Nothing here is real.
// Quantities are integer thousandths inside the store.

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
const MOCK_BRANCHES = [
  { id: BRANCH_A, code: 'BR1', name: 'Test Branch A', is_head_office: true },
  { id: BRANCH_B, code: 'BR2', name: 'Test Branch B', is_head_office: false },
];

// The seeded sales role: its sale permissions and, in the cash book, what a shop user may do without seeing
// profit: record savings (the standard amount only), banking and expenses, and read the lists and reports
// (ADR-022 decision 13, chapter 8 matrix).
const SALES_PERMISSIONS = [
  'retail.sale.create', 'retail.sale.read', 'retail.stock.read', 'retail.usage.report', 'retail.customer.manage', 'retail.price.override',
  'retail.savings.record', 'retail.banking.record', 'retail.expense.record', 'retail.cashbook.read',
];
const CASHIER_PERMISSIONS = SALES_PERMISSIONS;
const ADMIN_PERMISSIONS = [
  ...SALES_PERMISSIONS, 'retail.catalogue.manage', 'retail.price.edit', 'retail.sale.void',
  'retail.stocktake.commit', 'retail.purchase.create', 'retail.stock.transfer', 'retail.profit.read', 'core.settings.manage', 'retail.branch_role.manage', 'retail.pricelist.manage', 'retail.pricelist.use', 'retail.credit.manage', 'retail.credit.override', 'retail.document.issue', 'retail.sale.return',
  'retail.savings.record', 'retail.savings.overwrite', 'retail.banking.record', 'retail.expense.record', 'retail.expense.manage',
  'retail.withdrawal.record', 'retail.advance.create', 'retail.advance.repay', 'retail.cashbook.read', 'retail.cashbook.void', 'retail.payable.manage',
];

/** A fake signed-in user for the mock session, so the app runs without a backend. */
export function mockMe(role: string | undefined): Me & { modules: string[] } {
  const permissions = role === 'sales' ? SALES_PERMISSIONS : role === 'cashier' ? CASHIER_PERMISSIONS : ADMIN_PERMISSIONS;
  const scope = role === 'sales' || role === 'cashier' ? { all_branches: false, branch_ids: [BRANCH_A, BRANCH_B] } : { all_branches: true, branch_ids: [] };
  return {
    user_id: '00000000-0000-4000-8000-0000000000f1',
    full_name: role === 'sales' || role === 'cashier' ? 'Test Seller 01' : 'Test Admin 01',
    kind: 'staff',
    permissions,
    permission_scopes: Object.fromEntries(permissions.map((p) => [p, scope])),
    all_branches: role !== 'sales' && role !== 'cashier',
    default_branch_id: BRANCH_A,
    branches: MOCK_BRANCHES,
    mfa_enabled: false,
    unused_recovery_codes: 8,
    modules: ['retail'],
  };
}

let profitAccess = true;
let transferReceipt = false;

/** Forces the two-step transfer on for every mock instance, besides the capability a mock instance's settings switch (ADR-033). */
export function setMockTransferReceipt(on: boolean): void {
  transferReceipt = on;
}

/** The staff layout tells the mock what the session may see; the real API decides by itself. */
export function setMockProfitAccess(allowed: boolean): void {
  profitAccess = allowed;
}

interface MockProduct { id: string; code: string; description: string; category: string; unit: string; costMinor: number; sellMinor: number; active?: boolean; version?: number }

/** The mock's page size for credit buyers, as the API's default limit is 50. */
const CUSTOMER_PAGE = 50;

const CATEGORIES: Category[] = ['Cables', 'Lighting', 'Fittings', 'Solar'].map((name, i) => ({
  id: `00000000-0000-4000-8000-0000000c${String(i + 1).padStart(4, '0')}`,
  name,
  version: 1,
}));

const PRODUCTS: MockProduct[] = [
  ['P001', 'Cables', '2.5mm twin cable 100m roll', 'roll', 185000, 230000],
  ['P002', 'Cables', '1.5mm single cable 100m roll', 'roll', 95000, 120000],
  ['P003', 'Lighting', 'LED bulb 9W screw', 'piece', 3500, 6000],
  ['P004', 'Lighting', 'LED bulb 15W screw', 'piece', 6000, 9500],
  ['P005', 'Fittings', 'Double socket 13A', 'piece', 8000, 12000],
  ['P006', 'Fittings', 'Single switch 1 gang', 'piece', 2500, 4500],
  ['P007', 'Fittings', 'MCB 20A single pole', 'piece', 7500, 12000],
  ['P008', 'Fittings', 'Consumer unit 8 way', 'piece', 65000, 90000],
  ['P009', 'Fittings', 'Insulation tape black', 'piece', 1200, 2500],
  ['P010', 'Cables', 'Conduit pipe 20mm 3m', 'piece', 2800, 4500],
  ['P011', 'Fittings', 'Extension board 4 way', 'piece', 14000, 22000],
  ['P012', 'Solar', 'Solar panel 100W', 'piece', 210000, 290000],
].map(([code, category, description, unit, costMinor, sellMinor], i) => ({
  id: `00000000-0000-4000-8000-0000000b${String(i + 1).padStart(4, '0')}`,
  code: code as string, category: category as string, description: description as string, unit: unit as string,
  costMinor: costMinor as number, sellMinor: sellMinor as number,
}));

/** The server's level filter: out is zero or less, low is at or below 5 (so it includes out). */
const atLevel = (qty: number, level?: 'out' | 'low'): boolean => level === undefined || qty <= (level === 'out' ? 0 : 5);
const toMilli = (text: string): number => Math.round(Number(text) * 1000);
const fromMilli = (m: number): string => `${m < 0 ? '-' : ''}${Math.floor(Math.abs(m) / 1000)}.${String(Math.abs(m) % 1000).padStart(3, '0')}`;
const lineTotal = (price: number, qtyMilli: number): number => Number((BigInt(price) * BigInt(qtyMilli) + 500n) / 1000n);
const today = (): string => businessToday();
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

const MOCK_FLAGS = ['branch_roles', 'units', 'price_lists', 'customer_accounts', 'documents', 'purchasing', 'transfer_receipt', 'returns'] as const;
const MOCK_TRADE_PRESET: readonly string[] = ['branch_roles', 'units', 'price_lists', 'customer_accounts', 'returns', 'transfer_receipt'];

export function createMockRetail(): RetailApi {
  // Branch roles and capabilities of this mock instance (ADR-033): every branch both, every capability off.
  const roles = new Map<string, 'store' | 'shop' | 'both'>();
  const roleOf = (branch: string): 'store' | 'shop' | 'both' => roles.get(branch) ?? 'both';
  let caps: { value: Record<string, unknown>; version: number } = { value: { profile: 'shop' }, version: 1 };
  const twoStep = () => transferReceipt || caps.value['transfer_receipt'] === true;
  const read = (): CapabilitiesRead => ({ capabilities: { ...caps.value, ...Object.fromEntries(MOCK_FLAGS.map((k) => [k, caps.value[k] === true])) }, version: caps.version });
  const balances = new Map<string, number>();
  PRODUCTS.forEach((p, i) => {
    balances.set(`${BRANCH_A}|${p.id}`, (i % 5 === 3 ? -2 : 4 + i * 3) * 1000);
    balances.set(`${BRANCH_B}|${p.id}`, (i % 7 === 2 ? 0 : 2 + i) * 1000);
  });
  const products = new Map<string, MockProduct>(PRODUCTS.map((p) => [p.id, { ...p, active: true, version: 1 }]));
  // Categories and units of this mock instance: renamed and switched off by the management screens (#146).
  const cats: { id: string; name: string; active: boolean; version: number }[] = CATEGORIES.map((c) => ({ id: c.id ?? '', name: c.name ?? '', active: true, version: 1 }));
  const unitNames = [...new Set(PRODUCTS.map((p) => p.unit))];
  const unitRows: { id: string; name: string; active: boolean; version: number }[] = unitNames.map((name, i) => ({ id: `00000000-0000-4000-8000-0000000d${String(i + 1).padStart(4, '0')}`, name, active: true, version: 1 }));
  const categoryId = (name: string): string => cats.find((c) => c.name === name)?.id ?? '';
  const history = new Map<string, PriceChange[]>();
  const packs = new Map<string, Pack[]>();
  const used = <K extends 'category' | 'unit'>(key: K, name: string) => [...products.values()].filter((p) => p[key] === name).length;
  const refreshed = <T extends { name?: string }>(row: T, key: 'category' | 'unit') => ({ ...row, product_count: used(key, row.name ?? '') });
  const asProduct = (p: MockProduct): Product =>
    cost({ id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit, unit_id: unitRows.find((u) => u.name === p.unit)?.id, sell_minor: p.sellMinor, currency: 'UGX', active: p.active !== false, version: p.version ?? 1 }, { cost_minor: p.costMinor });
  const clash = (list: { id: string; name: string }[], id: string, name: string, code: string, what: string) => {
    if (list.some((x) => x.id !== id && x.name.toLowerCase() === name.trim().toLowerCase())) refuse(409, code, `A ${what} with this name exists.`);
  };
  const staleRow = (row: { version?: number }, version: number) => {
    if ((row.version ?? 1) !== version) refuse(409, 'version_conflict', 'This was changed by someone else. Reload and try again.');
  };
  const stale = (p: MockProduct, version: number) => {
    if ((p.version ?? 1) !== version) refuse(412, 'version_conflict', 'This item was changed by someone else. Reload and try again.');
  };
  const customers: Customer[] = [{ id: 'c0000000-0000-4000-8000-000000000001', name: 'Test Buyer 01', contact: '+256700000001', version: 1 }];
  // The second supplier row is a forwarder, who is owed the landed cost of imported goods (issue #199).
  const suppliers: Supplier[] = [
    { id: 's0000000-0000-4000-8000-000000000001', name: 'Test Supplier 01', active: true, version: 1 },
    { id: 's0000000-0000-4000-8000-000000000002', name: 'Test Forwarder 01', active: true, version: 1 },
  ];
  const stocktakes = new Map<string, Stocktake>();
  const sales: (Sale & { costTotal: number })[] = [];
  const usageCostByDay = new Map<string, number>();
  seedSales(sales);
  const transfers: Transfer[] = [];
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
    return !q || p.description.toLowerCase().includes(q) || p.code.toLowerCase().includes(q) || p.category.toLowerCase().includes(q);
  };
  // The tenant forbids negative stock (the default): a sale or usage beyond the balance is refused.
  const guard = (branch: string, productId: string, milli: number) => {
    const p = find(productId);
    if (bal(branch, p.id) - milli < 0) refuse(422, 'insufficient_stock', `Product ${p.id} has ${fromMilli(bal(branch, p.id))} in stock at this branch.`);
  };

  // What the day's sales at a branch came to, for the cash book's suggestions and expected cash.
  const sold = (branchId: string, date: string) => {
    const day = sales.filter((x) => x.branch_id === branchId && x.sale_date === date && x.status !== 'voided');
    return {
      total: day.reduce((s, x) => s + (x.total_minor ?? 0), 0),
      cash: day.filter((x) => x.payment_method === 'cash').reduce((s, x) => s + (x.total_minor ?? 0), 0),
      cost: day.reduce((s, x) => s + x.costTotal, 0),
    };
  };

  // Price lists, items and customer groups (ADR-033, issue #195); they answer 404 while the capability is off.
  const { resolveFor, ...pricingApi } = createPricingMock({
    on: () => caps.value['price_lists'] === true,
    today, nextId, delay, run, refuse,
    customer: (id) => customers.find((c) => c.id === id),
    customerCount: (groupId) => customers.filter((c) => c.customer_group_id === groupId).length,
    product: (id) => {
      const p = products.get(id);
      return p && { sellMinor: p.sellMinor, unitId: unitRows.find((u) => u.name === p.unit)?.id ?? '', code: p.code, description: p.description, unit: p.unit };
    },
  });

  // Customer accounts (ADR-033, issue #196): limits, terms, statements and payment on account; 404 while the capability is off.
  const { forSale, figures, ...accountsApi } = createAccountsMock({
    on: () => caps.value['customer_accounts'] === true,
    today, nextId, delay, run, once, refuse,
    customers: () => customers,
    sales: () => sales,
  });

  // Optional documents (ADR-033, issue #197): numbered quotations, invoices and delivery notes; 404 while the capability is off.
  let self: RetailApi | undefined;
  const documentsApi = createDocumentsMock({
    on: () => caps.value['documents'] === true,
    today, nextId, delay, run, once, refuse,
    sales: () => sales,
    branches: () => MOCK_BRANCHES.map((b) => ({ id: b.id, code: b.code ?? 'BR', name: b.name ?? '' })),
    product: (id) => {
      const p = products.get(id);
      return p && { code: p.code, description: p.description, unit: p.unit, sellMinor: p.sellMinor };
    },
    customer: (id) => customers.find((c) => c.id === id),
    makeSale: (body, key) => (self ?? refuse(500, 'not_ready', 'The mock is not ready.')).createSale(body, key),
  });

  // Customer returns (ADR-033, issue #201): credit notes against a sale; 404 while the capability is off.
  const returnsApi = createReturnsMock({
    on: () => caps.value['returns'] === true,
    today, nextId, delay, run, once, refuse,
    sales: () => sales,
    branchCode: (id) => MOCK_BRANCHES.find((b) => b.id === id)?.code ?? '',
  });

  // Optional purchasing (ADR-033, issue #198): orders, goods received, supplier invoices and payments; 404 while the capability is off.
  const purchasingApi = createPurchasingMock({
    on: () => caps.value['purchasing'] === true,
    today, nextId, delay, run, once, refuse,
    suppliers: () => suppliers,
    branchCode: (id) => MOCK_BRANCHES.find((b) => b.id === id)?.code ?? '',
    product: (id) => {
      const p = products.get(id);
      return p && { code: p.code, description: p.description, unit: p.unit, sellMinor: p.sellMinor };
    },
    pack: (productId, unitId) => {
      const k = (packs.get(productId) ?? []).find((x) => x.unit_id === unitId);
      return k && { unit: k.unit ?? '', factor: k.factor ?? '1.000' };
    },
    move: (branchId, productId, baseMilli, costMinor, sellMinor) => {
      move(branchId, productId, baseMilli);
      const p = products.get(productId);
      if (p && costMinor !== undefined) p.costMinor = costMinor;
      if (p && sellMinor !== undefined) p.sellMinor = sellMinor;
    },
    balance: bal,
    cost: (id) => products.get(id)?.costMinor ?? 0,
  });

  const mock: RetailApi = {
    ...pricingApi,
    ...accountsApi,
    ...documentsApi,
    ...returnsApi,
    ...purchasingApi,
    ...createCashbookMock({ profit: () => profitAccess, today, sold, branchIds: MOCK_BRANCHES.map((b) => b.id), delay, run, once, nextId }),

    listProducts: ({ query, branchId }) =>
      delay([...products.values()].filter((p) => p.active !== false && matches(p, query)).map((p): Product => {
        const row: Product = {
          id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit, unit_id: unitRows.find((u) => u.name === p.unit)?.id, sell_minor: p.sellMinor, currency: 'UGX', active: true,
        };
        if (branchId) {
          row.qty = fromMilli(bal(branchId, p.id));
          row.negative = bal(branchId, p.id) < 0;
        }
        return cost(row, { cost_minor: p.costMinor });
      })),

    listCategories: () => delay(cats.map((c) => refreshed(c, 'category') as Category)),

    listCatalogue: ({ query, categoryId: category, active, cursor }) => {
      const wanted = [...products.values()]
        .filter((p) => matches(p, query) && (!category || categoryId(p.category) === category) && (active === undefined || (p.active !== false) === active))
        .sort((a, b) => a.code.localeCompare(b.code));
      const start = cursor ? Number(cursor) : 0;
      return delay({ items: wanted.slice(start, start + 50).map(asProduct), ...(start + 50 < wanted.length ? { next_cursor: String(start + 50) } : {}) });
    },

    getProduct: (id) => run(() => asProduct(find(id))),

    createProduct: (body) =>
      run(() => {
        const code = (body.code ?? '').trim();
        if ([...products.values()].some((x) => x.code.toLowerCase() === code.toLowerCase())) refuse(409, 'duplicate_product_code', 'A product with this code exists (codes ignore case).');
        const category = cats.find((c) => c.id === body.category_id);
        const unit = unitRows.find((u) => u.id === body.unit_id);
        if (!category || !unit) return refuse(422, 'validation_failed', 'Choose a category and a unit.');
        if (!category.active || !unit.active) refuse(422, 'inactive_category', 'This category or unit is switched off. Choose another one.');
        const p: MockProduct = { id: nextId('b'), code, description: (body.description ?? '').trim(), category: category.name, unit: unit.name, costMinor: body.cost_minor ?? 0, sellMinor: body.sell_minor ?? 0, active: true, version: 1 };
        products.set(p.id, p);
        history.set(p.id, [{ id: nextId('h'), at: new Date().toISOString(), source: 'initial', new_sell_minor: p.sellMinor, currency: 'UGX', ...(profitAccess ? { new_cost_minor: p.costMinor } : {}) }]);
        return asProduct(p);
      }),

    updateProduct: (id, version, body) =>
      run(() => {
        const p = find(id);
        stale(p, version);
        const code = body.code === undefined ? p.code : body.code.trim();
        if ([...products.values()].some((x) => x.id !== id && x.code.toLowerCase() === code.toLowerCase())) refuse(409, 'duplicate_product_code', 'A product with this code exists (codes ignore case).');
        const category = body.category_id ? cats.find((c) => c.id === body.category_id) : undefined;
        const unit = body.unit_id ? unitRows.find((u) => u.id === body.unit_id) : undefined;
        if (category && !category.active && category.name !== p.category) refuse(422, 'inactive_category', 'This category is switched off. Choose another one.');
        Object.assign(p, {
          code, description: body.description === undefined ? p.description : body.description.trim(),
          category: category?.name ?? p.category, unit: unit?.name ?? p.unit, active: body.active ?? p.active, version: (p.version ?? 1) + 1,
        });
        return asProduct(p);
      }),

    editPrices: (id, version, body) =>
      run(() => {
        const p = find(id);
        stale(p, version);
        const nextCost = body.cost_minor ?? p.costMinor;
        const nextSell = body.sell_minor ?? p.sellMinor;
        if (nextCost === p.costMinor && nextSell === p.sellMinor) refuse(422, 'price_unchanged', 'The new prices equal the current ones.');
        history.set(id, [...(history.get(id) ?? []), {
          id: nextId('h'), at: new Date().toISOString(), source: 'manual', old_sell_minor: p.sellMinor, new_sell_minor: nextSell, currency: 'UGX', reason: body.reason,
          ...(profitAccess ? { old_cost_minor: p.costMinor, new_cost_minor: nextCost } : {}),
        }]);
        Object.assign(p, { costMinor: nextCost, sellMinor: nextSell, version: (p.version ?? 1) + 1 });
        return asProduct(p);
      }),

    priceHistory: (id) => run(() => ((find(id) && history.get(id)) || undefined)?.map((h) => (profitAccess ? h : { ...h, old_cost_minor: undefined, new_cost_minor: undefined })) || [{ id: nextId('h'), at: '2026-09-01T08:00:00Z', source: 'initial', new_sell_minor: find(id).sellMinor, currency: 'UGX', ...(profitAccess ? { new_cost_minor: find(id).costMinor } : {}) }]),

    // Pack sizes (ADR-033): kept per item in memory; the mock tenant has the units capability off, so products carry none.
    // JUSTIFICATION-A3: RetailApi gained three methods; the mock must implement them, and nothing existing carries packs.
    listPacks: (productId) => run(() => packs.get(find(productId).id) ?? []),

    addPack: (productId, body) =>
      run(() => {
        const p = find(productId);
        const unit = unitRows.find((u) => u.id === body.unit_id);
        if (!unit) refuse(422, 'validation_failed', 'No such unit.');
        if (unit?.name === p.unit) refuse(422, 'pack_unit_is_base_unit', 'A pack must be a different unit from the one the item is counted in.');
        const rows = packs.get(p.id) ?? [];
        if (rows.some((k) => k.unit_id === body.unit_id)) refuse(409, 'duplicate_pack', 'The item has a pack of this unit already.');
        const pack: Pack = { id: nextId('k'), product_id: p.id, unit_id: body.unit_id, unit: unit?.name ?? '', factor: body.factor, active: true, version: 1 };
        packs.set(p.id, [...rows, pack]);
        return pack;
      }),

    updatePack: (productId, unitId, version, body) =>
      run(() => {
        const rows = packs.get(find(productId).id) ?? [];
        const pack = rows.find((k) => k.unit_id === unitId);
        if (!pack) refuse(404, 'not_found', 'No such pack.');
        if (pack && pack.version !== version) refuse(409, 'version_conflict', 'The pack changed since it was read.');
        const next: Pack = { ...(pack as Pack), ...(body.factor === undefined ? {} : { factor: body.factor }), ...(body.active === undefined ? {} : { active: body.active }), version: (pack?.version ?? 0) + 1 };
        packs.set(productId, rows.map((k) => (k.unit_id === unitId ? next : k)));
        return next;
      }),

    importProducts: (csv, dryRun) =>
      run(() => {
        const lines = csv.split(/\r?\n/).filter((l) => l.trim() !== '');
        const sep = lines[0]?.includes('\t') ? '\t' : ',';
        const head = (lines[0] ?? '').split(sep).map((h) => h.trim().toLowerCase().replace(/[\s-]+/g, '_'));
        for (const need of ['code', 'description', 'category', 'unit', 'sell_price']) {
          if (!head.includes(need)) refuse(422, 'validation_failed', `The file needs a column called ${need.replace('_', ' ')}.`);
        }
        if (head.includes('cost_price') && !profitAccess) refuse(422, 'cost_not_allowed', 'The cost price column needs permission to see costs. Take that column out of the file and try again.');
        const seen = new Set<string>();
        const rows = lines.slice(1).map((l, i) => {
          const c = l.split(sep).map((x) => x.trim());
          const get = (n: string) => c[head.indexOf(n)] ?? '';
          const code = get('code');
          const base = { line: i + 2, code, description: get('description') };
          if (!/^\d+$/.test(get('sell_price').replace(/,/g, ''))) return { ...base, outcome: 'error', message: 'The sell price must be a whole number, for example 12000.' };
          if ([...products.values()].some((p) => p.code.toLowerCase() === code.toLowerCase())) return { ...base, outcome: 'skipped', message: 'An item with this code exists already.' };
          if (seen.has(code.toLowerCase())) return { ...base, outcome: 'skipped', message: 'This code is repeated in the file; the first row is used.' };
          seen.add(code.toLowerCase());
          return { ...base, outcome: 'added', message: dryRun ? 'Would be added.' : 'Added.' };
        });
        const count = (o: string) => rows.filter((r) => r.outcome === o).length;
        if (!dryRun && count('error') > 0) refuse(422, 'import_has_errors', `${count('error')} rows have a problem. Nothing was added. Run the check to see which rows, fix them and try again.`);
        if (!dryRun) {
          lines.slice(1).forEach((l) => {
            const c = l.split(sep).map((x) => x.trim());
            const get = (n: string) => c[head.indexOf(n)] ?? '';
            if ([...products.values()].some((p) => p.code.toLowerCase() === get('code').toLowerCase())) return;
            const id = nextId('b');
            products.set(id, { id, code: get('code'), description: get('description'), category: get('category'), unit: get('unit'), costMinor: 0, sellMinor: Number(get('sell_price').replace(/,/g, '')), active: true, version: 1 });
          });
        }
        return { dry_run: dryRun, rows_read: rows.length, added: count('added'), skipped: count('skipped'), errors: count('error'), categories_created: [], units_created: [], rows };
      }),

    createCategory: (name) =>
      run(() => {
        clash(cats, '', name, 'duplicate_category', 'category');
        const c = { id: nextId('c'), name: name.trim(), active: true, version: 1 };
        cats.push(c);
        return refreshed(c, 'category') as Category;
      }),

    updateCategory: (id, version, body) =>
      run(() => {
        const c = cats.find((x) => x.id === id);
        if (!c) return refuse(404, 'not_found', 'That could not be found.');
        staleRow(c, version);
        if (body.name !== undefined) {
          clash(cats, id, body.name, 'duplicate_category', 'category');
          for (const p of products.values()) if (p.category === c.name) p.category = body.name.trim();
          c.name = body.name.trim();
        }
        c.active = body.active ?? c.active;
        c.version += 1;
        return refreshed(c, 'category') as Category;
      }),

    listUnits: () => delay(unitRows.map((u) => refreshed(u, 'unit') as Unit)),

    createUnit: (name) =>
      run(() => {
        clash(unitRows, '', name, 'duplicate_unit', 'unit');
        const u = { id: nextId('d'), name: name.trim(), active: true, version: 1 };
        unitRows.push(u);
        return refreshed(u, 'unit') as Unit;
      }),

    updateUnit: (id, version, body) =>
      run(() => {
        const u = unitRows.find((x) => x.id === id);
        if (!u) return refuse(404, 'not_found', 'That could not be found.');
        staleRow(u, version);
        if (body.name !== undefined) {
          clash(unitRows, id, body.name, 'duplicate_unit', 'unit');
          for (const p of products.values()) if (p.unit === u.name) p.unit = body.name.trim();
          u.name = body.name.trim();
        }
        u.active = body.active ?? u.active;
        u.version += 1;
        return refreshed(u, 'unit') as Unit;
      }),

    listStockAllBranches: ({ query, categoryId: category, negativeOnly, level }) =>
      delay({
        branches: MOCK_BRANCHES.map((b) => ({ id: b.id, code: b.code, name: b.name, head_office: b.is_head_office })),
        items: [...products.values()].filter((p) => matches(p, query) && (!category || categoryId(p.category) === category)).map((p): AllBranchesRow => {
          const balancesMilli = MOCK_BRANCHES.map((b) => bal(b.id, p.id));
          return cost({
            product_id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit,
            total_qty: fromMilli(balancesMilli.reduce((x, y) => x + y, 0)), negative: balancesMilli.some((m) => m < 0), sell_minor: p.sellMinor,
            balances: MOCK_BRANCHES.map((b, i) => ({ branch_id: b.id, qty: fromMilli(balancesMilli[i] ?? 0), negative: (balancesMilli[i] ?? 0) < 0 })),
          }, { cost_minor: p.costMinor });
        }).filter((r) => (!negativeOnly || r.negative) && atLevel(Number(r.total_qty), level)),
      }),

    listStock: ({ branchId, query, categoryId: category, negativeOnly, level }) =>
      delay([...products.values()].filter((p) => matches(p, query) && (!category || categoryId(p.category) === category)).map((p): StockRow => {
        const milli = bal(branchId, p.id);
        return cost({ product_id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit, qty: fromMilli(milli), negative: milli < 0, sell_minor: p.sellMinor }, { cost_minor: p.costMinor });
      }).filter((r) => (!negativeOnly || r.negative) && atLevel(Number(r.qty), level))),

    listSales: ({ cursor, ...q }) => {
      const wanted = [...sales].reverse().filter((x) => saleMatches(x, q));
      const start = cursor ? Number(cursor) : 0;
      const items = wanted.slice(start, start + 50).map(visibleSale);
      return delay({ items, ...(start + 50 < wanted.length ? { next_cursor: String(start + 50) } : {}) });
    },

    salesSummary: (q) => delay(summariseSales(sales.filter((x) => saleMatches(x, q)), q.level, profitAccess)),

    getSale: (id) => {
      const found = sales.find((x) => x.id === id);
      return found ? delay(visibleSale(found)) : Promise.reject(new RetailError('That could not be found.', 404, 'not_found'));
    },

    paySale: (saleId, body, key) =>
      run(() => once(`pay|${key}`, { saleId, body }, () => {
        const sale = sales.find((x) => x.id === saleId) ?? refuse(404, 'not_found', 'That could not be found.');
        if (sale.payment_method !== 'credit' || sale.status !== 'completed') refuse(422, 'sale_not_payable', 'Only a completed credit sale takes payments.');
        if (body.amount_minor > (sale.balance_minor ?? 0)) refuse(422, 'payment_exceeds_balance', 'The payment is larger than the balance.');
        const paidOn = body.paid_on ?? today();
        if (paidOn > today() || paidOn < (sale.sale_date ?? '')) refuse(422, 'validation_failed', 'A payment is dated between the sale and today.');
        sale.paid_minor = (sale.paid_minor ?? 0) + body.amount_minor;
        sale.balance_minor = (sale.total_minor ?? 0) - sale.paid_minor;
        return { payment: { id: nextId('pay'), sale_id: saleId, amount_minor: body.amount_minor, method: body.method, paid_on: paidOn, currency: 'UGX' }, sale_paid_minor: sale.paid_minor, sale_balance_minor: sale.balance_minor };
      })),

    voidSale: (saleId, reason) =>
      run(() => {
        const sale = sales.find((x) => x.id === saleId) ?? refuse(404, 'not_found', 'That could not be found.');
        if (sale.status === 'voided') refuse(409, 'sale_voided', 'This sale has been voided already.');
        if (sale.payment_method === 'credit' && (sale.paid_minor ?? 0) > 0) refuse(422, 'sale_has_payments', 'A credit sale with payments against it cannot be voided.');
        (sale.lines ?? []).forEach((l) => move(sale.branch_id ?? BRANCH_A, l.product_id ?? '', toMilli(l.qty ?? '0')));
        sale.status = 'voided';
        sale.void_reason = reason.trim();
        return visibleSale(sale);
      }),

    listCustomers: () =>
      delay(customers.map((c) => ({ ...c, ...figures(c.id), balance_minor: sales.filter((x) => x.payment_method === 'credit' && x.status === 'completed' && x.buyer_name === c.name).reduce((sum, x) => sum + (x.balance_minor ?? 0), 0) }))),
    listCustomerPage: ({ query, cursor }) => {
      const q = (query ?? '').trim().toLowerCase();
      const all = customers
        .filter((c) => !q || (c.name ?? '').toLowerCase().includes(q))
        .sort((a, b) => (a.name ?? '').toLowerCase().localeCompare((b.name ?? '').toLowerCase()));
      const start = cursor ? Number(cursor) : 0;
      const items = all.slice(start, start + CUSTOMER_PAGE).map((c) => ({
        ...c, ...figures(c.id),
        balance_minor: sales.filter((x) => x.payment_method === 'credit' && x.status === 'completed' && x.customer_id === c.id).reduce((sum, x) => sum + (x.balance_minor ?? 0), 0),
      }));
      return delay({ items, ...(start + CUSTOMER_PAGE < all.length ? { next_cursor: String(start + CUSTOMER_PAGE) } : {}) });
    },
    listSuppliers: () => delay([...suppliers]),
    createSupplier: (body) =>
      run(() => {
        clash(suppliers as { id: string; name: string }[], '', body.name, 'duplicate_supplier', 'supplier');
        const s: Supplier = { id: nextId('s'), name: body.name.trim(), active: true, version: 1, ...(body.contact?.trim() ? { contact: body.contact.trim() } : {}) };
        suppliers.push(s);
        return s;
      }),

    updateSupplier: (id, version, body) =>
      run(() => {
        const s = suppliers.find((x) => x.id === id);
        if (!s) return refuse(404, 'not_found', 'That could not be found.');
        staleRow(s, version);
        if (body.name !== undefined) {
          clash(suppliers as { id: string; name: string }[], id, body.name, 'duplicate_supplier', 'supplier');
          s.name = body.name.trim();
        }
        if (body.contact !== undefined) s.contact = body.contact.trim() || undefined;
        s.active = body.active ?? s.active;
        s.version = (s.version ?? 1) + 1;
        return { ...s };
      }),

    createCustomer: (body) =>
      run(() => {
        const c: Customer = { id: nextId('c'), name: body.name.trim(), version: 1, ...(body.contact?.trim() ? { contact: body.contact.trim() } : {}) };
        customers.push(c);
        return c;
      }),

    updateCustomer: (id, version, body) =>
      run(() => {
        const c = customers.find((x) => x.id === id);
        if (!c) return refuse(404, 'not_found', 'That could not be found.');
        staleRow(c, version);
        if (body.name !== undefined) c.name = body.name.trim();
        if (body.contact !== undefined) c.contact = body.contact.trim() || undefined;
        c.version = (c.version ?? 1) + 1;
        return { ...c };
      }),

    setCustomerPricing: (id, version, body) =>
      run(() => {
        if (caps.value['price_lists'] !== true) refuse(404, 'capability_not_enabled', 'This capability is not enabled for the tenant.');
        const c = customers.find((x) => x.id === id);
        if (!c) return refuse(404, 'not_found', 'That could not be found.');
        staleRow(c, version);
        c.customer_group_id = body.customer_group_id;
        c.price_list_id = body.price_list_id;
        c.version = (c.version ?? 1) + 1;
        return { ...c };
      }),

    createSale: (body, key) =>
      run(() => once(`sale|${key}`, body, (): Sale => {
      const branchId = body.branch_id ?? BRANCH_A;
      body.lines.forEach((l) => guard(branchId, l.product_id, toMilli(l.qty)));
      // Customer accounts on: a credit sale names a buyer from the list and stays within the buyer's limit. The mock judges the
      // limit on the typed or retail prices; the server judges the resolved total.
      const estimate = body.lines.reduce((sum, l) => sum + lineTotal(l.unit_price_minor ?? find(l.product_id).sellMinor, toMilli(l.qty)), 0);
      const { dueDate } = forSale(body, estimate, body.sale_date ?? today());
      let costTotal = 0;
      const priced = caps.value['price_lists'] === true;
      const lines: SaleLine[] = body.lines.map((l, n) => {
        const p = find(l.product_id);
        const milli = toMilli(l.qty);
        // With price lists on, the line takes the list price (or the retail price) and says so (spec 3.2).
        const hit = priced ? resolveFor(body.customer_id, body.price_list_id, p.id, undefined, l.qty, body.sale_date ?? today()) : undefined;
        const resolved = hit?.price ?? p.sellMinor;
        const price = l.unit_price_minor ?? resolved;
        const overridden = priced && l.unit_price_minor !== undefined && l.unit_price_minor !== resolved;
        move(branchId, p.id, -milli);
        costTotal += lineTotal(p.costMinor, milli);
        const how = priced
          ? { price: { source: overridden ? 'override' : hit ? 'list' : 'retail', resolved_unit_price_minor: resolved, ...(hit ? { price_list_id: hit.list.id, price_list: hit.list.name, break_min_qty: hit.minQty } : {}), ...(overridden ? { override_reason: l.price_override_reason } : {}) } }
          : {};
        return cost({ id: nextId('l'), line_no: n + 1, product_id: p.id, code: p.code, description: p.description, qty: l.qty, unit_price_minor: price, line_total_minor: lineTotal(price, milli), ...how },
          { unit_cost_minor: p.costMinor, line_cost_minor: lineTotal(p.costMinor, milli) });
      });
      const total = lines.reduce((sum, l) => sum + (l.line_total_minor ?? 0), 0);
      const credit = body.payment_method === 'credit';
      const buyer = body.buyer_name ?? customers.find((c) => c.id === body.customer_id)?.name;
      const sale = cost({
        id: nextId('5'), sale_no: `S-${String(seq).padStart(6, '0')}`, branch_id: branchId, sale_date: body.sale_date ?? today(),
        payment_method: body.payment_method, status: 'completed', currency: 'UGX',
        ...(buyer ? { buyer_name: buyer } : {}), ...(body.customer_id ? { customer_id: body.customer_id } : {}), ...((dueDate ?? body.due_date) ? { due_date: dueDate ?? body.due_date } : {}),
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

    // Stock value is read with retail.stock.read; its cost and profit columns need retail.profit.read.
    // Without a branch it covers every branch, with a total per branch, as the server does (#144).
    valuation: ({ branchId, asOf }) => {
      const bp = (profit: number, atCost: number): { expected_profit_bp?: number } => (atCost > 0 ? { expected_profit_bp: Math.round((profit * 10000) / atCost) } : {});
      const ids = branchId ? [branchId] : MOCK_BRANCHES.map((b) => b.id);
      const rows = ids.flatMap((id) => [...products.values()].map((p): ValuationRow => {
        const milli = bal(id, p.id);
        const sells = lineTotal(p.sellMinor, milli);
        const atCost = lineTotal(p.costMinor, milli);
        return cost({ branch_id: id, product_id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit, qty: fromMilli(milli), negative: milli < 0, sell_minor: p.sellMinor, expected_sales_minor: sells },
          { cost_minor: p.costMinor, value_at_cost_minor: atCost, expected_profit_minor: sells - atCost, ...bp(sells - atCost, atCost) });
      }));
      const sum = (pick: (r: ValuationRow) => number | undefined, from: ValuationRow[] = rows) => from.reduce((s, r) => s + (pick(r) ?? 0), 0);
      const totals = (own: ValuationRow[]) => {
        const sells = sum((r) => r.expected_sales_minor, own);
        const atCost = sum((r) => r.value_at_cost_minor, own);
        return cost({ expected_sales_minor: sells }, { value_at_cost_minor: atCost, expected_profit_minor: sells - atCost, ...bp(sells - atCost, atCost) });
      };
      const branches = ids.map((id) => ({ branch_id: id, ...totals(rows.filter((r) => r.branch_id === id)) }));
      const categories = CATEGORIES.map((c) => ({ category_id: c.id, category: c.name, ...totals(rows.filter((r) => r.category_id === c.id)) }));
      const v: Valuation = { as_of: asOf ?? today(), currency: 'UGX', rows, branches, categories, ...totals(rows) };
      return delay(v);
    },

    stockedBranches: () => delay([BRANCH_A, BRANCH_B].filter((b) => [...products.values()].some((p) => bal(b, p.id) > 0))),

    branchRoles: () => delay(MOCK_BRANCHES.map((b) => ({ branch_id: b.id, code: b.code, name: b.name, role: roles.get(b.id) ?? 'both', can_sell: (roles.get(b.id) ?? 'both') !== 'store' }))),

    setBranchRole: (branchId, role) => {
      const b = MOCK_BRANCHES.find((x) => x.id === branchId);
      roles.set(branchId, role);
      return delay({ branch_id: branchId, code: b?.code, name: b?.name, role, can_sell: role !== 'store' });
    },

    capabilities: () => delay(read()),

    // The server's rule (RetailCapabilityGroup): a profile writes its preset first, then the flags sent win.
    changeCapabilities: (body, version) => {
      if (version !== caps.version) return Promise.reject(new RetailError(retailMessage({ code: 'version_conflict' }, 409), 409, 'version_conflict'));
      const next: Record<string, unknown> = { ...caps.value };
      if (body.profile) {
        next.profile = body.profile;
        for (const key of MOCK_FLAGS) next[key] = body.profile === 'shop' ? false : MOCK_TRADE_PRESET.includes(key) ? true : next[key];
      }
      for (const key of MOCK_FLAGS) if (typeof body[key] === 'boolean') next[key] = body[key];
      caps = { value: next, version: caps.version + 1 };
      return delay(read());
    },

    dailyProfit: ({ branchId, from, to }) => {
      if (!profitAccess) return Promise.reject(new RetailError(retailMessage({ code: 'permission_denied' }, 403), 403, 'permission_denied'));
      const ids = branchId ? [branchId] : MOCK_BRANCHES.map((b) => b.id);
      const rows: DailyProfitRow[] = [];
      for (let d = new Date(`${from}T00:00:00Z`); d <= new Date(`${to}T00:00:00Z`) && rows.length < 366 * ids.length; d.setUTCDate(d.getUTCDate() + 1)) {
        const date = d.toISOString().slice(0, 10);
        for (const id of ids) {
          const own = sales.filter((s) => s.branch_id === id && s.sale_date === date);
          const salesMinor = own.reduce((s, x) => s + (x.total_minor ?? 0), 0);
          const costMinor = own.reduce((s, x) => s + x.costTotal, 0);
          const usageMinor = id === (branchId ?? BRANCH_A) ? (usageCostByDay.get(date) ?? 0) : 0;
          if (salesMinor === 0 && costMinor === 0 && usageMinor === 0 && ids.length > 1) continue;
          rows.push({ branch_id: id, date, sales_minor: salesMinor, cost_of_sales_minor: costMinor, gross_profit_minor: salesMinor - costMinor, usage_cost_minor: usageMinor, stocktake_difference_minor: 0, profit_minor: salesMinor - costMinor - usageMinor });
        }
      }
      const sum = (pick: (r: DailyProfitRow) => number | undefined) => rows.reduce((s, r) => s + (pick(r) ?? 0), 0);
      const report: DailyProfit = {
        from, to, currency: 'UGX', rows, sales_minor: sum((r) => r.sales_minor), cost_of_sales_minor: sum((r) => r.cost_of_sales_minor),
        usage_cost_minor: sum((r) => r.usage_cost_minor), stocktake_difference_minor: 0, profit_minor: sum((r) => r.profit_minor),
      };
      return delay(report);
    },

    // Stock moves between branches at the source's cost: nothing gained or lost, refused beyond the
    // source balance, voided only while the destination still holds what arrived.
    createTransfer: (body, key) =>
      run(() => once(`transfer|${key}`, body, (): Transfer => {
        const from = body.from_branch_id ?? BRANCH_A;
        if (from === body.to_branch_id) refuse(422, 'validation_failed', 'Pick a different branch to move the stock to.');
        body.lines.forEach((l) => guard(from, l.product_id, toMilli(l.qty)));
        const lines = body.lines.map((l, n) => {
          const p = find(l.product_id);
          move(from, p.id, -toMilli(l.qty));
          if (!twoStep()) move(body.to_branch_id, p.id, toMilli(l.qty));
          return { line_no: n + 1, product_id: p.id, code: p.code, description: p.description, qty: fromMilli(toMilli(l.qty)), unit_cost_minor: p.costMinor, line_cost_minor: lineTotal(p.costMinor, toMilli(l.qty)) };
        });
        const t: Transfer = {
          id: nextId('9'), from_branch_id: from, to_branch_id: body.to_branch_id, transfer_date: body.transfer_date ?? today(),
          status: twoStep() ? 'in_transit' : 'completed', currency: 'UGX', lines, cost_total_minor: lines.reduce((sum, l) => sum + l.line_cost_minor, 0),
          created_at: new Date().toISOString(), ...(body.note ? { note: body.note } : {}),
        };
        transfers.unshift(t);
        return visibleTransfer(t);
      })),

    listTransfers: ({ branchId, status }) =>
      delay({ items: transfers.filter((t) => (!branchId || t.from_branch_id === branchId || t.to_branch_id === branchId) && (!status || t.status === status)).map(visibleTransfer) }),

    getTransfer: (id) => {
      const t = transfers.find((x) => x.id === id);
      return t ? delay(visibleTransfer(t)) : Promise.reject(new RetailError('That could not be found.', 404, 'not_found'));
    },

    voidTransfer: (id, reason) =>
      run(() => {
        const t = transfers.find((x) => x.id === id);
        if (!t) return refuse(404, 'not_found', 'That could not be found.');
        if (t.status === 'voided') refuse(409, 'transfer_voided', 'This transfer has been voided already.');
        if (t.status === 'cancelled') refuse(409, 'transfer_cancelled', 'This transfer was cancelled while it was on its way, so there is nothing to void.');
        if (t.status === 'in_transit') refuse(409, 'transfer_in_transit', 'This transfer is still on its way. Cancel it, or wait for it to be received.');
        const arrived = (l: { qty?: string; received_qty?: string }) => toMilli(l.received_qty ?? l.qty ?? '0');
        const gone = (t.lines ?? []).filter((l) => bal(t.to_branch_id ?? '', l.product_id ?? '') < arrived(l));
        if (gone.length > 0) {
          refuse(422, 'transfer_stock_moved', `The destination branch no longer holds all of ${gone.map((l) => l.code).join(', ')} that this transfer brought, so it cannot be voided. Move the stock back with a new transfer instead.`);
        }
        (t.lines ?? []).forEach((l) => {
          move(t.to_branch_id ?? '', l.product_id ?? '', -arrived(l));
          move(t.from_branch_id ?? '', l.product_id ?? '', toMilli(l.qty ?? '0'));
        });
        Object.assign(t, { status: 'voided', voided_at: new Date().toISOString(), void_reason: reason });
        return visibleTransfer(t);
      }),

    // The two-step transfer: the destination receives what arrived (less is a shortage, valued at the
    // source cost), or the source takes it back; each is refused unless the transfer is on its way.
    receiveTransfer: (id, lines, key) =>
      run(() => once(`receive|${key}`, { id, lines }, (): Transfer => {
        const t = onItsWay(transfers, id);
        const got = new Map((lines ?? []).map((l) => [l.product_id, toMilli(l.qty)]));
        if (lines && (t.lines ?? []).some((l) => !got.has(l.product_id ?? ''))) refuse(422, 'validation_failed', 'Say how much of every item arrived, even if it is none.');
        let shortage = 0;
        t.lines = (t.lines ?? []).map((l) => {
          const sent = toMilli(l.qty ?? '0');
          const arrived = lines ? (got.get(l.product_id ?? '') ?? 0) : sent;
          if (arrived > sent) refuse(422, 'received_exceeds_dispatched', 'More arrived than was dispatched for one or more items. Record only what was sent.');
          move(t.to_branch_id ?? '', l.product_id ?? '', arrived);
          shortage += lineTotal(l.unit_cost_minor ?? 0, sent - arrived);
          return { ...l, received_qty: fromMilli(arrived) };
        });
        Object.assign(t, { status: 'received', received_at: new Date().toISOString(), shortage_cost_minor: shortage });
        return visibleTransfer(t);
      })),

    cancelTransfer: (id, reason, key) =>
      run(() => once(`cancel|${key}`, { id, reason }, (): Transfer => {
        const t = onItsWay(transfers, id);
        (t.lines ?? []).forEach((l) => move(t.from_branch_id ?? '', l.product_id ?? '', toMilli(l.qty ?? '0')));
        Object.assign(t, { status: 'cancelled', voided_at: new Date().toISOString(), void_reason: reason });
        return visibleTransfer(t);
      })),

    stockByLocation: ({ query, categoryId: category, negativeOnly }) => {
      const order = { store: 0, shop: 1, both: 2 } as const;
      const columns = [...MOCK_BRANCHES].sort((a, b) => order[roleOf(a.id)] - order[roleOf(b.id)]);
      const groups = [...new Set(columns.map((b) => roleOf(b.id)))];
      const open = transfers.filter((t) => t.status === 'in_transit');
      const items = [...products.values()].filter((p) => matches(p, query) && (!category || categoryId(p.category) === category)).map((p): LocationRow => {
        const held = columns.map((b) => bal(b.id, p.id));
        const way = open.flatMap((t) => (t.lines ?? []).filter((l) => l.product_id === p.id));
        const wayMilli = way.reduce((x, l) => x + toMilli(l.qty ?? '0'), 0);
        const total = held.reduce((x, y) => x + y, 0);
        const row: LocationRow = {
          product_id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit,
          balances: columns.map((b, i) => ({ branch_id: b.id, qty: fromMilli(held[i] ?? 0), negative: (held[i] ?? 0) < 0, ...(profitAccess ? { value_minor: lineTotal(p.costMinor, held[i] ?? 0) } : {}) })),
          subtotals: groups.map((r) => ({ role: r, qty: fromMilli(columns.reduce((x, b, i) => x + (roleOf(b.id) === r ? (held[i] ?? 0) : 0), 0)) })),
          in_transit_qty: fromMilli(wayMilli), combined_qty: fromMilli(total + wayMilli), negative: held.some((m) => m < 0), sell_minor: p.sellMinor,
        };
        if (!profitAccess) return row;
        const value = held.reduce((x, m) => x + lineTotal(p.costMinor, m), 0);
        const transit = way.reduce((x, l) => x + (l.line_cost_minor ?? 0), 0);
        return { ...row, cost_minor: p.costMinor, value_minor: value, in_transit_value_minor: transit, combined_value_minor: value + transit };
      }).filter((r) => !negativeOnly || r.negative);
      const everyone = [...products.values()];
      const worth = (branch: string) => everyone.reduce((y, p) => y + lineTotal(p.costMinor, bal(branch, p.id)), 0);
      const value = columns.reduce((x, b) => x + worth(b.id), 0);
      const transit = open.reduce((x, t) => x + (t.cost_total_minor ?? 0), 0);
      const report: StockByLocation = {
        currency: 'UGX',
        branches: columns.map((b) => ({ id: b.id, code: b.code, name: b.name, head_office: b.is_head_office, role: roleOf(b.id) })),
        items,
        totals: profitAccess
          ? {
              by_branch: columns.map((b) => ({ branch_id: b.id, value_minor: worth(b.id) })),
              by_role: groups.map((r) => ({ role: r, value_minor: columns.filter((b) => roleOf(b.id) === r).reduce((x, b) => x + worth(b.id), 0) })),
              value_minor: value, in_transit_value_minor: transit, combined_value_minor: value + transit,
            }
          : { by_branch: [], by_role: [] },
      };
      return delay(report);
    },

    transfersInFlight: () => {
      const open = transfers.filter((t) => t.status === 'in_transit').reverse();
      const day = 86_400_000;
      const worth = open.reduce((x, t) => x + (t.cost_total_minor ?? 0), 0);
      const report: InFlightReport = {
        currency: 'UGX',
        items: open.map((t) => ({
          id: t.id, from_branch_id: t.from_branch_id, to_branch_id: t.to_branch_id, transfer_date: t.transfer_date, dispatched_at: t.created_at,
          age_days: Math.max(0, Math.floor((Date.parse(today()) - Date.parse(t.transfer_date ?? today())) / day)), note: t.note, lines: visibleTransfer(t).lines,
          ...(profitAccess ? { cost_total_minor: t.cost_total_minor } : {}),
        })),
        ...(profitAccess ? { in_transit_value_minor: worth, clearing_minor: worth, reconciled: true } : {}),
      };
      return delay(report);
    },
  };
  self = mock;
  return mock;
}

/** The list's filters (branch, dates, buyer, method, item, status, owing) applied to one stored sale. */
function saleMatches(x: Sale, { branchId, from, to, buyer, paymentMethod, productId, status, owing }: Omit<SalesQuery, 'cursor' | 'signal'>): boolean {
  return (!branchId || x.branch_id === branchId) && (!from || (x.sale_date ?? '') >= from) && (!to || (x.sale_date ?? '') <= to)
    && (!buyer || (x.buyer_name ?? '').toLowerCase().includes(buyer.trim().toLowerCase()))
    && (!paymentMethod || x.payment_method === paymentMethod) && (!productId || (x.lines ?? []).some((l) => l.product_id === productId))
    && (!status || x.status === status)
    && (!owing || (x.payment_method === 'credit' && x.status === 'completed' && (owing === 'paid' ? (x.balance_minor ?? 0) <= 0
      : (x.balance_minor ?? 0) > 0 && (owing === 'owing' || (x.due_date ?? '9999') < businessToday()))));
}

/** A sale as the session may see it: cost and profit only with retail.profit.read. */
function visibleSale(x: Sale & { costTotal: number }): Sale {
  const { costTotal: _hidden, ...sale } = x;
  if (profitAccess) return sale;
  const { cost_total_minor: _cost, profit_minor: _profit, lines, ...rest } = sale;
  return { ...rest, lines: (lines ?? []).map(({ unit_cost_minor: _unit, line_cost_minor: _line, ...l }) => l) };
}

/** A few fabricated sales so the History screens have something to show: cash, and credit paid, part paid and overdue. */
function seedSales(into: (Sale & { costTotal: number })[]): void {
  const day = (back: number) => daysBefore(businessToday(), back);
  const make = (n: number, back: number, branch: string, productIndex: number, qty: number, method: 'cash' | 'credit', buyer?: string, paid = 0, dueIn?: number): Sale & { costTotal: number } => {
    const p = PRODUCTS[productIndex] as MockProduct;
    const total = p.sellMinor * qty;
    const costTotal = p.costMinor * qty;
    const credit = method === 'credit';
    const sale: Sale = {
      id: `5eed0000-0000-4000-8000-${String(n).padStart(12, '0')}`, sale_no: `S-${String(900000 + n)}`, branch_id: branch, sale_date: day(back),
      payment_method: method, status: 'completed', currency: 'UGX', ...(buyer ? { buyer_name: buyer, buyer_contact: '+256700000001' } : {}),
      ...(credit && dueIn !== undefined ? { due_date: day(-dueIn) } : {}),
      lines: [{ id: `5eed1000-0000-4000-8000-${String(n).padStart(12, '0')}`, line_no: 1, product_id: p.id, code: p.code, description: p.description, qty: `${qty}.000`, unit_price_minor: p.sellMinor, line_total_minor: total, unit_cost_minor: p.costMinor, line_cost_minor: costTotal }],
      total_minor: total, paid_minor: credit ? paid : total, balance_minor: credit ? total - paid : 0, cost_total_minor: costTotal, profit_minor: total - costTotal,
    };
    return { ...sale, costTotal };
  };
  into.push(
    make(1, 9, BRANCH_A, 2, 3, 'cash'),
    make(2, 8, BRANCH_B, 4, 2, 'credit', 'Test Buyer 01', 0, -3),
    make(3, 6, BRANCH_A, 0, 1, 'credit', 'Test Buyer 02', 100000, 2),
    make(4, 3, BRANCH_A, 5, 4, 'credit', 'Test Buyer 02', 18000, 10),
    make(5, 1, BRANCH_B, 3, 2, 'cash'),
  );
}

/** The transfer, which must be on its way: a receipt or a cancel of any other is the server's 409. */
function onItsWay(transfers: Transfer[], id: string): Transfer {
  const t = transfers.find((x) => x.id === id);
  if (!t) return refuse(404, 'not_found', 'That could not be found.');
  if (t.status !== 'in_transit') refuse(409, 'transfer_not_in_transit', `Only a transfer that is on its way can be received or cancelled; this one is ${t.status}.`);
  return t;
}

/** A transfer as the session may see it: cost fields only with retail.profit.read. */
function visibleTransfer(t: Transfer): Transfer {
  if (profitAccess) return { ...t };
  const { cost_total_minor: _total, shortage_cost_minor: _short, lines, ...rest } = t;
  return { ...rest, lines: (lines ?? []).map(({ unit_cost_minor: _unit, line_cost_minor: _line, ...l }) => l) };
}
