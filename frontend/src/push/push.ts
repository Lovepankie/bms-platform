import { type PushApi, pushApi } from '../api/push';

// Notifications on this device (ADR-050). The page asks for permission only when a person taps the switch, never on page load: a
// permission prompt nobody asked for is refused by habit, and on a phone a refusal is hard to undo. Everything that touches the
// browser goes through a small environment object, so the rules here are tested without a browser.

/** What the switch shows. `unsupported`: this browser cannot (an iPhone before the app is on its home screen). */
export type PushState = 'unsupported' | 'not_offered' | 'blocked' | 'off' | 'on';

export interface PushSubscriptionLike {
  endpoint: string;
  options?: { applicationServerKey?: ArrayBuffer | null };
  toJSON(): { endpoint?: string; keys?: Record<string, string> };
  unsubscribe(): Promise<boolean>;
}

export interface PushRegistrationLike {
  pushManager: {
    getSubscription(): Promise<PushSubscriptionLike | null>;
    subscribe(options: { userVisibleOnly: boolean; applicationServerKey: Uint8Array }): Promise<PushSubscriptionLike>;
  };
}

export interface PushEnv {
  /** True when this browser has service workers, push and notifications. */
  supported(): boolean;
  permission(): NotificationPermission;
  requestPermission(): Promise<NotificationPermission>;
  registration(): Promise<PushRegistrationLike>;
}

export function browserEnv(): PushEnv {
  return {
    supported: () =>
      typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window,
    permission: () => Notification.permission,
    requestPermission: () => Notification.requestPermission(),
    registration: () => navigator.serviceWorker.ready as unknown as Promise<PushRegistrationLike>,
  };
}

/** The server's key is unpadded base64url; the browser wants its bytes. */
export function keyBytes(text: string): Uint8Array {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(text.length / 4) * 4, '=');
  const raw = atob(padded);
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

function sameKey(existing: ArrayBuffer | null | undefined, wanted: Uint8Array): boolean {
  if (!existing) return true;
  const bytes = new Uint8Array(existing);
  return bytes.length === wanted.length && bytes.every((b, i) => b === wanted[i]);
}

function body(subscription: PushSubscriptionLike) {
  const json = subscription.toJSON();
  const p256dh = json.keys?.p256dh;
  const auth = json.keys?.auth;
  if (!json.endpoint || !p256dh || !auth) throw new Error('This browser gave an incomplete subscription.');
  return { endpoint: json.endpoint, keys: { p256dh, auth } };
}

/**
 * The device's subscription under the server's current key. A subscription made under another key (the server's keys were
 * changed) can never be delivered to, so it is dropped and made again; that needs no new permission once it is granted.
 */
async function currentSubscription(env: PushEnv, publicKey: string): Promise<PushSubscriptionLike> {
  const registration = await env.registration();
  const wanted = keyBytes(publicKey);
  let subscription = await registration.pushManager.getSubscription();
  if (subscription && !sameKey(subscription.options?.applicationServerKey, wanted)) {
    await subscription.unsubscribe();
    subscription = null;
  }
  return subscription ?? registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: wanted });
}

export function createPush(env: PushEnv = browserEnv(), api: PushApi = pushApi) {
  return {
    /** What the switch shows now. Never asks for anything. */
    async state(): Promise<PushState> {
      if (!env.supported()) return 'unsupported';
      const config = await api.config();
      if (!config.enabled) return 'not_offered';
      if (env.permission() === 'denied') return 'blocked';
      if (env.permission() !== 'granted') return 'off';
      const subscription = await (await env.registration()).pushManager.getSubscription();
      return subscription ? 'on' : 'off';
    },

    /** The tap on the switch: asks the browser for permission (only here), subscribes, and tells the server. */
    async enable(): Promise<PushState> {
      if (!env.supported()) return 'unsupported';
      const config = await api.config();
      if (!config.enabled || !config.publicKey) return 'not_offered';
      const permission = env.permission() === 'default' ? await env.requestPermission() : env.permission();
      if (permission === 'denied') return 'blocked';
      if (permission !== 'granted') return 'off';
      const subscription = await currentSubscription(env, config.publicKey);
      await api.subscribe(body(subscription));
      return 'on';
    },

    /** The switch off: the server forgets the device, then the browser drops the subscription. */
    async disable(): Promise<PushState> {
      if (!env.supported()) return 'unsupported';
      const subscription = await (await env.registration()).pushManager.getSubscription();
      if (subscription) {
        await api.unsubscribe(subscription.endpoint);
        await subscription.unsubscribe();
      }
      return 'off';
    },

    /**
     * Run each time the staff area opens: a device that already has permission and a subscription is registered again under the
     * person now signed in (this is what moves a shared device to the next person, and what restores it after a sign-out). Quiet:
     * it never asks for permission and never shows an error.
     */
    async sync(): Promise<void> {
      try {
        if (!env.supported() || env.permission() !== 'granted') return;
        const config = await api.config();
        if (!config.enabled || !config.publicKey) return;
        const registration = await env.registration();
        if (!(await registration.pushManager.getSubscription())) return;
        await api.subscribe(body(await currentSubscription(env, config.publicKey)));
      } catch {
        // Notifications are a convenience: a failure here is not worth the person's attention.
      }
    },

    /** On sign-out: this device stops receiving this person's notifications. The browser keeps its subscription. */
    async forget(): Promise<void> {
      try {
        if (!env.supported()) return;
        const subscription = await (await env.registration()).pushManager.getSubscription();
        if (subscription) await api.unsubscribe(subscription.endpoint);
      } catch {
        // Signing out must never wait on, or fail because of, a notification.
      }
    },
  };
}
