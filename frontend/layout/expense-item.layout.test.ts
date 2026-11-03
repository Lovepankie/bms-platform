import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// "Add a new item" on the expense form (issue #802, ADR-118), in Chromium on a phone 412 wide: the button under the Item
// picker and the small sheet it opens fit inside the phone, nothing scrolls sideways, and the buttons are big enough to tap.
// The page is layout/expense-item.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

const fits = async (page: Page, selector: string) => {
  const box = await page.locator(selector).first().boundingBox();
  expect(box && box.x >= 0 && box.x + box.width <= WIDTH, selector).toBe(true);
  return box!;
};

describe('Add a new item on the expense form at 412px (#802)', () => {
  it.each(['c1', 'c2'])('fits the button and the sheet inside the phone for category %s', async (category) => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/expense-item.html', page.locator('#expense-category'));
    await page.selectOption('#expense-category', category);
    const add = page.getByRole('button', { name: 'Add a new item' });
    const button = await add.boundingBox();
    expect(button && button.x >= 0 && button.x + button.width <= WIDTH).toBe(true);
    // docs/ui-rules.md rule 12: every tap target is at least 48px high.
    expect(button!.height).toBeGreaterThanOrEqual(48);
    await add.click();
    expect((await fits(page, '[id$="-name"]')).height).toBeGreaterThanOrEqual(48);
    for (const name of ['Add to the list', 'Cancel']) {
      const box = await page.getByRole('button', { name }).boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, name).toBe(true);
      expect(box!.height, name).toBeGreaterThanOrEqual(48);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    expect((await page.locator('main').textContent()) ?? '').not.toMatch(/Ask the owner to add its items first/);
    await page.close();
  });
});
