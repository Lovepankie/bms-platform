import { api, problemOf } from './client';
import type { components } from './schema';

// The report runs client (extras slice D1, issue #214; chapter 7 section 7.11.8; ADR-038). A run is queued, then
// executed by a job; the screen polls until it is ready and fetches the file through the signed URL path (FR-DOC-03).
// Money in a file is integer minor units. Error codes are plain codes, never text from the data.

// The generated schema marks every field optional, as for the rest of the API; these are the shapes the server always
// sends (src/main/java/.../core/reporting/internal/ReportsApi.java), so the screen reads them without guards.
export interface ReportCatalogueParam {
  name: string;
  /** date, uuid_list, enum, integer or text */
  type: string;
  required: boolean;
  description: string;
  values: string[];
}

export interface ReportCatalogueEntry {
  key: string;
  title: string;
  description: string;
  permission: string;
  formats: string[];
  params: ReportCatalogueParam[];
}

export interface ReportRun {
  id: string;
  report_key: string;
  title: string;
  format: string;
  /** queued, running, ready, failed or cancelled */
  status: string;
  params: Record<string, unknown>;
  as_of: string;
  snapshot_at: string | null;
  requested_at: string;
  started_at: string | null;
  finished_at: string | null;
  row_count: number | null;
  size_bytes: number | null;
  document_id: string | null;
  expires_at: string | null;
  expired: boolean;
  cancel_requested: boolean;
  error: string | null;
}

// Keeps the generated types honest: a change to the contract that drops a field breaks this line, not the screen.
export type GeneratedReportRun = components['schemas']['ReportRun'];

export class ReportsError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

/** What a run's error code means to the person who asked for it. */
const RUN_ERRORS: Record<string, string | undefined> = {
  report_too_large: 'This report is too large to produce. Choose fewer branches or a shorter range and run it again.',
  snapshot_unavailable: 'The loan book was not saved for that date. Choose a later date.',
  timeout: 'The report took too long and was stopped. Choose fewer branches or a later date and run it again.',
  interrupted: 'The report was interrupted by a restart. Run it again.',
  reports_disabled: 'Reports were switched off before this one started.',
  requester_deactivated: 'The report was not run because your access has ended.',
  report_unavailable: 'This report is no longer offered.',
  run_failed: 'The report could not be produced. Try again, and tell your administrator if it keeps failing.',
};

export function runErrorText(code: string | null | undefined): string {
  if (!code) return '';
  return RUN_ERRORS[code] ?? RUN_ERRORS.run_failed ?? '';
}

const MESSAGES: Record<string, string | undefined> = {
  reports_not_enabled: 'Reports are switched off for this business.',
  report_not_found: 'That report is not available to you.',
  rate_limited: 'You have asked for many reports in a minute. Wait a moment and try again.',
  run_finished: 'That report has already finished.',
  report_expired: 'This report was deleted after 30 days. Run it again.',
  snapshot_unavailable: 'The loan book was not saved for that date. Choose a later date.',
  range_in_future: 'Choose today or an earlier date.',
  permission_denied: 'You do not have permission for this report.',
};

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  const fields = (problem.errors ?? []).map((e) => e.message).join(' ');
  const message = (problem.code && MESSAGES[problem.code]) || fields || (status === 403 ? MESSAGES.permission_denied ?? fallback : fallback);
  throw new ReportsError(message, status, problem.code);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    fail(result.error, result.response.status, fallback);
  }
  return result.data;
}

/** Statuses that will still change: the screen polls while any run is in one. */
export const OPEN_STATUSES = ['queued', 'running'];

export const reports = {
  async catalogue(): Promise<ReportCatalogueEntry[]> {
    return unwrap(await api.GET('/api/v1/reports'), 'Could not load the reports.') as ReportCatalogueEntry[];
  },

  async runs(): Promise<ReportRun[]> {
    return unwrap(await api.GET('/api/v1/report-runs'), 'Could not load your reports.') as ReportRun[];
  },

  async request(reportKey: string, params: Record<string, unknown>, format: string): Promise<ReportRun> {
    // A key per click makes a retry after a lost answer the same request.
    const key = `report-${crypto.randomUUID()}`;
    return unwrap(
      await api.POST('/api/v1/report-runs', {
        params: { header: { 'Idempotency-Key': key } },
        body: { report_key: reportKey, params: params as never, format },
      }),
      'Could not start the report.',
    ) as ReportRun;
  },

  async cancel(runId: string): Promise<ReportRun> {
    return unwrap(
      await api.POST('/api/v1/report-runs/{run_id}/cancel', { params: { path: { run_id: runId } } }),
      'Could not cancel the report.',
    ) as ReportRun;
  },

  /** The signed address of a ready run's file; it works for 5 minutes. */
  async downloadUrl(documentId: string): Promise<string> {
    const result = await api.POST('/api/v1/documents/{document_id}/download-url', {
      params: { path: { document_id: documentId } },
    });
    return unwrap(result, 'Could not prepare the download.').url as string;
  },
};
