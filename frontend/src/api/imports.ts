import { api, problemOf } from './client';
import type { components } from './schema';

// The reconciliation of an imported run and the customer's sign-off (issue #134, spec sections 3.9 and 3.10, FR-IMP-08): the
// tenant admin reads what the import brought in against their own sheet and signs it. Typed by the generated schema.

type S = components['schemas'];

export type SignOffRun = S['ImportSignOffRun'];
export type Reconciliation = S['ImportReconciliation'];
export type ReconciliationSection = S['ImportReconciliationSection'];
export type ReconciliationCheck = S['ImportReconciliationCheck'];
export type ReconciliationItem = S['ImportReconciliationItem'];
export type SignOffRequest = S['ImportSignOffRequest'];

// The screens of the whole pipeline (issue #141): the business's runs, files, mapping choice, check, problems, preview, dry run,
// approval request and certificate, and the operator portal's queue and actions.
export type ImportRun = S['ImportRun'];
export type ImportFile = S['ImportFile'];
export type MappingChoice = S['ImportMappingChoice'];
export type CheckResult = S['ImportCheckResult'];
export type ImportIssue = S['ImportIssue'];
export type ImportPreview = S['ImportPreview'];
export type DryRun = S['ImportDryRun'];
export type Balances = S['ImportBalances'];
export type Certificate = S['ImportDeletionCertificate'];
export type RawRow = S['ImportRawRow'];
export type RunMetrics = S['ImportRunMetrics'];
export type ResolveRequest = S['ImportResolveRequest'];
export type BulkResolveRequest = S['ImportBulkResolveRequest'];
export type PlatformRun = S['PlatformImportRun'];
export type PlatformRunState = S['PlatformImportRunState'];

/** A refusal with the API's code, so the screen can answer reconciliation_changed by showing the newest figures. */
export class ImportError extends Error {
  constructor(
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}

function fail(error: unknown, fallback: string): never {
  const problem = problemOf(error);
  const fields = problem.errors ?? [];
  throw new ImportError(fields.length > 0 ? fields.map((f) => f.message).join(' ') : (problem.detail ?? fallback), problem.code);
}

export async function signOffRuns(): Promise<SignOffRun[]> {
  const { data, error } = await api.GET('/api/v1/imports/sign-off');
  if (error || !data) fail(error, 'Could not load the imports to sign off.');
  return data.items ?? [];
}

export async function reconciliationOf(runId: string): Promise<Reconciliation> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/reconciliation', { params: { path: { run_id: runId } } });
  if (error || !data) fail(error, 'Could not load the reconciliation of this import.');
  return data;
}

/** Makes the reconciliation again from the source and the books (core.imports.manage): after a correction, or when none was made. */
export async function reconcileAgain(runId: string): Promise<Reconciliation> {
  const { data, error } = await api.POST('/api/v1/imports/runs/{run_id}/reconciliation', { params: { path: { run_id: runId } } });
  if (error || !data) fail(error, 'Could not make the reconciliation. Try again.');
  return data;
}

export async function signOff(runId: string, body: SignOffRequest): Promise<Reconciliation> {
  const { data, error } = await api.POST('/api/v1/imports/runs/{run_id}/sign-off', { params: { path: { run_id: runId } }, body });
  if (error || !data) fail(error, 'Could not sign. Nothing was signed.');
  return data;
}

/** Saves the reconciliation as the plain text that is signed, with the sign-off once signed, on this device. */
export async function downloadReconciliation(runId: string, version: number): Promise<void> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/reconciliation.txt', {
    params: { path: { run_id: runId } },
    parseAs: 'blob',
  });
  if (error || !data) fail(error, 'Could not download the reconciliation. Try again.');
  saveFile(data as Blob, `import-reconciliation-${version}.txt`);
}

/** Saves a file on this device under `name`. */
export function saveFile(data: Blob, name: string): void {
  const url = URL.createObjectURL(data);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoked later: revoking right after the click can cancel the download in Safari and Firefox.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// JUSTIFICATION-A3: issue #141 puts a screen on every route of the pipeline; one typed call per route, as above.
const run = (runId: string) => ({ params: { path: { run_id: runId } } });

export async function importRuns(): Promise<ImportRun[]> {
  const { data, error } = await api.GET('/api/v1/imports/runs');
  if (error || !data) fail(error, 'Could not load the imports.');
  return data.items ?? [];
}

export async function startImport(): Promise<ImportRun> {
  const { data, error } = await api.POST('/api/v1/imports/runs');
  if (error || !data) fail(error, 'Could not start an import. Try again.');
  return data;
}

export async function importRun(runId: string): Promise<ImportRun> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}', run(runId));
  if (error || !data) fail(error, 'Could not load this import.');
  return data;
}

