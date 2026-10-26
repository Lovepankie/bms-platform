import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { Me } from '../api/client';
import { mockMe } from '../api/retail-mock';
import { offendingWords } from '../app/glossary';
import { router } from '../app/router';
import { ARTICLES, JOBS, REVISION, articleForScreen, articleText, articlesFor, parseArticle, searchArticles } from './corpus';

// The help corpus (issue #598, ADR-095): every article is well formed, every picture it names is a 412x900 WebP in public/help and
// every picture there is used, the revision is the content hash the ADR states, the permissions and screens it names exist, the
// words follow the glossary, the search finds what pilots asked, and a person sees only what their permissions allow. Every
// person here is fabricated.

const ARTICLE_DIR = new URL('./articles/', import.meta.url);
const PICTURE_DIR = new URL('../../public/help/', import.meta.url);
const MIGRATIONS = new URL('../../../backend/src/main/resources/db/migration/', import.meta.url);

/** The width and height of a WebP file, from its first chunk. */
function webpSize(bytes: Buffer): { width: number; height: number } {
  expect(bytes.toString('ascii', 0, 4)).toBe('RIFF');
  expect(bytes.toString('ascii', 8, 12)).toBe('WEBP');
  const chunk = bytes.toString('ascii', 12, 16);
  if (chunk === 'VP8X') return { width: 1 + bytes.readUIntLE(24, 3), height: 1 + bytes.readUIntLE(27, 3) };
  if (chunk === 'VP8L') {
    const bits = bytes.readUInt32LE(21);
    return { width: (bits & 0x3fff) + 1, height: ((bits >> 14) & 0x3fff) + 1 };
  }
  return { width: bytes.readUInt16LE(26) & 0x3fff, height: bytes.readUInt16LE(28) & 0x3fff };
}

const seller = mockMe('sales') as Me;
const shopAdmin = mockMe('admin') as Me;
// The shop's administrator also holds the core permissions of a tenant administrator, which the retail mock leaves out.
const owner = { ...shopAdmin, permissions: [...(shopAdmin.permissions ?? []), 'core.users.read', 'core.users.manage', 'core.branches.read', 'core.branches.manage', 'core.approvals.read'] } as Me;
const officer = {
  user_id: '00000000-0000-4000-8000-0000000000c1',
  full_name: 'Test Officer 01',
  kind: 'staff',
  permissions: ['lending.loans.read', 'lending.loans.create', 'lending.members.read', 'lending.loans.appraise', 'core.approvals.read'],
  modules: ['lending'],
  branches: [],
} as unknown as Me;
const ids = (list: { id: string }[]) => list.map((a) => a.id);

describe('the help corpus', () => {
  it('holds the 15 to 20 articles of the questions pilots asked, each job with one at least', () => {
    expect(ARTICLES.length).toBeGreaterThanOrEqual(15);
    expect(ARTICLES.length).toBeLessThanOrEqual(20);
    for (const job of JOBS) expect(ARTICLES.some((a) => a.job === job.id), job.label).toBe(true);
  });

  it('gives every article a title, an introduction and at least two steps, every step with a captioned picture', () => {
    for (const a of ARTICLES) {
      expect(a.title, a.id).toMatch(/^(How do I|What|Why) .+\?$/);
      expect(a.intro.length, a.id).toBeGreaterThan(0);
      expect(a.steps.length, a.id).toBeGreaterThanOrEqual(2);
      expect(a.steps.every((s) => s.picture?.caption), a.id).toBe(true);
      for (const s of a.steps) if (s.picture) expect(s.picture.file, a.id).toMatch(new RegExp(`^${a.id}-\\d+\\.webp$`));
    }
  });

  it('finds every picture in public/help at 412 by 900, and no picture there that no article uses', () => {
    const named = ARTICLES.flatMap((a) => a.steps.flatMap((s) => (s.picture ? [s.picture.file] : [])));
    const files = readdirSync(PICTURE_DIR).filter((n) => !n.startsWith('.'));
    expect([...files].sort()).toEqual([...new Set(named)].sort());
    for (const file of files) expect(webpSize(readFileSync(new URL(file, PICTURE_DIR))), file).toEqual({ width: 412, height: 900 });
  });

  it('has as its revision the first 16 hex digits of the SHA-256 of each file name and its bytes, in name order (ADR-095)', () => {
    const hash = createHash('sha256');
    for (const name of readdirSync(ARTICLE_DIR).filter((n) => n.endsWith('.md')).sort()) {
      hash.update(`${name}\0`);
      hash.update(readFileSync(new URL(name, ARTICLE_DIR)));
      hash.update('\0');
    }
    expect(REVISION).toBe(hash.digest('hex').slice(0, 16));
  });

  it('names only permissions the database seeds and screens the app routes', () => {
    const seeded = readdirSync(MIGRATIONS).map((n) => readFileSync(new URL(n, MIGRATIONS), 'utf8')).join('\n');
    const routes = Object.keys(router.routesByPath);
    for (const a of ARTICLES) {
      for (const p of [...a.needs, ...a.needsAny]) expect(seeded, `${a.id}: ${p}`).toContain(`'${p}'`);
      for (const screen of a.screens) expect(routes, `${a.id}: ${screen}`).toContain(screen);
    }
  });

  it('uses the glossary words and no long dashes', () => {
    for (const a of ARTICLES) {
      const text = articleText(a);
      expect(offendingWords(text), a.id).toEqual([]);
      expect(text, a.id).not.toMatch(/[\u2013\u2014]/);
    }
  });

  it('refuses an article without its front matter or with a job it does not know', () => {
    expect(() => parseArticle('x', 'Just text')).toThrow(/front matter/);
    expect(() => parseArticle('x', '---\ntitle: How do I fly?\njob: flying\n---\nText')).toThrow(/known job/);
  });

  it('reads steps, pictures, bold words and notes', () => {
    const a = parseArticle('x', '---\ntitle: How do I test?\njob: sell\nneeds: a.b, c.d\n---\nIntro **here**.\n\n1. Tap **Go**.\n   ![Tap Go](x-1.webp)\n2. Done.\n\n> A note.');
    expect(a.needs).toEqual(['a.b', 'c.d']);
    expect(a.intro).toEqual([[{ text: 'Intro ', strong: false }, { text: 'here', strong: true }, { text: '.', strong: false }]]);
    expect(a.steps[0]).toEqual({ text: [{ text: 'Tap ', strong: false }, { text: 'Go', strong: true }, { text: '.', strong: false }], picture: { caption: 'Tap Go', file: 'x-1.webp' } });
    expect(a.steps[1]?.picture).toBeUndefined();
    expect(a.notes).toEqual([[{ text: 'A note.', strong: false }]]);
  });
});

