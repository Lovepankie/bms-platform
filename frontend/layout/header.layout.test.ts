import { chromium, type Browser } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// The staff top bar laid out in Chromium on a phone (issue #603): with the Ask button beside Search, at 360, 390 and 412 wide, at
// the default and a large font size, with Ask BMS on and off, no control overlaps another, each is a target of at least 48px,
// the long branch title is cut with an ellipsis inside its own space, the tours' anchors in the bar are on screen, and nothing scrolls sideways. The page
// is layout/header.html under the app's own stylesheets. Since issue #605 the bar also holds the sync indicator, and below
// 430px the controls sit closer, Ask is its icon and the sync indicator its dot and the number waiting, in every sync state, so the
// business and the branch each show their first word and the start of the second (the accepted minimum at font scale 2 is below).
// Since issue #631 Refresh is in the bar again beside Search, its icon alone, and the same minimum holds.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

type Box = { x: number; y: number; width: number; height: number };
const overlap = (a: Box, b: Box) => Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x) > 0.5 && Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y) > 0.5;

/**
 * The characters of a title line a person sees: left of the nearest edge that clips it (its own box or an ancestor's with
 * overflow other than visible), of the chevron inside it, and of the ellipsis when it is cut.
 */
function visibleText(el: Element): string {
  const cut = el.scrollWidth > el.clientWidth;
  let right = Infinity;
  for (let box: Element | null = el; box && box !== document.body; box = box.parentElement) {
    if (getComputedStyle(box).overflowX !== 'visible') right = Math.min(right, box.getBoundingClientRect().right);
  }
  const chevron = el.querySelector('.top-title-chevron')?.getBoundingClientRect();
  if (chevron) right = Math.min(right, chevron.left);
  if (cut) {
    const probe = document.createElement('span');
    probe.textContent = '…';
    el.appendChild(probe);
    right -= probe.getBoundingClientRect().width;
    probe.remove();
  }
  const range = document.createRange();
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  let seen = '';
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node.textContent ?? '';
    for (let i = 0; i < text.length; i++) {
      range.setStart(node, i);
      range.setEnd(node, i + 1);
      if (range.getBoundingClientRect().right > right + 0.5) return seen;
      seen += text[i];
    }
  }
  return seen;
}

// What a person must see of "Tumaini Traders" over "Store 1 140025 (BR1)". At the default size, the first word and the start of
// the second. At font scale 2 (a phone's large text setting) the 48px targets and the text both double, and the first word fits
// only from 412px with nothing waiting, so the accepted minimum there is the first two letters of each name ("Tu", "St"); a third
// letter fits on one machine's fonts and not on CI's.
const MINIMUM: Record<number, { business: RegExp; branch: RegExp }> = {
  1: { business: /^Tumaini T/, branch: /^Store 1/ },
  2: { business: /^Tu/, branch: /^St/ },
};

// The sync indicator in each state it can show: below 430px it is the dot and the number waiting alone, its name the words.
const SYNC = [
  ['online', 0, 'Online', ''],
  ['online', 3, 'Online, 3 waiting to sync', '3'],
  ['offline', 0, 'Offline', ''],
  ['offline', 3, 'Offline, 3 waiting to sync', '3'],
  ['syncing', 3, 'Syncing, 3 waiting to sync', '3'],
] as const;

