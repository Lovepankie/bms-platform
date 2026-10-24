import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useRef, useState } from 'react';
import {
  SmsError,
  sms,
  type SmsMeasure,
  type SmsPlaceholder,
  type SmsTemplate,
  type SmsTestResult,
} from '../../../api/sms';
import { SEARCH_DELAY_MS } from '../../../app/ui/useLiveSearch';
import { formatMinor } from '../../../components/money';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { mayManageWording, mayReadNotifications } from './state';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';

// SMS wording (extras slice B3, issue #218; ADR-036 decision 9): the tenant's own words for each event, built from a short
// list of placeholders and nothing else (no conditions, no code), with a preview made with fabricated data as you type, the
// encoding and the number of text messages it takes, a cost estimate from the business's own history, and a test message that
// only ever reaches the test sink or a listed test number. Everything shown is plain text: a business name with markup or
// another script is shown as it is, never as markup.

/** The encoding in words. UCS-2 holds fewer letters per message: one letter outside the SMS alphabet switches the whole text. */
export function encodingWords(encoding: string): string {
  return encoding === 'ucs2'
    ? 'Unicode (UCS-2): shorter parts, because it holds letters outside the SMS alphabet'
    : 'Standard SMS alphabet (GSM 7)';
}

export const messages = (n: number): string => (n === 1 ? '1 text message' : `${n} text messages`);

/** What the preview costs, from the business's own sent messages of the last 30 days; never an invented price. */
export function estimateWords(m: Pick<SmsMeasure, 'estimated_cost_minor' | 'currency'>): string {
  if (m.estimated_cost_minor === null || m.currency === null) {
    return 'No cost estimate yet: there are no sent messages in the last 30 days to base one on.';
  }
  try {
    return `About ${formatMinor(m.estimated_cost_minor, m.currency)} for this message, from what your messages cost in the last 30 days.`;
  } catch {
    return 'No cost estimate for this currency.';
  }
}

export function MeasureView({ measure }: { measure: SmsMeasure }) {
  return (
    <ul className="stack" aria-label="How it is sent">
      <li>{encodingWords(measure.encoding)}</li>
      <li>
        {measure.units} characters: {messages(measure.segments)}
        {measure.worst_case_segments > measure.segments && (
          <span className="hint"> (up to {measure.worst_case_segments} if a name is long or uses other letters)</span>
        )}
      </li>
      <li>{estimateWords(measure)}</li>
    </ul>
  );
}

const REASONS: Record<string, string | undefined> = {
  channel_off: 'SMS is switched off on this system, so nothing was sent.',
  live_mode_preview_only:
    'This system sends real messages, so test messages are not sent from here. The preview above is the test.',
  operator_sms_off: 'The platform has switched SMS off, so nothing was sent.',
  tenant_sms_off: 'SMS is switched off for your business, so nothing was sent.',
  blocked_optout: 'That number is on the opt-out list, so nothing was sent.',
  cap_reached: 'A daily or monthly limit has been reached, so nothing was sent.',
};

/** What happened to a test message, in words. */
export function testWords(result: Pick<SmsTestResult, 'outcome' | 'reason' | 'recipient_masked'>): string {
  switch (result.outcome) {
    case 'sunk':
      return `Recorded by the test sink for ${result.recipient_masked}. Nothing left the platform.`;
    case 'sent':
      return `Sent to ${result.recipient_masked}, a listed test number.`;
    default:
      return (result.reason && REASONS[result.reason]) || 'The test message was not sent.';
  }
}

function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
}

