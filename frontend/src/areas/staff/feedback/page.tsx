import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useRouterState } from '@tanstack/react-router';
import { type FormEvent, useEffect, useRef, useState } from 'react';
import { businessZone } from '../../../api/retail';
import {
  type FeedbackItem,
  type FeedbackKind,
  type FeedbackSend,
  type FeedbackStatus,
  myFeedback,
  myScreenshot,
  sendFeedback,
} from '../../../api/feedback';
import { BusyButton } from '../../../app/ui/BusyButton';
import { KIND_WORDS, KINDS, MESSAGE_MAX, SCREENSHOT_TYPES, STATUS_WORDS, messageProblem, pageBeforeFeedback, screenshotProblem } from './feedback-state';
import { Hint } from '../../../app/ui/Hint';

// Feedback (issue #447, ADR-065): any member of staff tells the BMS team an idea, a problem or a question, with a picture of the
// screen if it helps. The page they came from, the version of BMS and the browser go with it on their own. Below the form, what
// this person sent before, whether the team has read it, and the team's reply.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Your feedback was not sent. Try again.');

function When({ iso }: { iso?: string | null }) {
  if (!iso) return null;
  const d = new Date(iso);
  return <>{Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone: businessZone() })}</>;
}

export function FeedbackForm({ send = sendFeedback, from = pageBeforeFeedback, ask }: {
  send?: (body: FeedbackSend) => Promise<FeedbackItem>;
  from?: () => string | null;
  /** What the person searched for on How do I...? before tapping Ask us (issue #598): the form starts as a question with it typed. */
  ask?: string;
}) {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<FeedbackKind | null>(ask ? 'question' : null);
  const [message, setMessage] = useState(ask ?? '');
  const [screenshot, setScreenshot] = useState<File | null>(null);
  const [tried, setTried] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const submit = useMutation({
    mutationFn: send,
    onSuccess: () => {
      setTried(false);
      setKind(null);
      setMessage('');
      setScreenshot(null);
      if (fileInput.current) fileInput.current.value = '';
      void queryClient.invalidateQueries({ queryKey: ['feedback', 'mine'] });
    },
    meta: { success: 'Feedback sent' },
  });
  const kindProblem = kind === null ? 'Choose what it is: an idea, a problem or a question.' : null;
  const textProblem = messageProblem(message);
  const fileProblem = screenshotProblem(screenshot);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (kindProblem || textProblem || fileProblem || kind === null) return;
    submit.mutate({ kind, message: message.trim(), pagePath: from(), screenshot });
  }

  return (
    <form className="rt-card stack" onSubmit={onSubmit} noValidate aria-labelledby="feedback-form-title">
      <h2 id="feedback-form-title">Tell us</h2>
      <fieldset className="stack" data-tour="feedback-kind">
        <legend>What is it?</legend>
        {KINDS.map((k) => (
          <label key={k.value}>
            <input type="radio" name="feedback-kind" value={k.value} checked={kind === k.value} onChange={() => setKind(k.value)} disabled={submit.isPending} />{' '}
            <strong>{k.label}</strong> <span className="hint">{k.hint}</span>
          </label>
        ))}
        {tried && kindProblem && (
          <p role="alert" className="field-error">
            {kindProblem}
          </p>
        )}
      </fieldset>
      <div className="stack" data-tour="feedback-message">
        <label htmlFor="feedback-message">Your message</label>
        <textarea
          id="feedback-message"
          rows={6}
          value={message}
          maxLength={MESSAGE_MAX + 200}
          onChange={(e) => setMessage(e.target.value)}
          disabled={submit.isPending}
          aria-describedby="feedback-message-count"
          style={{ display: 'block', width: '100%' }}
        />
        <span id="feedback-message-count" className="hint">
          {message.trim().length} of {MESSAGE_MAX} characters
        </span>
        {tried && textProblem && (
          <p role="alert" className="field-error">
            {textProblem}
          </p>
        )}
      </div>
      <div className="stack" data-tour="feedback-screenshot">
        <label htmlFor="feedback-screenshot">A picture of the screen (if it helps)</label>
        <input
          id="feedback-screenshot"
          ref={fileInput}
          type="file"
          accept={SCREENSHOT_TYPES}
          disabled={submit.isPending}
          onChange={(e) => setScreenshot(e.target.files?.[0] ?? null)}
        />
        <span className="hint">A PNG or JPEG picture, up to 2 MB.</span>
        {fileProblem && (
          <p role="alert" className="field-error">
            {fileProblem}
          </p>
        )}
      </div>
      <Hint>The page you came from, the version of BMS and your browser are sent with it, so we can find the problem.</Hint>
      {submit.isError && (
        <p role="alert" className="alert alert-danger">
          {messageOf(submit.error)}
        </p>
      )}
      <div className="form-actions">
        <BusyButton type="submit" className="btn-primary" busy={submit.isPending} data-tour="feedback-send">
          Send feedback
        </BusyButton>
      </div>
    </form>
  );
}

