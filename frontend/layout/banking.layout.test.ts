import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The Banking form of a person with the Till role and no profit access (issue #814, ADR-115 decision 11), in Chromium on a
// phone 412 wide: with the owner's savings switch on, the savings set aside and Expected to bank show in tabular figures inside
// the phone, the amount is filled with Expected to bank, the field and the save button are 48px targets, nothing scrolls sideways
// and no stock bought with cash shows. With the switch off, the cash expected only. The page is layout/banking.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('the Banking form of the Till role at 412px (#814)', () => {
  it("with the owner's savings switch on: the savings line and Expected to bank fit, in tabular figures, and the amount is filled", async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/banking.html', page.getByText('Savings set aside'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const rows = page.locator('[aria-label="What to expect"] .rt-row');
    const texts = await rows.allTextContents();
    expect(texts.some((t) => t.includes('Savings set aside') && t.includes('UGX 240,000'))).toBe(true);
    expect(texts.some((t) => t.includes('Expected to bank') && t.includes('UGX 1,010,000'))).toBe(true);
    for (const line of await rows.all()) {
      const box = await line.boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH).toBe(true);
    }
    const tabular = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('[aria-label="What to expect"] .rt-row strong')].map((e) => getComputedStyle(e).fontVariantNumeric),
    );
    expect(tabular.length).toBe(4);
    expect(tabular.every((v) => v.includes('tabular-nums'))).toBe(true);
    expect(await page.locator('#banking-amount').inputValue()).toBe('1010000');
    for (const target of [page.locator('#banking-amount'), page.getByRole('button', { name: 'Save banking' })]) {
      const box = await target.boundingBox();
      expect(box && box.height >= 48 && box.x >= 0 && box.x + box.width <= WIDTH).toBe(true);
    }
    const text = (await page.locator('main').textContent()) ?? '';
    expect(text).not.toMatch(/Stock bought with cash|Profit/);
    await page.close();
  });

  it('with savings more than the cash expected: Expected to bank is 0 with the plain line, and the amount is filled with 0 (#847)', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/banking.html?more', page.getByText('Savings set aside'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const texts = await page.locator('[aria-label="What to expect"] .rt-row').allTextContents();
    expect(texts.some((t) => t.includes('Expected to bank') && t.includes('UGX 0'))).toBe(true);
    expect(texts.join(' ')).not.toMatch(/-\s*UGX|UGX\s*-/);
    const line = page.getByText('Savings are more than the cash in the till today.');
    const box = await line.boundingBox();
    expect(box && box.x >= 0 && box.x + box.width <= WIDTH).toBe(true);
    expect(await page.locator('#banking-amount').inputValue()).toBe('0');
    await page.close();
  });

  it('with the switch off: the cash expected only, the amount filled with it', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/banking.html?off', page.getByText('Cash expected'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const text = (await page.locator('main').textContent()) ?? '';
    expect(text).not.toMatch(/Savings set aside|Expected to bank|Stock bought with cash/);
    expect(await page.locator('#banking-amount').inputValue()).toBe('1250000');
    await page.close();
  });
});
