import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type ReturnOutcome, type Sale, type SaleLine } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { qtyString, showQty } from './maths';
import { usePersistedDraft } from './idempotency';
import { SALE_RETURN, branchesWhere, canUse, holdsAt } from './permissions';
import {
  DISPOSITION_WORDS, MONEY_METHODS, accountRefundAllowed, REASONS, REFUND_WORDS, backOf, blankReturn, blankRow, buildReturnRequest, lineUnit, plannedLines, refundPlan, remainingMilli,
  resolved, returnProblem, rowProblem, soldMilli, type Back, type Disposition, type MoneyMethod, type RefundChoice, type ReturnForm, type RowForm,
} from './returns-state';
import { showDate } from './transfers';
import { Problem, Success, money } from './ui';

// Take goods back against a sale (returns capability, ADR-033, issue #201). Each line of the sale shows what was sold, what has
// come back already and what can still come back; the person enters a quantity, the reason and where the goods go, then how the
// credit is settled. The form shows the credit note it would make before anything is saved. The server decides every rule
// again under the sale's lock: nothing can come back beyond what was sold, the credit is the price actually charged, and an
// account refund only works for a credit sale that still owes.

/** Why "take it off what they owe" is not offered, in the server's terms. */
const accountWhy = (sale: Sale): string =>
  sale.historical === true
    ? 'This is an imported credit sale, so it cannot be taken off the balance. Pay it back instead.'
    : sale.payment_method === 'credit'
      ? 'Nothing is owed on this credit sale any more, so there is no balance to take it off.'
      : 'Taking it off what they owe is only for a credit sale that still owes.';

const qtyWords = (milli: number): string => showQty(qtyString(milli));

