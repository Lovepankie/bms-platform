import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { lending, type Loan, type LoanScheduleRow } from '../../../api/lending';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { KeyFigure, KeyFigureRow } from '../../../app/ui/KeyFigure';
import { showDate } from '../../../app/ui/PeriodPicker';
import { Segmented } from '../../../app/ui/Segmented';
import { Sheet } from '../../../app/ui/Sheet';
import { useStaff } from '../context';
import { usePersistedDraft } from '../retail/idempotency';
import { Problem } from '../retail/ui';
import { currencyOf, money } from './loan-state';
import { waiveStep, type WaiveComponent, type WaiveDraft } from './waive-state';
import { mayApproveFeeInterest } from './permissions';

// Waive fee or interest on one schedule item (FR-ARR-04, issue #265). The person picks fees or interest, types how much (at
// most what is still due on it) and why, then reviews who asks, for whom, how much and why before anything is sent. The
// server weighs it against the business's approval amount and the accountant's permission: it either takes effect at once or
// waits on Approvals for someone with that permission. The draft and its Idempotency-Key stay in this tab until the server
// answers, so a retry sends it once.

const WHAT: Record<WaiveComponent, string> = { interest: 'Interest', fee: 'Fees' };

export function WaiveItemSheet({ loan, row, memberName, onClose }: {
  loan: Loan;
  row: LoanScheduleRow;
  memberName?: string;
  onClose: () => void;
}) {
  const { me } = useStaff();
  const loanId = loan.id ?? '';
  const c = loan.currency;
  const queryClient = useQueryClient();
  const form = useRef<HTMLDivElement>(null);
  const [reviewing, setReviewing] = useState(false);
  const [pressed, setPressed] = useState(false);
  const { key, draft, setDraft, finish } = usePersistedDraft<WaiveDraft>(`lending-waive:${me.user_id ?? ''}:${loanId}:${row.no ?? ''}`, {
    component: (row.interest_outstanding_minor ?? 0) > 0 ? 'interest' : 'fee',
    amount: '',
    reason: '',
  });
  const step = waiveStep(draft, row, c);
  // toast-exempt: success screen, the Waived screen replaces the form in the sheet
  const save = useMutation({
    mutationFn: (sent: { component: WaiveComponent; amount_minor: number; reason: string }) => lending.waiveItem(loanId, row.no ?? 0, sent, key),
    onSuccess: () => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['lending', 'loan', loanId] });
    },
  });
  const borrower = memberName ? `${memberName} (${loan.member_no ?? ''})` : `Member ${loan.member_no ?? ''}`;
  const title = `Waive fee or interest on item ${row.no ?? ''}`;

  if (save.data && save.variables) {
    const sent = save.variables;
    return (
      <Sheet open onClose={onClose} title={title} actions={<button type="button" className="rt-primary" onClick={onClose}>Done</button>}>
        {save.data.executed ? (
          <p role="status" className="alert alert-success">
            Waived {money(sent.amount_minor, c)} of the {WHAT[sent.component].toLowerCase()} on item {row.no}.
          </p>
        ) : (
          <p role="status" className="alert alert-info">
            Sent for approval. Nothing changes until someone with the accountant's permission approves it. You can see it, or cancel
            it, on Approvals.
          </p>
        )}
      </Sheet>
    );
  }

  if (reviewing && step.ready) {
    return (
      <Sheet
        open
        onClose={onClose}
        title="Check the waiver"
        actions={
          <>
            <BusyButton key="send" busy={save.isPending} type="button" className="rt-primary" onClick={() => save.mutate({ component: draft.component, amount_minor: step.amountMinor ?? 0, reason: draft.reason.trim() })}>
              Send waiver of {money(step.amountMinor ?? 0, c)}
            </BusyButton>
            <button type="button" onClick={() => setReviewing(false)}>
              Change it
            </button>
          </>
        }
      >
        <KeyFigure label={`${WHAT[draft.component]} to waive`} value={money(step.amountMinor ?? 0, c)}>
          <KeyFigureRow label="Still due now" value={money(step.dueMinor, c)} />
          <KeyFigureRow label="Still due after" value={money(step.dueMinor - (step.amountMinor ?? 0), c)} />
        </KeyFigure>
        <dl className="ln-facts ln-review" aria-label="The waiver">
          <dt>Borrower</dt>
          <dd>{borrower}</dd>
          <dt>Loan</dt>
          <dd>{loan.loan_no}</dd>
          <dt>Item</dt>
          <dd>
            {row.no}, due {showDate(row.due_date)}
          </dd>
          <dt>Asked by</dt>
          <dd>{me.full_name ?? 'You'}</dd>
          <dt>Why</dt>
          <dd className="ln-reason">{draft.reason.trim()}</dd>
        </dl>
        <p className="alert alert-info">
          {mayApproveFeeInterest(me, loan.branch_id)
            ? 'Below the business\'s approval amount it is waived at once. Above it, another person with the accountant\'s permission approves it first.'
            : 'Someone with the accountant\'s permission approves it before anything changes. The amount is never income.'}
        </p>
        <Problem error={save.error} />
      </Sheet>
    );
  }

  // The button stays enabled (docs/ui-rules.md rule 6): a press with a field not yet right shows the errors and goes to the first.
  const next = () => {
    setPressed(true);
    if (step.ready) setReviewing(true);
    else setTimeout(() => form.current && focusFirstMissing(form.current));
  };

  return (
    <Sheet
      open
      onClose={onClose}
      title={title}
      actions={
        <BusyButton key="check" type="button" busy={false} className="rt-primary" onClick={next}>
          Check the waiver
        </BusyButton>
      }
    >
      <div ref={form} className="ln-waive">
        <FieldsNeededNote />
        <p className="ln-muted">
          {borrower}, loan {loan.loan_no}, item {row.no} due {showDate(row.due_date)}. Principal is never waived: a loan that will
          not be repaid is written off.
        </p>
        <Segmented<WaiveComponent>
          label="What to waive"
          value={draft.component}
          onChange={(component) => setDraft((d) => ({ ...d, component }))}
          options={[
            { value: 'interest', label: `Interest ${money(row.interest_outstanding_minor ?? 0, c)}` },
            { value: 'fee', label: `Fees ${money(row.fees_outstanding_minor ?? 0, c)}` },
          ]}
        />
        <Field
          label={`Amount to waive (${currencyOf(c ?? me.tenant_currency)})`}
          required
          inputMode={step.decimals ? 'decimal' : 'numeric'}
          autoComplete="off"
          value={draft.amount}
          onChange={(e) => setDraft((d) => ({ ...d, amount: e.target.value }))}
          error={step.amountProblem}
          showError={pressed}
          hint={`At most ${money(step.dueMinor, c)}, what is still due.`}
        />
        <Field
          label="Why"
          required
          value={draft.reason}
          error={step.reasonProblem}
          showError={pressed}
          hint="The checker and the loan's history show this."
          control={(props) => (
            <textarea {...props} rows={3} maxLength={500} value={draft.reason} onChange={(e) => setDraft((d) => ({ ...d, reason: e.target.value }))} />
          )}
        />
      </div>
    </Sheet>
  );
}
