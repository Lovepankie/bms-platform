import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// An item's packages laid out in Chromium (issue #709, seen on the #710 screenshots): on a phone 412 wide, at the default and a
// large font size, the page never scrolls sideways, every amount of the pack table ends inside its cell and the table inside
// its card (the price sits under the size), the hint lines show their whole sentence with no ellipsis, and the ladder starts
// with the single unit (D99 = A).

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe.each([1, 1.5])('An item\'s packages at 412px, font scale %s', (font) => {
  it('fits the phone with no amount clipped and no hint cut', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 900 }, hasTouch: true, isMobile: true });
    await openLayout(page, `layout/pack-table.html?font=${font}`, page.locator('[data-testid="pack-table"]'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const seen = await page.evaluate(() => {
      const wrap = document.querySelector('[data-testid="pack-table"]')?.closest('.table-wrap') as HTMLElement | null;
      const box = wrap?.getBoundingClientRect();
      const amounts = [...document.querySelectorAll<HTMLElement>('[data-testid="pack-table"] .pack-size-price > *, [data-testid="pack-table"] th[scope="row"]')].map((e) => {
        const cell = e.closest('td, th')?.getBoundingClientRect();
        const r = e.getBoundingClientRect();
        return { text: e.textContent ?? '', right: r.right, cell: cell?.right ?? 0, over: e.scrollWidth - e.clientWidth };
      });
      const hints = [...document.querySelectorAll<HTMLElement>('[data-testid="pack-editor"] .hint, [data-testid="item-ladder"] .hint')].map((e) => ({
        text: e.textContent ?? '', over: e.scrollWidth - e.clientWidth, ellipsis: getComputedStyle(e).textOverflow === 'ellipsis' && getComputedStyle(e).whiteSpace === 'nowrap',
      }));
      return {
        wrap: box ? box.right : 0,
        scrolls: wrap ? wrap.scrollWidth - wrap.clientWidth : 0,
        amounts,
        hints,
        first: document.querySelector('.ladder-name')?.textContent ?? '',
      };
    });
    expect(seen.scrolls, 'the table scrolls sideways').toBeLessThanOrEqual(0);
    expect(seen.amounts.some((a) => a.text.includes('UGX 25,500,000'))).toBe(true);
    for (const a of seen.amounts) {
      expect(a.over, a.text).toBeLessThanOrEqual(0);
      expect(a.right, a.text).toBeLessThanOrEqual(a.cell + 0.5);
      expect(a.right, a.text).toBeLessThanOrEqual(seen.wrap + 0.5);
    }
    expect(seen.hints.some((h) => h.text.startsWith('Single unit first'))).toBe(true);
    for (const h of seen.hints) {
      expect(h.ellipsis, h.text).toBe(false);
      expect(h.over, h.text).toBeLessThanOrEqual(0);
    }
    expect(seen.first).toBe('1 Piece Single unit');
    await page.close();
  });

  it('keeps the table inside its card in a wide fallback font, before the web font loads', async () => {
    // JUSTIFICATION-A3: a new layout case; in DejaVu Sans at font 1.5 an unbroken "UGX 25,500,000" made the table 2px too wide.
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 900 }, hasTouch: true, isMobile: true });
    await openLayout(page, `layout/pack-table.html?font=${font}`, page.locator('[data-testid="pack-table"]'));
    await page.addStyleTag({ content: '* { font-family: "DejaVu Sans", sans-serif !important; }' });
    await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
    const wrap = page.locator('[data-testid="pack-table"]').locator('xpath=ancestor::*[contains(@class,"table-wrap")][1]');
    expect(await wrap.evaluate((e) => e.scrollWidth - e.clientWidth), 'the table scrolls sideways').toBeLessThanOrEqual(0);
    await page.close();
  });
});
