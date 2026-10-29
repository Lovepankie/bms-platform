import react from '@vitejs/plugin-react';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { chromium, type Browser, type Page } from 'playwright';
import { createServer, type ViteDevServer } from 'vite';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

// My stock on a phone 412 wide (issue #680), at the default and a large font size: a card per item inside the page, the quantity
// larger than the prices, the money in tabular figures, Correct and the shop label full 48px targets, no stripe on a card, and no
// sideways scroll. Switching shop from the label shows the dark toast inside the screen. With LAYOUT_SHOTS=1 it saves the pictures
// of the pull request in docs/ui/shop-owner-clarity-680.

let server: ViteDevServer;
let browser: Browser;
let base = '';
const SHOTS = process.env.LAYOUT_SHOTS === '1' ? resolve(process.cwd(), '../docs/ui/shop-owner-clarity-680') : null;

beforeAll(async () => {
  server = await createServer({ configFile: false, root: process.cwd(), plugins: [react()], logLevel: 'error', server: { port: 0, host: '127.0.0.1' } });
  await server.listen();
  base = server.resolvedUrls?.local[0] ?? '';
  browser = await chromium.launch();
  if (SHOTS) mkdirSync(SHOTS, { recursive: true });
});

afterAll(async () => {
  await browser?.close();
  await server?.close();
});

const WIDTH = 412;

async function open(font: number, theme = 'light'): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  await page.goto(`${base}layout/my-stock.html?font=${font}&theme=${theme}`);
  await page.locator('.stock-card').first().waitFor();
  return page;
}

