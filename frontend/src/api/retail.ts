import { api, problemOf } from './client';
import { retailMessage } from './retail-errors';
import { postFile } from './lending-forms';
import type { components } from './schema';

// The retail API client, typed by the generated schema (ADR-009; `npm run gen:api`), as client.ts
// is. Names are the server's snake_case. Money is integer minor units (`..._minor`), quantities are
// decimal strings with up to three places. Fields the schema marks "present only with
// retail.profit.read" (cost, cost snapshot, profit) are absent from the body for any other caller.
// The screens use only `RetailApi`, so the fabricated mock (retail-mock.ts) can stand in for it. The
// mock is imported dynamically behind VITE_RETAIL_MOCK, so a production build leaves it out (#77).

type S = components['schemas'];

export type Product = S['RetailProduct'];
/** A barcode of an item or one of its pack sizes (ADR-070, issue #345). */
export type Barcode = S['RetailBarcode'];
/** The active item a scanned barcode belongs to, and the pack it names (`unit_id`), if any. */
export type BarcodeMatch = S['RetailBarcodeMatch'];
/** A pack size of an item (units of measure, ADR-033): `factor` base units in one pack of `unit`, three places ("12.000"). */
export type Pack = S['RetailPack'];
export type StockRow = S['RetailStockRow'];
/** A quantity corrected on the stock list (issue #351): the old and new quantity, why, who and when. */
export type StockCorrection = S['RetailStockCorrection'];
export type StockCorrectionRequest = S['RetailStockCorrectionRequest'];
/** One line of a product's stock history; a correction carries `correction` (why, and the quantity before and after). */
export type StockMovement = S['RetailStockMovement'];
export type AllBranchesRow = S['RetailAllBranchesRow'];
export type StockBranch = S['RetailStockBranch'];
export type AllBranchesStock = { branches: StockBranch[]; items: AllBranchesRow[]; closed_branches?: number };
export type BranchRole = S['RetailBranchRole'];
export type RetailCapabilities = S['RetailCapabilities'];
export type CapabilityChange = S['UpdateRetailCapabilitiesRequest'];
/** The capabilities with the settings version they were read at, the If-Match of the next change. */
export interface CapabilitiesRead { capabilities: RetailCapabilities; version: number }
export type Category = S['RetailCategory'];
export type Unit = S['RetailUnit'];
export type ProductPage = S['RetailProductPage'];
export type PriceChange = S['RetailPriceChange'];
export type NewProduct = S['CreateRetailProductRequest'];
export type ProductChange = S['UpdateRetailProductRequest'];
/** An item with sizes and colours (issue #384, ADR-051): its variants are products, each with its own code, prices and stock. */
export type Family = S['RetailFamily'];
export type NewFamily = S['CreateRetailFamilyRequest'];
export type FamilyChange = S['UpdateRetailFamilyRequest'];
export type AddVariants = S['AddRetailVariantsRequest'];
export type MakeFamily = S['RetailMakeFamilyRequest'];
export type VariantLine = S['RetailVariantLine'];
/** Items kept apart until now (one per size) becoming the sizes and colours of one item (issue #384). */
export type JoinRequest = S['RetailJoinRequest'];
/** The item a product is a variant of, and its size and colour; absent for an item without variants. */
export type VariantOf = S['RetailProductVariantOf'];
export type PriceEdit = S['RetailPriceEditRequest'];
/** A product with this name or a close one (issue #352): `exact` is the same name in the same unit, which a save refuses. */
export type SimilarProducts = S['RetailSimilarProducts'];
export type SimilarProduct = S['RetailSimilarProduct'];
/** Issue #353, ADR-093: an item's recent history the entry screens warn against, and the hints while naming a new item. */
export type EntryNorms = S['RetailEntryNorms'];
export type EntryUsage = S['RetailEntryUsage'];
export type NameHints = S['RetailNameHints'];
export type EntryConfirmation = S['RetailEntryConfirmation'];
export type ImportResult = S['RetailProductImportResult'];
export type ImportRow = S['RetailProductImportRow'];
export type Customer = S['RetailCustomer'];
export type SaleReceipt = S['RetailSaleReceipt'];
export type ReceiptPreview = S['RetailReceiptPreview'];
export type CustomerPage = S['RetailCustomerList'];
/** Customer accounts (ADR-033, issue #196): the credit limit and terms, the statement, payment on account. Money is integer minor units. */
export type CustomerAccount = S['RetailCustomerAccount'];
export type AccountEdit = S['RetailCustomerAccountRequest'];
export type Statement = S['RetailCustomerStatement'];
export type StatementLine = S['RetailStatementLine'];
export type CustomerBalance = S['RetailCustomerBalance'];
/** A credit sale that still owes; `historical` (an imported sale) is skipped by payment on account. */
export type OpenSale = S['RetailOpenSale'];
export type AccountPayment = S['RetailAccountPayment'];
export type AccountPaymentRequest = S['RetailAccountPaymentRequest'];
/** One payment against one credit sale (FR-RET-05) and the sale's figures after it. */
export type SalePaymentRequest = S['RetailPaymentRequest'];
/** The write-off of a credit sale's unpaid part (ADR-043): done at once, or waiting for a second administrator. */
export type WriteOffOutcome = S['RetailWriteOffOutcome'];
export type SalePaymentResult = S['RetailPaymentResult'];
export type AccountPaymentPage = S['RetailAccountPaymentList'];
/** Optional documents (documents capability, ADR-033, issue #197): quotations, invoices and delivery notes, numbered per branch. */
export type RetailDocument = S['RetailDocument'];
export type DocumentPage = S['RetailDocumentPage'];
export type DocumentRequest = S['RetailDocumentRequest'];
export type DocumentSeries = S['RetailDocumentSeries'];
export type Quotation = S['RetailQuotation'];
export type QuotationLine = S['RetailQuotationLine'];
export type QuotationPage = S['RetailQuotationPage'];
export type QuotationRequest = S['RetailQuotationRequest'];
export type ConvertRequest = S['RetailQuotationConvertRequest'];
export type Converted = S['RetailQuotationConverted'];
export type DocumentType = 'quotation' | 'invoice' | 'delivery_note';
/** The credit note PDF of a return, under the return's own number (documents and returns capabilities). */
export type CreditNoteDocument = S['RetailCreditNoteDocument'];
/** Customer returns and sales credit notes (the returns capability, ADR-033, issue #201). Money is integer minor units, quantities three places. */
export type ReturnRequest = S['RetailReturnRequest'];
export type ReturnLineRequest = S['RetailReturnLineRequest'];
export type SaleReturn = S['RetailSaleReturn'];
export type ReturnLine = S['RetailReturnLine'];
/** 201 with the return when it was recorded; 202 with `approval_id` when it waits for a second administrator. */
export type ReturnOutcome = S['RetailReturnOutcome'];
export type ReturnPage = S['RetailReturnList'];
// JUSTIFICATION-A3: the purchasing capability's types are new API surface (ADR-033, issue #198); nothing existing carries them.
/**
 * Optional retail purchasing (the purchasing capability, ADR-033, issue #198): purchase orders, goods received, supplier invoices
 * ("bills"), supplier payments and the payables report. Money is integer minor units; quantities are decimal strings with three
 * places, in base units unless a field says it is in the unit entered.
 */
