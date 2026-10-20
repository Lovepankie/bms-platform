// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Outlet, RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { PurchaseOrder, SaleReturn, SupplierBill } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { CreditNoteView } from './credit-note';
import { PurchaseOrderView } from './purchase-order';
import { SupplierBillView } from './supplier-bills';

// The cross links of the returns and purchasing detail pages (issue #336): a buyer opens their account, a supplier opens what is owed
// to them, a delivery number on an invoice opens the delivery. Each link goes where a screen already exists; the stubs below stand in
// for those screens so the click resolves. Fabricated names and numbers.

afterEach(cleanup);

// A buyer: an administrator of a business with purchasing on, so the delivery and payment screens may be opened.
const ME = { ...mockMe('admin'), module_capabilities: { retail: ['purchasing'] } };

const STUBS = ['/accounts/$customerId', '/supplier-payments/new', '/goods-received/$receiptId'];

function show(view: ReactElement) {
  const root = createRootRoute({ component: () => <QueryClientProvider client={new QueryClient()}><StaffContext.Provider value={{ me: ME, branch: null }}><Outlet /></StaffContext.Provider></QueryClientProvider> });
  const staff = createRoute({ getParentRoute: () => root, path: '/staff' });
  const retail = createRoute({ getParentRoute: () => staff, path: '/retail' });
  const here = createRoute({ getParentRoute: () => retail, path: '/here', component: () => view });
  const stubs = STUBS.map((path) => createRoute({ getParentRoute: () => retail, path, component: () => <p>Opened {path}</p> }));
  const router = createRouter({
    routeTree: root.addChildren([staff.addChildren([retail.addChildren([here, ...stubs])])]),
    history: createMemoryHistory({ initialEntries: ['/staff/retail/here'] }),
  });
  render(<RouterProvider router={router} />);
  return router;
}

const nameOf = () => 'Test Branch 01';
const note = { id: 'r1', credit_note_no: 'CN-0001', status: 'completed', sale_no: 'S-0001', customer_id: 'c1', customer_name: 'Test Buyer Ltd', returned_on: '2026-10-02', total_minor: 100000, lines: [] } as SaleReturn;
const order = { id: 'o1', order_no: 'PO-0001', status: 'sent', supplier_id: 's1', supplier_name: 'Test Supplier 01', lines: [], total_minor: 0 } as PurchaseOrder;
const bill = {
  id: 'b1', bill_no: 'SB-0001', supplier_ref: 'INV-77', status: 'posted', kind: 'goods', supplier_id: 's1', supplier_name: 'Test Supplier 01',
  lines: [{ id: 'bl1', description: 'Test Item 01', receipt_id: 'g1', receipt_no: 'GR-0001', qty: '1.000', unit: 'piece' }],
} as SupplierBill;

describe('links on the returns and purchasing detail pages', () => {
  it('a buyer on a credit note opens the buyer account, and is plain text when accounts are off', async () => {
    const router = show(<CreditNoteView note={note} nameOf={nameOf} buyerOpens />);
    fireEvent.click(await screen.findByRole('link', { name: 'Test Buyer Ltd' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff/retail/accounts/c1'));
    cleanup();
    show(<CreditNoteView note={note} nameOf={nameOf} />);
    expect(await screen.findByText('Test Buyer Ltd')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Test Buyer Ltd' })).toBeNull();
  });

  it('a supplier on an order opens what is owed to them, only for someone who may pay suppliers', async () => {
    const router = show(<PurchaseOrderView order={order} nameOf={nameOf} payable />);
    fireEvent.click(await screen.findByRole('link', { name: 'See what is owed to Test Supplier 01' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff/retail/supplier-payments/new'));
    expect(router.state.location.search).toEqual({ supplier: 's1' });
    cleanup();
    show(<PurchaseOrderView order={order} nameOf={nameOf} />);
    expect(await screen.findByText('Test Supplier 01')).toBeTruthy();
    expect(screen.queryByRole('link', { name: /See what is owed/ })).toBeNull();
  });

  it('a delivery number on a supplier invoice opens the delivery', async () => {
    const router = show(<SupplierBillView bill={bill} nameOf={nameOf} />);
    fireEvent.click(await screen.findByRole('link', { name: 'GR-0001' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff/retail/goods-received/g1'));
  });
});
