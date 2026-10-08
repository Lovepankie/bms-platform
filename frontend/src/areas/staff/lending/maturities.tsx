import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { investments, type InvestmentMaturities, type InvestmentMetrics } from '../../../api/investments';
import { branchFilter } from '../../../auth/branch';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { bucketWords, instructionWords, investmentBadge, percent } from './investment-state';
import { InvestmentsGate } from './investments';
import { money, words } from './loan-state';

// The maturities list (FR-INV-12): what falls due in the next 7, 30 and 90 days, and what has
// matured and still waits to be paid, so the owner can plan cash for payouts; then the month's
// investment figures and the largest investors. Every figure is the server's.

export function Ladder({ data }: { data: InvestmentMaturities }) {
  const c = data.currency;
  return (
    <section aria-labelledby="iv-ladder" className="rt-card">
      <h2 id="iv-ladder">Cash needed for payouts</h2>
      <ul className="iv-ladder">
        {(data.ladder ?? []).map((b) => (
          <li key={b.bucket} className={b.bucket === 'overdue' && (b.count ?? 0) > 0 ? 'iv-due' : undefined}>
            <span className="ln-muted">{bucketWords(b.bucket)}</span>
            <strong className="num">{money(b.total_minor, c)}</strong>
            <span className="ln-muted">
              {b.count} investments: principal {money(b.principal_minor, c)}, return {money(b.return_minor, c)}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function MaturityRows({ data }: { data: InvestmentMaturities }) {
  const items = data.items ?? [];
  if (items.length === 0) return <p className="empty-state">Nothing matures in the next 90 days.</p>;
  return (
    <ul className="ln-list">
      {items.map((m) => (
        <li key={m.investment_id}>
          <Link className="ln-item" to="/staff/lending/investments/$investmentId" params={{ investmentId: m.investment_id ?? '' }}>
            <span className="ln-item-head">
              <strong>{m.account_no}</strong>
              <span className={investmentBadge(m.status)}>{words(m.status)}</span>
            </span>
            <span>{m.member_name}</span>
            <span className="ln-item-facts">
              <span>{(m.days_to_maturity ?? 0) < 0 ? `Matured ${m.maturity_date}` : `Matures ${m.maturity_date}, in ${m.days_to_maturity} days`}</span>
              <span>Pay {money(m.total_minor, data.currency)}</span>
              <span>{instructionWords(m.maturity_instruction)}</span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

export function MetricsCard({ data }: { data: InvestmentMetrics }) {
  return (
    <section aria-labelledby="iv-metrics" className="rt-card">
      <h2 id="iv-metrics">This month, {data.from} to {data.to}</h2>
      <dl className="ln-facts">
        {(data.metrics ?? []).map((m) => (
          <div key={m.key} className="iv-metric">
            <dt title={m.definition}>{m.label}</dt>
            <dd>{m.kind === 'money' ? money(m.value, m.currency) : m.kind === 'basis_points' ? percent(m.value) : m.value}</dd>
          </div>
        ))}
      </dl>
      <h3>Largest investors</h3>
      {(data.top_investors ?? []).length === 0 ? (
        <p className="empty-state">No open investments.</p>
      ) : (
        <ol className="iv-shares">
          {(data.top_investors ?? []).map((s) => (
            <li key={s.id}>
              <span>{s.label}</span>
              <span className="num">{percent(s.share_bp)}</span>
            </li>
          ))}
        </ol>
      )}
      <h3>By product</h3>
      <ol className="iv-shares">
        {(data.products ?? []).map((s) => (
          <li key={s.id}>
            <span>{s.label}</span>
            <span className="num">{percent(s.share_bp)}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}

function Maturities() {
  const { branch } = useStaff();
  const branches = branchFilter(branch);
  const ladder = useQuery({ queryKey: ['lending', 'investments', 'maturities', branch], queryFn: () => investments.maturities(branches), enabled: branch !== null });
  const metrics = useQuery({ queryKey: ['lending', 'investments', 'metrics', branch], queryFn: () => investments.metrics(branches), enabled: branch !== null });
  return (
    <InvestmentsGate title="Maturities">
      {ladder.isPending && branch !== null && <p className="loading">Loading maturities</p>}
      <Problem error={ladder.error} />
      {ladder.data && (
        <>
          <Ladder data={ladder.data} />
          <MaturityRows data={ladder.data} />
        </>
      )}
      <Problem error={metrics.error} />
      {metrics.data && <MetricsCard data={metrics.data} />}
    </InvestmentsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/investments/maturities')({ component: Maturities });
