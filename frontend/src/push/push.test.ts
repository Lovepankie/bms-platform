import { describe, expect, it, vi } from 'vitest';
import type { PushApi } from '../api/push';
import { type PushEnv, type PushSubscriptionLike, createPush, keyBytes } from './push';

// The rules of "Notifications on this device" (ADR-050), with a fake browser and a fake server: the permission question is asked
// only by enable(), never by state() or sync(); a refusal is a state and not an error; a subscription made under an old server key
// is made again; and the server hears about the device only after the browser has made the subscription. Fabricated keys.

const KEY = 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4';

function subscription(endpoint: string, key: Uint8Array = keyBytes(KEY)): PushSubscriptionLike & { unsubscribed: boolean } {
  const self = {
    endpoint,
    unsubscribed: false,
    options: { applicationServerKey: key.slice().buffer as ArrayBuffer },
    toJSON: () => ({ endpoint, keys: { p256dh: 'test-p256dh-value', auth: 'test-auth-value' } }),
    unsubscribe: () => {
      self.unsubscribed = true;
      return Promise.resolve(true);
    },
  };
  return self;
}

function setup(over: { permission?: NotificationPermission; granted?: NotificationPermission; existing?: PushSubscriptionLike | null; supported?: boolean; enabled?: boolean } = {}) {
  let permission: NotificationPermission = over.permission ?? 'default';
  let current: PushSubscriptionLike | null = over.existing ?? null;
  const made = subscription('https://push.example.test/made');
  const subscribe = vi.fn((options: { userVisibleOnly: boolean; applicationServerKey: Uint8Array }) => {
    void options;
    current = made;
    return Promise.resolve(made);
  });
  const requestPermission = vi.fn(() => {
    permission = over.granted ?? 'granted';
    return Promise.resolve(permission);
  });
  const env: PushEnv = {
    supported: () => over.supported ?? true,
    permission: () => permission,
    requestPermission,
    registration: () => Promise.resolve({ pushManager: { getSubscription: () => Promise.resolve(current), subscribe } }),
  };
  const api: PushApi = {
    config: vi.fn(() => Promise.resolve({ enabled: over.enabled ?? true, publicKey: KEY })),
    list: vi.fn(() => Promise.resolve([])),
    subscribe: vi.fn(() => Promise.resolve({})),
    unsubscribe: vi.fn(() => Promise.resolve()),
    remove: vi.fn(() => Promise.resolve()),
  };
  return { push: createPush(env, api), env, api, subscribe, requestPermission, made };
}

describe('what the switch shows', () => {
  it('is unsupported where the browser cannot, and never asks for anything', async () => {
    const { push, requestPermission, api } = setup({ supported: false });
    expect(await push.state()).toBe('unsupported');
    expect(requestPermission).not.toHaveBeenCalled();
    expect(api.config).not.toHaveBeenCalled();
  });

  it('is not offered when the server has push off', async () => {
    expect(await setup({ enabled: false }).push.state()).toBe('not_offered');
  });

  it('is blocked after a refusal, off with no subscription, and on with permission and a subscription', async () => {
    expect(await setup({ permission: 'denied' }).push.state()).toBe('blocked');
    expect(await setup({ permission: 'default' }).push.state()).toBe('off');
    expect(await setup({ permission: 'granted' }).push.state()).toBe('off');
    expect(await setup({ permission: 'granted', existing: subscription('https://push.example.test/one') }).push.state()).toBe('on');
  });

  it('never asks the browser for permission just by looking', async () => {
    const { push, requestPermission } = setup({ permission: 'default' });
    await push.state();
    await push.sync();
    expect(requestPermission).not.toHaveBeenCalled();
  });
});

