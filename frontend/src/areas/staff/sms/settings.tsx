import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { SmsError, sms, type SmsSettings, type SmsSettingsChange } from '../../../api/sms';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import {
  capProblem,
  capWords,
  changeOf,
  draftOf,
  isChange,
  mayManageSettings,
  mayReadSettings,
  modeNote,
  problemsOf,
  type Draft,
} from './state';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';

// Tenant SMS settings (extras slice B3, issue #218; ADR-036): the switch with its acknowledgement of what SMS costs, the
// sender id (chosen from the ones the platform approved, never typed), the sending window, the daily and monthly caps (which
// can be lowered, not raised), and whether a savings receipt carries the balance. Every change is saved under the version
// that was read and audited with before and after. The server decides each rule; this screen says the same things first.

export function Field({ id, label, hint, error, children }: { id: string; label: string; hint?: string; error?: string; children: ReactNode }) {
  return (
    <div className="stack">
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && <p id={`${id}-hint`} className="field-hint">{hint}</p>}
      {error && <p id={`${id}-error`} role="alert" className="field-error">{error}</p>}
    </div>
  );
}

function When({ iso }: { iso: string | null }) {
  if (!iso) return null;
  const d = new Date(iso);
  return <>{Number.isNaN(d.getTime()) ? '' : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</>;
}

/** The message under a failed save: the server's words, with the field messages next to their boxes. */
function failureOf(error: unknown): { message: string | null; fields: Record<string, string> } {
  if (!error) return { message: null, fields: {} };
  if (error instanceof SmsError) return { message: Object.keys(error.fields).length > 0 ? null : error.message, fields: error.fields };
  return { message: error instanceof Error ? error.message : 'Something went wrong.', fields: {} };
}

/** The switch: off shows the notice to acknowledge; on shows who accepted it and the way to switch off. */
export function SwitchPanel({ saved, canManage, busy, error, onChange }: {
  saved: SmsSettings;
  canManage: boolean;
  busy: boolean;
  error: string | null;
  onChange: (change: SmsSettingsChange) => void;
}) {
  const [ticked, setTicked] = useState(false);
  const [opening, setOpening] = useState(false);
  const note = modeNote(saved);
  return (
    <section className="rt-card stack" aria-labelledby="sms-switch" data-tour="sms-switch">
      <h2 id="sms-switch">Text messages (SMS)</h2>
      <p>
        {saved.enabled ? <span className="badge badge-success">On</span> : <span className="badge">Off</span>}{' '}
        {saved.enabled ? 'Your business sends text messages to members.' : 'Your business does not send text messages.'}
      </p>
      {note && <p role="note" className="alert alert-info">{note}</p>}
      {saved.enabled && saved.acknowledged_at && (
        <p className="hint">
          Switched on after the notice was accepted on <When iso={saved.acknowledged_at} />.
        </p>
      )}
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      {canManage && saved.enabled && (
        <p>
          <BusyButton type="button" className="btn-ghost" busy={busy} onClick={() => onChange({ enabled: false })}>
            Switch SMS off
          </BusyButton>
        </p>
      )}
      {canManage && !saved.enabled && !opening && (
        <p>
          <button type="button" className="rt-primary" onClick={() => setOpening(true)}>
            Switch SMS on
          </button>
        </p>
      )}
      {canManage && !saved.enabled && opening && (
        <form
          aria-label="Switch SMS on"
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            if (ticked && !busy) onChange({ enabled: true, acknowledged_text_version: saved.acknowledgement.text_version });
          }}
        >
          <p role="note" className="alert alert-warning pre-line" data-testid="sms-notice">{saved.acknowledgement.text}</p>
          <label>
            <input type="checkbox" checked={ticked} onChange={(e) => setTicked(e.target.checked)} /> I have read this and accept it for my business
          </label>
          <p className="cluster">
            <BusyButton type="submit" className="rt-primary" busy={busy} disabled={!ticked}>
              Switch SMS on
            </BusyButton>
            <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={() => { setOpening(false); setTicked(false); }}>
              Not now
            </button>
          </p>
        </form>
      )}
      {!canManage && <p className="hint">Only a business administrator can switch SMS on or off.</p>}
    </section>
  );
}

