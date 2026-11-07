import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// Imports to sign off on a phone 412 wide (issue #134), at the default and a large font size: the list of imports, and one
// import's reconciliation with long file names, a long product code and money past a billion, stay inside the phone; nothing
// scrolls sideways; each section is a card with one border width all round, no gradient and tabular figures; every checkbox,
// button and the line that opens the explained differences is a 48px target; there is one primary action, Sign the
// reconciliation; and an import with a difference that is not explained offers no way to sign.

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
  const ready = extra.includes('open') ? page.getByRole('heading', { name: 'Sign off the import' }) : page.getByText('Ready to sign');
  await openLayout(page, `layout/imports-sign-off.html?font=${font}${extra}`, ready);
  // Measured only once the fonts are in and the page measures the same twice in a row (issue #775).
  await settle(page.locator('body'));
  return page;
}

const pageWidth = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth);

describe.each([1, 2])('Imports to sign off at 412px, font scale %i', (font) => {
  it('keeps the list inside the phone, each import a full target', async () => {
    const page = await open(font);
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const rows = await page.locator('.list-row').evaluateAll((els) => els.map((e) => e.getBoundingClientRect()).map((b) => ({ right: b.right, height: b.height })));
    expect(rows).toHaveLength(2);
    for (const row of rows) {
      expect(row.right, 'row inside the phone').toBeLessThanOrEqual(WIDTH);
      expect(row.height, 'row is a full target').toBeGreaterThanOrEqual(48);
    }
    await page.close();
  });

  it('keeps every section of the reconciliation inside the phone, plain and tabular, with one primary action', async () => {
    const page = await open(font, '&open=1');
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const cards = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('main section.rt-card')].map((card) => {
        const box = card.getBoundingClientRect();
        const style = getComputedStyle(card);
        return {
          right: box.right,
          left: box.left,
          over: [...card.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 0.5).map((e) => e.tagName),
          edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
          gradient: style.backgroundImage !== 'none',
          tabular: getComputedStyle(card.querySelector('dd') ?? card).fontVariantNumeric.includes('tabular-nums'),
        };
      }),
    );
    expect(cards.length, 'four sections').toBe(4);
    for (const card of cards) {
      expect(card.left, 'card inside the phone').toBeGreaterThanOrEqual(0);
      expect(card.right, 'card inside the phone').toBeLessThanOrEqual(WIDTH);
      expect(card.over, 'nothing past the card').toEqual([]);
      expect(card.edges, 'no side or top stripe: one border width all round').toBe(1);
      expect(card.gradient, 'no gradient').toBe(false);
      expect(card.tabular, 'tabular figures').toBe(true);
    }
    expect(await page.locator('.btn-primary').count(), 'one primary action').toBe(1);
    for (const target of await page.locator('label:has(> input[type="checkbox"]), main button, .recon-items > summary').all()) {
      const box = await target.boundingBox();
      expect(box?.height ?? 0, `${await target.textContent()} is a full target`).toBeGreaterThanOrEqual(48);
    }
    for (const field of ['Your name', "Today's date"]) {
      const box = await page.getByLabel(field).boundingBox();
      expect(box?.height ?? 0, `${field} is a full target`).toBeGreaterThanOrEqual(48);
      expect((box?.x ?? 0) + (box?.width ?? 0), `${field} inside the phone`).toBeLessThanOrEqual(WIDTH);
    }
    await page.close();
  });

  it('keeps the explained differences inside the phone once opened', async () => {
    const page = await open(font, '&open=1');
    await page.locator('.recon-items > summary').first().click();
    await settle(page.locator('.recon-items[open]'));
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const over = await page.locator('.recon-items[open] li').evaluateAll((els, width) => els.filter((e) => e.getBoundingClientRect().right > width + 0.5).length, WIDTH);
    expect(over, 'no line past the phone').toBe(0);
    await page.close();
  });

  it('offers no way to sign while a difference is not explained', async () => {
    const page = await open(font, '&open=1&broken=1');
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    expect(await page.getByText(/not explained, so this cannot be signed yet/).count()).toBe(1);
    expect(await page.locator('.btn-primary').count()).toBe(0);
    expect(await page.locator('input[type="checkbox"]').count()).toBe(0);
    await page.close();
  });
});