/** One line of the sale: what was sold and returned, and the fields for what is coming back. */
export function ReturnRow({ line, row, back, branches, onChange }: {
  line: SaleLine;
  row: RowForm;
  back: Back;
  branches: { id?: string; name?: string; code?: string }[];
  onChange: (row: RowForm) => void;
}) {
  const id = line.id ?? '';
  const left = remainingMilli(line, back);
  const problem = rowProblem(line, row, back);
  const unit = lineUnit(line);
  const planned = problem === null && row.qty.trim() !== '' ? plannedLines({ lines: [line] }, { ...blankReturn(), rows: { [id]: row } }, back)[0] : undefined;
  return (
    <li className="rt-card" data-testid={`return-row-${id}`}>
      <p><strong>{line.description}</strong></p>
      <p className="hint">
        Sold {qtyWords(soldMilli(line))}{unit ? ` (${showQty(line.sale_qty ?? '')} ${unit})` : ''}, at {money(line.line_total_minor ?? 0)} in all.
        {' '}{left <= 0 ? 'All of it has come back.' : `${qtyWords(left)} can still come back.`}
      </p>
      {left > 0 && (
        <div className="stack">
          <label htmlFor={`qty-${id}`}>Quantity to return of {line.description}</label>
          <input id={`qty-${id}`} inputMode="decimal" value={row.qty} onChange={(e) => onChange({ ...row, qty: e.target.value })} aria-invalid={problem !== null} />
          {unit && (
            <>
              <label htmlFor={`unit-${id}`}>Counted in</label>
              <select id={`unit-${id}`} value={row.entered} onChange={(e) => onChange({ ...row, entered: e.target.value as RowForm['entered'] })}>
                <option value="line">{unit}</option>
                <option value="base">single units</option>
              </select>
            </>
          )}
          {row.qty.trim() !== '' && (
            <>
              <label htmlFor={`reason-${id}`}>Why is it coming back</label>
              <select id={`reason-${id}`} value={row.reason} onChange={(e) => onChange({ ...row, reason: e.target.value })}>
                {REASONS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
              </select>
              <label htmlFor={`disposition-${id}`}>What happens to the goods</label>
              <select id={`disposition-${id}`} value={row.disposition} onChange={(e) => onChange({ ...row, disposition: e.target.value as Disposition })}>
                {(Object.keys(DISPOSITION_WORDS) as Disposition[]).map((d) => <option key={d} value={d}>{DISPOSITION_WORDS[d]}</option>)}
              </select>
              {row.disposition === 'restock' && (
                <>
                  <label htmlFor={`branch-${id}`}>Put them back at</label>
                  <select id={`branch-${id}`} value={row.restockBranchId} onChange={(e) => onChange({ ...row, restockBranchId: e.target.value })}>
                    <option value="">Choose a branch or store</option>
                    {branches.map((b) => <option key={b.id} value={b.id}>{branchLabel(b)}</option>)}
                  </select>
                </>
              )}
              {row.disposition === 'write_off' && <p className="hint">Damaged goods never go back into stock. Their cost is written off.</p>}
            </>
          )}
          {problem && <p role="alert" className="rt-flag">{problem}</p>}
          {planned && <p data-testid={`credit-${id}`}>Credit for this line: <strong>{money(planned.creditMinor)}</strong></p>}
        </div>
      )}
    </li>
  );
}

/** The whole form for one sale, controlled by the page so a draft can survive a reload. */
export function ReturnFormView({ sale, back, form: chosen, branches, busy, error, onChange, onSubmit }: {
  sale: Sale;
  back: Back;
  form: ReturnForm;
  branches: { id?: string; name?: string; code?: string }[];
  busy?: boolean;
  error?: unknown;
  onChange: (form: ReturnForm) => void;
  onSubmit: () => void;
}) {
  const form = resolved(sale, chosen);
  const planned = plannedLines(sale, form, back);
  const total = planned.reduce((sum, p) => sum + p.creditMinor, 0);
  const plan = refundPlan(sale, total, form, back);
  const problem = returnProblem(sale, chosen, back);
  const setRow = (lineId: string, row: RowForm) => onChange({ ...form, rows: { ...form.rows, [lineId]: row } });
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!problem && !busy) onSubmit(); }} data-tour="return-form">
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="return-lines">
        {(sale.lines ?? []).map((l) => (
          <ReturnRow key={l.id} line={l} row={form.rows[l.id ?? ''] ?? blankRow(sale.branch_id ?? '')} back={back} branches={branches} onChange={(row) => setRow(l.id ?? '', row)} />
        ))}
      </ul>
      <section aria-label="How it is settled" className="rt-card" data-tour="return-refund">
        <h2>How the customer is repaid</h2>
        <label htmlFor="refund-choice">Repay by</label>
        <select id="refund-choice" value={form.refund} onChange={(e) => onChange({ ...form, refund: e.target.value as RefundChoice, moneyMethod: '' })}>
          {(Object.keys(REFUND_WORDS) as RefundChoice[]).map((m) => (
            <option key={m} value={m} disabled={m === 'account' && !accountRefundAllowed(sale)}>{REFUND_WORDS[m]}</option>
          ))}
        </select>
        {!accountRefundAllowed(sale) && <p className="hint">{accountWhy(sale)}</p>}
        {plan.moneyMinor > 0 && branches.length > 1 && (
          <>
            <label htmlFor="refund-branch">Pay the money out of the till of</label>
            <select id="refund-branch" value={form.refundBranchId} onChange={(e) => onChange({ ...form, refundBranchId: e.target.value })}>
              <option value="">{sale.branch_id ? 'The usual branch' : 'Choose a branch'}</option>
              {branches.map((b) => <option key={b.id} value={b.id}>{branchLabel(b)}</option>)}
            </select>
          </>
        )}
        {plan.needsMoneyMethod && (
          <>
            <label htmlFor="money-method">The part that is not taken off the balance is paid back by</label>
            <select id="money-method" value={form.moneyMethod} onChange={(e) => onChange({ ...form, moneyMethod: e.target.value as '' | MoneyMethod })}>
              <option value="">Choose how</option>
              {MONEY_METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
            </select>
          </>
        )}
        {total > 0 && (
          <p data-testid="return-plan">
            Credit note total <strong>{money(total)}</strong>
            {plan.accountMinor > 0 ? `, ${money(plan.accountMinor)} taken off what they owe` : ''}
            {plan.moneyMinor > 0 ? `, ${money(plan.moneyMinor)} paid back` : ''}.
          </p>
        )}
        <label htmlFor="return-note">Note (optional)</label>
        <input id="return-note" value={form.note} maxLength={300} onChange={(e) => onChange({ ...form, note: e.target.value })} />
      </section>
      {problem && planned.length > 0 && <p role="alert" className="rt-flag">{problem}</p>}
      <Problem error={error} />
      <button type="submit" className="rt-primary" disabled={problem !== null || busy}>{busy ? 'Saving' : 'Record the return'}</button>
    </form>
  );
}

