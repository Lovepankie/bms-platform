import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout, settle } from './open';

// Buttons sized for a phone and a laptop (issue #825), on the daily screens of an owner (layout/buttons.html): Sell, My stock,
// Buy, Money, Expenses, Savings and Set up your shop, with the catalogue Items, Barcode labels (a bar of two buttons) and an open
// Sheet, each under the staff top bar and over the bottom tabs. On a phone 412 by 900 every target is at least 48 by 48px and 8px
// clear of every other, and the screen's main action is full width in the bar stuck above the bottom tabs. On a laptop 1366 by
// 800 no button is wider than --button-max (320px), the main action is as wide as its label (at least 160px), stands at the right
// of its form and is still 48px tall. A bar or sheet shows its buttons in the order of the page, the primary last. Nothing scrolls
// sideways at either size. With LAYOUT_SHOTS=1 it saves the pictures of the pull request in docs/ui/button-sizes-825.

let browser: Browser;
const SHOTS = process.env.LAYOUT_SHOTS === '1' ? resolve(process.cwd(), '../docs/ui/button-sizes-825') : null;

beforeAll(async () => {
  browser = await chromium.launch();
  if (SHOTS) mkdirSync(SHOTS, { recursive: true });
});

afterAll(async () => {
  await browser?.close();
});

const PHONE = { width: 412, height: 900 };
const LAPTOP = { width: 1366, height: 800 };
const TAP = 48;
const GAP = 8;
const MAX = 320;
const MIN_WIDE = 160;
// The page gutter of the staff layout on a phone (layout.css): the stuck bar runs over it, its button inside it.
const GUTTER = 16;
// The classes of a main action (buttons.css).
const PRIMARY = '.rt-primary, .btn-primary, .btn-brand, .btn-danger';

/**
 * The screens, the selector of the main action of the ones that have one, of a secondary action under it, and of links drawn as
 * buttons that must be found and measured by name (`sized`): the stock card's Open the item, the Items ways to add sizes.
 */
const SCREENS: { screen: string; ready: string; main?: string; secondary?: string; sized?: string }[] = [
  { screen: 'sell', ready: '.sale-bar-action', main: '.sale-bar-action' },
  { screen: 'stock', ready: '.stock-card, .cat-head', sized: '.stock-card .item-page-link' },
  { screen: 'buy', ready: 'main .rt-primary', main: 'main .rt-primary', secondary: 'main .action-bar + button' },
  { screen: 'money', ready: '.list-row' },
  { screen: 'expenses', ready: '.action-bar .rt-primary', main: '.action-bar .rt-primary' },
  { screen: 'savings', ready: '.action-bar .rt-primary', main: '.action-bar .rt-primary' },
  { screen: 'setup', ready: 'main fieldset label', main: '.action-bar .rt-primary' },
  { screen: 'items', ready: 'main [data-tour="product-list"] :is(.list-row, li)', sized: 'main a[href*="/sizes/"]' },
  // Issue #863: one unit's items, with Show every unit in its status line.
  { screen: 'items-unit', ready: 'main [data-testid="one-unit"] button', sized: 'main [data-testid="one-unit"] button' },
  { screen: 'labels', ready: '.action-bar .rt-primary', main: '.action-bar .rt-primary' },
  { screen: 'period', ready: '.period-picker' },
  { screen: 'signout', ready: '.sheet-actions button' },
  { screen: 'sheet', ready: '.sheet-actions button' },
];

type Box = { left: number; top: number; right: number; bottom: number };
type Target = Box & { name: string; index: number; width: number; height: number; group: number; cover: (Box & { floating: boolean }) | null };

async function open(screen: string, ready: string, size: { width: number; height: number }, phone: boolean): Promise<Page> {
  const page = await browser.newPage({ viewport: size, hasTouch: phone, isMobile: phone });
  await openLayout(page, `layout/buttons.html?screen=${screen}`, page.locator(ready));
  // Set up your shop shows its Add these once a kind of shop is chosen; the PeriodPicker opens its sheet from Custom.
  if (screen === 'setup') await page.locator('main fieldset label').first().click();
  if (screen === 'period') {
    await page.locator('.period-picker .segmented > button', { hasText: 'Custom' }).click();
    await page.locator('.sheet-actions button').last().waitFor();
  }
  await settle(page.locator('main'));
  return page;
}

