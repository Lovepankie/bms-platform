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
/** A pack size of an item (units of measure, ADR-033): `factor` base units in one pack of `unit`, three places ("12.000"). */
export type Pack = S['RetailPack'];
export type StockRow = S['RetailStockRow'];
export type AllBranchesRow = S['RetailAllBranchesRow'];
export type StockBranch = S['RetailStockBranch'];
export type AllBranchesStock = { branches: StockBranch[]; items: AllBranchesRow[] };
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
export type PriceEdit = S['RetailPriceEditRequest'];
export type ImportResult = S['RetailProductImportResult'];
export type ImportRow = S['RetailProductImportRow'];
export type Customer = S['RetailCustomer'];
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
  listProducts(q: { query?: string; branchId?: string; signal?: AbortSignal }): Promise<Product[]>;
  listCategories(): Promise<Category[]>;
  /** Catalogue management (#146): one page of products, with the filters of the Products screen. */
  listCatalogue(q: { query?: string; categoryId?: string; active?: boolean; cursor?: string; signal?: AbortSignal }): Promise<ProductPage>;
  getProduct(id: string): Promise<Product>;
  createProduct(body: NewProduct): Promise<Product>;
  /** Non-price fields, under If-Match with the version read. */
  updateProduct(id: string, version: number, body: ProductChange): Promise<Product>;
  /** A price change under If-Match; the server writes the history row. */
  editPrices(id: string, version: number, body: PriceEdit): Promise<Product>;
  priceHistory(id: string): Promise<PriceChange[]>;
  /** The pack sizes of an item (the units capability, issue #194); 404 capability_not_enabled while it is off. */
  listPacks(productId: string): Promise<Pack[]>;
  addPack(productId: string, body: { unit_id: string; factor: string }): Promise<Pack>;
  /** Under If-Match with the pack's version: a new factor, or switch the pack off or on. */
  updatePack(productId: string, unitId: string, version: number, body: { factor?: string; active?: boolean }): Promise<Pack>;
  /** The CSV item import: a dry run reports, an apply adds the rows that are fine (administrators only). */
  importProducts(csv: string, dryRun: boolean): Promise<ImportResult>;
  createCategory(name: string): Promise<Category>;
  updateCategory(id: string, version: number, body: { name?: string; active?: boolean }): Promise<Category>;
  listUnits(): Promise<Unit[]>;
  createUnit(name: string): Promise<Unit>;
  updateUnit(id: string, version: number, body: { name?: string; active?: boolean }): Promise<Unit>;
  listStock(q: { branchId: string; query?: string; categoryId?: string; negativeOnly?: boolean; level?: StockLevel; signal?: AbortSignal }): Promise<StockRow[]>;
  /** One page of sales, newest first, narrowed by the filters (#145). */
  listSales(q: SalesQuery): Promise<SalePage>;
  getSale(id: string): Promise<Sale>;
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
  createUsage(body: UsageRequest, idempotencyKey: string): Promise<Usage>;
  createStocktake(body: StocktakeRequest): Promise<Stocktake>;
  commitStocktake(id: string): Promise<Stocktake>;
  /** Every product with its balance in each branch the caller may read (#144). */
  listStockAllBranches(q: { query?: string; categoryId?: string; negativeOnly?: boolean; level?: StockLevel; signal?: AbortSignal }): Promise<AllBranchesStock>;
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

  async listStock({ branchId, query, categoryId, negativeOnly, level, signal }) {
    const rows: StockRow[] = [];
    let cursor: string | undefined;
    do {
      const page = unwrap(
        await api.GET('/api/v1/retail/stock', {
          params: { query: { branch_id: branchId, query: query || undefined, category_id: categoryId || undefined, negative_only: negativeOnly || undefined, stock_level: level, limit: PAGE, cursor } },
          signal,
        }),
      );
      rows.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    return rows;
  },

  async listStockAllBranches({ query, categoryId, negativeOnly, level, signal }) {
    const items: AllBranchesRow[] = [];
    let branches: StockBranch[] = [];
    let cursor: string | undefined;
    do {
      const page = unwrap(
        await api.GET('/api/v1/retail/stock/all-branches', {
          params: { query: { query: query || undefined, category_id: categoryId || undefined, negative_only: negativeOnly || undefined, stock_level: level, limit: PAGE, cursor } },
          signal,
        }),
      );
      branches = page.branches ?? branches;
      items.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    return { branches, items };
  },

  async listSales(q) {
    return unwrap(await api.GET('/api/v1/retail/sales', { params: { query: salesParams(q) }, signal: q.signal }));
  },

  async getSale(id) {
    return unwrap(await api.GET('/api/v1/retail/sales/{sale_id}', { params: { path: { sale_id: id } } }));
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
