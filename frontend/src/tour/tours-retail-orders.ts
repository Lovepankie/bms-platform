import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// Page tours for customer orders with delivery (issue #385, ADR-052): the Orders page, one order, and the delivery fees and
// cancellations report. Plain words, short sentences, say what to tap. tours-retail-orders.test.tsx renders each page and
// fails if an anchor is missing.

const ORDERS = 'retail.order.manage';

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);

const retail = (step: Omit<TourStep, 'module'>): TourStep => ({ module: 'retail', ...step });

export const ordersPage: Tour = {
  id: 'page-orders',
  version: 1,
  title: 'This page: orders',
  audience: (me) => held(me, ORDERS),
  autoStart: false,
  page: '/staff/retail/orders',
  steps: [
    retail({ id: 'help', target: 'orders-help', title: 'Orders from chats and calls', body: 'Use this page for orders that come by WhatsApp, Instagram, Facebook, a phone call or at the counter, and are delivered later.' }),
    retail({ id: 'new', target: 'order-new', optional: true, title: 'Take an order in seconds', body: 'Tap New order and type the phone number first. A customer who ordered before is found, with their place and fee. Pick where the order came from. Add the items, the place and the fee. Saving keeps the items aside, so the till cannot sell them.' }),
    retail({ id: 'board', target: 'orders-board', title: 'Each step', body: 'Tap New, Packed, Out for delivery, To deliver today or Late to see those orders.' }),
    retail({ id: 'list', target: 'orders-list', title: 'Move an order on', body: 'On an order tap Packed, then Out for delivery and type who takes it. Tap Delivered and say how the customer paid: all of it, part of it, or later. It then becomes a normal sale. Cancel puts the items back on the shelf.' }),
    retail({ id: 'links', target: 'orders-links', title: 'Fees and cancellations', body: 'Tap Delivery fees and cancellations to see the fees taken and the orders cancelled, and why.' }),
  ],
};

export const orderPage: Tour = {
  id: 'page-order',
  version: 1,
  title: 'This page: one order',
  audience: (me) => held(me, ORDERS),
  autoStart: false,
  page: '/staff/retail/orders/$orderId',
  steps: [
    retail({ id: 'summary', target: 'order-summary', title: 'The order', body: 'Who ordered, where it goes, the items, the delivery fee and where it stands. The buttons move it to the next step.' }),
    retail({ id: 'history', target: 'order-history', title: 'Who did what', body: 'Every step with the time and the person: taken, changed, packed, sent out, delivered or cancelled.' }),
    retail({ id: 'back', target: 'order-back', title: 'Back to the list', body: 'Tap Back to the orders to see the others.' }),
  ],
};

export const ordersReportPage: Tour = {
  id: 'page-orders-report',
  version: 1,
  title: 'This page: delivery fees and cancellations',
  audience: (me) => held(me, ORDERS),
  autoStart: false,
  page: '/staff/retail/orders-report',
  steps: [
    retail({ id: 'range', target: 'orders-report-range', title: 'Pick the days', body: 'Choose the first and the last day. Use at most 366 days.' }),
    retail({ id: 'fees', target: 'orders-report-fees', title: 'Delivery fees', body: 'The fees taken on the orders delivered. Group them by day, by where the orders came from, or by who delivered them.' }),
    retail({ id: 'cancelled', target: 'orders-report-cancelled', title: 'Cancelled orders', body: 'Each cancelled order with the reason. Tap a number to open the order.' }),
    retail({ id: 'links', target: 'orders-report-links', title: 'The orders and the sales', body: 'Tap Orders for the open orders, or All sales for the sales the delivered orders became.' }),
  ],
};

export const RETAIL_ORDER_TOURS: readonly Tour[] = [ordersPage, orderPage, ordersReportPage];
