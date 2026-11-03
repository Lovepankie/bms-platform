import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The old app's Delete and Edit buttons (issue #830) in Chromium on a phone 412 wide, at the default and a large font size: the
// question each destructive button asks first, and its Yes and Keep buttons, fit inside the phone, nothing scrolls sideways, and
// every button is big enough to tap (docs/ui-rules.md rule 12). The page is layout/old-app-buttons.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

async function tappable(page: Page, scope: string, name: string) {
  const box = await page.locator(scope).getByRole('button', { name, exact: true }).boundingBox();
  expect(box && box.x >= 0 && box.x + box.width <= WIDTH, `${scope} ${name}`).toBe(true);
  expect(box!.height, `${scope} ${name}`).toBeGreaterThanOrEqual(48);
}

async function inside(page: Page, selector: string) {
  const box = await page.locator(selector).first().boundingBox();
  expect(box && box.x >= 0 && box.x + box.width <= WIDTH, selector).toBe(true);
}

describe.each([1, 1.5])('The old app buttons at 412px, font scale %s (#830)', (font) => {
  it('fits the questions and their buttons inside the phone, each at least 48px high', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/old-app-buttons.html', page.locator('[data-testid="void-sale-question"]'));
    await page.evaluate((scale) => { document.documentElement.style.fontSize = `${16 * scale}px`; }, font);

    await inside(page, '[data-testid="void-sale-question"]');
    await tappable(page, 'section[aria-label="Sale"]', 'Yes, delete it');
    await tappable(page, 'section[aria-label="Sale"]', 'Keep the sale');

    await tappable(page, 'section[aria-label="Expense"]', 'Edit');
    await tappable(page, 'section[aria-label="Expense"]', 'Delete');
    await page.locator('section[aria-label="Expense"]').getByRole('button', { name: 'Delete', exact: true }).click();
    await inside(page, '[data-testid="delete-expense-question"]');
    await tappable(page, 'section[aria-label="Expense"]', 'Yes, delete it');
    await tappable(page, 'section[aria-label="Expense"]', 'Keep it');

    await tappable(page, 'ul[aria-label="Categories"]', 'Delete Test lamps and torches');
    await page.locator('ul[aria-label="Categories"]').getByRole('button', { name: 'Delete Test lamps and torches' }).click();
    await inside(page, '[data-testid="delete-category-question"]');
    await tappable(page, 'ul[aria-label="Categories"]', 'Yes, delete it');
    await tappable(page, 'ul[aria-label="Categories"]', 'Keep it');

    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
