import { api, problemOf } from './client';
import { retailMessage } from './retail-errors';
import { createMockRetail } from './retail-mock';
import type { components } from './schema';

// The retail API client, typed by the generated schema (ADR-009; `npm run gen:api`), as client.ts
// is. Names are the server's snake_case. Money is integer minor units (`..._minor`), quantities are
// decimal strings with up to three places. Fields the schema marks "present only with
// retail.profit.read" (cost, cost snapshot, profit) are absent from the body for any other caller.
// The screens use only `RetailApi`, so the fabricated mock (retail-mock.ts) can stand in for it.

type S = components['schemas'];

export type Product = S['RetailProduct'];
export type StockRow = S['RetailStockRow'];
export type Customer = S['RetailCustomer'];
export type Supplier = S['RetailSupplier'];
export type SaleRequest = S['RetailSaleRequest'];
export type Sale = S['RetailSale'];
export type SaleLine = S['RetailSaleLine'];
export type PurchaseRequest = S['RetailPurchaseRequest'];
export type Purchase = S['RetailPurchase'];
export type UsageRequest = S['RetailUsageRequest'];
export type Usage = S['RetailUsageReport'];
export type StocktakeRequest = S['RetailStocktakeRequest'];
export type Stocktake = S['RetailStocktake'];
export type StocktakeLine = S['RetailStocktakeLine'];
export type Valuation = S['RetailValuation'];
export type ValuationRow = S['RetailValuationRow'];
export type DailyProfit = S['RetailDailyProfit'];
export type DailyProfitRow = S['RetailDailyProfitRow'];

/** The tenant currency is not on /me yet; the retail pilot trades in shillings. */
export const RETAIL_CURRENCY = 'UGX';

export type SalePayment = 'cash' | 'mobile_money' | 'bank' | 'credit';
export type PurchasePayment = 'cash' | 'bank' | 'credit';

export interface RetailApi {
  listProducts(q: { query?: string; branchId?: string }): Promise<Product[]>;
  listStock(q: { branchId: string; query?: string; negativeOnly?: boolean }): Promise<StockRow[]>;
  listCustomers(): Promise<Customer[]>;
  listSuppliers(): Promise<Supplier[]>;
  createSupplier(body: { name: string }): Promise<Supplier>;
  createSale(body: SaleRequest, idempotencyKey: string): Promise<Sale>;
  createPurchase(body: PurchaseRequest, idempotencyKey: string): Promise<Purchase>;
  createUsage(body: UsageRequest, idempotencyKey: string): Promise<Usage>;
  createStocktake(body: StocktakeRequest): Promise<Stocktake>;
  commitStocktake(id: string): Promise<Stocktake>;
  valuation(q: { branchId: string; asOf?: string }): Promise<Valuation>;
  dailyProfit(q: { branchId: string; from: string; to: string }): Promise<DailyProfit>;
}

/** A failed retail call: `message` is plain words for people, `code` is the server's identifier. */
export class RetailError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

/** Unwraps an openapi-fetch result, turning a problem detail into a RetailError. */
function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    throw new RetailError(retailMessage(problem, result.response.status), result.response.status, problem.code);
  }
  return result.data;
}

const PAGE = 500;

const realRetail: RetailApi = {
  async listProducts({ query, branchId }) {
    const page = unwrap(
      await api.GET('/api/v1/retail/products', {
        params: { query: { query: query || undefined, branch_id: branchId, active: true, limit: 50 } },
      }),
    );
    return page.items ?? [];
  },

  async listStock({ branchId, query, negativeOnly }) {
    const rows: StockRow[] = [];
    let cursor: string | undefined;
    do {
      const page = unwrap(
        await api.GET('/api/v1/retail/stock', {
          params: { query: { branch_id: branchId, query: query || undefined, negative_only: negativeOnly || undefined, limit: PAGE, cursor } },
        }),
      );
      rows.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    return rows;
  },

  async listCustomers() {
    return unwrap(await api.GET('/api/v1/retail/customers', { params: { query: {} } })).items ?? [];
  },

  async listSuppliers() {
    return unwrap(await api.GET('/api/v1/retail/suppliers')).items ?? [];
  },

  async createSupplier(body) {
    return unwrap(await api.POST('/api/v1/retail/suppliers', { body }));
  },

  async createSale(body, key) {
    return unwrap(await api.POST('/api/v1/retail/sales', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async createPurchase(body, key) {
    return unwrap(await api.POST('/api/v1/retail/purchases', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async createUsage(body, key) {
    return unwrap(await api.POST('/api/v1/retail/usage', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async createStocktake(body) {
    return unwrap(await api.POST('/api/v1/retail/stocktakes', { body }));
  },

  async commitStocktake(id) {
    return unwrap(await api.POST('/api/v1/retail/stocktakes/{stocktake_id}/commit', { params: { path: { stocktake_id: id } } }));
  },

  async valuation({ branchId, asOf }) {
    return unwrap(await api.GET('/api/v1/retail/reports/valuation', { params: { query: { branch_id: [branchId], as_of: asOf } } }));
  },

  async dailyProfit({ branchId, from, to }) {
    return unwrap(await api.GET('/api/v1/retail/reports/profit/daily', { params: { query: { branch_id: [branchId], from, to } } }));
  },
};

/** True when VITE_RETAIL_MOCK is set (and not "0" or "false"): fabricated in-memory data, no backend. */
export const retailMockEnabled = (): boolean => {
  const flag = import.meta.env.VITE_RETAIL_MOCK;
  return flag !== undefined && flag !== '' && flag !== '0' && flag !== 'false';
};

/** The adapter every retail screen uses: the real client, or the fabricated mock. */
export const retail: RetailApi = retailMockEnabled() ? createMockRetail() : realRetail;
