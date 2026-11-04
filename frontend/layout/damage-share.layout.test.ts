import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Damage and use on Stock health (issue #822, ADR-127), in Chromium on a phone 412 wide: the period picker, the shop total
// and each item's shares with their quantities fit inside the phone, nothing scrolls sideways, the figures are tabular, and
// every period button is big enough to tap. The page is layout/damage-share.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('damage and use share at 412px (#822)', () => {
  it('fits the period picker, the shop total and the items inside the phone, with 48px buttons', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/damage-share.html', page.getByTestId('damage-share'));
    for (const name of ['Today', 'This week', 'This month', 'Custom']) {
      const box = await page.getByRole('radio', { name }).or(page.getByRole('button', { name })).first().boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, name).toBe(true);
      // docs/ui-rules.md rule 12: every tap target is at least 48px high.
      expect(box!.height, name).toBeGreaterThanOrEqual(48);
    }
    const items = page.getByTestId('damage-share-item');
    expect(await items.count()).toBe(2);
    for (const box of [await page.getByTestId('damage-share-shop').boundingBox(), await items.nth(0).boundingBox(), await items.nth(1).boundingBox()]) {
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH).toBe(true);
    }
    expect(await items.nth(0).textContent()).toContain('Damaged 10%');
    expect(await items.nth(1).textContent()).toContain('no stock came in to compare with');
    const figure = items.nth(0).locator('.num').first();
    expect(await figure.evaluate((el) => getComputedStyle(el).fontVariantNumeric)).toContain('tabular-nums');
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
