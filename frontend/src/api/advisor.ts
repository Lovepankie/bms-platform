import { api, problemOf } from './client';
import type { components } from './schema';

// The AI assistant client (extras slice C3, issue #226; chapter 7 section 7.11.24; ADR-037): today's brief and the settings.
// The generated schema marks every field optional, as for the rest of the API; these are the shapes the server always sends
// (lending/advisor/internal/AdvisorApi.java). Every AI text carries `label` and `note`: the card shows both.

export interface AdvisorPoint {
  text: string;
  facts: string[];
}

export interface AdvisorFact {
  key: string;
  label: string;
  display: string;
  /** The insights metric whose rows open from this figure, or null (a group figure or a change) */
  metric: string | null;
}

export interface AdvisorBrief {
  advisory: true;
  /** model: written from a structured model answer by our code. fallback: the automatic summary */
  source: 'model' | 'fallback';
  /** off, scope, validation, provider_down, cap, not_ready or unavailable; null for a model brief */
  reason: string | null;
  label: string;
  as_of: string;
  facts_as_of: string | null;
  summary: string;
  points: AdvisorPoint[];
  facts: AdvisorFact[];
  note: string;
}

export interface AdvisorAcknowledgement {
  text_version: string;
  text: string;
}

export interface AdvisorSettings {
  enabled: boolean;
  allowed: boolean;
  provider_configured: boolean;
  daily_token_cap: number;
  daily_cost_cap_micro: number;
  cost_currency: string;
  tokens_used_today: number;
  cost_used_today_micro: number;
  acknowledged_by: string | null;
  acknowledged_at: string | null;
  acknowledged_text_version: string | null;
  acknowledgement: AdvisorAcknowledgement;
  version: number;
}

/** A partial change; an omitted field is unchanged. */
export interface AdvisorSettingsChange {
  enabled?: boolean;
  acknowledged_text_version?: string;
  daily_token_cap?: number;
  daily_cost_cap_micro?: number;
}

// Keeps the generated types honest: a change to the contract that drops one of these breaks this line, not a screen.
export type GeneratedAdvisorBrief = components['schemas']['AdvisorBrief'];
export type GeneratedAdvisorSettings = components['schemas']['AdvisorSettings'];

export class AdvisorError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string | undefined> = {
  advisor_not_available: 'The AI assistant is not available for your business yet. Ask the platform operator.',
  acknowledgement_required: 'Read the notice and tick the box to switch the assistant on.',
  version_conflict: 'Someone else changed this. The latest has been loaded; check it and try again.',
  precondition_required: 'This page is out of date. Reload it and try again.',
  permission_denied: 'You do not have permission to do this.',
  above_platform_ceiling: 'Above the platform limit for a day.',
};

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  const first = (problem.errors ?? [])
    .map((e) => (e as { code?: string }).code)
    .map((c) => (c ? MESSAGES[c] : undefined))
    .find((m) => m);
  const message = (problem.code && MESSAGES[problem.code]) || first || problem.detail || fallback;
  throw new AdvisorError(message, status, problem.code);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    fail(result.error, result.response.status, fallback);
  }
  return result.data;
}

const ifMatch = (version: number) => `"${version}"`;

export const advisor = {
  /** Null when the assistant is off for the business (204): the card is not drawn at all. */
  async brief(): Promise<AdvisorBrief | null> {
    const result = await api.GET('/api/v1/lending/advisor/brief');
    if (result.response.status === 204) return null;
    return unwrap(result, 'Could not load the summary.') as AdvisorBrief;
  },

  async settings(): Promise<AdvisorSettings> {
    return unwrap(await api.GET('/api/v1/lending/advisor/settings'), 'Could not load the assistant settings.') as AdvisorSettings;
  },

  async changeSettings(version: number, change: AdvisorSettingsChange): Promise<AdvisorSettings> {
    return unwrap(
      await api.PUT('/api/v1/lending/advisor/settings', { params: { header: { 'If-Match': ifMatch(version) } }, body: change }),
      'Could not save the assistant settings.',
    ) as AdvisorSettings;
  },
};
