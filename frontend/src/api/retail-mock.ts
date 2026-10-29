import type {
  ApprovalWaiting,
  Barcode,
  AllBranchesRow, Category, EntryConfirmation, EntryNorms, NameHints, CapabilitiesRead, Family, Pack, Customer, InFlightReport, LocationRow, PriceChange, DailyProfit, DailyProfitRow, Product, Purchase, RetailApi, Sale, SaleLine, SimilarProduct, StockMovement, Stocktake, StockByLocation, StockRow,
  Supplier, Transfer, Unit, Usage, UsualPackRequest, Valuation, ValuationRow,
} from './retail';
import type { SalesQuery } from './retail';
import { NO_SUPPLIER, RetailError, businessToday, daysBefore } from './retail';
import { summariseChain } from './retail-chain-mock';
import { summarisePurchases, summariseStock } from './retail-grouping-mock';
import { retailMessage } from './retail-errors';
import { foldText } from '../app/ui/fold';
import type { Me } from './client';
import { createCashbookMock } from './retail-cashbook-mock';
import { createPricingMock } from './retail-pricing-mock';
import { createAccountsMock } from './retail-accounts-mock';
import { createDocumentsMock } from './retail-documents-mock';
import { createReturnsMock } from './retail-returns-mock';
import { createPurchasingMock } from './retail-purchasing-mock';
import { barcodeKey, checkBarcode, scanKeys, shopEan } from '../areas/staff/retail/barcode-state';
import { bigDrop } from '../areas/staff/retail/maths';
import { MAX_VARIANTS, cleanOption, planVariants, variantDescription, variantKey, variantLabel } from './retail-variants';

// Fabricated retail data for VITE_RETAIL_MOCK and for tests: invented products, branches and
// balances, held in memory, with the REAL snake_case shapes of docs/sdd/07-api-design.md section
// 7.11.20 (cost, cost snapshot and profit fields are absent unless the session holds
// retail.profit.read; a repeated Idempotency-Key replays the first answer; a sale beyond the
// branch balance is refused with the server's insufficient_stock code). Nothing here is real.
// Quantities are integer thousandths inside the store.

const BRANCH_A = '00000000-0000-4000-8000-0000000000a1';
const BRANCH_B = '00000000-0000-4000-8000-0000000000a2';
/** The one fabricated salesperson of every mock sale (issue #341). */
const MOCK_SELLER = { id: '00000000-0000-4000-8000-0000000005e1', name: 'Test Seller' };
const MOCK_BRANCHES = [
  { id: BRANCH_A, code: 'BR1', name: 'Test Branch A', is_head_office: true },
  { id: BRANCH_B, code: 'BR2', name: 'Test Branch B', is_head_office: false },
];

// The seeded sales role: its sale permissions. Since V74 (ADR-055, the owner's decision of 21 October 2026) the cash book is
// the till's, given per person: the cashier below is a seller who was also given "Can close the till and do the banking", which
// records savings (the standard amount only), banking and expenses and reads the lists and reports (ADR-022 decision 13).
const SALES_PERMISSIONS = [
  'retail.sale.create', 'retail.sale.read', 'retail.stock.read', 'retail.usage.report', 'retail.customer.manage', 'retail.price.override',
  'retail.order.manage',
];
const TILL_PERMISSIONS = ['retail.savings.record', 'retail.banking.record', 'retail.expense.record', 'retail.cashbook.read', 'retail.till.close'];
const CASHIER_PERMISSIONS = [...SALES_PERMISSIONS, ...TILL_PERMISSIONS];
const ADMIN_PERMISSIONS = [
  ...SALES_PERMISSIONS, 'retail.catalogue.manage', 'retail.price.edit', 'retail.sale.void',
  'retail.stocktake.commit', 'retail.purchase.create', 'retail.stock.transfer', 'retail.profit.read', 'core.settings.manage', 'retail.branch_role.manage', 'retail.pricelist.manage', 'retail.pricelist.use', 'retail.credit.manage', 'retail.credit.write_off', 'retail.credit.override', 'retail.document.issue', 'retail.sale.return', 'retail.controls.read', 'retail.sale.all_sellers', 'core.billing.read',
  'retail.savings.record', 'retail.savings.overwrite', 'retail.banking.record', 'retail.expense.record', 'retail.expense.manage',
  'retail.withdrawal.record', 'retail.advance.create', 'retail.advance.repay', 'retail.cashbook.read', 'retail.cashbook.void', 'retail.payable.manage',
  'retail.till.close',
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
    // VITE_RETAIL_MOCK_CAPS lists switched-on capabilities for a dev run, so the menus and tiles of a wholesaler can be seen.
    ...(import.meta.env.VITE_RETAIL_MOCK_CAPS ? { module_capabilities: { retail: import.meta.env.VITE_RETAIL_MOCK_CAPS.split(',') } } : {}),
    // The role the staff menu shows under the person's name (issue #564).
    roles: [{ role_key: role === 'sales' ? 'sales' : role === 'cashier' ? 'cashier' : 'tenant_admin', role_name: role === 'sales' ? 'Seller' : role === 'cashier' ? 'Cashier' : 'Administrator' }],
  };
}

let profitAccess = true;
let transferReceipt = false;
let storeBranches: string[] = [];

/** Marks branches as stores (sells_goods false) for every mock instance: a store is the branch's flag, not a role (ADR-104). */
export function setMockStores(branchIds: string[]): void {
  storeBranches = branchIds;
}

/** Forces the two-step transfer on for every mock instance, besides the capability a mock instance's settings switch (ADR-033). */
export function setMockTransferReceipt(on: boolean): void {
  transferReceipt = on;
}

/**
 * Issue #343, ADR-091: the approval rules live on the server. In the mock, the action types named here (by a test, or by
 * VITE_RETAIL_MOCK_APPROVALS in development) wait for an administrator, answering as the server's 202 does, so the screens'
 * "Sent for approval" can be seen. None by default, as on a business that never switched one on.
 */
let waitingActions = new Set<string>((import.meta.env.VITE_RETAIL_MOCK_APPROVALS ?? '').split(',').filter(Boolean));

export function setMockApprovals(actionTypes: string[]): void {
  waitingActions = new Set(actionTypes);
}

/** The 202 of an action that waits in the mock, or null when it happens at once. */
export function mockWaiting(actionType: string, amountMinor: number, id: string): ApprovalWaiting | null {
  return waitingActions.has(actionType) ? { approval_id: id, action_type: actionType, amount_minor: amountMinor, currency: 'UGX' } : null;
}

/** The staff layout tells the mock what the session may see; the real API decides by itself. */
export function setMockProfitAccess(allowed: boolean): void {
  profitAccess = allowed;
}

// Issue #353: the mock keeps no entry history of its own, so no screen warns unless a test or a screenshot sets one here.
const entryNormsSet = new Map<string, Partial<EntryNorms>>();
/** Confirmed entry warnings, newest last, for the tests to read. */
export const mockEntryConfirmations: EntryConfirmation[] = [];

/** Sets (or with null clears) the fabricated history an item's entry warnings read (issue #353). */
export function setMockEntryNorms(productId: string, norms: Partial<EntryNorms> | null): void {
  if (norms) entryNormsSet.set(productId, norms);
  else entryNormsSet.delete(productId);
}

interface MockProduct {
  id: string; code: string; description: string; category: string; unit: string; costMinor: number; sellMinor: number; active?: boolean; version?: number;
  /** Issue #384: the item with sizes and colours this product is a variant of, its size, colour and place. */
  familyId?: string; size?: string; colour?: string; position?: number;
  /** Issue #384: the product's own photo. */
  photoId?: string;
}

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
/** A quantity without trailing zeros: 10.000 is 10. */
const plainQty = (q: string): string => (q.includes('.') ? q.replace(/0+$/, '').replace(/\.$/, '') : q);
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
const refuse = (status: number, code: string, detail: string, extra?: Record<string, unknown>): never => {
  throw new RetailError(retailMessage({ code, detail }, status), status, code, extra ? { code, detail, ...extra } : undefined);
};

/** The server's name key (issue #352): lower case, every run of spaces and punctuation one space. */
const nameKey = (text: string): string => text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/** Letters added, removed or changed to turn one text into the other. */
const editDistance = (a: string, b: string): number => {
  let previous = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const current = [i];
    for (let j = 1; j <= b.length; j++) {
      current[j] = Math.min((previous[j - 1] ?? 0) + (a[i - 1] === b[j - 1] ? 0 : 1), (previous[j] ?? 0) + 1, (current[j - 1] ?? 0) + 1);
    }
    previous = current;
  }
  return previous[b.length] ?? 0;
};

const MOCK_FLAGS = ['branch_roles', 'units', 'price_lists', 'customer_accounts', 'documents', 'purchasing', 'transfer_receipt', 'returns'] as const;
/** The switches each kind of business turns on (BusinessTypes.PRESETS, issue #424); MOCK_GOVERNED are the ones a kind sets. */
const MOCK_GOVERNED: readonly string[] = ['units', 'price_lists', 'customer_accounts', 'documents', 'purchasing', 'transfer_receipt'];
const MOCK_PRESETS: Record<string, readonly string[]> = {
  shops: [],
  stores_and_shops: ['transfer_receipt'],
  wholesale: ['units', 'price_lists', 'customer_accounts', 'documents', 'purchasing'],
  wholesale_and_shops: MOCK_GOVERNED,
};

