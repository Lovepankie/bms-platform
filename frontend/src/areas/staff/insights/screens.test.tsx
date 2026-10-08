import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { InsightsBrief, InsightsMembers, InsightsTable } from '../../../api/insights';
import { BarList, ColumnChart, LineChart, niceMax, showLabel } from './charts';
import { BriefCards, DrillTable, MembersView } from './page';
import { MetricCard } from './ui';

const renderToString = (node: ReactElement) =>
  render(<QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>).replaceAll('<!-- -->', '');

// Static markup tests, as the lending and retail screens do (the test setup has no DOM): they prove
// every number carries its definition and its drill-down, and every chart its table alternative.

const metric = (key: string, value: number | undefined, kind: string, label: string) => ({
  key,
  label,
  kind,
  value,
  currency: kind === 'money' ? 'UGX' : undefined,
  definition: `Definition of ${label}.`,
  drill: { table: 'disbursements', params: { date: '2026-10-06' } },
});

const brief: InsightsBrief = {
  as_of: '2026-10-06',
  currency: 'UGX',
  metrics: [
    metric('brief.disbursed_today', 1_200_000, 'money', 'Money out today'),
    metric('brief.collected_today', 400_000, 'money', 'Money in today'),
    metric('brief.expected_today', 500_000, 'money', 'Due today'),
    metric('brief.collection_rate_today', 7000, 'basis_points', 'Collection rate today'),
    metric('brief.new_arrears', 2, 'count', 'New arrears'),
    metric('brief.going_bad', 1, 'count', 'Going bad this week'),
  ],
  sentences: [
    'UGX 1,200,000 went out today on 3 loans.',
    'UGX 400,000 came in today.',
    'UGX 350,000 of the UGX 500,000 due today has been paid (70.0 percent).',
    '2 loans fell into arrears today, owing UGX 45,000 overdue.',
    '1 loan holding UGX 300,000 will pass 30 days late within 7 days unless paid.',
  ],
  generated_at: '2026-10-06T06:00:00Z',
};

describe('insights screens', () => {
  it('shows each brief card with its value, sentence, definition and drill-down', () => {
    const html = renderToString(<BriefCards brief={brief} onDrill={() => {}} />);
    expect(html).toContain('UGX 1,200,000');
    expect(html).toContain('went out today on 3 loans.');
    expect(html).toContain('2 loans fell into arrears today');
    expect(html).toContain('How Money out today is calculated');
    expect(html).toContain('Definition of Money out today.');
    expect(html).toContain('Money out today: UGX 1,200,000. Show the rows');
    expect(html).not.toMatch(/style="/);
  });

  it('shows an empty rate as a dash, never as zero', () => {
    const html = renderToString(<MetricCard metric={metric('portfolio.par30', undefined, 'basis_points', 'PAR 30')} />);
    expect(html).toContain('>-<');
  });

  it('gives every chart a table with the same numbers', () => {
    const points = [
      { label: 'Jan 2026', values: [1_000_000, 800_000] },
      { label: 'Feb 2026', values: [1_500_000, null] },
    ];
    const line = renderToString(
      <LineChart title="Disbursed and collected" series={[{ key: 'd', label: 'Disbursed' }, { key: 'c', label: 'Collected' }]} points={points} kind="money" currency="UGX" />,
    );
    expect(line).toContain('<svg');
    expect(line).toContain('Show as table');
    expect(line).toContain('UGX 1,500,000');
    expect(line).toContain('<td class="num">-</td>');
    expect(line).toContain('iv-legend');
    const columns = renderToString(
      <ColumnChart title="Collection rate" label="Rate" points={[{ label: 'Jan', values: [8125] }]} kind="basis_points" currency="UGX" />,
    );
    expect(columns).toContain('81.3%');
    const bars = renderToString(
      <BarList title="Ageing" rows={[{ key: '1_30', label: '1 to 30 days', value: 250_000, note: '3 loans' }]} kind="money" currency="UGX" />,
    );
    expect(bars).toContain('1 to 30 days');
    expect(bars).toContain('UGX 250,000');
    expect(line + columns + bars).not.toMatch(/style="/);
    expect(niceMax(0)).toBe(1);
    expect([0, 1, 2, 3, 4, 5, 6].filter((i) => showLabel(i, 7, 3))).toEqual([0, 3, 6]);
    expect([0, 1, 2, 3, 4, 5, 6, 7].filter((i) => showLabel(i, 8, 3))).toEqual([0, 3, 7]);
    expect(niceMax(1_234_000)).toBe(2_000_000);
    expect(niceMax(5_000)).toBe(5_000);
  });

  it('formats drill-down cells by column kind', () => {
    const table: InsightsTable = {
      key: 'arrears',
      title: 'Loans in arrears',
      currency: 'UGX',
      columns: [
        { key: 'loan_no', label: 'Loan', kind: 'text' },
        { key: 'days_past_due', label: 'Days past due', kind: 'days' },
        { key: 'arrears_minor', label: 'Arrears', kind: 'money' },
      ],
      rows: [['LN000001', '45', '120000']],
      truncated: false,
    };
    const html = renderToString(<DrillTable table={table} />);
    expect(html).toContain('LN000001');
    expect(html).toContain('45 days');
    expect(html).toContain('UGX 120,000');
  });

  it('shows the funnel with conversions and the staff table', () => {
    const members: InsightsMembers = {
      from: '2026-10-01',
      to: '2026-10-06',
      grain: 'day',
      metrics: [metric('members.applied', 20, 'count', 'Applied')],
      new_members: [{ period_start: '2026-10-01', label: '1 Oct', count: 3 }],
      funnel: [
        { key: 'applied', label: 'Applied', count: 20, conversion_bp: undefined, drill: { table: 'applications', params: { stage: 'applied' } } },
        { key: 'approved', label: 'Approved', count: 15, conversion_bp: 7500, drill: { table: 'applications', params: { stage: 'approved' } } },
      ],
      kyc: [{ key: 'verified', label: 'Verified', count: 40 }],
      score_bands: [{ key: 'A', label: 'Band A', count: 5 }],
      staff: [
        {
          user_id: '00000000-0000-4000-8000-0000000000f1',
          name: 'Test Officer 01',
          members_registered: 4,
          applications_submitted: 6,
          appraisals: 0,
          disbursements: 0,
          repayments_recorded: 0,
        },
      ],
      generated_at: '2026-10-06T06:00:00Z',
    };
    const html = renderToString(<MembersView query={{ data: members, error: null, isPending: false }} onDrill={() => {}} />);
    expect(html).toContain('75.0% of the stage before');
    expect(html).toContain('Test Officer 01');
    expect(html).toContain('Band A');
  });
});
