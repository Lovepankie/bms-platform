import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Link, useParams } from '@tanstack/react-router';
import { type FormEvent, useMemo, useRef, useState } from 'react';
import {
  type Business,
  type BusinessDetail,
  businessOverview,
  listPricePlans,
  OperatorError,
  type Plan,
  recordPayment,
  resetOwnerSignIn,
  resetOwnerTwoStep,
  makeOwner,
  reissueOwnerInvitation,
  resumeBusiness,
  setBusinessModules,
  setBusinessPilot,
  setBusinessPlan,
  setSubscriptionDates,
  setSubscriptionStatus,
  type SubscriptionStatus,
  suspendBusiness,
  type TenantAdmin,
} from '../../api/operator';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { formatMinor, parseMinor } from '../../components/money';
import { ConfirmPanel, TourButton } from './operator-parts';
import {
  type Action,
  actionSentence,
  expectedAmount,
  formatDay,
  formatMoment,
  historyLabel,
  limitsLine,
  messageLabel,
  METHODS,
  methodLabel,
  MODULES,
  moduleLabel,
  type PaymentForm,
  paymentFormFor,
  paymentProblems,
  paymentSentence,
  type Problems,
  priceLine,
  reasonProblem,
  STATUS_MEANING,
  STATUSES,
  statusBadge,
  statusLabel,
  subscriptionLine,
  todayInKampala,
} from './operator-state';

// The Business page (issue #407): one tenant's details and owner, its modules, its subscription (status, plan, free month and paid
// until), suspend and resume with a reason, a payment received, the owner's sign-in rescue (a one-time reset link, or a lost
// two-step sign-in reset, each with the operator's own authenticator code), and what was sent to it and done to it. Every change is
// confirmed in plain words first and carries a reason that the platform audit log keeps.

function errorText(err: unknown): string {
  return err instanceof OperatorError ? err.message : 'Something went wrong. Try again.';
}

