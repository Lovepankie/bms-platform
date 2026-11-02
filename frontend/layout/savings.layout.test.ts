import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The savings form of a salesperson with the owner's setting on (issue #768, ADR-115), in Chromium on a phone 412 wide: the
// suggestion shows in tabular figures, written as "UGX 1,000", the amount field fits, nothing scrolls sideways, and neither the
// profit, the expenses nor the most the day allows appear. The page is layout/savings.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('the savings form of a salesperson at 412px (#768)', () => {
  it('shows the suggestion inside the phone, in tabular figures, and no cap, profit or expenses', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/savings.html', page.locator('#savings-amount'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    expect(await page.locator('.key-figure-value').textContent()).toBe('UGX 240,000');
    expect(await page.getByText(/The most you can save|Available to save/).count()).toBe(0);
    const tabular = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.key-figure-value')].map((e) => getComputedStyle(e).fontVariantNumeric),
    );
    expect(tabular.length).toBe(1);
    expect(tabular.every((v) => v.includes('tabular-nums'))).toBe(true);
    const field = await page.locator('#savings-amount').boundingBox();
    expect(field && field.x >= 0 && field.x + field.width <= WIDTH).toBe(true);
    const text = (await page.locator('main').textContent()) ?? '';
    expect(text).not.toMatch(/Profit|Expenses for the day/);
    await page.close();
  });
});