export async function importFiles(runId: string): Promise<ImportFile[]> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/files', run(runId));
  if (error || !data) fail(error, 'Could not load the files of this import.');
  return data.items ?? [];
}

/** The most sheet layouts the server lists (MappingService.NEWEST_LIMIT); the upload sheet says so when it holds this many. */
export const MAPPINGS_LISTED = 200;

export async function mappingChoices(): Promise<MappingChoice[]> {
  const { data, error } = await api.GET('/api/v1/imports/mappings');
  if (error || !data) fail(error, 'Could not load the sheet layouts.');
  return data.items ?? [];
}

/** Streams the file itself as the body: the server stores it encrypted in the quarantine, then reads it with the mapping. */
export async function uploadFile(runId: string, file: File, mappingId: string, source: string): Promise<ImportFile> {
  const { data, error } = await api.POST('/api/v1/imports/runs/{run_id}/files', {
    params: { path: { run_id: runId }, query: { file_name: file.name, mapping_id: mappingId, source } },
    body: file as unknown as string,
    bodySerializer: (body) => body as unknown as Blob,
    headers: { 'Content-Type': 'application/octet-stream' },
  });
  if (error || !data) fail(error, 'Could not upload the file. Nothing was kept.');
  return data;
}

export async function removeFile(batchId: string): Promise<ImportFile> {
  const { data, error } = await api.POST('/api/v1/imports/files/{batch_id}/cancel', { params: { path: { batch_id: batchId } } });
  if (error || !data) fail(error, 'Could not take the file out. Try again.');
  return data;
}

export async function checkFiles(runId: string): Promise<CheckResult> {
  const { data, error } = await api.POST('/api/v1/imports/runs/{run_id}/check', run(runId));
  if (error || !data) fail(error, 'Could not check the files. Try again.');
  return data;
}

export async function runMetrics(runId: string): Promise<RunMetrics> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/metrics', run(runId));
  if (error || !data) fail(error, 'Could not load where this import stands.');
  return data;
}

/** The most open issues one screen lists; the counts of each kind come from the run's metrics, never from this list. */
export const ISSUES_SHOWN = 2000;

/** The open issues of the run, read a page of 500 at a time, at most ISSUES_SHOWN; `more` is true when some were not read. */
export async function openIssues(runId: string): Promise<{ items: ImportIssue[]; more: boolean }> {
  const items: ImportIssue[] = [];
  let cursor: string | undefined;
  do {
    const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/issues', {
      params: { path: { run_id: runId }, query: { status: 'open', limit: 500, cursor } },
    });
    if (error || !data) fail(error, 'Could not load the problems.');
    items.push(...(data.items ?? []));
    cursor = data.next_cursor ?? undefined;
  } while (cursor && items.length < ISSUES_SHOWN);
  return { items, more: cursor !== undefined };
}

export async function resolveIssue(issueId: string, body: ResolveRequest): Promise<void> {
  const { error } = await api.POST('/api/v1/imports/issues/{issue_id}/resolve', { params: { path: { issue_id: issueId } }, body });
  if (error) fail(error, 'Could not save the fix. Nothing was changed.');
}

export async function resolveAll(runId: string, body: BulkResolveRequest): Promise<number> {
  const { data, error } = await api.POST('/api/v1/imports/runs/{run_id}/issues/bulk-resolve', { ...run(runId), body });
  if (error || !data) fail(error, 'Could not fix them all. Nothing was changed.');
  return data.resolved ?? 0;
}

export async function askCustomer(issueId: string, version: number, ask: boolean): Promise<void> {
  const { error } = await api.PUT('/api/v1/imports/issues/{issue_id}/ask-customer', {
    params: { path: { issue_id: issueId } },
    body: { ask_customer: ask, version },
  });
  if (error) fail(error, 'Could not change the question list. Try again.');
}

export async function downloadQuestions(runId: string): Promise<void> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/questions.csv', { ...run(runId), parseAs: 'blob' });
  if (error || !data) fail(error, 'Could not download the questions. Try again.');
  saveFile(data as Blob, 'import-questions.csv');
}

/** The row as uploaded (core.imports.view_raw); every opening is audited by the server. */
export async function rawRow(issueId: string): Promise<RawRow> {
  const { data, error } = await api.GET('/api/v1/imports/issues/{issue_id}/raw', { params: { path: { issue_id: issueId } } });
  if (error || !data) fail(error, 'Could not show the row as uploaded.');
  return data;
}

export async function previewOf(runId: string): Promise<ImportPreview> {
  const { data, error } = await api.GET('/api/v1/imports/runs/{run_id}/preview', run(runId));
  if (error || !data) fail(error, 'Could not show what will come in.');
  return data;
}

/** The stored dry run, or null while none ran. */
export async function dryRunOf(runId: string): Promise<DryRun | null> {
  const { data, error, response } = await api.GET('/api/v1/imports/runs/{run_id}/dry-run', run(runId));
  if (response.status === 404) return null;
  if (error || !data) fail(error, 'Could not load the trial run.');
  return data;
}

