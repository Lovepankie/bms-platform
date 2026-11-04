import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Where each entry is made (issue #826, ADR-122), in Chromium on a phone 412 wide: the one-time question, the owner's Shop
// locations with Use where I am now, and the sale's Where row fit inside the phone, nothing scrolls sideways, and every
// button is big enough to tap. The page is layout/entry-location.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('entry location at 412px (#826)', () => {
  it('fits the question, the shop locations and the distance inside the phone, with 48px buttons', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/entry-location.html', page.getByTestId('shop-locations'));
    expect(await page.getByTestId('location-ask').isVisible()).toBe(true);
    const buttons = [
      page.getByRole('button', { name: 'Allow' }),
      page.getByRole('button', { name: 'No thanks' }),
      page.getByRole('button', { name: 'Use where I am now' }).first(),
      page.getByRole('button', { name: 'Use where I am now' }).nth(1),
      page.getByRole('button', { name: 'Clear' }),
    ];
    for (const button of buttons) {
      const box = await button.boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH).toBe(true);
      // docs/ui-rules.md rule 12: every tap target is at least 48px high.
      expect(box!.height).toBeGreaterThanOrEqual(48);
    }
    // Rule 12 (#833): the question's two buttons stand at least 8px apart, side by side or stacked.
    const [allow, decline] = [await buttons[0]!.boundingBox(), await buttons[1]!.boundingBox()];
    const apart = Math.max(decline!.x - (allow!.x + allow!.width), decline!.y - (allow!.y + allow!.height));
    expect(apart).toBeGreaterThanOrEqual(8);
    const where = page.getByTestId('sale-where');
    expect(await where.textContent()).toBe('Where: 3.2 km from the shop, the phone sure to about 30 m');
    const box = await where.boundingBox();
    expect(box && box.x >= 0 && box.x + box.width <= WIDTH).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
