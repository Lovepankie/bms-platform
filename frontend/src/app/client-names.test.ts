import { readFileSync, readdirSync, statSync } from 'node:fs';
import { extname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// Issue #680 and AGENTS.md: no real client, shop, borrower or staff name in code, fixtures, tests, docs or help articles. This
// test reads every text file under frontend/src (the help articles included), frontend/layout and docs, folds it to lower case
// letters and digits only, and fails on any name below. The names are stored the same way (lower case, spaces and punctuation
// removed), so this file reads as a list of folded strings, not as the names.
//
// When a real client's name turns up in an issue, a screenshot or a review, add it here, folded the same way, in the same pull
// request that removes it.
const DENIED = [
  // The branch of a pilot shop named in issue #680 and found by the review of #687.
  'bulbstore',
];

const FRONTEND = fileURLToPath(new URL('../../', import.meta.url));
const ROOTS = [join(FRONTEND, 'src'), join(FRONTEND, 'layout'), join(FRONTEND, '../docs')];
const TEXT = new Set(['.ts', '.tsx', '.js', '.css', '.html', '.md', '.json', '.dsl', '.yml', '.yaml', '.txt', '.csv', '.sql', '.svg']);
const SELF = fileURLToPath(import.meta.url);

/** Lower case letters and digits only: "Bulb Store" and "bulb-store" both fold to "bulbstore". */
export const fold = (text: string) => text.toLowerCase().replace(/[^a-z0-9]/g, '');

function textFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'node_modules' ? [] : textFiles(path);
    return TEXT.has(extname(name)) && path !== SELF ? [path] : [];
  });
}

describe('no real client names (issue #680)', () => {
  it('folds a name however it is written', () => {
    expect(['Bulb Store', 'BULB-STORE', 'bulb_store'].map(fold)).toEqual(['bulbstore', 'bulbstore', 'bulbstore']);
    expect(DENIED.every((name) => name === fold(name))).toBe(true);
  });

  it('finds none in the code, the fixtures, the help articles or the docs', () => {
    const files = ROOTS.flatMap(textFiles);
    expect(files.length).toBeGreaterThan(500);
    const found = files.flatMap((path) => {
      const folded = fold(readFileSync(path, 'utf8'));
      return DENIED.filter((name) => folded.includes(name)).map((name) => `${relative(join(FRONTEND, '..'), path)}: ${name}`);
    });
    expect(found).toEqual([]);
  });
});
