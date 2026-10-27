import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useState, type FormEvent } from 'react';
import { asFormError, unknownOutcome, type FormError } from '../../../api/lending-forms';
import { useApplicationsApi, useCollateralApi, useMembersApi } from '../../../api/lending-context';
import type { Loan, LoanListItem } from '../../../api/lending';
import { FieldsNeededNote } from '../../../app/ui/Field';
import { NoAccess } from '../../../app/ui/EmptyState';
import { exponentOf } from '../../../components/money';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import {
  applicationOrder, applicationProblems, blankApplication, createBody, draftOf, guarantorsBody, partiesChanged, pledgeable, pledgesBody,
  PURPOSES, rateWords, termsOf, termWords, updateBody, type ApplicationDraft, type GuarantorDraft,
} from './application-state';
import { typeWords } from './collateral-state';
import { Group, SaveBar, SelectField, TextField, useFormErrors, type FormErrors } from './form-fields';
import { LendingGate } from './loans';
import { amountText, currencyOf, localDate, money, tenantCurrency } from './loan-state';
import { mayManageCollateral } from './member-permissions';
import { MemberPicker } from './member-picker';
import { applicationSteps, mayApply } from './permissions';
import { SaveGuardNotice, useSaveGuard } from './save-guard';

// A loan application (issue #280 slice 3, FR-ORG-01 to FR-ORG-03): the member, the product, how much and for how long, what for,
// guarantors and the member's collateral, then "Send for appraisal". The same form changes a draft (a send-back returns one). It
// saves in steps, each carrying the loan's version: the draft, then its guarantors, then its collateral, then the submit. A step
// that fails after the draft exists leaves the draft saved, says so, and the next press carries on from it. After a create whose
// answer was lost the form looks for the member's drafts before it lets the person send again (save-guard.tsx).

interface Props {
  /** A draft to change. */
  loan?: Loan;
  /** The member to start from (from the member's page). */
  memberId?: string;
}