export type PurchaseOrder = S['RetailPurchaseOrder'];
export type PurchaseOrderLine = S['RetailPurchaseOrderLine'];
export type PurchaseOrderRequest = S['RetailPurchaseOrderRequest'];
export type PurchaseOrderLineRequest = S['RetailPurchaseOrderLineRequest'];
/** 200 with the order when it was sent; 202 with `approval_id` when it waits for a checker (retail.purchase.approve). */
export type PurchaseOrderOutcome = S['RetailPurchaseOrderOutcome'];
export type PurchaseOrderPage = S['RetailPurchaseOrderList'];
export type GoodsReceipt = S['RetailGoodsReceipt'];
export type GoodsReceiptLine = S['RetailGoodsReceiptLine'];
export type GoodsReceiptRequest = S['RetailGoodsReceiptRequest'];
export type GoodsReceiptLineRequest = S['RetailGoodsReceiptLineRequest'];
export type GoodsReceiptPage = S['RetailGoodsReceiptList'];
export type SupplierBill = S['RetailSupplierBill'];
export type SupplierBillLine = S['RetailSupplierBillLine'];
export type SupplierBillRequest = S['RetailSupplierBillRequest'];
export type SupplierBillLineRequest = S['RetailSupplierBillLineRequest'];
export type SupplierBillMatch = S['RetailSupplierBillMatch'];
export type SupplierBillPage = S['RetailSupplierBillList'];
export type UnbilledReceipts = S['RetailUnbilledReceiptList'];
export type UnbilledReceiptLine = S['RetailUnbilledReceiptLine'];
export type SupplierPayment = S['RetailSupplierPayment'];
export type SupplierPaymentRequest = S['RetailSupplierPaymentRequest'];
export type SupplierPaymentAllocation = S['RetailSupplierPaymentAllocationRequest'];
export type SupplierPaymentPage = S['RetailSupplierPaymentList'];
/** A supplier invoice (`bill`) or a credit restock (`restock`) that still owes something. */
export type SupplierOpenItem = S['RetailSupplierOpenItem'];
export type SupplierOpenItems = S['RetailSupplierOpenItems'];
export type SupplierPayables = S['RetailSupplierPayables'];
export type SupplierPayablesRow = S['RetailSupplierPayablesRow'];
/** A supplier's statement (issue #557): opening balance, each purchase on credit, invoice, payment, void and reversal with the running balance. */
export type SupplierStatement = S['RetailSupplierStatement'];
export type SupplierStatementLine = S['RetailSupplierStatementLine'];
export type PurchasingSettings = S['RetailPurchasingSettings'];
export type PurchasingSettingsUpdate = S['RetailPurchasingSettingsUpdate'];
// JUSTIFICATION-A3: landed costs and supplier credit notes are new API surface (issue #199); nothing existing carries these types.
/** Landed cost (issue #199): freight, duty and the like of one payee, spread over received lines. The payee's invoice is a supplier bill of kind `landed_cost`. */
export type LandedCost = S['RetailLandedCost'];
export type LandedCostCharge = S['RetailLandedCostCharge'];
export type LandedCostChargeRequest = S['RetailLandedCostChargeRequest'];
export type LandedCostLine = S['RetailLandedCostLine'];
export type LandedCostLineRequest = S['RetailLandedCostLineRequest'];
export type LandedCostRequest = S['RetailLandedCostRequest'];
export type LandedCostPreview = S['RetailLandedCostPreview'];
export type LandedCostPage = S['RetailLandedCostList'];
/** Supplier credit notes (issue #199): goods sent back or a price adjustment, against an invoice that stands. */
export type SupplierCredit = S['RetailSupplierCredit'];
export type SupplierCreditLine = S['RetailSupplierCreditLine'];
export type SupplierCreditRequest = S['RetailSupplierCreditRequest'];
export type SupplierCreditLineRequest = S['RetailSupplierCreditLineRequest'];
export type SupplierCreditPage = S['RetailSupplierCreditList'];
export type Supplier = S['RetailSupplier'];
export type SaleRequest = S['RetailSaleRequest'];
export type Sale = S['RetailSale'];
/** `sale_*` and `unit_factor` are present only for a line entered in a pack; `qty` and `unit_price_minor` are always per base unit. */
export type SaleLine = S['RetailSaleLine'];
/** How the price of a line was chosen (price_lists capability, ADR-033): absent on a line made without the capability. */
export type LinePrice = S['RetailSaleLinePrice'];
export type SalePage = S['RetailSalePage'];
/** The sales grouped by year, month or day, summed on the server (issue #328); `profit_minor` is absent without retail.profit.read. */
export type SalesSummary = S['RetailSalesSummary'];
export type SalesSummaryGroup = S['RetailSalesSummaryGroup'];
/** The restocks and the stock grouped by a chain of levels, summed on the server (issue #350); `total_minor` and `cost_minor` are absent without retail.profit.read. */
export type PurchasesSummary = S['RetailPurchasesSummary'];
export type PurchasesSummaryGroup = S['RetailPurchasesSummaryGroup'];
export type StockSummary = S['RetailStockSummary'];
export type StockSummaryGroup = S['RetailStockSummaryGroup'];
/** Price lists, their items and customer groups (ADR-033, issue #195). Prices are integer minor units per `unit`. */
export type PriceList = S['RetailPriceList'];
export type PriceListItem = S['RetailPriceListItem'];
export type PriceListItemPage = S['RetailPriceListItemPage'];
export type NewPriceListItem = S['RetailPriceListItemRequest'];
export type CustomerGroup = S['RetailCustomerGroup'];
export type PriceResolution = S['RetailPriceResolution'];
export type ResolvedLine = S['RetailPriceResolveLine'];
export type ResolveRequest = S['RetailPriceResolveRequest'];
export type PurchaseRequest = S['RetailPurchaseRequest'];
export type Purchase = S['RetailPurchase'];
export type PurchasePage = S['RetailPurchasePage'];
/** `unit*` are present only for a line entered in a pack; `cost_minor` and `qty_total` are always per base unit. */
export type PurchaseLine = S['RetailPurchaseLine'];
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
export type TransferStatus = 'completed' | 'in_transit' | 'received' | 'cancelled' | 'voided';
export type ReceiveLine = S['RetailTransferReceiveLine'];
export type StockByLocation = S['RetailStockByLocation'];
export type LocationRow = S['RetailLocationRow'];
export type LocationBranch = S['RetailLocationBranch'];
export type LocationBalance = S['RetailLocationBalance'];
export type InFlightReport = S['RetailTransfersInFlight'];
export type InFlightTransfer = S['RetailInFlightTransfer'];
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
  /** Aborts the request when the search that asked for it has moved on. */
  signal?: AbortSignal;
  /** One branch; absent means every branch the caller may read. */
  branchId?: string;
  from?: string;
  to?: string;
  buyer?: string;
  paymentMethod?: SalePayment;
  productId?: string;
  /** Sales made by this user (the chain's salesperson, issue #341). */
  sellerId?: string;
  /** Sales with a line in this product category. */
  categoryId?: string;
  status?: 'completed' | 'voided';
  /** Credit sales only: still owing, overdue, or fully paid. Filtered on the server, across every page. */
  owing?: 'owing' | 'overdue' | 'paid';
  cursor?: string;
}

/** The query string of a sales list: newest first, 50 a page, empty filters left out. */
export function salesParams({ branchId, from, to, buyer, paymentMethod, productId, sellerId, categoryId, status, owing, cursor }: SalesQuery) {
  return {
    branch_id: branchId ? [branchId] : undefined, from: from || undefined, to: to || undefined, buyer: buyer || undefined,
    payment_method: paymentMethod, product_id: productId || undefined, seller_id: sellerId || undefined, category_id: categoryId || undefined,
    status, owing, newest_first: true, limit: 50, cursor,
  };
}

/** The levels a sales summary can group by (issue #341). */
export type SummaryLevel = 'branch' | 'year' | 'month' | 'seller' | 'day' | 'category' | 'item' | 'variant';

export interface SalesSummaryQuery extends Omit<SalesQuery, 'cursor' | 'status'> {
  /** The level whose groups are asked for. */
  level: SummaryLevel;
  /** The whole chain, in order, so the answer can say which level comes next; absent means the level alone. */
  chain?: SummaryLevel[];
  /** Item chosen above (the item's own lines, not every sale holding it). */
  itemId?: string;
  /** The rest of a level that was cut off at its bound (every level but year, month and day). */
  offset?: number;
}

/** The query string of a sales summary: the list's filters (no status, no paging), the chain and the level. */
export function salesSummaryParams({ level, chain, branchId, from, to, buyer, paymentMethod, productId, sellerId, categoryId, itemId, offset, owing }: SalesSummaryQuery) {
  return {
    group_by: (chain ?? [level]).join(','), level: chain ? level : undefined,
    branch_id: branchId ? [branchId] : undefined, from: from || undefined, to: to || undefined, buyer: buyer || undefined,
    payment_method: paymentMethod, product_id: productId || undefined, owing,
    seller_id: sellerId || undefined, category_id: categoryId || undefined, item_id: itemId || undefined, offset: offset || undefined,
  };
}

/** The levels a restocks summary can group by (issue #350). */
export type PurchaseLevel = 'branch' | 'year' | 'month' | 'supplier' | 'day' | 'category' | 'item' | 'variant';

/** The levels a stock summary can group by: the items themselves are the stock list (issue #350). */
export type StockChainLevel = 'branch' | 'category';

/** The restocks summary: the level asked for, the whole chain, and what was opened above (the filters). */
export interface PurchasesSummaryQuery {
  level: PurchaseLevel;
  chain: PurchaseLevel[];
  branchId?: string;
  from?: string;
  to?: string;
  supplierId?: string;
  categoryId?: string;
  itemId?: string;
  /** The posted or voided restocks only; absent for both. */
  status?: 'posted' | 'voided';
  offset?: number;
  signal?: AbortSignal;
}

/** The stock summary: the level asked for, the whole chain, the stock list's own filters and what was opened above. */
export interface StockSummaryQuery {
  level: StockChainLevel;
  chain: StockChainLevel[];
  branchId?: string;
  categoryId?: string;
  query?: string;
  negativeOnly?: boolean;
  stockLevel?: StockLevel;
  includeClosed?: boolean;
  includeInactive?: boolean;
  offset?: number;
  signal?: AbortSignal;
}