describe('turning it on', () => {
  it('asks for permission on the tap, subscribes under the server key and tells the server', async () => {
    const { push, requestPermission, subscribe, api } = setup();
    expect(await push.enable()).toBe('on');
    expect(requestPermission).toHaveBeenCalledTimes(1);
    const options = subscribe.mock.calls[0]?.[0];
    expect(options?.userVisibleOnly).toBe(true);
    expect(Array.from(options?.applicationServerKey ?? [])).toEqual(Array.from(keyBytes(KEY)));
    expect(api.subscribe).toHaveBeenCalledWith({ endpoint: 'https://push.example.test/made', keys: { p256dh: 'test-p256dh-value', auth: 'test-auth-value' } });
  });

  it('does not ask again when permission is already granted, and reuses the subscription it has', async () => {
    const existing = subscription('https://push.example.test/existing');
    const { push, requestPermission, subscribe, api } = setup({ permission: 'granted', existing });
    expect(await push.enable()).toBe('on');
    expect(requestPermission).not.toHaveBeenCalled();
    expect(subscribe).not.toHaveBeenCalled();
    expect(api.subscribe).toHaveBeenCalledWith(expect.objectContaining({ endpoint: 'https://push.example.test/existing' }));
  });

  it('says blocked after a refusal and tells the server nothing', async () => {
    const refused = setup({ granted: 'denied' });
    expect(await refused.push.enable()).toBe('blocked');
    expect(refused.api.subscribe).not.toHaveBeenCalled();
    const already = setup({ permission: 'denied' });
    expect(await already.push.enable()).toBe('blocked');
    expect(already.requestPermission).not.toHaveBeenCalled();
    // Closing the question without answering leaves it off, and asks nothing more.
    const dismissed = setup({ granted: 'default' });
    expect(await dismissed.push.enable()).toBe('off');
  });

  it('makes the subscription again when the server changed its key', async () => {
    const stale = subscription('https://push.example.test/stale', new Uint8Array([1, 2, 3]));
    const { push, subscribe } = setup({ permission: 'granted', existing: stale });
    expect(await push.enable()).toBe('on');
    expect((stale as unknown as { unsubscribed: boolean }).unsubscribed).toBe(true);
    expect(subscribe).toHaveBeenCalledTimes(1);
  });

  it('does nothing when the server has push off or the browser cannot', async () => {
    const off = setup({ enabled: false });
    expect(await off.push.enable()).toBe('not_offered');
    expect(off.requestPermission).not.toHaveBeenCalled();
    expect(await setup({ supported: false }).push.enable()).toBe('unsupported');
  });
});

describe('turning it off, signing out and coming back', () => {
  it('removes the device from the server and then drops the browser subscription', async () => {
    const existing = subscription('https://push.example.test/one');
    const { push, api } = setup({ permission: 'granted', existing });
    expect(await push.disable()).toBe('off');
    expect(api.unsubscribe).toHaveBeenCalledWith('https://push.example.test/one');
    expect((existing as unknown as { unsubscribed: boolean }).unsubscribed).toBe(true);
  });

  it('on sign-out tells the server only, and keeps the browser subscription', async () => {
    const existing = subscription('https://push.example.test/one');
    const { push, api } = setup({ permission: 'granted', existing });
    await push.forget();
    expect(api.unsubscribe).toHaveBeenCalledWith('https://push.example.test/one');
    expect((existing as unknown as { unsubscribed: boolean }).unsubscribed).toBe(false);
  });

  it('registers a device that already has permission and a subscription under whoever is now signed in, quietly', async () => {
    const existing = subscription('https://push.example.test/one');
    const { push, api, requestPermission } = setup({ permission: 'granted', existing });
    await push.sync();
    expect(api.subscribe).toHaveBeenCalledWith(expect.objectContaining({ endpoint: 'https://push.example.test/one' }));
    expect(requestPermission).not.toHaveBeenCalled();
    // No permission, or no subscription: nothing is registered.
    const none = setup({ permission: 'default', existing });
    await none.push.sync();
    expect(none.api.subscribe).not.toHaveBeenCalled();
    const nosub = setup({ permission: 'granted', existing: null });
    await nosub.push.sync();
    expect(nosub.api.subscribe).not.toHaveBeenCalled();
  });

  it('never shows an error from the quiet paths', async () => {
    const { push, api } = setup({ permission: 'granted', existing: subscription('https://push.example.test/one') });
    (api.subscribe as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('offline'));
    (api.unsubscribe as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('offline'));
    await expect(push.sync()).resolves.toBeUndefined();
    await expect(push.forget()).resolves.toBeUndefined();
  });
});

describe('the server key', () => {
  it('is decoded from unpadded base64url to the 65 bytes of a P-256 point', () => {
    const bytes = keyBytes(KEY);
    expect(bytes.length).toBe(65);
    expect(bytes[0]).toBe(4);
  });
});
