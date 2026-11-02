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

  it('opens WhatsApp with the receipt link for a typed number', async () => {
    const page = await open(font, theme);
    await page.evaluate(() => {
      (window as unknown as { opened: string[] }).opened = [];
      window.open = (url?: string | URL) => {
        (window as unknown as { opened: string[] }).opened.push(String(url));
        return null;
      };
    });
    await page.getByLabel("Customer's WhatsApp number").fill('0700 000 072');
    await page.getByRole('button', { name: 'WhatsApp customer' }).click();
    const opened = await page.evaluate(() => (window as unknown as { opened: string[] }).opened);
    expect(opened).toHaveLength(1);
    expect(opened[0]?.startsWith('https://wa.me/256700000072?text=')).toBe(true);
    expect(decodeURIComponent(opened[0]?.split('?text=')[1] ?? '')).toContain('See your receipt: https://example.test/abcdefghijklmnopqrstuvwxyz');
    expect(decodeURIComponent(opened[0] ?? '')).toContain('Customer: Test Customer 71');
    await page.close();
  });

  // JUSTIFICATION-A3: a new test case for review item 1 of #762, the tap path a phone takes when the link is not made yet.
  it('opens the chat window inside the tap when the link is not made yet, and sends it to WhatsApp afterwards', async () => {
    const page = await open(font, theme, '&nolink=1');
    await page.evaluate(() => {
      const w = window as unknown as { opened: string[]; chat: { opener: unknown; location: { href: string } } };
      w.opened = [];
      w.chat = { opener: window, location: { href: '' } };
      window.open = ((url?: string | URL) => {
        w.opened.push(String(url));
        return w.chat;
      }) as unknown as typeof window.open;
    });
    await page.getByLabel("Customer's WhatsApp number").fill('0700 000 073');
    // The window is asked for in the same task as the tap, before any request is answered.
    const inTap = await page.evaluate(() => {
      [...document.querySelectorAll('button')].find((b) => b.textContent === 'WhatsApp customer')?.click();
      return [...(window as unknown as { opened: string[] }).opened];
    });
    expect(inTap).toEqual(['']);
    // No server answers here, so the link cannot be made: the details still go, with a note saying so.
    await page.getByText('The link to the receipt could not be made', { exact: false }).waitFor();
    const href = await page.evaluate(() => (window as unknown as { chat: { location: { href: string } } }).chat.location.href);
    expect(href.startsWith('https://wa.me/256700000073?text=')).toBe(true);
    expect(decodeURIComponent(href)).not.toContain('See your receipt');
    await page.close();
  });
});
