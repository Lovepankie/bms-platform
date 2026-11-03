import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The Rincol Tech logo laid out in Chromium in both themes (issue #115), where style-page.dom.test.tsx can only read the
// stylesheet: in the brand bar, the Powered-by footer and the landing hero, exactly one version shows (the black lettering on
// the light theme, the light lettering on the dark one), the light-lettering version has no plate behind it, and both files
// have the same size in pixels, so the light one is drawn at the same size as the black one and never stretched. The page is
// layout/logo.html under the app's own stylesheets.

let browser: Browser;

beforeAll(async () => {
  browser = await chromium.launch();
});

afterAll(async () => {
  await browser?.close();
});

const PLACES = ['.brand-bar', '.landing-hero', '.app-footer'] as const;

type Shown = { src: string; width: number; height: number; natural: [number, number]; background: string };

/** The logos inside `place` that a person sees: drawn with a size, with their file, size, file size in pixels and background. */
async function shown(page: Page, place: string): Promise<Shown[]> {
  return page.locator(`${place} img`).evaluateAll((imgs) =>
    (imgs as HTMLImageElement[])
      .filter((img) => img.getBoundingClientRect().width > 0)
      .map((img) => ({
        src: new URL(img.src).pathname,
        width: Math.round(img.getBoundingClientRect().width),
        height: Math.round(img.getBoundingClientRect().height),
        natural: [img.naturalWidth, img.naturalHeight] as [number, number],
        background: getComputedStyle(img).backgroundColor,
      })),
  );
}

async function open(theme: 'light' | 'dark') {
  const page = await browser.newPage({ viewport: { width: 390, height: 900 } });
  await openLayout(page, `layout/logo.html?theme=${theme}`, page.locator('.app-footer a'));
  await page.waitForFunction(() => [...document.images].every((img) => img.complete && img.naturalWidth > 0));
  expect(await page.evaluate(() => document.documentElement.getAttribute('data-theme'))).toBe(theme);
  return page;
}

describe('the Rincol Tech logo in both themes (#115)', () => {
  it('shows the black lettering on the light theme, with no plate, in every place', async () => {
    const page = await open('light');
    for (const place of PLACES) {
      const logos = await shown(page, place);
      expect(logos.map((l) => l.src), place).toEqual(['/brand/rincoltech-logo.png']);
      expect(logos[0]?.background, place).toBe('rgba(0, 0, 0, 0)');
    }
    await page.close();
  });

  it('shows the light lettering on the dark theme, with no white plate behind it, in every place', async () => {
    const page = await open('dark');
    for (const place of PLACES) {
      const logos = await shown(page, place);
      expect(logos.map((l) => l.src), place).toEqual(['/brand/rincoltech-logo-on-dark.png']);
      expect(logos[0]?.background, place).toBe('rgba(0, 0, 0, 0)');
    }
    await page.close();
  });

  it('draws the light lettering at the size of the black one, from a file of the same pixel size', async () => {
    const light = await open('light');
    const dark = await open('dark');
    for (const place of PLACES) {
      const [a] = await shown(light, place);
      const [b] = await shown(dark, place);
      expect(a, place).toBeDefined();
      expect(b?.natural, place).toEqual(a?.natural);
      expect([b?.width, b?.height], place).toEqual([a?.width, a?.height]);
    }
    expect((await shown(dark, '.app-footer'))[0]?.natural).toEqual([400, 114]);
    await light.close();
    await dark.close();
  });
});