export async function tryImport(runId: string): Promise<DryRun> {
  const { data, error } = await api.POST('/api/v1/imports/runs/{run_id}/dry-run', run(runId));
  if (error || !data) fail(error, 'The trial run did not finish. Nothing was written.');
  return data;
}

export async function markReady(runId: string): Promise<ImportRun> {
  const { data, error } = await api.POST('/api/v1/imports/runs/{run_id}/ready', run(runId));
  if (error || !data) fail(error, 'Could not mark it ready.');
  return data;
}

export async function askApproval(runId: string): Promise<void> {
  const { error } = await api.POST('/api/v1/imports/runs/{run_id}/commit-request', run(runId));
  if (error) fail(error, 'Could not ask for approval. Try again.');
}

/** The certificate of deletion, or null until the copies are deleted. */
export async function certificateOf(runId: string): Promise<Certificate | null> {
  const { data, error, response } = await api.GET('/api/v1/imports/runs/{run_id}/certificate', run(runId));
  if (response.status === 404) return null;
  if (error || !data) fail(error, 'Could not load the certificate of deletion.');
  return data;
}

// The operator portal (platform host): the queue is the platform report; an action names the business by its slug.

export async function platformRuns(): Promise<PlatformRun[]> {
  const { data, error } = await api.GET('/api/v1/platform/imports/report', { params: { query: { limit: 200 } } });
  if (error || !data) fail(error, 'Could not load the imports.');
  return data.items ?? [];
}

const at = (slug: string, runId: string) => ({ params: { path: { slug, run_id: runId } } });

export async function platformRun(slug: string, runId: string): Promise<PlatformRunState> {
  const { data, error } = await api.GET('/api/v1/platform/imports/businesses/{slug}/runs/{run_id}', at(slug, runId));
  if (error || !data) fail(error, 'Could not load this import.');
  return data;
}

export type RunAction = 'stop' | 'start' | 'resume' | 'approve-override';

export async function platformAction(slug: string, runId: string, action: RunAction): Promise<PlatformRunState> {
  const result =
    action === 'stop'
      ? await api.POST('/api/v1/platform/imports/businesses/{slug}/runs/{run_id}/stop', at(slug, runId))
      : action === 'start'
        ? await api.POST('/api/v1/platform/imports/businesses/{slug}/runs/{run_id}/start', at(slug, runId))
        : action === 'resume'
          ? await api.POST('/api/v1/platform/imports/businesses/{slug}/runs/{run_id}/resume', at(slug, runId))
          : await api.POST('/api/v1/platform/imports/businesses/{slug}/runs/{run_id}/trading-override/approve', at(slug, runId));
  const { data, error } = result as { data?: PlatformRunState; error?: unknown };
  if (error || !data) fail(error, 'That did not go through. Nothing was changed.');
  return data;
}

export async function askTradingOverride(slug: string, runId: string, reason: string): Promise<PlatformRunState> {
  const { data, error } = await api.POST('/api/v1/platform/imports/businesses/{slug}/runs/{run_id}/trading-override', {
    ...at(slug, runId),
    body: { reason },
  });
  if (error || !data) fail(error, 'Could not ask for the override. Nothing was changed.');
  return data;
}

export async function recordDump(slug: string, runId: string, file: string, takenAt: string, sha256: string): Promise<PlatformRunState> {
  const { data, error } = await api.POST('/api/v1/platform/imports/businesses/{slug}/runs/{run_id}/dump', {
    ...at(slug, runId),
    body: { file, taken_at: takenAt, sha256 },
  });
  if (error || !data) fail(error, 'Could not record the dump. Nothing was changed.');
  return data;
}

export async function checkDump(slug: string, runId: string, sha256: string, listedEntries: number): Promise<PlatformRunState> {
  const { data, error } = await api.POST('/api/v1/platform/imports/businesses/{slug}/runs/{run_id}/dump/check', {
    ...at(slug, runId),
    body: { sha256, listed_entries: listedEntries },
  });
  if (error || !data) fail(error, 'Could not record the check. Nothing was changed.');
  return data;
}

export async function killSwitch(): Promise<boolean> {
  const { data, error } = await api.GET('/api/v1/platform/imports/kill-switch');
  if (error || !data) fail(error, 'Could not load whether imports may run.');
  return data.commit_enabled ?? false;
}

export async function setKillSwitch(enabled: boolean, reason: string): Promise<boolean> {
  const { data, error } = await api.PUT('/api/v1/platform/imports/kill-switch', { body: { commit_enabled: enabled, reason } });
  if (error || !data) fail(error, 'Could not change it. Nothing was changed.');
  return data.commit_enabled ?? false;
}
