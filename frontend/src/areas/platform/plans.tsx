import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, Link } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { addPlanPrice, listPricePlans, OperatorError, type Plan, savePlan } from '../../api/operator';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { parseMinor } from '../../components/money';
import { ConfirmPanel, TourButton } from './operator-parts';
import {
  EMPTY_PLAN,
  formatDay,
  largePriceChange,
  limitsLine,
  MODULES,
  moduleLabel,
  type PlanForm,
  planFormFrom,
  planProblems,
  type PriceForm,
  priceLine,
  priceProblems,
  priceSentence,
  type Problems,
  todayInKampala,
  toSavePlan,
} from './operator-state';

// Plans and prices (issue #407, ADR-056): the price book. A plan holds the limits a business works within and, from a date, its
// monthly price and the months charged for a year. Nothing here is preset: every plan and every price is typed by the operator,
// and a new price starts today or later, so what a business was charged before never changes.

export function PlanCards({ plans, onEdit, onPrice }: { plans: Plan[]; onEdit: (p: Plan) => void; onPrice: (p: Plan) => void }) {
  if (plans.length === 0) return <p>No plan yet. Add the first one below.</p>;
  return (
    <ul className="ln-list" aria-label="Plans" data-tour="plans-list">
      {plans.map((p, i) => (
        <li key={p.code} className="ln-item">
          <p className="ln-item-head">
            <strong>{p.name}</strong>
            {p.active ? <span className="badge badge-success">Offered</span> : <span className="badge">Not offered</span>}
          </p>
          <p data-tour={i === 0 ? 'plans-price-line' : undefined}>{priceLine(p.current_price)}</p>
          {p.next_price && (
            <p className="ln-muted">
              From {formatDay(p.next_price.effective_from)}: {priceLine(p.next_price)}
            </p>
          )}
          <p className="ln-item-facts">
            <span>{limitsLine(p)}</span>
            <span>Modules: {(p.allowed_modules ?? []).map(moduleLabel).join(', ')}</span>
            <span>
              {p.tenant_count === 1 ? '1 business' : `${p.tenant_count ?? 0} businesses`} on it
            </span>
            <span>Code {p.code}</span>
          </p>
          {p.description && <p className="ln-muted pre-line">{p.description}</p>}
          <div className="ln-actions">
            <button type="button" className="btn-primary" onClick={() => onPrice(p)} data-tour={i === 0 ? 'plans-set-price' : undefined}>
              Set the price
            </button>
            <button type="button" onClick={() => onEdit(p)}>
              Change limits
            </button>
          </div>
        </li>
      ))}
    </ul>
  );
}

export interface PlanFormViewProps {
  form: PlanForm;
  isNew: boolean;
  problems: Problems;
  busy: boolean;
  error: string | null;
  onChange: (f: PlanForm) => void;
  onSubmit: (e: FormEvent) => void;
  onCancel: () => void;
}

