import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import {
  type FeedbackStatus,
  type PlatformFeedbackItem,
  operatorFeedback,
  operatorScreenshot,
  setFeedbackLinkedIssue,
  setFeedbackStatus,
} from '../../api/feedback';
import { TourButton } from './operator-parts';

// Feedback from every business (issue #447, ADR-065), newest first, on the platform host for operators only. Filter by status;
// move an item forward (new, seen, done) and write a reply the sender then sees on their own Feedback page; note the number of
// the issue opened for it, or clear it. The sender's words are shown as text, never as markup.

const FILTERS: { value: FeedbackStatus | null; label: string }[] = [
  { value: null, label: 'All' },
  { value: 'new', label: 'New' },
  { value: 'seen', label: 'Seen' },
  { value: 'done', label: 'Done' },
];

const KIND_WORDS: Record<string, string> = { idea: 'Idea', problem: 'Problem', question: 'Question' };
const STATUS_WORDS: Record<string, string> = { new: 'New', seen: 'Seen', done: 'Done' };

/** The moves an item may still make: forward only. */
export function nextStatuses(status: string | undefined): FeedbackStatus[] {
  if (status === 'new') return ['seen', 'done'];
  if (status === 'seen') return ['done'];
  return [];
}

/** The issue number typed in, or null when the box is empty; undefined when it is not a whole number above zero. */
export function issueNumberOf(text: string): number | null | undefined {
  const t = text.trim();
  if (t === '') return null;
  const n = Number(t.replace(/^#/, ''));
  return Number.isInteger(n) && n > 0 ? n : undefined;
}

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'The change was not saved. Try again.');

function When({ iso }: { iso?: string | null }) {
  if (!iso) return null;
  const d = new Date(iso);
  return <>{Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}</>;
}

function Screenshot({ id, load }: { id: string; load: (id: string) => Promise<Blob> }) {
  const [open, setOpen] = useState(false);
  const blob = useQuery({ queryKey: ['platform-feedback', 'screenshot', id], queryFn: () => load(id), enabled: open, staleTime: Infinity });
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!blob.data || typeof URL.createObjectURL !== 'function') return undefined;
    const made = URL.createObjectURL(blob.data);
    setUrl(made);
    return () => URL.revokeObjectURL(made);
  }, [blob.data]);
  if (!open) {
    return (
      <button type="button" className="btn-ghost btn-sm" onClick={() => setOpen(true)}>
        Show the screenshot
      </button>
    );
  }
  if (blob.isError) return <p className="hint">Could not load the screenshot.</p>;
  return url ? <img src={url} alt="Screenshot sent with this feedback" style={{ maxWidth: '100%', borderRadius: 6 }} /> : <p className="hint">Loading</p>;
}