/** The nil UUID the server uses as the key of the restocks that name no supplier. */
export const NO_SUPPLIER = '00000000-0000-0000-0000-000000000000';

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
export type PurchasePayment = 'cash' | 'mobile_money' | 'bank' | 'credit';

export interface RetailApi {
  listProducts(q: { query?: string; branchId?: string; signal?: AbortSignal }): Promise<Product[]>;
  listCategories(): Promise<Category[]>;
  /** Catalogue management (#146): one page of products, with the filters of the Products screen. */
  listCatalogue(q: { query?: string; categoryId?: string; active?: boolean; cursor?: string; signal?: AbortSignal }): Promise<ProductPage>;
  getProduct(id: string): Promise<Product>;
  /** Before saving a new or renamed item: the products with this name or a close one (issue #352). */
  similarProducts(q: { description: string; unitId?: string; excludeId?: string }): Promise<SimilarProducts>;
  /** Issue #353: the item's median quantities, usual units and last price; the screens call it once per item. */
  entryNorms(productId: string): Promise<EntryNorms>;
  /** Issue #353: did you mean, and the category of similar items, while a new item's name is typed. */
  nameHints(description: string): Promise<NameHints>;
  /** Issue #353: the person confirmed an entry warning; recorded, never needed to save. */
  confirmEntryWarning(body: EntryConfirmation): Promise<void>;
  createProduct(body: NewProduct): Promise<Product>;
  /** Non-price fields, under If-Match with the version read. */
  updateProduct(id: string, version: number, body: ProductChange): Promise<Product>;
  /** A price change under If-Match; the server writes the history row. */
  editPrices(id: string, version: number, body: PriceEdit): Promise<Product>;
  priceHistory(id: string): Promise<PriceChange[]>;
  /** An item with sizes and colours, its variants and its stock over the caller's branches (issue #384). */
  getFamily(id: string): Promise<Family>;
  /** Makes the item and every combination of the sizes and colours chosen once (or the variants named). */
  createFamily(body: NewFamily): Promise<Family>;
  /** A new name renames every variant, a new category moves every variant; under If-Match. */
  updateFamily(id: string, version: number, body: FamilyChange): Promise<Family>;
  /** More sizes or colours; a combination the item has is skipped. */
  addVariants(id: string, body: AddVariants): Promise<Family>;
  /** Gives an item without variants sizes and colours; it keeps its stock and history as one of them. */
  makeFamily(productId: string, body: MakeFamily): Promise<Family>;
  /** Items kept apart become the sizes and colours of a new item (familyId null) or of one that exists; each keeps its stock and history. */
  joinFamily(familyId: string | null, body: JoinRequest): Promise<Family>;
  /** An item's photo (issue #384): PNG, JPEG or WebP, at most 5 MB; the server re-encodes it without metadata. */
  setProductPhoto(productId: string, file: Blob): Promise<Product>;
  /** Takes an item's own photo off; a size then shows its item's photo. */
  clearProductPhoto(productId: string): Promise<Product>;
  /** The photo of an item with sizes and colours, shown for every size without its own. */
  setFamilyPhoto(familyId: string, file: Blob): Promise<Family>;
  clearFamilyPhoto(familyId: string): Promise<Family>;
  /** The image of a photo (`photo_id` or `item_photo_id`), from the same origin; `small` is the 240 px copy for cards and pickers. */
  photo(photoId: string, signal?: AbortSignal, small?: boolean): Promise<Blob>;
  /** The pack sizes of an item (the units capability, issue #194); 404 capability_not_enabled while it is off. */
  listPacks(productId: string): Promise<Pack[]>;
  addPack(productId: string, body: { unit_id: string; factor: string }): Promise<Pack>;
  /** Under If-Match with the pack's version: a new factor, or switch the pack off or on. */
  updatePack(productId: string, unitId: string, version: number, body: { factor?: string; active?: boolean }): Promise<Pack>;
  /** The CSV item import: a dry run reports, an apply adds the rows that are fine (administrators only). */
  /** Owner decision D45 (issue #562): makeCodes is Let the app make the codes, off by default. */
  importProducts(csv: string, dryRun: boolean, makeCodes?: boolean): Promise<ImportResult>;
  /** Issue #345: the active item a scanned code belongs to, with the branch's balance when given; null for an unknown code. */
  findByBarcode(code: string, branchId?: string, signal?: AbortSignal): Promise<BarcodeMatch | null>;
  addBarcode(productId: string, body: { code: string; format?: string; unit_id?: string }): Promise<Barcode>;
  removeBarcode(productId: string, barcodeId: string): Promise<void>;
  /** A Code 128 label code for each item that has no made one yet; an item keeps the one it has. */
  generateBarcodes(productIds: string[]): Promise<Barcode[]>;
  /** A blank code start is suggested from the name by the server (issue #493). */
  createCategory(name: string, codePrefix?: string): Promise<Category>;
  updateCategory(id: string, version: number, body: { name?: string; active?: boolean; code_prefix?: string }): Promise<Category>;
  /** The code start a new category of this name would get, free in the business (issue #493). */
  suggestCategoryPrefix(name: string): Promise<string>;
  listUnits(): Promise<Unit[]>;
  createUnit(name: string): Promise<Unit>;
  updateUnit(id: string, version: number, body: { name?: string; active?: boolean }): Promise<Unit>;
  /** Corrects the quantity of an item at a branch (retail.stocktake.commit there); takes the Idempotency-Key of its form (issue #351). */
  correctStock(body: StockCorrectionRequest, idempotencyKey: string): Promise<StockCorrection>;
  /** The stock history of an item at a branch, newest first (issue #351). */
  stockHistory(q: { productId: string; branchId: string; cursor?: string; limit?: number; signal?: AbortSignal }): Promise<{ items: StockMovement[]; next_cursor?: string }>;
  listStock(q: { branchId: string; query?: string; categoryId?: string; negativeOnly?: boolean; level?: StockLevel; includeInactive?: boolean; signal?: AbortSignal }): Promise<StockRow[]>;
  /** One page of sales, newest first, narrowed by the filters (#145). */
  listSales(q: SalesQuery): Promise<SalePage>;
  /** Groups of sales at one level of a chain (shop, year, month, salesperson, day, category, item) with the totals of each; `next_to` (time levels) or `next_offset` (the rest) continues (#328, #341). */
  salesSummary(q: SalesSummaryQuery): Promise<SalesSummary>;
  getSale(id: string): Promise<Sale>;
  /** A payment, partial allowed, against a credit sale (retail.sale.create at the branch); takes the Idempotency-Key of its form. */
  paySale(saleId: string, body: SalePaymentRequest, idempotencyKey: string): Promise<SalePaymentResult>;
  /** Voids a sale by reversal (retail.sale.void at the branch). The schema has no Idempotency-Key for it: a second void is refused as sale_voided. */
  voidSale(saleId: string, reason: string): Promise<Sale>;
  /** Writes off what a credit sale still owes as a loss (retail.credit.manage); takes the Idempotency-Key of its form. */
  writeOffSale(saleId: string, reason: string, idempotencyKey: string): Promise<WriteOffOutcome>;
  listCustomers(): Promise<Customer[]>;
  /** The credit buyers screen: server-side search and cursor paging. */
  listCustomerPage(q: { query?: string; cursor?: string; signal?: AbortSignal }): Promise<CustomerPage>;
  listSuppliers(): Promise<Supplier[]>;
  createSupplier(body: { name: string; contact?: string }): Promise<Supplier>;
  updateSupplier(id: string, version: number, body: { name?: string; contact?: string; active?: boolean }): Promise<Supplier>;
  createCustomer(body: { name: string; contact?: string }): Promise<Customer>;
  /** The price lists, their items and customer groups (the price_lists capability, issue #195); 404 capability_not_enabled while it is off. */
  listPriceLists(): Promise<PriceList[]>;
  createPriceList(body: { name: string; strict?: boolean }): Promise<PriceList>;
  /** Under If-Match with the list's version: rename, make strict or switch off; never deleted. */
  updatePriceList(id: string, version: number, body: { name?: string; strict?: boolean; active?: boolean }): Promise<PriceList>;
  listPriceListItems(listId: string, q: { query?: string; includeEnded?: boolean; cursor?: string; signal?: AbortSignal }): Promise<PriceListItemPage>;
  /** A new price; an open ended one takes over from the current open ended one from its first day (409 price_item_overlap otherwise). */
  addPriceListItem(listId: string, body: NewPriceListItem): Promise<PriceListItem>;
  /** Ends an open price: it applies up to and including `validTo`. */
  endPriceListItem(listId: string, itemId: string, validTo: string): Promise<PriceListItem>;
  listCustomerGroups(): Promise<CustomerGroup[]>;
  createCustomerGroup(body: { name: string; price_list_id?: string }): Promise<CustomerGroup>;
  updateCustomerGroup(id: string, version: number, body: { name: string; price_list_id?: string; active: boolean }): Promise<CustomerGroup>;
  /** A buyer's group and own list, replaced under If-Match; an absent field clears it (retail.pricelist.manage). */
  setCustomerPricing(id: string, version: number, body: { customer_group_id?: string; price_list_id?: string }): Promise<Customer>;
  /** The price and its source for each line as a sale would take them, without making one (never the cost). */
  resolvePrices(body: ResolveRequest): Promise<PriceResolution>;
  /** A buyer's account (the customer_accounts capability, issue #196); 404 capability_not_enabled while it is off. */
  customerAccount(id: string): Promise<CustomerAccount>;
  /** The limit and the terms, replaced under If-Match; an absent field clears it (retail.credit.manage). */
  setCustomerAccount(id: string, version: number, body: AccountEdit): Promise<CustomerAccount>;
  /** Opening balance, lines with a running balance and closing balance over the caller's branch scope. */
  customerStatement(id: string, q: { from?: string; to?: string }): Promise<Statement>;
  /** What a buyer owes on credit sales in the caller's branch scope, sale by sale. */
  customerBalance(id: string): Promise<CustomerBalance>;
  listAccountPayments(id: string, q?: { cursor?: string }): Promise<AccountPaymentPage>;
  /** Money from a buyer that is not tied to one sale, allocated oldest first or as chosen; takes the Idempotency-Key of its form. */
  payOnAccount(id: string, body: AccountPaymentRequest, idempotencyKey: string): Promise<AccountPayment>;
  /** Undoes a payment on account as a whole (retail.sale.void); takes the Idempotency-Key of its form. */
  reverseAccountPayment(id: string, paymentId: string, reason: string, idempotencyKey: string): Promise<AccountPayment>;
  /** The customer statement as a PDF (documents and customer_accounts), as at today and never stored. */
  customerStatementPdf(id: string, q: { from?: string; to?: string }): Promise<Blob>;
  /** The receipt of a sale as a PDF to share from the seller's phone (retail.sale.read; own sales only without retail.sale.all_sellers), never stored (issue #439). */
  saleReceiptPdf(saleId: string): Promise<Blob>;
  /** The facts of the receipt to share, as its PDF prints them (same check as the PDF; 422 for a voided, written-off or wholly returned sale). */
  saleReceipt(saleId: string): Promise<SaleReceipt>;
  /** What the receipt PDF prints, for the screen's preview, asked for only when View receipt is tapped (issue #500); without `printed` when it could not be laid out. */
  saleReceiptPrinted(saleId: string): Promise<ReceiptPreview>;
  /** Optional documents (the documents capability, issue #197); 404 capability_not_enabled while it is off. */
  listSaleDocuments(saleId: string): Promise<RetailDocument[]>;
  /** The invoice or delivery note of a sale, issued once; takes the Idempotency-Key of its button. */
  issueSaleDocument(saleId: string, body: DocumentRequest, idempotencyKey: string): Promise<RetailDocument>;
  /** The delivery note of a stock move (quantities only). */
  issueTransferDocument(transferId: string, idempotencyKey: string): Promise<RetailDocument>;
  listDocuments(q: { branchId?: string; type?: DocumentType; from?: string; to?: string; query?: string; cursor?: string; signal?: AbortSignal }): Promise<DocumentPage>;
  /** The document with its stored PDF, rendered now from its snapshot when none was stored. */
  renderDocument(id: string): Promise<RetailDocument>;
  /** A short-lived signed link to a stored PDF (FR-DOC-03). */
  documentUrl(pdfDocumentId: string): Promise<string>;
  documentSeries(): Promise<DocumentSeries[]>;
  /** Changes the prefix of later numbers (core.settings.manage); issued numbers never change. */
  setDocumentPrefix(type: DocumentType, prefix: string): Promise<DocumentSeries[]>;
  listQuotations(q: { branchId?: string; status?: 'open' | 'converted' | 'cancelled'; query?: string; cursor?: string; signal?: AbortSignal }): Promise<QuotationPage>;
  getQuotation(id: string): Promise<Quotation>;
  createQuotation(body: QuotationRequest, idempotencyKey: string): Promise<Quotation>;
  cancelQuotation(id: string, reason: string): Promise<Quotation>;
  convertQuotation(id: string, body: ConvertRequest, idempotencyKey: string): Promise<Converted>;
  /** Takes goods back against a sale (retail.sale.return); takes the Idempotency-Key of its form. 404 capability_not_enabled while returns are off. */
  createSaleReturn(saleId: string, body: ReturnRequest, idempotencyKey: string): Promise<ReturnOutcome>;
  /** The returns made against a sale, oldest first (voided ones included). */
  listSaleReturns(saleId: string): Promise<SaleReturn[]>;
  /** Credit notes in the caller's branch scope, newest first; `query` matches the credit note number, the sale number and the buyer's name. */
  listReturns(q: { query?: string; status?: string; cursor?: string; signal?: AbortSignal }): Promise<ReturnPage>;
  getReturn(id: string): Promise<SaleReturn>;
  /** Voids a return as a whole by reversal (retail.sale.return); takes the Idempotency-Key of its form. 202 `executed: false` when it needs a second administrator. */
  voidReturn(id: string, reason: string, idempotencyKey: string): Promise<ReturnOutcome>;

