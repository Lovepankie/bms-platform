import type { Me } from '../../../api/client';
import type { DryRun, ImportIssue, ImportRun, RunMetrics } from '../../../api/imports';

// The import screens (issue #141, spec sections 2, 3 and 11): the steps of a run in plain words, what to do next, the plain name
// of each issue code and the permission each route declares. Kept apart from the pages so it is tested without one.

/** The permission each import route declares; the page shows NoAccess without it, and the API refuses the same. */
export const IMPORT_ROUTE_PERMISSION = {
  '/staff/setup/imports': 'core.imports.sign_off',
  '/staff/setup/imports/runs': 'core.imports.manage',
  '/staff/setup/imports/runs/$runId': 'core.imports.manage',
  '/staff/setup/imports/runs/$runId/problems': 'core.imports.manage',
  '/platform/imports': 'platform.tenants.read',
  '/platform/imports/$slug/$runId': 'platform.tenants.read',
} as const;

export type ImportRoute = keyof typeof IMPORT_ROUTE_PERMISSION;

/** Whether this person may open the route. */
export const mayOpen = (me: Pick<Me, 'permissions'>, route: ImportRoute) => (me.permissions ?? []).includes(IMPORT_ROUTE_PERMISSION[route]);

/** The operator portal's actions on a run (stop, start, resume, the override, the backup record, the kill switch) need this. */
export const PLATFORM_MANAGE = 'platform.tenants.manage';

/** Whether this operator may use the portal's import actions, not only read them. */
export const mayAct = (me: Pick<Me, 'permissions'> | undefined) => (me?.permissions ?? []).includes(PLATFORM_MANAGE);

/** The raw cells of a row are for this permission alone (spec section 6.3); every opening is audited by the server. */
export const VIEW_RAW = 'core.imports.view_raw';

/** The steps a shop owner sees, each with the run states that stand on it. */
export const STEPS = [
  { key: 'upload', label: 'Upload your files', states: ['draft'] },
  { key: 'check', label: 'Check the files', states: ['uploaded'] },
  { key: 'fix', label: 'Fix the problems and try it', states: ['in_review'] },
  { key: 'approve', label: 'A second person approves', states: ['ready'] },
  { key: 'import', label: 'Bring the data in', states: ['committing'] },
  { key: 'sign', label: 'Compare and sign', states: ['committed'] },
  { key: 'delete', label: 'Delete the copies', states: ['signed_off'] },
] as const;

export type StepState = 'done' | 'now' | 'later';

/** Each step's state for a run in `status`: the steps before its own are done; a purged run has every step done. */
export function stepStates(status: string | undefined): StepState[] {
  if (status === 'purged') return STEPS.map(() => 'done');
  const at = STEPS.findIndex((s) => (s.states as readonly string[]).includes(status ?? ''));
  if (at < 0) return STEPS.map(() => 'later');
  return STEPS.map((_, i) => (i < at ? 'done' : i === at ? 'now' : 'later'));
}

/** "Step 3 of 7" for the run, or a plain word for a run that left the steps. */
export function stepLine(status: string | undefined): string {
  if (status === 'purged') return 'Finished';
  if (status === 'cancelled') return 'Stopped for good';
  const at = stepStates(status).indexOf('now');
  return at < 0 ? 'Not started' : `Step ${at + 1} of ${STEPS.length}`;
}

/** A run's state in plain words. */
export function stateWords(status: string | undefined): string {
  switch (status) {
    case 'draft':
      return 'Waiting for files';
    case 'uploaded':
      return 'Files ready to check';
    case 'in_review':
      return 'Being checked';
    case 'ready':
      return 'Ready for approval';
    case 'committing':
      return 'Being brought in';
    case 'committed':
      return 'Brought in, waiting for sign off';
    case 'signed_off':
      return 'Signed';
    case 'purged':
      return 'Finished, copies deleted';
    case 'cancelled':
      return 'Stopped for good';
    default:
      return 'Unknown';
  }
}

