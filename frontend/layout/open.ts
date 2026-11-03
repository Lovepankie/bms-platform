import type { ConsoleMessage, Frame, Locator, Page, Request, Response } from 'playwright';
import { inject } from 'vitest';

// Opens a layout page and waits until it draws (issue #717). A page that throws, loses one of its modules or is reloaded under
// the test fails at once with what the page reported, and one that never draws fails at the wait with the same report, instead
// of a bare timeout.

// JUSTIFICATION-A3: nothing waits for fonts or a settled layout yet; one shared wait for the header, reports and pack-table cases.
/**
 * Waits until the fonts have loaded and `target` measures the same on two animation frames in a row (issue #775): its box,
 * scroll and client widths, its text and the page's scroll width. Read earlier, a check once saw "Store " for "Store 1" and a
 * page 418 wide. A layout still moving after `frames` frames fails with its last measurement.
 */
export async function settle(target: Locator, frames = 120): Promise<void> {
  await target.first().evaluate(async (el, limit) => {
    const measure = async () => {
      await document.fonts.ready;
      await new Promise<void>((done) => requestAnimationFrame(() => done()));
      const r = el.getBoundingClientRect();
      return [document.fonts.status, r.x, r.y, r.width, r.height, el.scrollWidth, el.clientWidth, document.documentElement.scrollWidth, el.textContent].join('|');
    };
    let last = await measure();
    for (let i = 0; i < limit; i++) {
      const now = await measure();
      if (now === last && document.fonts.status === 'loaded') return;
      last = now;
    }
    throw new Error(`the layout did not settle in ${limit} frames: last measured ${last}`);
  }, frames);
}

/** Opens `path` (for example `layout/my-stock.html?font=2`) on the layout server and waits for `ready` to show. */
export async function openLayout(page: Page, path: string, ready: Locator): Promise<Page> {
  const report: string[] = [];
  let fail: (reason: Error) => void = () => {};
  const failed = new Promise<never>((_, reject) => {
    fail = reject;
  });
  const stop = (what: string) => fail(new Error(`${path} did not draw: ${what}\n${report.join('\n')}`));
  const onConsole = (m: ConsoleMessage) => {
    if (m.type() === 'error' || m.type() === 'warning') report.push(`console.${m.type()}: ${m.text()}`);
  };
  const onError = (e: Error) => {
    report.push(`page error: ${e.stack ?? e.message}`);
    stop(e.message);
  };
  // Only the page, its modules and its stylesheets count: a missing picture or icon leaves the layout to the assertions.
  const vital = (r: Request) => ['document', 'script', 'stylesheet'].includes(r.resourceType());
  const onFailed = (r: Request) => {
    report.push(`request failed: ${r.url()} ${r.failure()?.errorText ?? ''}`);
    if (vital(r)) stop(`request failed: ${r.url()}`);
  };
  const onResponse = (r: Response) => {
    if (r.status() < 400) return;
    report.push(`HTTP ${r.status()} ${r.statusText()}: ${r.url()}`);
    if (vital(r.request())) stop(`HTTP ${r.status()} ${r.statusText()}: ${r.url()}`);
  };
  let loads = 0;
  const onNavigated = (frame: Frame) => {
    if (frame === page.mainFrame() && ++loads > 1) stop('the page was reloaded while it loaded');
  };
  page.on('console', onConsole);
  page.on('pageerror', onError);
  page.on('requestfailed', onFailed);
  page.on('response', onResponse);
  page.on('framenavigated', onNavigated);
  failed.catch(() => {});
  try {
    await page.goto(`${inject('layoutBase')}${path}`);
    await Promise.race([ready.first().waitFor(), failed]);
  } catch (e) {
    if (e instanceof Error && !e.message.includes(' did not draw: ')) e.message = `${path} did not draw: ${e.message}\n${report.join('\n')}`;
    throw e;
  } finally {
    page.off('console', onConsole);
    page.off('pageerror', onError);
    page.off('requestfailed', onFailed);
    page.off('response', onResponse);
    page.off('framenavigated', onNavigated);
  }
  return page;
}
