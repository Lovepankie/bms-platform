import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The report centre on a phone 412 wide (issue #229), at the default and a large font size: the monthly pack card, the report
// cards with long titles and descriptions, and the runs with a pack's two downloads and a long failure stay inside the phone;
// nothing scrolls sideways; the cards have one border width all round, no gradient and no purple; every action is a 48px
// target; and a user with no run sees one line and one action.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

async function open(font: number, extra = ''): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
  const ready = extra.includes('empty') ? page.getByText('You have not run a report yet.') : page.getByText('Download Excel');
  return openLayout(page, `layout/reports.html?font=${font}${extra}`, ready);
}

const pageWidth = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth);

describe.each([1, 2])('The report centre at 412px, font scale %i', (font) => {
  it('keeps the pack card and every report card inside the phone, plain and tabular', async () => {
    const page = await open(font);
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const cards = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('.rp-pack, .rp-report')].map((card) => {
        const box = card.getBoundingClientRect();
        const style = getComputedStyle(card);
        const purple = (c: string) => {
          const m = /rgba?\((\d+), (\d+), (\d+)/.exec(c);
          if (!m) return false;
          const [r, g, b] = [Number(m[1]), Number(m[2]), Number(m[3])];
          return b > 120 && r > 90 && g < r - 30 && g < b - 30;
        };
        return {
          right: box.right,
          left: box.left,
          over: [...card.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 0.5).map((e) => e.tagName),
          edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size,
          gradient: style.backgroundImage !== 'none',
          purple: purple(style.backgroundColor) || purple(style.borderLeftColor) || purple(style.borderTopColor),
          tabular: style.fontVariantNumeric.includes('tabular-nums'),
        };
      }),
    );
    expect(cards.length, 'the pack and three reports').toBe(4);
    for (const card of cards) {
      expect(card.left, 'card inside the phone').toBeGreaterThanOrEqual(0);
      expect(card.right, 'card inside the phone').toBeLessThanOrEqual(WIDTH);
      expect(card.over, 'nothing past the card').toEqual([]);
      expect(card.edges, 'no side or top stripe: one border width all round').toBe(1);
      expect(card.gradient, 'no gradient').toBe(false);
      expect(card.purple, 'no purple').toBe(false);
      expect(card.tabular, 'tabular figures').toBe(true);
    }
    await page.close();
  });

  it('keeps an opened report form inside the phone', async () => {
    const page = await open(font);
    await page.locator('.rp-report summary').first().click();
    await page.getByRole('button', { name: 'Run report' }).first().waitFor();
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const form = await page.locator('.rp-report[open] form').boundingBox();
    expect((form?.x ?? 0) + (form?.width ?? 0)).toBeLessThanOrEqual(WIDTH);
    await page.close();
  });

  it('keeps the runs, a pack with two downloads and a long failure inside the phone, each action a full target', async () => {
    const page = await open(font);
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const rows = await page.evaluate(
      (width) =>
        [...document.querySelectorAll<HTMLElement>('.rp-runs tbody tr')].map((row) => {
          const box = row.getBoundingClientRect();
          return {
            right: box.right,
            over: [...row.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > width + 0.5).map((e) => e.tagName),
          };
        }),
      WIDTH,
    );
    expect(rows).toHaveLength(4);
    for (const row of rows) {
      expect(row.right, 'run inside the phone').toBeLessThanOrEqual(WIDTH);
      expect(row.over, 'nothing past the phone').toEqual([]);
    }
    for (const name of ['Download PDF', 'Download Excel', 'Run again', 'Cancel', 'Run the pack']) {
      const box = await page.getByRole('button', { name }).first().boundingBox();
      expect(box?.height ?? 0, `${name} is a full target`).toBeGreaterThanOrEqual(48);
    }
    await page.close();
  });

  it('shows one line and one action when there is no run yet', async () => {
    const page = await open(font, '&empty=1');
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    const section = page.locator('section[aria-label="Your reports"]');
    expect(await section.getByRole('button').count()).toBe(1);
    const action = section.getByRole('button', { name: 'Choose a report' });
    expect((await action.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(48);
    await page.close();
  });

  it('keeps the runs inside the phone in a wide fallback font, before the web font loads', async () => {
    // A runner that measures before Open Sans loads draws the system font; DejaVu Sans is wider and once put a run's
    // "(Excel)" 6px past the phone (review of #735).
    const page = await open(font);
    await page.addStyleTag({ content: '* { font-family: "DejaVu Sans", sans-serif !important; }' });
    await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
    expect(await pageWidth(page), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});
