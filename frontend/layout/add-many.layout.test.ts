import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// Add many items on Import items laid out in Chromium (issue #870): on a phone 360, 390 and 412 wide, at the default and a large font size, the
// page never scrolls sideways, each row is a card whose fields all end inside it, every control is a 48px target, and the
// errors under the fields wrap inside the card; from 720px a row is one line of cells. The page is layout/add-many.html under
// the app's own stylesheets.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

/** Every part of every row card that sticks out of it, and every control under 48px tall. */
async function misfits(page: Page) {
  return page.evaluate(() => {
    const out: string[] = [];
    for (const card of document.querySelectorAll<HTMLElement>('.am-row')) {
      const box = card.getBoundingClientRect();
      for (const e of card.querySelectorAll<HTMLElement>('.field, .field > *, input, button, .field-error, .hint')) {
        const r = e.getBoundingClientRect();
        if (r.width === 0) continue;
        if (r.right > box.right + 0.5 || r.left < box.left - 0.5) out.push(`${card.dataset.testid} ${e.tagName.toLowerCase()}#${e.id}.${e.className} sticks out`);
        if (e.scrollWidth > e.clientWidth + 1 && (e.classList.contains('hint') || e.classList.contains('field-error'))) out.push(`${e.id} text runs out`);
        if ((e.tagName === 'INPUT' || e.tagName === 'BUTTON') && r.height < 47.5) out.push(`${e.id || e.textContent} is ${r.height}px tall`);
      }
    }
    return out;
  });
}

describe.each([360, 390, 412])('Add many items at %ipx wide', (width) => {
  it.each([1, 2])('fits the phone at font scale %i, before and after the row checks show', async (font) => {
    const page = await browser.newPage({ viewport: { width, height: 900 }, hasTouch: true, isMobile: true });
    await openLayout(page, `layout/add-many.html?font=${font}`, page.locator('#am-name-1'));
    for (const step of ['open', 'after Save'] as const) {
      if (step === 'after Save') {
        // A row with no name and a comma decimal: its errors show under the fields, and nothing is sent.
        await page.fill('#am-qty-1', '2,5');
        await page.fill('#am-sell-1', '1,50');
        await page.getByRole('button', { name: 'Save the list' }).click();
        await page.locator('#am-qty-1-error').waitFor();
      }
      const wide = await page.evaluate((w) => [...document.querySelectorAll<HTMLElement>('body *')].filter((e) => e.getBoundingClientRect().right > w + 0.5).slice(0, 8).map((e) => `${e.tagName}.${e.className}#${e.id} ${Math.round(e.getBoundingClientRect().right)}`), width);
      expect(wide, `wide, ${step}`).toEqual([]);
      expect(await page.evaluate(() => document.documentElement.scrollWidth), `page width, ${step}`).toBeLessThanOrEqual(width);
      expect(await page.locator('.am-row').count()).toBe(5);
      expect(await misfits(page), step).toEqual([]);
      // A card per row on a phone: the cells two to a line, the name across the card.
      const name = await page.locator('#am-name-1').boundingBox();
      const sell = await page.locator('#am-sell-1').boundingBox();
      expect((sell?.y ?? 0) > (name?.y ?? 0), 'the selling price sits below the name').toBe(true);
      const another = await page.getByRole('button', { name: 'Add another row' }).boundingBox();
      expect(another?.height ?? 0).toBeGreaterThanOrEqual(47.5);
      // Review of #871: the Paste a list and From a file summaries are 48px targets too.
      for (const summary of await page.locator('summary').all()) {
        const box = await summary.boundingBox();
        expect(box?.height ?? 0, `${await summary.textContent()} is a 48px target`).toBeGreaterThanOrEqual(47.5);
      }
    }
    await page.close();
  });
});

describe('Import items with From a file open at 412px wide', () => {
  it('still fits the phone, the file part under the rows', async () => {
    const page = await browser.newPage({ viewport: { width: 412, height: 900 }, hasTouch: true, isMobile: true });
    await openLayout(page, 'layout/add-many.html', page.locator('#am-name-1'));
    await page.getByText('From a file', { exact: true }).click();
    await page.locator('#import-text').waitFor();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(412);
    const check = await page.getByRole('button', { name: 'Check the file' }).boundingBox();
    expect(check?.height ?? 0).toBeGreaterThanOrEqual(47.5);
    // Save the list stays the one main action: the file's buttons are plain ones.
    expect(await page.locator('.rt-primary').count()).toBe(1);
    await page.close();
  });
});

describe('Add many items on a laptop', () => {
  it('puts each row on one line of cells', async () => {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    await openLayout(page, 'layout/add-many.html', page.locator('#am-name-1'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1280);
    // The cells start on one line; a label that wraps (the runner's fonts differ) may push its own box down, not the cell.
    const top = (id: string) => page.evaluate((x) => document.getElementById(x)?.closest('.field')?.getBoundingClientRect().top ?? -1, id);
    expect(Math.abs((await top('am-sell-1')) - (await top('am-name-1'))), 'name and selling price on one line').toBeLessThan(1);
    expect(await misfits(page)).toEqual([]);
    await page.close();
  });
});