function FeedbackCard({ item, save, link, loadScreenshot }: {
  item: PlatformFeedbackItem;
  save: (id: string, status: FeedbackStatus, reply: string | null) => Promise<unknown>;
  link: (id: string, issueNumber: number | null) => Promise<unknown>;
  loadScreenshot: (id: string) => Promise<Blob>;
}) {
  const [reply, setReply] = useState(item.reply_note ?? '');
  const [issue, setIssue] = useState(item.linked_issue_number == null ? '' : String(item.linked_issue_number));
  const issueNumber = issueNumberOf(issue);
  const issueChanged = issueNumber !== undefined && issueNumber !== (item.linked_issue_number ?? null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const id = item.id ?? '';
  const moves = nextStatuses(item.status);
  const replyChanged = reply.trim() !== (item.reply_note ?? '');

  async function act(status: FeedbackStatus) {
    setBusy(true);
    setError(null);
    try {
      await save(id, status, replyChanged ? reply.trim() : null);
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveIssue() {
    if (issueNumber === undefined) return;
    setBusy(true);
    setError(null);
    try {
      await link(id, issueNumber);
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className="rt-card stack" data-tour="feedback-item">
      <span>
        <strong>{KIND_WORDS[item.kind ?? ''] ?? item.kind}</strong> from {item.sender_name} at {item.tenant_name} ({item.tenant_slug}),{' '}
        <When iso={item.created_at} />. <span className="badge">{STATUS_WORDS[item.status ?? ''] ?? item.status}</span>
      </span>
      <span style={{ whiteSpace: 'pre-wrap' }}>{item.message}</span>
      <span className="hint">
        Page: {item.page_path ?? 'not known'}. Version: {item.app_version}. Browser: {item.user_agent ?? 'not known'}.
      </span>
      {item.has_screenshot && <Screenshot id={id} load={loadScreenshot} />}
      {item.linked_issue_number != null && <span className="hint">Issue: #{item.linked_issue_number}.</span>}
      <label htmlFor={`issue-${id}`}>Issue number</label>
      <div className="cluster">
        <input
          id={`issue-${id}`}
          inputMode="numeric"
          value={issue}
          disabled={busy}
          aria-invalid={issueNumber === undefined}
          onChange={(e) => setIssue(e.target.value)}
          style={{ maxWidth: '10rem' }}
        />
        <button type="button" className="btn-sm" disabled={busy || !issueChanged} onClick={() => void saveIssue()}>
          {issueNumber === null ? 'Clear the issue' : 'Save the issue'}
        </button>
      </div>
      {issueNumber === undefined && <p className="hint">The issue number is a whole number above zero.</p>}
      <label htmlFor={`reply-${id}`}>Reply the sender sees</label>
      <textarea id={`reply-${id}`} rows={3} value={reply} maxLength={2000} disabled={busy} onChange={(e) => setReply(e.target.value)} data-tour="feedback-reply" />
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      <div className="form-actions" data-tour="feedback-actions">
        {moves.map((status) => (
          <button key={status} type="button" disabled={busy} onClick={() => void act(status)}>
            Mark as {status}
          </button>
        ))}
        <button type="button" disabled={busy || !replyChanged} onClick={() => void act((item.status ?? 'new') as FeedbackStatus)}>
          Save the reply
        </button>
      </div>
    </li>
  );
}

export function FeedbackInbox({
  load = operatorFeedback,
  save = setFeedbackStatus,
  link = setFeedbackLinkedIssue,
  loadScreenshot = operatorScreenshot,
}: {
  load?: (status: FeedbackStatus | null) => Promise<PlatformFeedbackItem[]>;
  save?: (id: string, status: FeedbackStatus, reply: string | null) => Promise<unknown>;
  link?: (id: string, issueNumber: number | null) => Promise<unknown>;
  loadScreenshot?: (id: string) => Promise<Blob>;
}) {
  const client = useQueryClient();
  const [filter, setFilter] = useState<FeedbackStatus | null>('new');
  const list = useQuery({ queryKey: ['platform-feedback', filter], queryFn: () => load(filter) });
  const change = useMutation({
    mutationFn: ({ id, status, reply }: { id: string; status: FeedbackStatus; reply: string | null }) => save(id, status, reply),
    onSuccess: () => client.invalidateQueries({ queryKey: ['platform-feedback'] }),
  });
  const linkIssue = useMutation({
    mutationFn: ({ id, issueNumber }: { id: string; issueNumber: number | null }) => link(id, issueNumber),
    onSuccess: () => client.invalidateQueries({ queryKey: ['platform-feedback'] }),
  });
  const items = list.data ?? [];
  return (
    <main>
      <div className="cluster">
        <h1>Feedback</h1>
        <TourButton />
      </div>
      <p>What the staff of every business sent from inside the app, newest first.</p>
      <div className="cluster" role="group" aria-label="Show" data-tour="feedback-filter">
        {FILTERS.map((f) => (
          <button key={f.label} type="button" aria-pressed={filter === f.value} className={filter === f.value ? 'btn-primary btn-sm' : 'btn-sm'} onClick={() => setFilter(f.value)}>
            {f.label}
          </button>
        ))}
      </div>
      {list.isPending && (
        <p role="status" className="loading">
          Loading
        </p>
      )}
      {list.isError && (
        <p role="alert" className="alert alert-danger">
          Could not load the feedback.
        </p>
      )}
      {list.isSuccess && items.length === 0 && <p>Nothing here.</p>}
      <ul className="stack" data-tour="feedback-list">
        {items.map((item) => (
          <FeedbackCard
            key={`${item.id}-${item.status}-${item.reply_note ?? ''}-${item.linked_issue_number ?? ''}`}
            item={item}
            loadScreenshot={loadScreenshot}
            save={(id, status, reply) => change.mutateAsync({ id, status, reply })}
            link={(id, issueNumber) => linkIssue.mutateAsync({ id, issueNumber })}
          />
        ))}
      </ul>
    </main>
  );
}

export const Route = createLazyRoute('/platform/feedback')({ component: () => <FeedbackInbox /> });
