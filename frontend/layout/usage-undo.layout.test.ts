import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Undo a usage or damage entry (issue #818), in Chromium on a phone 412 wide: Undo this entry, the reason form with Yes, undo
// it and Keep the entry, and the Undone mark with who, when and why fit inside the phone, nothing scrolls sideways, and every
// button is big enough to tap. The page is layout/usage-undo.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('usage undo at 412px (#818)', () => {
  it('fits the button, the reason form and the Undone mark inside the phone, with 48px buttons 8px apart', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/usage-undo.html', page.getByTestId('undone-words'));
    const buttons = [
      page.getByRole('button', { name: 'Undo this entry' }),
      page.getByRole('button', { name: 'Yes, undo it' }),
      page.getByRole('button', { name: 'Keep the entry' }),
    ];
    for (const button of buttons) {
      const box = await button.boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH).toBe(true);
      // docs/ui-rules.md rule 12: every tap target is at least 48px high.
      expect(box!.height).toBeGreaterThanOrEqual(48);
    }
    // Rule 12: the form's two buttons stand at least 8px apart, side by side or stacked.
    const [yes, keep] = [await buttons[1]!.boundingBox(), await buttons[2]!.boundingBox()];
    const apart = Math.max(keep!.x - (yes!.x + yes!.width), keep!.y - (yes!.y + yes!.height));
    expect(apart).toBeGreaterThanOrEqual(8);
    const mark = page.getByTestId('usage-undone');
    expect(await mark.textContent()).toBe('Undone');
    // The icon is drawn at the size of the words, beside them.
    const icon = await mark.locator('svg').boundingBox();
    expect(icon!.height).toBeLessThanOrEqual(16);
    for (const id of ['usage-undone', 'undone-words', 'live-row', 'form-row', 'undone-row']) {
      const box = await page.getByTestId(id).boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, id).toBe(true);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
