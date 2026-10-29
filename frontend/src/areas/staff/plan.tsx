import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api, problemOf } from '../../api/client';
import { BusyButton } from '../../app/ui/BusyButton';
import type { components } from '../../api/schema';
import { resetProofProblem } from './users/state';

// The Plan and modules card of Business set-up and the end-date notices (issue #472, ADR-073). Administrators see the card;
// only the Owner (ADR-062) asks for a module, stops one, cancels or undoes. Adding, stopping and cancelling say plainly what
// happens and when, then ask the Owner to prove it is still them (the authenticator code with two-step sign-in, else the
// password), as Transfer ownership does. Every member of staff sees the end date while a cancellation waits, and the read-only notice after it.

type Plan = components['schemas']['BusinessPlan'];
type Change = components['schemas']['PlanScheduledChange'];
type Request = components['schemas']['PlanModuleRequest'];

/** A module as people say it. */
export function moduleName(key: string): string {
  if (key === 'retail') return 'Shop';
  if (key === 'lending') return 'Lending';
  return key.charAt(0).toUpperCase() + key.slice(1);
}

/** A date as people write it, for example "1 November 2026"; the date is the business's own, so it is read as UTC. */
export function dayWords(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(`${iso}T00:00:00Z`);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
}

/** The day before a date: the last day that still works. */
export function dayBefore(iso: string): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10); // business-day-ok: a calendar sum on a UTC midnight
}

/** What the server says about when a change asked for now takes effect, by the business's own date. */
type When = Pick<Plan, 'changes_take_effect_on' | 'applies_at_once' | 'paid_until'>;

/** When a change asked for now takes effect, in words. */
export function whenWords(plan: When): string {
  if (plan.applies_at_once || !plan.changes_take_effect_on) return 'at once, because nothing is paid ahead';
  return `on ${dayWords(plan.changes_take_effect_on)}, after the last day paid for (${dayWords(plan.paid_until)})`;
}

/** The plain sentence of the confirm for stopping a module. */
export function stopSentence(module: string, plan: When): string {
  const name = moduleName(module);
  return (
    `${name} stops ${whenWords(plan)}. Its records are kept but hidden: nobody can open them while it is off, and they all come back ` +
    `if ${name} is switched on again. Until then everything works as now.`
  );
}

/** The plain sentence of the confirm for adding a module: at once when the plan includes it, else Rincol Tech answers first. */
export function addSentence(module: string, inPlan: boolean): string {
  const name = moduleName(module);
  return inPlan
    ? `${name} is switched on at once: your plan includes it, so the price does not change.`
    : `Your request for ${name} goes to Rincol Tech, who answer first; the price may change. You are emailed the answer.`;
}

/** The plain sentence of the confirm for cancelling. */
export function cancelSentence(plan: When): string {
  return (
    `The subscription ends ${whenWords(plan)}. Until then everything works. After it the business is read only: everyone can still ` +
    'sign in, look at everything and download the data, but nobody can add or change anything, and nothing more is billed. Nothing is ' +
    'deleted. You can undo the cancellation until that day.'
  );
}

/** The banner or notice for every member of staff, or null when the subscription goes on. */
export function endNotice(
  status: { ends_on?: string | null; ended_on?: string | null; closing_remove_after?: string | null } | undefined,
): { tone: 'warning' | 'danger'; text: string } | null {
  if (status?.closing_remove_after) {
    return {
      tone: 'danger',
      text: `The owner asked to close this business. It is read only: you can sign in, look at everything and download your data, but not add or change anything. Rincol Tech may remove it from ${dayWords(status.closing_remove_after)}. The owner can cancel the closing in Business set-up.`,
    };
  }
  if (status?.ended_on) {
    return {
      tone: 'danger',
      text: `The subscription of this business ended on ${dayWords(status.ended_on)}. It is read only: you can sign in, look at everything and download your data, but not add or change anything. The owner can ask Rincol Tech to start it again.`,
    };
  }
  if (status?.ends_on) {
    return {
      tone: 'warning',
      text: `The subscription of this business was cancelled. Everything works until ${dayWords(dayBefore(status.ends_on))}; from ${dayWords(status.ends_on)} the business is read only. The owner can undo this in Business set-up.`,
    };
  }
  return null;
}

function requestWords(r: Request): string {
  const name = moduleName(r.module_key ?? '');
  if (r.status === 'pending') return `${name}: asked for, waiting for Rincol Tech to answer.`;
  if (r.status === 'approved') return r.decided_automatically ? `${name}: switched on (your plan includes it).` : `${name}: approved and switched on.`;
  return `${name}: declined. ${r.decision_reason ?? ''}`.trim();
}