describe.each([360, 390, 412])('the business and the branch at %ipx wide', (width) => {
  describe.each([1, 2])('at font scale %i', (font) => {
    it.each([...SYNC.map(([sync, waiting, name, shows]) => ['on', sync, waiting, name, shows] as const), ['off', 'online', 0, 'Online', ''] as const])(
      'stay readable with Ask BMS %s and the sync indicator %s with %i waiting',
      async (ask, sync, waiting, name, shows) => {
        const page = await browser.newPage({ viewport: { width, height: 700 }, hasTouch: true, isMobile: true });
        const names = `branch=${encodeURIComponent('Store 1 140025')}&business=${encodeURIComponent('Tumaini Traders')}`;
        await openLayout(page, `layout/header.html?font=${font}&ask=${ask === 'on' ? 1 : 0}&sync=${sync}&waiting=${waiting}&${names}`, page.locator('.staff-bar-top .ask-open'));
        const button = page.locator('.staff-bar-top .sync-button');
        await button.waitFor();
        // Read only once the fonts are in and what is asserted measures the same twice in a row (issue #775): the title,
        // whose visible text is read, and the whole bar, whose 48px targets and edges are.
        await settle(page.locator('.staff-bar-top .top-title'));
        await settle(page.locator('.staff-bar-top'));
        // The business name comes from the branding of this page's host; without it the title would show the platform's name.
        expect(await page.locator('.top-title-business').textContent()).toBe('Tumaini Traders');
        // Ask is its icon and the sync indicator its dot and number, both 48px targets that keep their names.
        expect(await page.locator('.staff-bar-top .ask-open-word').isVisible()).toBe(false);
        expect(await button.getAttribute('aria-label')).toBe(name);
        expect((await button.innerText()).trim()).toBe(shows);
        const target = (await button.boundingBox()) as Box;
        expect(target.width).toBeGreaterThanOrEqual(48);
        expect(target.height).toBeGreaterThanOrEqual(48);
        const seen = { business: await page.locator('.top-title-business').evaluate(visibleText), branch: await page.locator('.top-title-branch').evaluate(visibleText) };
        expect(seen.business).toMatch(MINIMUM[font]?.business ?? /^/);
        expect(seen.branch).toMatch(MINIMUM[font]?.branch ?? /^/);
        for (const box of await Promise.all((await page.locator('.staff-bar-top > *').all()).map((c) => c.boundingBox()))) {
          if (box && box.width > 0) expect(box.x + box.width).toBeLessThanOrEqual(width + 0.5);
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
        await page.close();
      },
    );
  });
});

describe.each([360, 390, 412])('the top bar at %ipx wide', (width) => {
  describe.each([1, 2])('at font scale %i', (font) => {
    it.each([
      ['on', '1'],
      ['off', '0'],
    ])('fits the Ask button with Ask BMS %s, every control 48px and on its own space', async (_state, ask) => {
      const page = await browser.newPage({ viewport: { width, height: 700 }, hasTouch: true, isMobile: true });
      const button = page.locator('.staff-bar-top .ask-open');
      await openLayout(page, `layout/header.html?font=${font}&ask=${ask}`, button);
      await settle(page.locator('.staff-bar-top'));
      expect(await button.getAttribute('aria-label')).toBe(ask === '1' ? 'Ask BMS, New' : 'How do I?, New');
      // Refresh is in the bar beside Search (issue #631), its icon alone, named Refresh.
      const refresh = page.locator('.staff-bar-top .refresh-button');
      expect(await refresh.getAttribute('aria-label')).toBe('Refresh');
      expect(await page.locator('.staff-bar-top .refresh-label').isVisible()).toBe(false);
      // Its word stays inside the button, whatever the width and the font size.
      expect(await button.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
      const controls = await Promise.all(
        (await page.locator('.staff-bar-top > *').all()).map(async (c) => ({ cls: (await c.getAttribute('class')) ?? '', box: (await c.boundingBox()) as Box | null })),
      );
      const shown = controls.filter((c): c is { cls: string; box: Box } => c.box !== null && c.box.width > 0);
      for (const [i, c] of shown.entries()) {
        expect(c.box.x, `${c.cls} starts on screen`).toBeGreaterThanOrEqual(-0.5);
        expect(c.box.x + c.box.width, `${c.cls} ends on screen`).toBeLessThanOrEqual(width + 0.5);
        for (const other of shown.slice(i + 1)) expect(overlap(c.box, other.box), `${c.cls} over ${other.cls}`).toBe(false);
      }
      for (const target of ['.menu-button', '.sync-button', '.find-open', '.refresh-button', '.ask-open', '.top-menu-button']) {
        const box = (await page.locator(`.staff-bar-top ${target}`).first().boundingBox()) as Box;
        expect(box.width, `${target} width`).toBeGreaterThanOrEqual(48);
        expect(box.height, `${target} height`).toBeGreaterThanOrEqual(48);
      }
      for (const anchor of ['branch-picker', 'feature-search', 'refresh', 'top-menu']) {
        const box = (await page.locator(`[data-tour="${anchor}"]`).first().boundingBox()) as Box;
        expect(box.width, `${anchor} is drawn`).toBeGreaterThan(0);
        expect(box.x + box.width, `${anchor} is on screen`).toBeLessThanOrEqual(width + 0.5);
      }
      // The long branch name is cut with an ellipsis on one line, inside the title, never under Search or Ask.
      const title = await page.locator('.top-title-branch').evaluate((el) => {
        const style = getComputedStyle(el);
        const own = el.getBoundingClientRect();
        const box = el.closest('.top-title')?.getBoundingClientRect();
        return { cut: el.scrollWidth > el.clientWidth, ellipsis: style.textOverflow, wrap: style.whiteSpace, right: own.right, titleRight: box?.right ?? 0 };
      });
      expect(title).toMatchObject({ cut: true, ellipsis: 'ellipsis', wrap: 'nowrap' });
      expect(title.right).toBeLessThanOrEqual(title.titleRight + 0.5);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      await page.close();
    });
  });
});

// JUSTIFICATION-A3: a new layout case for issue #763 (the offline banner at 360 and 412px) after the top bar cases it extends.
// Issue #763: offline, the banner under the bar is the page's one offline notice, with the time of the figures, wrapped inside
// the screen at the default and a large font size, and the bar's Refresh does not repeat it.
describe.each([360, 412])('the offline banner at %ipx wide', (width) => {
  it.each([1, 2])('fits at font scale %i as the only offline notice', async (font) => {
    const page = await browser.newPage({ viewport: { width, height: 700 }, hasTouch: true, isMobile: true });
    const banner = page.locator('.offline-banner');
    await openLayout(page, `layout/header.html?font=${font}&sync=offline`, banner);
    await settle(banner);
    expect(await banner.count()).toBe(1);
    expect(await banner.textContent()).toMatch(/^You are offline\. You can still sell; your sales will send when you are back online\. Figures are from \d{1,2} [A-Z][a-z]{2} at \d\d:\d\d\.$/);
    const box = (await banner.boundingBox()) as Box;
    expect(box.x).toBeGreaterThanOrEqual(-0.5);
    expect(box.x + box.width).toBeLessThanOrEqual(width + 0.5);
    expect(await banner.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    // The time of the figures is said once on the page: not again beside Refresh or anywhere else.
    expect(await page.getByText(/Figures are from|Showing the figures/).count()).toBe(1);
    // One notice: the dot's own word Offline (ADR-067) is a status, not a second notice.
    expect(await page.getByText(/You are offline/).count()).toBe(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.close();
  });
});