/** Issue #493: PREFIX-0001, at least four digits, as bms_next_item_code makes it (V96). */
export function itemCode(prefix: string, n: number): string {
  return `${prefix}-${String(n).padStart(4, '0')}`;
}

/** The code start a name suggests, the rule of bms_category_prefix_base (V96): Bulbs BLB, TVs TV, Phone accessories PHA. */
export function prefixBase(name: string): string {
  const raw = name.split(/[^\p{L}]+/u).filter((w) => w !== '');
  const words: string[] = [];
  const acronyms: (string | null)[] = [];
  for (const w of raw) {
    const letters = foldText(w).toUpperCase().replace(/[^A-Z]/g, '');
    if (letters === '' || ['AND', 'OF', 'THE', 'FOR', 'WITH', 'NA'].includes(letters)) continue;
    const caps = w.replace(/[^A-Z]/g, '');
    words.push(letters);
    acronyms.push(caps.length >= 2 && (/[a-z]/.test(w) || w.length <= 3) ? caps : null);
  }
  if (words.length === 0) return 'CAT';
  let result: string;
  const first = words[0] ?? '';
  if (words.length === 1) {
    result = acronyms[0] ? acronyms[0].slice(0, 4) : first.slice(0, 1) + first.slice(1).replace(/[AEIOU]/g, '').slice(0, 2);
    if (result.length < 2) result = first.slice(0, 2);
  } else {
    result = acronyms[0]?.slice(0, 3) ?? first.slice(0, 2);
    for (const w of words.slice(1)) {
      if (result.length >= 4) break;
      result += w.slice(0, 1);
    }
  }
  return result.padEnd(2, 'X');
}

const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');

/** The suggested code start, free among these categories, as bms_suggest_category_prefix tries them. */
function freePrefix(rows: { id: string; code_prefix: string }[], name: string, self = ''): string {
  const base = prefixBase(name);
  const taken = (p: string) => rows.some((r) => r.code_prefix === p && r.id !== self);
  const tries = [base, ...LETTERS.map((a) => base.slice(0, 3) + a), ...LETTERS.flatMap((a) => LETTERS.map((b) => base.slice(0, 2) + a + b))];
  return tries.find((p) => !taken(p)) ?? base;
}

function checkedPrefix(rows: { id: string; code_prefix: string }[], self: string, raw: string): string {
  const p = raw.trim().toUpperCase();
  if (!/^[A-Z]{2,4}$/.test(p)) refuse(422, 'validation_failed', 'The code start must be 2 to 4 letters, A to Z, such as BLB.');
  if (rows.some((r) => r.code_prefix === p && r.id !== self)) refuse(409, 'duplicate_category_prefix', 'Another category already uses this code start. Choose other letters.');
  return p;
}

