import react from '@vitejs/plugin-react';
import { chromium, type Browser } from 'playwright';
import { createServer, type ViteDevServer } from 'vite';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

// Quick add laid out in Chromium (issue #642): on a phone 360, 390 and 412 wide, at the default and a large font size, the page
// never scrolls sideways and every field, its label, its control and its hint or error ends inside the form. Before the fix the
// photo's one uncut hint line widened the whole .stack column to 429px on a 412px phone. The page is layout/quick-add.html under
// the app's own stylesheets.

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

describe.each([360, 390, 412])('Quick add at %ipx wide', (width) => {
  it.each([1, 2])('fits the phone at font scale %i, before and after Add shows the errors', async (font) => {
    const page = await browser.newPage({ viewport: { width, height: 900 }, hasTouch: true, isMobile: true });
    await page.goto(`${base}layout/quick-add.html?font=${font}`);
    await page.locator('#qa-name').waitFor();
    for (const step of ['open', 'after Add'] as const) {
      if (step === 'after Add') {
        await page.getByRole('button', { name: 'Add', exact: true }).click();
        await page.locator('#qa-name-error').waitFor();
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth), `page width, ${step}`).toBeLessThanOrEqual(width);
      const form = await page.evaluate(() => document.querySelector('form')?.getBoundingClientRect().right ?? Infinity);
      expect(form, `form, ${step}`).toBeLessThanOrEqual(width + 0.5);
      const parts = await page.evaluate(() =>
        [...document.querySelectorAll('form .field, form .field > *, form .photo-choice, form .hint')].map((e) => ({
          what: `${e.tagName.toLowerCase()}.${e.className} ${e.id}`.trim(),
          right: e.getBoundingClientRect().right,
        })),
      );
      expect(parts.length).toBeGreaterThan(10);
      for (const part of parts) expect(part.right, `${part.what}, ${step}`).toBeLessThanOrEqual(form + 0.5);
      // Every hint's text fits its own box (issue #642 review): it wraps rather than running out sideways.
      const hints = await page.evaluate(() =>
        [...document.querySelectorAll<HTMLElement>('form .hint')].map((e) => ({ text: e.textContent ?? '', over: e.scrollWidth - e.clientWidth })),
      );
      expect(hints.some((h) => h.text.startsWith('A PNG, JPEG or WebP'))).toBe(true);
      for (const hint of hints) expect(hint.over, `${hint.text}, ${step}`).toBeLessThanOrEqual(0);
    }
    await page.close();
  });
});
