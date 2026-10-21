import type { ReactElement } from 'react';
import type { Order } from '../api/retail-orders';
import { mockMe } from '../api/retail-mock';
import { OrderPage } from '../areas/staff/retail/order';
import { OrdersPage } from '../areas/staff/retail/orders';
import { OrdersReportPage } from '../areas/staff/retail/orders-report';
import { RETAIL_ORDER_TOURS } from './tours-retail-orders';
import { describeTours, type Seed } from './test-kit';

// Every page tour of customer orders (issue #385): the screen each step points at is rendered as the user the tour is for, and
// the step's data-tour anchor must be in its markup. Names, phones and amounts are fabricated.

const ADMIN = mockMe('admin');
const SELLER = mockMe('sales');
const ORDER = '00000000-0000-4000-8000-0000000a0001';

const order = {
  id: ORDER, order_no: 'OR000001', branch_id: ADMIN.default_branch_id, ordered_on: '2026-10-21', source: 'whatsapp', customer_name: 'Test Customer 01',
  customer_phone: '+256700000011', delivery_place: 'Test Place, Plot 1', delivery_date: '2026-10-21', delivery_fee_minor: 5000, currency: 'UGX',
  items_total_minor: 20000, total_minor: 25000, status: 'ordered', created_at: '2026-10-21T08:00:00Z', created_by: ADMIN.user_id, version: 1,
  lines: [{ line_no: 1, product_id: 'p1', code: 'ROMPER', description: 'Test Romper', qty: '2', unit: 'piece', unit_price_minor: 10000, line_total_minor: 20000, base_qty: '2' }],
  history: [{ step: 'ordered', by_user: ADMIN.user_id, at: '2026-10-21T08:00:00Z' }],
} as Order;

const seed: Seed = (client) => {
  client.setQueryData(['retail', 'order', ORDER], order);
};

function screen(path: string): ReactElement {
  switch (path) {
    case '/staff/retail/orders': return <OrdersPage />;
    case '/staff/retail/orders/$orderId': return <OrderPage orderId={ORDER} />;
    case '/staff/retail/orders-report': return <OrdersReportPage />;
    default: throw new Error(`no screen for ${path}`);
  }
}

describeTours('customer orders', RETAIL_ORDER_TOURS, {
  'page-orders': [ADMIN, SELLER],
  'page-order': [ADMIN, SELLER],
  'page-orders-report': [ADMIN],
}, screen, seed);
