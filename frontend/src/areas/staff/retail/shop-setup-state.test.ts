import { describe, expect, it } from 'vitest';
import { RetailError, type Category, type Unit, type UsualPackRequest } from '../../../api/retail';
import { offendingWords } from '../../../app/glossary';
import { SHOP_TYPES, shopTypeById } from './shop-starters';
import { applySetup, key, packNeeds, packText, planSetup, type SetupItem } from './shop-setup-state';

// Set up your shop (issue #625): each starting set adds what it lists, skips the names a shop has and never makes one twice, and
// running it again adds nothing new. The shop here is an in-memory one that refuses a name it has, as the server does.

/** A shop that starts with the default units of ADR-098 and keeps what is added, refusing a name it has, in any case. */
function fakeShop(start: { categories?: string[]; units?: string[] } = {}) {
  let n = 0;
  const categories: Category[] = (start.categories ?? []).map((name) => ({ id: `c${++n}`, name, active: true, version: 1 }));
  const units: Unit[] = (start.units ?? ['Piece', 'Pair', 'Set', 'Box', 'Pack', 'Kg', 'Litre', 'Metre']).map((name) => ({ id: `u${++n}`, name, active: true, version: 1 }));
  const clash = (rows: { name?: string }[], name: string, code: string) => {
    if (rows.some((r) => r.name?.toLowerCase() === name.toLowerCase())) throw new RetailError('exists', 409, code);
  };
  const usual = (p: UsualPackRequest): Unit['usual_pack'] => ({ size: Number(p.size).toFixed(3), of_unit_id: p.of_unit_id, of_unit: units.find((u) => u.id === p.of_unit_id)?.name ?? '', purpose: p.purpose });
  const api = {
    listUnits: () => Promise.resolve(units.map((u) => ({ ...u }))),
    createCategory: (name: string) => {
      clash(categories, name, 'duplicate_category');
      const c: Category = { id: `c${++n}`, name, active: true, version: 1, code_prefix: name.slice(0, 3).toUpperCase() };
      categories.push(c);
      return Promise.resolve(c);
    },
    createUnit: (name: string, pack?: UsualPackRequest) => {
      clash(units, name, 'duplicate_unit');
      const u: Unit = { id: `u${++n}`, name, active: true, version: 1, ...(pack ? { usual_pack: usual(pack) } : {}) };
      units.push(u);
      return Promise.resolve(u);
    },
    updateUnit: (id: string, version: number, body: { usual_pack?: UsualPackRequest }) => {
      const u = units.find((x) => x.id === id)!;
      if (u.version !== version) throw new RetailError('stale', 409, 'stale');
      if (body.usual_pack) u.usual_pack = usual(body.usual_pack);
      u.version = (u.version ?? 1) + 1;
      return Promise.resolve(u);
    },
  };
  return { api, categories, units };
}

const allTicked = (items: SetupItem[]) => new Set(items.map((i) => key(i.kind, i.name)));

async function run(shop: ReturnType<typeof fakeShop>, typeId: string, untick: string[] = []) {
  const type = shopTypeById(typeId)!;
  const plan = planSetup(type, [...shop.categories], [...shop.units]);
  const ticked = allTicked(plan);
  for (const k of untick) ticked.delete(k);
  return { plan, result: await applySetup(shop.api, plan, ticked, [...shop.units]) };
}

describe('the starting sets', () => {
  it('hold the eight kinds of shop the owner named, Something else with just the common units', () => {
    expect(SHOP_TYPES.map((t) => t.label)).toEqual([
      'Bedding and home', 'Electrical and electronics', 'Hardware and building', 'Phones and accessories', 'Clothing and shoes',
      'Supermarket and general', 'Pharmacy and cosmetics', 'Something else',
    ]);
    const other = shopTypeById('other')!;
    expect(other.categories).toEqual([]);
    expect(other.packs).toEqual([]);
    expect(other.units).toEqual(expect.arrayContaining(['Piece', 'Kg', 'Litre', 'Metre']));
  });

  it('give hardware a roll of 100 metres and a box of 50 pieces, as the issue asks', () => {
    const packs = shopTypeById('hardware')!.packs;
    expect(packs).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: 'Roll', size: 100, of: 'Metre' }),
      expect.objectContaining({ name: 'Box', size: 50, of: 'Piece' }),
    ]));
  });

  it('count every pack in a unit of its own kind of shop, with names that fit and plain words', () => {
    for (const t of SHOP_TYPES) {
      for (const p of t.packs) {
        expect(t.units, `${t.id}: ${p.name}`).toContain(p.of);
        expect(p.size, `${t.id}: ${p.name}`).toBeGreaterThanOrEqual(1);
        expect(t.units, `${t.id}: ${p.name} is a pack, not a unit too`).not.toContain(p.name);
      }
      for (const name of [...t.units, ...t.packs.map((p) => p.name)]) expect(name.length).toBeLessThanOrEqual(30);
      for (const name of t.categories) expect(name.length).toBeLessThanOrEqual(100);
      const words = [t.label, t.hint, ...t.categories, ...t.units, ...t.packs.map((p) => p.name)].join(' ');
      expect(offendingWords(words), t.id).toEqual([]);
      expect(words, t.id).not.toMatch(/[\u2013\u2014]| - /);
    }
  });
});

