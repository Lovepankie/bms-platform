import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { ReportCatalogueEntry, ReportRun } from '../src/api/reports';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { Reports } from '../src/areas/staff/reports/page';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page reports.layout.test.ts lays out in a real browser (issue #229): the report centre on a phone, with the monthly pack,
// reports with long titles and descriptions in their groups, and runs with long titles, a pack's two downloads and a long
// failure. The answers are put in the query cache, so the page asks no server. ?font= scales the root font size as a phone's
// large text setting does, ?empty=1 draws a user with no run yet, ?off=1 an administrator of a business with report runs switched
// off (issue #764). Every name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const permissions = ['lending.reports.portfolio', 'lending.reports.collections', 'lending.reports.members', 'lending.reports.compliance'];
const me = {
  ...mockMe('admin'),
  modules: ['lending'],
  reports_enabled: query.get('off') !== '1',
  permissions: query.get('off') === '1' ? [...permissions, 'core.settings.manage'] : permissions,
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
} as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';

const param = (name: string, type: string, values: string[] = []) => ({ name, type, required: false, description: name, values });
const catalogue: ReportCatalogueEntry[] = [
  {
    key: 'lending.monthly_pack',
    title: 'Monthly report pack',
    description: 'All eight lending reports for one month from one reading of your books: one PDF with a cover, contents and sign off, and one Excel workbook.',
    permission: 'lending.reports.portfolio',
    formats: ['pack'],
    params: [param('period_end', 'date'), param('branch_id', 'uuid_list')],
  },
  {
    key: 'lending.par',
    title: 'Portfolio at risk and ageing of the active loans in every branch of the business',
    description: 'PAR 1, 30, 60 and 90 and the ageing of the active loans on a date, in your branches, with the arrears of each group.',
    permission: 'lending.reports.portfolio',
    formats: ['csv', 'json', 'pdf', 'xlsx'],
    params: [param('as_at', 'date'), param('branch_id', 'uuid_list'), param('group_by', 'enum', ['none', 'branch', 'product', 'officer'])],
  },
  {
    key: 'lending.arrears_list',
    title: 'Arrears list',
    description: 'Every loan that is late on a date, by how late it is, with the last payment, the last follow up and any promise to pay.',
    permission: 'lending.reports.collections',
    formats: ['csv', 'json', 'pdf', 'xlsx'],
    params: [param('as_at', 'date'), param('bucket', 'enum', ['1_30', '31_60', '61_90', 'over_90'])],
  },
  {
    key: 'lending.collateral_register',
    title: 'Collateral register',
    description: 'Every collateral item in your branches: its value, where it is kept, the loans it secures, and what needs attention.',
    permission: 'lending.reports.members',
    formats: ['csv', 'json', 'pdf', 'xlsx'],
    params: [],
  },
];

const at = '2026-10-31T09:00:00Z';
const base: ReportRun = {
  id: 'r1',
  report_key: 'lending.monthly_pack',
  title: 'Monthly report pack',
  format: 'pack',
  status: 'ready',
  params: { period_end: '2026-10-31' },
  as_of: '2026-10-31',
  snapshot_at: at,
  requested_at: at,
  started_at: at,
  finished_at: at,
  row_count: 123456,
  size_bytes: 7_340_032,
  document_id: 'd1',
  expires_at: '2026-11-30T09:00:00Z',
  expired: false,
  cancel_requested: false,
  error: null,
  files: [
    { document_id: 'd1', format: 'pdf' },
    { document_id: 'd2', format: 'xlsx' },
  ],
};
const runs: ReportRun[] =
  query.get('empty') === '1'
    ? []
    : [
        base,
        { ...base, id: 'r2', report_key: 'lending.par', title: catalogue[1]!.title, format: 'xlsx', files: [{ document_id: 'd3', format: 'xlsx' }] },
        { ...base, id: 'r3', status: 'running', files: [], document_id: null, row_count: null, size_bytes: null },
        { ...base, id: 'r4', report_key: 'lending.arrears_list', title: 'Arrears list', format: 'pdf', status: 'failed', error: 'report_stale_as_of', files: [], document_id: null },
      ];

const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, refetchInterval: false } } });
client.setQueryData(['reports', 'catalogue'], catalogue);
client.setQueryData(['reports', 'runs'], runs);
const router = layoutRouter('/staff/reports');

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout"><div className="nav-layout-main">
          <Reports />
        </div></div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
