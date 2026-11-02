import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The shop's AI assistant card laid out in Chromium (review of #735): on a phone 412 wide, at the default and a large font size,
// a figure chip whose label carries a long item name wraps inside the card, every chip ends inside it, and the page never scrolls
// sideways.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe.each([1, 1.5])('The AI assistant card at 412px, font scale %s', (font) => {
  it('wraps a long chip inside the card and never scrolls sideways', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 900 }, hasTouch: true, isMobile: true });
    await openLayout(page, `layout/advisor-card.html?font=${font}`, page.locator('[data-testid="advisor-card"]'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const seen = await page.evaluate(() => {
      const card = document.querySelector('[data-testid="advisor-card"] .iv-card')?.getBoundingClientRect();
      const chips = [...document.querySelectorAll<HTMLElement>('[data-testid="advisor-card"] .cluster > *')].map((e) => {
        const r = e.getBoundingClientRect();
        return { text: e.textContent ?? '', right: r.right, over: e.scrollWidth - e.clientWidth };
      });
      return { card: card ? card.right : 0, chips };
    });
    expect(seen.chips).toHaveLength(2);
    for (const c of seen.chips) {
      expect(c.over, c.text).toBeLessThanOrEqual(0);
      expect(c.right, c.text).toBeLessThanOrEqual(seen.card + 0.5);
    }
    await page.close();
  });
});
