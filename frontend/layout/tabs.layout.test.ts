import react from '@vitejs/plugin-react';
import { chromium, type Browser, type Page } from 'playwright';
import { createServer, type ViteDevServer } from 'vite';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

// The bottom tabs laid out in Chromium (issue #544), where tabs.dom.test.tsx can only read the stylesheet: on a phone 360 and 412
// wide, with the iPhone's home indicator inset simulated and at the default and a large font size, no tab overlaps another, each is
// a target of at least 48px, the bar sits on the inset, the sticky action bar stops on top of it, the page ends above both, and
// nothing scrolls sideways. The page is layout/tabs.html under the app's own stylesheets.

const INSET = 34;
let server: ViteDevServer;
let browser: Browser;
let base = '';

beforeAll(async () => {
  server = await createServer({ configFile: false, root: process.cwd(), plugins: [react()], logLevel: 'error', server: { port: 0, host: '127.0.0.1' } });
  await server.listen();
  base = server.resolvedUrls?.local[0] ?? '';
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
  await server?.close();
});

type Box = { x: number; y: number; width: number; height: number };
const overlap = (a: Box, b: Box) => Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x) > 0.5 && Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y) > 0.5;
const box = async (page: Page, selector: string) => (await page.locator(selector).first().boundingBox()) as Box;

async function open(width: number, font: number, low = 12) {
  const page = await browser.newPage({ viewport: { width, height: 900 }, hasTouch: true, isMobile: true });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: { top: 0, left: 0, right: 0, bottom: INSET } });
  await page.goto(`${base}layout/tabs.html?font=${font}&low=${low}`);
  await page.locator('nav.tab-bar .tab-badge').waitFor();
  return page;
}

describe.each([360, 412])('the bottom tabs at %ipx wide', (width) => {
  describe.each([1, 2])('at font scale %i', (font) => {
    it('lays out five tabs of at least 48px, none over another, each label under its icon', async () => {
      const page = await open(width, font);
      const tabs = await Promise.all((await page.locator('nav.tab-bar .tab').all()).map((t) => t.boundingBox() as Promise<Box>));
      expect(tabs).toHaveLength(5);
      for (const [i, t] of tabs.entries()) {
        expect(t.width, `tab ${i} width`).toBeGreaterThanOrEqual(48);
        expect(t.height, `tab ${i} height`).toBeGreaterThanOrEqual(48);
        for (const other of tabs.slice(i + 1)) expect(overlap(t, other)).toBe(false);
      }
      for (const tab of await page.locator('nav.tab-bar .tab').all()) {
        const icon = (await tab.locator('.tab-icon').boundingBox()) as Box;
        const label = (await tab.locator('.tab-label').boundingBox()) as Box;
        expect(label.y, 'label under its icon').toBeGreaterThanOrEqual(icon.y + icon.height - 0.5);
      }
      await page.close();
    });

    it.each([12, 120])('keeps a badge of %i off the next tab and off its own label', async (low) => {
      const page = await open(width, font, low);
      const badge = await box(page, '.tab-badge');
      const buy = page.locator('nav.tab-bar li').nth(2);
      for (const part of ['.tab-icon', '.tab-label']) expect(overlap(badge, (await buy.locator(part).boundingBox()) as Box), `Buy ${part}`).toBe(false);
      expect(overlap(badge, await box(page, 'nav.tab-bar li:nth-child(2) .tab-label'))).toBe(false);
      const bar = await box(page, 'nav.tab-bar');
      expect(badge.y).toBeGreaterThanOrEqual(bar.y);
      await page.close();
    });

    it('sits on the home indicator inset, with the action bar on top of it and the page ending above both', async () => {
      const page = await open(width, font);
      const bar = await box(page, 'nav.tab-bar');
      expect(bar.y + bar.height).toBeCloseTo(900, 0);
      const lastTab = await box(page, 'nav.tab-bar li:last-child .tab');
      expect(lastTab.y + lastTab.height).toBeLessThanOrEqual(900 - INSET + 0.5);
      // The measured height, inset included, is what the page and the action bar make room for.
      const measured = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--tab-bar'));
      expect(Number.parseFloat(measured)).toBeCloseTo(Math.ceil(bar.height), 0);
      for (const at of ['top', 'bottom'] as const) {
        await page.evaluate((to) => window.scrollTo(0, to === 'top' ? 0 : document.documentElement.scrollHeight), at);
        const action = await box(page, '.action-bar');
        expect(action.y + action.height, `action bar at the ${at}`).toBeLessThanOrEqual(bar.y + 0.5);
      }
      const last = await box(page, '.layout-line:last-of-type');
      const action = await box(page, '.action-bar');
      expect(last.y + last.height).toBeLessThanOrEqual(action.y + 0.5);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      await page.close();
    });
  });
});
