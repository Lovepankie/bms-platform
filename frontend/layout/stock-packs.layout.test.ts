import react from '@vitejs/plugin-react';
import { chromium, type Browser } from 'playwright';
import { createServer, type ViteDevServer } from 'vite';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

// Stock in cartons and pieces laid out in Chromium (issue #650): Stock (one shop and All branches), Count stock and Move stock on a phone 360 wide, at the
// default and a large font size. The page never scrolls sideways, the quantity in packs and pieces wraps inside its cell, and on
// a count or a move line the two boxes, their labels, the 0 button and the total end inside the card, every box a full tap.

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

const WIDTH = 360;

describe.each([1, 2])('Stock in cartons and pieces at 360px, font scale %i', (font) => {
  // Since issue #680 one shop's stock on a phone is a card per item, the quantity large on its own line.
  it('Stock fits the phone and the quantity wraps in its card', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 900 }, hasTouch: true, isMobile: true });
    await page.goto(`${base}layout/stock-packs.html?screen=stock&font=${font}`);
    await page.getByText('46 Half cartons and 23 Pieces').waitFor();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(WIDTH);
    const quantities = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.stock-card-qty')].map((e) => {
        const card = e.closest('.stock-card')?.getBoundingClientRect();
        return { text: e.textContent ?? '', over: e.scrollWidth - e.clientWidth, right: e.getBoundingClientRect().right, card: card?.right ?? 0 };
      }),
    );
    expect(quantities.some((q) => q.text.includes('(1127 Pieces)'))).toBe(true);
    for (const q of quantities) {
      expect(q.over, q.text).toBeLessThanOrEqual(0);
      expect(q.right, q.text).toBeLessThanOrEqual(q.card + 0.5);
    }
    await page.close();
  });

  it('All branches on a phone fits, the total and each branch in packs inside its card', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 900 }, hasTouch: true, isMobile: true });
    await page.goto(`${base}layout/stock-packs.html?screen=all&font=${font}`);
    await page.getByText('46 Half cartons and 23 Pieces').first().waitFor();
    const show = page.getByRole('button', { name: 'Show branches' });
    while ((await show.count()) > 0) await show.first().click();
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const parts = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.rt-card')].flatMap((card) => {
        const box = card.getBoundingClientRect();
        return [...card.querySelectorAll<HTMLElement>('.rt-row, .rt-row > *, .qty-in-packs, .qty-total')].map((e) => ({
          what: `${e.tagName.toLowerCase()}.${e.className} ${e.textContent ?? ''}`, right: e.getBoundingClientRect().right, card: box.right, over: e.scrollWidth - e.clientWidth,
        }));
      }),
    );
    expect(parts.some((p) => p.what.includes('(1127 Pieces)'))).toBe(true);
    for (const p of parts) {
      expect(p.right, p.what).toBeLessThanOrEqual(p.card + 0.5);
      expect(p.over, p.what).toBeLessThanOrEqual(0);
    }
    await page.close();
  });

  it.each(['count', 'move'])('%s fits the phone with both boxes inside the card', async (screen) => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 900 }, hasTouch: true, isMobile: true });
    await page.goto(`${base}layout/stock-packs.html?screen=${screen}&font=${font}`);
    const id = screen === 'count' ? 'count-p1' : 'tq-0';
    await page.locator(`#${id}-packs`).waitFor();
    if (screen === 'count') {
      await page.locator(`#${id}-packs`).fill('46');
      await page.locator(`#${id}`).fill('23');
    }
    await page.getByText('(1127 Pieces)').waitFor();
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const parts = await page.evaluate((line) => {
      const card = document.getElementById(line)?.closest('.line-card');
      const box = card?.getBoundingClientRect();
      return {
        card: box ? { left: box.left, right: box.right } : null,
        parts: [...(card?.querySelectorAll<HTMLElement>('.count-part, .count-part > *, .count-entry button, .count-total') ?? [])].map((e) => {
          const r = e.getBoundingClientRect();
          return { what: `${e.tagName.toLowerCase()}.${e.className} ${e.id}`.trim(), left: r.left, right: r.right, height: r.height, over: e.scrollWidth - e.clientWidth, tag: e.tagName };
        }),
      };
    }, id);
    expect(parts.card).not.toBeNull();
    expect(parts.card?.right ?? Infinity).toBeLessThanOrEqual(WIDTH);
    expect(parts.parts.filter((p) => p.tag === 'INPUT')).toHaveLength(2);
    for (const p of parts.parts) {
      expect(p.right, p.what).toBeLessThanOrEqual((parts.card?.right ?? 0) + 0.5);
      expect(p.left, p.what).toBeGreaterThanOrEqual((parts.card?.left ?? 0) - 0.5);
      expect(p.over, p.what).toBeLessThanOrEqual(0);
      if (p.tag === 'INPUT' || p.tag === 'BUTTON') expect(p.height, p.what).toBeGreaterThanOrEqual(48);
    }
    await page.close();
  });
});