export function createMockRetail(): RetailApi {
  // Branch roles and capabilities of this mock instance (ADR-033): every branch both, every capability off. A store is read
  // from the branch's flag (setMockStores, ADR-104) and is never stored as a role.
  const roles = new Map<string, 'shop' | 'both'>();
  const roleOf = (branch: string): 'store' | 'shop' | 'both' => (storeBranches.includes(branch) ? 'store' : roles.get(branch) ?? 'both');
  let caps: { value: Record<string, unknown>; version: number } = { value: { profile: 'shops', slow_mover_months: 4, slow_mover_min_sold: 0 }, version: 1 };
  const twoStep = () => transferReceipt || caps.value['transfer_receipt'] === true;
  const read = (): CapabilitiesRead => ({ capabilities: { ...caps.value, ...Object.fromEntries(MOCK_FLAGS.map((k) => [k, caps.value[k] === true])) }, version: caps.version });
  const balances = new Map<string, number>();
  PRODUCTS.forEach((p, i) => {
    balances.set(`${BRANCH_A}|${p.id}`, (i % 5 === 3 ? -2 : 4 + i * 3) * 1000);
    balances.set(`${BRANCH_B}|${p.id}`, (i % 7 === 2 ? 0 : 2 + i) * 1000);
  });
  const products = new Map<string, MockProduct>(PRODUCTS.map((p) => [p.id, { ...p, active: true, version: 1 }]));
  // Categories and units of this mock instance: renamed and switched off by the management screens (#146).
  // Issue #493: each has its code start and the next number to try, as V96 keeps them.
  const cats: { id: string; name: string; active: boolean; version: number; code_prefix: string; next: number }[] = [];
  for (const c of CATEGORIES) cats.push({ id: c.id ?? '', name: c.name ?? '', active: true, version: 1, code_prefix: freePrefix(cats, c.name ?? ''), next: 1 });
  const unitNames = [...new Set(PRODUCTS.map((p) => p.unit))];
  const unitRows: { id: string; name: string; active: boolean; version: number; usual_pack?: Unit['usual_pack'] }[] = unitNames.map((name, i) => ({ id: `00000000-0000-4000-8000-0000000d${String(i + 1).padStart(4, '0')}`, name, active: true, version: 1 }));
  // Issue #625: a pack's usual size in a smaller unit, as the server keeps it (three places, with the smaller unit's name).
  const usualPackOf = (p: UsualPackRequest): Unit['usual_pack'] => ({
    size: Number(p.size).toFixed(3), of_unit_id: p.of_unit_id, of_unit: unitRows.find((x) => x.id === p.of_unit_id)?.name ?? '', purpose: p.purpose,
  });
  const categoryId = (name: string): string => cats.find((c) => c.name === name)?.id ?? '';
  const history = new Map<string, PriceChange[]>();
  const packs = new Map<string, Pack[]>();
  // Barcodes of this mock instance (issue #345), by item; codes are unique across the items.
  const barcodes = new Map<string, Barcode[]>();
  let made = 0;
  const barcodesOf = (id: string) => ((barcodes.get(id) ?? []).length > 0 ? { barcodes: barcodes.get(id) } : {});
  const used = <K extends 'category' | 'unit'>(key: K, name: string) => [...products.values()].filter((p) => p[key] === name).length;
  const refreshed = <T extends { name?: string }>(row: T, key: 'category' | 'unit') => ({ ...row, product_count: used(key, row.name ?? '') });
  const codeTaken = (code: string) => [...products.values()].some((x) => x.code.toLowerCase() === code.toLowerCase());
  const freeCode = (c: { code_prefix: string; next: number }): [string, number] => {
    let n = c.next;
    while (codeTaken(itemCode(c.code_prefix, n))) n += 1;
    return [itemCode(c.code_prefix, n), n];
  };
  const categoryRow = (c: (typeof cats)[number]): Category => {
    const { next: _next, ...row } = c;
    return { ...refreshed(row, 'category'), next_code: freeCode(c)[0] } as Category;
  };
  // Items with sizes and colours of this mock instance (issue #384): the item's own row; its variants are products.
  const families = new Map<string, { id: string; name: string; baseCode: string; category: string; unit: string; version: number; createdAt: string; photoId?: string }>();
  // Item photos of this mock instance (issue #384): kept as blobs by their id.
  const photos = new Map<string, Blob>();
  const photoOf = (p: MockProduct): { photo_id?: string; item_photo_id?: string } => {
    const item = p.familyId ? families.get(p.familyId)?.photoId : undefined;
    return { ...(p.photoId ? { photo_id: p.photoId } : {}), ...(item ? { item_photo_id: item } : {}) };
  };
  const variantOf = (p: MockProduct) => {
    const f = p.familyId ? families.get(p.familyId) : undefined;
    return f ? { variant: { family_id: f.id, family_name: f.name, ...(p.size ? { size: p.size } : {}), ...(p.colour ? { colour: p.colour } : {}), position: p.position ?? 0 } } : {};
  };
  const asProduct = (p: MockProduct): Product =>
    cost({ id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit, unit_id: unitRows.find((u) => u.name === p.unit)?.id, sell_minor: p.sellMinor, currency: 'UGX', active: p.active !== false, version: p.version ?? 1, ...variantOf(p), ...photoOf(p), ...barcodesOf(p.id) }, { cost_minor: p.costMinor });
  const clash = (list: { id: string; name: string }[], id: string, name: string, code: string, what: string) => {
    if (list.some((x) => x.id !== id && x.name.toLowerCase() === name.trim().toLowerCase())) refuse(409, code, `A ${what} with this name exists.`);
  };
  const staleRow = (row: { version?: number }, version: number) => {
    if ((row.version ?? 1) !== version) refuse(409, 'version_conflict', 'This was changed by someone else. Reload and try again.');
  };
  const stale = (p: MockProduct, version: number) => {
    if ((p.version ?? 1) !== version) refuse(412, 'version_conflict', 'This item was changed by someone else. Reload and try again.');
  };
  /** Issue #352: a name is kept once per unit; a reason from an administrator adds it anyway (the mock user is one). */
  const sameNameCheck = (description: string, unit: string, selfId: string | undefined, reason: string | undefined) => {
    const same = [...products.values()].find((x) => x.id !== selfId && x.unit === unit && nameKey(x.description) === nameKey(description));
    if (!same) return;
    if ((reason ?? '').trim() === '') {
      refuse(422, 'duplicate_product_name', `This product already exists: ${same.description} (${same.code}).`, { existing_id: same.id, existing_code: same.code, existing_name: same.description, may_add_anyway: true });
    }
    if ((reason ?? '').trim().length < 5) refuse(422, 'validation_failed', 'Give a reason of at least 5 characters.');
  };
  // The stock history of this mock instance (issue #351): restocks, their voids and quantity corrections, in order.
  const stockLog: StockMovement[] = [];
  const logMove = (m: Omit<StockMovement, 'id' | 'at' | 'by' | 'historical'>) =>
    stockLog.push({ id: nextId('m'), at: new Date().toISOString(), by: mockMe('admin').user_id, historical: false, ...m });
  // Restocks of this mock instance (issue #351): kept with the prices before each, so a void can give them back.
  const purchases: (Purchase & { before: Record<string, { cost: number; sell: number }> })[] = [];
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
  const categoryOf = (id?: string): string | undefined => {
    const p = id ? products.get(id) : undefined;
    return p ? categoryId(p.category) : undefined;
  };
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

  /** Makes the planned variants of an item; returns how many were made. */
  const addPlanned = (familyId: string, sizes: string[], colours: string[], lines: { size?: string; colour?: string; code?: string; cost_minor?: number; sell_minor?: number }[] | undefined, costMinor: number, sellMinor: number, nextPosition: number): number => {
    const f = families.get(familyId) ?? refuse(404, 'not_found', 'That could not be found.');
    const existing = new Set([...products.values()].filter((p) => p.familyId === familyId).map((p) => variantKey(p.size, p.colour)));
    const plan = lines && lines.length > 0
      ? lines.map((l, i) => ({ size: cleanOption(l.size), colour: cleanOption(l.colour), code: planVariants({ baseCode: f.baseCode, sizes: [l.size ?? ''], colours: [l.colour ?? ''], costMinor, sellMinor })[0]?.code || f.baseCode, costMinor: l.cost_minor ?? costMinor, sellMinor: l.sell_minor ?? sellMinor, position: nextPosition + i }))
      : planVariants({ baseCode: f.baseCode, sizes, colours, costMinor, sellMinor, existing, nextPosition });
    if (plan.length > 100) refuse(422, 'too_many_variants', `That makes ${plan.length} sizes and colours. An item has at most 100.`);
    // An item never has more than 100, counting those it has (issue #395).
    if (existing.size + plan.length > 100) refuse(422, 'too_many_variants', `The item has ${existing.size} sizes and colours and this adds ${plan.length}. An item has at most 100; add fewer.`);
    for (const v of plan) {
      if (existing.has(variantKey(v.size, v.colour)) && lines && lines.length > 0) refuse(409, 'duplicate_variant', `The item has ${variantLabel(v.size, v.colour)} already. Each one is listed once.`);
      if ([...products.values()].some((x) => x.code.toLowerCase() === v.code.toLowerCase())) refuse(409, 'duplicate_product_code', `A product with the code ${v.code} exists. Spell the size or colour of ${variantLabel(v.size, v.colour)} differently.`);
      const p: MockProduct = { id: nextId('b'), code: v.code, description: variantDescription(f.name, v.size, v.colour), category: f.category, unit: f.unit, costMinor: v.costMinor, sellMinor: v.sellMinor, active: true, version: 1, familyId, size: v.size, colour: v.colour, position: v.position };
      products.set(p.id, p);
      existing.add(variantKey(v.size, v.colour));
      history.set(p.id, [{ id: nextId('h'), at: new Date().toISOString(), source: 'initial', new_sell_minor: p.sellMinor, currency: 'UGX', ...(profitAccess ? { new_cost_minor: p.costMinor } : {}) }]);
    }
    return plan.length;
  };
  /** The item as the server answers it: its variants in order, each with its stock, and the item's stock per branch. */
  const familyOf = (id: string): Family => {
    const f = families.get(id) ?? refuse(404, 'not_found', 'That could not be found.');
    const mine = [...products.values()].filter((p) => p.familyId === id).sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
    const byBranch = MOCK_BRANCHES.map((b) => ({ branch_id: b.id, milli: mine.reduce((s, p) => s + bal(b.id, p.id), 0) })).filter((b) => b.milli !== 0);
    const sizes = [...new Map(mine.filter((p) => p.size).map((p) => [(p.size ?? '').toLowerCase(), p.size ?? ''])).values()];
    const colours = [...new Map(mine.filter((p) => p.colour).map((p) => [(p.colour ?? '').toLowerCase(), p.colour ?? ''])).values()];
    return {
      id: f.id, name: f.name, base_code: f.baseCode, category_id: categoryId(f.category), category: f.category, unit_id: unitRows.find((u) => u.name === f.unit)?.id, unit: f.unit,
      sizes, colours, created_at: f.createdAt, version: f.version, ...(f.photoId ? { photo_id: f.photoId } : {}),
      variants: mine.map((p) => {
        const held = MOCK_BRANCHES.map((b) => bal(b.id, p.id)).filter((m) => m !== 0);
        return { ...asProduct(p), stock_on_hand: fromMilli(held.reduce((x, y) => x + y, 0)), stock_branches: held.length };
      }),
      stock_on_hand: fromMilli(byBranch.reduce((s, b) => s + b.milli, 0)),
      stock_by_branch: byBranch.map((b) => ({ branch_id: b.branch_id, qty: fromMilli(b.milli) })),
    };
  };

  const mock: RetailApi = {
    ...pricingApi,
    ...accountsApi,
    ...documentsApi,
    ...returnsApi,
    ...purchasingApi,
    ...createCashbookMock({ profit: () => profitAccess, today, sold, branchIds: MOCK_BRANCHES.map((b) => b.id), delay, run, once, nextId, waiting: (type, amount) => mockWaiting(type, amount, nextId('apr')) }),

    listProducts: ({ query, branchId }) =>
      delay([...products.values()].filter((p) => p.active !== false && matches(p, query)).map((p): Product => {
        const row: Product = {
          id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit, unit_id: unitRows.find((u) => u.name === p.unit)?.id, sell_minor: p.sellMinor, currency: 'UGX', active: true, ...photoOf(p), ...barcodesOf(p.id),
        };
        if (branchId) {
          row.qty = fromMilli(bal(branchId, p.id));
          row.negative = bal(branchId, p.id) < 0;
        }
        return cost(row, { cost_minor: p.costMinor });
      })),

    listCategories: () => delay(cats.map(categoryRow)),

    listCatalogue: ({ query, categoryId: category, active, cursor }) => {
      const wanted = [...products.values()]
        .filter((p) => matches(p, query) && (!category || categoryId(p.category) === category) && (active === undefined || (p.active !== false) === active))
        .sort((a, b) => a.code.localeCompare(b.code));
      const start = cursor ? Number(cursor) : 0;
      return delay({ items: wanted.slice(start, start + 50).map(asProduct), ...(start + 50 < wanted.length ? { next_cursor: String(start + 50) } : {}) });
    },

    // Issue #342: the one-product read also reports the stock on hand and in how many branches it is not zero.
    getProduct: (id) =>
      run(() => {
        const held = MOCK_BRANCHES.map((b) => bal(b.id, id)).filter((m) => m !== 0);
        return { ...asProduct(find(id)), stock_on_hand: fromMilli(held.reduce((x, y) => x + y, 0)), stock_branches: held.length };
      }),

    similarProducts: ({ description, unitId, excludeId }) =>
      run(() => {
        const key = nameKey(description);
        const unit = unitRows.find((u) => u.id === unitId)?.name;
        const exact: SimilarProduct[] = [];
        const similar: SimilarProduct[] = [];
        for (const p of products.values()) {
          if (p.id === excludeId || key === '') continue;
          const other = nameKey(p.description);
          const distance = other === key ? 0 : editDistance(key, other);
          const allowed = Math.max(key.length, other.length) < 5 ? 0 : Math.max(key.length, other.length) < 10 ? 1 : Math.max(key.length, other.length) < 20 ? 2 : 3;
          if (distance > allowed) continue;
          const row: SimilarProduct = { id: p.id, code: p.code, description: p.description, unit: p.unit, active: p.active !== false, distance, same_unit: distance === 0 && p.unit === unit };
          (row.same_unit ? exact : similar).push(row);
        }
        return { exact, similar: similar.sort((a, b) => (a.distance ?? 0) - (b.distance ?? 0)) };
      }),

    entryNorms: (productId) =>
      run(() => {
        const p = products.get(productId);
        if (!p) return refuse(404, 'not_found', 'No such item.');
        const none = { lines_read: 0 };
        return { product_id: p.id, unit: p.unit, buy: none, sell: none, move: none, quantity_factor: 10, price_factor: 2, min_lines: 3, ...entryNormsSet.get(productId) };
      }),

    nameHints: (description) =>
      run((): NameHints => {
        // As the server does (ADR-093): case, accents, spaces and punctuation ignored; holding the name first, then a few letters apart.
        const key = nameKey(description);
        const compact = key.replace(/ /g, '');
        if (compact.length < 3) return { did_you_mean: [] };
        const all = [...products.values()].filter((p) => p.active !== false);
        const flat = (p: MockProduct) => nameKey(p.description).replace(/ /g, '');
        const holding = all.filter((p) => flat(p).includes(compact))
          .sort((a, b) => Number(flat(b).startsWith(compact)) - Number(flat(a).startsWith(compact)) || nameKey(a.description).length - nameKey(b.description).length || a.code.localeCompare(b.code));
        const allowed = (a: string, b: string) => { const n = Math.max(a.length, b.length); return n < 5 ? 0 : n < 10 ? 1 : n < 20 ? 2 : 3; };
        const close = all.filter((p) => !holding.includes(p) && editDistance(compact, flat(p)) <= allowed(compact, flat(p)))
          .sort((a, b) => editDistance(compact, flat(a)) - editDistance(compact, flat(b)) || a.code.localeCompare(b.code));
        const did_you_mean = [...holding, ...close].slice(0, 5).map((p) => ({ id: p.id, code: p.code, description: p.description, unit: p.unit, category_id: categoryId(p.category), category: p.category }));
        const words = [...new Set(key.split(' ').filter((w) => w.length >= 3))].slice(0, 6);
        const scored = all.map((p) => ({ p, score: words.filter((w) => nameKey(p.description).split(' ').includes(w)).length })).filter((x) => x.score > 0);
        const best = Math.max(0, ...scored.map((x) => x.score));
        const top = scored.filter((x) => x.score === best);
        const byCategory = new Map<string, MockProduct[]>();
        for (const x of top) byCategory.set(x.p.category, [...(byCategory.get(x.p.category) ?? []), x.p]);
        const anyOf = (c: string) => scored.filter((x) => x.p.category === c).length;
        const chosen = [...byCategory.entries()].sort(([a, xs], [b, ys]) => ys.length - xs.length || anyOf(b) - anyOf(a) || a.localeCompare(b))[0];
        return { did_you_mean, ...(chosen ? { category: { id: categoryId(chosen[0]), name: chosen[0], items_like: chosen[1].length, example: chosen[1][0]?.description } } : {}) };
      }),

    confirmEntryWarning: (body) =>
      run(() => {
        mockEntryConfirmations.push(body);
      }),

    createProduct: (body) =>
      run(() => {
        // Issue #562: the app makes every new item's code; a typed one is refused, as the server does.
        if ((body.code ?? '').trim()) refuse(400, 'item_code_made_by_app', 'The app makes the item code from the category. Leave the code out.');
        const category = cats.find((c) => c.id === body.category_id);
        const unit = unitRows.find((u) => u.id === body.unit_id);
        if (!category || !unit) return refuse(422, 'validation_failed', 'Choose a category and a unit.');
        if (!category.active || !unit.active) refuse(422, 'inactive_category', 'This category or unit is switched off. Choose another one.');
        sameNameCheck(body.description ?? '', unit.name, undefined, body.duplicate_reason);
        const [code, n] = freeCode(category);
        category.next = n + 1;
        const p: MockProduct = { id: nextId('b'), code, description: (body.description ?? '').trim(), category: category.name, unit: unit.name, costMinor: body.cost_minor ?? 0, sellMinor: body.sell_minor ?? 0, active: true, version: 1 };
        products.set(p.id, p);
        history.set(p.id, [{ id: nextId('h'), at: new Date().toISOString(), source: 'initial', new_sell_minor: p.sellMinor, currency: 'UGX', ...(profitAccess ? { new_cost_minor: p.costMinor } : {}) }]);
        return asProduct(p);
      }),

    updateProduct: (id, version, body) =>
      run(() => {
        const p = find(id);
        stale(p, version);
        if (body.code !== undefined && body.code.trim() !== '' && body.code.trim().toLowerCase() !== p.code.toLowerCase()) refuse(400, 'item_code_made_by_app', 'An item keeps the code the app gave it. The code cannot be changed.');
        const fam = p.familyId ? families.get(p.familyId) : undefined;
        if (!fam && (body.variant_size !== undefined || body.variant_colour !== undefined)) refuse(422, 'not_a_variant', 'This item has no sizes or colours. Give it sizes and colours first, then change one of them.');
        if (fam) {
          if (body.description !== undefined && body.description.trim() !== p.description) refuse(422, 'variant_name_follows_item', "The name of a size or colour comes from its item. Change the item's name on its sizes and colours page.");
          const size = body.variant_size === undefined ? p.size : cleanOption(body.variant_size);
          const colour = body.variant_colour === undefined ? p.colour : cleanOption(body.variant_colour);
          if (!size && !colour) refuse(422, 'validation_failed', 'Keep a size, a colour or both.');
          if ([...products.values()].some((x) => x.id !== id && x.familyId === fam.id && variantKey(x.size, x.colour) === variantKey(size, colour))) refuse(409, 'duplicate_variant', `The item has ${variantLabel(size, colour)} already. Each one is listed once.`);
          Object.assign(p, { size, colour, description: variantDescription(fam.name, size, colour), active: body.active ?? p.active, version: (p.version ?? 1) + 1 });
          return asProduct(p);
        }
        const category = body.category_id ? cats.find((c) => c.id === body.category_id) : undefined;
        const unit = body.unit_id ? unitRows.find((u) => u.id === body.unit_id) : undefined;
        if (category && !category.active && category.name !== p.category) refuse(422, 'inactive_category', 'This category is switched off. Choose another one.');
        if (body.description !== undefined || unit) sameNameCheck(body.description ?? p.description, unit?.name ?? p.unit, id, body.duplicate_reason);
        Object.assign(p, {
          description: body.description === undefined ? p.description : body.description.trim(),
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

    // Items with sizes and colours (issue #384): the same rules as the server, in memory.
    getFamily: (id) => run(() => familyOf(id)),

    createFamily: (body) =>
      run(() => {
        const category = cats.find((c) => c.id === body.category_id);
        const unit = unitRows.find((u) => u.id === body.unit_id);
        if (!category || !unit) return refuse(422, 'validation_failed', 'Choose a category and a unit.');
        // Issue #562: the item's codes start with the category's next code, made by the app.
        if ((body.base_code ?? '').trim() || (body.variants ?? []).some((v) => (v.code ?? '').trim())) refuse(400, 'item_code_made_by_app', 'The app makes the item code from the category. Leave the code out.');
        const [baseCode, n] = freeCode(category);
        category.next = n + 1;
        const id = nextId('f');
        families.set(id, { id, name: (body.name ?? '').trim(), baseCode, category: category.name, unit: unit.name, version: 1, createdAt: new Date().toISOString() });
        addPlanned(id, body.sizes ?? [], body.colours ?? [], body.variants, body.cost_minor ?? 0, body.sell_minor ?? 0, 0);
        return familyOf(id);
      }),

    updateFamily: (id, version, body) =>
      run(() => {
        const f = families.get(id) ?? refuse(404, 'not_found', 'That could not be found.');
        if (f.version !== version) refuse(409, 'version_conflict', 'This was changed by someone else. Reload and try again.');
        const category = body.category_id ? cats.find((c) => c.id === body.category_id) : undefined;
        f.name = body.name?.trim() || f.name;
        f.category = category?.name ?? f.category;
        f.version += 1;
        for (const p of products.values()) {
          if (p.familyId !== id) continue;
          Object.assign(p, { description: variantDescription(f.name, p.size, p.colour), category: f.category, version: (p.version ?? 1) + 1 });
        }
        return familyOf(id);
      }),

    addVariants: (id, body) =>
      run(() => {
        const f = families.get(id) ?? refuse(404, 'not_found', 'That could not be found.');
        const mine = [...products.values()].filter((p) => p.familyId === f.id).sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
        const added = addPlanned(f.id, body.sizes ?? [], body.colours ?? [], body.variants, body.cost_minor ?? mine[0]?.costMinor ?? 0, body.sell_minor ?? mine[0]?.sellMinor ?? 0, Math.max(-1, ...mine.map((p) => p.position ?? 0)) + 1);
        if (added === 0) refuse(422, 'nothing_to_add', 'The item has every one of these sizes and colours already. Choose one it does not have.');
        return familyOf(f.id);
      }),

    joinFamily: (familyId, body) =>
      run(() => {
        const items = body.items ?? [];
        // An item that exists keeps its own name, base code and category: the server refuses a body that would name it anew.
        if (familyId && (body.name?.trim() || body.base_code?.trim() || body.category_id || body.duplicate_reason?.trim())) {
          refuse(422, 'validation_failed', 'The item exists already and keeps its own name, code and category; leave these out.');
        }
        const inItem = familyId ? [...products.values()].filter((p) => p.familyId === familyId).length : 0;
        if (inItem + items.length > MAX_VARIANTS) refuse(422, 'too_many_variants', `An item has at most ${MAX_VARIANTS} sizes and colours.`);
        const picked = items.map((i) => find(i.product_id ?? ''));
        if (picked.some((p) => p.familyId)) refuse(422, 'already_has_variants', 'One of these is a size or colour of an item already.');
        const first = picked[0] ?? refuse(422, 'validation_failed', 'Pick at least one item.');
        if (picked.some((p) => p.unit !== first.unit)) refuse(422, 'unit_differs', 'The sizes of one item are counted in one unit.');
        let id = familyId;
        if (!id) {
          id = nextId('f');
          const category = body.category_id ? cats.find((c) => c.id === body.category_id)?.name : undefined;
          families.set(id, { id, name: (body.name ?? '').trim(), baseCode: (body.base_code ?? first.code).slice(0, 24), category: category ?? first.category, unit: first.unit, version: 1, createdAt: new Date().toISOString() });
        }
        const f = families.get(id) ?? refuse(404, 'not_found', 'That could not be found.');
        const taken = new Set([...products.values()].filter((p) => p.familyId === f.id).map((p) => variantKey(p.size, p.colour)));
        let position = [...products.values()].filter((p) => p.familyId === f.id).length;
        items.forEach((item, i) => {
          const p = picked[i] as MockProduct;
          const size = cleanOption(item.size);
          const colour = cleanOption(item.colour);
          if (!size && !colour) refuse(422, 'validation_failed', `Give ${p.description} a size, a colour or both.`);
          if (taken.has(variantKey(size, colour))) refuse(409, 'duplicate_variant', `The item has ${variantLabel(size, colour)} already. Each one is listed once.`);
          taken.add(variantKey(size, colour));
          Object.assign(p, { familyId: f.id, size, colour, position: position++, category: f.category, description: variantDescription(f.name, size, colour), version: (p.version ?? 1) + 1 });
        });
        return familyOf(f.id);
      }),

    makeFamily: (productId, body) =>
      run(() => {
        const p = find(productId);
        if (p.familyId) refuse(422, 'already_has_variants', 'This item has sizes and colours already. Add more on its sizes and colours page.');
        const size = cleanOption(body.size);
        const colour = cleanOption(body.colour);
        if (!size && !colour) refuse(422, 'validation_failed', 'Say which size or colour the stock of this item is now.');
        const id = nextId('f');
        families.set(id, { id, name: p.description, baseCode: p.code.slice(0, 24), category: p.category, unit: p.unit, version: 1, createdAt: new Date().toISOString() });
        Object.assign(p, { familyId: id, size, colour, position: 0, description: variantDescription(p.description, size, colour), version: (p.version ?? 1) + 1 });
        addPlanned(id, body.sizes ?? [], body.colours ?? [], body.variants, p.costMinor, p.sellMinor, 1);
        return familyOf(id);
      }),

    setProductPhoto: (productId, file) =>
      run(() => {
        const p = find(productId);
        if (file.size > 5 * 1024 * 1024) refuse(413, 'file_too_large', 'The photo is larger than 5 MB.');
        const id = nextId('d');
        photos.set(id, file);
        Object.assign(p, { photoId: id, version: (p.version ?? 1) + 1 });
        return asProduct(p);
      }),
    clearProductPhoto: (productId) =>
      run(() => {
        const p = find(productId);
        Object.assign(p, { photoId: undefined, version: (p.version ?? 1) + 1 });
        return asProduct(p);
      }),
    setFamilyPhoto: (familyId, file) =>
      run(() => {
        const f = families.get(familyId) ?? refuse(404, 'not_found', 'That could not be found.');
        const id = nextId('d');
        photos.set(id, file);
        f.photoId = id;
        f.version += 1;
        return familyOf(familyId);
      }),
    clearFamilyPhoto: (familyId) =>
      run(() => {
        const f = families.get(familyId) ?? refuse(404, 'not_found', 'That could not be found.');
        f.photoId = undefined;
        f.version += 1;
        return familyOf(familyId);
      }),
    photo: (photoId) => run(() => photos.get(photoId) ?? refuse(404, 'not_found', 'That could not be found.')),

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
        const sell = body.purpose === 'buy' ? undefined : body.sell_minor;
        const pack: Pack = { id: nextId('k'), product_id: p.id, unit_id: body.unit_id, unit: unit?.name ?? '', factor: body.factor, active: true, version: 1, purpose: body.purpose ?? 'both', ...(sell === undefined ? {} : { sell_minor: sell }), suggested_sell_minor: Math.round(p.sellMinor * Number(body.factor)) };
        packs.set(p.id, [...rows, pack]);
        return pack;
      }),

    updatePack: (productId, unitId, version, body) =>
      run(() => {
        const rows = packs.get(find(productId).id) ?? [];
        const pack = rows.find((k) => k.unit_id === unitId);
        if (!pack) refuse(404, 'not_found', 'No such pack.');
        if (pack && pack.version !== version) refuse(409, 'version_conflict', 'The pack changed since it was read.');
        const was = pack as Pack;
        const purpose = body.purpose ?? was.purpose ?? 'both';
        // Issue #624: a pack made buy only, or whose own price is dropped, sells at the piece price times the size again.
        const sell = purpose === 'buy' || body.clear_sell_minor ? undefined : (body.sell_minor ?? was.sell_minor);
        const { sell_minor: _dropped, ...rest } = was;
        const next: Pack = { ...rest, ...(body.factor === undefined ? {} : { factor: body.factor }), ...(body.active === undefined ? {} : { active: body.active }), purpose, ...(sell === undefined ? {} : { sell_minor: sell }), version: (pack?.version ?? 0) + 1 };
        packs.set(productId, rows.map((k) => (k.unit_id === unitId ? next : k)));
        return next;
      }),

    findByBarcode: (code, branchId) =>
      run(() => {
        for (const key of scanKeys(code)) for (const [id, list] of barcodes) {
          const b = list.find((x) => barcodeKey(x.code ?? '', x.format) === key);
          const p = b ? products.get(id) : undefined;
          if (b && p && p.active !== false) {
            if (b.unit_id && !(packs.get(id) ?? []).some((k) => k.unit_id === b.unit_id && k.active)) refuse(404, 'pack_inactive', 'This barcode is on a pack that is switched off. Switch the pack on, or scan the item\'s own barcode.');
            const own = (packs.get(id) ?? []).filter((k) => k.active);
            const product = { ...asProduct(p), ...(own.length > 0 ? { packs: own } : {}) };
            return { product: branchId ? { ...product, qty: fromMilli(bal(branchId, id)) } : product, barcode: b, ...(b.unit_id ? { unit_id: b.unit_id } : {}) };
          }
        }
        return null;
      }),

    addBarcode: (productId, body) =>
      run(() => {
        const p = find(productId);
        const checked = checkBarcode(body.code, body.format === 'code128' ? 'code128' : undefined);
        if (!checked.ok) refuse(422, 'validation_failed', checked.problem);
        const code = body.code.trim();
        const format = checked.ok ? checked.format : 'code128';
        if ([...barcodes.values()].flat().some((b) => barcodeKey(b.code ?? '', b.format) === barcodeKey(code, format))) refuse(409, 'barcode_taken', 'This barcode is on another item already.');
        const row: Barcode = { id: nextId('bc'), product_id: p.id, code, format, generated: false, ...(body.unit_id ? { unit_id: body.unit_id } : {}) };
        barcodes.set(p.id, [...(barcodes.get(p.id) ?? []), row]);
        return row;
      }),

    removeBarcode: (productId, barcodeId) =>
      run(() => {
        barcodes.set(productId, (barcodes.get(productId) ?? []).filter((b) => b.id !== barcodeId));
      }),

    generateBarcodes: (productIds) =>
      run(() =>
        [...new Set(productIds)].sort().map((id) => {
          const own = (barcodes.get(id) ?? []).find((b) => b.generated);
          if (own) return own;
          const row: Barcode = { id: nextId('bc'), product_id: find(id).id, code: shopEan((made += 1)), format: 'ean13', generated: true };
          barcodes.set(id, [...(barcodes.get(id) ?? []), row]);
          return row;
        }),
      ),

    importProducts: (csv, dryRun, makeCodes = false) =>
      run(() => {
        const lines = csv.split(/\r?\n/).filter((l) => l.trim() !== '');
        const sep = lines[0]?.includes('\t') ? '\t' : ',';
        const head = (lines[0] ?? '').split(sep).map((h) => h.trim().toLowerCase().replace(/[\s-]+/g, '_'));
        for (const need of [...(makeCodes ? [] : ['code']), 'description', 'category', 'unit', 'sell_price']) {
          if (!head.includes(need)) refuse(422, 'validation_failed', `The file needs a column called ${need.replace('_', ' ')}.`);
        }
        if (head.includes('cost_price') && !profitAccess) refuse(422, 'cost_not_allowed', 'The buying price column needs permission to see buying prices. Take that column out of the file and try again.');
        const seen = new Set<string>();
        const rows = lines.slice(1).map((l, i) => {
          const c = l.split(sep).map((x) => x.trim());
          const get = (n: string) => c[head.indexOf(n)] ?? '';
          // Owner decision D45 (issue #562): with Let the app make the codes, the file's codes are not read.
          const code = makeCodes ? '' : get('code');
          const base = { line: i + 2, code, description: get('description') };
          if (!/^\d+$/.test(get('sell_price').replace(/,/g, ''))) return { ...base, outcome: 'error', message: 'The selling price must be a whole number, for example 12000.' };
          if (makeCodes) {
            const typed = head.includes('code') ? get('code') : '';
            return { ...base, outcome: 'added', message: `${dryRun ? 'Would be added.' : 'Added.'} The app gives it the next code of ${get('category')}.${typed ? ` The code ${typed} in the file is not used.` : ''}` };
          }
          if ([...products.values()].some((p) => p.code.toLowerCase() === code.toLowerCase())) return { ...base, outcome: 'skipped', message: 'An item with this code exists already.' };
          if (seen.has(code.toLowerCase())) return { ...base, outcome: 'skipped', message: 'This code is repeated in the file; the first row is used.' };
          seen.add(code.toLowerCase());
          return { ...base, outcome: 'added', message: dryRun ? 'Would be added.' : 'Added.' };
        });
        const count = (o: string) => rows.filter((r) => r.outcome === o).length;
        if (!dryRun && count('error') > 0) refuse(422, 'import_has_errors', `${count('error')} rows have a problem. Nothing was added. Run the check to see which rows, fix them and try again.`);
        if (!dryRun) {
          lines.slice(1).forEach((l, i) => {
            const c = l.split(sep).map((x) => x.trim());
            const get = (n: string) => c[head.indexOf(n)] ?? '';
            if (!makeCodes && [...products.values()].some((p) => p.code.toLowerCase() === get('code').toLowerCase())) return;
            const category = cats.find((x) => x.name.toLowerCase() === get('category').toLowerCase()) ?? cats[0];
            let code = get('code');
            if (makeCodes && category) {
              const [made, n] = freeCode(category);
              category.next = n + 1;
              code = made;
              const row = rows[i];
              if (row) row.code = made;
            }
            const id = nextId('b');
            products.set(id, { id, code, description: get('description'), category: get('category'), unit: get('unit'), costMinor: 0, sellMinor: Number(get('sell_price').replace(/,/g, '')), active: true, version: 1 });
          });
        }
        return { dry_run: dryRun, rows_read: rows.length, added: count('added'), skipped: count('skipped'), errors: count('error'), categories_created: [], units_created: [], rows };
      }),

    createCategory: (name, codePrefix) =>
      run(() => {
        clash(cats, '', name, 'duplicate_category', 'category');
        const prefix = codePrefix?.trim() ? checkedPrefix(cats, '', codePrefix) : freePrefix(cats, name);
        const c = { id: nextId('c'), name: name.trim(), active: true, version: 1, code_prefix: prefix, next: 1 };
        cats.push(c);
        return categoryRow(c);
      }),

    suggestCategoryPrefix: (name) => delay(freePrefix(cats, name)),

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
        if (body.code_prefix !== undefined) c.code_prefix = checkedPrefix(cats, id, body.code_prefix);
        c.active = body.active ?? c.active;
        c.version += 1;
        return categoryRow(c);
      }),

    listUnits: () => delay(unitRows.map((u) => refreshed(u, 'unit') as Unit)),

    createUnit: (name, usualPack) =>
      run(() => {
        clash(unitRows, '', name, 'duplicate_unit', 'unit');
        const u = { id: nextId('d'), name: name.trim(), active: true, version: 1, ...(usualPack ? { usual_pack: usualPackOf(usualPack) } : {}) };
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
        if (body.usual_pack) u.usual_pack = usualPackOf(body.usual_pack);
        if (body.clear_usual_pack) delete u.usual_pack;
        u.version += 1;
        return refreshed(u, 'unit') as Unit;
      }),

    listStockAllBranches: ({ query, categoryId: category, negativeOnly, level, includeInactive }) =>
      delay({
        branches: MOCK_BRANCHES.map((b) => ({ id: b.id, code: b.code, name: b.name, head_office: b.is_head_office })),
        items: [...products.values()].filter((p) => (includeInactive || p.active !== false) && matches(p, query) && (!category || categoryId(p.category) === category)).map((p): AllBranchesRow => {
          const balancesMilli = MOCK_BRANCHES.map((b) => bal(b.id, p.id));
          return cost({
            product_id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit,
            total_qty: fromMilli(balancesMilli.reduce((x, y) => x + y, 0)), negative: balancesMilli.some((m) => m < 0), sell_minor: p.sellMinor,
            balances: MOCK_BRANCHES.map((b, i) => ({ branch_id: b.id, qty: fromMilli(balancesMilli[i] ?? 0), negative: (balancesMilli[i] ?? 0) < 0 })),
          }, { cost_minor: p.costMinor });
        }).filter((r) => (!negativeOnly || r.negative) && atLevel(Number(r.total_qty), level)),
      }),

    listStock: ({ branchId, query, categoryId: category, negativeOnly, level, includeInactive }) =>
      delay([...products.values()].filter((p) => (includeInactive || p.active !== false) && matches(p, query) && (!category || categoryId(p.category) === category)).map((p): StockRow => {
        const milli = bal(branchId, p.id);
        return cost({ product_id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit, qty: fromMilli(milli), negative: milli < 0, sell_minor: p.sellMinor }, { cost_minor: p.costMinor });
      }).filter((r) => (!negativeOnly || r.negative) && atLevel(Number(r.qty), level))),

    listSales: ({ cursor, ...q }) => {
      const wanted = [...sales].reverse().filter((x) => saleMatches(x, q, categoryOf));
      const start = cursor ? Number(cursor) : 0;
      const items = wanted.slice(start, start + 50).map(visibleSale);
      return delay({ items, ...(start + 50 < wanted.length ? { next_cursor: String(start + 50) } : {}) });
    },

    // The category and the item of a chain choose lines, not sales: summariseChain applies them, so the sales are matched without.
    salesSummary: (q) => delay(summariseChain(sales.filter((x) => saleMatches(x, { ...q, categoryId: undefined }, categoryOf)), q, {
      profit: profitAccess,
      branchName: (id) => MOCK_BRANCHES.find((b) => b.id === id)?.name ?? 'Branch',
      sellerName: () => MOCK_SELLER.name,
      product: (id) => {
        const p = id ? products.get(id) : undefined;
        const f = p?.familyId ? families.get(p.familyId) : undefined;
        return p ? { name: p.description, categoryId: categoryId(p.category), categoryName: p.category, ...(f ? { familyId: f.id, familyName: f.name } : {}) } : undefined;
      },
      variants: families.size > 0,
    })),

    getSale: (id) => {
      const found = sales.find((x) => x.id === id);
      return found ? delay(visibleSale(found)) : Promise.reject(new RetailError('That could not be found.', 404, 'not_found'));
    },

    paySale: (saleId, body, key) =>
      run(() => once(`pay|${key}`, { saleId, body }, () => {
        const sale = sales.find((x) => x.id === saleId) ?? refuse(404, 'not_found', 'That could not be found.');
        if (sale.payment_method !== 'credit' || sale.status !== 'completed') refuse(422, 'sale_not_payable', 'Only a completed credit sale takes payments.');
        if (sale.historical === true) refuse(422, 'sale_historical', 'An imported sale cannot be paid from here. Record this payment with your accountant.');
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
        const waiting = mockWaiting('retail_sale_void', sale.total_minor ?? 0, nextId('apr'));
        if (waiting) return waiting;
        (sale.lines ?? []).forEach((l) => move(sale.branch_id ?? BRANCH_A, l.product_id ?? '', toMilli(l.qty ?? '0')));
        sale.status = 'voided';
        sale.void_reason = reason.trim();
        return visibleSale(sale);
      }),

    writeOffSale: (saleId, reason, key) =>
      run(() => once(`write-off|${key}`, { saleId, reason }, () => {
        const sale = sales.find((x) => x.id === saleId) ?? refuse(404, 'not_found', 'That could not be found.');
        if (sale.status === 'voided') refuse(409, 'sale_voided', 'This sale has been voided already.');
        if (sale.payment_method !== 'credit') refuse(422, 'sale_not_credit', 'Only a sale on credit can be written off.');
        if (sale.write_off) refuse(409, 'already_written_off', 'The unpaid part of this sale has been written off already.');
        const unpaid = sale.balance_minor ?? 0;
        if (unpaid <= 0) refuse(422, 'nothing_owing', 'This sale has nothing left to pay.');
        sale.paid_minor = sale.total_minor;
        sale.balance_minor = 0;
        sale.write_off = { written_off_on: today(), unpaid_minor: unpaid, reason: reason.trim() };
        return { executed: true, write_off: { id: nextId('wo'), sale_id: saleId, unpaid_minor: unpaid, reason: reason.trim(), written_off_on: today() } };
      })),

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
        id: nextId('5'), sale_no: `S-${String(seq).padStart(6, '0')}`, branch_id: branchId, sale_date: body.sale_date ?? today(), created_by: MOCK_SELLER.id,
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
      const before: Record<string, { cost: number; sell: number }> = {};
      const lines = body.lines.map((l, n) => {
        const p = find(l.product_id);
        before[p.id] ??= { cost: p.costMinor, sell: p.sellMinor };
        p.costMinor = l.cost_minor;
        if (l.sell_minor !== undefined) p.sellMinor = l.sell_minor;
        let qtyTotal = 0;
        l.qty_by_branch.forEach((q) => {
          move(q.branch_id, p.id, toMilli(q.qty));
          qtyTotal += toMilli(q.qty);
          logMove({ branch_id: q.branch_id, product_id: p.id, kind: 'purchase', qty: fromMilli(toMilli(q.qty)), source_type: 'retail.purchase' });
        });
        total += lineTotal(l.cost_minor, qtyTotal);
        return { id: nextId('p'), line_no: n + 1, product_id: p.id, code: p.code, description: p.description, cost_minor: l.cost_minor, sell_minor: p.sellMinor, qty_total: fromMilli(qtyTotal), qty_by_branch: l.qty_by_branch, line_total_minor: lineTotal(l.cost_minor, qtyTotal), base_unit: p.unit };
      });
      const made: Purchase = { id: nextId('6'), purchase_no: `P-${String(seq).padStart(6, '0')}`, ...(body.supplier_id ? { supplier_id: body.supplier_id } : {}), purchased_on: body.purchased_on, payment_method: body.payment_method, currency: 'UGX', lines, total_minor: total, status: 'posted' };
      purchases.push({ ...made, before });
      if (body.payment_method === 'credit' && body.supplier_id) {
        purchasingApi.recordCreditRestock({ id: made.id ?? '', purchase_no: made.purchase_no ?? '', supplier_id: body.supplier_id, date: body.purchased_on, total_minor: total });
      }
      return made;
      })),

    correctStock: (body, key) =>
      run(() => once(`correct-stock|${key}`, body, () => {
        const p = find(body.product_id);
        const was = bal(body.branch_id, p.id);
        const next = toMilli(body.new_qty);
        if (next < 0) refuse(422, 'validation_failed', 'The quantity cannot be below none.');
        if (toMilli(body.expected_qty) !== was) refuse(409, 'stock_moved', `The quantity changed while you were typing: it is ${plainQty(fromMilli(was))} ${p.unit} now. Look at the list again and type the right quantity.`, { current_qty: fromMilli(was) });
        // The server refuses a correction that changes nothing before it asks for approval, so the mock does too.
        if (next === was) refuse(422, 'quantity_unchanged', `The quantity is ${plainQty(fromMilli(was))} ${p.unit} already. Type the right quantity.`);
        const waiting = mockWaiting('retail_stock_correction', Math.round((Math.abs(next - was) * p.costMinor) / 1000), nextId('apr'));
        if (waiting) return waiting;
        move(body.branch_id, p.id, next - was);
        const label = body.reason_code === 'entry_mistake' ? 'Entry mistake' : 'Miscount';
        const id = nextId('c');
        logMove({ branch_id: body.branch_id, product_id: p.id, kind: 'adjustment', qty: fromMilli(next - was), source_type: 'retail.stock_correction', note: body.note ? `${label}: ${body.note}` : label, correction: { reason_code: body.reason_code, old_qty: fromMilli(was), new_qty: fromMilli(next) } });
        return { id, branch_id: body.branch_id, product_id: p.id, code: p.code, description: p.description, old_qty: fromMilli(was), new_qty: fromMilli(next), reason_code: body.reason_code, ...(body.note ? { note: body.note } : {}), at: new Date().toISOString(), by: mockMe('admin').user_id };
      })),

    stockHistory: ({ productId, branchId }) =>
      run(() => ({ items: stockLog.filter((m) => m.product_id === productId && m.branch_id === branchId).reverse() })),

    listPurchases: ({ branchId, from, to, supplierId, categoryId: category, productId, status }) =>
      run(() => ({
        items: purchases
          .filter((p) => (!from || (p.purchased_on ?? '') >= from) && (!to || (p.purchased_on ?? '') <= to) && (!status || p.status === status)
            && (!supplierId || (p.supplier_id ?? NO_SUPPLIER) === supplierId)
            // One line must match the branch, the item and the category together, as the server's movement row does (#372).
            && (!(branchId || productId || category) || (p.lines ?? []).some((l) => {
              const product = products.get(l.product_id ?? '');
              return (!branchId || (l.qty_by_branch ?? []).some((b) => b.branch_id === branchId))
                && (!productId || l.product_id === productId || product?.familyId === productId)
                && (!category || categoryId(product?.category ?? '') === category);
            })))
          .map(({ before: _before, ...p }) => p)
          .reverse(),
      })),

    purchasesSummary: (q) =>
      run(() => summarisePurchases(purchases, q, {
        cost: profitAccess,
        branchName: (id) => MOCK_BRANCHES.find((b) => b.id === id)?.name ?? 'Branch',
        supplierName: (id) => suppliers.find((s) => s.id === id)?.name ?? 'Unknown',
        product: (id) => {
          const p = id ? products.get(id) : undefined;
          const f = p?.familyId ? families.get(p.familyId) : undefined;
          return p ? { name: p.description, categoryId: categoryId(p.category), categoryName: p.category, unit: p.unit, ...(f ? { familyId: f.id, familyName: f.name } : {}) } : undefined;
        },
        variants: families.size > 0,
      })),

    stockSummary: (q) =>
      delay(summariseStock(q, {
        cost: profitAccess,
        branches: MOCK_BRANCHES.map((b) => ({ id: b.id, name: b.name })),
        products: [...products.values()].filter((p) => matches(p, q.query)).map((p) => ({ id: p.id, category: p.category, categoryId: categoryId(p.category), sellMinor: p.sellMinor, costMinor: p.costMinor })),
        balance: bal,
        keepQty: (m) => atLevel(m / 1000, q.stockLevel),
      })),

    getPurchase: (id) => run(() => {
      const found = purchases.find((p) => p.id === id);
      if (!found) return refuse(404, 'not_found', 'The resource does not exist.');
      const { before: _before, ...p } = found;
      return p;
    }),

    voidPurchase: (id, reason, key) =>
      run(() => once(`void-purchase|${key}`, { id, reason }, (): Purchase => {
        const found = purchases.find((p) => p.id === id);
        if (!found) return refuse(404, 'not_found', 'The resource does not exist.');
        if (found.status === 'voided') refuse(409, 'purchase_voided', 'This purchase has been voided already.');
        if (reason.trim().length < 5) refuse(422, 'validation_failed', 'Give a reason of at least 5 characters.');
        for (const l of found.lines ?? []) {
          for (const b of l.qty_by_branch ?? []) {
            const have = bal(b.branch_id ?? '', l.product_id ?? '');
            if (have < toMilli(b.qty ?? '0')) {
              refuse(422, 'purchase_stock_moved', `${l.description}: this purchase added ${plainQty(b.qty ?? '0')} ${l.base_unit ?? ''} but only ${plainQty(fromMilli(have))} are in stock there now, because some were sold, used or moved. It cannot be voided. Use Correct the quantity on the stock list, or count the stock.`);
            }
          }
        }
        for (const l of found.lines ?? []) {
          for (const b of l.qty_by_branch ?? []) {
            move(b.branch_id ?? '', l.product_id ?? '', -toMilli(b.qty ?? '0'));
            logMove({ branch_id: b.branch_id, product_id: l.product_id, kind: 'adjustment', qty: fromMilli(-toMilli(b.qty ?? '0')), source_type: 'retail.purchase_void', note: reason.trim() });
          }
          const was = found.before[l.product_id ?? ''];
          const p = products.get(l.product_id ?? '');
          if (was && p) Object.assign(p, { costMinor: was.cost, sellMinor: was.sell, version: (p.version ?? 1) + 1 });
        }
        Object.assign(found, { status: 'voided', void_reason: reason.trim(), voided_on: today(), voided_at: new Date().toISOString() });
        const { before: _before, ...voided } = found;
        return voided;
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
    commitStocktake: (id, acknowledged = []) => {
      const st = stocktakes.get(id);
      if (!st) return Promise.reject(new RetailError('That could not be found.', 404, 'not_found'));
      if (st.status === 'committed') return Promise.reject(new RetailError(retailMessage({ code: 'stocktake_committed' }, 409), 409, 'stocktake_committed'));
      if (st.status === 'discarded') return Promise.reject(new RetailError(retailMessage({ code: 'stocktake_discarded' }, 409), 409, 'stocktake_discarded'));
      // Issue #636, as the server: a big drop left out of the acknowledged lines refuses the commit.
      const unticked = (st.lines ?? []).filter((l) => bigDrop(l) && !acknowledged.includes(l.product_id ?? '')).map((l) => l.description ?? l.code);
      if (unticked.length > 0) {
        const detail = `The count of ${unticked.join(', ')} removes more than half of what was there. Review the difference again and tick each line marked before you commit the count.`;
        return Promise.reject(new RetailError(retailMessage({ code: 'big_drop_not_acknowledged', detail }, 409), 409, 'big_drop_not_acknowledged'));
      }
      (st.lines ?? []).forEach((l) => move(st.branch_id ?? BRANCH_A, l.product_id ?? '', toMilli(l.variance_qty ?? '0')));
      st.status = 'committed';
      return delay({ ...st });
    },
    // Issue #656, as the server: a draft ends without moving stock, again is the same, a committed count is refused.
    discardStocktake: (id) => {
      const st = stocktakes.get(id);
      if (!st) return Promise.reject(new RetailError('That could not be found.', 404, 'not_found'));
      if (st.status === 'committed') return Promise.reject(new RetailError(retailMessage({ code: 'stocktake_committed' }, 409), 409, 'stocktake_committed'));
      st.status = 'discarded';
      return delay({ ...st });
    },
    // Issue #681, as the server: the open counts at the branch, newest first (the mock has one person).
    myDraftStocktakes: (branchId) => delay([...stocktakes.values()].filter((s) => s.status === 'draft' && s.branch_id === branchId).reverse().slice(0, 10)),

    // Quick add (issue #632, ADR-101): the item as createProduct makes it, General and Piece made when missing, then the quantity
    // added to the branch's stock as opening stock, as the server does.
    quickAdd: (body, key) =>
      run(() => once(`quick-add|${key}`, body, () => {
        const branchId = body.branch_id ?? BRANCH_A;
        let p: MockProduct;
        let categoryMade = false;
        let unitMade = false;
        if (body.product_id) {
          p = find(body.product_id);
          if (!p.active) refuse(422, 'inactive_product', 'This item is switched off. Switch it on first, on Items.');
        } else {
          if (!(body.description ?? '').trim() || body.sell_minor === undefined) refuse(422, 'validation_failed', 'Type the name and the selling price.');
          const general = cats.find((c) => c.name.toLowerCase() === 'general');
          if (!body.category_id && general && !general.active) refuse(422, 'inactive_category', 'The General category is switched off. Choose a category.');
          let category = body.category_id ? cats.find((c) => c.id === body.category_id) : general;
          if (!category) {
            category = { id: nextId('c'), name: 'General', active: true, version: 1, code_prefix: freePrefix(cats, 'General'), next: 1 };
            cats.push(category);
            categoryMade = true;
          }
          // Only a switched-on piece unit is used; a switched-off Piece is never switched back on (the server's rule).
          let unit = body.unit_id ? unitRows.find((u) => u.id === body.unit_id) : ['piece', 'pc', 'pcs'].map((n) => unitRows.find((u) => u.active && u.name.toLowerCase() === n)).find(Boolean);
          if (!body.unit_id && !unit && unitRows.some((u) => !u.active && u.name.toLowerCase() === 'piece')) refuse(422, 'inactive_unit', 'The Piece unit is switched off. Choose a unit.');
          if (!unit) {
            unit = { id: nextId('d'), name: 'Piece', active: true, version: 1 };
            unitRows.push(unit);
            unitMade = true;
          }
          if (!category.active) refuse(422, 'inactive_category', 'This category is switched off. Choose another one.');
          if (!unit.active) refuse(422, 'inactive_unit', 'This unit is switched off. Choose another one.');
          sameNameCheck(body.description ?? '', unit.name, undefined, body.duplicate_reason);
          const [code, n] = freeCode(category);
          category.next = n + 1;
          p = { id: nextId('b'), code, description: (body.description ?? '').trim(), category: category.name, unit: unit.name, costMinor: body.cost_minor ?? 0, sellMinor: body.sell_minor ?? 0, active: true, version: 1 };
          products.set(p.id, p);
          history.set(p.id, [{ id: nextId('h'), at: new Date().toISOString(), source: 'initial', new_sell_minor: p.sellMinor, currency: 'UGX', ...(profitAccess ? { new_cost_minor: p.costMinor } : {}) }]);
        }
        // The quantity is added to what the branch holds, never set (ADR-101).
        const added = body.qty === undefined ? null : toMilli(body.qty);
        if (body.product_id && !(added !== null && added > 0)) refuse(422, 'validation_failed', 'Type how many you are adding, more than 0.');
        if (added !== null && added > 0) move(branchId, p.id, added);
        return {
          product_id: p.id, code: p.code, description: p.description, unit: p.unit, item_made: !body.product_id, category_made: categoryMade, unit_made: unitMade,
          ...(added !== null ? { branch_id: branchId, qty: plainQty(fromMilli(added)), total_qty: plainQty(fromMilli(bal(branchId, p.id))), ...(added > 0 ? { movement_id: nextId('9') } : {}) } : {}),
        };
      })),

    // The valuation is read with retail.stock.read; the value at price, cost and profit columns need retail.profit.read (ADR-055).
    // Without a branch it covers every branch, with a total per branch, as the server does (#144).
    valuation: ({ branchId, asOf }) => {
      const bp = (profit: number, atCost: number): { expected_profit_bp?: number } => (atCost > 0 ? { expected_profit_bp: Math.round((profit * 10000) / atCost) } : {});
      const ids = branchId ? [branchId] : MOCK_BRANCHES.map((b) => b.id);
      const rows = ids.flatMap((id) => [...products.values()].map((p): ValuationRow => {
        const milli = bal(id, p.id);
        const sells = lineTotal(p.sellMinor, milli);
        const atCost = lineTotal(p.costMinor, milli);
        return cost({ branch_id: id, product_id: p.id, code: p.code, description: p.description, category_id: categoryId(p.category), category: p.category, unit: p.unit, qty: fromMilli(milli), negative: milli < 0, sell_minor: p.sellMinor },
          { expected_sales_minor: sells, cost_minor: p.costMinor, value_at_cost_minor: atCost, expected_profit_minor: sells - atCost, ...bp(sells - atCost, atCost) });
      }));
      const sum = (pick: (r: ValuationRow) => number | undefined, from: ValuationRow[] = rows) => from.reduce((s, r) => s + (pick(r) ?? 0), 0);
      const totals = (own: ValuationRow[]) => {
        const sells = sum((r) => r.expected_sales_minor, own);
        const atCost = sum((r) => r.value_at_cost_minor, own);
        return cost({}, { expected_sales_minor: sells, value_at_cost_minor: atCost, expected_profit_minor: sells - atCost, ...bp(sells - atCost, atCost) });
      };
      const branches = ids.map((id) => ({ branch_id: id, ...totals(rows.filter((r) => r.branch_id === id)) }));
      const categories = CATEGORIES.map((c) => ({ category_id: c.id, category: c.name, ...totals(rows.filter((r) => r.category_id === c.id)) }));
      const v: Valuation = { as_of: asOf ?? today(), currency: 'UGX', rows, branches, categories, ...totals(rows) };
      return delay(v);
    },

    stockedBranches: () => delay([BRANCH_A, BRANCH_B].filter((b) => [...products.values()].some((p) => bal(b, p.id) > 0))),

    branchRoles: () => delay(MOCK_BRANCHES.map((b) => ({ branch_id: b.id, code: b.code, name: b.name, role: roleOf(b.id), can_sell: roleOf(b.id) !== 'store' }))),

    setBranchRole: (branchId, role) => {
      const b = MOCK_BRANCHES.find((x) => x.id === branchId);
      if (storeBranches.includes(branchId)) refuse(422, 'branch_is_store', 'This branch is a store. Mark it as selling again on Branches first.');
      roles.set(branchId, role);
      return delay({ branch_id: branchId, code: b?.code, name: b?.name, role, can_sell: true });
    },

    capabilities: () => delay(read()),

    // The server's rule (RetailCapabilityGroup): a profile writes its preset first, then the flags sent win.
    changeCapabilities: (body, version) => {
      if (version !== caps.version) return Promise.reject(new RetailError(retailMessage({ code: 'version_conflict' }, 409), 409, 'version_conflict'));
      const next: Record<string, unknown> = { ...caps.value };
      if (body.profile) {
        next.profile = body.profile;
        for (const key of MOCK_GOVERNED) if (key !== 'units' || (MOCK_PRESETS[body.profile] ?? []).includes(key)) next[key] = (MOCK_PRESETS[body.profile] ?? []).includes(key);
      }
      for (const key of MOCK_FLAGS) if (typeof body[key] === 'boolean') next[key] = body[key];
      // The slow mover rules of Stock health (issue #414), whole numbers in the server's ranges.
      if (typeof body.slow_mover_months === 'number') next.slow_mover_months = body.slow_mover_months;
      if (typeof body.slow_mover_min_sold === 'number') next.slow_mover_min_sold = body.slow_mover_min_sold;
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
          age_days: Math.max(0, Math.floor((Date.parse(today()) - Date.parse((t.created_at ?? today()).slice(0, 10))) / day)), note: t.note, lines: visibleTransfer(t).lines,
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
function saleMatches(x: Sale, { branchId, from, to, buyer, paymentMethod, productId, sellerId, categoryId, status, owing }: Omit<SalesQuery, 'cursor' | 'signal'>, categoryOf: (productId?: string) => string | undefined): boolean {
  return (!branchId || x.branch_id === branchId) && (!sellerId || x.created_by === sellerId)
    && (!categoryId || (x.lines ?? []).some((l) => categoryOf(l.product_id) === categoryId)) && (!from || (x.sale_date ?? '') >= from) && (!to || (x.sale_date ?? '') <= to)
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
