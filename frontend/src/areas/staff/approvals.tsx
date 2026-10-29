import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessZone } from '../../api/retail';
import { type Approval, api, listPendingApprovals, problemOf } from '../../api/client';
import { branchFilter } from '../../auth/branch';
import { formatMinor } from '../../components/money';
import { EmptyState, SkeletonList } from '../../components/states';
import { useStaff } from './context';
import { BusyButton } from '../../app/ui/BusyButton';
import { useCollateralApi } from '../../api/lending-context';
import { custodyWords, typeWords } from './lending/collateral-state';
import { money as lendingMoney } from './lending/loan-state';
import { Hint } from '../../app/ui/Hint';

// The approvals inbox (FR-APR-05, FR-APR-06): pending requests the user may decide or made, in
// the active branch. Approve executes the action at once; reject needs a note. The maker sees
// their own requests and may cancel them, but never decide them (FR-APR-02).

/** Each action in words, as the checker decides it (issue #343): the action type stays only for one this screen does not know. */
export const ACTION_WORDS: Record<string, string> = {
  retail_sale_void: 'Void a sale',
  retail_stock_correction: 'Correct a stock quantity',
  retail_expense: 'Record an expense',
  retail_sale_return: 'Take goods back from a customer',
  retail_sale_return_void: 'Void a customer return',
  retail_sale_write_off: 'Write off what a buyer owes',
  retail_purchase_order: 'Send a purchase order',
  loan_disbursement: 'Pay out a loan',
  loan_write_off: 'Write off a loan',
  loan_bring_in: 'Bring in a loan from the paper register',
  loan_bring_in_reversal: 'Undo a loan brought in',
  repayment_reversal: 'Reverse a repayment',
  charge_waiver: 'Waive a penalty, fee or interest',
  savings_withdrawal: 'Pay out savings',
  savings_reversal: 'Reverse a savings entry',
  investment_funding: 'Take in an investment',
  investment_early_withdrawal: 'Pay out an investment early',
  investment_reversal: 'Reverse an investment entry',
};

/**
 * What a request is, in words a checker can decide on. A collateral release names the item, what it is worth and where it is now, read
 * from the item itself (the list carries no payload, so who collects it is not shown here); other types keep their identifier until their screen gives them words.
 */
export function What({ item }: { item: Approval }) {
  const api = useCollateralApi();
  const release = item.action_type === 'collateral_release';
  const detail = useQuery({ queryKey: ['collateral', 'one', item.subject_id], queryFn: () => api.get(item.subject_id ?? ''), enabled: release && !!item.subject_id, retry: false });
  if (!release) return <>{ACTION_WORDS[item.action_type ?? ''] ?? item.action_type}</>;
  const c = detail.data?.item;
  return (
    <>
      <strong>Give collateral back</strong>
      {c ? (
        <span className="hint">
          {' '}{typeWords(c.collateral_type)}: {c.description}. Worth about {lendingMoney(c.collateral_value_minor ?? c.estimated_value_minor, c.currency)}. Now: {custodyWords(c.custody_status)}.
        </span>
      ) : detail.isError ? <span className="hint"> The item could not be loaded here. Open the collateral register to see it before you decide.</span> : null}
    </>
  );
}

function Row({ item, id }: { item: Approval; id: string }) {
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const decide = useMutation({
    mutationFn: async (kind: 'approve' | 'reject' | 'cancel') => {
      const path = { params: { path: { approval_id: id } } };
      let error: unknown;
      if (kind === 'approve') {
        ({ error } = await api.POST('/api/v1/approvals/{approval_id}/approve', {
          ...path,
          body: { note: note || undefined },
        }));
      } else if (kind === 'reject') {
        ({ error } = await api.POST('/api/v1/approvals/{approval_id}/reject', { ...path, body: { note } }));
      } else {
        ({ error } = await api.POST('/api/v1/approvals/{approval_id}/cancel', path));
      }
      if (error) throw error;
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['approvals'] }),
    onError: (error) => setMessage(problemOf(error).detail ?? 'The decision failed.'),
  });

  return (
    <tr>
      <td data-label="Action">
        <What item={item} />
        {['loan_bring_in', 'loan_bring_in_reversal', 'charge_waiver'].includes(item.action_type ?? '') && item.subject_id && (
          <>
            {' '}
            <Link to="/staff/lending/loans/$loanId" params={{ loanId: item.subject_id }}>Open the loan</Link>
          </>
        )}
        {item.action_type === 'loan_bring_in' && (
          <Hint full>
            Approving puts this loan from the paper register in the books. No arrears SMS goes to the borrower in the 7 days after it comes in, so the business can speak to them first.
          </Hint>
        )}
        {item.action_type === 'collateral_release' && item.subject_id && (
          <>
            {' '}
            <Link to="/staff/lending/collateral/$collateralId" params={{ collateralId: item.subject_id }}>Open the item</Link>
          </>
        )}
      </td>
      <td data-label="Amount" className="num">{item.amount_minor != null && item.currency ? formatMinor(item.amount_minor, item.currency) : ''}</td>
      <td data-label="Requested by">{item.requested_by_name ?? item.requested_by}</td>
      <td data-label="Requested at">{item.requested_at ? new Date(item.requested_at).toLocaleString(undefined, { timeZone: businessZone() }) : ''}</td>
      <td data-label="Decision" className="cell-stack">
        <div className="decision">
          {item.can_decide && (
            <>
              <input placeholder="Note (required to reject)" aria-label="Note" value={note} onChange={(e) => setNote(e.target.value)} />{' '}
              <BusyButton className="btn-primary" busy={decide.isPending && decide.variables === 'approve'} disabled={decide.isPending} onClick={() => decide.mutate('approve')}>
                Approve
              </BusyButton>{' '}
              <BusyButton className="btn-danger" busy={decide.isPending && decide.variables === 'reject'} disabled={decide.isPending || note.trim() === ''} onClick={() => decide.mutate('reject')}>
                Reject
              </BusyButton>
            </>
          )}
          {item.can_cancel && (
            <BusyButton busy={decide.isPending && decide.variables === 'cancel'} disabled={decide.isPending} onClick={() => decide.mutate('cancel')}>
              Cancel my request
            </BusyButton>
          )}
          {item.execution_error && <p role="alert" className="alert alert-danger">Last attempt failed: {item.execution_error}</p>}
          {message && <p role="alert" className="alert alert-danger">{message}</p>}
        </div>
      </td>
    </tr>
  );
}

export function Approvals() {
  const { branch } = useStaff();
  const pending = useQuery({
    queryKey: ['approvals', branch],
    queryFn: () => listPendingApprovals(branchFilter(branch)),
    enabled: branch !== null,
  });
  const items = pending.data?.items ?? [];

  return (
    <main>
      <h1>Approvals</h1>
      <div data-tour="approvals-list">
      {pending.isPending && <SkeletonList label="Loading approvals" />}
      {pending.isError && <p role="alert" className="alert alert-danger">Could not load approvals.</p>}
      {pending.data && items.length === 0 && <EmptyState title="Nothing waiting.">Requests for your approval appear here.</EmptyState>}
      {items.length > 0 && (
        <div className="table-wrap table-cards" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th>Action</th>
                <th className="num">Amount</th>
                <th data-tour="approvals-who">Requested by</th>
                <th>Requested at</th>
                <th data-tour="approvals-decision">Decision</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => item.id && <Row key={item.id} item={item} id={item.id} />)}
            </tbody>
          </table>
        </div>
      )}
      </div>
    </main>
  );
}

export const Route = createLazyRoute('/staff/approvals')({ component: Approvals });