function Placeholders({ list, disabled, onInsert }: { list: SmsPlaceholder[]; disabled: boolean; onInsert: (name: string) => void }) {
  return (
    <div className="stack">
      <p className="field-hint">Put a placeholder in your words and it is filled in for each message:</p>
      <ul className="stack" aria-label="Placeholders">
        {list.map((p) => (
          <li key={p.name} className="cluster">
            <button type="button" className="btn-ghost btn-sm" disabled={disabled} onClick={() => onInsert(p.name)} aria-label={`Insert {${p.name}}`}>
              {`{${p.name}}`}
            </button>
            <span className="hint">
              {p.description}, for example {p.sample}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function TestMessage({ eventKey, text, canSend }: { eventKey: string; text: string; canSend: boolean }) {
  const [to, setTo] = useState('');
  const send = useMutation({ mutationFn: () => sms.testMessage(eventKey, text, to) });
  if (!canSend) return null;
  const error = send.error instanceof SmsError ? send.error : null;
  return (
    <form
      aria-label="Try this wording on a phone"
      className="stack"
      onSubmit={(e) => {
        e.preventDefault();
        if (to.trim() !== '' && !send.isPending) send.mutate();
      }}
    >
      <h3>Try it on a phone</h3>
      <p className="field-hint">
        A test message starts with TEST and uses made-up figures. It only ever reaches the test sink or a number on the system's test list, never a member's phone.
      </p>
      <label htmlFor={`sms-test-${eventKey}`}>Phone number</label>
      <input id={`sms-test-${eventKey}`} inputMode="tel" autoComplete="off" value={to} onChange={(e) => { setTo(e.target.value); send.reset(); }} />
      {error && <p role="alert" className="field-error">{error.fields.to ?? error.message}</p>}
      {send.data && <p role="status" className="alert alert-info">{testWords(send.data)}</p>}
      <p>
        <BusyButton type="submit" className="btn-ghost" busy={send.isPending} disabled={to.trim() === ''}>
          Send a test message
        </BusyButton>
      </p>
    </form>
  );
}

export function TemplateEditor({ template, canManage, onNotice, tour = false }: { template: SmsTemplate; canManage: boolean; onNotice: (message: string | null) => void; tour?: boolean }) {
  const queryClient = useQueryClient();
  const saved = template.override?.text ?? template.default_text;
  const [text, setText] = useState(saved);
  const [reverting, setReverting] = useState(false);
  const area = useRef<HTMLTextAreaElement>(null);
  const settled = useDebounced(text, SEARCH_DELAY_MS);
  const version = template.override?.version ?? 0;

  const preview = useQuery({
    queryKey: ['sms', 'preview', template.event_key, settled],
    queryFn: ({ signal }) => sms.previewTemplate(template.event_key, settled, signal),
    placeholderData: keepPreviousData,
    enabled: canManage,
    retry: false,
  });
  // Read-only viewers see the saved wording as the list measured it.
  const shown = canManage ? preview.data : { text: template.sample_text, measure: template.measure, note: template.note };
  const previewError = preview.error instanceof SmsError ? (preview.error.fields.text ?? preview.error.message) : null;

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['sms', 'templates'] });
  const save = useMutation({
    mutationFn: () => sms.saveTemplate(template.event_key, version, text),
    onSuccess: () => { onNotice(null); return refresh(); },
    onError: (error) => { if (error instanceof SmsError && error.code === 'version_conflict') onNotice(error.message); return refresh(); },
  });
  const revert = useMutation({
    mutationFn: () => sms.revertTemplate(template.event_key, version),
    onSuccess: () => { onNotice(null); return refresh(); },
    onError: (error) => { if (error instanceof SmsError && error.code === 'version_conflict') onNotice(error.message); return refresh(); },
  });
  const saveError = save.error instanceof SmsError ? save.error : null;
  const dirty = text !== saved;
  const insert = (name: string) => {
    const el = area.current;
    const token = `{${name}}`;
    const start = el?.selectionStart ?? text.length;
    const end = el?.selectionEnd ?? start;
    setText(text.slice(0, start) + token + text.slice(end));
    queueMicrotask(() => {
      el?.focus();
      el?.setSelectionRange(start + token.length, start + token.length);
    });
  };

  return (
    <section className="rt-card stack" aria-labelledby={`sms-w-${template.event_key}`}>
      <h2 id={`sms-w-${template.event_key}`}>{template.title}</h2>
      <p>
        {template.override ? <span className="badge badge-info">Your own words</span> : <span className="badge">Default words</span>}
      </p>
      <label htmlFor={`sms-text-${template.event_key}`}>Message</label>
      <textarea
        id={`sms-text-${template.event_key}`}
        ref={area}
        data-tour={tour ? 'sms-wording-message' : undefined}
        rows={4}
        value={text}
        maxLength={template.max_chars}
        readOnly={!canManage}
        aria-describedby={`sms-text-${template.event_key}-hint`}
        aria-invalid={previewError !== null}
        onChange={(e) => { setText(e.target.value); save.reset(); }}
      />
      <p id={`sms-text-${template.event_key}-hint`} className="field-hint">
        {text.length} of {template.max_chars} characters. At most {template.max_worst_case_segments} text messages even with long names.
      </p>
      {previewError && <p role="alert" className="field-error">{previewError}</p>}
      {canManage && <Placeholders list={template.placeholders} disabled={false} onInsert={insert} />}

      {shown && (
        <div className="stack" aria-label="Preview" data-testid={`sms-preview-${template.event_key}`}>
          <h3>Preview</h3>
          <p className="field-hint">Made with sample figures and your business name.</p>
          <p className="rt-card pre-line wrap-anywhere" data-testid="sms-preview-text">{shown.text}</p>
          {shown.note && <p role="note" className="alert alert-warning">{shown.note}</p>}
          <MeasureView measure={shown.measure} />
        </div>
      )}

      {saveError && <p role="alert" className="field-error">{saveError.fields.text ?? saveError.message}</p>}
      {revert.error instanceof Error && <p role="alert" className="field-error">{revert.error.message}</p>}
      {canManage && (
        <p className="cluster">
          <BusyButton type="button" className="rt-primary" data-tour={tour ? 'sms-wording-save' : undefined} busy={save.isPending} disabled={!dirty || previewError !== null} onClick={() => save.mutate()}>
            Save my words
          </BusyButton>
          {dirty && (
            <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={save.isPending} onClick={() => { setText(saved); save.reset(); }}>
              Undo
            </button>
          )}
          {template.override && !reverting && (
            <button type="button" className="btn-ghost" onClick={() => setReverting(true)}>
              Go back to the default words
            </button>
          )}
          {template.override && reverting && (
            <>
              <BusyButton type="button" className="btn-ghost" busy={revert.isPending} onClick={() => revert.mutate()}>
                Yes, use the default words
              </BusyButton>
              <button type="button" className="btn-ghost" onClick={() => setReverting(false)}>
                Keep my words
              </button>
            </>
          )}
        </p>
      )}
      <TestMessage eventKey={template.event_key} text={text} canSend={canManage} />
    </section>
  );
}

export function WordingScreen() {
  const { me } = useStaff();
  const list = useQuery({ queryKey: ['sms', 'templates'], queryFn: sms.templates, enabled: mayReadNotifications(me), retry: false });
  // Held here, not in an editor, which starts afresh from every new version (a save, or someone else's change, is one).
  const [notice, setNotice] = useState<string | null>(null);
  if (!mayReadNotifications(me)) return <NoAccess what="the SMS wording" />;
  if (list.isPending) return <p className="loading">Loading the wording</p>;
  if (list.isError || !list.data) {
    return <p role="alert" className="alert alert-danger">{list.error instanceof Error ? list.error.message : 'Could not load the wording.'}</p>;
  }
  const canManage = mayManageWording(me);
  return (
    <div className="stack" data-tour="sms-wording">
      <p>
        Choose the words each text message uses. Without your own words, the default is sent. Placeholders such as <code>{'{amount}'}</code> are filled in for each message.
      </p>
      {notice && <p role="alert" className="alert alert-warning">{notice}</p>}
      {!canManage && <Hint full>You can look at the wording. Only a business administrator can change it.</Hint>}
      {list.data.map((t, i) => (
        // Keyed by the saved version, so a fresh read after a save or someone else's change starts from what is saved.
        <TemplateEditor key={`${t.event_key}:${t.override?.version ?? 0}`} template={t} canManage={canManage} onNotice={setNotice} tour={i === 0} />
      ))}
    </div>
  );
}

export const Route = createLazyRoute('/staff/sms/wording')({ component: WordingScreen });
