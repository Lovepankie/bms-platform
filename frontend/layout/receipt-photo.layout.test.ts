import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The receipt photo (issue #819, ADR-124), in Chromium on a phone 412 wide: the form's Add receipt photo with a photo chosen
// and Take the photo off, the offline words, a sale's small receipt photo that opens full size, and Add receipt photo on an
// expense row fit inside the phone, nothing scrolls sideways, and every button is big enough to tap. The page is
// layout/receipt-photo.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('receipt photo at 412px (#819)', () => {
  it('fits the button, the preview, the offline words and the photo on a sale inside the phone, with 48px targets', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/receipt-photo.html', page.getByRole('button', { name: 'Open the receipt photo' }));
    const chosen = page.getByTestId('chosen-receipt');
    const targets = [
      chosen.getByText('Change receipt photo'),
      chosen.getByRole('button', { name: 'Take the photo off' }),
      page.getByRole('button', { name: 'Open the receipt photo' }),
      page.getByTestId('expense-row').getByText('Add receipt photo'),
    ];
    for (const target of targets) {
      const box = await target.boundingBox();
      const name = (await target.textContent()) ?? '';
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, name).toBe(true);
      // docs/ui-rules.md rule 12: every tap target is at least 48px high.
      expect(box!.height, name).toBeGreaterThanOrEqual(48);
    }
    const preview = await chosen.getByRole('img', { name: 'The receipt photo chosen' }).boundingBox();
    expect(preview!.width).toBe(64);
    const offline = page.getByTestId('offline-receipt-offline');
    expect(await offline.textContent()).toBe('No network. The entry saves without the photo. Add the photo from the entry once you are online.');
    const words = await offline.boundingBox();
    expect(words && words.x >= 0 && words.x + words.width <= WIDTH).toBe(true);
    expect(await page.getByTestId('offline-receipt').count()).toBe(0);

    // The small picture opens full size, inside the phone.
    await page.getByRole('button', { name: 'Open the receipt photo' }).click();
    const full = page.getByRole('img', { name: 'Receipt photo, full size' });
    await full.waitFor();
    const big = await full.boundingBox();
    expect(big && big.x >= 0 && big.x + big.width <= WIDTH).toBe(true);
    expect(big!.width).toBeGreaterThan(200);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
