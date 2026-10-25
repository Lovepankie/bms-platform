import { problemOf } from './client';
import { LendingError, lendingMessage } from './lending';
import type { RetailProblem } from './retail-errors';
import { getAccessToken, refreshSession, tenantHeaders } from '../auth/session';

// What the member and collateral forms share on the wire (issue #280): a failed call that carries
// its field problems in plain words (never the API's field names), and the one file upload with
// progress. Field keys are the form's own (`phone`, `national_id`), so a screen ties a message to
// the input that caused it with aria-describedby and moves focus to the first one.

/** Plain words per form field; the key is the API's field name, which is also the form's. */
export type FieldMessages = Record<string, string>;

const BY_CODE: Record<string, string> = {
  duplicate_phone: 'Another member already has this phone number. Check it is a different person, then tick the box to go on.',
  invalid_phone: 'That does not look like a Uganda mobile number. Use 07..., +256 7... or 2567...',
  invalid_nin: 'A national ID number starts with CM or CF and has 14 letters and digits in all.',
  next_of_kin_required: 'A member whose details are complete keeps at least one next of kin.',
  version_conflict: 'Someone else changed this since you opened it. Your entries are kept. Open the latest version to compare, then save again.',
  duplicate_nin: 'This national ID number is already registered to another member.',
  invalid_status_transition: 'This is not possible in the state it is in now. Reload the page to see where it stands.',
  approval_already_pending: 'A request for this is already waiting in the approvals inbox.',
  self_approval_forbidden: 'Someone else must approve this.',
  precondition_required: 'This could not be saved safely. Reload the page and try again.',
  collateral_already_pledged: 'An item of this type with this reference number is already registered and not released.',
  collateral_type_disabled: 'This business does not accept that kind of collateral.',
  collateral_secures_open_loan: 'This item secures a loan that is not closed yet, so it cannot be released.',
  file_too_large: 'The file is bigger than 5 MB. Take the photo again, or choose a smaller one.',
  unsupported_file_type: 'Use a JPEG or PNG photo, or a PDF.',
  svg_not_allowed: 'Use a JPEG or PNG photo, or a PDF.',
  unknown_branch: 'You cannot add members to that branch. Choose another branch.',
  unknown_member: 'That member is not in your branches. Search for the member again.',
  member_blacklisted: 'This member is blacklisted.',
  plan_limit_reached: 'Your plan has no room for more members. Ask the business owner to change the plan.',
};

const FIELD_BY_CODE: Record<string, string> = {
  invalid_phone: 'Not a Uganda mobile number. Use 07..., +256 7... or 2567...',
  invalid_nin: 'A national ID number starts with CM or CF and has 14 letters and digits in all.',
  required: 'Fill this in.',
  unknown_branch: 'Choose a branch you work in.',
  unknown_member: 'Search for the member again.',
};

/** The message for a failed call in plain words. */
export function formMessage(problem: RetailProblem | undefined, status: number): string {
  if (problem?.code === 'duplicate_nin' && problem.detail) return problem.detail;
  const known = problem?.code ? BY_CODE[problem.code] : undefined;
  return known ?? lendingMessage(problem, status);
}

/** Refusals that the server sends without a field list, tied to the input that caused them. */
const FIELD_OF_CODE: Record<string, string> = {
  duplicate_nin: 'national_id',
  duplicate_phone: 'phone',
  invalid_nin: 'national_id',
  invalid_phone: 'phone',
  collateral_already_pledged: 'reference_no',
};

/** The API's field problems as plain words per field. A code with no words of its own shows a short, neutral line. */
export function fieldMessages(problem: RetailProblem | undefined): FieldMessages {
  const out: FieldMessages = {};
  for (const e of problem?.errors ?? []) {
    if (!e.field || out[e.field]) continue;
    out[e.field] = (e.code && FIELD_BY_CODE[e.code]) || 'Check this entry.';
  }
  const own = problem?.code ? FIELD_OF_CODE[problem.code] : undefined;
  if (own && !out[own]) {
    // A duplicate names the other record in the server's detail (a member number), which helps; the rest use the plain line.
    const named = problem?.code === 'duplicate_nin' || problem?.code === 'duplicate_phone';
    out[own] = (named && problem?.detail) || BY_CODE[problem?.code ?? ''] || 'Check this entry.';
  }
  return out;
}

/**
 * True when the call may or may not have been saved: no answer (a dropped connection, a timeout, a stopped request), a server
 * error, or anything that is not an answer from the server at all. Look the record up before sending it again.
 */
export function unknownOutcome(e: unknown): boolean {
  if (e instanceof FormError) return e.status === 0 || e.status >= 500;
  return e instanceof Error;
}