describe('Set up your shop', () => {
  it.each(SHOP_TYPES.map((t) => t.id))('adds what %s lists to a new shop, and a run again adds nothing new', async (id) => {
    const shop = fakeShop();
    const type = shopTypeById(id)!;
    const { result } = await run(shop, id);
    expect(result.error).toBeNull();
    for (const name of type.categories) expect(shop.categories.map((c) => c.name)).toContain(name);
    for (const name of type.units) expect(shop.units.map((u) => u.name)).toContain(name);
    for (const p of type.packs) {
      const unit = shop.units.find((u) => u.name === p.name)!;
      expect(unit.usual_pack, `${id}: ${p.name}`).toEqual({ size: p.size.toFixed(3), of_unit_id: shop.units.find((u) => u.name === p.of)!.id, of_unit: p.of, purpose: p.purpose });
    }
    // No name twice, in any case.
    const names = shop.units.map((u) => u.name!.toLowerCase());
    expect(new Set(names).size).toBe(names.length);

    const before = { categories: shop.categories.length, units: shop.units.length };
    const again = await run(shop, id);
    expect(again.plan.every((i) => i.status === 'there')).toBe(true);
    expect(again.result.added).toEqual([]);
    expect({ categories: shop.categories.length, units: shop.units.length }).toEqual(before);
  });

  it('skips names the shop has, in any case, and gives an existing unit with no usual size its size', async () => {
    const shop = fakeShop({ categories: ['CEMENT'], units: ['piece', 'Metre', 'Box'] });
    const { plan, result } = await run(shop, 'hardware');
    const status = (kind: string, name: string) => plan.find((i) => i.kind === kind && i.name === name)?.status;
    expect(status('category', 'Cement')).toBe('there');
    expect(status('unit', 'Piece')).toBe('there');
    expect(status('pack', 'Box')).toBe('size');
    expect(status('pack', 'Roll')).toBe('add');
    expect(result.added.map((a) => a.name)).not.toContain('Cement');
    expect(shop.categories.filter((c) => c.name!.toLowerCase() === 'cement')).toHaveLength(1);
    expect(shop.units.filter((u) => u.name!.toLowerCase() === 'box')).toHaveLength(1);
    expect(shop.units.find((u) => u.name === 'Box')!.usual_pack).toMatchObject({ size: '50.000', of_unit: 'piece' });
    expect(result.added.find((a) => a.name === 'Box')?.detail).toBe('50 piece, for buying and selling');
  });

  it('keeps a unit that has a usual size as it is', async () => {
    const shop = fakeShop({ units: ['Piece', 'Metre'] });
    const metre = shop.units.find((u) => u.name === 'Metre')!;
    shop.units.push({ id: 'u-roll', name: 'Roll', active: true, version: 3, usual_pack: { size: '25.000', of_unit_id: metre.id, of_unit: 'Metre', purpose: 'sell' } });
    const { result } = await run(shop, 'hardware');
    expect(result.added.map((a) => a.name)).not.toContain('Roll');
    expect(shop.units.find((u) => u.name === 'Roll')).toMatchObject({ version: 3, usual_pack: { size: '25.000' } });
  });

  it('adds only what is ticked, and leaves out a pack whose smaller unit is neither there nor ticked', async () => {
    const shop = fakeShop({ units: [] });
    const type = shopTypeById('hardware')!;
    const plan = planSetup(type, [], []);
    const ticked = allTicked(plan);
    ticked.delete(key('category', 'Paint'));
    ticked.delete(key('unit', 'Metre'));
    const roll = plan.find((i) => i.name === 'Roll')!;
    expect(packNeeds(roll, ticked, [])).toBe('Tick Metre too, or this pack is left out.');
    const result = await applySetup(shop.api, plan, ticked, []);
    expect(shop.categories.map((c) => c.name)).not.toContain('Paint');
    expect(shop.units.map((u) => u.name)).not.toContain('Metre');
    expect(shop.units.map((u) => u.name)).not.toContain('Roll');
    expect(result.skipped).toContainEqual({ name: 'Roll', why: 'Metre is not in your shop, so this pack was left out.' });
    expect(shop.units.find((u) => u.name === 'Box')?.usual_pack?.of_unit).toBe('Piece');
  });

  it('says a pack whose smaller unit is switched off is left out, and why, rather than asking to tick a unit already there', async () => {
    const shop = fakeShop();
    shop.units.find((u) => u.name === 'Metre')!.active = false;
    const { plan, result } = await run(shop, 'hardware');
    const roll = plan.find((i) => i.name === 'Roll')!;
    expect(plan.find((i) => i.kind === 'unit' && i.name === 'Metre')?.status).toBe('there');
    expect(packNeeds(roll, allTicked(plan), shop.units)).toBe('Metre is switched off. Switch it on in Units, or this pack is left out.');
    expect(result.skipped).toEqual([{ name: 'Roll', why: 'Metre is switched off. Switch it on in Units, or this pack is left out.' }]);
    expect(shop.units.map((u) => u.name)).not.toContain('Roll');
  });

  it('takes a changed size and purpose, and never sends a price', async () => {
    const shop = fakeShop();
    const sent: unknown[] = [];
    const api = { ...shop.api, createUnit: (name: string, pack?: UsualPackRequest) => { sent.push(pack); return shop.api.createUnit(name, pack); } };
    const plan = planSetup(shopTypeById('supermarket')!, [], [...shop.units]).map((i) => (i.name === 'Crate' ? { ...i, size: 12, purpose: 'sell' as const } : i));
    await applySetup(api, plan, allTicked(plan), [...shop.units]);
    expect(shop.units.find((u) => u.name === 'Crate')!.usual_pack).toMatchObject({ size: '12.000', purpose: 'sell' });
    for (const body of sent.filter(Boolean)) expect(Object.keys(body as object).sort()).toEqual(['of_unit_id', 'purpose', 'size']);
  });

  it('counts a name another phone added meanwhile as there, and stops at a real failure saying what was added', async () => {
    const shop = fakeShop();
    const plan = planSetup(shopTypeById('phones')!, [], [...shop.units]);
    await shop.api.createCategory('Chargers');
    const failing = { ...shop.api, createCategory: (name: string) => (name === 'Phone covers' ? Promise.reject(new Error('offline')) : shop.api.createCategory(name)) };
    const result = await applySetup(failing, plan, allTicked(plan), [...shop.units]);
    expect(result.skipped).toContainEqual({ name: 'Chargers', why: 'Your shop has it already.' });
    expect(result.added.map((a) => a.name)).toEqual(['Phones', 'Earphones']);
    expect(result.error).toBeInstanceOf(Error);
    const again = await run(shop, 'phones');
    expect(again.result.error).toBeNull();
    expect(again.result.added.map((a) => a.name)).toEqual(['Phone covers', 'Screen protectors', 'Memory cards', 'Box', 'Carton']);
  });

  it('reads the units back when one was added meanwhile, so its pack is added, not left out as missing (issue #637)', async () => {
    const shop = fakeShop({ units: ['Piece', 'Box'] });
    const plan = planSetup(shopTypeById('hardware')!, [], [...shop.units]);
    // Another phone adds Metre after the plan was read.
    await shop.api.createUnit('Metre');
    const result = await applySetup(shop.api, plan, allTicked(plan), [shop.units[0]!, shop.units[1]!]);
    expect(result.error).toBeNull();
    expect(result.skipped).toContainEqual({ name: 'Metre', why: 'Your shop has it already.' });
    expect(result.skipped.map((s) => s.name)).not.toContain('Roll');
    expect(shop.units.find((u) => u.name === 'Roll')?.usual_pack?.of_unit).toBe('Metre');
  });

  it('leaves out a pack of a unit that is a pack itself, one level only (issue #637)', async () => {
    const shop = fakeShop();
    const metre = shop.units.find((u) => u.name === 'Metre')!;
    metre.usual_pack = { size: '2.000', of_unit_id: shop.units[0]!.id, of_unit: 'Piece', purpose: 'both' };
    const { result } = await run(shop, 'hardware');
    expect(result.skipped).toContainEqual({ name: 'Roll', why: 'Metre is itself a pack, so this pack was left out.' });
    expect(shop.units.map((u) => u.name)).not.toContain('Roll');
  });

  it("gives a pack's unit another phone added meanwhile, with no usual size, this one (review of #637)", async () => {
    const shop = fakeShop();
    const plan = planSetup(shopTypeById('hardware')!, [], [...shop.units]);
    await shop.api.createUnit('Roll');
    const result = await applySetup(shop.api, plan, allTicked(plan), [...shop.units].filter((u) => u.name !== 'Roll'));
    expect(result.error).toBeNull();
    expect(result.skipped.map((s) => s.name)).not.toContain('Roll');
    expect(shop.units.filter((u) => u.name === 'Roll')).toHaveLength(1);
    expect(shop.units.find((u) => u.name === 'Roll')?.usual_pack?.of_unit).toBe('Metre');
  });

  it('holds no pack in a starting set whose smaller unit is a pack of that set, one level only (ADR-100 note)', () => {
    for (const type of SHOP_TYPES) {
      const packNames = new Set(type.packs.map((p) => p.name.toLowerCase()));
      for (const p of type.packs) expect(packNames.has(p.of.toLowerCase()), `${type.id}: ${p.name} of ${p.of}`).toBe(false);
    }
  });

  it('says a pack in plain words', () => {
    expect(packText('100.000', 'Metre', 'both')).toBe('100 Metre, for buying and selling');
    expect(packText(50, 'Piece', 'buy')).toBe('50 Piece, for buying');
    expect(packText(12, 'Piece', 'sell')).toBe('12 Piece, for selling');
  });
});
