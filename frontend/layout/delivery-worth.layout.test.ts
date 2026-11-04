import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// What a delivery is worth (issue #821), in Chromium on a phone 412 wide: the owner's Buy stock with the total cost, what it
// should sell for and the expected profit under the lines, and a Past purchases row with the same three. Each figure stays inside
// the phone on one line with its words beside or above it, never over them, nothing scrolls sideways, and the buttons around
// them are big enough to tap. The page is layout/delivery-worth.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('delivery worth at 412px (#821)', () => {
  it('fits the three figures of the form and of a past purchase inside the phone, in tabular figures', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/delivery-worth.html', page.getByTestId('delivery-worth'));
    const blocks = page.getByTestId('delivery-worth');
    await settle(blocks.first());
    expect(await blocks.count()).toBe(2);
    const expected = [
      ['Total cost', 'UGX 795,457,500'],
      ['Should sell for', 'UGX 997,755,000'],
      ['Expected profit', 'UGX 202,297,500'],
    ];
    for (const block of await blocks.all()) {
      const rows = await block.locator(':scope > div').all();
      expect(rows).toHaveLength(3);
      for (const [i, row] of rows.entries()) {
        const term = row.locator('dt');
        const amount = row.locator('dd .num');
        expect(await term.textContent()).toBe(expected[i]![0]);
        expect(await amount.textContent()).toBe(expected[i]![1]);
        const [t, a] = [await term.boundingBox(), await amount.boundingBox()];
        expect(a && a.x >= 0 && a.x + a.width <= WIDTH, expected[i]![1]).toBe(true);
        // One line: an amount never wraps inside its figures.
        const lineHeight = await amount.evaluate((el) => parseFloat(getComputedStyle(el).lineHeight) || 24);
        expect(a!.height, expected[i]![1]).toBeLessThan(lineHeight * 1.5);
        // The words and the amount never overlap.
        const apart = Math.max(a!.x - (t!.x + t!.width), a!.y - (t!.y + t!.height));
        expect(apart, expected[i]![0]).toBeGreaterThanOrEqual(0);
        expect(await amount.evaluate((el) => getComputedStyle(el).fontVariantNumeric)).toContain('tabular-nums');
      }
    }
    const targets = [
      page.getByRole('button', { name: 'Save the purchase' }),
      page.getByRole('button', { name: 'Edit delivery RP00000821' }),
      page.getByRole('button', { name: 'Correct purchase RP00000821' }),
    ];
    for (const target of targets) {
      const box = await target.boundingBox();
      const what = (await target.textContent()) ?? '';
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, what).toBe(true);
      // docs/ui-rules.md rule 12: every tap target is at least 48px high.
      expect(box!.height, what).toBeGreaterThanOrEqual(48);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