export function PlanFormView({ form, isNew, problems, busy, error, onChange, onSubmit, onCancel }: PlanFormViewProps) {
  const set = (patch: Partial<PlanForm>) => onChange({ ...form, ...patch });
  const problem = (key: string) =>
    problems[key] ? (
      <span id={`plan-${key}-problem`} className="field-error">
        {problems[key]}
      </span>
    ) : null;
  const aria = (key: string) => ({ 'aria-invalid': Boolean(problems[key]), 'aria-describedby': problems[key] ? `plan-${key}-problem` : undefined });
  return (
    <form className="card form-stack" onSubmit={onSubmit} aria-labelledby="plan-form-heading" aria-busy={busy} data-tour="plans-form">
      <h2 id="plan-form-heading">{isNew ? 'New plan' : `Change ${form.name}`}</h2>
      {isNew && (
        <div>
          <label htmlFor="plan-code">Code (small letters, cannot change later)</label>
          <input id="plan-code" value={form.code} maxLength={40} onChange={(e) => set({ code: e.target.value.toLowerCase() })} {...aria('code')} />
          {problem('code')}
        </div>
      )}
      <div>
        <label htmlFor="plan-name">Name the owner sees</label>
        <input id="plan-name" value={form.name} maxLength={80} onChange={(e) => set({ name: e.target.value })} {...aria('name')} />
        {problem('name')}
      </div>
      <div>
        <label htmlFor="plan-description">What it includes (optional)</label>
        <textarea id="plan-description" rows={2} maxLength={500} value={form.description} onChange={(e) => set({ description: e.target.value })} {...aria('description')} />
        {problem('description')}
      </div>
      <fieldset>
        <legend>Modules</legend>
        {MODULES.map((m) => (
          <label key={m.key}>
            <input
              type="checkbox"
              checked={form.modules.includes(m.key)}
              onChange={(e) => set({ modules: e.target.checked ? [...new Set([...form.modules, m.key])] : form.modules.filter((k) => k !== m.key) })}
            />
            {m.label}
          </label>
        ))}
        {problem('modules')}
      </fieldset>
      <p className="hint">Leave a limit empty for unlimited.</p>
      <div>
        <label htmlFor="plan-branches">Shops and stores at most</label>
        <input id="plan-branches" inputMode="numeric" value={form.branches} onChange={(e) => set({ branches: e.target.value })} {...aria('branches')} />
        {problem('branches')}
      </div>
      <div>
        <label htmlFor="plan-staff">Staff users at most</label>
        <input id="plan-staff" inputMode="numeric" value={form.staff} onChange={(e) => set({ staff: e.target.value })} {...aria('staff')} />
        {problem('staff')}
      </div>
      {form.modules.includes('lending') && (
        <div>
          <label htmlFor="plan-members">Lending members at most</label>
          <input id="plan-members" inputMode="numeric" value={form.members} onChange={(e) => set({ members: e.target.value })} {...aria('members')} />
          {problem('members')}
        </div>
      )}
      <div>
        <label htmlFor="plan-sort">Order in the list (0 first)</label>
        <input id="plan-sort" inputMode="numeric" value={form.sort} onChange={(e) => set({ sort: e.target.value })} {...aria('sort')} />
        {problem('sort')}
      </div>
      <label>
        <input type="checkbox" checked={form.active} onChange={(e) => set({ active: e.target.checked })} />
        Offered to new businesses
      </label>
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      <div className="cluster">
        <button type="submit" className="btn-primary" disabled={busy}>
          Review
        </button>
        <button type="button" className="btn-ghost" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </form>
  );
}

export interface PriceFormViewProps {
  plan: Plan;
  form: PriceForm;
  problems: Problems;
  onChange: (f: PriceForm) => void;
  onSubmit: (e: FormEvent) => void;
  onCancel: () => void;
}

