import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The item page on a phone 412 wide (issue #813), at the default and a large font size: long shop and item names, large
// quantities and amounts stay inside each card, nothing scrolls sideways, figures are tabular, a card has one border all round
// and no gradient, and the shop choice, the days and Show older movements are full 48px targets. A seller's page has no buying
// figure or profit.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

async function open(font: number, extra = ''): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  return openLayout(page, `layout/item-page.html?font=${font}${extra}`, page.locator('[data-testid="item-move"]').first());
}

const pageWidth = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth);

async function cards(page: Page) {
  return page.evaluate(() =>
    [...document.querySelectorAll<HTMLElement>('[data-testid="item-shop"], [data-testid="item-move"]')].map((card) => {
      const box = card.getBoundingClientRect();
      const style = getComputedStyle(card);
      return {
        right: box.right,
        left: box.left,
        over: [...card.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 0.5).map((e) => e.tagName),
        edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
        tabular: style.fontVariantNumeric.includes('tabular-nums'),
        gradient: style.backgroundImage !== 'none',
      };
    }),
  );
}

describe.each([1, 2])('The item page at 412px, font scale %i', (font) => {
  it('keeps every shop and movement card, its long names and large figures inside the phone', async () => {
    const page = await open(font);
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const all = await cards(page);
    expect(all).toHaveLength(6);
    for (const card of all) {
      expect(card.left, 'card inside the phone').toBeGreaterThanOrEqual(0);
      expect(card.right, 'card inside the phone').toBeLessThanOrEqual(WIDTH);
      expect(card.over, 'nothing past the card').toEqual([]);
      expect(card.edges, 'no side or top stripe: one border width all round').toBe(1);
      expect(card.tabular, 'tabular figures').toBe(true);
      expect(card.gradient, 'no gradient').toBe(false);
    }
    await page.close();
  });

  it('makes the shop choice, the days and Show older movements full 48px targets', async () => {
    const page = await open(font);
    const targets = [
      page.locator('#item-shop'),
      page.getByRole('button', { name: 'Show older movements' }),
      ...(await page.locator('.period-picker button').all()),
    ];
    for (const target of targets) {
      const box = await target.boundingBox();
      expect(box?.height ?? 0, await target.evaluate((e) => e.outerHTML.slice(0, 80))).toBeGreaterThanOrEqual(48);
      expect((box?.x ?? 0) + (box?.width ?? 0)).toBeLessThanOrEqual(WIDTH);
    }
    await page.close();
  });

  it('shows a seller the quantities and no buying figure or profit, with the same full size targets', async () => {
    const page = await open(font, '&seller=1');
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    for (const target of [page.locator('#item-shop'), page.getByRole('button', { name: 'Show older movements' })]) {
      expect((await target.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(48);
    }
    const text = (await page.locator('body').textContent()) ?? '';
    expect(text).toContain('In stock after');
    expect(text).not.toMatch(/buying price|profit/i);
    await page.close();
  });
});