describe.each([1, 2])('My stock at 412px, font scale %i', (font) => {
  it('draws a card per item inside the phone, the quantity large and the prices small in tabular figures', async () => {
    const page = await open(font);
    if (SHOTS && font === 1) await page.screenshot({ path: `${SHOTS}/01-my-stock-cards-412.png`, fullPage: true });
    if (SHOTS && font === 2) await page.screenshot({ path: `${SHOTS}/03-my-stock-cards-large-font-412.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    expect(await page.locator('table').count()).toBe(0);
    expect(await page.getByText('Extensions').first().isVisible()).toBe(true);
    expect(await page.getByText('1 item, 8 piece, worth UGX 64,000 at selling price').isVisible()).toBe(true);
    const cards = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.stock-card')].map((card) => {
        const box = card.getBoundingClientRect();
        const style = getComputedStyle(card);
        const qty = card.querySelector<HTMLElement>('.stock-card-qty');
        const money = [...card.querySelectorAll<HTMLElement>('.hint .num')];
        const correct = card.querySelector<HTMLElement>('button');
        return {
          text: card.textContent ?? '',
          left: box.left,
          right: box.right,
          edges: [style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth],
          edgeColours: new Set([style.borderLeftColor, style.borderRightColor, style.borderTopColor, style.borderBottomColor]).size,
          qtySize: qty ? parseFloat(getComputedStyle(qty).fontSize) : 0,
          priceSize: money[0] ? parseFloat(getComputedStyle(money[0]).fontSize) : 0,
          tabular: money.length > 0 && money.every((m) => getComputedStyle(m).fontVariantNumeric.includes('tabular-nums')),
          qtyTabular: qty ? getComputedStyle(qty).fontVariantNumeric.includes('tabular-nums') : false,
          correctHeight: correct?.getBoundingClientRect().height ?? 0,
          over: [...card.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 0.5).map((e) => e.className),
        };
      }),
    );
    expect(cards).toHaveLength(3);
    expect(cards.some((c) => c.text.includes('In stock: 8 piece') && c.text.includes('Buying price UGX 6,000 · Selling price UGX 8,000'))).toBe(true);
    for (const c of cards) {
      expect(c.left, c.text).toBeGreaterThanOrEqual(0);
      expect(c.right, c.text).toBeLessThanOrEqual(WIDTH);
      expect(c.over, c.text).toEqual([]);
      // No coloured stripe: one hairline width and one colour on every edge.
      expect(new Set(c.edges).size, c.text).toBe(1);
      expect(c.edgeColours, c.text).toBe(1);
      expect(c.qtySize, c.text).toBeGreaterThan(c.priceSize);
      expect(c.tabular && c.qtyTabular, c.text).toBe(true);
      expect(c.correctHeight, c.text).toBeGreaterThanOrEqual(48);
    }
    await page.close();
  });

  it('names the shop under the title as a 48px target the switcher covers, and a switch shows the toast', async () => {
    const page = await open(font);
    const label = page.getByTestId('shop-here');
    expect(await label.textContent()).toContain('Test Branch A (BR1)');
    const boxes = await page.evaluate(() => {
      const shop = document.querySelector<HTMLElement>('.shop-here')?.getBoundingClientRect();
      const select = document.querySelector<HTMLElement>('.shop-here-select')?.getBoundingClientRect();
      const title = document.querySelector<HTMLElement>('main h1')?.getBoundingClientRect();
      return { shop: shop && { top: shop.top, height: shop.height, right: shop.right, width: shop.width }, select: select && { height: select.height, width: select.width }, titleBottom: title?.bottom ?? 0 };
    });
    expect(boxes.shop?.height ?? 0).toBeGreaterThanOrEqual(48);
    expect(boxes.shop?.right ?? Infinity).toBeLessThanOrEqual(WIDTH);
    // The select covers the label inside its 1px edge, so a tap anywhere on it opens the picker.
    expect(boxes.select?.height ?? 0).toBeGreaterThanOrEqual((boxes.shop?.height ?? 0) - 2);
    expect(boxes.select?.width ?? 0).toBeGreaterThanOrEqual((boxes.shop?.width ?? 0) - 2);
    expect(boxes.shop?.top ?? 0).toBeGreaterThanOrEqual(boxes.titleBottom);
    await page.getByRole('combobox', { name: 'Shop: Test Branch A (BR1). Switch shop' }).selectOption({ label: 'Test Branch B (BR2)' });
    const toast = page.getByRole('status').filter({ hasText: 'You are now in Test Branch B (BR2).' });
    await toast.waitFor();
    expect(await label.textContent()).toContain('Test Branch B (BR2)');
    const shape = await toast.evaluate((t) => {
      const box = t.getBoundingClientRect();
      const style = getComputedStyle(t);
      const probe = document.createElement('span');
      probe.style.color = 'var(--color-inverse)';
      document.body.append(probe);
      const inverse = getComputedStyle(probe).color;
      probe.remove();
      return { left: box.left, right: box.right, bottom: box.bottom, background: style.backgroundColor, inverse, edges: new Set([style.borderLeftColor, style.borderTopColor]).size };
    });
    expect(shape.background).toBe(shape.inverse);
    expect(shape.left).toBeGreaterThanOrEqual(0);
    expect(shape.right).toBeLessThanOrEqual(WIDTH);
    expect(shape.bottom).toBeLessThanOrEqual(915);
    if (SHOTS && font === 1) await page.screenshot({ path: `${SHOTS}/02-shop-switched-toast-412.png` });
    await page.close();
  });
});

it('shows one toast at a time: the switch gives way to a toast of the screen', async () => {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  await page.goto(`${base}layout/my-stock.html?screenToast=1`);
  await page.locator('.stock-card').first().waitFor();
  await page.getByRole('combobox', { name: /Switch shop/ }).selectOption({ label: 'Test Branch B (BR2)' });
  await page.locator('.shop-here-name', { hasText: 'Test Branch B (BR2)' }).waitFor();
  expect(await page.locator('.toast:visible').allTextContents()).toEqual(['Stock corrected.']);
  await page.close();
});

it('draws My stock and the shop label in the dark theme too', async () => {
  const page = await open(1, 'dark');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(WIDTH);
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/04-my-stock-cards-dark-412.png`, fullPage: true });
  await page.close();
});
