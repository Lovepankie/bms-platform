import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_CHAIN, addLevel, chainUser, dropLevel, droppedLevels, effectiveChain, moveLevel, offersSales, overLines, parseChain, pathKey, readChain, selectGroup, shutGroup, toggleGroup, trailOf, usableChain, usableLevels, wholeSalesNote, writeChain,
  type ChainLevel,
} from './sales-chain-state';

// The pure rules of the grouping chain (issue #341): ordering, dropping, remembering, what a group selects, what is open.

const chain = (...levels: ChainLevel[]) => levels;

describe('changing the chain', () => {
  it('starts with the order of the old app', () => {
    expect(DEFAULT_CHAIN).toEqual(['branch', 'year', 'month', 'seller', 'day', 'category', 'item']);
  });

  it('moves a level one place, and stays put at either end', () => {
    expect(moveLevel(DEFAULT_CHAIN, 'seller', -1)).toEqual(['branch', 'year', 'seller', 'month', 'day', 'category', 'item']);
    expect(moveLevel(DEFAULT_CHAIN, 'seller', 1)).toEqual(['branch', 'year', 'month', 'day', 'seller', 'category', 'item']);
    expect(moveLevel(DEFAULT_CHAIN, 'branch', -1)).toEqual([...DEFAULT_CHAIN]);
    expect(moveLevel(DEFAULT_CHAIN, 'item', 1)).toEqual([...DEFAULT_CHAIN]);
    expect(moveLevel(chain('year'), 'day', 1)).toEqual(['year']);
  });

  it('drops a level but never the last one', () => {
    expect(dropLevel(DEFAULT_CHAIN, 'month')).toEqual(['branch', 'year', 'seller', 'day', 'category', 'item']);
    expect(dropLevel(chain('day'), 'day')).toEqual(['day']);
  });

  it('puts a dropped level back where the usual order has it, or at the end', () => {
    expect(addLevel(chain('branch', 'day'), 'seller')).toEqual(['branch', 'seller', 'day']);
    expect(addLevel(chain('day', 'category'), 'branch')).toEqual(['branch', 'day', 'category']);
    expect(addLevel(chain('year'), 'item')).toEqual(['year', 'item']);
    expect(addLevel(chain('year'), 'year')).toEqual(['year']);
    expect(droppedLevels(chain('year', 'day'))).toEqual(['branch', 'month', 'seller', 'category', 'item']);
  });

  it('leaves the shop out when one shop is chosen, unless that would leave nothing', () => {
    expect(effectiveChain(DEFAULT_CHAIN, true)).toEqual(['year', 'month', 'seller', 'day', 'category', 'item']);
    expect(effectiveChain(DEFAULT_CHAIN, false)).toEqual([...DEFAULT_CHAIN]);
    expect(effectiveChain(chain('branch'), true)).toEqual(['branch']);
  });
});

describe('who may use which level', () => {
  it('keeps the salesperson level for those who may read profit', () => {
    expect(usableLevels(true)).toEqual([...DEFAULT_CHAIN]);
    expect(usableLevels(false)).toEqual(['branch', 'year', 'month', 'day', 'category', 'item']);
    expect(usableChain(DEFAULT_CHAIN, false)).toEqual(['branch', 'year', 'month', 'day', 'category', 'item']);
    expect(usableChain(chain('seller', 'day'), false)).toEqual(['day']);
    expect(usableChain(chain('seller'), false)).toEqual(usableLevels(false));
    expect(usableChain(chain('seller', 'day'), true)).toEqual(['seller', 'day']);
    expect(droppedLevels(chain('year'), usableLevels(false))).not.toContain('seller');
  });

  it('keys the remembered chain by the user id, with a neutral fallback', () => {
    expect(chainUser({ user_id: 'abc' })).toBe('abc');
    expect(chainUser({})).toBe('staff');
  });
});

describe('where the sales can be reached', () => {
  it('offers them on a day, on every level when there is no day, and above a last item', () => {
    expect(offersSales(DEFAULT_CHAIN, 4)).toBe(true);
    expect(offersSales(DEFAULT_CHAIN, 3)).toBe(false);
    expect(offersSales(DEFAULT_CHAIN, 5)).toBe(true);
    expect(offersSales(chain('branch', 'year', 'month', 'seller', 'category', 'item'), 0)).toBe(true);
    expect(offersSales(chain('year', 'day', 'category'), 0)).toBe(false);
    expect(offersSales(chain('year', 'category', 'item'), 1)).toBe(true);
    expect(offersSales(chain('year', 'category', 'item'), 0)).toBe(true);
    expect(offersSales(chain('year', 'day', 'category', 'item'), 0)).toBe(false);
  });

  it('knows when figures are summed over lines and what to say about the sales under them', () => {
    expect(overLines('year', {})).toBe(false);
    expect(overLines('category', {})).toBe(true);
    expect(overLines('day', { categoryId: 'c' })).toBe(true);
    expect(overLines('day', { itemId: 'p' })).toBe(true);
    expect(wholeSalesNote({})).toBeNull();
    expect(wholeSalesNote({ categoryId: 'c' })).toBe('Whole sales that include this category.');
    expect(wholeSalesNote({ categoryId: 'c', itemId: 'p' })).toBe('Whole sales that include this item.');
  });
});