function changeWords(c: Change): string {
  const what = c.kind === 'cancel' ? 'The subscription ends' : `${moduleName(c.module_key ?? '')} stops`;
  if (c.status === 'scheduled') return `${what} on ${dayWords(c.effective_on)}.`;
  if (c.status === 'applied') return c.kind === 'cancel' ? `The subscription ended on ${dayWords(c.effective_on)}.` : `${moduleName(c.module_key ?? '')} stopped on ${dayWords(c.effective_on)}.`;
  return `${what} on ${dayWords(c.effective_on)}: undone.`;
}

const PLAN_QUERY = ['plan'] as const;

/** The end-date banner and the read-only notice, for every member of staff. */
export function PlanNotice() {
  const status = useQuery({
    queryKey: ['plan-status'],
    queryFn: async () => (await api.GET('/api/v1/plan/status')).data ?? null,
    staleTime: 5 * 60_000,
  });
  const notice = endNotice(status.data ?? undefined);
  if (!notice) return null;
  return (
    <p role="status" className={`alert alert-${notice.tone}`}>
      {notice.text}
    </p>
  );
}

type Confirm = { kind: 'add'; module: string } | { kind: 'stop'; module: string } | { kind: 'cancel' };

/** The Plan and modules card of Business set-up. */
export function PlanCard({ mfaEnabled }: { mfaEnabled: boolean }) {
  const queryClient = useQueryClient();
  const plan = useQuery({
    queryKey: PLAN_QUERY,
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/plan');
      if (error) throw new Error(problemOf(error).detail ?? 'Could not load the plan.');
      return data;
    },
  });
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const sending = useRef(false);

  if (!plan.data) {
    return (
      <fieldset data-tour="setup-plan">
        <legend>Plan and modules</legend>
        {plan.isError ? <p role="alert">Could not load the plan.</p> : <p>Loading the plan</p>}
      </fieldset>
    );
  }
  const p = plan.data;
  const today = p.today ?? '';
  const scheduled = (p.changes ?? []).filter((c) => c.status === 'scheduled');
  const stopping = new Set(scheduled.filter((c) => c.kind === 'stop_module').map((c) => c.module_key));
  const pending = new Set((p.requests ?? []).filter((r) => r.status === 'pending').map((r) => r.module_key));
  const staying = (p.modules ?? []).filter((m) => !stopping.has(m));
  const proofProblem = typed === '' ? null : resetProofProblem(mfaEnabled, typed);

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: PLAN_QUERY });
    await queryClient.invalidateQueries({ queryKey: ['plan-status'] });
    await queryClient.invalidateQueries({ queryKey: ['me'] });
  }

  async function run(action: () => Promise<{ error?: unknown }>, done: string) {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const { error: failed } = await action();
      if (failed) {
        const problem = problemOf(failed);
        setError(problem.errors?.[0]?.message ?? problem.detail ?? 'That did not work. Try again.');
        return;
      }
      setConfirm(null);
      setMessage(done);
      await refresh();
    } finally {
      sending.current = false;
      setBusy(false);
      setTyped('');
    }
  }

  function send() {
    if (!confirm || resetProofProblem(mfaEnabled, typed) !== null) return;
    const body = mfaEnabled ? { totp_code: typed.trim() } : { password: typed };
    if (confirm.kind === 'add') {
      const module = confirm.module;
      void run(() => api.POST('/api/v1/plan/module-requests', { body: { module, ...body } }), `${moduleName(module)}: your request is sent.`);
    } else if (confirm.kind === 'stop') {
      void run(
        () => api.POST('/api/v1/plan/modules/{module}/stop', { params: { path: { module: confirm.module } }, body }),
        `${moduleName(confirm.module)} will stop as described.`,
      );
    } else {
      void run(() => api.POST('/api/v1/plan/cancel', { body }), 'The subscription is cancelled as described. You can undo it until that day.');
    }
  }

  function undo(change: Change) {
    void run(
      () => api.POST('/api/v1/plan/changes/{change_id}/undo', { params: { path: { change_id: change.id ?? '' } } }),
      'Undone. Everything goes on as before.',
    );
  }

  const ended = Boolean(p.ended_on);

  return (
    <fieldset data-tour="setup-plan">
      <legend>Plan and modules</legend>
      <p>
        Plan: <strong>{p.plan_name}</strong>
        {p.pilot ? ' (pilot, free)' : ''}
        {p.paid_until ? `. Paid until ${dayWords(p.paid_until)}.` : p.trial_ends_on ? `. Free month until ${dayWords(p.trial_ends_on)}.` : '.'}
      </p>
      <p>In use: {(p.modules ?? []).map(moduleName).join(', ') || 'none'}.</p>
      {!p.can_change && <p>Only the owner of the business can change the plan.</p>}

      {p.can_change && !ended && (
        <>
          <ul className="stack">
            {(p.modules ?? []).map((m) => (
              <li key={m}>
                {moduleName(m)}{' '}
                {stopping.has(m) ? (
                  '(stopping)'
                ) : staying.length > 1 ? (
                  <button type="button" /* busy-exempt: opens the confirm, sends nothing */ className="btn-ghost" disabled={busy} onClick={() => { setConfirm({ kind: 'stop', module: m }); setError(null); }}>
                    Stop using {moduleName(m)}
                  </button>
                ) : (
                  <span>(a business keeps at least one module)</span>
                )}
              </li>
            ))}
            {(p.modules_available ?? []).map((m) => (
              <li key={m}>
                {moduleName(m)}{' '}
                {pending.has(m) ? (
                  '(asked for)'
                ) : (
                  <button type="button" /* busy-exempt: opens the confirm, sends nothing */ disabled={busy} onClick={() => { setConfirm({ kind: 'add', module: m }); setError(null); }}>
                    Add {moduleName(m)}
                  </button>
                )}
                {!pending.has(m) && !(p.modules_in_plan ?? []).includes(m) && <span> Rincol Tech answers first; the price may change.</span>}
              </li>
            ))}
          </ul>
          {!p.ends_on && (
            <p>
              <button type="button" /* busy-exempt: opens the confirm, sends nothing */ className="btn-danger" disabled={busy} onClick={() => { setConfirm({ kind: 'cancel' }); setError(null); }}>
                Cancel the subscription
              </button>
            </p>
          )}
        </>
      )}

      {confirm && (
        <form
          className="alert alert-warning stack"
          aria-label={confirm.kind === 'cancel' ? 'Cancel the subscription' : confirm.kind === 'add' ? `Add ${moduleName(confirm.module)}` : `Stop using ${moduleName(confirm.module)}`}
          autoComplete="off"
          onSubmit={(e) => {
            e.preventDefault();
            send();
          }}
        >
          <p>
            {confirm.kind === 'cancel'
              ? cancelSentence(p)
              : confirm.kind === 'add'
                ? addSentence(confirm.module, (p.modules_in_plan ?? []).includes(confirm.module))
                : stopSentence(confirm.module, p)}
          </p>
          {mfaEnabled ? (
            <>
              <label htmlFor="plan-proof">Code from your authenticator app</label>
              <input id="plan-proof" value={typed} onChange={(e) => setTyped(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={6} spellCheck={false} aria-invalid={proofProblem !== null} />
            </>
          ) : (
            <>
              <label htmlFor="plan-proof">Your own password</label>
              <input id="plan-proof" type="password" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="current-password" spellCheck={false} aria-invalid={proofProblem !== null} />
            </>
          )}
          {proofProblem && <p role="alert" className="field-error">{proofProblem}</p>}
          <p className="cluster">
            <BusyButton type="submit" className={confirm.kind === 'add' ? undefined : 'btn-danger'} busy={busy} disabled={resetProofProblem(mfaEnabled, typed) !== null}>
              {confirm.kind === 'cancel'
                ? 'Yes, cancel the subscription'
                : confirm.kind === 'add'
                  ? `Yes, add ${moduleName(confirm.module)}`
                  : `Yes, stop using ${moduleName(confirm.module)}`}
            </BusyButton>
            <button type="button" /* busy-exempt: closes the confirm, sends nothing */ className="btn-ghost" disabled={busy} onClick={() => { setConfirm(null); setTyped(''); }}>
              Keep it
            </button>
          </p>
        </form>
      )}

      {scheduled.length + (p.requests ?? []).length > 0 && (
        <ul>
          {scheduled.map((c) => (
            <li key={c.id}>
              {changeWords(c)}{' '}
              {p.can_change && !ended && (c.effective_on ?? '') > today && (
                <BusyButton type="button" className="btn-ghost" busy={busy} onClick={() => undo(c)}>
                  Undo
                </BusyButton>
              )}
            </li>
          ))}
          {(p.changes ?? [])
            .filter((c) => c.status !== 'scheduled')
            .map((c) => (
              <li key={c.id}>{changeWords(c)}</li>
            ))}
          {(p.requests ?? []).map((r) => (
            <li key={r.id}>{requestWords(r)}</li>
          ))}
        </ul>
      )}
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      {message && <p role="status">{message}</p>}
    </fieldset>
  );
}
