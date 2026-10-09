import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retailAnalytics, type Ageing, type CreditControl } from '../../../api/retail-analytics';
import { SkeletonList } from '../../../components/states';
import { Bars, Choice, DateRange, Empty, Section, defaultRange, rangeProblem } from './analytics-ui';
import { Gate, Problem, money, useBranchName, useBranchView, useIsPhone } from './ui';

// Credit control (issue #149, step 4): what credit buyers owe, how late it is, and what was paid in a
// range. The owing and overdue tests are those of the credit sales list (a completed credit sale whose
// total is above what was paid; overdue when the due date is before today). No cost or profit appears.

const TOPS = [10, 20, 50, 100].map((n) => ({ value: n, label: `Top ${n}` }));
const SORTS = [{ value: 'amount', label: 'Biggest first' }, { value: 'age', label: 'Oldest first' }] as const;

const BUCKETS: { key: keyof Ageing; label: string }[] = [
  { key: 'not_due_minor', label: 'Not yet due' },
  { key: 'days1_to30_minor', label: '1 to 30 days late' },
  { key: 'days31_to60_minor', label: '31 to 60 days late' },
  { key: 'days61_to90_minor', label: '61 to 90 days late' },
  { key: 'over90_minor', label: 'Over 90 days late' },
];

const METHODS: Record<string, string> = { cash: 'Cash', mobile_money: 'Mobile money', bank: 'Bank' };
const methodName = (m?: string) => METHODS[m ?? ''] ?? m ?? '';

export function CreditControlView({ data, nameOf, sort }: { data: CreditControl; nameOf: (id: string | undefined) => string; sort: 'amount' | 'age' }) {
  const phone = useIsPhone();
  const totals = data.totals ?? { owed_minor: 0 };
  const buyers = data.buyers ?? [];
  const overdue = data.overdue ?? [];
  const payments = data.payments ?? [];
  return (
    <>
      <p className="rt-total">Owed by credit buyers {money(totals.owed_minor ?? 0)}</p>
      <p className="hint">{data.buyer_count ?? 0} {data.buyer_count === 1 ? 'buyer owes' : 'buyers owe'} this, as of {data.as_of}. Days are counted from the due date; a sale with no due date counts as not yet due.</p>
      <Section title="Ageing">
        <Bars label="Owed by age" rows={BUCKETS.map((b) => ({ key: b.key, name: b.label, value: totals[b.key] ?? 0, text: money(totals[b.key] ?? 0) }))} />
      </Section>

      <Section title="Who owes" hint="The buyers owing the most first.">
        {buyers.length === 0 ? <Empty>Nobody owes anything.</Empty> : (
          <>
            <div className="table-wrap" tabIndex={0}><table>
              <thead>
                <tr>
                  <th>Buyer</th>
                  <th className="num">Owes</th>
                  {!phone && BUCKETS.map((b) => <th key={b.key} className="num">{b.label}</th>)}
                  {phone && <th className="num">Oldest late</th>}
                </tr>
              </thead>
              <tbody>
                {buyers.map((b) => (
                  <tr key={b.customer_id ?? b.name}>
                    <td>{b.name}<br /><span className="hint">{b.sale_count} {b.sale_count === 1 ? 'sale' : 'sales'}</span></td>
                    <td className="num">{money(b.ageing?.owed_minor ?? 0)}</td>
                    {!phone && BUCKETS.map((x) => <td key={x.key} className="num">{b.ageing?.[x.key] ? money(b.ageing[x.key] ?? 0) : ''}</td>)}
                    {phone && <td className="num">{b.oldest_overdue_days !== undefined ? `${b.oldest_overdue_days} days` : ''}</td>}
                  </tr>
                ))}
              </tbody>
            </table></div>
            {(data.buyer_count ?? 0) > buyers.length && <p className="hint">Showing {buyers.length} of {data.buyer_count}. Raise the Top number to see more.</p>}
          </>
        )}
      </Section>

      <Section title="Overdue sales" hint={sort === 'age' ? 'Oldest first.' : 'Biggest amount first.'}>
        {overdue.length === 0 ? <Empty>Nothing is overdue.</Empty> : (
          <>
            <div className="table-wrap" tabIndex={0}><table>
              <thead><tr><th>Sale</th>{!phone && <th>Due</th>}<th className="num">Late</th><th className="num">Owes</th></tr></thead>
              <tbody>
                {overdue.map((o) => (
                  <tr key={o.sale_id}>
                    <td>{o.buyer_name}<br /><span className="hint">{o.sale_no}, {nameOf(o.branch_id)}{phone ? `, due ${o.due_date}` : ''}</span></td>
                    {!phone && <td>{o.due_date}</td>}
                    <td className="num">{o.days_overdue} days</td>
                    <td className="num">{money(o.outstanding_minor ?? 0)}</td>
                  </tr>
                ))}
              </tbody>
            </table></div>
            {(data.overdue_count ?? 0) > overdue.length && <p className="hint">Showing {overdue.length} of {data.overdue_count}. Raise the Top number to see more.</p>}
          </>
        )}
      </Section>

      <Section title="Payments received" hint={`Payments on credit sales from ${data.from} to ${data.to}.`}>
        {payments.length === 0 ? <Empty>No payment was received in this range.</Empty> : (
          <>
            <p className="rt-total">Received {money(data.payments_minor ?? 0)}</p>
            <div className="table-wrap"><table>
              <thead><tr><th>Method</th><th className="num">Payments</th><th className="num">Amount</th></tr></thead>
              <tbody>
                {(data.payments_by_method ?? []).map((m) => (
                  <tr key={m.method}><td>{methodName(m.method)}</td><td className="num">{m.count}</td><td className="num">{money(m.amount_minor ?? 0)}</td></tr>
                ))}
              </tbody>
            </table></div>
            <h3>By day</h3>
            <div className="table-wrap" tabIndex={0}><table>
              <thead><tr><th>Day</th><th>Method</th><th className="num">Payments</th><th className="num">Amount</th></tr></thead>
              <tbody>
                {payments.map((p) => (
                  <tr key={`${p.date}-${p.method}`}><td>{p.date}</td><td>{methodName(p.method)}</td><td className="num">{p.count}</td><td className="num">{money(p.amount_minor ?? 0)}</td></tr>
                ))}
              </tbody>
            </table></div>
          </>
        )}
      </Section>
    </>
  );
}

function CreditControlPage() {
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  const initial = defaultRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [top, setTop] = useState(20);
  const [sort, setSort] = useState<'amount' | 'age'>('amount');
  const problem = rangeProblem(from, to);
  const report = useQuery({
    queryKey: ['retail', 'credit-control', all ? 'all' : branchId, from, to, top, sort],
    queryFn: () => retailAnalytics.creditControl({ branchId: all ? undefined : (branchId ?? ''), from, to, top, sort }),
    enabled: !problem && (all || branchId !== null),
  });
  return (
    <Gate screen="creditControl" title="Credit control" wide>
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
      <p className="hint">The dates choose which payments are listed. What is owed is always as of today.</p>
      <DateRange from={from} to={to} onFrom={setFrom} onTo={setTo} />
      <div className="rt-row">
        <Choice id="cc-sort" label="Overdue list" value={sort} options={[...SORTS]} onChange={setSort} />
        <Choice id="cc-top" label="Rows to show" value={top} options={TOPS} onChange={setTop} />
      </div>
      {report.isPending && !problem && <SkeletonList label="Loading credit control" />}
      <Problem error={report.error} />
      {report.data && <CreditControlView data={report.data} nameOf={nameOf} sort={sort} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/credit-control')({ component: CreditControlPage });
