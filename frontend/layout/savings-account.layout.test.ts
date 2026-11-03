import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The savings transactions on a phone 412 wide (issue #314), at the default and a large font size: the payment code of a deposit
// paid by mobile money or card, even a long one with no spaces, stays inside its card and the phone; nothing scrolls sideways;
// the card has one border width all round and no gradient. The page is layout/savings-account.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;
const CODE = 'TESTCODE314ABCDEFGHIJKLMNOPQRSTUV';

async function open(font: number): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  return openLayout(page, `layout/savings-account.html?font=${font}`, page.getByText(CODE));
}

describe.each([1, 2])('The savings transactions at 412px, font scale %i (#314)', (font) => {
  it('keeps the payment code inside its card and the phone', async () => {
    const page = await open(font);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const c = await page.evaluate((code) => {
      const strong = [...document.querySelectorAll<HTMLElement>('strong')].find((e) => e.textContent === code);
      const card = strong?.closest<HTMLElement>('li');
      if (!strong || !card) return null;
      const box = card.getBoundingClientRect();
      const style = getComputedStyle(card);
      return {
        right: box.right,
        codeRight: strong.getBoundingClientRect().right,
        over: [...card.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 0.5).map((e) => e.tagName),
        edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
        gradient: style.backgroundImage !== 'none',
        codes: [...document.querySelectorAll('li')].filter((li) => li.textContent?.includes('Payment code')).length,
      };
    }, CODE);
    expect(c?.right ?? Infinity, 'card inside the phone').toBeLessThanOrEqual(WIDTH);
    expect(c?.codeRight ?? Infinity, 'code inside the card').toBeLessThanOrEqual(c?.right ?? 0);
    expect(c?.over, 'nothing past the card').toEqual([]);
    expect(c?.edges, 'no side or top stripe: one border width all round').toBe(1);
    expect(c?.gradient, 'no gradient').toBe(false);
    expect(c?.codes, 'only the gateway deposit has a code').toBe(1);
    await page.close();
  });
});
