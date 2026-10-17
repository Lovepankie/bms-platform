import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The one file upload (issue #280) with a stand-in for XMLHttpRequest: the answer mapped to words, a token that expired refreshed once,
// a stopped upload that never starts, a lost connection and a timeout as unknown outcomes, and progress passed on.

const refresh = vi.hoisted(() => vi.fn(async () => true));
vi.mock('../auth/session', () => ({ getAccessToken: () => 'token-1', refreshSession: refresh, tenantHeaders: () => ({}) }));

import { FormError, postFile, unknownOutcome } from './lending-forms';

interface Reply { status?: number; body?: unknown; error?: 'network' | 'timeout'; progress?: [number, number] }

class FakeXhr {
  static replies: Reply[] = [];
  static sent: FakeXhr[] = [];
  headers: Record<string, string> = {};
  upload: { onprogress?: (e: { lengthComputable: boolean; loaded: number; total: number }) => void } = {};
  onload?: () => void;
  onerror?: () => void;
  onabort?: () => void;
  ontimeout?: () => void;
  responseType = '';
  timeout = 0;
  status = 0;
  responseText = '';
  aborted = false;
  form?: FormData;
  open() {}
  setRequestHeader(k: string, v: string) { this.headers[k] = v; }
  abort() { this.aborted = true; this.onabort?.(); }
  send(form: FormData) {
    this.form = form;
    FakeXhr.sent.push(this);
    const reply = FakeXhr.replies.shift() ?? { status: 201, body: {} };
    queueMicrotask(() => {
      if (reply.progress) this.upload.onprogress?.({ lengthComputable: true, loaded: reply.progress[0], total: reply.progress[1] });
      if (reply.error === 'network') return this.onerror?.();
      if (reply.error === 'timeout') return this.ontimeout?.();
      this.status = reply.status ?? 200;
      this.responseText = reply.body === undefined ? '' : JSON.stringify(reply.body);
      this.onload?.();
    });
  }
}

beforeEach(() => {
  FakeXhr.replies = [];
  FakeXhr.sent = [];
  refresh.mockClear();
  vi.stubGlobal('XMLHttpRequest', FakeXhr);
});
afterEach(() => vi.unstubAllGlobals());

const file = new Blob([new Uint8Array(100)], { type: 'image/jpeg' });
const upload = (opts = {}) => postFile<{ document_id: string }>('/api/v1/lending/members/m1/documents', file, 'a.jpg', { doc_kind: 'id_front' }, opts);
const failure = async (p: Promise<unknown>): Promise<FormError> => {
  try {
    await p;
  } catch (e) {
    return e as FormError;
  }
  throw new Error('did not fail');
};

describe('postFile', () => {
  it('sends the kind and the file with the token, passes progress on and returns the saved document', async () => {
    FakeXhr.replies = [{ status: 201, body: { document_id: 'd1' }, progress: [50, 100] }];
    const progress = vi.fn();
    expect(await upload({ onProgress: progress })).toEqual({ document_id: 'd1' });
    expect(progress).toHaveBeenCalledWith(50, 100);
    expect(FakeXhr.sent[0]?.headers.Authorization).toBe('Bearer token-1');
    expect(FakeXhr.sent[0]?.form?.get('doc_kind')).toBe('id_front');
    expect((FakeXhr.sent[0]?.form?.get('file') as File).name).toBe('a.jpg');
  });

  it('refreshes an expired session once and sends the same file again', async () => {
    FakeXhr.replies = [{ status: 401, body: { code: 'unauthenticated' } }, { status: 201, body: { document_id: 'd2' } }];
    expect(await upload()).toEqual({ document_id: 'd2' });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(FakeXhr.sent).toHaveLength(2);
  });

  it('gives up after one refresh, and when the session cannot be refreshed', async () => {
    FakeXhr.replies = [{ status: 401, body: {} }, { status: 401, body: {} }];
    expect((await failure(upload())).status).toBe(401);
    expect(FakeXhr.sent).toHaveLength(2);
    refresh.mockResolvedValueOnce(false);
    FakeXhr.replies = [{ status: 401, body: {} }];
    FakeXhr.sent = [];
    expect((await failure(upload())).status).toBe(401);
    expect(FakeXhr.sent).toHaveLength(1);
  });

  it('maps the server\'s refusals to plain words that are not seen as a lost answer', async () => {
    FakeXhr.replies = [{ status: 413, body: { code: 'file_too_large' } }];
    const big = await failure(upload());
    expect(big.message).toMatch(/bigger than 5 MB/);
    expect(unknownOutcome(big)).toBe(false);
    FakeXhr.replies = [{ status: 415, body: { code: 'unsupported_file_type' } }];
    expect((await failure(upload())).message).toMatch(/JPEG or PNG/);
    FakeXhr.replies = [{ status: 404, body: { code: 'not_found' } }];
    expect((await failure(upload())).status).toBe(404);
  });

  it('takes a 503, a lost connection and a timeout as unknown outcomes', async () => {
    FakeXhr.replies = [{ status: 503, body: {} }];
    expect(unknownOutcome(await failure(upload()))).toBe(true);
    FakeXhr.replies = [{ error: 'network' }];
    const lost = await failure(upload());
    expect(lost).toMatchObject({ status: 0, code: 'network' });
    expect(unknownOutcome(lost)).toBe(true);
    FakeXhr.replies = [{ error: 'timeout' }];
    expect(await failure(upload())).toMatchObject({ status: 0, code: 'timeout' });
    expect(FakeXhr.sent.every((x) => x.timeout > 0)).toBe(true);
  });

  it('never starts an upload that was stopped before it began, and stops one that is on its way', async () => {
    const early = new AbortController();
    early.abort();
    expect(await failure(upload({ signal: early.signal }))).toMatchObject({ code: 'aborted' });
    expect(FakeXhr.sent).toHaveLength(0);
    const late = new AbortController();
    const p = failure(upload({ signal: late.signal }));
    late.abort();
    expect(await p).toMatchObject({ code: 'aborted' });
    expect(FakeXhr.sent[0]?.aborted).toBe(true);
  });
});
