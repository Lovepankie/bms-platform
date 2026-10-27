import { RetailError, type Category, type RetailApi, type Unit } from '../../../api/retail';
import type { Purpose } from './catalogue-packs';
import type { ShopType } from './shop-starters';

// Set up your shop (issue #625): what a starting set would add to this shop, and adding it through the routes that add one
// category or one unit, so no new endpoint is needed. A name the shop has already, in any case and switched on or off, is
// skipped and never made twice, so running it again adds nothing new. A pack whose name is a unit the shop has without a usual
// size gets the size and purpose set on that unit (the default units of ADR-098 include Box and Pack); a unit that has one keeps it.

export type ItemKind = 'category' | 'unit' | 'pack';

export interface SetupItem {
  kind: ItemKind;
  name: string;
  /** add: not in the shop. size: a unit the shop has, given its usual pack size. there: in the shop, skipped. */
  status: 'add' | 'size' | 'there';
  /** Packs only: the usual size and the smaller unit it counts, and what the pack is for. */
  size?: number;
  of?: string;
  purpose?: Purpose;
}

export const key = (kind: ItemKind, name: string) => `${kind}:${name.toLowerCase()}`;
const same = (a: string | undefined, b: string) => (a ?? '').trim().toLowerCase() === b.trim().toLowerCase();

/** The starting set against what the shop has: each category, unit and pack, with what adding it would do. */
export function planSetup(type: ShopType, categories: Category[], units: Unit[]): SetupItem[] {
  const unit = (name: string) => units.find((u) => same(u.name, name));
  return [
    ...type.categories.map((name): SetupItem => ({ kind: 'category', name, status: categories.some((c) => same(c.name, name)) ? 'there' : 'add' })),
    ...type.units.map((name): SetupItem => ({ kind: 'unit', name, status: unit(name) ? 'there' : 'add' })),
    ...type.packs.map((p): SetupItem => {
      const had = unit(p.name);
      return { kind: 'pack', name: p.name, size: p.size, of: p.of, purpose: p.purpose, status: !had ? 'add' : had.usual_pack ? 'there' : 'size' };
    }),
  ];
}

/** A pack in plain words: "100 Metre, for buying and selling". */
export function packText(size: number | string, of: string, purpose: Purpose | string | undefined): string {
  const what = purpose === 'buy' ? 'for buying' : purpose === 'sell' ? 'for selling' : 'for buying and selling';
  return `${Number(size)} ${of}, ${what}`;
}

/** Why a pack whose smaller unit the shop has switched off is left out; it is not tickable, since the shop has it. */
export const switchedOffText = (of: string) => `${of} is switched off. Switch it on in Units, or this pack is left out.`;

/**
 * Why a pack cannot be added as ticked, or null when it can: its smaller unit is switched off in the shop (review of #628: it
 * shows as already there, so ticking it is no way out), or it is neither in the shop nor ticked. The screen says so on the
 * pack's row and Add these passes it over, so the owner ticks the unit, switches it on, or leaves the pack.
 */
export function packNeeds(item: SetupItem, ticked: Set<string>, units: Unit[]): string | null {
  if (item.kind !== 'pack' || !item.of) return null;
  const had = units.find((u) => same(u.name, item.of!));
  if (had) return had.active === false ? switchedOffText(item.of) : null;
  return ticked.has(key('unit', item.of)) ? null : `Tick ${item.of} too, or this pack is left out.`;
}

export interface SetupResult {
  added: { kind: ItemKind; name: string; detail?: string }[];
  /** Ticked but not added, with why: already there when added (another phone added it first), or its smaller unit was missing. */
  skipped: { name: string; why: string }[];
  error: unknown;
}

export const ADDED_MEANWHILE = 'Your shop has it already.';

/**
 * Adds what is ticked, one at a time: categories, then units, then packs, so a pack finds the unit it counts. It stops at the first
 * failure and says what was added before it; running it again adds only what is still missing.
 */
export async function applySetup(api: Pick<RetailApi, 'createCategory' | 'createUnit' | 'updateUnit'>, items: SetupItem[], ticked: Set<string>, units: Unit[]): Promise<SetupResult> {
  const result: SetupResult = { added: [], skipped: [], error: null };
  const known = [...units];
  const chosen = items.filter((i) => i.status !== 'there' && ticked.has(key(i.kind, i.name)));
  const order: ItemKind[] = ['category', 'unit', 'pack'];
  chosen.sort((a, b) => order.indexOf(a.kind) - order.indexOf(b.kind));
  for (const item of chosen) {
    try {
      if (item.kind === 'category') {
        const made = await api.createCategory(item.name);
        result.added.push({ kind: 'category', name: made.name ?? item.name, ...(made.code_prefix ? { detail: `Item codes start with ${made.code_prefix}` } : {}) });
        continue;
      }
      if (item.kind === 'unit') {
        known.push(await api.createUnit(item.name));
        result.added.push({ kind: 'unit', name: item.name });
        continue;
      }
      const of = known.find((u) => same(u.name, item.of ?? ''));
      if (!of?.id || of.active === false) {
        result.skipped.push({ name: item.name, why: of ? switchedOffText(item.of ?? '') : `${item.of} is not in your shop, so this pack was left out.` });
        continue;
      }
      const pack = { size: String(item.size), of_unit_id: of.id, purpose: item.purpose ?? 'both' };
      const had = known.find((u) => same(u.name, item.name));
      if (had?.id && item.status === 'size') await api.updateUnit(had.id, had.version ?? 1, { usual_pack: pack });
      else await api.createUnit(item.name, pack);
      result.added.push({ kind: 'pack', name: item.name, detail: packText(pack.size, of.name ?? '', pack.purpose) });
    } catch (error) {
      // Added meanwhile, on another phone: it is there, which is what was asked.
      if (error instanceof RetailError && (error.code === 'duplicate_category' || error.code === 'duplicate_unit')) {
        result.skipped.push({ name: item.name, why: ADDED_MEANWHILE });
        continue;
      }
      result.error = error;
      return result;
    }
  }
  return result;
}