/**
 * Every target a person can tap, where it is drawn on the screen now: in the staff top bar, the page, an open sheet and the bottom
 * tabs, every button, link (bar a link inside running text, which WCAG 2.5.8 leaves out), summary, select, field and choice row.
 * What a sheet hides from a screen reader, or a closed details holds, cannot be tapped and is left out. `group` numbers the one
 * control a target is a part of, whose parts touch by design: the rows of one list, the parts of one Segmented or Stepper, the
 * tabs; -1 for a target of its own. `cover` is the stuck or fixed bar a target is drawn in, null for one in the page.
 */
async function targets(page: Page): Promise<Target[]> {
  return page.evaluate(() => {
    const groups = new Map<Element, number>();
    // The stuck or fixed bar a target is drawn in, and whether it floats over the page now: a fixed bar always does, a sticky one
    // when it stands away from its own place in the page (read with the bar put back in the flow for a moment).
    const coverOf = (el: Element) => {
      for (let box: HTMLElement | null = el as HTMLElement; box; box = box.parentElement) {
        const position = getComputedStyle(box).position;
        if (!/^(sticky|fixed)$/.test(position)) continue;
        const r = box.getBoundingClientRect();
        let floating = position === 'fixed';
        if (!floating) {
          const was = box.style.position;
          box.style.position = 'relative';
          floating = Math.abs(box.getBoundingClientRect().top - r.top) > 0.5;
          box.style.position = was;
        }
        return { left: r.left, top: r.top, right: r.right, bottom: r.bottom, floating };
      }
      return null;
    };
    // A tick box or a choice is tapped through its label, which is then the target: a label holding it, or one naming it (htmlFor).
    const labelled = (el: Element) => el instanceof HTMLInputElement && /^(radio|checkbox)$/.test(el.type) && (el.labels?.length ?? 0) > 0;
    const tappable = [
      'button', 'a[href]', 'summary', 'select', 'textarea', 'input:not([type="hidden"])', 'label:has(input[type="radio"], input[type="checkbox"])',
      '[role="button"]', '[role="link"]', '[role="tab"]', '[role="switch"]', '[role="menuitem"]', '[role="option"]', '[role="checkbox"]', '[role="radio"]',
      '[tabindex]:not([tabindex="-1"])',
    ].join(', ');
    const labels = [...document.querySelectorAll<HTMLLabelElement>('label[for]')].filter((l) => l.control && labelled(l.control));
    // A link inside running text (a sentence in a paragraph) is left out, as WCAG 2.5.8 does; a link standing on its own is not.
    // Running text: an inline link whose nearest block is a paragraph or list item that holds words of its own, outside any link
    // or button (a card of a name and one link is not running text).
    const inText = (el: HTMLElement) => {
      if (getComputedStyle(el).display !== 'inline') return false;
      let block = el.parentElement;
      while (block && /^inline/.test(getComputedStyle(block).display)) block = block.parentElement;
      if (!block?.matches('p, li')) return false;
      const words = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
      for (let n = words.nextNode(); n; n = words.nextNode()) {
        if ((n.textContent ?? '').trim() !== '' && !n.parentElement?.closest('a, button')) return true;
      }
      return false;
    };
    return [...new Set([...document.querySelectorAll<HTMLElement>(`:is(main, .staff-bar, .sheet-panel, .tab-bar) :is(${tappable})`), ...labels])]
      .filter((el) => {
        const r = el.getBoundingClientRect();
        const style = getComputedStyle(el);
        return r.width > 0 && r.height > 0 && style.visibility !== 'hidden' && !labelled(el)
          && !(el.tagName === 'A' && inText(el))
          && !el.closest('details:not([open]) > :not(summary), [aria-hidden="true"], [inert], .visually-hidden')
          // A control inside another target (a field inside its label, a button inside a summary) is part of that target.
          && !el.parentElement?.closest('label, summary, button, a[href]')
          // The one box that holds targets and is focusable only to pass focus on: a Segmented's group (Radix ToggleGroup).
          && !el.matches('.segmented');
      })
      .map((el, index) => {
        const r = el.getBoundingClientRect();
        el.dataset.measured = String(index);
        const name = `${el.tagName.toLowerCase()}.${[...el.classList].join('.')} "${(el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 30)}"`;
        const control = el.matches('.row-list > li > .list-row, .row-list > .list-row') ? el.closest('.row-list')
          : el.matches('.segmented > button, .stepper > *') ? el.parentElement
            : el.closest('.tab-bar');
        if (control && !groups.has(control)) groups.set(control, groups.size);
        return {
          name, index, left: r.left, top: r.top, right: r.right, bottom: r.bottom, width: r.width, height: r.height,
          group: control ? (groups.get(control) as number) : -1, cover: coverOf(el),
        };
      });
  });
}

