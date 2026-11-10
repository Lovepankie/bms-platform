import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// The import screens on a phone 412 wide (issue #141), at the default and a large font size: the imports, one import with a long
// file name and money past a billion, one brought in and waiting for the sign-off, the problems by kind, and the operator's run
// queue and one run being brought in. Nothing scrolls sideways, there is at most one primary action, every button is a 48px
// target, and no card has a gradient or a stripe of colour on one edge.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const WIDTH = 412;

const SCREENS: [string, string][] = [
  ['runs', 'Start a new import'],
  ['run', 'Mark it ready'],
  ['committed', 'Compare and sign'],
  ['problems', 'Not the right kind of value (3)'],
  ['queue', 'fab-hardware-mbarara'],
  ['oprun', 'Stop the import'],
];

async function open(screen: string, ready: string, font: number): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: 900 }, hasTouch: true, isMobile: true });
  await openLayout(page, `layout/import-screens.html?screen=${screen}&font=${font}`, page.getByText(ready).first());
  await settle(page.locator('body'));
  return page;
}

describe.each([1, 2])('the import screens at 412px, font scale %i', (font) => {
  it.each(SCREENS)('%s stays inside the phone with at most one primary action and full targets', async (screen, ready) => {
    const page = await open(screen, ready, font);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    const found = await page.evaluate(() => {
      const shown = (e: Element) => (e as HTMLElement).offsetParent !== null;
      const buttons = [...document.querySelectorAll('main button, main a.btn')].filter(shown).map((b) => b.getBoundingClientRect());
      const cards = [...document.querySelectorAll<HTMLElement>('main section')].map((c) => {
        const style = getComputedStyle(c);
        return { gradient: style.backgroundImage !== 'none', edges: new Set([style.borderLeftWidth, style.borderRightWidth, style.borderTopWidth, style.borderBottomWidth]).size };
      });
      return {
        // A label squeezed by a long value reads one letter a line (seen on the first draft of the operator's facts).
        squeezed: [...document.querySelectorAll('main dt')].filter((dt) => dt.getBoundingClientRect().width < 120).length,
        // Every figure lines up digit for digit (rule 8): money and counts in tabular figures.
        proportional: [...document.querySelectorAll('main dd')].filter((dd) => !getComputedStyle(dd).fontVariantNumeric.includes('tabular-nums')).length,
        primaries: [...document.querySelectorAll('main .btn-primary')].filter(shown).length,
        small: buttons.filter((b) => b.height < 48 || b.width < 48).length,
        outside: buttons.filter((b) => b.right > 412.5).length,
        cards,
      };
    });
    expect(found.squeezed, 'labels squeezed by their values').toBe(0);
    expect(found.proportional, 'figures not in tabular figures').toBe(0);
    expect(found.primaries, 'primary actions').toBeLessThanOrEqual(1);
    expect(found.small, 'buttons under 48px').toBe(0);
    expect(found.outside, 'buttons past the edge').toBe(0);
    for (const card of found.cards) {
      expect(card.gradient, 'a card with a gradient').toBe(false);
      expect(card.edges, 'one border width all round').toBe(1);
    }
    await page.close();
  });
});
