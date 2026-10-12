import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { FOLD_FROM, FOLD_LIGATURES, FOLD_TO, foldText } from './fold';
import { highlightParts, matchesSearch, normaliseSearch } from './useLiveSearch';

// The browser marks matches and narrows loaded lists with the same folding the database uses for a search
// (bms_fold, migration V40). This keeps the two equal: the table is read out of the migration itself.

const SQL = readFileSync(fileURLToPath(new URL('../../../../backend/src/main/resources/db/migration/V40__search_helpers.sql', import.meta.url)), 'utf8');
const decode = (escaped: string) => Array.from(escaped.matchAll(/\\([0-9A-F]{4})/g), (m) => String.fromCodePoint(parseInt(m[1] ?? '0', 16))).join('');

describe('the folding table', () => {
  it('equals the one in migration V40, letter for letter', () => {
    const table = /U&'((?:\\[0-9A-F]{4}){20,})',\s+'([a-z]+)'/.exec(SQL);
    expect(table).not.toBeNull();
    expect(decode(table?.[1] ?? '')).toBe(FOLD_FROM);
    expect(table?.[2]).toBe(FOLD_TO);
    expect(FOLD_FROM.length).toBe(FOLD_TO.length);
  });

  it('spells out the same ligatures, in the same order, as the migration', () => {
    const pairs = Array.from(SQL.matchAll(/U&'((?:\\[0-9A-F]{4})+)', '([a-z]+)'\)/g), (m) => [decode(m[1] ?? ''), m[2] ?? ''] as [string, string]);
    expect(pairs).toEqual(FOLD_LIGATURES);
  });
});

/** The same cases are folded by the database in SearchLendingIT; both sides must give these answers. */
export const FOLD_CASES: [string, string][] = [
  ['José', 'jose'],
  ['ÀØÑ', 'aon'],
  ['Straße', 'strasse'],
  ['Æsop', 'aesop'],
  ['Cœur', 'coeur'],
  ['Łódź', 'lodz'],
  ['Đorđe', 'dorde'],
  ['Niño', 'nino'],
  ['Plain Name 07', 'plain name 07'],
];

describe('foldText', () => {
  it.each(FOLD_CASES)('folds %s to %s', (input, expected) => {
    expect(foldText(input)).toBe(expected);
  });

  it('makes a letter typed with a combining accent the same as the one-piece letter', () => {
    expect(foldText('José')).toBe('jose');
    expect(normaliseSearch('José')).toBe('José');
  });

  it('is what Highlight and the in-browser filter use', () => {
    expect(matchesSearch('Straße 5', 'STRASSE')).toBe(true);
    expect(matchesSearch('Cœur', 'coeur')).toBe(true);
    expect(matchesSearch('Łódź', 'lodz')).toBe(true);
    expect(highlightParts('Straße 5', 'strasse')).toEqual([
      { text: 'Straße', hit: true },
      { text: ' 5', hit: false },
    ]);
    expect(highlightParts('José Mukasa', 'jose')[0]).toEqual({ text: 'José', hit: true });
  });
});
