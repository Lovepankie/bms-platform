import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { daysBefore, businessToday } from '../src/api/retail';
import { openLayout } from './open';

// The owner's date on a new entry (issue #811, ADR-120), in Chromium on a phone 412 wide, at the default and a large font size:
// the date box and, once an earlier day is chosen, "Why an earlier date" and its hint fit inside the phone, nothing scrolls
// sideways, and the fields are big enough to tap. The page is layout/entry-date.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe.each([1, 1.5])('The entry date at 412px, font scale %s', (font) => {
  it('fits the date, the reason and its hint inside the phone', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/entry-date.html', page.locator('#withdrawal-date'));
    await page.evaluate((scale) => { document.documentElement.style.fontSize = `${16 * scale}px`; }, font);
    await page.fill('#withdrawal-date', daysBefore(businessToday(), 2));
    const reason = page.locator('#withdrawal-date-reason');
    await reason.waitFor();
    for (const selector of ['#withdrawal-date', '#withdrawal-date-reason', 'label[for="withdrawal-date-reason"]']) {
      const box = await page.locator(selector).boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, selector).toBe(true);
      // docs/ui-rules.md rule 12: every tap target is at least 48px high.
      if (selector.startsWith('#')) expect(box!.height, selector).toBeGreaterThanOrEqual(48);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    expect((await page.locator('main').textContent()) ?? '').toContain('Why an earlier date');
    await page.close();
  });
});
