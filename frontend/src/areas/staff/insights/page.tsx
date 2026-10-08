import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  insights,
  type InsightsBrief,
  type InsightsFilter,
  type InsightsMembers,
  type InsightsPortfolio,
  type InsightsRevenue,
  type InsightsTable,
} from '../../../api/insights';
import { branchFilter } from '../../../auth/branch';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { BarList, ColumnChart, LineChart, type BarRow } from './charts';
import { formatValue, presetRange, words } from './format';
import { canExport, canManageDigest, ownPortfolioOnly, showInsights } from './permissions';
import { MetricCard, MetricGrid, drillOf, type DrillTarget } from './ui';

// Insights (#153, expectation 7): the morning brief, the loan portfolio, revenue from the ledger
// and member activity, for the branch chosen in the staff bar and the filters here. Numbers are
// live for today and refresh every 60 seconds (no websockets); every number has its definition
// (the "i" tip) and opens the rows behind it, which can be exported as CSV. Printing gives a
// clean report: the filters, tabs and buttons are hidden and the chart tables are shown.

export const REFRESH_MS = 60_000;

const loans = (n: number | undefined) => (n === 1 ? '1 loan' : `${n ?? 0} loans`);

const TABS = [
  { key: 'brief', label: 'Today' },
  { key: 'portfolio', label: 'Portfolio' },
  { key: 'revenue', label: 'Revenue' },
  { key: 'members', label: 'Members' },
] as const;

const PRESETS = [
  { value: 'month', label: 'This month' },
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: 'quarter', label: 'Last 90 days' },
  { value: 'year', label: 'Last 12 months' },
  { value: 'custom', label: 'Custom dates' },
];

