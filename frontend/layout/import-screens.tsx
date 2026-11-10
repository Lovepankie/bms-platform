import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { DryRun, ImportFile, ImportIssue, ImportPreview, ImportRun, PlatformRun, PlatformRunState, RunMetrics } from '../src/api/imports';
import { mockMe } from '../src/api/retail-mock';
import { ImportQueue } from '../src/areas/platform/imports';
import { OperatorImportRun } from '../src/areas/platform/import-run';
import { StaffContext } from '../src/areas/staff/context';
import { ImportProblems } from '../src/areas/staff/imports/problems';
import { ImportRunScreen } from '../src/areas/staff/imports/run';
import { ImportRuns } from '../src/areas/staff/imports/runs';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page import-screens.layout.test.ts lays out in a real browser (issue #141): the import screens on a phone. ?screen= picks
// one: runs (the imports), run (one import being checked, its trial run matched, with a long file name and money past a billion),
// committed (brought in, waiting for the sign-off), problems (grouped by kind), queue (the operator's run queue) or oprun (one
// run being brought in, in the portal). ?font= scales the root font size. The answers are put in the query cache, so the page
// asks no server. Every name, code and figure is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;
const screen = query.get('screen') ?? 'run';

const me = {
  ...mockMe('admin'),
  permissions: [...(mockMe('admin').permissions ?? []), 'core.imports.manage', 'core.imports.sign_off', 'core.imports.view_raw'],
  tenant_currency: 'UGX',
  tenant_timezone: 'Africa/Kampala',
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
} as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';

const longFile = 'fabricated-shop-book-with-a-very-long-name-for-the-stock-tab.xlsx';
const run: ImportRun =
  screen === 'committed'
    ? { id: 'run-7', status: 'committed', version: 12, commit_approved: true, committed_at: '2026-11-09T10:00:00Z' }
    : { id: 'run-7', status: 'in_review', version: 8, commit_approved: false };
const files: ImportFile[] = [
  { id: 'b-1', seq: 1, status: screen === 'committed' ? 'committed' : 'parsed', file_name: longFile, row_count: 2480 },
  { id: 'b-2', seq: 2, status: screen === 'committed' ? 'committed' : 'parsed', file_name: 'fab-buyers.csv', row_count: 312 },
];
const metrics: RunMetrics = {
  run_id: 'run-7',
  status: run.status,
  issues: [{ code: 'NEGATIVE_BALANCE', severity: 'warning', total: 6, open: 0 }],
  stamps: { created_at: '2026-11-01T08:00:00Z', committed_at: run.committed_at, reconciled_at: screen === 'committed' ? '2026-11-09T10:05:00Z' : undefined },
};
const balances = {
  stock: [{ branch: 'HQ', qty: 1234567.125, value_minor: 1234567890123 }, { branch: 'SHOP2', qty: 845, value_minor: 4520000 }],
  receivables: [{ branch: 'HQ', amount_minor: 2350000 }],
  journals: [{ branch: 'HQ', kind: 'inventory', debit_minor: 1234567890123, credit_minor: 1234567890123 }],
};
const dryRun: DryRun = {
  at: '2026-11-09T08:00:00Z',
  current: true,
  preview_matches: true,
  sources: [
    { name: longFile, read: 2480, written: 2474, existing: 0, skipped: 6 },
    { name: 'fab-buyers.csv', read: 312, written: 312, existing: 0, skipped: 0 },
  ],
  anomalies: [{ file: longFile, line: 77, message: 'negative stock for FAB-CODE-0077 at HQ, listed for the first stock-take' }],
  balances,
};
const preview: ImportPreview = { template_key: 'retail_v1', template_version: 1, records: [{ type: 'products', count: 2474 }, { type: 'customers', count: 312 }], files: [], balances };
const issues: ImportIssue[] = [
  ...[12, 48, 301].map((line) => ({ id: `i-${line}`, line, code: 'TYPE_INVALID', severity: 'blocking', field: 'price', message: `price on ${longFile} line ${line} is not a valid money.`, version: 1 })),
  ...[7, 9].map((line) => ({ id: `n-${line}`, line, code: 'NAME_MISSING', severity: 'blocking', message: `The item on line ${line} has no name or description.`, version: 1 })),
  ...[77, 78, 90, 91].map((line) => ({ id: `b-${line}`, line, code: 'NEGATIVE_BALANCE', severity: 'warning', field: 'qty', message: 'The source shows a negative quantity for this item at HQ; it is imported as it is and listed for the first stock-take.', version: 1, ask_customer: line === 77 })),
];
const runs: ImportRun[] = [
  { id: 'run-7', status: 'in_review', version: 8 },
  { id: 'run-6', status: 'purged', version: 30 },
];
const platform: PlatformRun[] = [
  { tenant_slug: 'fab-hardware-mbarara', metrics: { run_id: 'run-7', status: 'committing', stamps: { created_at: '2026-11-01T08:00:00Z' }, stages: [{ number: 6, key: 'cutover', started_at: '2026-11-10T06:00:00Z' }] } },
  { tenant_slug: 'fab-pharmacy-gulu', metrics: { run_id: 'run-9', status: 'ready', stamps: { created_at: '2026-11-04T08:00:00Z' }, stages: [{ number: 5, key: 'dry_run', started_at: '2026-11-09T06:00:00Z' }] } },
  { tenant_slug: 'fab-lending-masaka', metrics: { run_id: 'run-4', status: 'in_review', fabricated: true, stamps: { created_at: '2026-11-06T08:00:00Z' }, stages: [{ number: 3, key: 'cleaning_and_review', started_at: '2026-11-07T06:00:00Z' }] } },
];
const opRun: PlatformRunState = {
  tenant_slug: 'fab-hardware-mbarara',
  commit_enabled: true,
  run: { id: 'run-7', status: 'committing', version: 14, halt_requested: false, commit_approved: true, pre_import_dump_file: 'fab-hardware-pre-import-2026-11-10.dump', pre_import_dump_verified: true, trading_override_set: false, trading_override_approved: false },
};

const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, refetchInterval: false } } });
client.setQueryData(['import-runs'], runs);
client.setQueryData(['import-run', 'run-7'], run);
client.setQueryData(['import-files', 'run-7'], files);
client.setQueryData(['import-metrics', 'run-7'], metrics);
client.setQueryData(['import-dry-run', 'run-7'], dryRun);
client.setQueryData(['import-preview', 'run-7'], preview);
client.setQueryData(['import-issues', 'run-7'], issues);
client.setQueryData(['platform-me-imports'], { full_name: 'Test Operator 01', permissions: ['platform.tenants.read', 'platform.tenants.manage'] });
client.setQueryData(['platform-import-runs'], platform);
client.setQueryData(['platform-import-kill-switch'], true);
client.setQueryData(['platform-import-run', 'fab-hardware-mbarara', 'run-7'], opRun);

const page = {
  runs: <ImportRuns />,
  run: <ImportRunScreen runId="run-7" />,
  committed: <ImportRunScreen runId="run-7" />,
  problems: <ImportProblems runId="run-7" />,
  queue: <ImportQueue />,
  oprun: <OperatorImportRun slug="fab-hardware-mbarara" runId="run-7" />,
}[screen];
const router = layoutRouter(screen === 'queue' || screen === 'oprun' ? '/platform/imports' : '/staff/setup/imports/runs');

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout"><div className="nav-layout-main">
          {page}
        </div></div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
