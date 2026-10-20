import { type AnyRouter, Match, RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { beforeAll } from 'vitest';
import type { Me } from '../api/client';
import type { CustomerAccount, GoodsReceipt, LandedCost, PurchaseOrder, Sale, SaleReturn, SupplierBill, SupplierCredit, SupplierPayment } from '../api/retail';
import { mockMe } from '../api/retail-mock';
import { router as appRouter } from '../app/router';
import { CreditNotePage } from '../areas/staff/retail/credit-note';
import { AccountPage } from '../areas/staff/retail/account';
import { blankCredit } from '../areas/staff/retail/credits-state';
import { GoodsReceivedPage, GoodsReceiptPage } from '../areas/staff/retail/goods-received';
import { ReceiveAgainstOrderPage, ReceiveBlindPage } from '../areas/staff/retail/goods-receive';
import { LandedCostPage } from '../areas/staff/retail/landed-costs';
import { PurchaseOrderPage } from '../areas/staff/retail/purchase-order';
import { PurchaseOrderEditPage, PurchaseOrderNewPage } from '../areas/staff/retail/purchase-order-form';
import { ReturnNewPage } from '../areas/staff/retail/return-new';
import { SupplierBillNewPage } from '../areas/staff/retail/supplier-bill-new';
import { SupplierBillPage, SupplierBillsPage } from '../areas/staff/retail/supplier-bills';
import { CreditFormView } from '../areas/staff/retail/supplier-credit-new';
import { SupplierCreditPage, SupplierCreditsPage } from '../areas/staff/retail/supplier-credits';
import { SupplierPaymentPage, SupplierPaymentsPage } from '../areas/staff/retail/supplier-payments';
import { SupplierPayablesPage } from '../areas/staff/retail/supplier-payables';
import { describeTours, type Seed } from './test-kit';
import { RETAIL_PURCHASING_TOURS } from './tours-retail-purchasing';

// Every page tour of the returns, customer account and purchasing screens has its anchors on the page it points at (issue #337).
// The detail pages read their address (getRouteApi), so each is drawn under a router that stands on its own address, with
// fabricated answers already in the query cache. All names, numbers and amounts are made up.

const ADMIN: Me = { ...mockMe('admin'), modules: ['retail'] } as Me;
const RETURNER: Me = { ...ADMIN, full_name: 'Test Returner 01', module_capabilities: { retail: ['returns'] } };
const LENDER: Me = { ...ADMIN, full_name: 'Test Lender 01', module_capabilities: { retail: ['customer_accounts'] } };
const BUYER: Me = { ...ADMIN, full_name: 'Test Buyer 01', module_capabilities: { retail: ['purchasing'] } };

const BRANCH = ADMIN.branches?.[0]?.id ?? '';
const SALE = '00000000-0000-4000-8000-0000000000b1';
const RETURN = '00000000-0000-4000-8000-0000000000b2';
const CUSTOMER = '00000000-0000-4000-8000-0000000000b3';
const SUPPLIER = '00000000-0000-4000-8000-0000000000b4';
const ORDER = '00000000-0000-4000-8000-0000000000b5';
const RECEIPT = '00000000-0000-4000-8000-0000000000b6';
const BILL = '00000000-0000-4000-8000-0000000000b7';
const PAYMENT = '00000000-0000-4000-8000-0000000000b8';
const LANDED = '00000000-0000-4000-8000-0000000000b9';
const CREDIT = '00000000-0000-4000-8000-0000000000ba';
const DRAFT = '00000000-0000-4000-8000-0000000000bb';

const sale: Sale = {
  id: SALE, sale_no: 'S-0001', status: 'completed', branch_id: BRANCH, customer_id: CUSTOMER, buyer_name: 'Test Buyer Ltd', sale_date: '2026-10-01', total_minor: 500000, payment_method: 'credit',
  lines: [{ id: 'l1', description: 'Test Item 01', sale_qty: '5.000', qty: '5.000', unit_price_minor: 100000, line_total_minor: 500000 }],
} as Sale;
const credit: SaleReturn = { id: RETURN, credit_note_no: 'CN-0001', status: 'completed', branch_id: BRANCH, sale_no: 'S-0001', customer_id: CUSTOMER, customer_name: 'Test Buyer Ltd', returned_on: '2026-10-02', total_minor: 100000, lines: [] } as SaleReturn;
const account: CustomerAccount = { id: CUSTOMER, name: 'Test Buyer Ltd', balance_minor: 0, version: 1 } as CustomerAccount;
const order = (id: string, status: string): PurchaseOrder => ({
  id, order_no: 'PO-0001', status, branch_id: BRANCH, supplier_id: SUPPLIER, supplier_name: 'Test Supplier 01', ordered_on: '2026-10-01', total_minor: 300000, version: 1,
  lines: [{ id: 'ol1', product_id: 'p1', code: 'P001', description: 'Test Item 01', unit: 'piece', qty: '10.000', received_qty: '0.000', unit_cost_minor: 30000, line_total_minor: 300000 }],
} as PurchaseOrder);
const receipt: GoodsReceipt = { id: RECEIPT, receipt_no: 'GR-0001', status: 'posted', branch_id: BRANCH, supplier_id: SUPPLIER, supplier_name: 'Test Supplier 01', order_id: ORDER, order_no: 'PO-0001', received_on: '2026-10-03', total_minor: 300000, lines: [] } as GoodsReceipt;
const bill: SupplierBill = {
  id: BILL, bill_no: 'SB-0001', supplier_ref: 'INV-77', status: 'posted', kind: 'goods', branch_id: BRANCH, supplier_id: SUPPLIER, supplier_name: 'Test Supplier 01', bill_date: '2026-10-04', due_date: '2026-11-04',
  total_minor: 300000, open_minor: 300000, lines: [{ id: 'bl1', description: 'Test Item 01', receipt_id: RECEIPT, receipt_no: 'GR-0001', qty: '10.000', unit: 'piece', unit_price_minor: 30000, line_total_minor: 300000 }],
} as SupplierBill;
const payment: SupplierPayment = { id: PAYMENT, payment_no: 'SP-0001', status: 'posted', branch_id: BRANCH, supplier_id: SUPPLIER, supplier_name: 'Test Supplier 01', paid_on: '2026-10-05', amount_minor: 100000, method: 'cash', allocations: [{ bill_id: BILL, bill_no: 'SB-0001', amount_minor: 100000 }] } as SupplierPayment;
const landed: LandedCost = { id: LANDED, landed_no: 'LC-0001', status: 'posted', branch_id: BRANCH, supplier_id: SUPPLIER, supplier_name: 'Test Forwarder 01', bill_id: BILL, bill_no: 'SB-0001', method: 'value', total_minor: 50000, paid_minor: 0, charges: [{ kind: 'freight', amount_minor: 50000 }], lines: [{ receipt_id: RECEIPT, receipt_no: 'GR-0001', description: 'Test Item 01', qty: '10.000', allocated_minor: 50000 }] } as LandedCost;
const supplierCredit: SupplierCredit = { id: CREDIT, credit_no: 'SC-0001', supplier_ref: 'CR-9', status: 'posted', kind: 'goods', branch_id: BRANCH, supplier_id: SUPPLIER, supplier_name: 'Test Supplier 01', bill_id: BILL, bill_no: 'SB-0001', credit_date: '2026-10-06', total_minor: 30000, lines: [] } as SupplierCredit;

const seed: Seed = (client) => {
  client.setQueryData(['retail', 'sale', SALE], sale);
  client.setQueryData(['retail', 'sale-returns', SALE], []);
  client.setQueryData(['retail', 'return', RETURN], credit);
  client.setQueryData(['retail', 'account', CUSTOMER], account);
  client.setQueryData(['retail', 'account-balance', CUSTOMER], { open_sales: [] });
  client.setQueryData(['retail', 'account-payments', CUSTOMER], { items: [] });
  // A sent order is the one goods are received against and the one whose actions show; a draft is the one that can be changed.
  client.setQueryData(['retail', 'purchase-order', ORDER], order(ORDER, 'sent'));
  client.setQueryData(['retail', 'purchase-order', DRAFT], order(DRAFT, 'draft'));
  client.setQueryData(['retail', 'goods-receipt', RECEIPT], receipt);
  client.setQueryData(['retail', 'supplier-bill', BILL], bill);
  client.setQueryData(['retail', 'supplier-payment', PAYMENT], payment);
  client.setQueryData(['retail', 'supplier-payables'], { as_of: '2026-10-07', suppliers: [{ supplier_id: SUPPLIER, supplier_name: 'Test Supplier 01', owed_minor: 300000 }], totals: { owed_minor: 300000 } });
  client.setQueryData(['retail', 'landed-cost', LANDED], landed);
  client.setQueryData(['retail', 'supplier-credit', CREDIT], supplierCredit);
};

// Each tour page, the address a visitor is on, and the page drawn there.
// A page that reads the address search (useSearch) is drawn through its route match, which carries it.
const here = (path: string): ReactElement => <Match routeId={routers.get(path)?.state.matches.at(-1)?.routeId as never} />;

const PAGES: Record<string, { url: string; screen: () => ReactElement }> = {
  '/staff/retail/returns/new/$saleId': { url: `/staff/retail/returns/new/${SALE}`, screen: () => <ReturnNewPage /> },
  '/staff/retail/returns/$returnId': { url: `/staff/retail/returns/${RETURN}`, screen: () => <CreditNotePage /> },
  '/staff/retail/accounts/$customerId': { url: `/staff/retail/accounts/${CUSTOMER}`, screen: () => <AccountPage /> },
  '/staff/retail/purchase-orders/new': { url: '/staff/retail/purchase-orders/new', screen: () => <PurchaseOrderNewPage /> },
  '/staff/retail/purchase-orders/$orderId/edit': { url: `/staff/retail/purchase-orders/${DRAFT}/edit`, screen: () => <PurchaseOrderEditPage /> },
  '/staff/retail/purchase-orders/$orderId': { url: `/staff/retail/purchase-orders/${ORDER}`, screen: () => <PurchaseOrderPage /> },
  '/staff/retail/purchase-orders/$orderId/receive': { url: `/staff/retail/purchase-orders/${ORDER}/receive`, screen: () => <ReceiveAgainstOrderPage /> },
  '/staff/retail/goods-received': { url: '/staff/retail/goods-received', screen: () => <GoodsReceivedPage /> },
  '/staff/retail/goods-received/$receiptId': { url: `/staff/retail/goods-received/${RECEIPT}`, screen: () => <GoodsReceiptPage /> },
  '/staff/retail/goods-received/new': { url: '/staff/retail/goods-received/new', screen: () => <ReceiveBlindPage /> },
  '/staff/retail/supplier-bills': { url: '/staff/retail/supplier-bills', screen: () => <SupplierBillsPage /> },
  '/staff/retail/supplier-bills/new': { url: '/staff/retail/supplier-bills/new', screen: () => <SupplierBillNewPage /> },
  '/staff/retail/supplier-bills/$billId': { url: `/staff/retail/supplier-bills/${BILL}`, screen: () => <SupplierBillPage /> },
  '/staff/retail/supplier-payments': { url: '/staff/retail/supplier-payments', screen: () => <SupplierPaymentsPage /> },
  '/staff/retail/supplier-payments/new': { url: '/staff/retail/supplier-payments/new', screen: () => here('/staff/retail/supplier-payments/new') },
  '/staff/retail/supplier-payments/$paymentId': { url: `/staff/retail/supplier-payments/${PAYMENT}`, screen: () => <SupplierPaymentPage /> },
  '/staff/retail/supplier-payables': { url: '/staff/retail/supplier-payables', screen: () => <SupplierPayablesPage /> },
  '/staff/retail/landed-costs/new': { url: '/staff/retail/landed-costs/new', screen: () => here('/staff/retail/landed-costs/new') },
  '/staff/retail/landed-costs/$landedCostId': { url: `/staff/retail/landed-costs/${LANDED}`, screen: () => <LandedCostPage /> },
  '/staff/retail/supplier-credits': { url: '/staff/retail/supplier-credits', screen: () => <SupplierCreditsPage /> },
  // Without an invoice chosen the page shows the invoice finder; the form a chosen invoice opens is drawn beside it.
  '/staff/retail/supplier-credits/new': {
    url: '/staff/retail/supplier-credits/new',
    screen: () => (
      <>
        {here('/staff/retail/supplier-credits/new')}
        <CreditFormView form={{ ...blankCredit('2026-10-07'), billId: BILL }} bill={bill} onChange={() => undefined} onSubmit={() => undefined} onOtherInvoice={() => undefined} />
      </>
    ),
  },
  '/staff/retail/supplier-credits/$creditId': { url: `/staff/retail/supplier-credits/${CREDIT}`, screen: () => <SupplierCreditPage /> },
};

const routers = new Map<string, AnyRouter>();

beforeAll(async () => {
  for (const [path, { url }] of Object.entries(PAGES)) {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [url] }) });
    await router.load();
    routers.set(path, router as AnyRouter);
  }
});