function Insights() {
  const { me } = useStaff();
  if (!showInsights(me)) {
    return (
      <main className="rt ln iv">
        <h1>Insights</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  return <InsightsPage />;
}

function InsightsPage() {
  const { me, branch } = useStaff();
  const [tab, setTab] = useState<string>('brief');
  const [preset, setPreset] = useState('month');
  const initial = presetRange('month', new Date());
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [grain, setGrain] = useState('');
  const [productId, setProductId] = useState('');
  const [officerId, setOfficerId] = useState('');
  const [drill, setDrill] = useState<DrillTarget | null>(null);
  const own = ownPortfolioOnly(me);

  const filter: InsightsFilter = {
    from,
    to,
    grain: grain || undefined,
    branchIds: branchFilter(branch),
    productId: productId || undefined,
    officerId: own ? undefined : officerId || undefined,
  };
  const enabled = branch !== null;
  const common = { enabled, refetchInterval: REFRESH_MS, refetchIntervalInBackground: false } as const;
  const brief = useQuery({ queryKey: ['insights', 'brief', filter], queryFn: () => insights.brief(filter), ...common });
  const portfolio = useQuery({
    queryKey: ['insights', 'portfolio', filter],
    queryFn: () => insights.portfolio(filter),
    ...common,
  });
  const revenue = useQuery({
    queryKey: ['insights', 'revenue', filter],
    queryFn: () => insights.revenue(filter),
    ...common,
    enabled: enabled && tab === 'revenue',
  });
  const members = useQuery({
    queryKey: ['insights', 'members', filter],
    queryFn: () => insights.members(filter),
    ...common,
    enabled: enabled && tab === 'members',
  });
  const panels = useQuery({ queryKey: ['insights', 'panels', filter], queryFn: () => insights.panels(filter), ...common });

  function choosePreset(value: string) {
    setPreset(value);
    if (value !== 'custom') {
      const r = presetRange(value, new Date());
      setFrom(r.from);
      setTo(r.to);
    }
  }

  const products = portfolio.data?.breakdowns?.find((b) => b.dimension === 'product')?.rows ?? [];
  const officers = portfolio.data?.breakdowns?.find((b) => b.dimension === 'officer')?.rows ?? [];
  const updated = brief.dataUpdatedAt ? new Date(brief.dataUpdatedAt) : null;
  const tabs = [
    ...TABS,
    ...(panels.data?.panels ?? []).map((p) => ({ key: `panel:${p.key}`, label: p.title ?? p.key ?? '' })),
    ...(canManageDigest(me) ? [{ key: 'digest', label: 'Daily digest' }] : []),
  ];

  return (
    <main className="rt ln iv">
      <div className="iv-head">
        <h1>Insights</h1>
        <p className="ln-muted" aria-live="polite">
          {updated ? `Updated ${updated.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}, refreshes every minute` : 'Loading'}
        </p>
        <button type="button" className="btn-ghost btn-sm iv-noprint" onClick={() => window.print()}>
          Print
        </button>
      </div>
      {own && <p className="alert alert-info">These figures cover only the loans you are the responsible officer for.</p>}
      <form className="iv-filters iv-noprint" onSubmit={(e: FormEvent) => e.preventDefault()} aria-label="Filters">
        <label>
          Range
          <select value={preset} onChange={(e) => choosePreset(e.target.value)}>
            {PRESETS.map((p) => (
              <option key={p.value} value={p.value}>{p.label}</option>
            ))}
          </select>
        </label>
        <label>
          From
          <input type="date" value={from} max={to} onChange={(e) => { setPreset('custom'); setFrom(e.target.value); }} />
        </label>
        <label>
          To
          <input type="date" value={to} min={from} max={presetRange('today', new Date()).to} onChange={(e) => { setPreset('custom'); setTo(e.target.value); }} />
        </label>
        <label>
          Group by
          <select value={grain} onChange={(e) => setGrain(e.target.value)}>
            <option value="">Automatic</option>
            <option value="day">Day</option>
            <option value="week">Week</option>
            <option value="month">Month</option>
            <option value="year">Year</option>
          </select>
        </label>
        <label>
          Product
          <select value={productId} onChange={(e) => setProductId(e.target.value)}>
            <option value="">All products</option>
            {products.map((p) => (
              <option key={p.key} value={p.key}>{p.label}</option>
            ))}
          </select>
        </label>
        {!own && (
          <label>
            Officer
            <select value={officerId} onChange={(e) => setOfficerId(e.target.value)}>
              <option value="">All officers</option>
              {officers.map((o) => (
                <option key={o.key} value={o.key}>{o.label}</option>
              ))}
            </select>
          </label>
        )}
        <p className="ln-muted iv-filters-note">Branch: the one chosen at the top of the page.</p>
      </form>

      <div className="iv-tabs iv-noprint" role="tablist" aria-label="Insights sections">
        {tabs.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            className={tab === t.key ? 'iv-tab iv-tab-on' : 'iv-tab'}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'brief' && (
        <section aria-label="Morning brief">
          <Problem error={brief.error} />
          {brief.data && (
            <>
              <h2>Morning brief, {brief.data.as_of}</h2>
              <BriefCards brief={brief.data} onDrill={setDrill} />
            </>
          )}
          {portfolio.data && (
            <>
              <h2>Portfolio at a glance</h2>
              <MetricGrid
                metrics={portfolio.data.metrics ?? []}
                onDrill={setDrill}
                keys={['portfolio.principal_outstanding', 'portfolio.par30', 'portfolio.collection_rate', 'portfolio.forecast_7']}
              />
            </>
          )}
        </section>
      )}
      {tab === 'portfolio' && <PortfolioView query={portfolio} onDrill={setDrill} />}
      {tab === 'revenue' && <RevenueView query={revenue} onDrill={setDrill} />}
      {tab === 'members' && <MembersView query={members} onDrill={setDrill} />}
      {tab.startsWith('panel:') &&
        (panels.data?.panels ?? [])
          .filter((p) => `panel:${p.key}` === tab)
          .map((p) => (
            <section key={p.key} aria-label={p.title}>
              <h2>{p.title}</h2>
              <MetricGrid metrics={p.metrics ?? []} />
            </section>
          ))}
      {tab === 'digest' && <DigestSettings />}

      {drill && <DrillPanel target={drill} filter={filter} exportable={canExport(me)} onClose={() => setDrill(null)} />}
    </main>
  );
}

export function BriefCards({ brief, onDrill }: { brief: InsightsBrief; onDrill: (t: DrillTarget) => void }) {
  const metrics = brief.metrics ?? [];
  const sentences = brief.sentences ?? [];
  const card = (key: string, sentence: number) => {
    const m = metrics.find((x) => x.key === key);
    if (!m) return null;
    return <MetricCard key={key} metric={m} onDrill={onDrill} emphasis note={sentences[sentence]} />;
  };
  return (
    <div className="iv-grid iv-grid-brief">
      {card('brief.disbursed_today', 0)}
      {card('brief.collected_today', 1)}
      {card('brief.expected_today', 2)}
      {card('brief.new_arrears', 3)}
      {card('brief.going_bad', 4)}
    </div>
  );
}

type Query<T> = { data?: T; error: unknown; isPending: boolean };

export function PortfolioView({ query, onDrill }: { query: Query<InsightsPortfolio>; onDrill: (t: DrillTarget) => void }) {
  const p = query.data;
  if (!p) return <>{query.error ? <Problem error={query.error} /> : <p className="loading">Loading the portfolio</p>}</>;
  const cur = p.currency ?? 'UGX';
  const series = p.series ?? [];
  const metrics = p.metrics ?? [];
  const stock =
    p.stock_source === 'live'
      ? 'Outstanding, PAR and ageing are live, as at today.'
      : p.stock_source === 'snapshot'
        ? `Outstanding, PAR and ageing are as at ${p.stock_as_of}, from the nightly snapshot.`
        : `There is no nightly snapshot for ${p.stock_as_of} yet, so outstanding and PAR are not shown for that date.`;
  const ageing: BarRow[] = (p.ageing ?? []).map((b) => ({
    key: b.key ?? '',
    label: b.label ?? '',
    value: b.principal_minor ?? 0,
    note: `${loans(b.loans)}${b.share_bp !== null && b.share_bp !== undefined ? `, ${formatValue(b.share_bp, 'basis_points')} of principal` : ''}`,
    action: <DrillButton onClick={() => onDrill({ table: 'active_loans', params: { group: 'bucket', key: b.key ?? '' }, title: `Loans ${b.label}` })} />,
  }));
  return (
    <section aria-label="Loan portfolio">
      <p className="ln-muted">{stock}</p>
      <MetricGrid metrics={metrics} onDrill={onDrill} />
      <h2>Money out and in</h2>
      <LineChart
        title={`Disbursed and collected by ${p.grain}`}
        series={[{ key: 'd', label: 'Disbursed' }, { key: 'c', label: 'Collected' }]}
        points={series.map((s) => ({ label: s.label ?? '', values: [s.disbursed_minor ?? 0, s.collected_minor ?? 0] }))}
        kind="money"
        currency={cur}
      />
      <ColumnChart
        title={`Collection rate by ${p.grain} (collected on due over expected)`}
        label="Collection rate"
        points={series.map((s) => ({ label: s.label ?? '', values: [s.collection_rate_bp ?? null] }))}
        kind="basis_points"
        currency={cur}
      />
      <h2>Portfolio at risk</h2>
      <BarList title="Ageing of principal outstanding" rows={ageing} kind="money" currency={cur} tone="c2" />
      {(p.breakdowns ?? []).map((b) => (
        <BarList
          key={b.dimension}
          title={b.title ?? ''}
          kind="money"
          currency={cur}
          rows={(b.rows ?? []).map((r) => ({
            key: r.key ?? '',
            label: r.label ?? '',
            value: r.principal_outstanding_minor ?? 0,
            note:
              b.dimension === 'status'
                ? loans(r.loans)
                : `${loans(r.loans)}, PAR 30 ${formatValue(r.par30_bp ?? null, 'basis_points')}`,
            action: (
              <DrillButton
                onClick={() =>
                  onDrill(
                    b.dimension === 'status'
                      ? { table: 'loans_by_status', params: { status: r.key ?? '' }, title: `${words(r.key ?? '')} loans` }
                      : { table: 'active_loans', params: { group: b.dimension ?? '', key: r.key ?? '' }, title: `Active loans: ${r.label}` },
                  )
                }
              />
            ),
          }))}
        />
      ))}
      <h2>Largest arrears</h2>
      {(p.top_arrears ?? []).length === 0 ? (
        <p className="ln-muted">No loan is in arrears.</p>
      ) : (
        <div className="table-wrap" role="region" aria-label="Largest arrears" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Loan</th>
                <th scope="col">Borrower</th>
                <th scope="col" className="num">Days late</th>
                <th scope="col" className="num">Arrears</th>
                <th scope="col" className="num">Principal</th>
                <th scope="col">Officer</th>
              </tr>
            </thead>
            <tbody>
              {(p.top_arrears ?? []).map((a) => (
                <tr key={a.loan_id}>
                  <td>
                    <Link to="/staff/lending/loans/$loanId" params={{ loanId: a.loan_id ?? '' }}>{a.loan_no}</Link>
                  </td>
                  <td>{a.member_name} ({a.member_no})</td>
                  <td className="num">{a.days_past_due}</td>
                  <td className="num">{formatValue(a.arrears_minor, 'money', cur)}</td>
                  <td className="num">{formatValue(a.principal_outstanding_minor, 'money', cur)}</td>
                  <td>{a.officer_name}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h2>Expected collections, next 30 days (estimate)</h2>
      <ColumnChart
        title="Unpaid instalments falling due each day; assumes every borrower pays on time"
        label="Expected"
        points={(p.forecast ?? []).map((d) => ({ label: d.date ?? '', values: [d.amount_minor ?? 0] }))}
        kind="money"
        currency={cur}
      />
      <h2>Twelve-month trend</h2>
      <LineChart
        title="Principal outstanding at each month end"
        series={[{ key: 'po', label: 'Principal outstanding' }]}
        points={(p.trend ?? []).map((t) => ({ label: t.label ?? '', values: [t.principal_outstanding_minor ?? null] }))}
        kind="money"
        currency={cur}
      />
      <ColumnChart
        title="PAR 30 at each month end"
        label="PAR 30"
        points={(p.trend ?? []).map((t) => ({ label: t.label ?? '', values: [t.par30_bp ?? null] }))}
        kind="basis_points"
        currency={cur}
      />
    </section>
  );
}

function DrillButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className="iv-drill-btn iv-noprint" onClick={onClick}>
      Rows
    </button>
  );
}

export function RevenueView({ query, onDrill }: { query: Query<InsightsRevenue>; onDrill: (t: DrillTarget) => void }) {
  const r = query.data;
  if (!r) return <>{query.error ? <Problem error={query.error} /> : <p className="loading">Loading revenue</p>}</>;
  const cur = r.currency ?? 'UGX';
  const rows = (list: InsightsRevenue['by_product']) => (
    <div className="table-wrap" role="region" tabIndex={0} aria-label="Revenue">
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col" className="num">Interest</th>
            <th scope="col" className="num">Fees</th>
            <th scope="col" className="num">Penalties</th>
            <th scope="col" className="num">Recovered</th>
            <th scope="col" className="num">Write-offs</th>
            <th scope="col" className="num">Contribution</th>
            <th scope="col" className="num">Yield</th>
          </tr>
        </thead>
        <tbody>
          {(list ?? []).map((x) => (
            <tr key={x.key}>
              <th scope="row">{x.label}</th>
              <td className="num">{formatValue(x.interest_minor, 'money', cur)}</td>
              <td className="num">{formatValue(x.fees_minor, 'money', cur)}</td>
              <td className="num">{formatValue(x.penalties_minor, 'money', cur)}</td>
              <td className="num">{formatValue(x.recovered_minor, 'money', cur)}</td>
              <td className="num">{formatValue(x.write_off_expense_minor, 'money', cur)}</td>
              <td className="num">{formatValue(x.contribution_minor, 'money', cur)}</td>
              <td className="num">{formatValue(x.effective_yield_bp ?? null, 'basis_points')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
  return (
    <section aria-label="Revenue">
      <p className="ln-muted">From posted journal lines only: nothing here is estimated from schedules.</p>
      <MetricGrid metrics={r.metrics ?? []} onDrill={onDrill} />
      <h2>Last 12 months</h2>
      <LineChart
        title="Interest, fees and profit contribution by month"
        series={[
          { key: 'i', label: 'Interest' },
          { key: 'f', label: 'Fees' },
          { key: 'c', label: 'Contribution' },
        ]}
        points={(r.months ?? []).map((m) => ({
          label: m.label ?? '',
          values: [m.interest_minor ?? 0, m.fees_minor ?? 0, m.contribution_minor ?? 0],
        }))}
        kind="money"
        currency={cur}
      />
      <h2>By product</h2>
      {rows(r.by_product)}
      <h2>By branch</h2>
      {rows(r.by_branch)}
    </section>
  );
}

export function MembersView({ query, onDrill }: { query: Query<InsightsMembers>; onDrill: (t: DrillTarget) => void }) {
  const m = query.data;
  if (!m) return <>{query.error ? <Problem error={query.error} /> : <p className="loading">Loading member activity</p>}</>;
  const funnel: BarRow[] = (m.funnel ?? []).map((s) => {
    const target = drillOf({ label: s.label, drill: s.drill });
    return {
      key: s.key ?? '',
      label: s.label ?? '',
      value: s.count ?? 0,
      note: s.conversion_bp !== null && s.conversion_bp !== undefined ? `${formatValue(s.conversion_bp, 'basis_points')} of the stage before` : undefined,
      action: target ? <DrillButton onClick={() => onDrill(target)} /> : undefined,
    };
  });
  return (
    <section aria-label="Member activity">
      <MetricGrid metrics={m.metrics ?? []} onDrill={onDrill} />
      <ColumnChart
        title={`New members by ${m.grain}`}
        label="New members"
        points={(m.new_members ?? []).map((p) => ({ label: p.label ?? '', values: [p.count ?? 0] }))}
        kind="count"
        currency="UGX"
      />
      <BarList title="Applications funnel" rows={funnel} kind="count" currency="UGX" />
      <BarList
        title="KYC status of active members"
        rows={(m.kyc ?? []).map((k) => ({ key: k.key ?? '', label: k.label ?? '', value: k.count ?? 0 }))}
        kind="count"
        currency="UGX"
        tone="c3"
      />
      <BarList
        title="Credit score bands of applications (latest appraisal)"
        rows={(m.score_bands ?? []).map((k) => ({ key: k.key ?? '', label: k.label ?? '', value: k.count ?? 0 }))}
        kind="count"
        currency="UGX"
        tone="c2"
      />
      <h2>Staff activity</h2>
      <div className="table-wrap" role="region" tabIndex={0} aria-label="Staff activity">
        <table>
          <thead>
            <tr>
              <th scope="col">Staff</th>
              <th scope="col" className="num">Members</th>
              <th scope="col" className="num">Submitted</th>
              <th scope="col" className="num">Appraisals</th>
              <th scope="col" className="num">Disbursements</th>
              <th scope="col" className="num">Repayments</th>
            </tr>
          </thead>
          <tbody>
            {(m.staff ?? []).map((s) => (
              <tr key={s.user_id}>
                <th scope="row">{s.name}</th>
                <td className="num">{s.members_registered}</td>
                <td className="num">{s.applications_submitted}</td>
                <td className="num">{s.appraisals}</td>
                <td className="num">{s.disbursements}</td>
                <td className="num">{s.repayments_recorded}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** The rows behind a number, with a CSV export for those who may export. */
export function DrillTable({ table }: { table: InsightsTable }) {
  const columns = table.columns ?? [];
  const cur = table.currency ?? 'UGX';
  return (
    <div className="table-wrap" role="region" tabIndex={0} aria-label={table.title}>
      <table>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} scope="col" className={['money', 'count', 'days', 'basis_points'].includes(c.kind ?? '') ? 'num' : undefined}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {(table.rows ?? []).map((row, i) => (
            <tr key={i}>
              {row.map((cell, j) => {
                const kind = columns[j]?.kind ?? 'text';
                const numeric = ['money', 'count', 'days', 'basis_points'].includes(kind);
                return (
                  <td key={j} className={numeric ? 'num' : undefined}>
                    {numeric && cell !== '' ? formatValue(Number(cell), kind, cur) : cell}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DrillPanel({ target, filter, exportable, onClose }: {
  target: DrillTarget;
  filter: InsightsFilter;
  exportable: boolean;
  onClose: () => void;
}) {
  const rows = useQuery({
    queryKey: ['insights', 'table', target, filter],
    queryFn: () => insights.table(target.table, filter, target.params),
    refetchInterval: REFRESH_MS,
  });
  const download = useMutation({ mutationFn: () => insights.exportCsv(target.table, filter, target.params) });
  const ref = useRef<HTMLElement>(null);
  // The panel opens below the page: bring it into view and give it focus for keyboard users.
  useEffect(() => {
    ref.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    ref.current?.focus({ preventScroll: true });
  }, [target]);
  return (
    <section ref={ref} tabIndex={-1} className="iv-drill rt-card" aria-label={`Rows behind ${target.title}`}>
      <div className="iv-drill-head">
        <h2>{target.title}: the rows</h2>
        <div className="rt-row iv-noprint">
          {exportable && (
            <button type="button" className="btn-sm" disabled={download.isPending} onClick={() => download.mutate()}>
              {download.isPending ? 'Exporting' : 'Export CSV'}
            </button>
          )}
          <button type="button" className="btn-ghost btn-sm" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
      <Problem error={rows.error ?? download.error} />
      {rows.isPending && <p className="loading">Loading rows</p>}
      {rows.data && (
        <>
          {(rows.data.rows ?? []).length === 0 ? <p className="ln-muted">No rows.</p> : <DrillTable table={rows.data} />}
          {rows.data.truncated && <p className="ln-muted">Showing the first 200 rows. Export the CSV for all of them.</p>}
        </>
      )}
    </section>
  );
}

/** The owner's daily digest (off by default): recipients, hour, and a preview of today's text. */
function DigestSettings() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: ['insights', 'digest'], queryFn: insights.digestSettings });
  const preview = useQuery({ queryKey: ['insights', 'digest', 'preview'], queryFn: insights.digestPreview, enabled: false });
  const [draft, setDraft] = useState<{ enabled: boolean; emails: string; chat: string; hour: number } | null>(null);
  const save = useMutation({
    mutationFn: () =>
      insights.updateDigest(
        {
          enabled: draft?.enabled ?? false,
          email_recipients: (draft?.emails ?? '').split(/[,\s]+/).map((e) => e.trim()).filter(Boolean),
          telegram_chat_id: draft?.chat.trim() || undefined,
          send_hour: draft?.hour ?? 7,
        },
        settings.data?.version ?? 0,
      ),
    onSuccess: (data) => {
      queryClient.setQueryData(['insights', 'digest'], data);
      setDraft(null);
    },
  });
  const s = settings.data;
  if (!s) return <>{settings.error ? <Problem error={settings.error} /> : <p className="loading">Loading</p>}</>;
  const d = draft ?? { enabled: s.enabled ?? false, emails: (s.email_recipients ?? []).join(', '), chat: s.telegram_chat_id ?? '', hour: s.send_hour ?? 7 };
  return (
    <section aria-label="Daily digest" className="rt-card">
      <h2>Daily digest</h2>
      <p className="ln-muted">
        A short plain-text summary of the day for the whole business, sent once a day by email and Telegram. Off until
        you switch it on. {s.last_sent_on ? `Last sent ${s.last_sent_on}.` : 'Not sent yet.'}
      </p>
      <form
        className="iv-digest"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <label className="iv-check">
          <input type="checkbox" checked={d.enabled} onChange={(e) => setDraft({ ...d, enabled: e.target.checked })} />
          Send the daily digest
        </label>
        <label>
          Email addresses (up to five, separated by commas)
          <input type="text" inputMode="email" autoComplete="off" value={d.emails} onChange={(e) => setDraft({ ...d, emails: e.target.value })} />
        </label>
        <label>
          Telegram chat id (numbers only; the chat must have started the BMS bot)
          <input type="text" inputMode="numeric" value={d.chat} onChange={(e) => setDraft({ ...d, chat: e.target.value })} />
        </label>
        <label>
          Send at or after
          <select value={d.hour} onChange={(e) => setDraft({ ...d, hour: Number(e.target.value) })}>
            {Array.from({ length: 24 }, (_, h) => (
              <option key={h} value={h}>{`${String(h).padStart(2, '0')}:00`}</option>
            ))}
          </select>
        </label>
        <Problem error={save.error} />
        {save.isSuccess && !draft && <p role="status" className="alert alert-success">Saved.</p>}
        <div className="rt-row">
          <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving' : 'Save'}</button>
          <button type="button" className="btn-ghost" onClick={() => void preview.refetch()}>Preview today&apos;s text</button>
        </div>
      </form>
      <Problem error={preview.error} />
      {preview.data && (
        <>
          <h3>{preview.data.subject}</h3>
          <pre className="iv-digest-preview">{preview.data.text}</pre>
        </>
      )}
    </section>
  );
}

export const Route = createLazyRoute('/staff/insights')({ component: Insights });