export function ApplicationForm({ loan: loaded, memberId }: Props) {
  const { me } = useStaff();
  const api = useApplicationsApi();
  const members = useMembersApi();
  const collateralApi = useCollateralApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [base, setBase] = useState<Loan | undefined>(loaded);
  const [draft, setDraft] = useState<ApplicationDraft>(() => (loaded ? draftOf(loaded) : blankApplication(memberId)));
  const [chosen, setChosen] = useState<{ id: string; name: string; no: string } | null>(null);
  const [addingGuarantor, setAddingGuarantor] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  // The summary line shows once Send was pressed, never on a form nobody has tried to send yet.
  const [tried, setTried] = useState(false);
  const guard = useSaveGuard<LoanListItem>();
  const today = localDate();
  const editing = base !== undefined;

  const products = useQuery({ queryKey: ['lending', 'loan-products', 'active'], queryFn: () => api.products() });
  // A draft keeps the product version it was made on, and the server checks a change against that version (LoanService.update,
  // versionOf), so the form does too, even when the product has a newer version or takes no new applications.
  const made = useQuery({ queryKey: ['lending', 'loan-product', base?.product_id], queryFn: () => api.product(base?.product_id ?? ''), enabled: !!base?.product_id });
  const lockedVersion = made.data?.versions?.find((v) => v.id === base?.product_version_id);
  const product = base ? (lockedVersion ? { ...made.data, current_version: lockedVersion } : undefined) : products.data?.find((p) => p.id === draft.productId);
  const terms = termsOf(product);
  // Money is typed in the product's currency once one is chosen, the loan's on a draft, else the business's (issue #289).
  const currency = currencyOf(base?.currency ?? terms?.currency ?? tenantCurrency(me));
  const order = applicationOrder(draft);
  const form = useFormErrors(applicationProblems(draft, product, today, currency), 'af');
  const given = useQuery({ queryKey: ['members', 'one', memberId], queryFn: () => members.get(memberId ?? ''), enabled: !!memberId && !editing });
  const shown = chosen ?? (given.data && draft.memberId === given.data.id ? { id: given.data.id ?? '', name: given.data.full_name ?? '', no: given.data.member_no ?? '' } : null);
  const items = useQuery({
    queryKey: ['collateral', 'member', draft.memberId],
    queryFn: () => collateralApi.list({ memberId: draft.memberId }),
    enabled: draft.memberId !== '',
  });
  const set = <K extends keyof ApplicationDraft>(key: K, value: ApplicationDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));
  const inputMode = exponentOf(currency) ? 'decimal' : 'numeric';

  /** A draft of this member with the same amount, made in the last ten minutes. Rejects when the look itself fails. */
  const lookUp = async (): Promise<LoanListItem | null> => {
    const body = createBody(draft, currency);
    const since = Date.now() - 10 * 60_000;
    const list = await api.drafts(draft.memberId);
    return list.find((l) => l.requested_principal_minor === body.requested_principal_minor && Date.parse(l.created_at ?? '') >= since) ?? null;
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || guard.blocked) return;
    setFailure(null);
    setTried(true);
    if (!form.attempt(order)) return;
    setBusy(true);
    let current = base;
    const keep = (l: Loan) => {
      current = l;
      setBase(l);
    };
    try {
      if (!current) keep(await api.create(createBody(draft, currency)));
      else {
        const body = updateBody(current, draft);
        if (Object.keys(body).length > 0) keep(await api.update(current.id ?? '', current.version ?? 0, body));
      }
      const loan = current as Loan;
      const changed = partiesChanged(loan, draft, currency);
      if (changed.guarantors) keep(await api.setGuarantors(loan.id ?? '', (current as Loan).version ?? 0, guarantorsBody(draft, currency)));
      if (changed.pledges) keep(await api.setCollateral(loan.id ?? '', (current as Loan).version ?? 0, pledgesBody(draft, currency)));
      const sent = await api.submit(loan.id ?? '', (current as Loan).version ?? 0);
      void queryClient.invalidateQueries({ queryKey: ['lending'] });
      void navigate({ to: '/staff/lending/loans/$loanId', params: { loanId: sent.id ?? '' } });
    } catch (raw) {
      const error: FormError = asFormError(raw);
      form.refuse(error.fields, order);
      const saved = current as Loan | undefined;
      const words = Object.keys(error.fields).length > 0 && error.code !== 'version_conflict' ? 'Some entries need a look. They are marked below.' : error.message;
      setFailure(saved ? `The application is saved as draft ${saved.loan_no ?? ''}, but it was not sent for appraisal. ${words}` : Object.keys(error.fields).length > 0 ? null : error.message);
      if (!saved && unknownOutcome(error)) await guard.check(lookUp);
      // The draft may have moved on (a lost answer, someone else's change): read it again so the next press sends its version.
      else if (saved && (unknownOutcome(error) || error.code === 'version_conflict')) {
        const fresh = await api.get(saved.id ?? '').catch(() => undefined);
        if (fresh && fresh.status !== 'draft') {
          // The lost answer was a submit that went through: the application is no longer a draft, so open it.
          void queryClient.invalidateQueries({ queryKey: ['lending'] });
          void navigate({ to: '/staff/lending/loans/$loanId', params: { loanId: fresh.id ?? '' } });
        } else if (fresh) setBase(fresh);
      }
    } finally {
      setBusy(false);
    }
  }

  const eligible = (items.data?.items ?? []).filter((c) => pledgeable(c, currency));
  const others = (items.data?.items ?? []).length - eligible.length;
  const togglePledge = (id: string, on: boolean) => {
    form.edited('collateral');
    const item = eligible.find((c) => c.id === id);
    if (!item) return;
    setDraft((d) => ({
      ...d,
      pledges: on
        ? [...d.pledges, { collateralId: id, label: `${typeWords(item.collateral_type)}: ${item.description ?? ''}`, value: item.collateral_value_minor ?? 0, amount: amountText(item.collateral_value_minor ?? 0, currency) }]
        : d.pledges.filter((p) => p.collateralId !== id),
    }));
  };
  const setPledgeAmount = (i: number, amount: string) => setDraft((d) => ({ ...d, pledges: d.pledges.map((x, j) => (j === i ? { ...x, amount } : x)) }));
  const setGuarantor = (i: number, patch: Partial<GuarantorDraft>) => setDraft((d) => ({ ...d, guarantors: d.guarantors.map((g, j) => (j === i ? { ...g, ...patch } : g)) }));

  return (
    <form className="mf" onSubmit={(e) => void submit(e)} noValidate aria-label={editing ? 'Change the application' : 'New loan application'} data-tour="application-form">
      <FieldsNeededNote />
      {tried && form.count(order) > 0 && <p role="alert" className="alert alert-danger">Some entries need a look. They are marked below.</p>}
      <SaveGuardNotice guard={guard} what="application" found={(l) => <Link to="/staff/lending/loans/$loanId" params={{ loanId: l.id ?? '' }}>Open draft {l.loan_no}</Link>} />
      <Group title="The loan" tourId="application-loan">
        {editing ? (
          <p className="mf-field">
            <span className="ph-label">Member and product</span>
            <br />
            Member {base?.member_no}, product {base?.product_code}
          </p>
        ) : (
          <>
            <MemberPicker id={form.idOf('member_id')} required chosen={shown} error={form.error('member_id')} errorId={`${form.idOf('member_id')}-error`}
              onChoose={(m) => { setChosen(m); form.edited('member_id'); setDraft((d) => ({ ...d, memberId: m?.id ?? '', pledges: [] })); }} />
            <SelectField name="product_id" label="Loan product" form={form} required value={draft.productId} onChange={(v) => setDraft((d) => ({ ...d, productId: v, pledges: [] }))}
              blankLabel={products.isPending ? 'Loading products' : 'Choose a product'} options={(products.data ?? []).map((p) => ({ value: p.id ?? '', label: `${p.name ?? ''} (${p.code ?? ''})` }))}
              empty={products.data || products.isError ? { text: 'No loan products yet.', ask: 'Ask the owner to add a loan product first.', load: products } : undefined} />
          </>
        )}
        {terms && (
          <dl className="ln-facts" aria-label="What the product allows">
            <dt>Interest</dt><dd>{rateWords(terms.interest_rate_bp, terms.rate_unit, terms.interest_method)}</dd>
            <dt>Amount</dt><dd>{money(terms.min_principal_minor, currency)} to {money(terms.max_principal_minor, currency)}</dd>
            <dt>Time</dt><dd>{termWords(terms.min_term_count, terms.term_unit)} to {termWords(terms.max_term_count, terms.term_unit)}</dd>
            {terms.requires_guarantor && <><dt>Guarantor</dt><dd>Needed</dd></>}
            {terms.requires_collateral && <><dt>Collateral</dt><dd>Needed</dd></>}
          </dl>
        )}
        <TextField name="requested_principal_minor" label={`Amount asked for (${currency})`} form={form} required inputMode={inputMode} value={draft.amount} onChange={(v) => set('amount', v)} />
        <TextField name="requested_term_count" label={`How long, in ${termWords(2, terms?.term_unit ?? base?.term_unit ?? 'month').replace(/^2 /, '')}`} form={form} inputMode="numeric" value={draft.term} onChange={(v) => set('term', v)}
          hint={terms?.default_term_count ? `Leave empty for the product's usual ${termWords(terms.default_term_count, terms.term_unit)}.` : undefined} />
        <SelectField name="purpose_category" label="What the loan is for" form={form} required value={draft.purpose} onChange={(v) => set('purpose', v)} options={PURPOSES} blankLabel="Choose" />
        <TextField name="purpose_text" label="More about what it is for" form={form} type="textarea" rows={2} maxLength={300} value={draft.purposeText} onChange={(v) => set('purposeText', v)} />
        <TextField name="proposed_disbursement_date" label="When the member wants the money" form={form} type="date" value={draft.date} onChange={(v) => set('date', v)}
          hint="Leave empty for the day it is sent for appraisal. The schedule starts from this day." />
      </Group>

      <Group title="Guarantors" tourId="application-guarantors">
        {draft.guarantors.length === 0 && <p className="empty-state">No guarantor yet.</p>}
        <ul className="ln-list">
          {draft.guarantors.map((g, i) => (
            <li key={g.memberId} className="rt-card stack">
              <p className="ln-item-head">
                <strong>{g.name}</strong>
                <button type="button" className="btn-sm btn-ghost" onClick={() => setDraft((d) => ({ ...d, guarantors: d.guarantors.filter((_, j) => j !== i) }))} aria-label={`Remove ${g.name}`}>Remove</button>
              </p>
              <FieldError form={form} name={`guarantors[${i}].member_id`} />
              <TextField name={`guarantors[${i}].guaranteed_amount_minor`} label={`Amount they guarantee (${currency})`} form={form} required inputMode={inputMode} value={g.amount} onChange={(v) => setGuarantor(i, { amount: v })} />
              <TextField name={`guarantors[${i}].relationship`} label="How they know the member" form={form} maxLength={60} value={g.relationship} onChange={(v) => setGuarantor(i, { relationship: v })} />
            </li>
          ))}
        </ul>
        {addingGuarantor ? (
          <MemberPicker id="af-guarantor-find" label="Find the guarantor (number, name, phone or national ID)" chosenLabel="Guarantor" chosen={null}
            onChoose={(m) => {
              if (m) setDraft((d) => (d.guarantors.some((g) => g.memberId === m.id) ? d : { ...d, guarantors: [...d.guarantors, { memberId: m.id, name: m.name, no: m.no, amount: draft.amount, relationship: '' }] }));
              form.edited('guarantors');
              setAddingGuarantor(false);
            }} />
        ) : (
          <button type="button" id={form.idOf('guarantors')} className="btn-block" aria-describedby={form.error('guarantors') ? `${form.idOf('guarantors')}-error` : undefined} onClick={() => setAddingGuarantor(true)}>
            Add a guarantor
          </button>
        )}
        <FieldError form={form} name="guarantors" />
      </Group>

      <Group title="Collateral" tourId="application-collateral">
        {draft.memberId === '' && <p className="empty-state">Choose the member first. Their registered items show here.</p>}
        {items.isPending && draft.memberId !== '' && <p className="loading">Loading the member's collateral</p>}
        <Problem error={items.error} />
        {items.data && eligible.length === 0 && draft.pledges.length === 0 && <p className="empty-state">The member has no item that can secure this loan.</p>}
        <div id={form.idOf('collateral')} tabIndex={-1} className="stack">
          {eligible.map((c) => {
            const i = draft.pledges.findIndex((p) => p.collateralId === c.id);
            const pledge = draft.pledges[i];
            return (
              <div key={c.id} className="stack">
                <label className="mf-check">
                  <input type="checkbox" checked={pledge !== undefined} onChange={(e) => togglePledge(c.id ?? '', e.target.checked)} />
                  <span>{typeWords(c.collateral_type)}: {c.description}, worth <span className="num">{money(c.collateral_value_minor, c.currency)}</span></span>
                </label>
                {pledge && <PledgeAmount form={form} index={i} currency={currency} inputMode={inputMode} amount={pledge.amount} onChange={(v) => setPledgeAmount(i, v)} />}
              </div>
            );
          })}
        </div>
        {/* A pledge on a draft whose item can no longer secure it (given back, its value removed): shown so it can be taken off. */}
        <ul className="ln-list">
          {draft.pledges.map((p, i) => (eligible.some((c) => c.id === p.collateralId) ? null : (
            <li key={p.collateralId} className="rt-card stack">
              <p className="ln-item-head">
                <strong>{p.label}</strong>
                <button type="button" className="btn-sm btn-ghost" onClick={() => setDraft((d) => ({ ...d, pledges: d.pledges.filter((_, j) => j !== i) }))} aria-label={`Remove ${p.label}`}>Remove</button>
              </p>
              <PledgeAmount form={form} index={i} currency={currency} inputMode={inputMode} amount={p.amount} onChange={(v) => setPledgeAmount(i, v)} hint={`Worth ${money(p.value, currency)}.`} />
            </li>
          )))}
        </ul>
        <FieldError form={form} name="collateral" />
        {others > 0 && <p className="field-hint">{others === 1 ? 'One item is' : `${others} items are`} not shown: given back, without a value, or in another currency.</p>}
        {draft.memberId !== '' && mayManageCollateral(me) && (
          <p><Link to="/staff/lending/collateral/new" search={{ memberId: draft.memberId }}>Register collateral for this member</Link></p>
        )}
      </Group>
      <Problem error={failure ? new Error(failure) : null} />
      <SaveBar label="Send for appraisal" busy={busy} blocked={guard.blocked ? 'Wait for the check above before sending again.' : undefined} tourId="application-send"
        cancel={editing ? <Link to="/staff/lending/loans/$loanId" params={{ loanId: base?.id ?? '' }} className="btn btn-ghost">Cancel</Link> : <Link to="/staff/lending" className="btn btn-ghost">Cancel</Link>} />
    </form>
  );
}