export function BusinessFacts({ detail }: { detail: BusinessDetail }) {
  const b = detail.tenant ?? {};
  // The Owner of the business (ADR-062), listed first among the administrators; an older business may have none yet.
  const owner = (detail.admins ?? []).find((a) => a.is_owner === true);
  return (
    <dl className="facts" data-tour="biz-details">
      <dt>Address</dt>
      <dd className="wrap-anywhere">
        {b.address ? (
          <a href={b.address} target="_blank" rel="noreferrer">
            {b.address.replace(/^https?:\/\//, '')}
          </a>
        ) : (
          b.slug
        )}
      </dd>
      <dt>Owner</dt>
      <dd>
        {owner
          ? `${owner.full_name ?? ''}, ${owner.email ?? owner.phone ?? ''}`
          : (detail.admins ?? []).length > 0
            ? 'No owner yet: choose one of the administrators below'
            : 'No administrator yet'}
      </dd>
      <dt>Last sign-in</dt>
      <dd>{b.last_sign_in_at ? formatMoment(b.last_sign_in_at) : 'Nobody has signed in yet'}</dd>
      <dt>Created</dt>
      <dd>{formatDay(b.created_at)}</dd>
      <dt>Currency and time zone</dt>
      <dd>
        {b.currency}, {b.timezone}
      </dd>
    </dl>
  );
}

export function SubscriptionFacts({ detail, today }: { detail: BusinessDetail; today: string }) {
  const b = detail.tenant ?? {};
  const status = b.subscription_status as SubscriptionStatus | undefined;
  return (
    <dl className="facts" data-tour="biz-status">
      <dt>Status</dt>
      <dd>
        <span className={statusBadge(status)}>{statusLabel(status)}</span> {status ? STATUS_MEANING[status] : ''}
      </dd>
      <dt>Where it stands</dt>
      <dd>{subscriptionLine(b, today)}</dd>
      <dt>Plan</dt>
      <dd>
        {b.plan_name ?? b.plan_code}: {priceLine(detail.plan_price)} (<Link to="/platform/plans">Plans and prices</Link>)
      </dd>
      <dt>Free month ends</dt>
      <dd>{b.pilot ? 'Never: a pilot' : b.trial_ends_on ? formatDay(b.trial_ends_on) : 'Starts at the first sign-in or the first payment'}</dd>
      <dt>Pilot</dt>
      <dd>{b.pilot ? 'Yes: free, with no end date, never suspended by itself' : 'No'}</dd>
      <dt>Paid until</dt>
      <dd>{b.paid_until ? formatDay(b.paid_until) : 'Nothing paid yet'}</dd>
    </dl>
  );
}

export function PaymentsList({ detail }: { detail: BusinessDetail }) {
  const payments = detail.payments ?? [];
  if (payments.length === 0) return <p className="muted">No payment recorded yet.</p>;
  return (
    <ul className="ln-list" aria-label="Payments">
      {payments.map((p) => (
        <li key={p.id} className="ln-item">
          <p className="ln-item-head">
            <strong>{formatMinor(p.amount_minor ?? 0, p.currency ?? 'UGX')}</strong>
            <span>{methodLabel(p.method)}</span>
          </p>
          <p className="ln-item-facts">
            <span>
              Covers {formatDay(p.period_start)} to {formatDay(p.period_end)}
            </span>
            <span>Paid on {formatDay(p.paid_on)}</span>
            {p.reference && <span>Reference {p.reference}</span>}
            <span>Recorded by {p.recorded_by_name ?? 'an operator'}</span>
          </p>
          {p.note && <p className="ln-muted pre-line">{p.note}</p>}
        </li>
      ))}
    </ul>
  );
}

export function MessagesList({ detail }: { detail: BusinessDetail }) {
  const messages = detail.messages ?? [];
  if (messages.length === 0) return <p className="muted">Nothing sent recently. Sent messages are cleared after a while.</p>;
  return (
    <ul className="ln-list" aria-label="Messages">
      {messages.map((m) => (
        <li key={m.id} className="ln-item">
          <p className="ln-item-head">
            <span>{messageLabel(m.template_key)}</span>
            <span className={m.status === 'failed' ? 'badge badge-danger' : m.status === 'sent' ? 'badge badge-success' : 'badge'}>
              {m.status === 'sent' ? 'Sent' : m.status === 'failed' ? 'Not sent' : 'Waiting'}
            </span>
          </p>
          <p className="ln-item-facts">
            <span>
              {m.channel === 'email' ? 'Email' : m.channel} to {m.recipient}
            </span>
            <span>{formatMoment(m.sent_at ?? m.failed_at ?? m.created_at)}</span>
          </p>
        </li>
      ))}
    </ul>
  );
}

export function HistoryList({ detail }: { detail: BusinessDetail }) {
  const history = detail.history ?? [];
  if (history.length === 0) return <p className="muted">No operator action yet.</p>;
  return (
    <ul className="ln-list" aria-label="History">
      {history.map((h, i) => (
        <li key={`${h.occurred_at}-${i}`} className="ln-item">
          <p className="ln-item-head">
            <span>{historyLabel(h.action)}</span>
            <span className="muted">{formatMoment(h.occurred_at)}</span>
          </p>
          <p className="ln-item-facts">
            <span>By {h.operator_name ?? 'the system'}</span>
            {h.reason && <span>Why: {h.reason}</span>}
          </p>
        </li>
      ))}
    </ul>
  );
}

export interface PaymentFormViewProps {
  business: Business;
  form: PaymentForm;
  problems: Problems;
  onChange: (f: PaymentForm) => void;
  onReview: (e: FormEvent) => void;
  onCancel: () => void;
  expected: (months: number) => string;
}

export function PaymentFormView({ business, form, problems, onChange, onReview, onCancel, expected }: PaymentFormViewProps) {
  const set = (patch: Partial<PaymentForm>) => onChange({ ...form, ...patch });
  const field = (key: string) => ({
    'aria-invalid': Boolean(problems[key]),
    'aria-describedby': problems[key] ? `pay-${key}-problem` : undefined,
  });
  const problem = (key: string) =>
    problems[key] ? (
      <span id={`pay-${key}-problem`} className="field-error">
        {problems[key]}
      </span>
    ) : null;
  return (
    <form className="card form-stack" onSubmit={onReview} aria-labelledby="pay-heading">
      <h3 id="pay-heading">Record a payment received</h3>
      <div>
        <label htmlFor="pay-months">Months it covers</label>
        <select
          id="pay-months"
          value={form.months}
          onChange={(e) => {
            const months = Number(e.target.value);
            const amount = expected(months);
            set({ months: e.target.value, amount: amount || form.amount });
          }}
          {...field('months')}
        >
          {[1, 2, 3, 6, 12].map((n) => (
            <option key={n} value={String(n)}>
              {n === 12 ? '1 year (12 months)' : n === 1 ? '1 month' : `${n} months`}
            </option>
          ))}
        </select>
        {problem('months')}
      </div>
      <div>
        <label htmlFor="pay-amount">Amount received ({business.currency})</label>
        <input id="pay-amount" inputMode="numeric" value={form.amount} onChange={(e) => set({ amount: e.target.value })} {...field('amount')} />
        {problem('amount')}
      </div>
      <div>
        <label htmlFor="pay-method">How it was paid</label>
        <select id="pay-method" value={form.method} onChange={(e) => set({ method: e.target.value })}>
          {METHODS.map((m) => (
            <option key={m.key} value={m.key}>
              {m.label}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor="pay-reference">Transaction reference (optional)</label>
        <input id="pay-reference" value={form.reference} maxLength={100} onChange={(e) => set({ reference: e.target.value })} {...field('reference')} />
        {problem('reference')}
      </div>
      <div>
        <label htmlFor="pay-on">Day the money came in</label>
        <input id="pay-on" type="date" value={form.paidOn} onChange={(e) => set({ paidOn: e.target.value })} {...field('paidOn')} />
        {problem('paidOn')}
      </div>
      <div>
        <label htmlFor="pay-note">Note (optional)</label>
        <textarea id="pay-note" rows={2} maxLength={500} value={form.note} onChange={(e) => set({ note: e.target.value })} />
      </div>
      <div className="cluster">
        <button type="submit" className="btn-primary">
          Review
        </button>
        <button type="button" className="btn-ghost" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

type Open =
  | { kind: 'none' }
  | { kind: 'confirm'; action: Action; value?: string; planName?: string }
  | { kind: 'payment'; reviewing: boolean }
  | { kind: 'rescue'; admin: TenantAdmin; what: 'sign-in' | 'two-step' | 'owner' | 'invite' };

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? `pay-${crypto.randomUUID()}` : `pay-${Date.now()}-${Math.random()}`;
}

export function BusinessScreen({ tenantId }: { tenantId: string }) {
  const client = useQueryClient();
  const query = useQuery({ queryKey: ['business', tenantId], queryFn: () => businessOverview(tenantId) });
  const plans = useQuery({ queryKey: ['price-plans'], queryFn: () => listPricePlans(false) });
  const [open, setOpen] = useState<Open>({ kind: 'none' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [planPick, setPlanPick] = useState('');
  const [statusPick, setStatusPick] = useState<SubscriptionStatus>('active');
  const [datePick, setDatePick] = useState('');
  const [clearDate, setClearDate] = useState(false);
  const [unusual, setUnusual] = useState<string | null>(null);
  const [confirmUnusual, setConfirmUnusual] = useState(false);
  const [modules, setModules] = useState<string[] | null>(null);
  const [payment, setPayment] = useState<PaymentForm | null>(null);
  const [problems, setProblems] = useState<Problems>({});
  const [code, setCode] = useState('');
  const [rescueReason, setRescueReason] = useState('');
  const [link, setLink] = useState<string | null>(null);
  // An invitation link lasts 72 hours, a reset link one hour (ADR-062, ADR-042).
  const [linkIsInvitation, setLinkIsInvitation] = useState(false);
  const key = useRef<string>(newKey());
  const today = todayInKampala();

  /**
   * A change to the payment form is a new request, so it gets a new Idempotency-Key. Ticking or unticking the box that
   * confirms an unusual amount keeps the key: the business and the amount are the same, so a resend after a lost answer is
   * answered from the first and never recorded twice.
   */
  function changePayment(next: PaymentForm) {
    key.current = newKey();
    setUnusual(null);
    setConfirmUnusual(false);
    setPayment(next);
  }

  const detail = query.data;
  const b: Business = detail?.tenant ?? {};
  const currentModules = useMemo(() => [...(b.modules ?? [])].sort(), [b.modules]);
  const shownModules = modules ?? currentModules;

  function close() {
    setOpen({ kind: 'none' });
    setError(null);
    setCode('');
    setRescueReason('');
  }

  async function run(action: () => Promise<unknown>, message: string) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await action();
      setDone(message);
      setOpen({ kind: 'none' });
      setModules(null);
      await client.invalidateQueries({ queryKey: ['business', tenantId] });
      await client.invalidateQueries({ queryKey: ['businesses'] });
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  function confirmAction(action: Action, reason: string, value?: string) {
    const name = b.name ?? 'The business';
    switch (action) {
      case 'suspend':
        return run(() => suspendBusiness(tenantId, reason), `${name} is suspended: read only, nothing deleted.`);
      case 'resume':
        return run(() => resumeBusiness(tenantId, reason), `${name} works normally again.`);
      case 'status':
        return run(
          () => setSubscriptionStatus(tenantId, value as SubscriptionStatus, reason),
          `${name} is now ${statusLabel(value)}.`,
        );
      case 'plan':
        return run(() => setBusinessPlan(tenantId, value ?? '', reason), `${name} is on the new plan.`);
      case 'trial':
        return clearDate
          ? run(() => setSubscriptionDates(tenantId, { clear_trial_ends_on: true }, reason), 'The free month now starts at the next sign-in.')
          : run(() => setSubscriptionDates(tenantId, { trial_ends_on: value }, reason), `The free month now ends on ${formatDay(value)}.`);
      case 'paid':
        return clearDate
          ? run(() => setSubscriptionDates(tenantId, { clear_paid_until: true }, reason), 'Nothing is shown as paid any more.')
          : run(() => setSubscriptionDates(tenantId, { paid_until: value }, reason), `Paid until is now ${formatDay(value)}.`);
      case 'modules':
        return run(() => setBusinessModules(tenantId, shownModules, reason), 'The modules are saved.');
      case 'pilot':
        return run(
          () => setBusinessPilot(tenantId, !b.pilot, reason),
          b.pilot ? `${name} is no longer a pilot.` : `${name} is a pilot: free, with no end date.`,
        );
    }
  }

  function startPayment() {
    changePayment(paymentFormFor(detail?.plan_price, today));
    setProblems({});
    setDone(null);
    setOpen({ kind: 'payment', reviewing: false });
  }

  function reviewPayment(e: FormEvent) {
    e.preventDefault();
    if (!payment) return;
    const found = paymentProblems(payment, b.currency ?? 'UGX', today);
    setProblems(found);
    if (Object.keys(found).length === 0) setOpen({ kind: 'payment', reviewing: true });
  }

  function sendPayment(reason: string) {
    if (!payment) return;
    const amount = parseMinor(payment.amount, b.currency ?? 'UGX') ?? 0;
    void run(
      () =>
        recordPayment(tenantId, key.current, {
          amount_minor: amount,
          method: payment.method,
          reference: payment.reference.trim() || undefined,
          paid_on: payment.paidOn,
          months: Number(payment.months),
          note: payment.note.trim() || undefined,
          reason,
          confirm_unusual_amount: confirmUnusual || undefined,
        }).catch((err: unknown) => {
          if (err instanceof OperatorError && err.code === 'payment_amount_unusual') setUnusual(err.message);
          throw err;
        }),
      'The payment is recorded.',
    );
  }

  function rescue(admin: TenantAdmin, what: 'sign-in' | 'two-step' | 'owner' | 'invite') {
    if (busy) return;
    if (!/^\d{6}$/.test(code.trim())) {
      setError('Type the 6 digit code from your own authenticator app.');
      return;
    }
    const why = reasonProblem(rescueReason);
    if (why) {
      setError(why);
      return;
    }
    setBusy(true);
    setError(null);
    const call =
      what === 'invite'
        ? reissueOwnerInvitation(tenantId, admin.id ?? '', code.trim(), rescueReason.trim()).then((r) => {
            setLinkIsInvitation(true);
            setLink(r.url ?? null);
          })
        : what === 'owner'
        ? makeOwner(tenantId, admin.id ?? '', code.trim(), rescueReason.trim()).then(() =>
            setDone(`${admin.full_name ?? 'The administrator'} is now the owner of the business.`),
          )
        : what === 'sign-in'
        ? resetOwnerSignIn(tenantId, admin.id ?? '', code.trim(), rescueReason.trim()).then((r) => {
            setLinkIsInvitation(false);
            setLink(r.url ?? null);
          })
        : resetOwnerTwoStep(tenantId, admin.id ?? '', code.trim(), rescueReason.trim()).then(() =>
            setDone(`${admin.full_name ?? 'The owner'} can sign in with the password and set up two-step sign-in again.`),
          );
    call
      .then(() => {
        setOpen({ kind: 'none' });
        setCode('');
        setRescueReason('');
        return client.invalidateQueries({ queryKey: ['business', tenantId] });
      })
      .catch((err: unknown) => setError(errorText(err)))
      .finally(() => setBusy(false));
  }

  const status = b.subscription_status as SubscriptionStatus | undefined;
  const readOnly = status === 'suspended' || status === 'cancelled';
  const activePlans: Plan[] = (plans.data ?? []).filter((p) => p.code !== b.plan_code);

  return (
    <main>
      <Breadcrumbs
        items={[{ label: 'Operator portal', to: '/platform' }, { label: 'Businesses', to: '/platform/businesses' }, { label: b.name ?? 'Business' }]}
      />
      <div className="page-header">
        <h1>
          {b.name ?? 'Business'} {status && <span className={statusBadge(status)}>{statusLabel(status)}</span>}
        </h1>
        <TourButton />
      </div>
      {query.isPending && (
        <p role="status" className="loading">
          Loading
        </p>
      )}
      {query.isError && (
        <p role="alert" className="alert alert-danger">
          {query.error.message}
        </p>
      )}
      {done && (
        <p role="status" className="alert alert-success">
          {done}
        </p>
      )}
      {link && (
        <section role="status" className="alert alert-success">
          <div>
            <p>Shown once. Copy it and send it to the owner. It works {linkIsInvitation ? 'for 72 hours' : 'for one hour'}, one time.</p>
            <p className="wrap-anywhere">
              <code>{link}</code>
            </p>
            <div className="cluster">
              <button type="button" onClick={() => void navigator.clipboard?.writeText(link)}>
                Copy the link
              </button>
              <button type="button" className="btn-ghost" onClick={() => setLink(null)}>
                Done
              </button>
            </div>
          </div>
        </section>
      )}
      {detail && (
        <>
          <section aria-labelledby="biz-details-heading">
            <h2 id="biz-details-heading">Details</h2>
            <BusinessFacts detail={detail} />
          </section>

          <section aria-labelledby="biz-sub-heading">
            <h2 id="biz-sub-heading">Subscription</h2>
            <SubscriptionFacts detail={detail} today={today} />
            <div className="ln-actions">
              <button type="button" className="btn-primary" data-tour="biz-payment" onClick={startPayment}>
                Record a payment
              </button>
              <button type="button" data-tour="biz-plan" onClick={() => setOpen({ kind: 'confirm', action: 'plan' })}>
                Change plan
              </button>
              {!b.pilot && (
                <button type="button" onClick={() => {
                  setDatePick(b.trial_ends_on ?? today);
                  setClearDate(false);
                  setOpen({ kind: 'confirm', action: 'trial' });
                }}>
                  Change free month end
                </button>
              )}
              <button type="button" onClick={() => {
                setDatePick(b.paid_until ?? today);
                setClearDate(false);
                setOpen({ kind: 'confirm', action: 'paid' });
              }}>
                Correct paid until
              </button>
              <button
                type="button"
                onClick={() => {
                  setStatusPick(STATUSES.find((s) => s !== status) ?? 'active');
                  setOpen({ kind: 'confirm', action: 'status' });
                }}
              >
                Change status
              </button>
              <button type="button" onClick={() => setOpen({ kind: 'confirm', action: 'pilot' })}>
                {b.pilot ? 'Stop being a pilot' : 'Mark as a pilot'}
              </button>
              {readOnly ? (
                <button type="button" data-tour="biz-suspend" onClick={() => setOpen({ kind: 'confirm', action: 'resume' })}>
                  Resume
                </button>
              ) : (
                <button type="button" className="btn-danger" data-tour="biz-suspend" onClick={() => setOpen({ kind: 'confirm', action: 'suspend' })}>
                  Suspend
                </button>
              )}
            </div>

            {open.kind === 'payment' && payment && !open.reviewing && (
              <PaymentFormView
                business={b}
                form={payment}
                problems={problems}
                onChange={changePayment}
                onReview={reviewPayment}
                onCancel={close}
                expected={(months) => expectedAmount(detail.plan_price, months)}
              />
            )}
            {open.kind === 'payment' && payment && open.reviewing && (
              <ConfirmPanel
                id="pay-confirm"
                sentence={paymentSentence(b, payment, today)}
                confirm="Yes, record it"
                busy={busy}
                error={error}
                onConfirm={sendPayment}
                onCancel={() => setOpen({ kind: 'payment', reviewing: false })}
              >
                {unusual && (
                  <label>
                    <input
                      type="checkbox"
                      checked={confirmUnusual}
                      onChange={(e) => setConfirmUnusual(e.target.checked)}
                    />
                    I checked it: this amount is right
                  </label>
                )}
              </ConfirmPanel>
            )}

            {open.kind === 'confirm' && open.action === 'plan' && (
              <ConfirmPanel
                id="plan-confirm"
                sentence={planPick ? actionSentence('plan', b, planPick, activePlans.find((p) => p.code === planPick)?.name) : 'Choose the new plan.'}
                confirm="Yes, change the plan"
                busy={busy}
                error={error}
                onConfirm={(reason) => {
                  if (!planPick) {
                    setError('Choose the new plan first.');
                    return;
                  }
                  void confirmAction('plan', reason, planPick);
                }}
                onCancel={close}
              >
                <div>
                  <label htmlFor="plan-pick">New plan</label>
                  <select id="plan-pick" value={planPick} onChange={(e) => setPlanPick(e.target.value)}>
                    <option value="">Choose</option>
                    {activePlans.map((p) => (
                      <option key={p.code} value={p.code}>
                        {p.name}: {limitsLine(p)}; {priceLine(p.current_price)}
                      </option>
                    ))}
                  </select>
                </div>
              </ConfirmPanel>
            )}

            {open.kind === 'confirm' && (open.action === 'trial' || open.action === 'paid') && (
              <ConfirmPanel
                id="date-confirm"
                sentence={
                  clearDate
                    ? open.action === 'trial'
                      ? `Clear the end of the free month of ${b.name ?? 'this business'}? It starts again at the next sign-in.`
                      : `Clear what is paid for ${b.name ?? 'this business'}? Use this only to correct a mistake.`
                    : actionSentence(open.action, b, datePick)
                }
                confirm={clearDate ? 'Yes, clear it' : 'Yes, change the date'}
                busy={busy}
                error={error}
                onConfirm={(reason) => void confirmAction(open.action, reason, datePick)}
                onCancel={close}
              >
                <div>
                  <label htmlFor="date-pick">{open.action === 'trial' ? 'Last day of the free month' : 'Last day paid for'}</label>
                  <input id="date-pick" type="date" value={datePick} disabled={clearDate} onChange={(e) => setDatePick(e.target.value)} />
                </div>
                <label>
                  <input type="checkbox" checked={clearDate} onChange={(e) => setClearDate(e.target.checked)} />
                  Clear this date instead
                </label>
              </ConfirmPanel>
            )}

            {open.kind === 'confirm' && open.action === 'status' && (
              <ConfirmPanel
                id="status-confirm"
                sentence={actionSentence('status', b, statusPick)}
                confirm="Yes, change the status"
                danger={statusPick === 'suspended' || statusPick === 'cancelled'}
                busy={busy}
                error={error}
                onConfirm={(reason) => void confirmAction('status', reason, statusPick)}
                onCancel={close}
              >
                <div>
                  <label htmlFor="status-pick">New status</label>
                  <select id="status-pick" value={statusPick} onChange={(e) => setStatusPick(e.target.value as SubscriptionStatus)}>
                    {STATUSES.filter((s) => s !== status).map((s) => (
                      <option key={s} value={s}>
                        {statusLabel(s)}
                      </option>
                    ))}
                  </select>
                </div>
              </ConfirmPanel>
            )}

            {open.kind === 'confirm' && open.action === 'pilot' && (
              <ConfirmPanel
                id="pilot-confirm"
                sentence={actionSentence('pilot', b)}
                confirm={b.pilot ? 'Yes, stop the pilot' : 'Yes, mark as a pilot'}
                busy={busy}
                error={error}
                onConfirm={(reason) => void confirmAction('pilot', reason)}
                onCancel={close}
              />
            )}

            {open.kind === 'confirm' && (open.action === 'suspend' || open.action === 'resume') && (
              <ConfirmPanel
                id="suspend-confirm"
                sentence={actionSentence(open.action, b)}
                confirm={open.action === 'suspend' ? 'Yes, suspend it' : 'Yes, resume it'}
                danger={open.action === 'suspend'}
                busy={busy}
                error={error}
                onConfirm={(reason) => void confirmAction(open.action, reason)}
                onCancel={close}
              />
            )}
          </section>

          <section aria-labelledby="biz-modules-heading" data-tour="biz-modules">
            <h2 id="biz-modules-heading">Modules</h2>
            <fieldset>
              <legend>Switched on for this business</legend>
              {MODULES.map((m) => (
                <label key={m.key}>
                  <input
                    type="checkbox"
                    checked={shownModules.includes(m.key)}
                    onChange={(e) =>
                      setModules(
                        e.target.checked ? [...new Set([...shownModules, m.key])].sort() : shownModules.filter((k) => k !== m.key),
                      )
                    }
                  />
                  {m.label}
                </label>
              ))}
            </fieldset>
            {modules && modules.join(',') !== currentModules.join(',') && open.kind === 'none' && (
              <button type="button" onClick={() => setOpen({ kind: 'confirm', action: 'modules' })}>
                Review the change
              </button>
            )}
            {open.kind === 'confirm' && open.action === 'modules' && (
              <ConfirmPanel
                id="modules-confirm"
                sentence={actionSentence('modules', b, shownModules.map(moduleLabel).join(' and '))}
                confirm="Yes, save the modules"
                busy={busy}
                error={error}
                onConfirm={(reason) => void confirmAction('modules', reason)}
                onCancel={() => {
                  close();
                  setModules(null);
                }}
              />
            )}
          </section>

          <section aria-labelledby="biz-rescue-heading" data-tour="biz-rescue">
            <h2 id="biz-rescue-heading">Owner sign-in help</h2>
            <p className="muted">
              For an owner who forgot the password or lost the phone with the authenticator. Each needs a code from your own
              authenticator app, and the owner is told by email.
            </p>
            {(detail.admins ?? []).length === 0 && <p>No administrator yet. The activation link is in the messages below.</p>}
            <ul className="ln-list" aria-label="Administrators">
              {(detail.admins ?? []).map((a) => (
                <li key={a.id} className="ln-item">
                  <p className="ln-item-head">
                    <strong>{a.full_name}</strong>
                    <span className="badge">{a.status === 'invited' ? 'Invited' : a.status === 'active' ? 'Active' : 'Deactivated'}</span>
                    {a.is_owner === true && <span className="badge badge-info">Owner</span>}
                  </p>
                  <p className="ln-item-facts">
                    <span>{a.email ?? a.phone}</span>
                    <span>Two-step sign-in {a.mfa_enabled ? 'on' : 'off'}</span>
                    <span>Last sign-in: {a.last_login_at ? formatMoment(a.last_login_at) : 'never'}</span>
                  </p>
                  {a.status === 'invited' && a.is_owner === true && (
                    <div className="ln-actions">
                      <button type="button" onClick={() => setOpen({ kind: 'rescue', admin: a, what: 'invite' })}>
                        Send a new invitation link
                      </button>
                    </div>
                  )}
                  {a.status === 'active' && (
                    <div className="ln-actions">
                      <button type="button" onClick={() => setOpen({ kind: 'rescue', admin: a, what: 'sign-in' })}>
                        Make a reset link
                      </button>
                      {a.mfa_enabled && (
                        <button type="button" onClick={() => setOpen({ kind: 'rescue', admin: a, what: 'two-step' })}>
                          Reset lost two-step sign-in
                        </button>
                      )}
                      {a.is_owner !== true && (
                        <button type="button" data-tour="biz-make-owner" onClick={() => setOpen({ kind: 'rescue', admin: a, what: 'owner' })}>
                          Make the owner
                        </button>
                      )}
                    </div>
                  )}
                  {open.kind === 'rescue' && open.admin.id === a.id && (
                    <form
                      className="card form-stack"
                      onSubmit={(e) => {
                        e.preventDefault();
                        rescue(a, open.what);
                      }}
                    >
                      <p>
                        <strong>
                          {open.what === 'invite'
                            ? `Make a new invitation link for ${a.full_name ?? 'the owner'}? The old link stops working. The new one works for 72 hours and is shown to you once.`
                            : open.what === 'owner'
                            ? `Make ${a.full_name ?? 'this administrator'} the owner of the business? Only they can then add, remove or change an administrator, and nobody in the business can remove them. ${(detail.admins ?? []).find((x) => x.is_owner === true)?.full_name ?? 'The owner of now'} stays an administrator.`
                            : open.what === 'sign-in'
                            ? `Make a one-time link for ${a.full_name ?? 'the owner'} to set a new password? It works for one hour and is shown to you once.`
                            : `Clear the two-step sign-in of ${a.full_name ?? 'the owner'}? Their sessions end and they set it up again at the next sign-in.`}
                        </strong>
                      </p>
                      <div>
                        <label htmlFor={`code-${a.id}`}>Code from your own authenticator app</label>
                        <input
                          id={`code-${a.id}`}
                          className="input-code"
                          inputMode="numeric"
                          autoComplete="one-time-code"
                          maxLength={6}
                          value={code}
                          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                        />
                      </div>
                      <div>
                        <label htmlFor={`why-${a.id}`}>Why (kept in the history)</label>
                        <textarea
                          id={`why-${a.id}`}
                          rows={2}
                          maxLength={300}
                          value={rescueReason}
                          onChange={(e) => setRescueReason(e.target.value)}
                        />
                      </div>
                      {error && (
                        <p role="alert" className="alert alert-danger">
                          {error}
                        </p>
                      )}
                      <div className="cluster">
                        <button type="submit" className="btn-primary" disabled={busy}>
                          {busy ? 'Working' : open.what === 'invite' ? 'Yes, make the link' : open.what === 'owner' ? 'Yes, make them the owner' : open.what === 'sign-in' ? 'Yes, make the link' : 'Yes, reset it'}
                        </button>
                        <button type="button" className="btn-ghost" onClick={close} disabled={busy}>
                          Cancel
                        </button>
                      </div>
                    </form>
                  )}
                </li>
              ))}
            </ul>
          </section>

          <section aria-labelledby="biz-payments-heading">
            <h2 id="biz-payments-heading">Payments</h2>
            <PaymentsList detail={detail} />
          </section>

          <section aria-labelledby="biz-messages-heading" data-tour="biz-messages">
            <h2 id="biz-messages-heading">Emails sent</h2>
            <MessagesList detail={detail} />
            <p className="muted">
              A message that could not be sent is also on <Link to="/platform/outbox">Messages not sent</Link>.
            </p>
          </section>

          <section aria-labelledby="biz-history-heading" data-tour="biz-history">
            <h2 id="biz-history-heading">What was done</h2>
            <HistoryList detail={detail} />
          </section>
        </>
      )}
    </main>
  );
}

function BusinessRoute() {
  const { tenantId } = useParams({ from: '/platform/businesses/$tenantId' });
  return <BusinessScreen tenantId={tenantId} />;
}

export const Route = createLazyRoute('/platform/businesses/$tenantId')({ component: BusinessRoute });
