import { api } from './client';
import { unwrap } from './retail';
import type { components } from './schema';

// Customer orders with delivery (issue #385, ADR-052), typed by the generated schema. An order is taken without payment and keeps
// its stock aside; on delivery it becomes an ordinary sale and its delivery fee is posted on its own. Money is integer minor units.

type S = components['schemas'];

export type Order = S['RetailOrder'];
export type OrderLine = S['RetailOrderLine'];
export type OrderEvent = S['RetailOrderEvent'];
export type OrderPage = S['RetailOrderPage'];
export type OrderBoard = S['RetailOrderBoard'];
export type OrderRequest = S['RetailOrderRequest'];
export type OrderChange = S['RetailOrderChangeRequest'];
export type ItemsRequest = S['RetailOrderItemsRequest'];
export type DeliverRequest = S['RetailOrderDeliverRequest'];
export type Delivered = S['RetailOrderDelivered'];
export type CustomerLookup = S['RetailOrderCustomerLookup'];
export type FeesReport = S['RetailOrderFeesReport'];
export type CancellationsReport = S['RetailOrderCancellationsReport'];

export type OrderSource = 'whatsapp' | 'instagram' | 'facebook' | 'phone' | 'walk_in' | 'other';
export type OrderStatus = 'ordered' | 'packed' | 'out_for_delivery' | 'delivered' | 'cancelled';

export interface OrderQuery {
  branchId?: string;
  status?: 'open' | OrderStatus;
  due?: 'today' | 'overdue';
  source?: OrderSource;
  query?: string;
  cursor?: string;
  signal?: AbortSignal;
}

const branches = (branchId?: string) => (branchId ? [branchId] : undefined);
const path = (id: string) => ({ path: { order_id: id } });

export const retailOrders = {
  async list({ branchId, status, due, source, query, cursor, signal }: OrderQuery): Promise<OrderPage> {
    return unwrap(await api.GET('/api/v1/retail/orders', {
      params: { query: { branch_id: branches(branchId), status, due, source, query: query || undefined, limit: 50, cursor } },
      signal,
    }));
  },
  async board(branchId?: string, signal?: AbortSignal): Promise<OrderBoard> {
    return unwrap(await api.GET('/api/v1/retail/orders/board', { params: { query: { branch_id: branches(branchId) } }, signal }));
  },
  async get(id: string): Promise<Order> {
    return unwrap(await api.GET('/api/v1/retail/orders/{order_id}', { params: path(id) }));
  },
  async create(body: OrderRequest, key: string): Promise<Order> {
    return unwrap(await api.POST('/api/v1/retail/orders', { body, params: { header: { 'Idempotency-Key': key } } }));
  },
  async change(id: string, version: number, body: OrderChange): Promise<Order> {
    return unwrap(await api.PATCH('/api/v1/retail/orders/{order_id}', { params: { ...path(id), header: { 'If-Match': String(version) } }, body }));
  },
  /** The whole new set of items of an order that is ordered or packed (issue #393): the stock kept follows, the number stays. */
  async changeItems(id: string, version: number, body: ItemsRequest, key: string): Promise<Order> {
    return unwrap(await api.POST('/api/v1/retail/orders/{order_id}/items', {
      params: { ...path(id), header: { 'If-Match': String(version), 'Idempotency-Key': key } },
      body,
    }));
  },
  async pack(id: string, note?: string): Promise<Order> {
    return unwrap(await api.POST('/api/v1/retail/orders/{order_id}/pack', { params: path(id), body: note ? { note } : {} }));
  },
  async send(id: string, deliveredBy: string, note?: string): Promise<Order> {
    return unwrap(await api.POST('/api/v1/retail/orders/{order_id}/send', { params: path(id), body: { delivered_by: deliveredBy, ...(note ? { note } : {}) } }));
  },
  async deliver(id: string, body: DeliverRequest, key: string): Promise<Delivered> {
    return unwrap(await api.POST('/api/v1/retail/orders/{order_id}/deliver', { params: { ...path(id), header: { 'Idempotency-Key': key } }, body }));
  },
  async payFee(id: string, method: 'cash' | 'mobile_money' | 'bank', key: string): Promise<Order> {
    return unwrap(await api.POST('/api/v1/retail/orders/{order_id}/fee-payment', { params: { ...path(id), header: { 'Idempotency-Key': key } }, body: { method } }));
  },
  async cancel(id: string, reason: string): Promise<Order> {
    return unwrap(await api.POST('/api/v1/retail/orders/{order_id}/cancel', { params: path(id), body: { reason } }));
  },
  async lookup(phone: string, signal?: AbortSignal): Promise<CustomerLookup> {
    return unwrap(await api.GET('/api/v1/retail/orders/customer-lookup', { params: { query: { phone } }, signal }));
  },
  async fees(q: { branchId?: string; from: string; to: string; groupBy: 'day' | 'source' | 'rider' }, signal?: AbortSignal): Promise<FeesReport> {
    return unwrap(await api.GET('/api/v1/retail/orders/reports/delivery-fees', {
      params: { query: { branch_id: branches(q.branchId), from: q.from, to: q.to, group_by: q.groupBy } },
      signal,
    }));
  },
  async cancellations(q: { branchId?: string; from: string; to: string }, signal?: AbortSignal): Promise<CancellationsReport> {
    return unwrap(await api.GET('/api/v1/retail/orders/reports/cancellations', {
      params: { query: { branch_id: branches(q.branchId), from: q.from, to: q.to } },
      signal,
    }));
  },
};
