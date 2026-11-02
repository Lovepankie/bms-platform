import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The payment link panel on a phone 412 wide (issue #231), at the default and a large font size: the QR code, the open and copy
// actions and "Send the link by text message" stay inside the phone, the send button is a full 48px target, and once the link is
// sent the plain sentence that says so wraps inside the card. Nothing scrolls sideways, the card has one border width all round and
// no gradient, and the amount uses tabular figures.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;
const INTENT = '22222222-2222-4222-8222-222222222231';

async function open(font: number): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  // The page polls its payment and sends the link through the API: answered here with invented values.
  await page.route(`**/api/v1/payments/intents/${INTENT}`, (route) =>
    route.fulfill({
      json: {
        id: INTENT,
        status: 'pending',
        amount_minor: 123456789,
        currency: 'UGX',
        payer_phone: '********0231',
        redirect_url: 'https://cybqa.pesapal.com/pesapaliframe/PesapalIframe3/Index?OrderTrackingId=00000000-0000-4000-8000-000000000231',
        flags: [],
      },
    }),
  );
  await page.route(`**/api/v1/payments/intents/${INTENT}/sms`, (route) =>
    route.fulfill({ json: { intent_id: INTENT, already_sent: false, expires_at: '2026-11-02T10:00:00Z' } }),
  );
  return openLayout(page, `layout/collect-status.html?font=${font}`, page.getByRole('button', { name: 'Send the link by text message' }));
}

const pageWidth = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth);

async function card(page: Page) {
  return page.evaluate(() => {
    const card = document.querySelector<HTMLElement>('section[aria-label="Payment status"]');
    if (!card) return null;
    const box = card.getBoundingClientRect();
    const style = getComputedStyle(card);
    return {
      right: box.right,
      over: [...card.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 0.5).map((e) => e.tagName),
      edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
      gradient: style.backgroundImage !== 'none',
      tabular: getComputedStyle(card.querySelector('strong') ?? card).fontVariantNumeric.includes('tabular-nums'),
    };
  });
}

describe.each([1, 2])('The payment link panel at 412px, font scale %i', (font) => {
  it('keeps the QR code, the actions and the send button inside the phone', async () => {
    const page = await open(font);
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const c = await card(page);
    expect(c?.right ?? Infinity, 'card inside the phone').toBeLessThanOrEqual(WIDTH);
    expect(c?.over, 'nothing past the card').toEqual([]);
    expect(c?.edges, 'no side or top stripe: one border width all round').toBe(1);
    expect(c?.gradient, 'no gradient').toBe(false);
    expect(c?.tabular, 'tabular figures').toBe(true);
    const send = page.getByRole('button', { name: 'Send the link by text message' });
    expect((await send.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(48);
    await page.close();
  });

  it('says the link was sent in a sentence that wraps inside the card', async () => {
    const page = await open(font);
    await page.getByRole('button', { name: 'Send the link by text message' }).click();
    await page.getByText('The link was sent by text message to ********0231.').waitFor();
    expect(await page.getByRole('button', { name: 'Send the link by text message' }).count()).toBe(0);
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const c = await card(page);
    expect(c?.over, 'nothing past the card').toEqual([]);
    await page.close();
  });
});
