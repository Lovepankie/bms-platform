import { api, problemOf } from './client';
import { retailMessage } from './retail-errors';
import type { components } from './schema';

// The retail API client, typed by the generated schema (ADR-009; `npm run gen:api`), as client.ts
// is. Names are the server's snake_case. Money is integer minor units (`..._minor`), quantities are
// decimal strings with up to three places. Fields the schema marks "present only with
// retail.profit.read" (cost, cost snapshot, profit) are absent from the body for any other caller.
// The screens use only `RetailApi`, so the fabricated mock (retail-mock.ts) can stand in for it. The
// mock is imported dynamically behind VITE_RETAIL_MOCK, so a production build leaves it out (#77).

type S = components['schemas'];

export type Product = S['RetailProduct'];
export type StockRow = S['RetailStockRow'];
export type AllBranchesRow = S['RetailAllBranchesRow'];
export type StockBranch = S['RetailStockBranch'];
export type AllBranchesStock = { branches: StockBranch[]; items: AllBranchesRow[] };
export type Category = S['RetailCategory'];
export type Unit = S['RetailUnit'];
export type ProductPage = S['RetailProductPage'];
export type PriceChange = S['RetailPriceChange'];
export type NewProduct = S['CreateRetailProductRequest'];
export type ProductChange = S['UpdateRetailProductRequest'];
export type PriceEdit = S['RetailPriceEditRequest'];
export type ImportResult = S['RetailProductImportResult'];
export type ImportRow = S['RetailProductImportRow'];
export type Customer = S['RetailCustomer'];
export type CustomerPage = S['RetailCustomerList'];
export type Supplier = S['RetailSupplier'];
export type SaleRequest = S['RetailSaleRequest'];
export type Sale = S['RetailSale'];
export type SaleLine = S['RetailSaleLine'];
export type SalePage = S['RetailSalePage'];
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
export type TransferRequest = S['RetailTransferRequest'];
export type Transfer = S['RetailTransfer'];
export type TransferLine = S['RetailTransferLine'];
export type TransferPage = S['RetailTransferPage'];
export type Savings = S['RetailSavings'];
export type SavingsRequest = S['RetailSavingsRequest'];
export type SavingsSuggestion = S['RetailSavingsSuggestion'];
export type Banking = S['RetailBanking'];
export type BankingRequest = S['RetailBankingRequest'];
export type BankingExpected = S['RetailBankingExpected'];
export type BankingDay = S['RetailBankingDay'];
export type BankingReport = S['RetailBankingReport'];
export type Withdrawal = S['RetailWithdrawal'];
export type WithdrawalRequest = S['RetailWithdrawalRequest'];
export type Expense = S['RetailExpense'];
export type ExpenseRequest = S['RetailExpenseRequest'];
export type ExpenseReport = S['RetailExpenseReport'];
export type ExpenseGroup = S['RetailExpenseGroup'];
export type ExpenseCategory = S['RetailCashExpenseCategory'];
export type ExpenseItem = S['RetailCashExpenseItem'];
export type CashParty = S['RetailCashParty'];
export type CashPartyRequest = S['RetailCashPartyRequest'];
export type CashPartyKind = CashPartyRequest['kind'];
export type Advance = S['RetailAdvance'];
export type AdvanceRequest = S['RetailAdvanceRequest'];
export type Repayment = S['RetailRepayment'];
export type RepaymentRequest = S['RetailRepaymentRequest'];
export type AdvanceReport = S['RetailAdvanceReport'];
export type AdvanceParty = S['RetailAdvanceParty'];
export type CashDailyReport = S['RetailCashDailyReport'];
export type CashDailyRow = S['RetailCashDailyRow'];

/** The tenant currency is not on /me yet; the retail pilot trades in shillings. */
export const RETAIL_CURRENCY = 'UGX';

/**
 * The tenant's timezone is not on /me or the tenant settings yet; the server dates retail events in
 * Africa/Kampala unless the tenant profile says otherwise (BusinessClock), so the forms do too.
 */
