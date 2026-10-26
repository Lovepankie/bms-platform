import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useEffect, useRef, useState } from 'react';
import { type ApprovalRule, type ApprovalRules, approvalRules } from '../../../api/approval-rules';
import { BusyButton } from '../../../app/ui/BusyButton';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';
import { MoneyInput } from '../../../app/ui/MoneyInput';
import { useStaff } from '../context';
import { type Draft, GENERIC_WAIT, MODULE_TITLES, WHILE_IT_WAITS, changesOf, draftOf, loosens, reviewLines, zeroWords } from './state';

// Approval rules (issue #343, ADR-091): which actions of the business need a second person before they happen, and from what amount.
// Lending actions always ask (a licensed lender keeps the second person); retail ones are switches, off unless the business had them
// on. Every change is read back in plain words and confirmed with the administrator's own code or password, then audited.

const MANAGE = 'core.settings.manage';
const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

function RuleCard({ rule, draft, busy, onChange }: {
  rule: ApprovalRule;
  draft: Draft;
  busy: boolean;
  onChange: (on: boolean, amount: number | null) => void;
}) {
  const type = rule.action_type ?? '';
  const entry = draft[type] ?? { on: rule.on === true, amount: rule.threshold_minor ?? 0 };
  return (
    <li className="rt-card stack" data-testid={`rule-${type}`} id={`rule-${type}`}>
      {rule.switchable ? (
        <label>
          <input type="checkbox" checked={entry.on} disabled={busy} onChange={(e) => onChange(e.target.checked, entry.amount)} />{' '}
          <strong>{rule.label}</strong>
          <span className="hint"> Ask an administrator first</span>
        </label>
      ) : (
        <p>
          <strong>{rule.label}</strong> <span className="badge">Always on</span>
        </p>
      )}
      {entry.on && rule.has_amount && (
        <MoneyInput
          id={`rule-${type}-amount`}
          label={`${rule.label} from this amount`}
          valueMinor={entry.amount}
          onChange={(amount) => onChange(entry.on, amount)}
          hint={zeroWords(rule.action_type)}
        />
      )}
      <Hint>{WHILE_IT_WAITS[type] ?? GENERIC_WAIT}</Hint>
    </li>
  );
}

