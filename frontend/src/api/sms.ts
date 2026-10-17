import { api, problemOf } from './client';
import type { components } from './schema';

// The tenant SMS client (extras slice B3, issue #218; chapter 7 section 7.11.7; ADR-036): the settings, the opt-out list, the
// wording and the test message. The generated schema marks every field optional, as for the rest of the API; these are the
// shapes the server always sends (core/notifications/internal/Sms*.java), so the screens read them without guards. A cap of
// 0 means stop: it is a number like any other here, never "empty".

export interface SmsAcknowledgement {
  text_version: string;
  text: string;
}

export interface SmsSettings {
  enabled: boolean;
  operator_enabled: boolean;
  /** off, sink, allowlist or live: what this system does with a message */
  mode: string;
  test_message_available: boolean;
  sender_id: string | null;
  /** none, approved or withdrawn */
  sender_status: string;
  approved_sender_ids: string[];
  window_start: string;
  window_end: string;
  window_zone: string;
  window_earliest: string;
  window_latest: string;
  window_min_minutes: number;
  daily_cap: number | null;
  monthly_cap: number | null;
  daily_ceiling: number;
  monthly_ceiling: number;
  effective_daily_cap: number;
  effective_monthly_cap: number;
  used_today: number;
  used_this_month: number;
  include_balance: boolean;
  acknowledgement: SmsAcknowledgement;
  acknowledged_text_version: string | null;
  acknowledged_at: string | null;
  acknowledged_by: string | null;
  version: number;
}

/** A partial change; an omitted field is unchanged, an empty sender_id clears it. */
export interface SmsSettingsChange {
  enabled?: boolean;
  acknowledged_text_version?: string;
  sender_id?: string;
  window_start?: string;
  window_end?: string;
  daily_cap?: number;
  monthly_cap?: number;
  include_balance?: boolean;
}

export interface SmsOptOut {
  id: string;
  /** member (follows the member to any number) or number (this number, whoever it is about) */
  kind: string;
  member_id: string | null;
  /** reminders or all */
  scope: string;
  source: string;
  recipient_masked: string | null;
  created_at: string;
  created_by: string | null;
}

export interface SmsOptOutPage {
  items: SmsOptOut[];
  next_cursor: string | null;
}

export interface SmsImportRow {
  line: number;
  outcome: string;
}

export interface SmsImportResult {
  dry_run: boolean;
  rows_read: number;
  added: number;
  changed: number;
  unchanged: number;
  invalid: number;
  rows: SmsImportRow[];
}

export interface SmsPlaceholder {
  name: string;
  description: string;
  sample: string;
  max_length: number;
}

export interface SmsMeasure {
  /** gsm7 or ucs2 */
  encoding: string;
  units: number;
  segments: number;
  worst_case_segments: number;
  estimated_cost_minor: number | null;
  currency: string | null;
}

export interface SmsTemplate {
  event_key: string;
  channel: string;
  title: string;
  default_text: string;
  placeholders: SmsPlaceholder[];
  override: { text: string; version: number; updated_at: string } | null;
  sample_text: string;
  measure: SmsMeasure;
  /** Set when the sample is the default words because the business name is too long for the wording */
  note: string | null;
  max_chars: number;
  max_worst_case_segments: number;
}

export interface SmsPreview {
  text: string;
  measure: SmsMeasure;
  /** Set when the text shown is the default words, not the ones previewed */
  note: string | null;
}

export interface SmsTestResult {
  sent: boolean;
  /** sunk, sent or not_sent */
  outcome: string;
  reason: string | null;
  mode: string;
  recipient_masked: string;
  text: string;
  measure: SmsMeasure;
}

export interface MemberSmsOptOut {
  opted_out: boolean;
  scope: string | null;
  recipient_masked: string | null;
  since: string | null;
}

// Keeps the generated types honest: a change to the contract that drops one of these breaks this line, not a screen.
export type GeneratedSmsSettings = components['schemas']['SmsSettings'];
export type GeneratedSmsOptOut = components['schemas']['SmsOptOut'];
export type GeneratedSmsTemplate = components['schemas']['SmsTemplate'];

export class SmsError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    /** field name to its message, from a 422 */
    readonly fields: Record<string, string> = {},
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string | undefined> = {
  version_conflict: 'Someone else changed this. The latest has been loaded; check it and try again.',
  precondition_required: 'This page is out of date. Reload it and try again.',
  acknowledgement_required: 'Read the notice and tick the box to switch SMS on.',
  permission_denied: 'You do not have permission to do this.',
  rate_limited: 'Too many test messages this hour. Try again later.',
  invalid_phone: 'Use a Uganda mobile number such as 0700 000 001.',
  sender_id_taken: 'Another business already holds this sender id.',
  reason_required: 'Say why, in 3 to 200 characters.',
};

const FIELD_CODES: Record<string, string | undefined> = {
  not_approved: 'Choose one of the sender ids the platform approved for you.',
  above_ceiling: 'Above the limit set for your business. A cap can be lowered, not raised.',
  window_too_short: 'The window must be at least an hour long.',
  outside_limits: 'Messages can only go between 06:00 and 21:00.',
};

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  const fields: Record<string, string> = {};
  for (const e of problem.errors ?? []) {
    const code = (e as { code?: string }).code;
    fields[e.field] = (code && FIELD_CODES[code]) || e.message;
  }
  const first = Object.values(fields)[0];
  const message =
    (problem.code && MESSAGES[problem.code]) || first || (status === 403 ? MESSAGES.permission_denied : undefined) || fallback;
  throw new SmsError(message ?? fallback, status, problem.code, fields);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    fail(result.error, result.response.status, fallback);
  }
  return result.data;
}

