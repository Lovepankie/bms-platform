import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type InFlightReport, type InFlightTransfer } from '../../../api/retail';
import { EmptyState, SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { newIdempotencyKey } from './idempotency';
import { showQty } from './maths';
import { branchesWhere, TRANSFER } from './permissions';
import { buildReceive, everythingArrived, receiveLineHint, receiveLineInvalid, receiveProblem, waiting, type Arrived } from './receive-state';
import { useBranchLabel } from './transfer';
import { showDate } from './transfers';
import { Gate, Problem, money, useProfitAccess } from './ui';
import { Hint } from '../../../app/ui/Hint';

// Stock on its way (ADR-033, capability transfer_receipt): the transfers one branch has sent and the
// other has not yet received, the longest wait first. The destination checks what arrived and marks
// it received (less than was sent is allowed: the difference is written off as a loss); the source
// can take it back before that. Until then the stock is in neither branch's count. Cost appears only
// with retail.profit.read, and the server sends none to anyone else.

function Items({ transfer }: { transfer: InFlightTransfer }) {
  return (
    <ul>
      {(transfer.lines ?? []).map((l) => (
        <li key={l.line_no}>{l.description} ({l.code}): {showQty(l.qty ?? '0')}</li>
      ))}
    </ul>
  );
}

/** What arrived, item by item, starting from everything that was sent. */
function ReceiveForm({ transfer }: { transfer: InFlightTransfer }) {
  const queryClient = useQueryClient();
  const lines = transfer.lines ?? [];
  const [arrived, setArrived] = useState<Arrived>(() => everythingArrived(lines));
  const problem = receiveProblem(lines, arrived);
  const idempotencyKey = useRef(newIdempotencyKey());
  const save = useMutation({
    mutationFn: () => retail.receiveTransfer(transfer.id ?? '', buildReceive(lines, arrived), idempotencyKey.current),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail'] }),
  });
  const id = transfer.id ?? '';
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!problem && !save.isPending) save.mutate(); }}>
      <h3>Check what arrived</h3>
      {lines.map((l) => {
        const key = l.product_id ?? '';
        const typed = arrived[key] ?? '';
        const hint = receiveLineHint(l.qty ?? '0', typed);
        return (
          <div key={key}>
            <label htmlFor={`recv-${id}-${key}`}>{l.description} (sent {showQty(l.qty ?? '0')})</label>
            <input id={`recv-${id}-${key}`} inputMode="decimal" value={typed} aria-invalid={receiveLineInvalid(l.qty ?? '0', typed)}
              onChange={(e) => setArrived((a) => ({ ...a, [key]: e.target.value }))} />
            {hint && <p role={receiveLineInvalid(l.qty ?? '0', typed) ? 'alert' : undefined} className="rt-flag">{hint}</p>}
          </div>
        );
      })}
      {problem && <p>{problem}</p>}
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="submit" className="rt-primary" disabled={problem !== null}>
        Mark as received
      </BusyButton>
    </form>
  );
}

/** The source takes the stock back before it is received: it returns to the source at the same cost. */
function TakeBackForm({ transfer }: { transfer: InFlightTransfer }) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const idempotencyKey = useRef(newIdempotencyKey());
  const take = useMutation({
    mutationFn: () => retail.cancelTransfer(transfer.id ?? '', reason.trim(), idempotencyKey.current),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail'] }),
  });
  const id = transfer.id ?? '';
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (reason.trim() !== '' && !take.isPending) take.mutate(); }}>
      <label htmlFor={`takeback-${id}`}>Reason to take this back</label>
      <input id={`takeback-${id}`} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problem error={take.error} />
      <BusyButton busy={take.isPending} type="submit" disabled={reason.trim() === ''}>
        Take this back
      </BusyButton>
    </form>
  );
}

export function InTransitCard({ transfer, mayReceive, mayTakeBack }: { transfer: InFlightTransfer; mayReceive: boolean; mayTakeBack: boolean }) {
  const label = useBranchLabel();
  const profit = useProfitAccess();
  return (
    <li className="rt-card">
      <strong style={{ display: 'block' }}>From {label(transfer.from_branch_id)} to {label(transfer.to_branch_id)}</strong>
      <span className="muted" style={{ display: 'block' }}>{showDate(transfer.transfer_date)}, {waiting(transfer.age_days)}</span>
      <Items transfer={transfer} />
      {transfer.note && <p>Note: {transfer.note}</p>}
      {profit && transfer.cost_total_minor !== undefined && <p>Value at cost: {money(transfer.cost_total_minor)}</p>}
      {mayReceive && <ReceiveForm transfer={transfer} />}
      {mayTakeBack && <TakeBackForm transfer={transfer} />}
    </li>
  );
}

/** The books agree with the list, or they do not: said only to a caller who reads every branch with cost. */
export function Reconciled({ report }: { report: InFlightReport }) {
  if (report.reconciled === undefined) return null;
  return report.reconciled
    ? <p className="hint">The books agree with this list.</p>
    : <p role="alert" className="alert alert-danger">The books do not agree with this list. Ask your accountant to check the inter-branch clearing account.</p>;
}

export function InTransitList({ report }: { report: InFlightReport }) {
  const { me } = useStaff();
  const profit = useProfitAccess();
  const mayAct = branchesWhere(me, TRANSFER).map((b) => b.id);
  const items = report.items ?? [];
  if (items.length === 0) return <EmptyState title="Nothing is on the way.">Stock you send to another branch shows here until it is received.</EmptyState>;
  return (
    <>
      {profit && report.in_transit_value_minor !== undefined && <p>On the way, at cost: <strong>{money(report.in_transit_value_minor)}</strong></p>}
      <Reconciled report={report} />
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {items.map((t) => (
          <InTransitCard key={t.id} transfer={t} mayReceive={mayAct.includes(t.to_branch_id)} mayTakeBack={mayAct.includes(t.from_branch_id)} />
        ))}
      </ul>
    </>
  );
}

export function InTransitPage() {
  const report = useQuery({ queryKey: ['retail', 'in-flight'], queryFn: () => retail.transfersInFlight() });
  return (
    <Gate screen="inTransit" title="On the way">
      <Hint tour="transit-help">
        Stock that one branch has sent and the other has not yet received. Until it is received it is in neither branch's count.
      </Hint>
      <div data-tour="transit-list">
        {report.isPending && <SkeletonList label="Loading stock on the way" />}
        <Problem error={report.error} />
        {report.data && <InTransitList report={report.data} />}
      </div>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/in-transit')({ component: InTransitPage });
