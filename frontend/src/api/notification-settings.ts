import { api, problemOf } from './client';
import type { components } from './schema';

// What the business sends by push (issue #412, ADR-057): a switch per everyday notice and the time of the daily summary, in the
// tenant settings group "notifications". One version for the whole settings row: the ETag of the read is sent back as If-Match.

export type NotificationSettings = components['schemas']['NotificationSettings'];
export type NotificationSettingsChange = components['schemas']['UpdateNotificationSettingsRequest'];

function fail(error: unknown, fallback: string): never {
  const problem = problemOf(error);
  const first = (problem.errors ?? []).map((e) => e.message).find((m) => m);
  throw new Error(first ?? problem.detail ?? fallback);
}

export const notificationSettingsApi = {
  async get(): Promise<NotificationSettings> {
    const { data, error } = await api.GET('/api/v1/settings/notifications');
    if (error || !data) fail(error, 'Could not load the notification settings.');
    return data;
  },

  async update(version: number, change: NotificationSettingsChange): Promise<NotificationSettings> {
    const { data, error } = await api.PUT('/api/v1/settings/notifications', {
      params: { header: { 'If-Match': String(version) } },
      body: change,
    });
    if (error || !data) fail(error, 'Could not save the notification settings.');
    return data;
  },
};