export const RETAIL_ZONE = 'Africa/Kampala';

/** Today as yyyy-mm-dd in the business's timezone, not the browser's or UTC (#112 items 3 and 9). */
export function businessToday(now: Date = new Date(), zone: string = RETAIL_ZONE): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

/** A yyyy-mm-dd date `days` before `date`, on the calendar (no timezone involved). */
export function daysBefore(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

/** Stock lists can show only items out of stock (zero or less) or low (at or below the server's threshold, 5). */
export type StockLevel = 'out' | 'low';

export interface SalesQuery {
  /** One branch; absent means every branch the caller may read. */
  branchId?: string;
  from?: string;
  to?: string;
  buyer?: string;
  paymentMethod?: SalePayment;
  productId?: string;
  status?: 'completed' | 'voided';
  /** Credit sales only: still owing, overdue, or fully paid. Filtered on the server, across every page. */
  owing?: 'owing' | 'overdue' | 'paid';
  cursor?: string;
}

/** The query string of a sales list: newest first, 50 a page, empty filters left out. */
export function salesParams({ branchId, from, to, buyer, paymentMethod, productId, status, owing, cursor }: SalesQuery) {
  return {
    branch_id: branchId ? [branchId] : undefined, from: from || undefined, to: to || undefined, buyer: buyer || undefined,
    payment_method: paymentMethod, product_id: productId || undefined, status, owing, newest_first: true, limit: 50, cursor,
  };
}

/** A cash book list or report window: branches (empty means every branch in scope), dates, voided rows. */
export interface CashQuery {
  branchIds?: string[];
  from?: string;
  to?: string;
  includeVoided?: boolean;
}

/** The query string of a cash book list: empty filters left out. */
export function cashParams({ branchIds, from, to, includeVoided }: CashQuery) {
  return { branch_id: branchIds && branchIds.length > 0 ? branchIds : undefined, from: from || undefined, to: to || undefined, include_voided: includeVoided || undefined };
}

export type SalePayment = 'cash' | 'mobile_money' | 'bank' | 'credit';
export type PurchasePayment = 'cash' | 'bank' | 'credit';

export interface RetailApi {
  listProducts(q: { query?: string; branchId?: string }): Promise<Product[]>;
  listCategories(): Promise<Category[]>;
  /** Catalogue management (#146): one page of products, with the filters of the Products screen. */
  listCatalogue(q: { query?: string; categoryId?: string; active?: boolean; cursor?: string }): Promise<ProductPage>;
  getProduct(id: string): Promise<Product>;
  createProduct(body: NewProduct): Promise<Product>;
  /** Non-price fields, under If-Match with the version read. */
  updateProduct(id: string, version: number, body: ProductChange): Promise<Product>;
  /** A price change under If-Match; the server writes the history row. */
  editPrices(id: string, version: number, body: PriceEdit): Promise<Product>;
  priceHistory(id: string): Promise<PriceChange[]>;
  /** The CSV item import: a dry run reports, an apply adds the rows that are fine (administrators only). */
  importProducts(csv: string, dryRun: boolean): Promise<ImportResult>;
  createCategory(name: string): Promise<Category>;
  updateCategory(id: string, version: number, body: { name?: string; active?: boolean }): Promise<Category>;
  listUnits(): Promise<Unit[]>;
  createUnit(name: string): Promise<Unit>;
  updateUnit(id: string, version: number, body: { name?: string; active?: boolean }): Promise<Unit>;
  listStock(q: { branchId: string; query?: string; categoryId?: string; negativeOnly?: boolean; level?: StockLevel }): Promise<StockRow[]>;
  /** One page of sales, newest first, narrowed by the filters (#145). */
  listSales(q: SalesQuery): Promise<SalePage>;
  getSale(id: string): Promise<Sale>;
  listCustomers(): Promise<Customer[]>;
  /** The credit buyers screen: server-side search and cursor paging. */
  listCustomerPage(q: { query?: string; cursor?: string }): Promise<CustomerPage>;
  listSuppliers(): Promise<Supplier[]>;
  createSupplier(body: { name: string; contact?: string }): Promise<Supplier>;
  updateSupplier(id: string, version: number, body: { name?: string; contact?: string; active?: boolean }): Promise<Supplier>;
  createCustomer(body: { name: string; contact?: string }): Promise<Customer>;
  updateCustomer(id: string, version: number, body: { name?: string; contact?: string }): Promise<Customer>;
  createSale(body: SaleRequest, idempotencyKey: string): Promise<Sale>;
  createPurchase(body: PurchaseRequest, idempotencyKey: string): Promise<Purchase>;
  createUsage(body: UsageRequest, idempotencyKey: string): Promise<Usage>;
  createStocktake(body: StocktakeRequest): Promise<Stocktake>;
  commitStocktake(id: string): Promise<Stocktake>;
  /** Every product with its balance in each branch the caller may read (#144). */
  listStockAllBranches(q: { query?: string; categoryId?: string; negativeOnly?: boolean; level?: StockLevel }): Promise<AllBranchesStock>;
  /** One branch, or every branch in the caller's scope when `branchId` is absent (#144). */
  valuation(q: { branchId?: string; asOf?: string }): Promise<Valuation>;
  /** The branches in the caller's stock scope holding a quantity above zero of anything (#103). */
  stockedBranches(): Promise<string[]>;
  dailyProfit(q: { branchId?: string; from: string; to: string }): Promise<DailyProfit>;
  createTransfer(body: TransferRequest, idempotencyKey: string): Promise<Transfer>;
  listTransfers(q: { branchId?: string; cursor?: string }): Promise<TransferPage>;
  getTransfer(id: string): Promise<Transfer>;
  voidTransfer(id: string, reason: string): Promise<Transfer>;

  // The cash book (FR-RET-17 to FR-RET-29, ADR-022). Every write takes the Idempotency-Key of its draft;
  // a void takes one too. Figures the schema marks "present only with retail.profit.read" are absent
  // from a body for any other caller, and the screens never infer them.
  savingsSuggestion(q: { branchId: string; date: string }): Promise<SavingsSuggestion>;
  createSavings(body: SavingsRequest, idempotencyKey: string): Promise<Savings>;
  listSavings(q: CashQuery): Promise<Savings[]>;
  voidSavings(id: string, reason: string, idempotencyKey: string): Promise<Savings>;
  bankingExpected(q: { branchId: string; date: string }): Promise<BankingExpected>;
  createBanking(body: BankingRequest, idempotencyKey: string): Promise<Banking>;
  listBankings(q: CashQuery): Promise<Banking[]>;
  voidBanking(id: string, reason: string, idempotencyKey: string): Promise<Banking>;
  createWithdrawal(body: WithdrawalRequest, idempotencyKey: string): Promise<Withdrawal>;
  listWithdrawals(q: CashQuery): Promise<Withdrawal[]>;
  voidWithdrawal(id: string, reason: string, idempotencyKey: string): Promise<Withdrawal>;
  listExpenseCategories(q?: { activeOnly?: boolean }): Promise<ExpenseCategory[]>;
  createExpenseCategory(body: { name: string }): Promise<ExpenseCategory>;
  /** `version` is the one the list showed; the server answers 409 version_conflict when it is stale. */
  updateExpenseCategory(id: string, body: { name?: string; active?: boolean }, version: number): Promise<ExpenseCategory>;
  createExpenseItem(categoryId: string, body: { name: string; requires_explanation?: boolean }): Promise<ExpenseItem>;
  updateExpenseItem(categoryId: string, itemId: string, body: { name?: string; active?: boolean; requires_explanation?: boolean }, version: number): Promise<ExpenseItem>;
  listCashParties(q?: { kind?: CashPartyKind }): Promise<CashParty[]>;
  createCashParty(body: CashPartyRequest): Promise<CashParty>;
  createExpense(body: ExpenseRequest, idempotencyKey: string): Promise<Expense>;
  listExpenses(q: CashQuery & { categoryId?: string; itemId?: string }): Promise<Expense[]>;
  voidExpense(id: string, reason: string, idempotencyKey: string): Promise<Expense>;
  createAdvance(body: AdvanceRequest, idempotencyKey: string): Promise<Advance>;
  listAdvances(q: CashQuery & { partyId?: string; openOnly?: boolean }): Promise<Advance[]>;
  getAdvance(id: string): Promise<Advance>;
  voidAdvance(id: string, reason: string, idempotencyKey: string): Promise<Advance>;
  createRepayment(advanceId: string, body: RepaymentRequest, idempotencyKey: string): Promise<Repayment>;
  voidRepayment(advanceId: string, repaymentId: string, reason: string, idempotencyKey: string): Promise<Repayment>;
  cashDaily(q: CashQuery): Promise<CashDailyReport>;
  bankingReport(q: CashQuery & { flag?: string }): Promise<BankingReport>;
  expensesReport(q: CashQuery & { groupBy: 'category' | 'item' | 'branch' | 'month' }): Promise<ExpenseReport>;
  advancesReport(q: { branchIds?: string[] }): Promise<AdvanceReport>;
}

/** A failed retail call: `message` is plain words for people, `code` is the server's identifier. */
export class RetailError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    /** The whole problem body, for the few refusals that carry data (a 409 suggestion_changed brings the new token). */
    readonly problem?: Record<string, unknown>,
  ) {
    super(message);
  }
}