function screenFor(path: string): ReactElement {
  const entry = PAGES[path];
  const router = routers.get(path);
  if (!entry || !router) throw new Error(`No screen is mapped for ${path}: add it to tours-retail-purchasing.test.tsx`);
  return <RouterContextProvider router={router}>{entry.screen()}</RouterContextProvider>;
}

describeTours(
  'purchasing and returns',
  RETAIL_PURCHASING_TOURS,
  {
    'page-return-new': [RETURNER],
    'page-credit-note': [RETURNER],
    'page-account': [LENDER],
    'page-purchase-order-new': [BUYER],
    'page-purchase-order-edit': [BUYER],
    'page-purchase-order': [BUYER],
    'page-order-receive': [BUYER],
    'page-goods-received': [BUYER],
    'page-goods-receipt': [BUYER],
    'page-receive-blind': [BUYER],
    'page-supplier-bills': [BUYER],
    'page-supplier-bill-new': [BUYER],
    'page-supplier-bill': [BUYER],
    'page-supplier-payments': [BUYER],
    'page-supplier-payment-new': [BUYER],
    'page-supplier-payment': [BUYER],
    'page-supplier-payables': [BUYER],
    'page-landed-cost-new': [BUYER],
    'page-landed-cost': [BUYER],
    'page-supplier-credits': [BUYER],
    'page-supplier-credit-new': [BUYER],
    'page-supplier-credit': [BUYER],
  },
  screenFor,
  seed,
);
