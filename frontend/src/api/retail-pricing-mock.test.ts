import { describe, expect, it } from 'vitest';
import { businessToday, daysBefore } from './retail';
import { createMockRetail, mockMe } from './retail-mock';

// The mock's price lists follow the server's rules in small (ADR-033, issue #195): off means 404, the largest break not
// above the quantity, a new open ended price takes over, no overlap, and a sale takes the resolved price. Fabricated data.

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const today = businessToday();

async function trade() {
  const api = createMockRetail();
  const read = await api.capabilities();
  await api.changeCapabilities({ price_lists: true }, read.version);
  const [bulb] = await api.listProducts({ query: 'P003' });
  const unitId = (await api.getProduct(bulb?.id ?? '')).unit_id ?? '';
  return { api, productId: bulb?.id ?? '', unitId };
}

describe('price lists in the mock', () => {
  it('answers 404 capability_not_enabled while the capability is off', async () => {
    const api = createMockRetail();
    await expect(api.listPriceLists()).rejects.toMatchObject({ status: 404, code: 'capability_not_enabled' });
    await expect(api.listCustomerGroups()).rejects.toMatchObject({ status: 404 });
  });

  it('takes the largest break not above the quantity, among the prices in force', async () => {
    const { api, productId, unitId } = await trade();
    const l = await api.createPriceList({ name: 'Wholesale' });
    await api.addPriceListItem(l.id ?? '', { product_id: productId, unit_id: unitId, min_qty: '0', price_minor: 5000 });
    await api.addPriceListItem(l.id ?? '', { product_id: productId, unit_id: unitId, min_qty: '10', price_minor: 4500 });
    const group = await api.createCustomerGroup({ name: 'Contractors', price_list_id: l.id });
    const buyer = (await api.listCustomers())[0];
    await api.setCustomerPricing(buyer?.id ?? '', buyer?.version ?? 1, { customer_group_id: group.id });
    const resolved = await api.resolvePrices({
      customer_id: buyer?.id, branch_id: branch,
      lines: [{ product_id: productId, qty: '9.999' }, { product_id: productId, qty: '10' }, { product_id: productId, qty: '12.5' }],
    });
    expect(resolved.origin).toBe('customer_group');
    expect(resolved.lines?.map((x) => x.unit_price_minor)).toEqual([5000, 4500, 4500]);
    expect(resolved.lines?.map((x) => x.line_total_minor)).toEqual([49995, 45000, 56250]);
    expect(resolved.lines?.[1]?.break_min_qty).toBe('10.000');
  });

  it('refuses an overlap, lets an open ended price take over, and refuses a start in the past', async () => {
    const { api, productId, unitId } = await trade();
    const l = await api.createPriceList({ name: 'Wholesale' });
    const id = l.id ?? '';
    await api.addPriceListItem(id, { product_id: productId, unit_id: unitId, price_minor: 5000 });
    await expect(api.addPriceListItem(id, { product_id: productId, unit_id: unitId, price_minor: 4900 })).rejects.toMatchObject({ status: 409, code: 'price_item_overlap' });
    await expect(api.addPriceListItem(id, { product_id: productId, unit_id: unitId, price_minor: 4900, valid_from: daysBefore(today, 1) })).rejects.toMatchObject({ status: 422 });
    const next = daysBefore(today, -3);
    await api.addPriceListItem(id, { product_id: productId, unit_id: unitId, price_minor: 4800, valid_from: next });
    const all = (await api.listPriceListItems(id, { includeEnded: true })).items ?? [];
    expect(all).toHaveLength(2);
    expect(all.find((i) => i.price_minor === 5000)?.valid_to).toBe(daysBefore(today, -2));
    expect(all.find((i) => i.price_minor === 4800)?.status).toBe('scheduled');
  });

  it('makes a sale at the list price and says so on the line, and the retail price without a list', async () => {
    const { api, productId, unitId } = await trade();
    const l = await api.createPriceList({ name: 'Wholesale' });
    await api.addPriceListItem(l.id ?? '', { product_id: productId, unit_id: unitId, price_minor: 5000 });
    const buyer = (await api.listCustomers())[0];
    await api.setCustomerPricing(buyer?.id ?? '', buyer?.version ?? 1, { price_list_id: l.id });
    const sale = await api.createSale({ branch_id: branch, payment_method: 'cash', customer_id: buyer?.id, lines: [{ product_id: productId, qty: '2' }] }, 'k-list');
    expect(sale.lines?.[0]?.unit_price_minor).toBe(5000);
    expect(sale.lines?.[0]?.price).toMatchObject({ source: 'list', price_list: 'Wholesale', resolved_unit_price_minor: 5000 });
    const retail = await api.createSale({ branch_id: branch, payment_method: 'cash', lines: [{ product_id: productId, qty: '1' }] }, 'k-retail');
    expect(retail.lines?.[0]?.price).toMatchObject({ source: 'retail', resolved_unit_price_minor: 6000 });
    const changed = await api.createSale({ branch_id: branch, payment_method: 'cash', lines: [{ product_id: productId, qty: '1', unit_price_minor: 5500, price_override_reason: 'Loyal buyer' }] }, 'k-over');
    expect(changed.lines?.[0]?.price).toMatchObject({ source: 'override', override_reason: 'Loyal buyer' });
  });

  it('leaves a sale exactly as before while the capability is off', async () => {
    const api = createMockRetail();
    const [bulb] = await api.listProducts({ query: 'P003' });
    const sale = await api.createSale({ branch_id: branch, payment_method: 'cash', lines: [{ product_id: bulb?.id ?? '', qty: '1' }] }, 'k-off');
    expect(sale.lines?.[0]).not.toHaveProperty('price');
  });
});
