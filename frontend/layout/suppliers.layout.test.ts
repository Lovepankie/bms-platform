import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Suppliers (issue #820), in Chromium on a phone 412 wide: the searchable list, one supplier's page with long details, the Call,
// Text, WhatsApp, Email and Website buttons and the deliveries, and the form with every field fit inside the phone, nothing
// scrolls sideways, and every button and row is big enough to tap. The page is layout/suppliers.html.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

describe('suppliers at 412px (#820)', () => {
  it('fits the list, the page and the form inside the phone, with 48px targets 8px apart', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/suppliers.html', page.getByRole('list', { name: 'Deliveries, newest first' }));
    const name = 'Test Supplier 01 with a long name for the phone';
    const page2 = page.getByTestId('page');
    const targets = [
      page.getByLabel('Search by name, phone or type'),
      page.getByRole('button', { name: 'Add a supplier' }),
      page.getByRole('link', { name: `Open ${name}` }),
      page2.getByRole('link', { name: `Call ${name}` }),
      page2.getByRole('link', { name: `Text ${name}` }),
      page2.getByRole('link', { name: `WhatsApp ${name}` }),
      page2.getByRole('link', { name: `Email ${name}` }),
      page2.getByRole('link', { name: `Open the website of ${name}` }),
      page2.getByRole('button', { name: `Edit ${name}` }),
      page2.getByRole('button', { name: `Switch off ${name}` }),
      page2.getByRole('button', { name: 'Show older deliveries' }),
      ...(await page2.getByRole('list', { name: 'Deliveries, newest first' }).getByRole('link').all()),
      page.getByTestId('form').getByRole('button', { name: 'Save' }),
      page.getByTestId('form').getByRole('button', { name: 'Cancel' }),
    ];
    for (const target of targets) {
      const box = await target.boundingBox();
      const what = (await target.textContent()) ?? '';
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, what).toBe(true);
      // docs/ui-rules.md rule 12: every tap target is at least 48px high.
      expect(box!.height, what).toBeGreaterThanOrEqual(48);
    }
    // Rule 12: Email and Website, and Edit and Switch off, stand at least 8px apart, side by side or stacked.
    for (const [a, b] of [[6, 7], [8, 9]] as const) {
      const [x, y] = [await targets[a]!.boundingBox(), await targets[b]!.boundingBox()];
      const apart = Math.max(y!.x - (x!.x + x!.width), y!.y - (x!.y + x!.height));
      expect(apart).toBeGreaterThanOrEqual(8);
    }
    for (const label of ['Email', 'Address', 'Website', 'Bank account number', 'Supplier type', 'Notes']) {
      const box = await page.getByTestId('form').getByLabel(label, { exact: true }).boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, label).toBe(true);
    }
    for (const text of ['orders.and.accounts@supplier-test-with-a-long-name.example', 'www.supplier-test-with-a-long-name.example/shop']) {
      const box = await page2.getByText(text, { exact: true }).boundingBox();
      expect(box && box.x >= 0 && box.x + box.width <= WIDTH, text).toBe(true);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