/** How far apart two boxes stand: the larger of the gaps across and down, below zero when they overlap. */
const apart = (a: Box, b: Box) => Math.max(b.left - a.right, a.left - b.right, b.top - a.bottom, a.top - b.bottom);

/**
 * The targets of a stuck or fixed bar nearer than 8px to an edge of the bar that faces the page (not an edge of the screen),
 * whether the bar floats or stands in its own place: the top bar at every pass, the tabs, a stuck ActionBar.
 */
function pinnedInset(all: Target[], screen: { width: number; height: number }): string[] {
  return all.flatMap((t) => {
    const bar = t.cover;
    if (!bar) return [];
    const edges = [
      bar.left > 0.5 ? t.left - bar.left : Infinity,
      bar.top > 0.5 ? t.top - bar.top : Infinity,
      bar.right < screen.width - 0.5 ? bar.right - t.right : Infinity,
      bar.bottom < screen.height - 0.5 ? bar.bottom - t.bottom : Infinity,
    ];
    const near = Math.min(...edges);
    return near < GAP - 0.5 ? [`${t.name}: ${Math.round(near)}px inside its bar`] : [];
  });
}

/**
 * The pairs of targets nearer than 8px, measured where they are drawn, and the targets of the page a bar hides for good. Two parts
 * of one control touch by design. Any two boxes that meet fail, a floating bar's target over a target of the page included. While
 * a bar floats over the page (the fixed tabs, the top bar or the ActionBar stuck away from its place) the page scrolls past it, so
 * only a target of the page 0 to 8px from one of its targets is left, and `pinnedInset` keeps the bar's own targets clear of its
 * edges. A target of the page under a floating bar where the page cannot scroll any further to bring it out (under the tabs or a
 * stuck ActionBar at the foot, under the top bar at the top) can never be tapped, and fails too.
 */
function tooClose(all: Target[]): { close: string[]; under: number[] } {
  const close: string[] = [];
  const under = new Set<number>();
  const floats = (t: Target) => !!t.cover?.floating;
  all.forEach((a, i) => all.slice(i + 1).forEach((b) => {
    if (a.group >= 0 && a.group === b.group) return;
    const gap = apart(a, b);
    const passing = !a.cover !== !b.cover && (floats(a) || floats(b));
    // A target of the page met by a floating bar is brought clear of the bars and measured again (`underBars`); any other meeting
    // fails at once, and only the 0 to 8px band of a floating bar against the page it passes over is left.
    if (gap < 0 && passing) under.add((a.cover ? b : a).index);
    else if (gap < 0 || (gap < GAP - 0.5 && !passing)) close.push(`${a.name} and ${b.name}: ${Math.round(gap)}px`);
  }));
  for (const t of all.filter((x) => !x.cover)) {
    if (all.some((b) => b.cover?.floating && apart(t, b.cover) < 0)) under.add(t.index);
  }
  return { close: [...new Set(close)], under: [...under] };
}

/**
 * Each target of the page found under a floating bar, scrolled into the middle of the screen (the root's scroll padding keeps it
 * above a stuck bar) and measured again: still under a bar, it can never be tapped, and that is a failure.
 */
