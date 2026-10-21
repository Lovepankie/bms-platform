import { describe, expect, it } from 'vitest';
import { createMockRetail } from './retail-mock';

// The mock's items with sizes and colours follow the server's rules in small (issue #384): every combination made once, a
// variant named after its item, an existing item given sizes keeping its id and stock, and a name that follows the item.
// Fabricated data.

async function romper() {
  const api = createMockRetail();
  const [category] = await api.listCategories();
  const [unit] = await api.listUnits();
  const family = await api.createFamily({
    name: 'Test romper', base_code: 'ROMP', category_id: category?.id ?? '', unit_id: unit?.id ?? '', cost_minor: 4000, sell_minor: 9000,
    sizes: ['0-3m', '3-6m'], colours: ['Blue', 'Pink'],
  });
  return { api, family };
}

describe('items with sizes and colours in the mock', () => {
  it('makes every combination with its own code, named after the item', async () => {
    const { family } = await romper();
    expect(family.variants?.map((v) => v.code)).toEqual(['ROMP-03M-BLUE', 'ROMP-03M-PINK', 'ROMP-36M-BLUE', 'ROMP-36M-PINK']);
    expect(family.variants?.[0]?.description).toBe('Test romper (0-3m, Blue)');
    expect(family.variants?.[0]?.variant).toMatchObject({ family_id: family.id, size: '0-3m', colour: 'Blue', position: 0 });
    expect(family.sizes).toEqual(['0-3m', '3-6m']);
  });

  it('skips a combination the item has and refuses when nothing is new', async () => {
    const { api, family } = await romper();
    const more = await api.addVariants(family.id ?? '', { sizes: ['0-3m', '6-12m'], colours: ['Blue'] });
    expect(more.variants?.map((v) => v.code)).toContain('ROMP-612M-BLUE');
    expect(more.variants).toHaveLength(5);
    await expect(api.addVariants(family.id ?? '', { sizes: ['0-3m'], colours: ['Blue'] })).rejects.toMatchObject({ status: 422, code: 'nothing_to_add' });
  });

  it('renames every variant with its item and refuses a name change on one variant', async () => {
    const { api, family } = await romper();
    const renamed = await api.updateFamily(family.id ?? '', family.version ?? 1, { name: 'Test sleepsuit' });
    expect(renamed.variants?.[0]?.description).toBe('Test sleepsuit (0-3m, Blue)');
    const v = renamed.variants?.[0];
    await expect(api.updateProduct(v?.id ?? '', v?.version ?? 1, { description: 'Other' })).rejects.toMatchObject({ code: 'variant_name_follows_item' });
    const pink = renamed.variants?.[1];
    await expect(api.updateProduct(v?.id ?? '', v?.version ?? 1, { variant_colour: pink?.variant?.colour ?? '' })).rejects.toMatchObject({ code: 'duplicate_variant' });
  });

  it('gives an existing item sizes and keeps its id, code and stock', async () => {
    const api = createMockRetail();
    const [item] = await api.listProducts({});
    const before = await api.getProduct(item?.id ?? '');
    const family = await api.makeFamily(item?.id ?? '', { size: 'Small', sizes: ['Small', 'Large'] });
    const kept = family.variants?.[0];
    expect(kept?.id).toBe(item?.id);
    expect(kept?.code).toBe(before.code);
    expect(kept?.stock_on_hand).toBe(before.stock_on_hand);
    expect(family.variants?.[1]?.sell_minor).toBe(before.sell_minor);
    await expect(api.makeFamily(item?.id ?? '', { size: 'Small' })).rejects.toMatchObject({ code: 'already_has_variants' });
  });

  it('joins items kept apart as the sizes of one item, each keeping its id and stock', async () => {
    const api = createMockRetail();
    const all = await api.listProducts({});
    const a = all[0];
    const b = all.find((p) => p.unit === a?.unit && p.id !== a?.id);
    const before = await api.getProduct(a?.id ?? '');
    const family = await api.joinFamily(null, { name: 'Test joined', items: [{ product_id: a?.id ?? '', size: 'Small' }, { product_id: b?.id ?? '', size: 'Large' }] });
    expect(family.variants?.map((v) => v.id)).toEqual([a?.id, b?.id]);
    expect(family.variants?.[0]?.description).toBe('Test joined (Small)');
    expect(family.variants?.[0]?.stock_on_hand).toBe(before.stock_on_hand);
    await expect(api.joinFamily(null, { name: 'Again', items: [{ product_id: a?.id ?? '', size: 'S' }] })).rejects.toMatchObject({ code: 'already_has_variants' });
  });

  it('joins into an item that exists, refusing a name, code or category that would name it anew', async () => {
    const api = createMockRetail();
    const all = await api.listProducts({});
    const a = all[0];
    const b = all.find((p) => p.unit === a?.unit && p.id !== a?.id);
    const family = await api.joinFamily(null, { name: 'Test joined', items: [{ product_id: a?.id ?? '', size: 'Small' }] });
    await expect(api.joinFamily(family.id ?? '', { name: 'Renamed', items: [{ product_id: b?.id ?? '', size: 'Large' }] })).rejects.toMatchObject({ status: 422 });
    await expect(api.joinFamily(family.id ?? '', { duplicate_reason: 'Test reason here', items: [{ product_id: b?.id ?? '', size: 'Large' }] })).rejects.toMatchObject({ status: 422 });
    const joined = await api.joinFamily(family.id ?? '', { items: [{ product_id: b?.id ?? '', size: 'Large' }] });
    expect(joined.name).toBe('Test joined');
    expect(joined.variants?.map((v) => v.id)).toEqual([a?.id, b?.id]);
  });
});
