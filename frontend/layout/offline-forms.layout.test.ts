import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { chromium, type Browser, type Page } from 'playwright';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openLayout } from './open';

// The daily forms kept on the phone (issue #812), in Chromium on a phone 412 wide (layout/offline-forms.html):
// 1. The offline note, the Saved on this phone panel and the sync sheet with a refused and a waiting entry fit the phone, nothing
//    scrolls sideways, and Fix, Send again, Discard and Record another are at least 48px high (docs/ui-rules.md rule 12).
// 2. The time to save on a cheap phone on 3G: the CPU slowed four times and the network throttled to the DevTools 3G and Slow 3G
//    profiles, the expense posted through the real API client to a local server. On 3G the server's answer comes in under 3
//    seconds; the Slow 3G time is printed and reported. A server that does not answer within the 8 seconds a sale waits leaves
//    the entry on the phone, sent later under the same key.

let browser: Browser;
let api: Server;
let apiBase = '';
/** How long the local server takes to answer, as the test sets it. */
let serverMs = 50;
const posts: string[] = [];

beforeAll(async () => {
  browser = await chromium.launch();
  api = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      posts.push(`${req.method} ${req.url} ${req.headers['idempotency-key'] ?? ''}`);
      setTimeout(() => {
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'e-812', branch_id: 'b1', business_date: '2026-11-03', category_name: 'Transport', item_name: 'Fuel', amount_minor: 20000 }));
      }, serverMs);
    });
  });
  await new Promise<void>((done) => api.listen(0, '127.0.0.1', done));
  apiBase = `http://127.0.0.1:${(api.address() as AddressInfo).port}`;
});

afterAll(async () => {
  await browser?.close();
  await new Promise((done) => api?.close(done));
});

const WIDTH = 412;

async function phone(): Promise<Page> {
  return browser.newPage({ viewport: { width: WIDTH, height: 915 }, hasTouch: true, isMobile: true });
}

async function fitsAndTall(page: Page, name: string | RegExp) {
  const box = await page.getByRole('button', { name }).first().boundingBox();
  expect(box && box.x >= 0 && box.x + box.width <= WIDTH, String(name)).toBe(true);
  expect(box!.height, String(name)).toBeGreaterThanOrEqual(48);
}

describe('the daily forms kept on the phone at 412px (#812)', () => {
  it('fits the offline note and Saved on this phone', async () => {
    const page = await phone();
    await openLayout(page, 'layout/offline-forms.html?mode=offline', page.getByTestId('offline-form-note'));
    const note = await page.getByTestId('offline-form-note').boundingBox();
    expect(note && note.x >= 0 && note.x + note.width <= WIDTH).toBe(true);
    // Offline the date is not asked: the entry is dated when it was made.
    expect(await page.locator('#expense-date').count()).toBe(0);
    await page.getByRole('button', { name: 'Save expense' }).click();
    await page.getByTestId('kept-on-phone').waitFor();
    expect(await page.getByTestId('kept-on-phone').textContent()).toContain('Waiting to be sent');
    await fitsAndTall(page, 'Record another');
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });

  it('fits the sync sheet with a refused entry and its Fix, Send again and Discard', async () => {
    const page = await phone();
    await openLayout(page, 'layout/offline-forms.html?mode=sheet', page.getByTestId('sync-dot'));
    await page.getByTestId('sync-dot').click();
    await page.getByText('Not recorded: This item is switched off. Choose another one.').waitFor();
    for (const name of ['Fix', 'Send again', 'Discard', 'Sync now', 'Close']) await fitsAndTall(page, name);
    expect(await page.getByText('Waiting to be sent').count()).toBe(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'page width').toBeLessThanOrEqual(WIDTH);
    await page.close();
  });
});

/** DevTools' network profiles, in bytes per second and milliseconds of latency. */
const PROFILES = {
  '3G': { latency: 562.5, downloadThroughput: (1.6 * 1024 * 1024 * 0.9) / 8, uploadThroughput: (750 * 1024 * 0.9) / 8 },
  'Slow 3G': { latency: 2000, downloadThroughput: (500 * 1024 * 0.8) / 8, uploadThroughput: (500 * 1024 * 0.8) / 8 },
};

/** Opens the online form on a throttled cheap phone and returns how long Save expense took to say it was saved, in ms. */
async function timeToSave(profile: keyof typeof PROFILES, server: number): Promise<{ ms: number; kept: boolean }> {
  serverMs = server;
  const page = await phone();
  await openLayout(page, 'layout/offline-forms.html?mode=online', page.getByRole('button', { name: 'Save expense' }));
  // Every API call goes on to the local server through the browser's network, so the throttling applies to it.
  await page.route('**/api/**', (route) => route.continue({ url: `${apiBase}${new URL(route.request().url()).pathname}` }));
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', { offline: false, ...PROFILES[profile] });
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  const start = Date.now();
  await page.getByRole('button', { name: 'Save expense' }).click();
  const done = await Promise.race([
    page.getByTestId('saved-online').waitFor({ timeout: 15000 }).then(() => false),
    page.getByTestId('kept-on-phone').waitFor({ timeout: 15000 }).then(() => true),
  ]);
  const ms = Date.now() - start;
  console.log(`time to save an expense, ${profile}, server ${server} ms, CPU 4x slower: ${ms} ms${done ? ' (kept on the phone)' : ''}`);
  await page.close();
  return { ms, kept: done };
}

describe('the time to save on a cheap phone on 3G (#812)', () => {
  it('saves an expense on 3G in under 3 seconds, answered by the server', async () => {
    const { ms, kept } = await timeToSave('3G', 50);
    expect(kept).toBe(false);
    expect(ms).toBeLessThan(3000);
  });

  // Reported, as the lead decided on the review of #839: Slow 3G's 2 second latency alone takes most of it.
  it('saves an expense on Slow 3G, answered by the server, and reports the time', async () => {
    const { ms, kept } = await timeToSave('Slow 3G', 50);
    expect(kept).toBe(false);
    expect(ms).toBeLessThan(8000);
  });

  it('keeps the entry on the phone under the same key when the server does not answer within the 8 seconds a sale waits', async () => {
    posts.length = 0;
    const { ms, kept } = await timeToSave('3G', 9000);
    expect(kept).toBe(true);
    expect(ms).toBeLessThan(10000);
    // The late request and the outbox's send carry the one key, so the server records one expense (RetailOfflineEntriesIT).
    const keys = new Set(posts.filter((p) => p.startsWith('POST /api/v1/retail/expenses')).map((p) => p.split(' ')[2]));
    expect([...keys]).toEqual(['00000000-0000-4000-8000-000000000812']);
  });
});
