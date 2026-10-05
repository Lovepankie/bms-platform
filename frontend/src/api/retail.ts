import { getAccessToken, refreshSession, tenantHeaders } from '../auth/session';
import { createMockRetail } from './retail-mock';

// The retail API client, written by hand against docs/api/retail-contract-draft.md because the
// retail paths are not in docs/api/openapi.json yet. When they are, replace the types below with
// `components['schemas'][...]` aliases and the `http` calls with `api.GET/POST` from ./client;
// the screens only use the `RetailApi` interface, so nothing else changes (docs/specs/retail-ui-notes.md).
// Money is integer minor units (`...Minor`), quantities are decimal strings with up to three places.
// Cost, cost snapshot and profit fields exist only for a user holding retail.profit.read.

/** The tenant currency is not on /me yet; retail tenants trade in shillings for now. */
export const RETAIL_CURRENCY = 'UGX';

export type SalePayment = 'cash' | 'mobile_money' | 'bank' | 'credit';
export type PurchasePayment = 'cash' | 'bank' | 'credit';

export interface Product {
  id: string;
  code: string;
  description: string;
  categoryId?: string;
  unit: string;
  sellMinor: number;
  costMinor?: number;
  active: boolean;
  qty?: string;
}

export interface StockRow {
  productId: string;
  description: string;
  unit: string;
  qty: string;
  negative: boolean;
  sellMinor: number;
  costMinor?: number;
}

export interface Customer {
  id: string;
  name: string;
  contact?: string;
}

export interface Supplier {
  id: string;
  name: string;
}

export interface SaleLineInput {
  productId: string;
  qty: string;
  unitPriceMinor?: number;
}

export interface SaleRequest {
  branchId?: string;
  saleDate?: string;
  paymentMethod: SalePayment;
  customerId?: string;
  buyerName?: string;
  buyerContact?: string;
  dueDate?: string;
  lines: SaleLineInput[];
}

export interface SaleLine {
  productId: string;
  description: string;
  qty: string;
  unitPriceMinor: number;
  lineTotalMinor: number;
  unitCostMinor?: number;
}

export interface Sale {
  id: string;
  branchId: string;
  saleDate: string;
  paymentMethod: SalePayment;
  buyerName?: string;
  dueDate?: string;
  lines: SaleLine[];
  totalMinor: number;
  balanceMinor: number;
  profitMinor?: number;
}

export interface PurchaseRequest {
  supplierId?: string;
  purchasedOn: string;
  paymentMethod: PurchasePayment;
  lines: {
    productId: string;
    costMinor: number;
    sellMinor?: number;
    qtyByBranch: { branchId: string; qty: string }[];
  }[];
}

export interface Purchase {
  id: string;
  purchasedOn: string;
  paymentMethod: PurchasePayment;
  lineCount: number;
  totalMinor: number;
  pricesUpdated: number;
}

export interface UsageRequest {
  branchId: string;
  kind: 'used' | 'damaged';
  reason: string;
  lines: { productId: string; qty: string }[];
}

export interface Usage {
  id: string;
  kind: 'used' | 'damaged';
  lineCount: number;
}

export interface StocktakeLine {
  productId: string;
  description?: string;
  expectedQty: string;
  countedQty: string;
  varianceQty: string;
}

export interface Stocktake {
  id: string;
  branchId: string;
  status: 'draft' | 'committed';
  lines: StocktakeLine[];
}

export interface ValuationRow {
  productId: string;
  description: string;
  qty: string;
  sellMinor: number;
  expectedSalesMinor: number;
  costMinor?: number;
  valueAtCostMinor?: number;
}

export interface Valuation {
  branchId: string;
  asOf: string;
  rows: ValuationRow[];
  totals: { expectedSalesMinor: number; valueAtCostMinor?: number };
}

export interface DailyProfitRow {
  branchId: string;
  date: string;
  salesMinor: number;
  costMinor: number;
  usageMinor: number;
  profitMinor: number;
}

export interface RetailApi {
  listProducts(q: { query?: string; branchId?: string }): Promise<Product[]>;
  listStock(q: { branchId: string; query?: string; negativeOnly?: boolean }): Promise<StockRow[]>;
  listCustomers(): Promise<Customer[]>;
  createCustomer(body: { name: string; contact?: string }): Promise<Customer>;
  listSuppliers(): Promise<Supplier[]>;
  createSupplier(body: { name: string }): Promise<Supplier>;
  createSale(body: SaleRequest, idempotencyKey: string): Promise<Sale>;
  createPurchase(body: PurchaseRequest, idempotencyKey: string): Promise<Purchase>;
  createUsage(body: UsageRequest, idempotencyKey: string): Promise<Usage>;
  createStocktake(body: { branchId: string; lines: { productId: string; countedQty: string }[]; note?: string }): Promise<Stocktake>;
  commitStocktake(id: string): Promise<Stocktake>;
  valuation(q: { branchId: string; asOf?: string }): Promise<Valuation>;
  dailyProfit(q: { branchId: string; from: string; to: string }): Promise<DailyProfitRow[]>;
}

export class RetailError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

const BASE = '/api/v1/retail';

function headers(extra: Record<string, string> = {}): Record<string, string> {
  const token = getAccessToken();
  return {
    Accept: 'application/json',
    ...tenantHeaders(),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  };
}

function search(params: Record<string, string | boolean | undefined>): string {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== '' && v !== false) q.set(k, String(v));
  });
  const text = q.toString();
  return text ? `?${text}` : '';
}

async function http<T>(method: 'GET' | 'POST', path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  const init = (): RequestInit => ({
    method,
    credentials: 'same-origin',
    headers: headers({
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
    }),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  let response = await fetch(`${BASE}${path}`, init());
  // An expired access token is refreshed once and a read is retried; writes are never replayed here.
  if (response.status === 401 && method === 'GET' && (await refreshSession())) {
    response = await fetch(`${BASE}${path}`, init());
  }
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as { detail?: string };
    throw new RetailError(problem.detail ?? 'The request failed.', response.status);
  }
  return (await response.json()) as T;
}

const realRetail: RetailApi = {
  listProducts: ({ query, branchId }) => http('GET', `/products${search({ query, branchId, active: true })}`),
  listStock: (q) => http('GET', `/stock${search(q)}`),
  listCustomers: () => http('GET', '/customers'),
  createCustomer: (body) => http('POST', '/customers', body),
  listSuppliers: () => http('GET', '/suppliers'),
  createSupplier: (body) => http('POST', '/suppliers', body),
  createSale: (body, key) => http('POST', '/sales', body, key),
  createPurchase: (body, key) => http('POST', '/purchases', body, key),
  createUsage: (body, key) => http('POST', '/usage', body, key),
  createStocktake: (body) => http('POST', '/stocktakes', body),
  commitStocktake: (id) => http('POST', `/stocktakes/${id}/commit`),
  valuation: (q) => http('GET', `/reports/valuation${search(q)}`),
  dailyProfit: (q) => http('GET', `/reports/profit/daily${search(q)}`),
};

/** True when VITE_RETAIL_MOCK=1: the screens then run on fabricated in-memory data. */
export const retailMockEnabled = (): boolean => import.meta.env.VITE_RETAIL_MOCK === '1';

/** The adapter every retail screen uses: the real client, or the fabricated mock. */
export const retail: RetailApi = retailMockEnabled() ? createMockRetail() : realRetail;
