import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// My stock on a phone 412 wide (issue #680), at the default and a large font size: a card per item inside the page, the quantity
// larger than the prices, the money in tabular figures, Correct and the shop label full 48px targets, no stripe on a card, and no
// sideways scroll. Switching shop from the label shows the dark toast inside the screen. With LAYOUT_SHOTS=1 it saves the pictures
// of the pull request in docs/ui/shop-owner-clarity-680.

let browser: Browser;
const SHOTS = process.env.LAYOUT_SHOTS === '1' ? resolve(process.cwd(), '../docs/ui/shop-owner-clarity-680') : null;

beforeAll(async () => {
  browser = await chromium.launch();
  if (SHOTS) mkdirSync(SHOTS, { recursive: true });
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

async function open(font: number, theme = 'light'): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  return openLayout(page, `layout/my-stock.html?font=${font}&theme=${theme}`, page.locator('.stock-card'));
}

describe.each([1, 2])('My stock at 412px, font scale %i', (font) => {
  it('draws a card per item inside the phone, the quantity large and the prices small in tabular figures', async () => {
    const page = await open(font);
    if (SHOTS && font === 1) await page.screenshot({ path: `${SHOTS}/01-my-stock-cards-412.png`, fullPage: true });
    if (SHOTS && font === 2) await page.screenshot({ path: `${SHOTS}/03-my-stock-cards-large-font-412.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    expect(await page.locator('table').count()).toBe(0);
    expect(await page.getByText('Extensions').first().isVisible()).toBe(true);
    expect(await page.getByText('1 item, 8 pieces, worth UGX 64,000 at selling price').isVisible()).toBe(true);
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
    expect(cards.some((c) => c.text.includes('In stock: 8 pieces') && c.text.includes('Buying price UGX 6,000 · Selling price UGX 8,000'))).toBe(true);
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

  it('names the shop above the page content as a 48px target the switcher covers, and a switch shows the toast', async () => {
    const page = await open(font);
    const label = page.getByTestId('shop-here');
    expect(await label.textContent()).toContain('Test Branch A (BR1)');
    const boxes = await page.evaluate(() => {
      const shop = document.querySelector<HTMLElement>('.shop-here')?.getBoundingClientRect();
      const select = document.querySelector<HTMLElement>('.shop-here-select')?.getBoundingClientRect();
      const title = document.querySelector<HTMLElement>('main h1')?.getBoundingClientRect();
      return { shop: shop && { bottom: shop.bottom, height: shop.height, right: shop.right, width: shop.width }, select: select && { height: select.height, width: select.width }, titleTop: title?.top ?? 0 };
    });
    expect(boxes.shop?.height ?? 0).toBeGreaterThanOrEqual(48);
    expect(boxes.shop?.right ?? Infinity).toBeLessThanOrEqual(WIDTH);
    // The select covers the label inside its 1px edge, so a tap anywhere on it opens the picker.
    expect(boxes.select?.height ?? 0).toBeGreaterThanOrEqual((boxes.shop?.height ?? 0) - 2);
    expect(boxes.select?.width ?? 0).toBeGreaterThanOrEqual((boxes.shop?.width ?? 0) - 2);
    expect(boxes.shop?.bottom ?? Infinity).toBeLessThanOrEqual(boxes.titleTop);
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

it('shows one toast at a time: the switch replaces a toast of the screen', async () => {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  await openLayout(page, 'layout/my-stock.html?screenToast=1', page.locator('.stock-card'));
  expect(await page.locator('.toast:visible').allTextContents()).toEqual(['Stock corrected.']);
  await page.getByRole('combobox', { name: /Switch shop/ }).selectOption({ label: 'Test Branch B (BR2)' });
  await page.locator('.shop-here-name', { hasText: 'Test Branch B (BR2)' }).waitFor();
  expect(await page.locator('.toast:visible').allTextContents()).toEqual(['You are now in Test Branch B (BR2).']);
  await page.close();
});

// JUSTIFICATION-A3: the 412px layout case issue #807 asks for; a new test, not a mechanism.
// Issue #807: every shop's quantity on each card, this shop first and larger, inside the phone at both font sizes, the
// quantities in tabular figures, no stripe and no Switch button.
describe.each([1, 2])('My stock with every shop at 412px, font scale %i', (font) => {
  it('lists each shop inside its card with no Switch button', async () => {
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
    await openLayout(page, `layout/my-stock.html?font=${font}&shops=1`, page.locator('.shop-qtys'));
    if (SHOTS && font === 1) await page.screenshot({ path: `${SHOTS}/05-my-stock-every-shop-412.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    expect(await page.getByRole('button', { name: /Switch to/ }).count()).toBe(0);
    const lists = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.stock-card')].map((card) => {
        const box = card.getBoundingClientRect();
        const lines = [...card.querySelectorAll<HTMLElement>('.shop-qtys li')];
        const style = getComputedStyle(card);
        return {
          text: lines.map((l) => l.textContent ?? ''),
          inside: lines.every((l) => l.getBoundingClientRect().right <= box.right + 0.5 && l.getBoundingClientRect().left >= box.left - 0.5),
          tabular: [...card.querySelectorAll<HTMLElement>('.shop-qtys .num')].every((n) => getComputedStyle(n).fontVariantNumeric.includes('tabular-nums')),
          hereLarger: lines[0] !== undefined && lines[1] !== undefined && parseFloat(getComputedStyle(lines[0]).fontSize) > parseFloat(getComputedStyle(lines[1]).fontSize),
          edges: new Set([style.borderLeftColor, style.borderRightColor, style.borderTopColor, style.borderBottomColor]).size,
        };
      }),
    );
    expect(lists).toHaveLength(3);
    for (const l of lists) {
      expect(l.text).toHaveLength(3);
      expect(l.text[0]).toMatch(/^In stock here: /);
      expect(l.inside, l.text.join(' / ')).toBe(true);
      expect(l.tabular, l.text.join(' / ')).toBe(true);
      expect(l.hereLarger, l.text.join(' / ')).toBe(true);
      expect(l.edges).toBe(1);
    }
    const lines = lists.flatMap((l) => l.text);
    expect(lines).toContain('Another shop with a rather long name on the main road: 0 pieces');
    expect(lines).toContain('Third Test Shop: -1 piece Below zero');
    // Issue #813: each card opens its item page with a full size target inside the card.
    const opens = await page.locator('.stock-card .item-page-link').all();
    expect(opens).toHaveLength(3);
    for (const open of opens) {
      const box = await open.boundingBox();
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(48);
      expect((box?.x ?? 0) + (box?.width ?? 0)).toBeLessThanOrEqual(WIDTH);
    }
    await page.close();
  });
});

it('draws My stock and the shop label in the dark theme too', async () => {
  const page = await open(1, 'dark');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(WIDTH);
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/04-my-stock-cards-dark-412.png`, fullPage: true });
  await page.close();
});
