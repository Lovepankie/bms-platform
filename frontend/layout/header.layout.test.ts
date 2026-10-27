import react from '@vitejs/plugin-react';
import { chromium, type Browser } from 'playwright';
import { createServer, type ViteDevServer } from 'vite';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { helpCorpus } from '../vite.config';

// The staff top bar laid out in Chromium on a phone (issue #603): with the Ask button beside Search, at 360, 390 and 412 wide, at
// the default and a large font size, with Ask BMS on and off, no control overlaps another, each is a target of at least 48px,
// the long branch title is cut with an ellipsis inside its own space, the tours' anchors in the bar are on screen, and nothing scrolls sideways. The page
// is layout/header.html under the app's own stylesheets. Since issue #605 the bar also holds the sync indicator, and below
// 430px the controls sit closer, Ask is its icon and the sync indicator its dot and the number waiting, in every sync state, so the
// business and the branch each show their first word and the start of the second (the accepted minimum at font scale 2 is below).

let server: ViteDevServer;
let browser: Browser;
let base = '';

beforeAll(async () => {
  // Its own dependency cache, so it never races tabs.layout.test.ts's server, which vitest starts at the same time.
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: 'node_modules/.vite-layout-header', plugins: [react(), helpCorpus()], logLevel: 'error', server: { port: 0, host: '127.0.0.1' } });
  await server.listen();
  base = server.resolvedUrls?.local[0] ?? '';
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
  await server?.close();
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
        await page.goto(`${base}layout/header.html?font=${font}&ask=${ask === 'on' ? 1 : 0}&sync=${sync}&waiting=${waiting}&${names}`);
        await page.locator('.staff-bar-top .ask-open').waitFor();
        const button = page.locator('.staff-bar-top .sync-button');
        await button.waitFor();
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
      await page.goto(`${base}layout/header.html?font=${font}&ask=${ask}`);
      const button = page.locator('.staff-bar-top .ask-open');
      await button.waitFor();
      expect(await button.getAttribute('aria-label')).toBe(ask === '1' ? 'Ask BMS, New' : 'How do I?, New');
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
      for (const target of ['.menu-button', '.sync-button', '.find-open', '.ask-open', '.top-menu-button']) {
        const box = (await page.locator(`.staff-bar-top ${target}`).first().boundingBox()) as Box;
        expect(box.width, `${target} width`).toBeGreaterThanOrEqual(48);
        expect(box.height, `${target} height`).toBeGreaterThanOrEqual(48);
      }
      for (const anchor of ['branch-picker', 'feature-search', 'top-menu']) {
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
