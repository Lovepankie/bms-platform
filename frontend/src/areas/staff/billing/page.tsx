import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { billingView, downloadReceipt } from '../../../api/billing';
import { formatMinor } from '../../../components/money';
import { formatDay } from '../../platform/operator-state';
import { BillingContactCard } from './contact';
import { METHOD_LABELS, priceSentence, STATE_LABELS, stateBadge, stateSentence } from './billing-state';

// Business set-up > Plan and billing (issue #480, ADR-075, FR-SUB-12): the plan, its price, the subscription's state and dates, how
// to pay and every payment with its receipt. Read only for the Owner and the administrators: payments are recorded by Rincol Tech.
// The Owner alone names who gets the bills (issue #571, ADR-090).

export function BillingScreen() {
  const view = useQuery({ queryKey: ['billing'], queryFn: billingView });
  const [problem, setProblem] = useState<string | null>(null);

  if (view.isError) return <p role="alert">Could not load the plan and billing.</p>;
  const v = view.data;
  if (!v) return <p className="loading">Loading</p>;
  const price = priceSentence(v);
  const state = v.state ?? '';
  const payments = v.payments ?? [];

  async function receipt(id: string, no: string) {
    setProblem(null);
    try {
      await downloadReceipt(id, no);
    } catch (e) {
      setProblem(e instanceof Error ? e.message : 'Could not download the receipt. Try again.');
    }
  }

  return (
    <main className="rt ln">
      <h1>Plan and billing</h1>
      <section className="rt-card stack" aria-labelledby="billing-plan" data-tour="billing-plan">
        <h2 id="billing-plan">Your plan</h2>
        <p>
          <strong>{v.plan_name}</strong> <span className={stateBadge(state)}>{STATE_LABELS[state] ?? state}</span>
        </p>
        <p>{price ?? 'The price of your plan is not set yet; Rincol Tech will tell you.'}</p>
        <p role={state === 'in_grace' || state === 'overdue' ? 'alert' : undefined}>{stateSentence(v)}</p>
        {v.paid_until && state !== 'active' && <p className="hint">Last day paid for: {formatDay(v.paid_until)}.</p>}
      </section>
      <section className="rt-card stack" aria-labelledby="billing-how" data-tour="billing-how">
        <h2 id="billing-how">How to pay</h2>
        <p style={{ whiteSpace: 'pre-line' }}>{v.how_to_pay ?? 'Contact Rincol Tech and we will tell you how to pay.'}</p>
        <p className="hint">
          When a payment is recorded you get a receipt by email. A payment late by more than {v.grace_days} days after the last day paid
          for may lead to the business being suspended (read only); nothing is ever deleted.
        </p>
      </section>
      <BillingContactCard view={v} />
      <section className="rt-card stack" aria-labelledby="billing-payments" data-tour="billing-payments">
        <h2 id="billing-payments">Payments</h2>
        {problem && (
          <p role="alert" className="alert alert-danger">
            {problem}
          </p>
        )}
        {payments.length === 0 ? (
          <p>No payment recorded yet.</p>
        ) : (
          <ul className="ln-list" aria-label="Payments">
            {payments.map((p) => (
              <li key={p.id} className="ln-item">
                <p className="ln-item-head">
                  <strong>{formatMinor(p.amount_minor ?? 0, p.currency ?? 'UGX')}</strong> on {formatDay(p.paid_on)}
                </p>
                <p>
                  {METHOD_LABELS[p.method ?? ''] ?? p.method}, {p.months === 1 ? '1 month' : `${p.months} months`}: {formatDay(p.period_start)} to{' '}
                  {formatDay(p.period_end)}
                </p>
                {p.receipt_no && (
                  <p>
                    <button type="button" className="btn-ghost btn-sm" onClick={() => void receipt(p.id ?? '', p.receipt_no ?? '')}>
                      Receipt {p.receipt_no}
                    </button>
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup/billing')({ component: BillingScreen });