export interface Notice { kind: 'success' | 'warning'; text: string }

function Editor({ saved, canManage, notice, setNotice }: { saved: SmsSettings; canManage: boolean; notice: Notice | null; setNotice: (notice: Notice | null) => void }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<Draft>(draftOf(saved));
  const problems = problemsOf(saved, draft);
  const change = changeOf(saved, draft);

  const save = useMutation({
    mutationFn: (c: SmsSettingsChange) => sms.changeSettings(saved.version, c),
    onSuccess: (next, c) => {
      queryClient.setQueryData(['sms', 'settings'], next);
      void queryClient.invalidateQueries({ queryKey: ['sms', 'templates'] });
      setNotice({ kind: 'success', text: c.enabled === true ? 'SMS is on.' : c.enabled === false ? 'SMS is off.' : 'Saved.' });
    },
    // Someone else's change (or a rule the screen could not see): read it again so the next try carries the right version.
    onError: (error) => {
      if (error instanceof SmsError && error.code === 'version_conflict') setNotice({ kind: 'warning', text: error.message });
      void queryClient.invalidateQueries({ queryKey: ['sms', 'settings'] });
    },
  });
  const failure = failureOf(save.error);
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    setNotice(null);
    save.reset();
    setDraft({ ...draft, [key]: value });
  };
  const fieldError = (name: string, local?: string) => local ?? failure.fields[name];
  const submitted = isChange(change) && Object.keys(problems).length === 0;
  const sender = saved.sender_id;

  return (
    <div className="stack">
      <SwitchPanel
        saved={saved}
        canManage={canManage}
        busy={save.isPending}
        error={failure.fields.acknowledged_text_version ? failure.fields.acknowledged_text_version : failure.message}
        onChange={(c) => { setNotice(null); save.mutate(c); }}
      />
      {notice && <p role={notice.kind === 'warning' ? 'alert' : 'status'} className={notice.kind === 'warning' ? 'alert alert-warning' : 'alert alert-success'}>{notice.text}</p>}

      <form
        aria-label="SMS settings"
        className="rt-card form-stack"
        onSubmit={(e) => {
          e.preventDefault();
          if (submitted && !save.isPending) save.mutate(change);
        }}
      >
        <h2>Settings</h2>
        {!canManage && <Hint full>You can look at these settings. Only a business administrator can change them.</Hint>}

        <Field
          id="sms-sender"
          label="Sender name"
          hint="The name members see. It must be one the platform approved for your business. Without one, messages come from the provider's shared sender. To get a name approved, ask the platform."
          error={fieldError('sender_id')}
        >
          <select id="sms-sender" data-tour="sms-sender" value={draft.senderId} disabled={!canManage} onChange={(e) => set('senderId', e.target.value)}>
            <option value="">The shared sender (default)</option>
            {saved.approved_sender_ids.map((id) => (
              <option key={id} value={id}>{id}</option>
            ))}
            {sender && !saved.approved_sender_ids.some((id) => id.toLowerCase() === sender.toLowerCase()) && (
              <option value={sender} disabled>{sender} (no longer approved)</option>
            )}
          </select>
        </Field>
        {saved.sender_status === 'withdrawn' && (
          <p role="alert" className="alert alert-warning">
            The platform withdrew the sender name {sender}. Messages now go from the shared sender until you choose another.
          </p>
        )}

        <fieldset className="stack" aria-describedby="sms-window-hint" data-tour="sms-window">
          <legend>Sending window</legend>
          <p id="sms-window-hint" className="field-hint">
            Reminders and arrears notices are sent only inside this window, on {saved.window_zone} time, between {saved.window_earliest} and{' '}
            {saved.window_latest}, and for at least {saved.window_min_minutes} minutes. Receipts are not held by it.
          </p>
          <div className="cluster">
            <label htmlFor="sms-window-start">From</label>
            <input id="sms-window-start" type="time" value={draft.windowStart} disabled={!canManage} onChange={(e) => set('windowStart', e.target.value)} aria-invalid={problems.window !== undefined} />
            <label htmlFor="sms-window-end">To</label>
            <input id="sms-window-end" type="time" value={draft.windowEnd} disabled={!canManage} onChange={(e) => set('windowEnd', e.target.value)} aria-invalid={problems.window !== undefined} />
          </div>
          {(problems.window ?? failure.fields.window_start ?? failure.fields.window_end) && (
            <p role="alert" className="field-error">{problems.window ?? failure.fields.window_start ?? failure.fields.window_end}</p>
          )}
        </fieldset>

        <fieldset className="stack" data-tour="sms-limits">
          <legend>Limits</legend>
          <p className="field-hint">
            The platform sets the most your business can send: {saved.daily_ceiling} a day and {saved.monthly_ceiling} a month. You can lower these, not raise them. Leave a box
            empty to use the platform limit. <strong>0 stops every message</strong>, receipts included.
          </p>
          <Field
            id="sms-daily-cap"
            label="Most messages a day"
            hint={`Now: ${capWords(saved.effective_daily_cap, 'day')}. Sent today: ${saved.used_today}.`}
            error={fieldError('daily_cap', problems.daily_cap)}
          >
            <input id="sms-daily-cap" inputMode="numeric" autoComplete="off" value={draft.dailyCap} placeholder={String(saved.daily_ceiling)} disabled={!canManage} onChange={(e) => set('dailyCap', e.target.value)} aria-invalid={problems.daily_cap !== undefined} />
          </Field>
          <Field
            id="sms-monthly-cap"
            label="Most messages a month"
            hint={`Now: ${capWords(saved.effective_monthly_cap, 'month')}. Sent this month: ${saved.used_this_month}.`}
            error={fieldError('monthly_cap', problems.monthly_cap)}
          >
            <input id="sms-monthly-cap" inputMode="numeric" autoComplete="off" value={draft.monthlyCap} placeholder={String(saved.monthly_ceiling)} disabled={!canManage} onChange={(e) => set('monthlyCap', e.target.value)} aria-invalid={problems.monthly_cap !== undefined} />
          </Field>
          {(capProblem(draft.dailyCap, saved.daily_ceiling) === null && draft.dailyCap.trim() === '0') ||
          (capProblem(draft.monthlyCap, saved.monthly_ceiling) === null && draft.monthlyCap.trim() === '0') ? (
            <p role="alert" className="alert alert-warning">A limit of 0 stops every message, receipts included. Nothing is sent until you raise it.</p>
          ) : null}
        </fieldset>

        <fieldset className="stack">
          <legend>Savings receipts</legend>
          <label>
            <input type="checkbox" checked={draft.includeBalance} disabled={!canManage} onChange={(e) => set('includeBalance', e.target.checked)} /> Show the account balance in a savings receipt
          </label>
          <p className="field-hint">
            Off by default. A phone is often shared in a household, so a balance tells everyone who sees the message what a member holds. The wording you write yourself never carries a balance.
          </p>
        </fieldset>

        {canManage && (
          <p className="cluster">
            <BusyButton type="submit" className="rt-primary" busy={save.isPending} disabled={!submitted}>
              Save changes
            </BusyButton>
            {isChange(change) && (
              <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={save.isPending} onClick={() => { save.reset(); setDraft(draftOf(saved)); }}>
                Undo
              </button>
            )}
          </p>
        )}
      </form>
    </div>
  );
}

export function SmsSettingsScreen() {
  const { me } = useStaff();
  const settings = useQuery({ queryKey: ['sms', 'settings'], queryFn: sms.settings, enabled: mayReadSettings(me), retry: false });
  // Held here, not in the editor, which starts afresh from every new version (a save is one).
  const [notice, setNotice] = useState<Notice | null>(null);
  if (!mayReadSettings(me)) return <NoAccess what="the SMS settings" />;
  if (settings.isPending) return <p className="loading">Loading the SMS settings</p>;
  if (settings.isError || !settings.data) {
    return <p role="alert" className="alert alert-danger">{settings.error instanceof Error ? settings.error.message : 'Could not load the SMS settings.'}</p>;
  }
  // Keyed by the version, so a fresh read after someone else's change starts from what is saved.
  return <Editor key={settings.data.version} saved={settings.data} canManage={mayManageSettings(me)} notice={notice} setNotice={setNotice} />;
}

export const Route = createLazyRoute('/staff/sms/')({ component: SmsSettingsScreen });