export function ApprovalRulesScreen() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = (me.permissions ?? []).includes(MANAGE);
  const read = useQuery({ queryKey: ['approval-rules'], queryFn: approvalRules.get, enabled: allowed });
  // The administrator's edits; until the first one the form shows the saved rules, so a refresh after saving never wipes an edit.
  const [edited, setDraft] = useState<Draft | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const [proof, setProof] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // One save at a time: a second tap before the first answer must not send a second request.
  const working = useRef(false);

  const saved: ApprovalRules | undefined = read.data;
  // A link to one rule (Retail settings' returns Hint, #575) names its row in the hash; the rows exist only once the rules have
  // loaded, so the page scrolls to it then, not on arrival.
  const loaded = saved !== undefined;
  useEffect(() => {
    const id = window.location.hash.slice(1);
    if (loaded && id) document.getElementById(id)?.scrollIntoView({ block: 'start' });
  }, [loaded]);
  const draft: Draft | null = edited ?? (saved ? draftOf(saved.rules ?? []) : null);

  if (!allowed) {
    return (
      <main className="rt ln" data-tour="approval-rules-page">
        <h1>Approval rules</h1>
        <NoAccess />
      </main>
    );
  }

  const rules = saved?.rules ?? [];
  const currency = saved?.currency ?? 'UGX';
  const changes = draft ? changesOf(rules, draft) : [];
  const modules = [...new Set(rules.map((r) => r.module ?? ''))];

  function edit(type: string, on: boolean, amount: number | null) {
    if (!draft) return;
    setReviewing(false);
    setNotice(null);
    setDraft({ ...draft, [type]: { on, amount } });
  }

  async function save() {
    if (!saved || !draft || working.current) return;
    working.current = true;
    setBusy(true);
    setError(null);
    try {
      const told = loosens(rules, draft);
      const after = await approvalRules.save(saved.version ?? 1, changes, me.mfa_enabled ? { totp_code: proof.trim() } : { password: proof });
      setProof('');
      setReviewing(false);
      setDraft(draftOf(after.rules ?? []));
      queryClient.setQueryData(['approval-rules'], after);
      setNotice(told ? 'Saved. The other administrators were told, because fewer actions now need a second person.' : 'Saved. It applies from the next action.');
      // Retail settings and the purchasing settings show the returns and order amounts.
      await queryClient.invalidateQueries({ queryKey: ['retail'] });
    } catch (e) {
      setError(messageOf(e));
    } finally {
      working.current = false;
      setBusy(false);
    }
  }

  return (
    <main className="rt ln stack" data-tour="approval-rules-page">
      <h1>Approval rules</h1>
      <p data-tour="approval-rules-intro">Choose which actions need an administrator to approve them first, and from what amount.</p>
      <Hint>
        While an action waits, nothing changes. The person who asked sees it on Approvals and can cancel it. The administrators are told on
        their phones and decide on Approvals. Every change here is recorded with your name.
      </Hint>
      {notice && (
        <p role="status" className="alert alert-success">
          {notice}
        </p>
      )}
      {read.isPending && <p className="loading">Loading the rules</p>}
      {read.isError && (
        <p role="alert" className="alert alert-danger">
          {messageOf(read.error)}
        </p>
      )}
      {draft &&
        modules.map((module) => (
          <section key={module} className="stack" aria-label={MODULE_TITLES[module] ?? module} data-tour={`approval-rules-${module}`}>
            <h2>{MODULE_TITLES[module] ?? module}</h2>
            {module === 'lending' && <p className="hint">A licensed lender keeps a second person on these.</p>}
            <ul className="stack">
              {rules
                .filter((r) => r.module === module)
                .map((r) => (
                  <RuleCard key={r.action_type} rule={r} draft={draft} busy={busy} onChange={(on, amount) => edit(r.action_type ?? '', on, amount)} />
                ))}
            </ul>
            {module === 'retail' && (
              <p className="hint" data-testid="below-cost-note">
                Selling below cost is not an approval. Who may do it is set in <Link to="/staff/users">Staff users</Link>.
              </p>
            )}
          </section>
        ))}
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      {draft && !reviewing && (
        <p className="cluster">
          <button type="button" /* busy-exempt: opens the review */ className="rt-primary" disabled={busy || changes.length === 0} onClick={() => setReviewing(true)} data-tour="approval-rules-save">
            Review and save
          </button>
          {changes.length === 0 && <span className="hint">Nothing has changed yet.</span>}
        </p>
      )}
      {draft && reviewing && (
        <div className="rt-card stack" role="group" aria-label="Check before you save">
          <p>
            <strong>Please check this before you save.</strong>
          </p>
          <ul className="stack">
            {reviewLines(rules, draft, currency).map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          {loosens(rules, draft) && <p>The other administrators will be told, because fewer actions will need a second person.</p>}
          <label>
            {me.mfa_enabled ? 'The current code from your authenticator app' : 'Your own password'}
            <input
              type={me.mfa_enabled ? 'text' : 'password'}
              inputMode={me.mfa_enabled ? 'numeric' : undefined}
              autoComplete={me.mfa_enabled ? 'one-time-code' : 'current-password'}
              value={proof}
              disabled={busy}
              onChange={(e) => setProof(e.target.value)}
            />
          </label>
          <p className="cluster">
            <BusyButton type="button" className="rt-primary" busy={busy} notReady={proof.trim() === ''} onClick={() => void save()}>
              Yes, save this
            </BusyButton>
            <button type="button" /* busy-exempt: going back waits for the request */ className="btn-ghost" disabled={busy} onClick={() => setReviewing(false)}>
              Go back
            </button>
          </p>
        </div>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup/approvals')({ component: ApprovalRulesScreen });