/** The ten stages of spec section 2 in plain words, for the operator. */
export const STAGE_WORDS: Record<string, string> = {
  discovery: 'Finding the data',
  field_mapping: 'Matching the columns',
  cleaning_and_review: 'Cleaning and questions',
  rehearsal: 'Practice on a copy',
  dry_run: 'Trial run',
  cutover: 'Bringing it in',
  reconciliation: 'Comparing figures',
  sign_off: 'Customer signs',
  source_deletion: 'Deleting the copies',
  hypercare: 'Close support',
};

/** The stage a run stands at, from its metrics: the furthest stage that started. */
export function currentStage(metrics: Pick<RunMetrics, 'stages'> | undefined): { number: number; words: string } | null {
  const started = (metrics?.stages ?? []).filter((s) => s.started_at);
  const last = started[started.length - 1];
  if (!last) return null;
  return { number: last.number ?? 0, words: STAGE_WORDS[last.key ?? ''] ?? (last.key ?? '') };
}

/** What a holder of core.imports.manage does next on a run, as the one main action of the run's screen. */
export type NextStep =
  | { kind: 'upload' }
  | { kind: 'check' }
  | { kind: 'fix'; open: number }
  | { kind: 'try' }
  | { kind: 'ready' }
  | { kind: 'approval' }
  | { kind: 'wait'; words: string }
  | { kind: 'sign' }
  | { kind: 'certificate' };

/**
 * The next step of a run: files first, then the check, then every blocking problem and warning dealt with, then a trial run
 * that still counts and matched the preview, then ready, then the second person's approval, then the operator brings it in,
 * then the owner signs and the copies are deleted.
 */
export function nextStep(run: Pick<ImportRun, 'status' | 'commit_approved'>, files: number, open: number, dryRun: DryRun | null): NextStep {
  switch (run.status) {
    case 'draft':
      return { kind: 'upload' };
    case 'uploaded':
      return files === 0 ? { kind: 'upload' } : { kind: 'check' };
    case 'in_review':
      if (open > 0) return { kind: 'fix', open };
      if (!dryRun || !dryRun.current || !dryRun.preview_matches || dryRun.failure) return { kind: 'try' };
      return { kind: 'ready' };
    case 'ready':
      return run.commit_approved
        ? { kind: 'wait', words: 'Approved. Rincol Tech brings the data in at the agreed time.' }
        : { kind: 'approval' };
    case 'committing':
      return { kind: 'wait', words: 'The data is being brought in. This page updates by itself.' };
    case 'committed':
      return { kind: 'sign' };
    case 'signed_off':
      return { kind: 'wait', words: 'Signed. The copies of your files are deleted within 30 days.' };
    case 'purged':
      return { kind: 'certificate' };
    default:
      return { kind: 'wait', words: 'This import was stopped for good. Start a new one if you need to.' };
  }
}

/** Open problems that hold the run: blocking ones and warnings. An info note never holds it. */
export const holding = (issues: Pick<ImportIssue, 'severity'>[]) => issues.filter((i) => i.severity !== 'info').length;

/** The plain name of an issue code, for its group's heading. An unknown code reads as its words. */
export function codeTitle(code: string | undefined): string {
  const known: Record<string, string> = {
    REQUIRED_MISSING: 'Empty where a value is needed',
    TYPE_INVALID: 'Not the right kind of value',
    MONEY_FRACTION: 'Amounts with cents',
    QTY_PRECISION: 'Quantities with too many decimals',
    DUPLICATE_SOURCE_REF: 'The same line twice',
    REF_UNKNOWN: 'Names something that is not there',
    CODE_DUPLICATE: 'Two codes that are the same',
    CODE_CONTROL_CHAR: 'A hidden character in a code',
    NAME_MISSING: 'Items with no name',
    NEGATIVE_BALANCE: 'Stock below zero',
    PRICE_DIFFERS_EXISTING: 'Prices differ from the shop',
    CREDIT_SALE_UNPAID: 'Credit sales without payments',
    CREDIT_DECISION_MISSING: 'A decision about money owed is needed',
    DATE_AFTER_CUTOVER: 'Dates after the agreed day',
    ROW_CHANGED_SINCE_REVIEW: 'Lines changed since they were checked',
    CHANGED_SINCE_IMPORT: 'Lines already brought in with other values',
    ENCODING_CONVERTED: 'Letters read another way',
    COLUMN_UNMAPPED: 'A column nobody matched',
    TEXT_TOO_LONG: 'Text that is too long',
    VALUE_OUT_OF_RANGE: 'Values outside the allowed range',
    NIN_DUPLICATE: 'The same ID number twice',
    VALUE_NORMALISED: 'Values tidied',
    BLANK_ROW: 'Blank lines',
  };
  if (!code) return 'Other problems';
  return known[code] ?? code.toLowerCase().split('_').join(' ').replace(/^./, (c) => c.toUpperCase());
}