  // JUSTIFICATION-A3: the purchasing capability's methods are new API surface (issue #198). Every route answers 404 capability_not_enabled
  // while the capability is off. Orders and receipts need retail.purchase.create; invoices, payments, open items and the payables
  // report need retail.payable.manage; the settings need core.settings.manage. Every POST takes the Idempotency-Key of its form.
  listPurchaseOrders(q: { query?: string; status?: string; supplierId?: string; cursor?: string; signal?: AbortSignal }): Promise<PurchaseOrderPage>;
  getPurchaseOrder(id: string): Promise<PurchaseOrder>;
  createPurchaseOrder(body: PurchaseOrderRequest, idempotencyKey: string): Promise<PurchaseOrder>;
  /** A draft order replaced as a whole under If-Match with the order's version (409 version_conflict, 409 order_not_draft). */
  updatePurchaseOrder(id: string, version: number, body: PurchaseOrderRequest): Promise<PurchaseOrder>;
  /** 200 `executed` when sent; 202 with `approval_id` when its value needs a second person (retail.purchase.approve). */
  sendPurchaseOrder(id: string, idempotencyKey: string): Promise<PurchaseOrderOutcome>;
  cancelPurchaseOrder(id: string, reason: string, idempotencyKey: string): Promise<PurchaseOrder>;
  listGoodsReceipts(q: { query?: string; status?: string; orderId?: string; supplierId?: string; cursor?: string; signal?: AbortSignal }): Promise<GoodsReceiptPage>;
  getGoodsReceipt(id: string): Promise<GoodsReceipt>;
  createGoodsReceipt(body: GoodsReceiptRequest, idempotencyKey: string): Promise<GoodsReceipt>;
  voidGoodsReceipt(id: string, reason: string, idempotencyKey: string): Promise<GoodsReceipt>;
  listSupplierBills(q: { query?: string; openOnly?: boolean; supplierId?: string; cursor?: string; signal?: AbortSignal }): Promise<SupplierBillPage>;
  getSupplierBill(id: string): Promise<SupplierBill>;
  createSupplierBill(body: SupplierBillRequest, idempotencyKey: string): Promise<SupplierBill>;
  /** The three-way view of a supplier invoice without recording anything; same body as createSupplierBill. */
  matchSupplierBill(body: SupplierBillRequest): Promise<SupplierBillMatch>;
  voidSupplierBill(id: string, reason: string, idempotencyKey: string): Promise<SupplierBill>;
  /** Goods received from a supplier and not yet on an invoice, line by line. */
  unbilledReceipts(supplierId: string): Promise<UnbilledReceipts>;
  listSupplierPayments(q: { query?: string; status?: string; supplierId?: string; cursor?: string; signal?: AbortSignal }): Promise<SupplierPaymentPage>;
  getSupplierPayment(id: string): Promise<SupplierPayment>;
  createSupplierPayment(body: SupplierPaymentRequest, idempotencyKey: string): Promise<SupplierPayment>;
  reverseSupplierPayment(id: string, reason: string, idempotencyKey: string): Promise<SupplierPayment>;
  /** The invoices and credit restocks of a supplier that still owe something, oldest due first. */
  supplierOpenItems(supplierId: string): Promise<SupplierOpenItems>;
  supplierPayables(): Promise<SupplierPayables>;
  /** One supplier's statement between two days, the last 90 days by default (issue #557). */
  supplierStatement(supplierId: string, q: { from?: string; to?: string }): Promise<SupplierStatement>;
  purchasingSettings(): Promise<PurchasingSettings>;
  /** The tolerances replaced under If-Match with the settings version; an omitted field is unchanged (core.settings.manage). */
  changePurchasingSettings(version: number, body: PurchasingSettingsUpdate): Promise<PurchasingSettings>;
  /** A receipt held as received and not costed, released without a landed cost, with a reason (retail.purchase.create). */
  releaseReceiptHold(id: string, reason: string, idempotencyKey: string): Promise<GoodsReceipt>;
  listLandedCosts(q: { query?: string; status?: string; supplierId?: string; receiptId?: string; cursor?: string; signal?: AbortSignal }): Promise<LandedCostPage>;
  getLandedCost(id: string): Promise<LandedCost>;
  createLandedCost(body: LandedCostRequest, idempotencyKey: string): Promise<LandedCost>;
  /** How the charges would be spread and what that would do, without recording anything; same body as createLandedCost. */
  previewLandedCost(body: LandedCostRequest): Promise<LandedCostPreview>;
  voidLandedCost(id: string, reason: string, idempotencyKey: string): Promise<LandedCost>;
  listSupplierCredits(q: { query?: string; status?: string; supplierId?: string; billId?: string; cursor?: string; signal?: AbortSignal }): Promise<SupplierCreditPage>;
  getSupplierCredit(id: string): Promise<SupplierCredit>;
  createSupplierCredit(body: SupplierCreditRequest, idempotencyKey: string): Promise<SupplierCredit>;
  voidSupplierCredit(id: string, reason: string, idempotencyKey: string): Promise<SupplierCredit>;
  /** The credit note of a return as a stored PDF (retail.document.issue), rendered once; a voided return is served marked void. */
  renderCreditNote(returnId: string): Promise<CreditNoteDocument>;
  updateCustomer(id: string, version: number, body: { name?: string; contact?: string }): Promise<Customer>;
  createSale(body: SaleRequest, idempotencyKey: string): Promise<Sale>;
  createPurchase(body: PurchaseRequest, idempotencyKey: string): Promise<Purchase>;
  /** The restocks (retail.purchase.create), a voided one included and marked; newest first with the filters of the past restocks screen (issue #351). */
  listPurchases(q: { branchId?: string; from?: string; to?: string; supplierId?: string; categoryId?: string; productId?: string; status?: 'posted' | 'voided'; cursor?: string; limit?: number; signal?: AbortSignal }): Promise<PurchasePage>;
  /** Groups of restocks at one level of a chain (shop, year, month, supplier, day, category, item); `next_to` (time levels) or `next_offset` continues (#350). */
  purchasesSummary(q: PurchasesSummaryQuery): Promise<PurchasesSummary>;
  /** Groups of the stock list by shop or category with the number of items and their value (#350). */
  stockSummary(q: StockSummaryQuery): Promise<StockSummary>;
  getPurchase(id: string): Promise<Purchase>;
  /** Voids a restock by reversal, once (retail.purchase.create at every branch it touched); takes the Idempotency-Key of its form. */
  voidPurchase(id: string, reason: string, idempotencyKey: string): Promise<Purchase>;
  createUsage(body: UsageRequest, idempotencyKey: string): Promise<Usage>;
  createStocktake(body: StocktakeRequest): Promise<Stocktake>;
  commitStocktake(id: string): Promise<Stocktake>;
  /** Every product with its balance in each branch the caller may read (#144). */
  listStockAllBranches(q: { query?: string; categoryId?: string; negativeOnly?: boolean; level?: StockLevel; includeClosed?: boolean; includeInactive?: boolean; signal?: AbortSignal }): Promise<AllBranchesStock>;
  /** One branch, or every branch in the caller's scope when `branchId` is absent (#144). */
  valuation(q: { branchId?: string; asOf?: string }): Promise<Valuation>;
  /** The branches in the caller's stock scope holding a quantity above zero of anything (#103). */
  stockedBranches(): Promise<string[]>;
  /** The branches in the caller's stock scope with their roles (ADR-033); 404 capability_not_enabled while branch_roles is off. */
  branchRoles(): Promise<BranchRole[]>;
  setBranchRole(branchId: string, role: 'store' | 'shop' | 'both'): Promise<BranchRole>;
  /** The tenant's capabilities and profile (administrators only, ADR-033 section 1). */
  capabilities(): Promise<CapabilitiesRead>;
  /** Switches the profile or flags under If-Match; the server audits the old and new values. */
  changeCapabilities(body: CapabilityChange, version: number): Promise<CapabilitiesRead>;
  dailyProfit(q: { branchId?: string; from: string; to: string }): Promise<DailyProfit>;
  createTransfer(body: TransferRequest, idempotencyKey: string): Promise<Transfer>;
  listTransfers(q: { branchId?: string; status?: TransferStatus; cursor?: string }): Promise<TransferPage>;
  getTransfer(id: string): Promise<Transfer>;
  voidTransfer(id: string, reason: string): Promise<Transfer>;
  /** The destination receives a dispatched transfer: what arrived per line, or every line in full when `lines` is absent (ADR-033; capability transfer_receipt). */
  receiveTransfer(id: string, lines: ReceiveLine[] | undefined, idempotencyKey: string): Promise<Transfer>;
  /** The source takes a dispatched transfer back before it is received. */
  cancelTransfer(id: string, reason: string, idempotencyKey: string): Promise<Transfer>;
  /** Every product with its balance in each store and shop, by role, with stock on the way and a combined total (ADR-033). */
  stockByLocation(q: { query?: string; categoryId?: string; negativeOnly?: boolean; signal?: AbortSignal }): Promise<StockByLocation>;
  /** Dispatched and not yet received transfers (capability transfer_receipt), the longest wait first. */
  transfersInFlight(): Promise<InFlightReport>;

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
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    throw new RetailError(retailMessage(problem, result.response.status), result.response.status, problem.code, problem as Record<string, unknown>);
  }
  return result.data;
}

