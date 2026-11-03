import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Issue #155 on a phone 412 wide, at the default and a large font size, light and dark. The platform landing: Apply to start and
// Find your sign-in page are the two big buttons, Operator sign-in is a small link below the cards, and nothing scrolls sideways.
// The Activate form: Check the address is a 48px target inside the screen, and the answer with the runbook pointer and a long
// host wraps inside the screen, with no coloured stripe and no gradient. The answer comes through the browser, as no server runs.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;
const HOST = 'test-hardware-and-electricals-155-bms-staging.example.test';

async function open(page: string, font: number, theme: string): Promise<Page> {
  const tab = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  await tab.route('**/api/v1/platform/applications/hostname-check**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ host: HOST, outcome: 'wrong_answer', http_status: 404 }) }),
  );
  const ready = page === 'landing' ? tab.getByRole('link', { name: 'Operator sign-in' }) : tab.getByRole('button', { name: 'Check the address' });
  return openLayout(tab, `layout/signup-findings.html?page=${page}&font=${font}&theme=${theme}`, ready);
}

/** The box of an element: inside the screen, and how tall. */
async function box(page: Page, selector: string) {
  return page.locator(selector).first().evaluate((e) => {
    const r = e.getBoundingClientRect();
    const style = getComputedStyle(e);
    return {
      left: r.left,
      right: r.right,
      height: r.height,
      edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
      colours: new Set([style.borderLeftColor, style.borderRightColor, style.borderTopColor, style.borderBottomColor]).size,
      gradient: style.backgroundImage.includes('gradient'),
    };
  });
}

describe.each([
  [1, 'light'],
  [2, 'light'],
  [1, 'dark'],
])('Issue #155 at 412px, font scale %i, %s', (font, theme) => {
  it('the landing keeps two big buttons and Operator sign-in as a small link below the cards', async () => {
    const page = await open('landing', font, theme);
    const buttons = await page.locator('.landing-actions a').allTextContents();
    expect(buttons).toEqual(['Apply to start', 'Staff of a business? Find your sign-in page']);
    const operator = page.getByRole('link', { name: 'Operator sign-in' });
    const cards = await page.locator('.landing-modules').boundingBox();
    const link = await operator.boundingBox();
    expect(link?.y ?? 0).toBeGreaterThan((cards?.y ?? 0) + (cards?.height ?? 0));
    const find = await page.getByRole('link', { name: /Find your sign-in page/ }).boundingBox();
    expect(find?.height ?? 0).toBeGreaterThanOrEqual(48);
    expect(link?.height ?? 0).toBeLessThan(find?.height ?? 0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });

  it('Check the address is a 48px target, and its answer wraps inside the screen with no stripe or gradient', async () => {
    const page = await open('activate', font, theme);
    const check = await box(page, 'button:has-text("Check the address")');
    expect(check.height).toBeGreaterThanOrEqual(48);
    expect(check.left).toBeGreaterThanOrEqual(0);
    expect(check.right).toBeLessThanOrEqual(WIDTH);
    await page.getByRole('button', { name: 'Check the address' }).click();
    const answer = page.getByRole('status');
    await answer.waitFor();
    expect(await answer.textContent()).toContain('Make the tenant host reachable');
    const alert = await box(page, '[role="status"].alert');
    expect(alert.left).toBeGreaterThanOrEqual(0);
    expect(alert.right).toBeLessThanOrEqual(WIDTH);
    expect(alert.edges).toBe(1);
    expect(alert.colours).toBe(1);
    expect(alert.gradient).toBe(false);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
