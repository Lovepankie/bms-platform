import type { Customer, CustomerGroup, PriceList, PriceListItem, PriceResolution, ResolvedLine, RetailApi } from './retail';

// Fabricated price lists, items and customer groups for VITE_RETAIL_MOCK and for tests (ADR-033, issue #195): the
// REAL snake_case shapes of docs/sdd/07-api-design.md section 7.11.20, held in memory, with the server's rules in
// small: the largest quantity break not above the quantity, valid on the date; an open ended price takes over from
// the current one; no overlap. Nothing here is real. Quantities are decimal strings, money integer minor units.

export interface PricingEnv {
  /** The price_lists capability of this mock instance; every route answers 404 capability_not_enabled while it is off. */
  on: () => boolean;
  today: () => string;
  nextId: (prefix: string) => string;
  delay: <T>(value: T) => Promise<T>;
  run: <T>(fn: () => T) => Promise<T>;
  refuse: (status: number, code: string, detail: string) => never;
  customer: (id: string) => Customer | undefined;
  /** The buyers in a group. */
  customerCount: (groupId: string) => number;
  /** The retail price of an item per base unit, its base unit's id and its description (undefined: no such item). */
  product: (id: string) => { sellMinor: number; unitId: string; code: string; description: string; unit: string } | undefined;
}

export type PricingMock = Pick<
  RetailApi,
  | 'listPriceLists' | 'createPriceList' | 'updatePriceList' | 'listPriceListItems' | 'addPriceListItem' | 'endPriceListItem'
  | 'listCustomerGroups' | 'createCustomerGroup' | 'updateCustomerGroup' | 'resolvePrices'
> & {
  /** The list and break a line takes, for the mock's sale: undefined when no list applies or none has a price. */
  resolveFor: (customerId: string | undefined, listId: string | undefined, productId: string, unitId: string | undefined, qty: string, date: string) => { price: number; list: PriceList; minQty: string } | undefined;
};

const milli = (text: string): number => Math.round(Number(text) * 1000);
const three = (text: string | undefined): string => (Math.round(Number(text ?? '0') * 1000) / 1000).toFixed(3);
const lineTotal = (price: number, qtyMilli: number): number => Number((BigInt(price) * BigInt(qtyMilli) + 500n) / 1000n);
const dayBefore = (date: string): string => {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
};

