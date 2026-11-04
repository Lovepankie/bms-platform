import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// Call, Text and WhatsApp (issue #817) in Chromium on a phone 412 wide, at the default and a large font size, and on a laptop
// 1366 wide: on a credit sale, a supplier, a customer who owes and an advance (layout/contact-links.html). Each link is a target
// of at least 48 by 48px inside the phone, the three stand 8px apart, none is stretched on a laptop (as wide as its word, never
// over --button-max), and nothing scrolls sideways.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const TAP = 48;
const GAP = 8;
const MAX = 320;
const PLACES = ['Test Buyer 01 with a long name', 'Test Supplier 01', 'Test Buyer 02', 'Test Owner 01'];

type Box = { x: number; y: number; width: number; height: number };

async function measure(width: number, height: number, font: number) {
  const page = await browser.newPage({ viewport: { width, height }, hasTouch: width < 1024, isMobile: width < 1024 });
  await openLayout(page, 'layout/contact-links.html', page.getByRole('link', { name: 'Call Test Owner 01' }));
  await page.evaluate((scale) => { document.documentElement.style.fontSize = `${16 * scale}px`; }, font);
  await settle(page.locator('[data-testid="contact-links"]'));
  const places: { who: string; boxes: Box[] }[] = [];
  for (const who of PLACES) {
    const boxes: Box[] = [];
    for (const verb of ['Call', 'Text', 'WhatsApp']) {
      const box = await page.getByRole('link', { name: `${verb} ${who}`, exact: true }).boundingBox();
      expect(box, `${verb} ${who}`).not.toBeNull();
      boxes.push(box!);
    }
    places.push({ who, boxes });
  }
  const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  await page.close();
  return { places, scrollWidth };
}

function apart(a: Box, b: Box): number {
  const dx = Math.max(b.x - (a.x + a.width), a.x - (b.x + b.width));
  const dy = Math.max(b.y - (a.y + a.height), a.y - (b.y + b.height));
  return Math.max(dx, dy);
}

describe.each([1, 1.5])('Call, Text and WhatsApp at 412px, font scale %s (#817)', (font) => {
  it('draws each link 48px or more, inside the phone, 8px apart, with no sideways scroll', async () => {
    const { places, scrollWidth } = await measure(412, 915, font);
    for (const { who, boxes } of places) {
      for (const b of boxes) {
        expect(b.x, who).toBeGreaterThanOrEqual(0);
        expect(b.x + b.width, who).toBeLessThanOrEqual(412);
        expect(b.height, who).toBeGreaterThanOrEqual(TAP);
        expect(b.width, who).toBeGreaterThanOrEqual(TAP);
      }
      boxes.forEach((a, i) => boxes.slice(i + 1).forEach((b) => expect(apart(a, b), who).toBeGreaterThanOrEqual(GAP - 0.5)));
    }
    expect(scrollWidth, 'page width').toBeLessThanOrEqual(412);
  });
});

describe('Call, Text and WhatsApp at 1366px (#817)', () => {
  it('keeps each link as wide as its word, in one row 8px apart, 48px tall', async () => {
    const { places, scrollWidth } = await measure(1366, 800, 1);
    for (const { who, boxes } of places) {
      for (const b of boxes) {
        expect(b.width, who).toBeLessThan(MAX / 2);
        expect(b.height, who).toBeGreaterThanOrEqual(TAP);
      }
      expect(new Set(boxes.map((b) => Math.round(b.y))).size, `${who} one row`).toBe(1);
      for (const [a, b] of boxes.slice(1).map((next, i) => [boxes[i]!, next] as const)) {
        const gap = b.x - (a.x + a.width);
        expect(gap, who).toBeGreaterThanOrEqual(GAP - 0.5);
        expect(gap, who).toBeLessThanOrEqual(GAP + 0.5);
      }
    }
    expect(scrollWidth, 'page width').toBeLessThanOrEqual(1366);
  });
});