const route = getRouteApi('/staff/retail/returns/new/$saleId');

export function ReturnNewPage() {
  const { saleId } = route.useParams();
  const { me, branch } = useStaff();
  const queryClient = useQueryClient();
  const allowed = canUse(me, 'returns');
  const [outcome, setOutcome] = useState<ReturnOutcome | null>(null);
  const sale = useQuery({ queryKey: ['retail', 'sale', saleId], queryFn: () => retail.getSale(saleId), enabled: allowed });
  const earlier = useQuery({ queryKey: ['retail', 'sale-returns', saleId], queryFn: () => retail.listSaleReturns(saleId), enabled: allowed });
  useCrumbLabel(sale.data?.sale_no ? `Take goods back from ${sale.data.sale_no}` : undefined);
  const branches = branchesWhere(me, SALE_RETURN);
  const { key, draft, setDraft, discard } = usePersistedDraft<ReturnForm>(`sale-return:${me.user_id ?? ''}:${saleId}`, blankReturn());
  const back = backOf(earlier.data ?? []);
  const send = useMutation({
    mutationFn: (s: Sale) => retail.createSaleReturn(saleId, buildReturnRequest(s, draft, back), key),
    onSuccess: (r) => {
      discard();
      setOutcome(r);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) {
    return (
      <main className="rt">
        <h1>Take goods back</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  const s = sale.data;
  return (
    <main className="rt rt-wide">
      <h1>Take goods back</h1>
      <p><Link to="/staff/retail/returns">Back to customer returns</Link></p>
      {(sale.isPending || earlier.isPending) && <SkeletonList label="Loading the sale" />}
      <Problem error={sale.error ?? earlier.error} />
      {outcome?.executed && outcome.sale_return && (
        <Success>
          Recorded credit note {outcome.sale_return.credit_note_no} for {money(outcome.sale_return.total_minor ?? 0)}.{' '}
          <Link to="/staff/retail/returns/$returnId" params={{ returnId: outcome.sale_return.id ?? '' }}>Open the credit note</Link>
        </Success>
      )}
      {outcome && !outcome.executed && (
        <Success>This return is large enough to need a second administrator. It will be recorded when they approve it in Approvals.</Success>
      )}
      {s && s.status === 'voided' && <p className="empty-state">This sale was voided, which already took everything back.</p>}
      {s && s.status !== 'voided' && !holdsAt(me, SALE_RETURN, s.branch_id) && <p className="empty-state">You cannot take goods back for the branch of this sale.</p>}
      {s && s.status !== 'voided' && holdsAt(me, SALE_RETURN, s.branch_id) && (
        <>
          <p className="hint">Sale {s.sale_no}, {showDate(s.sale_date)}{s.buyer_name ? `, ${s.buyer_name}` : ''}, {money(s.total_minor ?? 0)}. Branch {branchLabel((me.branches ?? []).find((b) => b.id === s.branch_id)) || (branch ?? '')}.</p>
          <ReturnFormView
            sale={s}
            back={back}
            form={draft}
            branches={branches}
            busy={send.isPending}
            error={send.error}
            onChange={(f) => setDraft(f)}
            onSubmit={() => send.mutate(s)}
          />
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/retail/returns/new/$saleId')({ component: ReturnNewPage });