describe('remembering the chain per user', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reads only a non-empty list of known levels, each once', () => {
    expect(parseChain('["year","day"]')).toEqual(['year', 'day']);
    for (const bad of [null, '', 'year', '[]', '["week"]', '["year","year"]', '{"a":1}', '[1]', '["year", null]']) expect(parseChain(bad)).toBeNull();
  });

  it('keeps one chain per user and list', () => {
    const store = new Map<string, string>();
    vi.stubGlobal('localStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) });
    writeChain('ann@example.test', 'all', ['seller', 'day']);
    writeChain('ben@example.test', 'all', ['year']);
    expect(readChain('ann@example.test', 'all')).toEqual(['seller', 'day']);
    expect(readChain('ben@example.test', 'all')).toEqual(['year']);
    expect(readChain('ann@example.test', 'credit')).toEqual([...DEFAULT_CHAIN]);
  });

  it('falls back to the usual order when storage holds rubbish or is blocked', () => {
    vi.stubGlobal('localStorage', { getItem: () => '["nonsense"]', setItem: () => undefined });
    expect(readChain('ann@example.test', 'all')).toEqual([...DEFAULT_CHAIN]);
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } });
    expect(readChain('ann@example.test', 'all')).toEqual([...DEFAULT_CHAIN]);
    expect(() => writeChain('ann@example.test', 'all', ['year'])).not.toThrow();
  });
});

describe('what opening a group selects for the levels below', () => {
  it('passes a shop, a person, a category and an item as their own filter', () => {
    expect(selectGroup({}, 'branch', { key: 'b1' })).toEqual({ branchId: 'b1' });
    expect(selectGroup({ branchId: 'b1' }, 'seller', { key: 'u1' })).toEqual({ branchId: 'b1', sellerId: 'u1' });
    expect(selectGroup({}, 'category', { key: 'c1' })).toEqual({ categoryId: 'c1' });
    expect(selectGroup({}, 'item', { key: 'p1' })).toEqual({ itemId: 'p1' });
  });

  it('narrows the days to the period, never wider than what the screen asked for', () => {
    expect(selectGroup({}, 'year', { key: '2026', from: '2026-01-01', to: '2026-12-31' })).toEqual({ from: '2026-01-01', to: '2026-12-31' });
    expect(selectGroup({ from: '2026-03-10', to: '2026-10-19' }, 'year', { from: '2026-01-01', to: '2026-12-31' })).toEqual({ from: '2026-03-10', to: '2026-10-19' });
    expect(selectGroup({ from: '2026-01-01', to: '2026-12-31', sellerId: 'u1' }, 'month', { from: '2026-10-01', to: '2026-10-31' }))
      .toEqual({ from: '2026-10-01', to: '2026-10-31', sellerId: 'u1' });
  });
});

describe('the open groups', () => {
  const path = pathKey(pathKey(pathKey('', 'branch', 'b1'), 'year', '2026'), 'month', '2026-10');

  it('builds a path key from the root down, so the same day under two people is two groups', () => {
    expect(path).toBe('/branch:b1/year:2026/month:2026-10');
    expect(pathKey('/seller:u1', 'day', '2026-10-19')).not.toBe(pathKey('/seller:u2', 'day', '2026-10-19'));
  });

  it('opens a group, and shuts it with everything inside it, leaving its neighbours', () => {
    let open = toggleGroup(new Map(), '/branch:b1', 'Gayaza');
    open = toggleGroup(open, '/branch:b1/year:2026', '2026');
    open = toggleGroup(open, '/branch:b10', 'Other shop');
    expect([...open.keys()]).toEqual(['/branch:b1', '/branch:b1/year:2026', '/branch:b10']);
    expect([...toggleGroup(open, '/branch:b1', 'Gayaza').keys()]).toEqual(['/branch:b10']);
  });

  it('shuts from a crumb only when the group is open', () => {
    const open = toggleGroup(new Map(), '/branch:b1', 'Gayaza');
    expect([...shutGroup(open, '/branch:b1').keys()]).toEqual([]);
    expect([...shutGroup(open, '/branch:b2').keys()]).toEqual(['/branch:b1']);
  });

  it('reads the breadcrumb from the group opened last', () => {
    expect(trailOf(new Map())).toEqual([]);
    let open = toggleGroup(new Map(), '/branch:b1', 'Gayaza');
    open = toggleGroup(open, '/branch:b1/year:2026', '2026');
    open = toggleGroup(open, path, 'October 2026');
    expect(trailOf(open)).toEqual([
      { key: '/branch:b1', label: 'Gayaza' }, { key: '/branch:b1/year:2026', label: '2026' }, { key: path, label: 'October 2026' },
    ]);
  });
});