const ifMatch = (version: number) => `"${version}"`;

export const sms = {
  async settings(): Promise<SmsSettings> {
    return unwrap(await api.GET('/api/v1/settings/sms'), 'Could not load the SMS settings.') as SmsSettings;
  },

  async changeSettings(version: number, change: SmsSettingsChange): Promise<SmsSettings> {
    return unwrap(
      await api.PUT('/api/v1/settings/sms', { params: { header: { 'If-Match': ifMatch(version) } }, body: change }),
      'Could not save the SMS settings.',
    ) as SmsSettings;
  },

  async optOuts(
    query: { q?: string; scope?: string; kind?: string; cursor?: string; limit?: number },
    signal?: AbortSignal,
  ): Promise<SmsOptOutPage> {
    return unwrap(
      await api.GET('/api/v1/sms/opt-outs', { params: { query }, signal }),
      'Could not load the opt-out list.',
    ) as SmsOptOutPage;
  },

  /** A reason is needed only to narrow a number already stopped for every message (3 to 200 characters, kept in the audit row). */
  async addOptOut(phone: string, scope: string, reason?: string): Promise<SmsOptOut> {
    return unwrap(
      await api.POST('/api/v1/sms/opt-outs', { body: { phone, scope, reason } }),
      'Could not add the number.',
    ) as SmsOptOut;
  },

  async importOptOuts(numbers: string, scope: string, dryRun: boolean): Promise<SmsImportResult> {
    return unwrap(
      await api.POST('/api/v1/sms/opt-outs/import', { body: { numbers, scope, dry_run: dryRun } }),
      'Could not read the list.',
    ) as SmsImportResult;
  },

  /** Lifting needs a reason (3 to 200 characters, kept in the audit row) and is the tenant administrator's. */
  async removeOptOut(id: string, reason: string): Promise<void> {
    const result = await api.DELETE('/api/v1/sms/opt-outs/{opt_out_id}', {
      params: { path: { opt_out_id: id } },
      body: { reason },
    });
    if (!result.response.ok) fail(result.error, result.response.status, 'Could not lift the opt-out.');
  },

  async templates(): Promise<SmsTemplate[]> {
    const list = unwrap(await api.GET('/api/v1/notification-templates'), 'Could not load the wording.');
    return ((list as { items?: SmsTemplate[] }).items ?? []) as SmsTemplate[];
  },

  async saveTemplate(eventKey: string, version: number, text: string): Promise<SmsTemplate> {
    return unwrap(
      await api.PUT('/api/v1/notification-templates/{event_key}/{channel}', {
        params: { path: { event_key: eventKey, channel: 'sms' }, header: { 'If-Match': ifMatch(version) } },
        body: { text },
      }),
      'Could not save the wording.',
    ) as SmsTemplate;
  },

  async revertTemplate(eventKey: string, version: number): Promise<SmsTemplate> {
    return unwrap(
      await api.DELETE('/api/v1/notification-templates/{event_key}/{channel}', {
        params: { path: { event_key: eventKey, channel: 'sms' }, header: { 'If-Match': ifMatch(version) } },
      }),
      'Could not go back to the default wording.',
    ) as SmsTemplate;
  },

  async previewTemplate(eventKey: string, text: string | undefined, signal?: AbortSignal): Promise<SmsPreview> {
    return unwrap(
      await api.POST('/api/v1/notification-templates/{event_key}/{channel}/preview', {
        params: { path: { event_key: eventKey, channel: 'sms' } },
        body: text === undefined ? {} : { text },
        signal,
      }),
      'Could not preview the wording.',
    ) as SmsPreview;
  },

  async testMessage(eventKey: string, text: string | undefined, to: string): Promise<SmsTestResult> {
    return unwrap(
      await api.POST('/api/v1/sms/test-message', { body: { event_key: eventKey, text, to } }),
      'Could not send the test message.',
    ) as SmsTestResult;
  },

  async memberOptOut(memberId: string): Promise<MemberSmsOptOut> {
    return unwrap(
      await api.GET('/api/v1/lending/members/{member_id}/sms-opt-out', { params: { path: { member_id: memberId } } }),
      'Could not load the SMS choice.',
    ) as MemberSmsOptOut;
  },

  /** Narrowing every message to reminders needs the administrator and a reason; any other change takes none. */
  async setMemberOptOut(memberId: string, scope: string, reason?: string): Promise<MemberSmsOptOut> {
    return unwrap(
      await api.PUT('/api/v1/lending/members/{member_id}/sms-opt-out', {
        params: { path: { member_id: memberId } },
        body: { scope, reason },
      }),
      'Could not save the SMS choice.',
    ) as MemberSmsOptOut;
  },

  async liftMemberOptOut(memberId: string, reason: string): Promise<MemberSmsOptOut> {
    return unwrap(
      await api.DELETE('/api/v1/lending/members/{member_id}/sms-opt-out', {
        params: { path: { member_id: memberId } },
        body: { reason },
      }),
      'Could not lift the opt-out.',
    ) as MemberSmsOptOut;
  },
};