/** Unwraps an openapi-fetch result, turning a problem detail into a RetailError. */
function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    throw new RetailError(retailMessage(problem, result.response.status), result.response.status, problem.code, problem as Record<string, unknown>);
  }
  return result.data;
}

const PAGE = 500;

/** Every page of a cursor-paged list, in the server's order. */
async function allPages<T>(
  fetchPage: (cursor?: string) => Promise<{ data?: { items?: T[]; next_cursor?: string }; error?: unknown; response: Response }>,
): Promise<T[]> {
  const rows: T[] = [];
  let cursor: string | undefined;
  do {
    const page = unwrap(await fetchPage(cursor));
    rows.push(...(page.items ?? []));
    cursor = page.next_cursor;
  } while (cursor);
  return rows;
}

const realRetail: RetailApi = {
  async listProducts({ query, branchId }) {
    const page = unwrap(
      await api.GET('/api/v1/retail/products', {
        params: { query: { query: query || undefined, branch_id: branchId, active: true, limit: 50 } },
      }),
    );
    return page.items ?? [];
  },

  async listCategories() {
    return unwrap(await api.GET('/api/v1/retail/categories')).items ?? [];
  },

  async listCatalogue({ query, categoryId, active, cursor }) {
    return unwrap(
      await api.GET('/api/v1/retail/products', {
        params: { query: { query: query || undefined, category_id: categoryId || undefined, active, limit: 50, cursor } },
      }),
    );
  },

  async getProduct(id) {
    return unwrap(await api.GET('/api/v1/retail/products/{product_id}', { params: { path: { product_id: id } } }));
  },

  async createProduct(body) {
    return unwrap(await api.POST('/api/v1/retail/products', { body }));
  },

  async updateProduct(id, version, body) {
    return unwrap(
      await api.PATCH('/api/v1/retail/products/{product_id}', { params: { path: { product_id: id }, header: { 'If-Match': `"${version}"` } }, body }),
    );
  },

  async editPrices(id, version, body) {
    return unwrap(
      await api.POST('/api/v1/retail/products/{product_id}/prices', { params: { path: { product_id: id }, header: { 'If-Match': `"${version}"` } }, body }),
    );
  },

  async priceHistory(id) {
    return unwrap(await api.GET('/api/v1/retail/products/{product_id}/price-history', { params: { path: { product_id: id } } })).items ?? [];
  },

  async importProducts(csv, dryRun) {
    return unwrap(await api.POST('/api/v1/retail/products/import', { params: { query: { dry_run: dryRun } }, body: { csv } }));
  },

  async createCategory(name) {
    return unwrap(await api.POST('/api/v1/retail/categories', { body: { name } }));
  },

  async updateCategory(id, version, body) {
    return unwrap(await api.PATCH('/api/v1/retail/categories/{category_id}', { params: { path: { category_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
  },

  async listUnits() {
    return unwrap(await api.GET('/api/v1/retail/units')).items ?? [];
  },

  async createUnit(name) {
    return unwrap(await api.POST('/api/v1/retail/units', { body: { name } }));
  },

  async updateUnit(id, version, body) {
    return unwrap(await api.PATCH('/api/v1/retail/units/{unit_id}', { params: { path: { unit_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
  },

  async listStock({ branchId, query, categoryId, negativeOnly, level }) {
    const rows: StockRow[] = [];
    let cursor: string | undefined;
    do {
      const page = unwrap(
        await api.GET('/api/v1/retail/stock', {
          params: { query: { branch_id: branchId, query: query || undefined, category_id: categoryId || undefined, negative_only: negativeOnly || undefined, stock_level: level, limit: PAGE, cursor } },
        }),
      );
      rows.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    return rows;
  },

  async listStockAllBranches({ query, categoryId, negativeOnly, level }) {
    const items: AllBranchesRow[] = [];
    let branches: StockBranch[] = [];
    let cursor: string | undefined;
    do {
      const page = unwrap(
        await api.GET('/api/v1/retail/stock/all-branches', {
          params: { query: { query: query || undefined, category_id: categoryId || undefined, negative_only: negativeOnly || undefined, stock_level: level, limit: PAGE, cursor } },
        }),
      );
      branches = page.branches ?? branches;
      items.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    return { branches, items };
  },

  async listSales(q) {
    return unwrap(await api.GET('/api/v1/retail/sales', { params: { query: salesParams(q) } }));
  },

  async getSale(id) {
    return unwrap(await api.GET('/api/v1/retail/sales/{sale_id}', { params: { path: { sale_id: id } } }));
  },

  async listCustomers() {
    return unwrap(await api.GET('/api/v1/retail/customers', { params: { query: {} } })).items ?? [];
  },

  async listCustomerPage({ query, cursor }) {
    return unwrap(await api.GET('/api/v1/retail/customers', { params: { query: { query: query || undefined, limit: 50, cursor } } }));
  },

  async listSuppliers() {
    return unwrap(await api.GET('/api/v1/retail/suppliers')).items ?? [];
  },

  async createSupplier(body) {
    return unwrap(await api.POST('/api/v1/retail/suppliers', { body }));
  },

  async updateSupplier(id, version, body) {
    return unwrap(await api.PATCH('/api/v1/retail/suppliers/{supplier_id}', { params: { path: { supplier_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
  },

  async createCustomer(body) {
    return unwrap(await api.POST('/api/v1/retail/customers', { body }));
  },

  async updateCustomer(id, version, body) {
    return unwrap(await api.PATCH('/api/v1/retail/customers/{customer_id}', { params: { path: { customer_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
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
    return unwrap(await api.GET('/api/v1/retail/reports/valuation', { params: { query: { branch_id: branchId ? [branchId] : undefined, as_of: asOf } } }));
  },

  async stockedBranches() {
    const all = unwrap(await api.GET('/api/v1/retail/reports/valuation', { params: { query: {} } }));
    return [...new Set((all.rows ?? []).filter((r) => r.qty !== undefined && !r.qty.startsWith('-')).map((r) => r.branch_id ?? ''))];
  },

  async dailyProfit({ branchId, from, to }) {
    return unwrap(await api.GET('/api/v1/retail/reports/profit/daily', { params: { query: { branch_id: branchId ? [branchId] : undefined, from, to } } }));
  },

  async createTransfer(body, key) {
    return unwrap(await api.POST('/api/v1/retail/transfers', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async listTransfers({ branchId, cursor }) {
    return unwrap(
      await api.GET('/api/v1/retail/transfers', { params: { query: { branch_id: branchId ? [branchId] : undefined, limit: 50, cursor } } }),
    );
  },

  async getTransfer(id) {
    return unwrap(await api.GET('/api/v1/retail/transfers/{transfer_id}', { params: { path: { transfer_id: id } } }));
  },

  async voidTransfer(id, reason) {
    return unwrap(await api.POST('/api/v1/retail/transfers/{transfer_id}/void', { params: { path: { transfer_id: id } }, body: { reason } }));
  },

  // The cash book. Lists read every page of the window asked for (200 a page, the most the server allows).
  async savingsSuggestion({ branchId, date }) {
    return unwrap(await api.GET('/api/v1/retail/savings/suggestion', { params: { query: { branch_id: branchId, date } } }));
  },

  async createSavings(body, key) {
    return unwrap(await api.POST('/api/v1/retail/savings', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async listSavings(q) {
    return allPages((cursor) => api.GET('/api/v1/retail/savings', { params: { query: { ...cashParams(q), limit: 200, cursor } } }));
  },

  async voidSavings(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/savings/{savings_id}/void', { params: { path: { savings_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async bankingExpected({ branchId, date }) {
    return unwrap(await api.GET('/api/v1/retail/bankings/expected', { params: { query: { branch_id: branchId, date } } }));
  },

  async createBanking(body, key) {
    return unwrap(await api.POST('/api/v1/retail/bankings', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async listBankings(q) {
    return allPages((cursor) => api.GET('/api/v1/retail/bankings', { params: { query: { ...cashParams(q), limit: 200, cursor } } }));
  },

  async voidBanking(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/bankings/{banking_id}/void', { params: { path: { banking_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async createWithdrawal(body, key) {
    return unwrap(await api.POST('/api/v1/retail/withdrawals', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async listWithdrawals(q) {
    return allPages((cursor) => api.GET('/api/v1/retail/withdrawals', { params: { query: { ...cashParams(q), limit: 200, cursor } } }));
  },

  async voidWithdrawal(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/withdrawals/{withdrawal_id}/void', { params: { path: { withdrawal_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async listExpenseCategories(q) {
    return unwrap(await api.GET('/api/v1/retail/expense-categories', { params: { query: { active: q?.activeOnly ? true : undefined } } })).items ?? [];
  },

  async createExpenseCategory(body) {
    return unwrap(await api.POST('/api/v1/retail/expense-categories', { body }));
  },

  async updateExpenseCategory(id, body, version) {
    return unwrap(await api.PATCH('/api/v1/retail/expense-categories/{category_id}', { params: { path: { category_id: id }, header: { 'If-Match': String(version) } }, body }));
  },

  async createExpenseItem(categoryId, body) {
    return unwrap(await api.POST('/api/v1/retail/expense-categories/{category_id}/items', { params: { path: { category_id: categoryId } }, body }));
  },

  async updateExpenseItem(categoryId, itemId, body, version) {
    return unwrap(
      await api.PATCH('/api/v1/retail/expense-categories/{category_id}/items/{item_id}', {
        params: { path: { category_id: categoryId, item_id: itemId }, header: { 'If-Match': String(version) } }, body,
      }),
    );
  },

  async listCashParties(q) {
    return allPages((cursor) => api.GET('/api/v1/retail/cash-parties', { params: { query: { kind: q?.kind, limit: 200, cursor } } }));
  },

  async createCashParty(body) {
    return unwrap(await api.POST('/api/v1/retail/cash-parties', { body }));
  },

  async createExpense(body, key) {
    return unwrap(await api.POST('/api/v1/retail/expenses', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async listExpenses(q) {
    return allPages((cursor) =>
      api.GET('/api/v1/retail/expenses', { params: { query: { ...cashParams(q), category_id: q.categoryId || undefined, item_id: q.itemId || undefined, limit: 200, cursor } } }),
    );
  },

  async voidExpense(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/expenses/{expense_id}/void', { params: { path: { expense_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async createAdvance(body, key) {
    return unwrap(await api.POST('/api/v1/retail/advances', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async listAdvances(q) {
    const { branch_id, from, to } = cashParams(q);
    return allPages((cursor) =>
      api.GET('/api/v1/retail/advances', { params: { query: { branch_id, from, to, party_id: q.partyId || undefined, open_only: q.openOnly || undefined, limit: 200, cursor } } }),
    );
  },

  async getAdvance(id) {
    return unwrap(await api.GET('/api/v1/retail/advances/{advance_id}', { params: { path: { advance_id: id } } }));
  },

  async voidAdvance(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/advances/{advance_id}/void', { params: { path: { advance_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async createRepayment(advanceId, body, key) {
    return unwrap(await api.POST('/api/v1/retail/advances/{advance_id}/repayments', { params: { path: { advance_id: advanceId }, header: { 'Idempotency-Key': key } }, body }));
  },

  async voidRepayment(advanceId, repaymentId, reason, key) {
    return unwrap(
      await api.POST('/api/v1/retail/advances/{advance_id}/repayments/{repayment_id}/void', {
        params: { path: { advance_id: advanceId, repayment_id: repaymentId }, header: { 'Idempotency-Key': key } }, body: { reason },
      }),
    );
  },

  async cashDaily(q) {
    const { branch_id, from, to } = cashParams(q);
    return unwrap(await api.GET('/api/v1/retail/reports/cash/daily', { params: { query: { branch_id, from, to } } }));
  },

  async bankingReport(q) {
    const { branch_id, from, to } = cashParams(q);
    return unwrap(await api.GET('/api/v1/retail/reports/cash/banking', { params: { query: { branch_id, from, to, flag: q.flag || undefined } } }));
  },

  async expensesReport(q) {
    return unwrap(await api.GET('/api/v1/retail/reports/cash/expenses', { params: { query: { ...cashParams(q), group_by: q.groupBy } } }));
  },

  async advancesReport({ branchIds }) {
    return unwrap(await api.GET('/api/v1/retail/reports/cash/advances', { params: { query: { branch_id: branchIds && branchIds.length > 0 ? branchIds : undefined } } }));
  },
};

/** True when VITE_RETAIL_MOCK is set (and not "0" or "false"): fabricated in-memory data, no backend. */
export const retailMockEnabled = (): boolean => {
  const flag = import.meta.env.VITE_RETAIL_MOCK;
  return flag !== undefined && flag !== '' && flag !== '0' && flag !== 'false';
};

type MockModule = typeof import('./retail-mock');

// Statically false in a build without VITE_RETAIL_MOCK, so the bundler drops the import below.
const mockModule: Promise<MockModule> | null = import.meta.env.VITE_RETAIL_MOCK && retailMockEnabled() ? import('./retail-mock') : null;

/** The mock's state and fake session, loaded only when the mock switch is on. */
export function loadRetailMock(): Promise<MockModule> {
  if (!mockModule) return Promise.reject(new Error('VITE_RETAIL_MOCK is off'));
  return mockModule;
}

function lazyMock(module: Promise<MockModule>): RetailApi {
  const api = module.then((m) => m.createMockRetail());
  const adapter = {} as Record<keyof RetailApi, unknown>;
  for (const name of Object.keys(realRetail) as (keyof RetailApi)[]) {
    adapter[name] = (...args: unknown[]) => api.then((m) => (m[name] as (...a: unknown[]) => Promise<unknown>)(...args));
  }
  return adapter as RetailApi;
}

/** The adapter every retail screen uses: the real client, or the fabricated mock. */
export const retail: RetailApi = mockModule ? lazyMock(mockModule) : realRetail;
