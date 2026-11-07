import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { createRoot } from 'react-dom/client';
import type { Me } from '../src/api/client';
import type { Reconciliation, SignOffRun } from '../src/api/imports';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { ImportsSignOff } from '../src/areas/staff/imports/page';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page imports-sign-off.layout.test.ts lays out in a real browser (issue #134): Imports to sign off on a phone, the list of
// imports, and one import's reconciliation with long file names, a long product code, money past a billion and a quantity with
// three decimals, ready to sign. ?font= scales the root font size as a phone's large text setting does; ?open=1 opens the
// import; ?broken=1 draws one whose difference is not explained. The answers are put in the query cache, so the page asks no
// server. Every name, code and figure is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;
const broken = query.get('broken') === '1';

const me = {
  ...mockMe('admin'),
  permissions: [...(mockMe('admin').permissions ?? []), 'core.imports.sign_off'],
  tenant_currency: 'UGX',
  tenant_timezone: 'Africa/Kampala',
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
} as unknown as Me;
const branch = me.branches?.[0]?.id ?? '';

const longFile = 'fabricated-shop-book-with-a-very-long-name-for-the-till-roll-tab.xlsx';
const runs: SignOffRun[] = [
  { run_id: 'run-1', status: 'committed', committed_at: '2026-10-01T07:00:00Z', version: 2, signable: !broken },
  { run_id: 'run-0', status: 'signed_off', committed_at: '2026-09-01T07:00:00Z', version: 1, signable: true, signed_at: '2026-09-02T09:00:00Z' },
];
const reconciliation: Reconciliation = {
  id: 'rec-2',
  run_id: 'run-1',
  version: 2,
  generated_at: '2026-10-01T08:00:00Z',
  as_at: '2026-10-01',
  sha256: 'a'.repeat(64),
  signable: !broken,
  sections: [
    {
      key: 'counts',
      title: 'Rows and records',
      facts: [
        { label: `${longFile}: rows read`, value: '100000' },
        { label: 'Records created as retail.stock_movement', value: '99994' },
      ],
      checks: [
        { key: 'batch 3', label: `Rows of ${longFile}`, unit: 'rows', source: '99999', system: '99993', difference: '6', itemised: '6', unitemised: '0', ties: true },
      ],
      items: [
        { check: 'batch 3', file: longFile, line: 99001, reason: 'excluded', detail: 'left out in the review (DATE_AFTER_CUTOVER REF_UNKNOWN)', amount: '1' },
        { check: 'batch 3', file: longFile, line: 4, reason: 'overridden', detail: 'written as reviewed; warning overridden (CODE_DUPLICATE)', amount: '0' },
      ],
    },
    {
      key: 'stock_quantity',
      title: 'Stock quantity per branch and product',
      facts: [],
      checks: [
        { key: 'HQ FAB-LONG-CODE-0000000000001', label: 'FAB-LONG-CODE-0000000000001 at HQ', unit: 'qty', source: '1234567.125', system: '1234567.125', difference: '0', itemised: '0', unitemised: '0', ties: true },
      ],
      items: [],
    },
    {
      key: 'stock_value',
      title: 'Stock value per branch',
      facts: [],
      checks: [
        {
          key: 'HQ',
          label: 'Stock value at HQ',
          unit: 'minor',
          source: '1234567890123',
          system: broken ? '1234567890124' : '1234567890123',
          difference: broken ? '-1' : '0',
          itemised: '0',
          unitemised: broken ? '-1' : '0',
          ties: !broken,
        },
      ],
      items: [],
    },
    {
      key: 'ledger',
      title: 'Journals and trial balance',
      facts: [],
      checks: [
        { key: 'trial balance', label: 'Trial balance of the business: debits less credits', unit: 'minor', source: '0', system: '0', difference: '0', itemised: '0', unitemised: '0', ties: true },
      ],
      items: [],
    },
  ],
};

const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, refetchInterval: false } } });
client.setQueryData(['imports-sign-off'], runs);
client.setQueryData(['imports-reconciliation', 'run-1'], reconciliation);
const router = layoutRouter(query.get('open') === '1' ? '/staff/setup/imports?run=run-1' : '/staff/setup/imports');

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <div className="app-shell"><div className="app-content"><div className="nav-layout"><div className="nav-layout-main">
          <ImportsSignOff />
        </div></div></div></div>
      </StaffContext.Provider>
    </QueryClientProvider>
  </RouterContextProvider>,
);