async function underBars(page: Page, under: number[]): Promise<string[]> {
  const failed: string[] = [];
  for (const index of under) {
    const hidden = await page.locator(`[data-measured="${index}"]`).evaluate((el) => {
      el.scrollIntoView({ block: 'center' });
      const r = el.getBoundingClientRect();
      const bars = [...document.querySelectorAll<HTMLElement>('*')].filter((b) => {
        const p = getComputedStyle(b).position;
        if (p !== 'fixed' && p !== 'sticky') return false;
        if (b.contains(el) || b.matches('.sheet-layer, .sheet-layer *')) return false;
        const box = b.getBoundingClientRect();
        return box.width > 0 && box.height > 0 && box.left < r.right && r.left < box.right && box.top < r.bottom && r.top < box.bottom;
      });
      return bars.length > 0 ? `${el.tagName.toLowerCase()} "${(el.textContent ?? '').trim().slice(0, 30)}" stays under ${bars.map((b) => b.className).join(', ')}` : '';
    });
    if (hidden) failed.push(hidden);
  }
  return failed;
}

/** The buttons of each ActionBar and sheet: their boxes in the order of the page, which is the primary, and the bar's inner box. */
async function bars(page: Page) {
  return page.locator('.action-bar, .sheet-actions').evaluateAll((all, primary) => all.map((bar) => {
    const cs = getComputedStyle(bar);
    const r = bar.getBoundingClientRect();
    const buttons = [...bar.querySelectorAll<HTMLElement>(':scope > :is(button, a)')];
    return {
      sticky: cs.position === 'sticky',
      edge: { left: r.left, top: r.top, right: r.right, bottom: r.bottom },
      inner: { left: r.left + parseFloat(cs.paddingLeft), right: r.right - parseFloat(cs.paddingRight) },
      boxes: buttons.map((b) => { const x = b.getBoundingClientRect(); return { left: x.left, top: x.top, right: x.right, bottom: x.bottom, width: x.width }; }),
      primaries: buttons.map((b, i) => (b.matches(primary) ? i : -1)).filter((i) => i >= 0),
    };
  }), PRIMARY);
}

