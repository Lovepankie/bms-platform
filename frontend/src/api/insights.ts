import { api, problemOf } from './client';
import { getAccessToken, tenantHeaders } from '../auth/session';
import type { components } from './schema';

// The insights client (#153, chapter 7 section 7.11.21), typed by the generated schema. Every
// route takes the same page filter; the page polls every 60 seconds and the server never caches.
// Money is integer minor units; rates are basis points.

type S = components['schemas'];

export type InsightsMetric = S['InsightsMetric'];
export type InsightsDrill = S['InsightsDrill'];
export type InsightsBrief = S['InsightsBrief'];
export type InsightsPortfolio = S['InsightsPortfolio'];
export type InsightsPeriod = S['InsightsPeriod'];
export type InsightsRevenue = S['InsightsRevenue'];
export type InsightsMembers = S['InsightsMembers'];
export type InsightsPanels = S['InsightsPanels'];
export type InsightsTable = S['InsightsTable'];
export type InsightsDigestSettings = S['InsightsDigestSettings'];
export type InsightsDigestPreview = S['InsightsDigestPreview'];

/** The page filter; dates are yyyy-mm-dd, empty means the server default. */
export interface InsightsFilter {
  from?: string;
  to?: string;
  grain?: string;
  branchIds?: string[];
  officerId?: string;
  productId?: string;
}

export class InsightsError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string> = {
  range_in_future: 'The range must end today or earlier.',
  invalid_range: 'The start date must be on or before the end date.',
  range_too_long: 'Pick a range of five years or less.',
  too_many_periods: 'That range has too many periods for this grouping. Pick a larger one, such as months.',
  invalid_grain: 'Pick days, weeks, months or years.',
  digest_needs_recipient: 'Add an email address or a Telegram chat before switching the digest on.',
  version_mismatch: 'Someone changed these settings. Reload and try again.',
};

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    const status = result.response.status;
    const message =
      (problem.code && MESSAGES[problem.code]) ||
      (status === 403 ? 'You do not have access to this.' : problem.detail) ||
      'Could not load the insights. Try again.';
    throw new InsightsError(message, status, problem.code);
  }
  return result.data;
}

/** The filter as query parameters (absent values left out). */
export function filterQuery(f: InsightsFilter) {
  return {
    from: f.from || undefined,
    to: f.to || undefined,
    branch_id: f.branchIds && f.branchIds.length > 0 ? f.branchIds : undefined,
    officer_id: f.officerId || undefined,
    product_id: f.productId || undefined,
  };
}

/** The filter plus a table's own parameters, as a URL query string for the CSV export. */
export function exportQueryString(f: InsightsFilter, params: Record<string, string>): string {
  const q = new URLSearchParams();
  const base = filterQuery(f);
  if (base.from) q.set('from', base.from);
  if (base.to) q.set('to', base.to);
  (base.branch_id ?? []).forEach((b) => q.append('branch_id', b));
  if (base.officer_id) q.set('officer_id', base.officer_id);
  if (base.product_id) q.set('product_id', base.product_id);
  Object.entries(params).forEach(([k, v]) => q.set(k, v));
  return q.toString();
}

export const insights = {
  async brief(f: InsightsFilter): Promise<InsightsBrief> {
    const q = filterQuery(f);
    return unwrap(
      await api.GET('/api/v1/lending/insights/brief', {
        params: { query: { branch_id: q.branch_id, officer_id: q.officer_id, product_id: q.product_id } },
      }),
    );
  },

  async portfolio(f: InsightsFilter): Promise<InsightsPortfolio> {
    return unwrap(
      await api.GET('/api/v1/lending/insights/portfolio', {
        params: { query: { ...filterQuery(f), grain: f.grain || undefined } },
      }),
    );
  },

  async revenue(f: InsightsFilter): Promise<InsightsRevenue> {
    return unwrap(await api.GET('/api/v1/lending/insights/revenue', { params: { query: filterQuery(f) } }));
  },

  async members(f: InsightsFilter): Promise<InsightsMembers> {
    return unwrap(
      await api.GET('/api/v1/lending/insights/members', {
        params: { query: { ...filterQuery(f), grain: f.grain || undefined } },
      }),
    );
  },

  async panels(f: InsightsFilter): Promise<InsightsPanels> {
    const q = filterQuery(f);
    return unwrap(
      await api.GET('/api/v1/lending/insights/panels', {
        params: { query: { from: q.from, to: q.to, branch_id: q.branch_id, officer_id: q.officer_id } },
      }),
    );
  },

  async table(table: string, f: InsightsFilter, params: Record<string, string>): Promise<InsightsTable> {
    // The table's own parameters (date, min_dpd, stage...) travel next to the page filter.
    const query = { ...filterQuery(f), ...params } as Record<string, unknown>;
    return unwrap(
      await api.GET('/api/v1/lending/insights/tables/{table}', {
        params: { path: { table }, query: query as never },
      }),
    );
  },

  /**
   * Downloads a table as CSV. A plain link cannot carry the bearer token, so the file is fetched
   * and handed to the browser as a blob.
   */
  async exportCsv(table: string, f: InsightsFilter, params: Record<string, string>): Promise<void> {
    const headers = new Headers(tenantHeaders());
    const token = getAccessToken();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    const response = await fetch(
      `/api/v1/lending/insights/tables/${encodeURIComponent(table)}/export?${exportQueryString(f, params)}`,
      { headers },
    );
    if (!response.ok) {
      throw new InsightsError(response.status === 403 ? 'You may not export this table.' : 'The export failed. Try again.', response.status);
    }
    const blob = await response.blob();
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const name = /filename="([^"]+)"/.exec(disposition)?.[1] ?? `insights-${table}.csv`;
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  },

  async digestSettings(): Promise<InsightsDigestSettings> {
    return unwrap(await api.GET('/api/v1/lending/insights/digest'));
  },

  async updateDigest(body: S['InsightsDigestSettingsRequest'], version: number): Promise<InsightsDigestSettings> {
    return unwrap(
      await api.PUT('/api/v1/lending/insights/digest', { params: { header: { 'If-Match': `"${version}"` } }, body }),
    );
  },

  async digestPreview(): Promise<InsightsDigestPreview> {
    return unwrap(await api.GET('/api/v1/lending/insights/digest/preview'));
  },
};