function PledgeAmount({ form, index, currency, inputMode, amount, onChange, hint }: {
  form: FormErrors; index: number; currency: string; inputMode: 'decimal' | 'numeric'; amount: string; onChange: (v: string) => void; hint?: string;
}) {
  return (
    <TextField name={`collateral[${index}].pledged_value_minor`} label={`Amount of the loan it secures (${currency})`} form={form} required inputMode={inputMode} value={amount} onChange={onChange} hint={hint} />
  );
}

/** A message for a key that has no input of its own (a whole list, or the guarantor chosen). */
function FieldError({ form, name }: { form: FormErrors; name: string }) {
  const error = form.error(name);
  return error ? <p id={`${form.idOf(name)}-error`} className="field-error" role="alert">{error}</p> : null;
}

const newRoute = getRouteApi('/staff/lending/applications/new');

function NewApplication() {
  const { me } = useStaff();
  const { memberId } = newRoute.useSearch();
  return (
    <LendingGate title="New loan application">
      {mayApply(me) ? <ApplicationForm memberId={memberId} /> : <NoAccess what="start a loan application" />}
    </LendingGate>
  );
}

const editRoute = getRouteApi('/staff/lending/loans/$loanId/edit');

function EditApplication() {
  const { loanId } = editRoute.useParams();
  return <EditApplicationBody loanId={loanId} />;
}

/** The page body for a loan, with its gate: the step must be offered to this session for this loan (applicationSteps). */
export function EditApplicationBody({ loanId }: { loanId: string }) {
  const { me } = useStaff();
  const api = useApplicationsApi();
  const loan = useQuery({ queryKey: ['lending', 'loan', loanId], queryFn: () => api.get(loanId) });
  const l = loan.data;
  return (
    <LendingGate title="Change the application">
      {loan.isPending && <p className="loading">Loading the application</p>}
      <Problem error={loan.error} />
      {l && (applicationSteps(me, l).includes('edit') ? <ApplicationForm loan={l} /> : <NoAccess what="change this application" />)}
    </LendingGate>
  );
}

export const NewRoute = createLazyRoute('/staff/lending/applications/new')({ component: NewApplication });
export const EditRoute = createLazyRoute('/staff/lending/loans/$loanId/edit')({ component: EditApplication });