export function createPricingMock(env: PricingEnv): PricingMock {
  const lists: PriceList[] = [];
  const items: PriceListItem[] = [];
  const groups: CustomerGroup[] = [];
  const gate = <T>(fn: () => T): Promise<T> => env.run(() => (env.on() ? fn() : env.refuse(404, 'capability_not_enabled', 'This feature is not switched on for your business.')));
  const stale = (row: { version?: number }, version: number) => {
    if ((row.version ?? 1) !== version) env.refuse(409, 'version_conflict', 'This was changed by someone else. Reload and try again.');
  };
  const statusOf = (i: PriceListItem): 'scheduled' | 'current' | 'ended' =>
    (i.valid_from ?? '') > env.today() ? 'scheduled' : i.valid_to && i.valid_to < env.today() ? 'ended' : 'current';
  const withStatus = (i: PriceListItem): PriceListItem => ({ ...i, status: statusOf(i) });
  const counted = (l: PriceList): PriceList => ({ ...l, current_items: items.filter((i) => i.price_list_id === l.id && statusOf(i) !== 'ended').length });
  const grouped = (g: CustomerGroup): CustomerGroup => ({ ...g, customer_count: env.customerCount(g.id ?? ''), ...(g.price_list_id ? { price_list: lists.find((l) => l.id === g.price_list_id)?.name } : {}) });
  const active = (id: string | undefined): PriceList | undefined => lists.find((l) => l.id === id && l.active);

  const resolveFor: PricingMock['resolveFor'] = (customerId, listId, productId, unitId, qty, date) => {
    const customer = customerId ? env.customer(customerId) : undefined;
    const group = groups.find((g) => g.id === customer?.customer_group_id && g.active);
    const list = active(listId) ?? active(customer?.price_list_id) ?? active(group?.price_list_id);
    if (!list) return undefined;
    const base = env.product(productId)?.unitId;
    const hit = items
      .filter((i) => i.price_list_id === list.id && i.product_id === productId && i.unit_id === (unitId ?? base) && milli(i.min_qty ?? '0') <= milli(qty) && (i.valid_from ?? '') <= date && (!i.valid_to || i.valid_to >= date))
      .sort((a, b) => milli(b.min_qty ?? '0') - milli(a.min_qty ?? '0'))[0];
    return hit ? { price: hit.price_minor ?? 0, list, minQty: three(hit.min_qty) } : undefined;
  };

  return {
    resolveFor,
    listPriceLists: () => gate(() => lists.map(counted)),

    createPriceList: (body) =>
      gate(() => {
        if (lists.some((l) => l.name?.toLowerCase() === body.name.trim().toLowerCase())) env.refuse(409, 'duplicate_price_list', 'A price list with this name exists.');
        const l: PriceList = { id: env.nextId('d'), name: body.name.trim(), currency: 'UGX', strict: body.strict === true, active: true, current_items: 0, version: 1 };
        lists.push(l);
        return counted(l);
      }),

    updatePriceList: (id, version, body) =>
      gate(() => {
        const l = lists.find((x) => x.id === id);
        if (!l) return env.refuse(404, 'not_found', 'That could not be found.');
        stale(l, version);
        Object.assign(l, { name: body.name?.trim() ?? l.name, strict: body.strict ?? l.strict, active: body.active ?? l.active, version: (l.version ?? 1) + 1 });
        return counted(l);
      }),

    listPriceListItems: (listId, { query, includeEnded }) =>
      gate(() => {
        const q = (query ?? '').trim().toLowerCase();
        const rows = items
          .filter((i) => i.price_list_id === listId && (includeEnded || statusOf(i) !== 'ended'))
          .map(withStatus)
          .filter((i) => !q || `${i.description} ${i.code}`.toLowerCase().includes(q))
          .sort((a, b) => (a.description ?? '').localeCompare(b.description ?? '') || milli(a.min_qty ?? '0') - milli(b.min_qty ?? '0'));
        return { items: rows };
      }),

    addPriceListItem: (listId, body) =>
      gate(() => {
        const list = lists.find((l) => l.id === listId);
        if (!list) return env.refuse(404, 'not_found', 'That could not be found.');
        const p = env.product(body.product_id);
        if (!p) return env.refuse(422, 'validation_failed', 'No such product.');
        const today = env.today();
        const from = body.valid_from ?? today;
        if (from < today) env.refuse(422, 'validation_failed', 'A price cannot start in the past.');
        if (body.valid_to && body.valid_to < from) env.refuse(422, 'validation_failed', 'The last day cannot be before the first day.');
        const min = three(body.min_qty);
        const same = items.filter((i) => i.price_list_id === listId && i.product_id === body.product_id && i.unit_id === body.unit_id && three(i.min_qty) === min && (!i.valid_to || i.valid_to >= from) && (!body.valid_to || (i.valid_from ?? '') <= body.valid_to));
        const takeOver = same.length === 1 && !body.valid_to && !same[0]?.valid_to && (same[0]?.valid_from ?? '') < from ? same[0] : undefined;
        if (same.length > 0 && !takeOver) env.refuse(409, 'price_item_overlap', 'This list already has a price for that item in those dates.');
        if (takeOver) takeOver.valid_to = dayBefore(from);
        const item: PriceListItem = {
          id: env.nextId('f'), price_list_id: listId, product_id: body.product_id, code: p.code, description: p.description, unit_id: body.unit_id, unit: p.unit,
          min_qty: min, price_minor: body.price_minor, valid_from: from, ...(body.valid_to ? { valid_to: body.valid_to } : {}), status: 'current',
        };
        items.push(item);
        return withStatus(item);
      }),

    endPriceListItem: (listId, itemId, validTo) =>
      gate(() => {
        const i = items.find((x) => x.id === itemId && x.price_list_id === listId);
        if (!i) return env.refuse(404, 'not_found', 'That could not be found.');
        if (i.valid_to) env.refuse(422, 'price_item_closed', 'This price has an end date already.');
        if (validTo < env.today() || validTo < (i.valid_from ?? '')) env.refuse(422, 'validation_failed', 'The last day cannot be before today or before the first day.');
        i.valid_to = validTo;
        return withStatus(i);
      }),

    listCustomerGroups: () => gate(() => groups.map(grouped)),

    createCustomerGroup: (body) =>
      gate(() => {
        if (groups.some((g) => g.name?.toLowerCase() === body.name.trim().toLowerCase())) env.refuse(409, 'duplicate_customer_group', 'A group with this name exists.');
        if (body.price_list_id && !active(body.price_list_id)) env.refuse(422, 'validation_failed', 'Choose a price list that exists and is switched on.');
        const g: CustomerGroup = { id: env.nextId('g'), name: body.name.trim(), ...(body.price_list_id ? { price_list_id: body.price_list_id } : {}), active: true, customer_count: 0, version: 1 };
        groups.push(g);
        return grouped(g);
      }),

    updateCustomerGroup: (id, version, body) =>
      gate(() => {
        const g = groups.find((x) => x.id === id);
        if (!g) return env.refuse(404, 'not_found', 'That could not be found.');
        stale(g, version);
        if (body.price_list_id && body.price_list_id !== g.price_list_id && !active(body.price_list_id)) env.refuse(422, 'validation_failed', 'Choose a price list that exists and is switched on.');
        Object.assign(g, { name: body.name.trim(), price_list_id: body.price_list_id, active: body.active, version: (g.version ?? 1) + 1 });
        return grouped(g);
      }),

    resolvePrices: (body) =>
      gate((): PriceResolution => {
        const customer = body.customer_id ? env.customer(body.customer_id) : undefined;
        const group = groups.find((g) => g.id === customer?.customer_group_id && g.active);
        const list = active(body.price_list_id) ?? active(customer?.price_list_id) ?? active(group?.price_list_id);
        const origin = body.price_list_id && list?.id === body.price_list_id ? 'request' : list?.id === customer?.price_list_id ? 'customer' : 'customer_group';
        const date = body.sale_date ?? env.today();
        const lines = body.lines.map((l): ResolvedLine => {
          const p = env.product(l.product_id);
          if (!p) return env.refuse(422, 'validation_failed', 'No such product.');
          const unit = l.unit_id && l.unit_id !== p.unitId ? l.unit_id : undefined;
          const hit = resolveFor(body.customer_id, body.price_list_id, l.product_id, unit, l.qty, date);
          const price = hit?.price ?? p.sellMinor;
          const refused = !hit && list?.strict === true;
          return {
            product_id: l.product_id, ...(unit ? { unit_id: unit } : {}), qty: three(l.qty), unit_price_minor: price, line_total_minor: lineTotal(price, milli(l.qty)),
            price_source: hit ? 'list' : 'retail', ...(hit ? { price_list_id: hit.list.id, break_min_qty: hit.minQty } : {}), ...(refused ? { problem: 'no_price_in_list' } : {}),
          };
        });
        return { ...(list ? { price_list_id: list.id, price_list: list.name, origin } : {}), lines };
      }),
  };
}
