import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Out of stock and Only N left on Record sale (issue #815) on a phone 412 wide, at the default and a large font size, light and
// dark: in the item picker and on the sale line each warning is a tinted badge with an icon and the words, inside the screen,
// with no stripe and no gradient; an item above its level has none; the line's buttons and boxes stay 48px; nothing scrolls
// sideways.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

async function open(font: number, theme: string): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  return openLayout(page, `layout/stock-warning.html?font=${font}&theme=${theme}`, page.getByRole('combobox'));
}

/** Every warning badge: its words, whether it has an icon, its box, and its edges and background. */
async function badges(page: Page, within: string) {
  return page.evaluate((selector) =>
    [...document.querySelectorAll<HTMLElement>(`${selector} [data-testid="stock-warning"]`)].map((b) => {
      const box = b.getBoundingClientRect();
      const style = getComputedStyle(b);
      const svg = b.querySelector('svg')?.getBoundingClientRect();
      return {
        words: b.textContent,
        icon: svg !== undefined,
        // On the words' row: the icon's middle at the badge's middle, and the words start after it.
        iconInline: svg !== undefined && Math.abs(svg.top + svg.height / 2 - (box.top + box.height / 2)) <= 3 && svg.right <= box.right - 10,
        widestBorder: Math.max(...[style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth].map(parseFloat)),
        left: box.left,
        right: box.right,
        tinted: style.backgroundColor !== 'rgba(0, 0, 0, 0)' && style.backgroundColor !== 'transparent',
        gradient: style.backgroundImage.includes('gradient'),
        edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
      };
    }), within);
}

describe.each([
  [1, 'light'],
  [1.5, 'light'],
  [1, 'dark'],
])('Stock warnings on Record sale at 412px, font scale %s, %s', (font, theme) => {
  it('shows the words with an icon on a tinted badge in the picker and on the line, inside the screen', async () => {
    const page = await open(font, theme);
    await page.getByRole('combobox').fill('test');
    await page.getByRole('option', { name: /^Test Bulb Plenty/ }).waitFor();
    for (const where of ['[role="listbox"]', '.line-card']) {
      const found = await badges(page, where);
      expect(found.map((b) => b.words), where).toEqual(['Out of stock', 'Only 10 left']);
      for (const b of found) {
        expect(b.icon, `${where} ${b.words}`).toBe(true);
        expect(b.iconInline, `${where} ${b.words} icon on the words' row`).toBe(true);
        expect(b.widestBorder, `${where} ${b.words} no stripe`).toBeLessThanOrEqual(1);
        expect(b.tinted, `${where} ${b.words}`).toBe(true);
        expect(b.gradient).toBe(false);
        expect(b.edges).toBe(1);
        expect(b.left).toBeGreaterThanOrEqual(0);
        expect(b.right).toBeLessThanOrEqual(WIDTH);
      }
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });

  it('keeps the sale line buttons and boxes 48px and inside the screen', async () => {
    const page = await open(font, theme);
    const targets = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.line-card button, .line-card input')]
        .filter((e) => e.offsetParent !== null)
        .map((e) => {
          const box = e.getBoundingClientRect();
          return { name: e.getAttribute('aria-label') || e.textContent?.trim() || e.id, height: box.height, left: box.left, right: box.right };
        }),
    );
    expect(targets.length).toBeGreaterThan(0);
    for (const t of targets) {
      expect(t.height, t.name).toBeGreaterThanOrEqual(48);
      expect(t.left, t.name).toBeGreaterThanOrEqual(0);
      expect(t.right, t.name).toBeLessThanOrEqual(WIDTH);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
