import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The note over Stock opened for one item from a link (issue #336, review of #797) on a phone 412 wide, at the default and a large
// font size: it stays inside the phone, Show every item is a target of at least 48px, and the note is a neutral grey with one
// border width all round (no coloured stripe) and no gradient. The page is layout/one-item.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe.each([1, 2])('The one item note at 412px, font scale %i', (font) => {
  it('fits the phone, with a 48px button on a plain grey note', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, `layout/one-item.html?font=${font}`, page.getByText('Show every item'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const c = await page.evaluate(() => {
      const note = document.querySelector<HTMLElement>('[data-testid="one-item"]');
      const button = note?.querySelector<HTMLElement>('button');
      if (!note || !button) return null;
      const style = getComputedStyle(note);
      const rgb = (style.backgroundColor.match(/\d+/g) ?? []).slice(0, 3).map(Number);
      return {
        right: note.getBoundingClientRect().right,
        buttonHeight: button.getBoundingClientRect().height,
        buttonRight: button.getBoundingClientRect().right,
        edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
        colours: new Set([style.borderLeftColor, style.borderRightColor, style.borderTopColor, style.borderBottomColor]).size,
        gradient: style.backgroundImage !== 'none',
        spread: Math.max(...rgb) - Math.min(...rgb),
      };
    });
    expect(c?.right ?? Infinity, 'note inside the phone').toBeLessThanOrEqual(WIDTH);
    expect(c?.buttonRight ?? Infinity, 'button inside the note').toBeLessThanOrEqual(c?.right ?? 0);
    expect(c?.buttonHeight ?? 0, 'a 48px target').toBeGreaterThanOrEqual(48);
    expect(c?.edges, 'no side or top stripe: one border width all round').toBe(1);
    expect(c?.colours, 'one border colour all round').toBe(1);
    expect(c?.gradient, 'no gradient').toBe(false);
    expect(c?.spread ?? 99, 'a neutral grey, not a tint').toBeLessThanOrEqual(12);
    await page.close();
  });
});