/** What a failed fetch (a dropped connection, a stopped request) becomes: no answer was seen, so the outcome is unknown. */
export function asFormError(e: unknown): FormError {
  if (e instanceof FormError) return e;
  const stopped = (e as { name?: string } | null)?.name === 'AbortError';
  return stopped
    ? new FormError('The request was stopped.', 0, 'aborted')
    : new FormError(problemOf(e).code === 'app_updating' ? (problemOf(e).detail as string) : 'The connection dropped before an answer came back. Check the connection.', 0, 'network');
}

/** The same client with every call's failures as FormError, so a screen sees a lost answer as one (status 0), never a raw TypeError. */
export function guarded<T extends object>(client: T): T {
  const out: Record<string, unknown> = {};
  for (const [name, fn] of Object.entries(client)) {
    out[name] = typeof fn === 'function'
      ? (...args: unknown[]) => Promise.resolve().then(() => (fn as (...a: unknown[]) => unknown)(...args)).catch((e: unknown) => { throw asFormError(e); })
      : fn;
  }
  return out as T;
}

/** A failed call: the message, the server's code and the problems per field. */
export class FormError extends LendingError {
  constructor(
    message: string,
    status: number,
    code: string | undefined,
    readonly fields: FieldMessages = {},
    /** The server's detail, for a refusal that names a record (a duplicate says which member number). */
    readonly detail?: string,
  ) {
    super(message, status, code);
  }
}

/** The data of a call, or a FormError. */
export function unwrapForm<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    const status = result.response.status;
    throw new FormError(formMessage(problem, status), status, problem.code, fieldMessages(problem), problem.detail);
  }
  return result.data;
}

/** The progress of an upload: bytes sent so far and the total. */
export type UploadProgress = (sent: number, total: number) => void;

/**
 * POST a multipart form with one file and report progress (fetch cannot). The access token is refreshed once
 * on a 401 and the upload retried, as a read is. `fields` are the form's text parts (for example `doc_kind`).
 * The file is sent as given: the screen has resized and checked it already.
 */
export function postFile<T>(
  path: string,
  file: Blob,
  fileName: string,
  fields: Record<string, string>,
  opts: { onProgress?: UploadProgress; signal?: AbortSignal } = {},
): Promise<T> {
  const once = (): Promise<{ status: number; body: unknown }> =>
    new Promise((resolve, reject) => {
      if (opts.signal?.aborted) {
        reject(new FormError('The upload was stopped.', 0, 'aborted'));
        return;
      }
      const xhr = new XMLHttpRequest();
      xhr.open('POST', path);
      for (const [k, v] of Object.entries(tenantHeaders())) xhr.setRequestHeader(k, v);
      const token = getAccessToken();
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
      xhr.responseType = 'text';
      xhr.timeout = 120_000;
      xhr.ontimeout = () => reject(new FormError('The upload took too long. Check the connection and try again.', 0, 'timeout'));
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) opts.onProgress?.(e.loaded, e.total);
      };
      xhr.onload = () => {
        let body: unknown;
        try {
          body = xhr.responseText ? JSON.parse(xhr.responseText) : undefined;
        } catch {
          body = undefined;
        }
        resolve({ status: xhr.status, body });
      };
      xhr.onerror = () => reject(new FormError('The upload did not reach the server. Check the connection and try again.', 0, 'network'));
      xhr.onabort = () => reject(new FormError('The upload was stopped.', 0, 'aborted'));
      opts.signal?.addEventListener('abort', () => xhr.abort(), { once: true });
      const form = new FormData();
      for (const [k, v] of Object.entries(fields)) form.append(k, v);
      form.append('file', file, fileName);
      xhr.send(form);
    });
  return (async () => {
    let r = await once();
    if (r.status === 401 && (await refreshSession())) r = await once();
    if (r.status >= 200 && r.status < 300) return r.body as T;
    const problem = (r.body ?? {}) as RetailProblem;
    throw new FormError(formMessage(problem, r.status), r.status, problem.code, fieldMessages(problem), problem.detail);
  })();
}

/**
 * Open a file from its short-lived signed address in a new tab. The tab is opened first, in the tap itself (a phone browser blocks one
 * opened after a wait), and pointed at the address once it is known; it is closed again when the address could not be had.
 */
export async function openInNewTab(getUrl: () => Promise<string>): Promise<void> {
  const tab = window.open('about:blank', '_blank');
  try {
    const url = await getUrl();
    if (!url) throw new FormError('The file could not be opened.', 0, 'no_url');
    if (tab) {
      tab.opener = null;
      tab.location.href = url;
    } else window.location.assign(url);
  } catch (e) {
    tab?.close();
    throw e;
  }
}