describe('searching the help', () => {
  const first = (typed: string, people: Me = owner) => searchArticles(articlesFor(people), typed)[0]?.id;

  it('answers the questions pilots asked', () => {
    expect(first('how do I move stock from the store to the shop')).toBe('move-stock-store-to-shop');
    expect(first('transfer')).toBe('move-stock-store-to-shop');
    expect(first('invite a cashier')).toBe('invite-someone');
    expect(first('change role')).toBe('change-roles');
    expect(first('give access to a branch')).toBe('change-roles');
    expect(first('link used or replaced')).toBe('invitation-link-used-or-replaced');
    expect(first('switch branch')).toBe('switch-branch');
    expect(first('what is the difference between a store and a shop')).toBe('store-or-shop');
    expect(first('counting')).toBe('count-stock');
    expect(first('item code')).toBe('add-an-item');
    expect(first('close the day')).toBe('close-the-day');
    expect(first('loan application', officer)).toBe('loan-application');
  });

  it('needs every word, ignores case and punctuation, and finds nothing for words no article holds', () => {
    expect(searchArticles(ARTICLES, 'xylophone')).toEqual([]);
    expect(searchArticles(ARTICLES, 'MOVE, Stock!')[0]?.id).toBe('move-stock-store-to-shop');
    expect(searchArticles(ARTICLES, 'move xylophone')).toEqual([]);
  });

  it('lists every article in order for an empty search', () => {
    expect(searchArticles(ARTICLES, '  ')).toEqual(ARTICLES);
  });
});

describe('who sees which article', () => {
  it('shows a seller the selling and branch answers, never the administrator ones', () => {
    const mine = ids(articlesFor(seller));
    expect(mine).toEqual(expect.arrayContaining(['record-a-sale', 'why-no-sale-at-a-store', 'switch-branch', 'store-or-shop', 'invitation-link-used-or-replaced', 'entry-warnings']));
    for (const id of ['add-a-branch', 'invite-someone', 'choose-what-needs-approval', 'count-stock', 'buy-stock', 'loan-application']) expect(mine).not.toContain(id);
  });

  it('shows a loan officer the loan answers and none of the shop ones', () => {
    const mine = ids(articlesFor(officer));
    expect(mine).toEqual(expect.arrayContaining(['loan-application', 'decide-a-loan-application', 'switch-branch']));
    expect(mine.some((id) => ['record-a-sale', 'store-or-shop', 'move-stock-store-to-shop'].includes(id))).toBe(false);
  });

  it('shows how to approve only to someone who may decide a request, not to someone who may only read them', () => {
    expect(ids(articlesFor(officer))).not.toContain('approve-a-request');
    const checker = { ...officer, permissions: [...(officer.permissions ?? []), 'lending.disbursements.authorise'] } as Me;
    expect(ids(articlesFor(checker))).toContain('approve-a-request');
  });

  it('opens the matching article from a screen, and nothing from a screen with none or for someone it is not for', () => {
    expect(articleForScreen('/staff/retail/transfer', owner)?.id).toBe('move-stock-store-to-shop');
    expect(articleForScreen('/staff/retail/catalogue/categories/00000000-0000-4000-8000-000000000001', owner)?.id).toBe('add-an-item');
    expect(articleForScreen('/staff/lending/applications/new', officer)?.id).toBe('loan-application');
    expect(articleForScreen('/staff/retail/transfer', seller)).toBeUndefined();
    expect(articleForScreen('/staff/users', owner)?.id).toBe('change-roles');
    expect(articleForScreen('/staff/users/new', owner)?.id).toBe('invite-someone');
    expect(articleForScreen('/staff/notifications', owner)).toBeUndefined();
  });
});
