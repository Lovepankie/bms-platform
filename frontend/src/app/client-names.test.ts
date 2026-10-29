import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// The client name guard (issue #680, AGENTS.md: clients are never named). No real shop or client name may appear in the code,
// the fixtures, the layout pages, the docs or the help articles: every name there is invented ("Test Shop One"). The names are
// kept here normalised, lower case with spaces and punctuation removed, so this file never spells one out, and every text file
// is normalised the same way before it is searched, so "Some Name", "some-name" and "SOME NAME" are all caught.

const FRONTEND = fileURLToPath(new URL('../../', import.meta.url));
const ROOTS = [join(FRONTEND, 'src'), join(FRONTEND, 'layout'), join(FRONTEND, '../docs')];

// Real shop and client names, normalised. When a review or an issue turns up another real name, add it here.
const DENYLIST = ['bulbstore'];

const TEXT = /\.(?:tsx?|css|html|json|md|dsl|txt|ya?ml|csv|sql)$/;
const THIS_FILE = fileURLToPath(import.meta.url);

export function normalised(text: string): string {
  return text.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'node_modules' ? [] : files(path);
    return TEXT.test(name) && path !== THIS_FILE ? [path] : [];
  });
}

describe('no real client names (issue #680)', () => {
  it('normalises a name the way the list keeps it', () => {
    expect(normalised('Test  Shop-One (HQ)')).toBe('testshoponehq');
    expect(DENYLIST.every((name) => name === normalised(name))).toBe(true);
  });

  it('finds none in frontend/src, frontend/layout, the docs or the help articles', () => {
    const all = ROOTS.flatMap(files);
    expect(all.some((f) => f.includes(join('help', 'articles')))).toBe(true);
    const found = all.filter((f) => {
      const text = normalised(readFileSync(f, 'utf8'));
      return DENYLIST.some((name) => text.includes(name));
    });
    expect(found.map((f) => relative(FRONTEND, f))).toEqual([]);
  });
});
