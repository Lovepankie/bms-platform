import { api, problemOf } from './client';
import type { components } from './schema';

// The device routes of Web Push (ADR-050; chapter 7 section 7.11.7). The generated types mark every field optional, as for the
// rest of the API; the small helpers here give the page what it needs. The subscription's address and keys go up and are never
// read back: the server returns a label and two dates for each device.

export type PushConfig = components['schemas']['PushConfig'];
export type PushDevice = components['schemas']['PushDevice'];

export interface PushSubscriptionBody {
  endpoint: string;
  keys: { p256dh: string; auth: string };
}

export class PushError extends Error {
  constructor(
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}

function fail(error: unknown, fallback: string): never {
  const problem = problemOf(error);
  const first = (problem.errors ?? []).map((e) => e.message).find((m) => m);
  throw new PushError(first ?? problem.detail ?? fallback, problem.code);
}

export interface PushApi {
  config(): Promise<{ enabled: boolean; publicKey: string | null }>;
  list(): Promise<PushDevice[]>;
  subscribe(body: PushSubscriptionBody): Promise<PushDevice>;
  unsubscribe(endpoint: string): Promise<void>;
  remove(id: string): Promise<void>;
}

export const pushApi: PushApi = {
  async config() {
    const { data, error } = await api.GET('/api/v1/notifications/push/config');
    if (error || !data) fail(error, 'Could not check notifications.');
    return { enabled: data.enabled === true, publicKey: data.public_key ?? null };
  },

  async list() {
    const { data, error } = await api.GET('/api/v1/notifications/push/subscriptions');
    if (error || !data) fail(error, 'Could not load your devices.');
    return data.items ?? [];
  },

  async subscribe(body) {
    const { data, error } = await api.POST('/api/v1/notifications/push/subscriptions', { body });
    if (error || !data) fail(error, 'Could not turn notifications on for this device.');
    return data;
  },

  async unsubscribe(endpoint) {
    const { error, response } = await api.POST('/api/v1/notifications/push/subscriptions/unsubscribe', { body: { endpoint } });
    if (error || !response.ok) fail(error, 'Could not turn notifications off for this device.');
  },

  async remove(id) {
    const { error, response } = await api.DELETE('/api/v1/notifications/push/subscriptions/{device_id}', {
      params: { path: { device_id: id } },
    });
    if (error || !response.ok) fail(error, 'Could not remove this device.');
  },
};
