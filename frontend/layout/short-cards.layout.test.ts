import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The cards that open short (issue #798, rule 1) on a phone 412 wide, at the default and a large font size: a move on its way,
// a cash summary day and a closed expense category stay inside the phone, every button that opens the rest is a target of at
// least 48px, none of them is a brand button, and the money is in tabular digits. The page is layout/short-cards.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe.each([1, 2])('The short cards at 412px, font scale %i', (font) => {
  it('fit the phone, with 48px plain buttons that open the rest', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, `layout/short-cards.html?font=${font}`, page.getByText('Show every line'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const c = await page.evaluate(() => {
      const names = ['Check what arrived', 'Take this back', 'Show every line', 'Show more moves'];
      const moves = document.querySelector<HTMLElement>('[data-testid="stock-moves"]');
      const buttons = [...document.querySelectorAll<HTMLElement>('button')];
      const opening = buttons.filter((b) => names.includes(b.textContent?.trim() ?? '') || b.getAttribute('aria-expanded') !== null);
      const money = [...document.querySelectorAll<HTMLElement>('.rt-row strong, .rt-row span')].find((s) => /UGX/.test(s.textContent ?? ''));
      return {
        count: opening.length,
        heights: opening.map((b) => b.getBoundingClientRect().height),
        rights: [...document.querySelectorAll<HTMLElement>('.rt-card')].map((x) => x.getBoundingClientRect().right),
        // The cards: none of them has a brand button until a form opens. Stock moves keeps its one, Move stock.
        brand: [...document.querySelectorAll('.rt-primary, .btn-primary')].filter((x) => !moves?.contains(x)).length,
        movesBrand: moves?.querySelectorAll('.rt-primary, .btn-primary').length ?? -1,
        soFar: moves?.textContent?.includes('1 move so far') ?? false,
        showMore: buttons.some((x) => x.textContent?.trim() === 'Show more moves' && moves?.contains(x)),
        tabular: money ? getComputedStyle(money).fontVariantNumeric : '',
      };
    });
    expect(c.count, 'the buttons that open more').toBeGreaterThanOrEqual(5);
    expect(c.showMore, 'Stock moves draws its own Show more moves').toBe(true);
    expect(c.soFar, 'the last month says so far while more pages exist').toBe(true);
    expect(c.movesBrand, 'Stock moves has one brand button, Move stock').toBe(1);
    for (const h of c.heights) expect(h, 'a 48px target').toBeGreaterThanOrEqual(48);
    for (const r of c.rights) expect(r, 'card inside the phone').toBeLessThanOrEqual(WIDTH);
    expect(c.brand, 'no brand button until a form is open').toBe(0);
    expect(c.tabular, 'tabular money').toContain('tabular-nums');
    await page.close();
  });
});
