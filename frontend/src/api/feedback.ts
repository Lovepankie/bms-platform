import { getAccessToken, refreshSession, tenantHeaders } from '../auth/session';
import { api, problemOf } from './client';
import type { components } from './schema';

// Feedback sent from inside the app (issue #447, ADR-065): the sender's own send and list on the business's host, and the
// operator's list, status change and screenshot on the platform host. Typed by the generated schema. A failure throws a
// FeedbackError with the server's plain-words detail, so a screen shows it as it is.

type S = components['schemas'];

export type FeedbackItem = S['FeedbackItem'];
export type PlatformFeedbackItem = S['PlatformFeedbackItem'];
export type FeedbackKind = 'idea' | 'problem' | 'question';
export type FeedbackStatus = 'new' | 'seen' | 'done';

export class FeedbackError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

const SEND_FAILED = 'Your feedback was not sent. Check the connection and try again.';

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error);
  const fields = (problem.errors ?? []).map((e) => e.message).filter((m) => m);
  throw new FeedbackError(fields.length > 0 ? fields.join(' ') : (problem.detail ?? fallback), status, problem.code);
}

export interface FeedbackSend {
  kind: FeedbackKind;
  message: string;
  pagePath?: string | null;
  screenshot?: File | null;
}

/** A multipart form (the screenshot is a file), sent with the session's token; a 401 refreshes the session and sends once more. */
export async function sendFeedback(body: FeedbackSend): Promise<FeedbackItem> {
  const once = () => {
    const form = new FormData();
    form.append('kind', body.kind);
    form.append('message', body.message);
    if (body.pagePath) form.append('page_path', body.pagePath);
    if (body.screenshot) form.append('screenshot', body.screenshot, body.screenshot.name || 'screenshot');
    const headers = new Headers(tenantHeaders());
    const token = getAccessToken();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    return fetch('/api/v1/feedback', { method: 'POST', body: form, headers });
  };
  let response: Response;
  try {
    response = await once();
    if (response.status === 401 && (await refreshSession())) response = await once();
  } catch {
    throw new FeedbackError(SEND_FAILED, 0, 'network');
  }
  const json: unknown = await response.json().catch(() => undefined);
  if (!response.ok) fail(json, response.status, SEND_FAILED);
  return json as FeedbackItem;
}

export async function myFeedback(): Promise<FeedbackItem[]> {
  const result = await api.GET('/api/v1/feedback');
  if (!result.data) fail(result.error, result.response.status, 'Could not load what you sent.');
  return result.data.items ?? [];
}

export async function myScreenshot(id: string): Promise<Blob> {
  const result = await api.GET('/api/v1/feedback/{feedback_id}/screenshot', { params: { path: { feedback_id: id } }, parseAs: 'blob' });
  if (!result.data) fail(result.error, result.response.status, 'Could not load the screenshot.');
  return result.data as unknown as Blob;
}

export async function operatorFeedback(status: FeedbackStatus | null, offset = 0): Promise<PlatformFeedbackItem[]> {
  const result = await api.GET('/api/v1/platform/feedback', { params: { query: { status: status ?? undefined, limit: 50, offset } } });
  if (!result.data) fail(result.error, result.response.status, 'Could not load the feedback.');
  return result.data.items ?? [];
}

export async function setFeedbackStatus(id: string, status: FeedbackStatus, replyNote?: string | null): Promise<PlatformFeedbackItem> {
  const result = await api.PUT('/api/v1/platform/feedback/{feedback_id}/status', {
    params: { path: { feedback_id: id } },
    body: { status, reply_note: replyNote ?? undefined },
  });
  if (!result.data) fail(result.error, result.response.status, 'The change was not saved. Try again.');
  return result.data;
}

export async function setFeedbackLinkedIssue(id: string, issueNumber: number | null): Promise<PlatformFeedbackItem> {
  const result = await api.PUT('/api/v1/platform/feedback/{feedback_id}/linked-issue', {
    params: { path: { feedback_id: id } },
    // An absent number is a null on the server, which clears the link.
    body: { linked_issue_number: issueNumber ?? undefined },
  });
  if (!result.data) fail(result.error, result.response.status, 'The issue number was not saved. Try again.');
  return result.data;
}

export async function operatorScreenshot(id: string): Promise<Blob> {
  const result = await api.GET('/api/v1/platform/feedback/{feedback_id}/screenshot', {
    params: { path: { feedback_id: id } },
    parseAs: 'blob',
  });
  if (!result.data) fail(result.error, result.response.status, 'Could not load the screenshot.');
  return result.data as unknown as Blob;
}
