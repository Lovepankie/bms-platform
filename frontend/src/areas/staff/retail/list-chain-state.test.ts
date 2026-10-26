// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { PURCHASE_ORDER, STOCK_ORDER, addTo, dropFrom, moveIn, offersRestocks, parseOf, readChainOf, readView, selectIn, withoutShop, wholeRestocksNote, writeChainOf, writeView } from './list-chain-state';

// The pure rules of the grouped stock and restocks lists (issue #350). Fabricated ids.

beforeEach(() => localStorage.clear());

describe('the chain', () => {
  it('moves a level, never past an end', () => {
    expect(moveIn(['branch', 'year', 'month'], 'year', 1)).toEqual(['branch', 'month', 'year']);
    expect(moveIn(['branch', 'year'], 'branch', -1)).toEqual(['branch', 'year']);
  });

  it('keeps at least one level, and puts a dropped level back in the usual place', () => {
    expect(dropFrom(['branch'], 'branch')).toEqual(['branch']);
    expect(dropFrom(['branch', 'year'], 'branch')).toEqual(['year']);
    expect(addTo(['branch', 'month'], 'year', PURCHASE_ORDER)).toEqual(['branch', 'year', 'month']);
    expect(addTo(['branch'], 'category', STOCK_ORDER)).toEqual(['branch', 'category']);
  });

  it('reads only a whole, known, non-repeating chain', () => {
    expect(parseOf('["category","branch"]', STOCK_ORDER)).toEqual(['category', 'branch']);
    expect(parseOf('["category","category"]', STOCK_ORDER)).toBeNull();
    expect(parseOf('["seller"]', STOCK_ORDER)).toBeNull();
    expect(parseOf('[]', STOCK_ORDER)).toBeNull();
    expect(parseOf('nonsense', STOCK_ORDER)).toBeNull();
    expect(parseOf(null, STOCK_ORDER)).toBeNull();
  });

  it('leaves the shop out when one shop is chosen at the top, unless it is all there is', () => {
    expect(withoutShop(['branch', 'category'], true)).toEqual(['category']);
    expect(withoutShop(['branch', 'category'], false)).toEqual(['branch', 'category']);
    expect(withoutShop(['branch'], true)).toEqual(['branch']);
  });
});

describe('what is remembered', () => {
  it('is the flat list and the usual order until the person chooses, per user and list', () => {
    expect(readView('u1', 'stock')).toBe('flat');
    expect(readChainOf('u1', 'stock', STOCK_ORDER)).toEqual(['branch', 'category']);
    writeView('u1', 'stock', 'grouped');
    writeChainOf('u1', 'stock', ['category', 'branch']);
    expect(readView('u1', 'stock')).toBe('grouped');
    expect(readView('u2', 'stock')).toBe('flat');
    expect(readView('u1', 'restocks')).toBe('flat');
    expect(readChainOf('u1', 'stock', STOCK_ORDER)).toEqual(['category', 'branch']);
  });

  it('survives blocked storage', () => {
    const real = Storage.prototype.getItem;
    Storage.prototype.getItem = () => { throw new Error('blocked'); };
    try {
      expect(readView('u1', 'stock')).toBe('flat');
      expect(readChainOf('u1', 'restocks', PURCHASE_ORDER)).toEqual([...PURCHASE_ORDER]);
    } finally {
      Storage.prototype.getItem = real;
    }
  });
});

describe('what was opened above a level', () => {
  it('travels as filters: the shop, the dates narrowed, the supplier, the category and the item', () => {
    let sel = selectIn({ from: '2026-08-21', to: '2026-10-20' }, 'year', { key: '2026', from: '2026-01-01', to: '2026-12-31' });
    expect(sel).toEqual({ from: '2026-08-21', to: '2026-10-20' });
    sel = selectIn(sel, 'month', { key: '2026-10', from: '2026-10-01', to: '2026-10-31' });
    expect(sel).toEqual({ from: '2026-10-01', to: '2026-10-20' });
    sel = selectIn(sel, 'branch', { key: 'b1' });
    sel = selectIn(sel, 'supplier', { key: 's1' });
    sel = selectIn(sel, 'category', { key: 'c1' });
    sel = selectIn(sel, 'item', { key: 'p1' });
    expect(sel).toEqual({ from: '2026-10-01', to: '2026-10-20', branchId: 'b1', supplierId: 's1', categoryId: 'c1', itemId: 'p1' });
  });

  it('says whole restocks under a category or an item', () => {
    expect(wholeRestocksNote({ itemId: 'p1' })).toMatch(/include this item/);
    expect(wholeRestocksNote({ categoryId: 'c1' })).toMatch(/include this category/);
    expect(wholeRestocksNote({ supplierId: 's1' })).toBeNull();
  });

  it("says a shop's restocks still show every shop the caller can see and their full total (#372)", () => {
    expect(wholeRestocksNote({ branchId: 'b1' })).toBe("Whole purchases that include this shop. Each shows the quantities and the total for every shop you can see.");
    expect(wholeRestocksNote({ branchId: 'b1', itemId: 'p1' })).toBe("Whole purchases that include this item. Each shows the quantities and the total for every shop you can see.");
  });

  it('offers the restocks on a day, on every level of a chain with no day, and above a last item level', () => {
    expect(offersRestocks(['branch', 'day', 'item'], 1)).toBe(true);
    expect(offersRestocks(['branch', 'day', 'item'], 0)).toBe(false);
    expect(offersRestocks(['branch', 'supplier'], 0)).toBe(true);
    expect(offersRestocks(['branch', 'year', 'item'], 1)).toBe(true);
    expect(offersRestocks(['branch', 'year', 'day', 'category', 'item'], 1)).toBe(false);
  });
});