/** The settings version in an ETag (`"3"`), or 0 when the response carries none. */
function versionOf(response: Response): number {
  return Number.parseInt((response.headers.get('ETag') ?? '').replace(/\D/g, ''), 10) || 0;
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
  async listProducts({ query, branchId, signal }) {
    const page = unwrap(
      await api.GET('/api/v1/retail/products', {
        params: { query: { query: query || undefined, branch_id: branchId, active: true, limit: 50 } },
        signal,
      }),
    );
    return page.items ?? [];
  },

  async listCategories() {
    return unwrap(await api.GET('/api/v1/retail/categories')).items ?? [];
  },

  async listCatalogue({ query, categoryId, active, cursor, signal }) {
    return unwrap(
      await api.GET('/api/v1/retail/products', {
        params: { query: { query: query || undefined, category_id: categoryId || undefined, active, limit: 50, cursor } },
        signal,
      }),
    );
  },

  async getProduct(id) {
    return unwrap(await api.GET('/api/v1/retail/products/{product_id}', { params: { path: { product_id: id } } }));
  },

  async similarProducts({ description, unitId, excludeId }) {
    return unwrap(
      await api.GET('/api/v1/retail/products/similar', { params: { query: { description, ...(unitId ? { unit_id: unitId } : {}), ...(excludeId ? { exclude_id: excludeId } : {}) } } }),
    );
  },

  async entryNorms(productId) {
    return unwrap(await api.GET('/api/v1/retail/products/{product_id}/entry-norms', { params: { path: { product_id: productId } } }));
  },

  async nameHints(description) {
    return unwrap(await api.GET('/api/v1/retail/products/name-hints', { params: { query: { description } } }));
  },

  async confirmEntryWarning(body) {
    const result = await api.POST('/api/v1/retail/entry-warnings/confirmations', { body });
    // 204 has no body, so unwrap is only for the refusal.
    if (!result.response.ok) unwrap(result);
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

  async getFamily(id) {
    return unwrap(await api.GET('/api/v1/retail/product-families/{family_id}', { params: { path: { family_id: id } } }));
  },

  async createFamily(body) {
    return unwrap(await api.POST('/api/v1/retail/product-families', { body }));
  },

  async updateFamily(id, version, body) {
    return unwrap(
      await api.PATCH('/api/v1/retail/product-families/{family_id}', { params: { path: { family_id: id }, header: { 'If-Match': `"${version}"` } }, body }),
    );
  },

  async addVariants(id, body) {
    return unwrap(await api.POST('/api/v1/retail/product-families/{family_id}/variants', { params: { path: { family_id: id } }, body }));
  },

  async makeFamily(productId, body) {
    return unwrap(await api.POST('/api/v1/retail/products/{product_id}/family', { params: { path: { product_id: productId } }, body }));
  },

  async joinFamily(familyId, body) {
    return familyId
      ? unwrap(await api.POST('/api/v1/retail/product-families/{family_id}/join', { params: { path: { family_id: familyId } }, body }))
      : unwrap(await api.POST('/api/v1/retail/product-families/join', { body }));
  },

  async setProductPhoto(productId, file) {
    return postFile<Product>(`/api/v1/retail/products/${encodeURIComponent(productId)}/photo`, file, 'photo', {});
  },

  async clearProductPhoto(productId) {
    return unwrap(await api.DELETE('/api/v1/retail/products/{product_id}/photo', { params: { path: { product_id: productId } } }));
  },

  async setFamilyPhoto(familyId, file) {
    return postFile<Family>(`/api/v1/retail/product-families/${encodeURIComponent(familyId)}/photo`, file, 'photo', {});
  },

  async clearFamilyPhoto(familyId) {
    return unwrap(await api.DELETE('/api/v1/retail/product-families/{family_id}/photo', { params: { path: { family_id: familyId } } }));
  },

  async photo(photoId, signal, small) {
    const result = await api.GET('/api/v1/retail/photos/{photo_id}', { params: { path: { photo_id: photoId }, query: { size: small ? 'small' : undefined } }, parseAs: 'blob', signal });
    if (!result.response.ok || !result.data) throw new RetailError('The photo could not be shown.', result.response.status, 'photo_unavailable');
    return result.data as Blob;
  },

  async listPacks(productId) {
    return unwrap(await api.GET('/api/v1/retail/products/{product_id}/units', { params: { path: { product_id: productId } } })).items ?? [];
  },

  async addPack(productId, body) {
    return unwrap(await api.POST('/api/v1/retail/products/{product_id}/units', { params: { path: { product_id: productId } }, body }));
  },

  async updatePack(productId, unitId, version, body) {
    return unwrap(
      await api.PATCH('/api/v1/retail/products/{product_id}/units/{unit_id}', {
        params: { path: { product_id: productId, unit_id: unitId }, header: { 'If-Match': `"${version}"` } },
        body,
      }),
    );
  },

  async importProducts(csv, dryRun, makeCodes = false) {
    return unwrap(await api.POST('/api/v1/retail/products/import', { params: { query: { dry_run: dryRun, make_codes: makeCodes } }, body: { csv } }));
  },

  async findByBarcode(code, branchId, signal) {
    const result = await api.GET('/api/v1/retail/products/by-barcode/{code}', {
      params: { path: { code }, query: { branch_id: branchId } },
      signal,
    });
    if (result.response.status === 404 && problemOf(result.error).code === 'barcode_not_found') return null;
    return unwrap(result);
  },

  async addBarcode(productId, body) {
    return unwrap(await api.POST('/api/v1/retail/products/{product_id}/barcodes', { params: { path: { product_id: productId } }, body }));
  },

  async removeBarcode(productId, barcodeId) {
    const result = await api.DELETE('/api/v1/retail/products/{product_id}/barcodes/{barcode_id}', {
      params: { path: { product_id: productId, barcode_id: barcodeId } },
    });
    // 204 has no body, so unwrap is only for the refusal.
    if (!result.response.ok) unwrap(result);
  },

  async generateBarcodes(productIds) {
    return unwrap(await api.POST('/api/v1/retail/products/barcodes/generate', { body: { product_ids: productIds } })).items ?? [];
  },

  async createCategory(name, codePrefix) {
    return unwrap(await api.POST('/api/v1/retail/categories', { body: { name, ...(codePrefix?.trim() ? { code_prefix: codePrefix.trim() } : {}) } }));
  },

  async suggestCategoryPrefix(name) {
    return unwrap(await api.GET('/api/v1/retail/categories/prefix-suggestion', { params: { query: { name } } })).code_prefix ?? '';
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

  async correctStock(body, key) {
    return unwrap(await api.POST('/api/v1/retail/stock/corrections', { params: { header: { 'Idempotency-Key': key } }, body }));
  },

  async stockHistory({ productId, branchId, cursor, limit, signal }) {
    const page = unwrap(
      await api.GET('/api/v1/retail/stock/movements', {
        params: { query: { product_id: productId, branch_id: [branchId], newest_first: true, ...(cursor ? { cursor } : {}), ...(limit ? { limit } : {}) } },
        signal,
      }),
    );
    return { items: page.items ?? [], ...(page.next_cursor ? { next_cursor: page.next_cursor } : {}) };
  },

  async listStock({ branchId, query, categoryId, negativeOnly, level, includeInactive, signal }) {
    const rows: StockRow[] = [];
    let cursor: string | undefined;
    do {
      const page = unwrap(
        await api.GET('/api/v1/retail/stock', {
          params: { query: { branch_id: branchId, query: query || undefined, category_id: categoryId || undefined, negative_only: negativeOnly || undefined, stock_level: level, include_inactive: includeInactive || undefined, limit: PAGE, cursor } },
          signal,
        }),
      );
      rows.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    return rows;
  },

  async listStockAllBranches({ query, categoryId, negativeOnly, level, includeClosed, includeInactive, signal }) {
    const items: AllBranchesRow[] = [];
    let branches: StockBranch[] = [];
    let closedBranches = 0;
    let cursor: string | undefined;
    do {
      const page = unwrap(
        await api.GET('/api/v1/retail/stock/all-branches', {
          params: { query: { query: query || undefined, category_id: categoryId || undefined, negative_only: negativeOnly || undefined, stock_level: level, include_closed: includeClosed || undefined, include_inactive: includeInactive || undefined, limit: PAGE, cursor } },
          signal,
        }),
      );
      branches = page.branches ?? branches;
      closedBranches = page.closed_branches ?? closedBranches;
      items.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    return { branches, items, closed_branches: closedBranches };
  },

  async listSales(q) {
    return unwrap(await api.GET('/api/v1/retail/sales', { params: { query: salesParams(q) }, signal: q.signal }));
  },

  async salesSummary(q) {
    return unwrap(await api.GET('/api/v1/retail/sales/summary', { params: { query: salesSummaryParams(q) }, signal: q.signal }));
  },

  async getSale(id) {
    return unwrap(await api.GET('/api/v1/retail/sales/{sale_id}', { params: { path: { sale_id: id } } }));
  },

  async paySale(saleId, body, key) {
    return unwrap(await api.POST('/api/v1/retail/sales/{sale_id}/payments', { params: { path: { sale_id: saleId }, header: { 'Idempotency-Key': key } }, body }));
  },

  async voidSale(saleId, reason) {
    return unwrap(await api.POST('/api/v1/retail/sales/{sale_id}/void', { params: { path: { sale_id: saleId } }, body: { reason } }));
  },

  async writeOffSale(saleId, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/sales/{sale_id}/write-off', { params: { path: { sale_id: saleId }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async listCustomers() {
    return unwrap(await api.GET('/api/v1/retail/customers', { params: { query: {} } })).items ?? [];
  },

  async listCustomerPage({ query, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/customers', { params: { query: { query: query || undefined, limit: 50, cursor } }, signal }));
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

  async listPriceLists() {
    return unwrap(await api.GET('/api/v1/retail/price-lists')).items ?? [];
  },

  async createPriceList(body) {
    return unwrap(await api.POST('/api/v1/retail/price-lists', { body }));
  },

  async updatePriceList(id, version, body) {
    return unwrap(await api.PATCH('/api/v1/retail/price-lists/{price_list_id}', { params: { path: { price_list_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
  },

  async listPriceListItems(listId, { query, includeEnded, cursor, signal }) {
    return unwrap(
      await api.GET('/api/v1/retail/price-lists/{price_list_id}/items', {
        params: { path: { price_list_id: listId }, query: { query: query || undefined, include_ended: includeEnded || undefined, limit: 50, cursor } },
        signal,
      }),
    );
  },

  async addPriceListItem(listId, body) {
    return unwrap(await api.POST('/api/v1/retail/price-lists/{price_list_id}/items', { params: { path: { price_list_id: listId } }, body }));
  },

  async endPriceListItem(listId, itemId, validTo) {
    return unwrap(
      await api.PATCH('/api/v1/retail/price-lists/{price_list_id}/items/{item_id}', {
        params: { path: { price_list_id: listId, item_id: itemId } },
        body: { valid_to: validTo },
      }),
    );
  },

  async listCustomerGroups() {
    return unwrap(await api.GET('/api/v1/retail/customer-groups')).items ?? [];
  },

  async createCustomerGroup(body) {
    return unwrap(await api.POST('/api/v1/retail/customer-groups', { body }));
  },

  async updateCustomerGroup(id, version, body) {
    return unwrap(await api.PUT('/api/v1/retail/customer-groups/{group_id}', { params: { path: { group_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
  },

  async setCustomerPricing(id, version, body) {
    return unwrap(await api.PUT('/api/v1/retail/customers/{customer_id}/pricing', { params: { path: { customer_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
  },

  async resolvePrices(body) {
    return unwrap(await api.POST('/api/v1/retail/prices/resolve', { body }));
  },

  async customerAccount(id) {
    return unwrap(await api.GET('/api/v1/retail/customers/{customer_id}/account', { params: { path: { customer_id: id } } }));
  },

  async setCustomerAccount(id, version, body) {
    return unwrap(await api.PUT('/api/v1/retail/customers/{customer_id}/account', { params: { path: { customer_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
  },

  async customerStatement(id, { from, to }) {
    return unwrap(await api.GET('/api/v1/retail/customers/{customer_id}/statement', { params: { path: { customer_id: id }, query: { from: from || undefined, to: to || undefined } } }));
  },

  async customerBalance(id) {
    return unwrap(await api.GET('/api/v1/retail/customers/{customer_id}/balance', { params: { path: { customer_id: id } } }));
  },

  async listAccountPayments(id, q) {
    return unwrap(await api.GET('/api/v1/retail/customers/{customer_id}/payments', { params: { path: { customer_id: id }, query: { limit: 50, cursor: q?.cursor } } }));
  },

  async payOnAccount(id, body, key) {
    return unwrap(await api.POST('/api/v1/retail/customers/{customer_id}/payments', { params: { path: { customer_id: id }, header: { 'Idempotency-Key': key } }, body }));
  },

  async reverseAccountPayment(id, paymentId, reason, key) {
    return unwrap(
      await api.POST('/api/v1/retail/customers/{customer_id}/payments/{payment_id}/reverse', {
        params: { path: { customer_id: id, payment_id: paymentId }, header: { 'Idempotency-Key': key } },
        body: { reason },
      }),
    );
  },

  async customerStatementPdf(id, { from, to }) {
    const result = await api.GET('/api/v1/retail/customers/{customer_id}/statement/pdf', {
      params: { path: { customer_id: id }, query: { from: from || undefined, to: to || undefined } },
      parseAs: 'blob',
    });
    return unwrap(result as { data?: Blob; error?: unknown; response: Response });
  },

  async saleReceipt(saleId) {
    return unwrap(await api.GET('/api/v1/retail/sales/{sale_id}/receipt', { params: { path: { sale_id: saleId } } }));
  },

  async saleReceiptPrinted(saleId) {
    return unwrap(await api.GET('/api/v1/retail/sales/{sale_id}/receipt/printed', { params: { path: { sale_id: saleId } } }));
  },

  async saleReceiptPdf(saleId) {
    const result = await api.GET('/api/v1/retail/sales/{sale_id}/receipt/pdf', { params: { path: { sale_id: saleId } }, parseAs: 'blob' });
    return unwrap(result as { data?: Blob; error?: unknown; response: Response });
  },

  async listSaleDocuments(saleId) {
    return unwrap(await api.GET('/api/v1/retail/sales/{sale_id}/documents', { params: { path: { sale_id: saleId } } })).items ?? [];
  },

  async issueSaleDocument(saleId, body, key) {
    return unwrap(await api.POST('/api/v1/retail/sales/{sale_id}/documents', { params: { path: { sale_id: saleId }, header: { 'Idempotency-Key': key } }, body }));
  },

  async issueTransferDocument(transferId, key) {
    return unwrap(await api.POST('/api/v1/retail/transfers/{transfer_id}/documents', { params: { path: { transfer_id: transferId }, header: { 'Idempotency-Key': key } }, body: { type: 'delivery_note' } }));
  },

  async listDocuments({ branchId, type, from, to, query, cursor, signal }) {
    return unwrap(
      await api.GET('/api/v1/retail/documents', {
        params: { query: { branch_id: branchId ? [branchId] : undefined, type: type || undefined, from: from || undefined, to: to || undefined, query: query || undefined, limit: 50, cursor } },
        signal,
      }),
    );
  },

  async renderDocument(id) {
    return unwrap(await api.POST('/api/v1/retail/documents/{document_id}/pdf', { params: { path: { document_id: id } } }));
  },

  async documentUrl(pdfDocumentId) {
    return unwrap(await api.POST('/api/v1/documents/{document_id}/download-url', { params: { path: { document_id: pdfDocumentId } } })).url ?? '';
  },

  async documentSeries() {
    return unwrap(await api.GET('/api/v1/retail/document-series')).items ?? [];
  },

  async setDocumentPrefix(type, prefix) {
    return unwrap(await api.PUT('/api/v1/retail/document-series/{type}', { params: { path: { type } }, body: { prefix } })).items ?? [];
  },

  async listQuotations({ branchId, status, query, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/quotations', { params: { query: { branch_id: branchId ? [branchId] : undefined, status, query: query || undefined, limit: 50, cursor } }, signal }));
  },

  async getQuotation(id) {
    return unwrap(await api.GET('/api/v1/retail/quotations/{quotation_id}', { params: { path: { quotation_id: id } } }));
  },

  async createQuotation(body, key) {
    return unwrap(await api.POST('/api/v1/retail/quotations', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async cancelQuotation(id, reason) {
    return unwrap(await api.POST('/api/v1/retail/quotations/{quotation_id}/cancel', { params: { path: { quotation_id: id } }, body: { reason } }));
  },

  async convertQuotation(id, body, key) {
    return unwrap(await api.POST('/api/v1/retail/quotations/{quotation_id}/convert', { params: { path: { quotation_id: id }, header: { 'Idempotency-Key': key } }, body }));
  },

  async createSaleReturn(saleId, body, key) {
    return unwrap(await api.POST('/api/v1/retail/sales/{sale_id}/returns', { params: { path: { sale_id: saleId }, header: { 'Idempotency-Key': key } }, body }));
  },

  async listSaleReturns(saleId) {
    return unwrap(await api.GET('/api/v1/retail/sales/{sale_id}/returns', { params: { path: { sale_id: saleId } } }));
  },

  async listReturns({ query, status, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/returns', { params: { query: { q: query || undefined, status: status || undefined, limit: 50, cursor } }, signal }));
  },

  async getReturn(id) {
    return unwrap(await api.GET('/api/v1/retail/returns/{return_id}', { params: { path: { return_id: id } } }));
  },

  async voidReturn(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/returns/{return_id}/void', { params: { path: { return_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  // JUSTIFICATION-A3: the purchasing capability's calls are new API surface (issue #198), each one a single typed request like the ones above.
  async listPurchaseOrders({ query, status, supplierId, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/purchase-orders', { params: { query: { q: query || undefined, status: status || undefined, supplier_id: supplierId, limit: 50, cursor } }, signal }));
  },

  async getPurchaseOrder(id) {
    return unwrap(await api.GET('/api/v1/retail/purchase-orders/{order_id}', { params: { path: { order_id: id } } }));
  },

  async createPurchaseOrder(body, key) {
    return unwrap(await api.POST('/api/v1/retail/purchase-orders', { params: { header: { 'Idempotency-Key': key } }, body }));
  },

  async updatePurchaseOrder(id, version, body) {
    return unwrap(await api.PUT('/api/v1/retail/purchase-orders/{order_id}', { params: { path: { order_id: id }, header: { 'If-Match': `"${version}"` } }, body }));
  },

  async sendPurchaseOrder(id, key) {
    return unwrap(await api.POST('/api/v1/retail/purchase-orders/{order_id}/send', { params: { path: { order_id: id }, header: { 'Idempotency-Key': key } } }));
  },

  async cancelPurchaseOrder(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/purchase-orders/{order_id}/cancel', { params: { path: { order_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async listGoodsReceipts({ query, status, orderId, supplierId, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/goods-receipts', { params: { query: { q: query || undefined, status: status || undefined, order_id: orderId, supplier_id: supplierId, limit: 50, cursor } }, signal }));
  },

  async getGoodsReceipt(id) {
    return unwrap(await api.GET('/api/v1/retail/goods-receipts/{receipt_id}', { params: { path: { receipt_id: id } } }));
  },

  async createGoodsReceipt(body, key) {
    return unwrap(await api.POST('/api/v1/retail/goods-receipts', { params: { header: { 'Idempotency-Key': key } }, body }));
  },

  async voidGoodsReceipt(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/goods-receipts/{receipt_id}/void', { params: { path: { receipt_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async listSupplierBills({ query, openOnly, supplierId, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/supplier-bills', { params: { query: { q: query || undefined, open: openOnly || undefined, supplier_id: supplierId, limit: 50, cursor } }, signal }));
  },

  async getSupplierBill(id) {
    return unwrap(await api.GET('/api/v1/retail/supplier-bills/{bill_id}', { params: { path: { bill_id: id } } }));
  },

  async createSupplierBill(body, key) {
    return unwrap(await api.POST('/api/v1/retail/supplier-bills', { params: { header: { 'Idempotency-Key': key } }, body }));
  },

  async matchSupplierBill(body) {
    return unwrap(await api.POST('/api/v1/retail/supplier-bills/match', { body }));
  },

  async voidSupplierBill(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/supplier-bills/{bill_id}/void', { params: { path: { bill_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async unbilledReceipts(supplierId) {
    return unwrap(await api.GET('/api/v1/retail/suppliers/{supplier_id}/unbilled-receipts', { params: { path: { supplier_id: supplierId } } }));
  },

  async listSupplierPayments({ query, status, supplierId, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/supplier-payments', { params: { query: { q: query || undefined, status: status || undefined, supplier_id: supplierId, limit: 50, cursor } }, signal }));
  },

  async getSupplierPayment(id) {
    return unwrap(await api.GET('/api/v1/retail/supplier-payments/{payment_id}', { params: { path: { payment_id: id } } }));
  },

  async createSupplierPayment(body, key) {
    return unwrap(await api.POST('/api/v1/retail/supplier-payments', { params: { header: { 'Idempotency-Key': key } }, body }));
  },

  async reverseSupplierPayment(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/supplier-payments/{payment_id}/reverse', { params: { path: { payment_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async supplierOpenItems(supplierId) {
    return unwrap(await api.GET('/api/v1/retail/suppliers/{supplier_id}/open-items', { params: { path: { supplier_id: supplierId } } }));
  },

  async supplierPayables() {
    return unwrap(await api.GET('/api/v1/retail/reports/supplier-payables', { params: { query: {} } }));
  },

  async supplierStatement(supplierId, { from, to }) {
    return unwrap(await api.GET('/api/v1/retail/suppliers/{supplier_id}/statement', { params: { path: { supplier_id: supplierId }, query: { from: from || undefined, to: to || undefined } } }));
  },

  async purchasingSettings() {
    return unwrap(await api.GET('/api/v1/retail/purchasing/settings'));
  },

  async changePurchasingSettings(version, body) {
    return unwrap(await api.PUT('/api/v1/retail/purchasing/settings', { params: { header: { 'If-Match': `"${version}"` } }, body }));
  },

  async releaseReceiptHold(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/goods-receipts/{receipt_id}/release-hold', { params: { path: { receipt_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async listLandedCosts({ query, status, supplierId, receiptId, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/landed-costs', { params: { query: { q: query || undefined, status: status || undefined, supplier_id: supplierId, receipt_id: receiptId, limit: 50, cursor } }, signal }));
  },

  async getLandedCost(id) {
    return unwrap(await api.GET('/api/v1/retail/landed-costs/{landed_cost_id}', { params: { path: { landed_cost_id: id } } }));
  },

  async createLandedCost(body, key) {
    return unwrap(await api.POST('/api/v1/retail/landed-costs', { params: { header: { 'Idempotency-Key': key } }, body }));
  },

  async previewLandedCost(body) {
    return unwrap(await api.POST('/api/v1/retail/landed-costs/preview', { body }));
  },

  async voidLandedCost(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/landed-costs/{landed_cost_id}/void', { params: { path: { landed_cost_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async listSupplierCredits({ query, status, supplierId, billId, cursor, signal }) {
    return unwrap(await api.GET('/api/v1/retail/supplier-credits', { params: { query: { q: query || undefined, status: status || undefined, supplier_id: supplierId, bill_id: billId, limit: 50, cursor } }, signal }));
  },

  async getSupplierCredit(id) {
    return unwrap(await api.GET('/api/v1/retail/supplier-credits/{credit_id}', { params: { path: { credit_id: id } } }));
  },

  async createSupplierCredit(body, key) {
    return unwrap(await api.POST('/api/v1/retail/supplier-credits', { params: { header: { 'Idempotency-Key': key } }, body }));
  },

  async voidSupplierCredit(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/supplier-credits/{credit_id}/void', { params: { path: { credit_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
  },

  async renderCreditNote(returnId) {
    return unwrap(await api.POST('/api/v1/retail/returns/{return_id}/document', { params: { path: { return_id: returnId } } }));
  },

  async createSale(body, key) {
    return unwrap(await api.POST('/api/v1/retail/sales', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async createPurchase(body, key) {
    return unwrap(await api.POST('/api/v1/retail/purchases', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async listPurchases({ branchId, from, to, supplierId, categoryId, productId, status, cursor, limit, signal }) {
    return unwrap(
      await api.GET('/api/v1/retail/purchases', {
        params: {
          query: {
            newest_first: true, ...(branchId ? { branch_id: [branchId] } : {}), ...(from ? { from } : {}), ...(to ? { to } : {}),
            ...(supplierId ? { supplier_id: supplierId } : {}), ...(categoryId ? { category_id: categoryId } : {}), ...(productId ? { product_id: productId } : {}),
            ...(status ? { status } : {}), ...(cursor ? { cursor } : {}), ...(limit ? { limit } : {}),
          },
        },
        signal,
      }),
    );
  },

  async purchasesSummary({ level, chain, branchId, from, to, supplierId, categoryId, itemId, status, offset, signal }) {
    return unwrap(
      await api.GET('/api/v1/retail/purchases/summary', {
        params: {
          query: {
            group_by: chain.join(','), level, branch_id: branchId ? [branchId] : undefined, from: from || undefined, to: to || undefined,
            supplier_id: supplierId || undefined, category_id: categoryId || undefined, item_id: itemId || undefined, status, offset: offset || undefined,
          },
        },
        signal,
      }),
    );
  },

  async stockSummary({ level, chain, branchId, categoryId, query, negativeOnly, stockLevel, includeClosed, includeInactive, offset, signal }) {
    return unwrap(
      await api.GET('/api/v1/retail/stock/summary', {
        params: {
          query: {
            group_by: chain.join(','), level, branch_id: branchId ? [branchId] : undefined, category_id: categoryId || undefined, query: query || undefined,
            negative_only: negativeOnly || undefined, stock_level: stockLevel, include_closed: includeClosed || undefined, include_inactive: includeInactive || undefined, offset: offset || undefined,
          },
        },
        signal,
      }),
    );
  },

  async getPurchase(id) {
    return unwrap(await api.GET('/api/v1/retail/purchases/{purchase_id}', { params: { path: { purchase_id: id } } }));
  },

  async voidPurchase(id, reason, key) {
    return unwrap(await api.POST('/api/v1/retail/purchases/{purchase_id}/void', { params: { path: { purchase_id: id }, header: { 'Idempotency-Key': key } }, body: { reason } }));
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

  async branchRoles() {
    return unwrap(await api.GET('/api/v1/retail/branch-roles')).items ?? [];
  },

  async setBranchRole(branchId, role) {
    return unwrap(await api.PUT('/api/v1/retail/branch-roles/{branch_id}', { params: { path: { branch_id: branchId } }, body: { role } }));
  },

  async capabilities() {
    const result = await api.GET('/api/v1/retail/settings/capabilities');
    return { capabilities: unwrap(result), version: versionOf(result.response) };
  },

  async changeCapabilities(body, version) {
    const result = await api.PUT('/api/v1/retail/settings/capabilities', { params: { header: { 'If-Match': `"${version}"` } }, body });
    return { capabilities: unwrap(result), version: versionOf(result.response) };
  },

  async dailyProfit({ branchId, from, to }) {
    return unwrap(await api.GET('/api/v1/retail/reports/profit/daily', { params: { query: { branch_id: branchId ? [branchId] : undefined, from, to } } }));
  },

  async createTransfer(body, key) {
    return unwrap(await api.POST('/api/v1/retail/transfers', { body, params: { header: { 'Idempotency-Key': key } } }));
  },

  async listTransfers({ branchId, status, cursor }) {
    return unwrap(
      await api.GET('/api/v1/retail/transfers', { params: { query: { branch_id: branchId ? [branchId] : undefined, status, limit: 50, cursor } } }),
    );
  },

  async getTransfer(id) {
    return unwrap(await api.GET('/api/v1/retail/transfers/{transfer_id}', { params: { path: { transfer_id: id } } }));
  },

  async voidTransfer(id, reason) {
    return unwrap(await api.POST('/api/v1/retail/transfers/{transfer_id}/void', { params: { path: { transfer_id: id } }, body: { reason } }));
  },

  async receiveTransfer(id, lines, key) {
    return unwrap(
      await api.POST('/api/v1/retail/transfers/{transfer_id}/receive', {
        params: { path: { transfer_id: id }, header: { 'Idempotency-Key': key } },
        body: lines ? { lines } : {},
      }),
    );
  },

  async cancelTransfer(id, reason, key) {
    return unwrap(
      await api.POST('/api/v1/retail/transfers/{transfer_id}/cancel', {
        params: { path: { transfer_id: id }, header: { 'Idempotency-Key': key } },
        body: { reason },
      }),
    );
  },

  async stockByLocation({ query, categoryId, negativeOnly, signal }) {
    const items: LocationRow[] = [];
    let report: StockByLocation = { branches: [], items: [] };
    let cursor: string | undefined;
    do {
      const page = unwrap(
        await api.GET('/api/v1/retail/reports/stock-by-location', {
          params: { query: { query: query || undefined, category_id: categoryId || undefined, negative_only: negativeOnly || undefined, limit: PAGE, cursor } },
          signal,
        }),
      );
      report = page;
      items.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    return { ...report, items };
  },

  async transfersInFlight() {
    return unwrap(await api.GET('/api/v1/retail/reports/transfers-in-flight'));
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