describe.each(SCREENS)('the buttons of $screen (#825)', ({ screen, ready, main, secondary, sized }) => {
  it('on a phone 412 by 900: every target 48 by 48px and 8px apart, the main action full width in the stuck bar', async () => {
    const page = await open(screen, ready, PHONE, true);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${screen}-after-412.png`, fullPage: false });
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(PHONE.width);
    if (sized) {
      // These links are drawn as buttons (.btn in buttons.css: inline-flex, 48 by 48px at least), never as text links.
      const links = await page.locator(sized).evaluateAll((els) => els.map((el) => {
        const r = el.getBoundingClientRect();
        return { text: (el.textContent ?? '').trim(), width: r.width, height: r.height, display: getComputedStyle(el).display };
      }));
      expect(links.length, `${sized} found`).toBeGreaterThan(0);
      for (const l of links) {
        expect(l.display, l.text).toMatch(/flex/);
        expect(Math.min(l.width, l.height), l.text).toBeGreaterThanOrEqual(TAP - 0.5);
      }
    }
    // Measured at the top of the page, at its foot, and with the ActionBar put back in the flow of the form, where it stands on a
    // page short enough not to stick: there its buttons are measured against the page around it (Clear this purchase under it).
    // On a screen with a main action, once more after a press of it while its fields are not ready (Buy: the cost is empty), when
    // the form points at what is missing.
    const passes = main ? (['top', 'foot', 'flow', 'pressed'] as const) : (['top', 'foot', 'flow'] as const);
    for (const at of passes) {
      if (at === 'foot') await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
      if (at === 'pressed' && main) {
        await page.locator(main).first().click();
        await page.evaluate(() => window.scrollTo(0, 0));
      }
      const flow = at === 'flow' ? await page.addStyleTag({ content: '.action-bar { position: static !important; }' }) : null;
      await settle(page.locator('main'));
      const all = await targets(page);
      expect(all.length, 'targets found').toBeGreaterThan(0);
      expect(all.filter((t) => t.width < TAP - 0.5 || t.height < TAP - 0.5).map((t) => `${t.name} ${Math.round(t.width)}x${Math.round(t.height)}`), at).toEqual([]);
      const { close, under } = tooClose(all);
      expect(close, `measured at the ${at}`).toEqual([]);
      expect(pinnedInset(all, PHONE), `measured at the ${at}`).toEqual([]);
      const scrolled = await page.evaluate(() => window.scrollY);
      expect(await underBars(page, under), `brought out from under a bar at the ${at}`).toEqual([]);
      await page.evaluate((y) => window.scrollTo(0, y), scrolled);
      await flow?.evaluate((el) => (el as Element).remove());
    }
    await page.evaluate(() => window.scrollTo(0, 0));
    await settle(page.locator('main'));
    for (const bar of await bars(page)) {
      // A button of the stuck bar stands 8px inside every edge of the bar, so whatever is beside the bar is 8px from it.
      if (bar.sticky) {
        for (const b of bar.boxes) {
          expect(Math.min(b.left - bar.edge.left, b.top - bar.edge.top, bar.edge.right - b.right, bar.edge.bottom - b.bottom), 'inside the bar').toBeGreaterThanOrEqual(GAP - 0.5);
        }
      }
      // What is seen matches the Tab order: one under the other in the order of the page, each the full width of the bar, the
      // primary last, nearest the thumb.
      const tops = bar.boxes.map((b) => b.top);
      expect(tops).toEqual([...tops].sort((a, b) => a - b));
      expect(bar.boxes.filter((b) => b.width < bar.inner.right - bar.inner.left - 1)).toEqual([]);
      // A bar or sheet of more than one button names its one primary by its class, and it is the last.
      if (bar.boxes.length > 1 || bar.primaries.length > 0) expect(bar.primaries, 'one primary, the last').toEqual([bar.boxes.length - 1]);
    }
    if (main) {
      await page.evaluate(() => window.scrollTo(0, 0));
      const action = page.locator(main).first();
      const box = await action.boundingBox();
      expect(box, 'main action').not.toBeNull();
      expect(box?.width ?? 0).toBeGreaterThanOrEqual(PHONE.width - 2 * GUTTER - 1);
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(TAP);
      const stuck = await action.evaluate((el) => {
        const bar = el.closest<HTMLElement>('.action-bar');
        const tabs = document.querySelector('.tab-bar')?.getBoundingClientRect().top ?? window.innerHeight;
        return { position: bar ? getComputedStyle(bar).position : '', bottom: el.getBoundingClientRect().bottom, tabs };
      });
      expect(stuck.position).toBe('sticky');
      expect(stuck.bottom, 'the main action shows above the bottom tabs').toBeLessThanOrEqual(stuck.tabs + 0.5);
    }
    if (secondary) {
      // A secondary action under the bar keeps the width of the form; its distance from the bar is measured above.
      expect((await page.locator(secondary).first().boundingBox())?.width ?? 0).toBeGreaterThanOrEqual(PHONE.width - 2 * GUTTER - 1);
    }
    await page.close();
  });

  it('on a laptop 1366 by 800: no button stretched, the main action as wide as its label at the right of its form, 48px tall', async () => {
    const page = await open(screen, ready, LAPTOP, false);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${screen}-after-1366.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(LAPTOP.width);
    const all = await targets(page);
    expect(all.filter((t) => t.height < TAP - 0.5).map((t) => t.name)).toEqual([]);
    // Fields, rows, choice rows and the category headers are lines of a form or list, not buttons, so they keep its width.
    const buttons = all.filter((t) => /^(?:button|a)\./.test(t.name) && !/^(?:a\.list-row|a\.link-row|button\.cat-head|button\.cp-trigger)/.test(t.name));
    expect(buttons.filter((t) => t.width > MAX + 0.5).map((t) => `${t.name} ${Math.round(t.width)}px`)).toEqual([]);
    if (main) {
      const placed = await page.locator(main).first().evaluate((el) => {
        const form = el.closest<HTMLElement>('form, .action-bar')?.parentElement?.closest<HTMLElement>('form') ?? el.closest<HTMLElement>('form') ?? (el.closest('main') as HTMLElement);
        const box = form.getBoundingClientRect();
        const r = el.getBoundingClientRect();
        return { width: r.width, height: r.height, right: r.right, formRight: box.right - parseFloat(getComputedStyle(form).paddingRight), scroll: el.scrollWidth - el.clientWidth };
      });
      expect(placed.width).toBeGreaterThanOrEqual(MIN_WIDE - 0.5);
      expect(placed.width).toBeLessThanOrEqual(MAX + 0.5);
      expect(placed.height).toBeGreaterThanOrEqual(TAP);
      expect(placed.scroll, 'the label fits its button').toBeLessThanOrEqual(0);
      expect(Math.abs(placed.formRight - placed.right), 'the main action stands at the right of its form').toBeLessThanOrEqual(1);
    }
    if (secondary) {
      const under = await page.locator(secondary).first().evaluate((el) => {
        const r = el.getBoundingClientRect();
        const form = (el.closest('form') ?? el.parentElement) as HTMLElement;
        return { width: r.width, right: r.right, formRight: form.getBoundingClientRect().right - parseFloat(getComputedStyle(form).paddingRight) };
      });
      expect(under.width).toBeLessThanOrEqual(MAX + 0.5);
      expect(Math.abs(under.formRight - under.right), 'the secondary action stands under the main one, at the right').toBeLessThanOrEqual(1);
    }
    // What is seen matches the Tab order: the buttons of an ActionBar or a sheet run left to right in the order of the page, 8 to
    // 12px apart, the last, the primary, at the right of the bar.
    for (const bar of await bars(page)) {
      const lefts = bar.boxes.map((b) => b.left);
      expect(lefts).toEqual([...lefts].sort((a, b) => a - b));
      bar.boxes.slice(1).forEach((b, i) => {
        const gap = b.left - (bar.boxes[i] as Box).right;
        expect(gap, 'the bar keeps its buttons apart').toBeGreaterThanOrEqual(GAP - 0.5);
        expect(gap, 'the bar keeps its buttons together').toBeLessThanOrEqual(12.5);
      });
      expect(Math.abs(bar.inner.right - (bar.boxes.at(-1)?.right ?? bar.inner.right))).toBeLessThanOrEqual(1);
      if (bar.boxes.length > 1 || bar.primaries.length > 0) expect(bar.primaries, 'one primary, the last').toEqual([bar.boxes.length - 1]);
    }
    await page.close();
  });
});

// JUSTIFICATION-A3: the lead's note on the review of a4cc489 asks for these two fixtures of the measuring itself.
describe('the measuring itself (#825)', () => {
  // The page's room for the tabs taken away, so the last rows of Money end under the fixed tab bar: the suite must fail it.
  it('fails a list row left under the tab bar', async () => {
    const page = await open('money', '.list-row', PHONE, true);
    await page.addStyleTag({ content: 'body, .app-shell, .app-content, .nav-layout, .nav-layout-main, main { padding-bottom: 0 !important; margin-bottom: 0 !important; min-height: 0 !important; } main { padding-top: 300px !important; }' });
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await settle(page.locator('main'));
    const { under } = tooClose(await targets(page));
    expect(under.length, 'a row under the tabs is found').toBeGreaterThan(0);
    expect((await underBars(page, under)).join('\n')).toMatch(/stays under tab-bar/);
    await page.close();
  });

  // A card of a name and one link drawn as a button is measured; a link inside a sentence is not, as WCAG 2.5.8 leaves it out.
  it('measures a link standing in a card and leaves out a link in running text', async () => {
    const page = await browser.newPage({ viewport: PHONE, hasTouch: true, isMobile: true });
    await page.setContent(`<main>
      <ul><li class="card"><strong>Test item</strong><a class="open" href="#item" style="display: inline-flex; min-height: 48px">Open the item</a></li></ul>
      <p>Read <a class="prose" href="#guide">the guide</a> before you start.</p>
      <div class="card"><a class="lonely" href="#alone">Open</a></div>
    </main>`);
    const names = (await targets(page)).map((t) => t.name);
    expect(names.some((n) => n.startsWith('a.open'))).toBe(true);
    expect(names.some((n) => n.startsWith('a.lonely'))).toBe(true);
    expect(names.some((n) => n.startsWith('a.prose'))).toBe(false);
    await page.close();
  });
});