export function PriceFormView({ plan, form, problems, onChange, onSubmit, onCancel }: PriceFormViewProps) {
  const set = (patch: Partial<PriceForm>) => onChange({ ...form, ...patch });
  const problem = (key: string) =>
    problems[key] ? (
      <span id={`price-${key}-problem`} className="field-error">
        {problems[key]}
      </span>
    ) : null;
  return (
    <form className="card form-stack" onSubmit={onSubmit} aria-labelledby="price-heading" data-tour="plans-price-form">
      <h2 id="price-heading">Price of {plan.name}</h2>
      <p className="muted">Now: {priceLine(plan.current_price)}</p>
      <div>
        <label htmlFor="price-monthly">Price a month (UGX)</label>
        <input id="price-monthly" inputMode="numeric" value={form.monthly} onChange={(e) => set({ monthly: e.target.value })} aria-invalid={Boolean(problems.monthly)} />
        {problem('monthly')}
      </div>
      <div>
        <label htmlFor="price-annual">Months charged for a year</label>
        <input id="price-annual" inputMode="numeric" value={form.annualMonths} onChange={(e) => set({ annualMonths: e.target.value })} aria-invalid={Boolean(problems.annualMonths)} />
        <span className="field-hint">Fewer than 12 gives months free: 11 means one month free.</span>
        {problem('annualMonths')}
      </div>
      <div>
        <label htmlFor="price-from">Starts on</label>
        <input id="price-from" type="date" value={form.from} onChange={(e) => set({ from: e.target.value })} aria-invalid={Boolean(problems.from)} />
        {problem('from')}
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
  | { kind: 'plan'; isNew: boolean; reviewing: boolean }
  | { kind: 'price'; plan: Plan; reviewing: boolean };

export function PlansScreen() {
  const client = useQueryClient();
  const query = useQuery({ queryKey: ['price-plans', 'all'], queryFn: () => listPricePlans(true) });
  const [open, setOpen] = useState<Open>({ kind: 'none' });
  const [planForm, setPlanForm] = useState<PlanForm>(EMPTY_PLAN);
  const [priceForm, setPriceForm] = useState<PriceForm>({ monthly: '', annualMonths: '12', from: '' });
  const [problems, setProblems] = useState<Problems>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  // A second look the server or this screen asks for: a lowered limit that businesses on the plan are already over, or a
  // price more than a quarter away from the one in force. The box must be ticked before the change is sent again.
  const [warning, setWarning] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const today = todayInKampala();

  function close() {
    setOpen({ kind: 'none' });
    setError(null);
    setProblems({});
    setWarning(null);
    setConfirmed(false);
  }

  /** Keeps the server's warning on screen for the confirmation box, then fails as usual. */
  function warnOn(code: string) {
    return (err: unknown): never => {
      if (err instanceof OperatorError && err.code === code) setWarning(err.message);
      throw err;
    };
  }

  function needsTick(): boolean {
    if (warning && !confirmed) {
      setError('Tick the box to confirm, or go back and change it.');
      return true;
    }
    return false;
  }

  const warningBox = warning && (
    <div className="form-stack">
      <p role="alert" className="alert alert-warning">
        {warning}
      </p>
      <label>
        <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
        I checked it: this is right
      </label>
    </div>
  );

  async function run(action: () => Promise<unknown>, message: string) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await action();
      setDone(message);
      setOpen({ kind: 'none' });
      await client.invalidateQueries({ queryKey: ['price-plans'] });
    } catch (err) {
      setError(err instanceof OperatorError ? err.message : 'Something went wrong. Try again.');
    } finally {
      setBusy(false);
    }
  }

  function reviewPlan(e: FormEvent, isNew: boolean) {
    e.preventDefault();
    const found = planProblems(planForm, isNew);
    setProblems(found);
    setWarning(null);
    setConfirmed(false);
    if (Object.keys(found).length === 0) setOpen({ kind: 'plan', isNew, reviewing: true });
  }

  function reviewPrice(e: FormEvent, plan: Plan) {
    e.preventDefault();
    const found = priceProblems(priceForm, today);
    setProblems(found);
    setConfirmed(false);
    setWarning(largePriceChange(plan.current_price, parseMinor(priceForm.monthly, 'UGX') ?? 0) ?? null);
    if (Object.keys(found).length === 0) setOpen({ kind: 'price', plan, reviewing: true });
  }

  return (
    <main>
      <Breadcrumbs items={[{ label: 'Operator portal', to: '/platform' }, { label: 'Plans and prices' }]} />
      <div className="page-header">
        <h1>Plans and prices</h1>
        <TourButton />
      </div>
      <p className="lead">
        The plans a business can be on, with their limits and prices. You type every plan and price here; nothing is preset. A new
        price starts on the day you choose and never changes what was charged before. Move a business to a plan from its page in{' '}
        <Link to="/platform/businesses">Businesses</Link>.
      </p>
      {done && (
        <p role="status" className="alert alert-success">
          {done}
        </p>
      )}
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
      {query.data && (
        <PlanCards
          plans={query.data}
          onEdit={(p) => {
            setPlanForm(planFormFrom(p));
            setProblems({});
            setDone(null);
            setOpen({ kind: 'plan', isNew: false, reviewing: false });
          }}
          onPrice={(p) => {
            setPriceForm({
              monthly: p.current_price?.monthly_minor != null ? String(p.current_price.monthly_minor) : '',
              annualMonths: String(p.current_price?.annual_months ?? 12),
              from: today,
            });
            setProblems({});
            setDone(null);
            setOpen({ kind: 'price', plan: p, reviewing: false });
          }}
        />
      )}
      {open.kind === 'none' && (
        <button
          type="button"
          className="btn-brand"
          data-tour="plans-new"
          onClick={() => {
            setPlanForm(EMPTY_PLAN);
            setProblems({});
            setDone(null);
            setOpen({ kind: 'plan', isNew: true, reviewing: false });
          }}
        >
          Add a plan
        </button>
      )}
      {open.kind === 'plan' && !open.reviewing && (
        <PlanFormView
          form={planForm}
          isNew={open.isNew}
          problems={problems}
          busy={busy}
          error={error}
          onChange={setPlanForm}
          onSubmit={(e) => reviewPlan(e, open.isNew)}
          onCancel={close}
        />
      )}
      {open.kind === 'plan' && open.reviewing && (
        <ConfirmPanel
          id="plan-save"
          sentence={`${open.isNew ? 'Add' : 'Save'} ${planForm.name.trim()}: ${limitsLine({
            max_branches: toSavePlan(planForm, '').max_branches,
            max_staff_users: toSavePlan(planForm, '').max_staff_users,
            max_active_members: toSavePlan(planForm, '').max_active_members,
            allowed_modules: planForm.modules,
          })}; ${planForm.active ? 'offered to new businesses' : 'not offered to new businesses'}.${open.isNew ? '' : ' Businesses on it get the new limits at once.'}`}
          confirm={open.isNew ? 'Yes, add the plan' : 'Yes, save the plan'}
          busy={busy}
          error={error === warning ? null : error}
          onConfirm={(reason) => {
            if (needsTick()) return;
            void run(
              () =>
                savePlan(planForm.code.trim(), { ...toSavePlan(planForm, reason), confirm_below_use: confirmed || undefined }).catch(
                  warnOn('plan_limit_below_use'),
                ),
              open.isNew ? `${planForm.name.trim()} is added. Set its price next.` : `${planForm.name.trim()} is saved.`,
            );
          }}
          onCancel={() => setOpen({ kind: 'plan', isNew: open.isNew, reviewing: false })}
        >
          {warningBox}
        </ConfirmPanel>
      )}
      {open.kind === 'price' && !open.reviewing && (
        <PriceFormView plan={open.plan} form={priceForm} problems={problems} onChange={setPriceForm} onSubmit={(e) => reviewPrice(e, open.plan)} onCancel={close} />
      )}
      {open.kind === 'price' && open.reviewing && (
        <ConfirmPanel
          id="price-save"
          sentence={priceSentence(open.plan.name ?? open.plan.code ?? '', priceForm, today)}
          confirm="Yes, save the price"
          busy={busy}
          error={error === warning ? null : error}
          onConfirm={(reason) => {
            if (needsTick()) return;
            void run(
              () =>
                addPlanPrice(open.plan.code ?? '', {
                  currency: 'UGX',
                  monthly_minor: parseMinor(priceForm.monthly, 'UGX') ?? 0,
                  annual_months: Number(priceForm.annualMonths),
                  effective_from: priceForm.from,
                  reason,
                  confirm_large_change: confirmed || undefined,
                }).catch(warnOn('price_change_unusual')),
              `The price of ${open.plan.name ?? ''} is saved.`,
            );
          }}
          onCancel={() => setOpen({ kind: 'price', plan: open.plan, reviewing: false })}
        >
          {warningBox}
        </ConfirmPanel>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/platform/plans')({ component: PlansScreen });
