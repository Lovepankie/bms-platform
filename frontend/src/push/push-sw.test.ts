import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it, vi } from 'vitest';

// The service worker's push handlers (public/push-sw.js, ADR-050), run against a fake worker scope: a notification always shows
// (a browser requires it), shows the title and body the server sent, opens the right page on a tap, and cannot be made to open
// another site. The file is plain script, loaded by the generated service worker with importScripts.

const SOURCE = readFileSync(fileURLToPath(new URL('../../public/push-sw.js', import.meta.url)), 'utf8');

interface FakeClient {
  url: string;
  focus: ReturnType<typeof vi.fn>;
  navigate: ReturnType<typeof vi.fn>;
}

function worker(open: FakeClient[] = []) {
  const handlers: Record<string, (event: unknown) => void> = {};
  const showNotification = vi.fn(() => Promise.resolve());
  const openWindow = vi.fn(() => Promise.resolve(null));
  const scope = {
    addEventListener: (type: string, handler: (event: unknown) => void) => {
      handlers[type] = handler;
    },
    registration: { showNotification },
    location: { origin: 'https://shop-bms.example.test' },
    clients: { matchAll: vi.fn(() => Promise.resolve(open)), openWindow },
  };
  new Function('self', SOURCE)(scope);
  return { handlers, showNotification, openWindow };
}

function pushEvent(data: unknown, throws = false) {
  const waits: Promise<unknown>[] = [];
  return {
    event: {
      data: data === undefined ? null : { json: () => (throws ? JSON.parse('{not json') : data) },
      waitUntil: (p: Promise<unknown>) => waits.push(p),
    },
    done: () => Promise.all(waits),
  };
}

function tapEvent(path: unknown) {
  const waits: Promise<unknown>[] = [];
  const close = vi.fn();
  return {
    event: { notification: { close, data: path === undefined ? undefined : { path } }, waitUntil: (p: Promise<unknown>) => waits.push(p) },
    close,
    done: () => Promise.all(waits),
  };
}

describe('a push arrives', () => {
  it('shows the title and body the server sent, and keeps the page a tap should open', async () => {
    const w = worker();
    const p = pushEvent({ title: 'An approval is waiting for you', body: 'Retail purchase order is waiting for your decision.', path: '/staff/approvals', tag: 'push:one' });
    w.handlers['push']?.(p.event);
    await p.done();
    expect(w.showNotification).toHaveBeenCalledWith(
      'An approval is waiting for you',
      expect.objectContaining({ body: 'Retail purchase order is waiting for your decision.', tag: 'push:one', data: { path: '/staff/approvals' }, icon: '/icons/icon-192.png' }),
    );
  });

  it('still shows a plain notification when the message has no text or cannot be read', async () => {
    for (const data of [undefined, {}, 'not an object']) {
      const w = worker();
      const p = pushEvent(data);
      w.handlers['push']?.(p.event);
      await p.done();
      expect(w.showNotification).toHaveBeenCalledWith('BMS', expect.objectContaining({ body: expect.stringContaining('Open BMS') }));
    }
    const broken = worker();
    const p = pushEvent({}, true);
    broken.handlers['push']?.(p.event);
    await p.done();
    expect(broken.showNotification).toHaveBeenCalledWith('BMS', expect.anything());
  });

  it('cuts a long title and body, and sends a path that is not an app route to the staff home', async () => {
    const w = worker();
    const p = pushEvent({ title: 'T'.repeat(500), body: 'B'.repeat(500), path: 'https://evil.example.test/steal' });
    w.handlers['push']?.(p.event);
    await p.done();
    const [title, options] = w.showNotification.mock.calls[0] as unknown as [string, { body: string; data: { path: string } }];
    expect(title).toHaveLength(80);
    expect(options.body).toHaveLength(200);
    expect(options.data.path).toBe('/staff');
  });
});

describe('a notification is tapped', () => {
  it('closes it and opens a new window on the page when the app is not open', async () => {
    const w = worker();
    const t = tapEvent('/staff/retail/in-transit');
    w.handlers['notificationclick']?.(t.event);
    await t.done();
    expect(t.close).toHaveBeenCalled();
    expect(w.openWindow).toHaveBeenCalledWith('/staff/retail/in-transit');
  });

  it('brings an open window of this app to the front and moves it to the page', async () => {
    const mine: FakeClient = { url: 'https://shop-bms.example.test/staff', focus: vi.fn(() => Promise.resolve()), navigate: vi.fn(() => Promise.resolve()) };
    const other: FakeClient = { url: 'https://other.example.test/', focus: vi.fn(), navigate: vi.fn() };
    const w = worker([other, mine]);
    const t = tapEvent('/staff/approvals');
    w.handlers['notificationclick']?.(t.event);
    await t.done();
    expect(mine.focus).toHaveBeenCalled();
    expect(mine.navigate).toHaveBeenCalledWith('/staff/approvals');
    expect(other.focus).not.toHaveBeenCalled();
    expect(w.openWindow).not.toHaveBeenCalled();
  });

  it('can never be made to open another site or a script', async () => {
    for (const bad of ['https://evil.example.test', '//evil.example.test', 'javascript:alert(1)', '/a\\b', 42, undefined]) {
      const w = worker();
      const t = tapEvent(bad);
      w.handlers['notificationclick']?.(t.event);
      await t.done();
      expect(w.openWindow).toHaveBeenCalledWith('/staff');
    }
  });
});
