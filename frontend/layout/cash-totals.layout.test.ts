import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The expense records grouped with a total at every level (issue #816) on a phone 412 wide: shop, year, month, category and day
// headers are 48px targets inside the phone, a long category name and a large total wrap instead of widening the page, every
// total is in tabular figures, and a group has one border width all round and no gradient. The page is layout/cash-totals.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('cash book totals at 412px (#816)', () => {
  it('keeps every group header, its total and its words inside the phone', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/cash-totals.html', page.locator('.rt-group-head').first());
    // The newest records open on arrival; the second shop opens on a tap.
    await page.locator('.rt-group-head', { hasText: 'Test Branch B' }).click();
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const heads = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.rt-group-head')].map((h) => {
        const box = h.getBoundingClientRect();
        const group = getComputedStyle(h.closest('.rt-group') as HTMLElement);
        return {
          right: box.right, height: box.height,
          over: [...h.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 0.5).map((e) => e.textContent),
          edges: new Set([group.borderLeftWidth, group.borderRightWidth, group.borderTopWidth, group.borderBottomWidth]).size,
          gradient: group.backgroundImage !== 'none' || getComputedStyle(h).backgroundImage !== 'none',
        };
      }),
    );
    for (const h of heads) {
      expect(h.right, 'header inside the phone').toBeLessThanOrEqual(WIDTH);
      expect(h.height, '48px target').toBeGreaterThanOrEqual(48);
      expect(h.over, 'nothing past the header').toEqual([]);
      expect(h.edges, 'no side or top stripe').toBe(1);
      expect(h.gradient, 'no gradient').toBe(false);
    }
    const totals = await page.evaluate(() => [...document.querySelectorAll<HTMLElement>('.rt-group-total .num')].map((e) => getComputedStyle(e).fontVariantNumeric));
    expect(totals.length).toBeGreaterThanOrEqual(6);
    expect(totals.every((v) => v.includes('tabular-nums'))).toBe(true);
    const shopA = (await page.locator('.rt-group-head', { hasText: 'Test Branch A' }).textContent()) ?? '';
    const shopB = (await page.locator('.rt-group-head', { hasText: 'Test Branch B' }).textContent()) ?? '';
    expect(shopA, 'the first shop totals what was spent, the voided record left out').toContain('UGX 987,656,321');
    expect(shopA).toContain('Spent in 2 expenses, 1 voided (not counted)');
    expect(shopB).toContain('UGX 4,000');
    expect(shopB).toContain('Spent in 1 expense');
    // Shop A down to its newest day (shop, year, month, two categories, a day) and shop B with its year: at least seven.
    expect(heads.length).toBeGreaterThanOrEqual(7);
    await page.close();
  });
});