/** The severity in plain words. */
export function severityWords(severity: string | undefined): string {
  if (severity === 'blocking') return 'Must be fixed';
  if (severity === 'warning') return 'Check it';
  return 'For your notes';
}

export interface IssueGroup {
  code: string;
  severity: string;
  issues: ImportIssue[];
}

/** The open issues grouped by code, the groups that must be fixed first, then by size. */
export function groupByCode(issues: ImportIssue[]): IssueGroup[] {
  const groups = new Map<string, IssueGroup>();
  for (const issue of issues) {
    const code = issue.code ?? '';
    const group = groups.get(code) ?? { code, severity: issue.severity ?? 'info', issues: [] };
    if (issue.severity === 'blocking') group.severity = 'blocking';
    group.issues.push(issue);
    groups.set(code, group);
  }
  const rank = (s: string) => (s === 'blocking' ? 0 : s === 'warning' ? 1 : 2);
  return [...groups.values()].sort((a, b) => rank(a.severity) - rank(b.severity) || b.issues.length - a.issues.length || a.code.localeCompare(b.code));
}

/**
 * How many open problems of one kind the run has (issue #897): the server's count from the run's metrics, every severity of
 * the code, which is what bulk-resolve acts on. Never the length of the listed lines, which stop at ISSUES_SHOWN. Null while
 * the metrics are not in.
 */
export function openOfKind(metrics: Pick<RunMetrics, 'issues'> | undefined, code: string): number | null {
  if (!metrics) return null;
  return (metrics.issues ?? []).filter((i) => i.code === code).reduce((n, i) => n + (i.open ?? 0), 0);
}

/** "Line 7, phone": where an issue is. */
export function issuePlace(issue: Pick<ImportIssue, 'line' | 'field'>): string {
  return issue.field ? `Line ${issue.line}, ${issue.field.split('_').join(' ')}` : `Line ${issue.line}`;
}

/** The ways to deal with every problem of one code at once, as the bulk route takes them. */
export const BULK_WAYS = [
  { value: 'accept_suggestion', label: 'Use the suggestion' },
  { value: 'exclude_row', label: 'Leave the lines out' },
  { value: 'override', label: 'Accept as it is' },
  { value: 'ask_customer', label: 'Ask the owner' },
] as const;

/**
 * The ways open to one group of `count` open problems: accepting as it is is for warnings only, the suggestion only where every
 * issue has one, so only when every one of them is listed (issue #897); the server refuses the whole of it otherwise.
 */
export function bulkWays(group: IssueGroup, count = group.issues.length) {
  return BULK_WAYS.filter((w) => {
    if (w.value === 'override') return group.severity === 'warning';
    if (w.value === 'accept_suggestion') return count <= group.issues.length && group.issues.every((i) => i.suggestion && Object.keys(i.suggestion).length > 0);
    return true;
  });
}

/** Leaving lines out and accepting as it is keep a reason (spec section 3.5). */
export const needsNote = (way: string) => way === 'exclude_row' || way === 'override';

/** A quantity as an exact decimal string, grouped by thousands, never rounded. */
export function quantity(value: number | string | undefined): string {
  const text = String(value ?? 0);
  const m = /^(-?)(\d+)(\.\d+)?$/.exec(text);
  if (!m) return text;
  return `${m[1]}${(m[2] ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${m[3] ?? ''}`;
}
