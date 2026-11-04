import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Past usage and damage on a phone 412 wide (issue #145), at the default and a large font size: a long item name and code, a
// long reason and a large value stay inside each card, the item list's long options do not widen the page, nothing scrolls
// sideways, figures are tabular, and the empty list shows its one action as a full 48px target.

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
  const ready = extra.includes('empty') ? page.getByText('Nothing was used or damaged in these dates.') : page.locator('[data-testid="usage-row"]').first();
  return openLayout(page, `layout/usage-history.html?font=${font}${extra}`, ready);
}

const pageWidth = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth);

describe.each([1, 2])('Past usage and damage at 412px, font scale %i', (font) => {
  it('keeps every card, its long name, code, reason and value inside the phone', async () => {
    const page = await open(font);
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const cards = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('[data-testid="usage-row"]')].map((card) => {
        const box = card.getBoundingClientRect();
        const style = getComputedStyle(card);
        return {
          right: box.right,
          over: [...card.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 0.5).map((e) => e.tagName),
          edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
          tabular: getComputedStyle(card).fontVariantNumeric.includes('tabular-nums'),
          gradient: style.backgroundImage !== 'none',
        };
      }),
    );
    expect(cards).toHaveLength(2);
    for (const card of cards) {
      expect(card.right, 'card inside the phone').toBeLessThanOrEqual(WIDTH);
      expect(card.over, 'nothing past the card').toEqual([]);
      expect(card.edges, 'no side or top stripe: one border width all round').toBe(1);
      expect(card.tabular, 'tabular figures').toBe(true);
      expect(card.gradient, 'no gradient').toBe(false);
    }
    await page.close();
  });

  it('keeps the page inside the phone with the item list open on long names', async () => {
    const page = await open(font);
    await page.getByLabel('Item (type part of its name or code)').fill('Test');
    await page.locator('#usage-history-item').waitFor();
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const select = await page.locator('#usage-history-item').boundingBox();
    expect(select?.x ?? 0).toBeGreaterThanOrEqual(0);
    expect((select?.x ?? 0) + (select?.width ?? 0)).toBeLessThanOrEqual(WIDTH);
    await page.close();
  });

  // JUSTIFICATION-A3: the 412px case of the Grouped view issue #816 asks for, beside the flat list's cases.
  it.each([false, true])('Grouped by shop, month, category and item (#816), seller %s: every header a 48px target inside the phone', async (seller) => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, `layout/usage-history.html?font=${font}&grouped=1${seller ? '&seller=1' : ''}`, page.locator('.rt-group-head').first());
    // Open the first shop, its month and its first category, so every level shows.
    for (let level = 0; level < 3; level++) {
      await page.locator('.rt-group-head[aria-expanded="false"]').first().click();
      await page.locator('.rt-group-head').nth(level + 1).waitFor();
    }
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const heads = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.rt-group-head')].map((h) => {
        const box = h.getBoundingClientRect();
        const group = getComputedStyle(h.closest('.rt-group') as HTMLElement);
        return {
          right: box.right, height: box.height,
          edges: new Set([group.borderLeftWidth, group.borderRightWidth, group.borderTopWidth, group.borderBottomWidth]).size,
          gradient: group.backgroundImage !== 'none' || getComputedStyle(h).backgroundImage !== 'none',
        };
      }),
    );
    expect(heads.length).toBeGreaterThanOrEqual(4);
    for (const h of heads) {
      expect(h.right, 'header inside the phone').toBeLessThanOrEqual(WIDTH);
      expect(h.height, '48px target').toBeGreaterThanOrEqual(48);
      expect(h.edges, 'no side or top stripe').toBe(1);
      expect(h.gradient, 'no gradient').toBe(false);
    }
    const totals = await page.evaluate(() => [...document.querySelectorAll<HTMLElement>('.rt-group-total .num')].map((e) => getComputedStyle(e).fontVariantNumeric));
    const words = await page.locator('.rt-group-head').allTextContents();
    if (seller) {
      expect(totals, 'no cost for a seller without the profit permission').toEqual([]);
      expect(words.filter((w) => /UGX|buying price/.test(w)), 'no cost words at any level, the item level included').toEqual([]);
      expect(words.some((w) => /^Test extension cable.*12 piece taken out, 1 record: 1 damaged$/.test(w)), 'the item keeps its quantity').toBe(true);
    } else {
      expect(totals.length).toBeGreaterThanOrEqual(4);
      expect(totals.every((v) => v.includes('tabular-nums'))).toBe(true);
    }
    expect(await page.getByText(/taken out, 1 record/).count(), 'the quantity at item level').toBeGreaterThan(0);
    await page.close();
  });

  it('shows the empty list with one action, a full 48px target, inside the phone', async () => {
    const page = await open(font, '&empty=1');
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const action = page.getByRole('link', { name: 'Record usage or damage' });
    expect(await action.isVisible()).toBe(true);
    expect((await action.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(48);
    await page.close();
  });
});