function Screenshot({ id }: { id: string }) {
  const [open, setOpen] = useState(false);
  const blob = useQuery({ queryKey: ['feedback', 'screenshot', id], queryFn: () => myScreenshot(id), enabled: open, staleTime: Infinity });
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
        Show the picture
      </button>
    );
  }
  if (blob.isError) return <p className="hint">Could not load the picture.</p>;
  return url ? <img src={url} alt="The picture you sent" style={{ maxWidth: '100%', borderRadius: 6 }} /> : <p className="hint">Loading</p>;
}

export function FeedbackHistory({ load = myFeedback }: { load?: () => Promise<FeedbackItem[]> }) {
  const list = useQuery({ queryKey: ['feedback', 'mine'], queryFn: load });
  const items = list.data ?? [];
  return (
    <section className="rt-card stack" aria-labelledby="feedback-history-title" data-tour="feedback-history">
      <h2 id="feedback-history-title">What you sent</h2>
      {list.isPending && (
        <p role="status" className="loading">
          Loading
        </p>
      )}
      {list.isError && <p role="alert">Could not load what you sent.</p>}
      {list.isSuccess && items.length === 0 && <p className="hint">You have not sent any feedback yet.</p>}
      {items.length > 0 && (
        <ul className="stack">
          {items.map((item) => (
            <li key={item.id} className="stack">
              <span>
                <strong>{KIND_WORDS[item.kind ?? ''] ?? item.kind}</strong>, <When iso={item.created_at} />.{' '}
                <span className="hint">{STATUS_WORDS[(item.status ?? 'new') as FeedbackStatus]}</span>
              </span>
              <span style={{ whiteSpace: 'pre-wrap' }}>{item.message}</span>
              {item.has_screenshot && item.id && <Screenshot id={item.id} />}
              {item.reply_note && (
                <p className="alert alert-info" style={{ whiteSpace: 'pre-wrap' }}>
                  <strong>Reply from the BMS team:</strong> {item.reply_note}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function FeedbackPage() {
  const asked: unknown = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).ask });
  // The router reads a search value that looks like JSON (a number, true, [1]) as that value; it is still what was typed.
  const ask = asked === undefined || asked === '' ? undefined : typeof asked === 'string' ? asked : JSON.stringify(asked);
  return (
    <main className="rt ln">
      <h1>Feedback</h1>
      <p>Tell the BMS team what would help, what went wrong or what you want to ask. Everyone who uses BMS can send it.</p>
      {/* Keyed on the question, so a second Ask us while Feedback is open fills the form again. */}
      <FeedbackForm key={ask ?? ''} ask={ask?.slice(0, MESSAGE_MAX)} />
      <FeedbackHistory />
    </main>
  );
}

export const Route = createLazyRoute('/staff/feedback')({ component: FeedbackPage });
