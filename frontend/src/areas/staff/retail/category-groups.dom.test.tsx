// @vitest-environment jsdom
import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { OPEN_UP_TO, useCategoryGroups } from './category-groups';

// The default open or closed state of the groups is decided from the list that belongs to the search, never
// from the previous answer that stays on screen while the new one loads (a kept placeholder, #241).

type Item = { id: string; category: string };
const make = (n: number): Item[] => Array.from({ length: n }, (_, i) => ({ id: `i${i}`, category: `Category ${i % 4}` }));
const few = make(3);
const many = make(OPEN_UP_TO + 10);

interface Props { items: Item[]; resetKey: string; searching: boolean; pending: boolean }
const render = (initial: Props) =>
  renderHook((p: Props) => useCategoryGroups({ screen: 'test-groups', categoryOf: (i) => i.category, ...p }), {
    initialProps: initial,
  });
const allOpen = (r: ReturnType<typeof render>) => r.result.current.groups.every((g) => r.result.current.isOpen(g.key));
const allClosed = (r: ReturnType<typeof render>) => r.result.current.groups.every((g) => !r.result.current.isOpen(g.key));

describe('the default state of the groups when a search is cleared', () => {
  it('keeps a long list closed, though the three-row answer to the search was still on screen when it was cleared', () => {
    const r = render({ items: many, resetKey: '', searching: false, pending: false });
    expect(allClosed(r)).toBe(true);
    // a search narrows the list to a few rows: they open
    r.rerender({ items: few, resetKey: 'ka', searching: true, pending: false });
    expect(allOpen(r)).toBe(true);
    // the search is cleared: the key is the empty one again, but the list on screen is still the three rows
    r.rerender({ items: few, resetKey: '', searching: false, pending: true });
    // the full catalogue arrives
    r.rerender({ items: many, resetKey: '', searching: false, pending: false });
    expect(allClosed(r)).toBe(true);
  });

  it('opens the groups of a search once its own answer is in', () => {
    const r = render({ items: many, resetKey: '', searching: false, pending: false });
    r.rerender({ items: many, resetKey: 'ka', searching: true, pending: true });
    r.rerender({ items: few, resetKey: 'ka', searching: true, pending: false });
    expect(allOpen(r)).toBe(true);
  });
});
