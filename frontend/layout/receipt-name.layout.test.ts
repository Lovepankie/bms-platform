import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The customer's name on a cash receipt and the receipt link (issue #760) on a phone 412 wide, at the default and a large font
// size, light and dark: Add customer name is one full 48px target, the name and phone fields and their buttons are 48px and
// inside the screen, no card has a coloured stripe or a gradient, nothing scrolls sideways, and WhatsApp customer opens a
// message that carries the link to the receipt PDF.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

async function open(font: number, theme: string, extra = ''): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  return openLayout(page, `layout/receipt-name.html?font=${font}&theme=${theme}${extra}`, page.getByRole('button', { name: 'WhatsApp customer' }));
}

/** Every visible button and text field: its height, and whether it stays inside the screen. */
async function targets(page: Page) {
  return page.evaluate(() =>
    [...document.querySelectorAll<HTMLElement>('button, input:not([type="checkbox"])')]
      .filter((e) => e.offsetParent !== null)
      .map((e) => {
        const box = e.getBoundingClientRect();
        return { name: e.textContent?.trim() || e.id, height: box.height, left: box.left, right: box.right };
      }),
  );
}

/** The cards: one hairline width and one colour on every edge (no stripe), and no gradient. */
async function cards(page: Page) {
  return page.evaluate(() =>
    [...document.querySelectorAll<HTMLElement>('.rt-card, fieldset')].map((card) => {
      const style = getComputedStyle(card);
      return {
        edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
        colours: new Set([style.borderLeftColor, style.borderRightColor, style.borderTopColor, style.borderBottomColor]).size,
        gradient: style.backgroundImage.includes('gradient'),
      };
    }),
  );
}

describe.each([
  [1, 'light'],
  [2, 'light'],
  [1, 'dark'],
])('Receipt name and link at 412px, font scale %i, %s', (font, theme) => {
  it('shows Add customer name as one 48px target and the name fields only after it is tapped', async () => {
    const page = await open(font, theme);
    expect(await page.getByLabel('Customer name (optional)').count()).toBe(0);
    const add = page.getByRole('button', { name: 'Add customer name' });
    expect((await add.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(48);
    await add.click();
    expect(await page.getByLabel('Customer name (optional)').isVisible()).toBe(true);
    expect(await page.getByLabel('Phone (optional)').isVisible()).toBe(true);
    await page.close();
  });

  it('keeps every target 48px and inside the screen, with no stripe, no gradient and no sideways scroll', async () => {
    const page = await open(font, theme, '&open=1');
    expect(await page.getByLabel('Customer name (optional)').inputValue()).toBe('Test Customer 71');
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    for (const t of await targets(page)) {
      expect(t.height, t.name).toBeGreaterThanOrEqual(48);
      expect(t.left, t.name).toBeGreaterThanOrEqual(0);
      expect(t.right, t.name).toBeLessThanOrEqual(WIDTH);
    }
    for (const c of await cards(page)) {
      expect(c.edges).toBe(1);
      expect(c.colours).toBe(1);
      expect(c.gradient).toBe(false);
    }
    await page.close();
  });

  it('opens the window inside the tap, before the link is made, and sends it to WhatsApp with the link', async () => {
    const page = await open(font, theme);
    // The link is not in the page's cache: the tap asks for it. The window is opened in the click itself and its address
    // is set once the link comes back.
    await page.evaluate(() => {
      const w = window as unknown as { opened: string[]; chat: { opener: unknown; closed: boolean; location: { href: string } } };
      w.opened = [];
      w.chat = { opener: {}, closed: false, location: { href: '' } };
      window.open = (url?: string | URL) => {
        w.opened.push(String(url ?? ''));
        return w.chat as unknown as Window;
      };
    });
    await page.getByLabel("Customer's WhatsApp number").fill('0700 000 072');
    const button = page.getByRole('button', { name: 'WhatsApp customer' });
    await button.evaluate((b: HTMLElement) => {
      b.click();
      const w = window as unknown as { opened: string[]; inTap: string[] };
      w.inTap = [...w.opened];
    });
    expect(await page.evaluate(() => (window as unknown as { inTap: string[] }).inTap), 'opened within the tap').toEqual(['']);
    await page.waitForFunction(() => (window as unknown as { chat: { location: { href: string } } }).chat.location.href !== '');
    const chat = await page.evaluate(() => (window as unknown as { chat: { opener: unknown; location: { href: string } } }).chat);
    expect(chat.opener).toBeNull();
    expect(chat.location.href.startsWith('https://wa.me/256700000072?text=')).toBe(true);
    expect(decodeURIComponent(chat.location.href.split('?text=')[1] ?? '')).toContain('See your receipt: https://example.test/abcdefghijklmnopqrstuvwxyz');
    expect(decodeURIComponent(chat.location.href)).toContain('Customer: Test Customer 71');
    expect(await page.evaluate(() => (window as unknown as { opened: string[] }).opened)).toEqual(['']);
    await page.close();
  });
});
