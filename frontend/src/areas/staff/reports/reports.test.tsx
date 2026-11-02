import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { runErrorText, type ReportCatalogueEntry, type ReportRun } from '../../../api/reports';
import {
  CatalogueList,
  PackCard,
  RunsTable,
  defaultFormat,
  downloadLabel,
  filesOf,
  formatName,
  grouped,
  paramLabel,
  reportDate,
  shows,
  sizeText,
  words,
} from './page';
import { showReports } from './permissions';

const renderToString = (node: ReactElement) =>
  render(<QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>).replaceAll('<!-- -->', '');

// Static markup tests, as the other staff screens do (the test setup has no DOM): the Reports page (extras slice D1,
// issue #214) offers what the server's catalogue lists, shows each run's status in words, offers a download only for a
// file that exists, and explains a failure by its code, never by a message.

const par: ReportCatalogueEntry = {
  key: 'lending.par',
  title: 'Portfolio at risk and ageing',
  description: 'PAR 1, 30, 60 and 90 and the ageing of the active loans on a date.',
  permission: 'lending.reports.portfolio',
  formats: ['csv', 'json', 'pdf', 'xlsx'],
  params: [
    { name: 'as_at', type: 'date', required: false, description: 'The date the loans stand at.', values: [] },
    { name: 'branch_id', type: 'uuid_list', required: false, description: 'Branches to include.', values: [] },
    { name: 'group_by', type: 'enum', required: false, description: 'Split the figures.', values: ['none', 'branch', 'product', 'officer'] },
  ],
};

const run = (over: Partial<ReportRun>): ReportRun => ({
  id: '00000000-0000-4000-8000-000000000001',
  report_key: 'lending.par',
  title: 'Portfolio at risk and ageing',
  format: 'csv',
  status: 'ready',
  params: { as_at: '2026-10-08', group_by: 'none' },
  as_of: '2026-10-08',
  snapshot_at: '2026-10-08T10:00:01Z',
  requested_at: '2026-10-08T10:00:00Z',
  started_at: '2026-10-08T10:00:01Z',
  finished_at: '2026-10-08T10:00:02Z',
  row_count: 6,
  size_bytes: 2048,
  document_id: '00000000-0000-4000-8000-0000000000d1',
  expires_at: '2026-11-07T10:00:00Z',
  expired: false,
  cancel_requested: false,
  error: null,
  ...over,
});

describe('who sees the Reports tab', () => {
  it('needs lending, the tenant switch and a report permission', () => {
    const on = { reports_enabled: true, modules: ['lending'] };
    expect(showReports({ permissions: ['lending.reports.portfolio'], ...on })).toBe(true);
    expect(showReports({ permissions: ['lending.reports.compliance'], reports_enabled: true })).toBe(true);
    expect(showReports({ permissions: ['lending.members.read'], ...on })).toBe(false);
    expect(showReports({ permissions: ['lending.reports.portfolio'], reports_enabled: true, modules: ['retail'] })).toBe(false);
  });

  it('hides the tab while the tenant has not switched reports on, whatever the user holds', () => {
    expect(showReports({ permissions: ['lending.reports.portfolio'], reports_enabled: false, modules: ['lending'] })).toBe(false);
    expect(showReports({ permissions: ['lending.reports.portfolio'], modules: ['lending'] })).toBe(false);
  });
});

describe('failures in words', () => {
  it.each([
    ['report_too_large', /too large/i],
    ['snapshot_unavailable', /not saved for that date/i],
    ['report_stale_as_of', /passed before it was made/i],
    ['timeout', /took too long/i],
    ['interrupted', /restart/i],
    ['reports_disabled', /switched off/i],
  ])('explains %s', (code, text) => {
    expect(runErrorText(code)).toMatch(text);
  });

  it('never shows an unknown code, and says nothing for no error', () => {
    expect(runErrorText('some_new_code')).toMatch(/could not be produced/i);
    expect(runErrorText('some_new_code')).not.toContain('some_new_code');
    expect(runErrorText(null)).toBe('');
  });
});

describe('the catalogue', () => {
  const html = renderToString(<CatalogueList entries={[par]} branchIds={undefined} onQueued={() => {}} />);

  it('shows each report with its description and its parameters in words', () => {
    expect(html).toContain('Portfolio at risk and ageing');
    expect(html).toContain('PAR 1, 30, 60 and 90');
    expect(html).toContain('As at');
    expect(html).toContain('Group by');
    expect(html).toContain('No grouping');
    expect(html).toContain('>Officer<');
  });

  it('leaves the branch to the staff bar and offers the file types', () => {
    expect(html).not.toContain('Branch id');
    expect(html).toContain('PDF (to read and print)');
    expect(html).toContain('Excel (workbook)');
    expect(html).toContain('CSV (plain spreadsheet)');
    expect(html).toContain('JSON (data)');
    expect(html).toContain('Run report');
  });

  it('offers the PDF first, then the workbook, and whatever the report has when it has neither', () => {
    // The file type is chosen with the PDF selected, so a click on Run report makes the file to read and print.
    expect(html).toMatch(/<option value="pdf" selected="">PDF \(to read and print\)<\/option>/);
    expect(html).not.toMatch(/<option value="csv" selected/);
    expect(defaultFormat(['csv', 'json', 'pdf', 'xlsx'])).toBe('pdf');
    expect(defaultFormat(['csv', 'json', 'xlsx'])).toBe('xlsx');
    expect(defaultFormat(['json', 'csv'])).toBe('csv');
    expect(defaultFormat(['json'])).toBe('json');
    expect(defaultFormat([])).toBe('json');
  });

  it('says so when no report is available', () => {
    expect(renderToString(<CatalogueList entries={[]} branchIds={undefined} onQueued={() => {}} />)).toContain(
      'No report is available to you yet.',
    );
  });

  it('writes no inline style', () => {
    expect(html).not.toMatch(/style="/);
  });
});

describe('your reports', () => {
  it('offers a download for a ready file and nothing for the others', () => {
    const html = renderToString(
      <RunsTable
        runs={[
          run({}),
          run({ id: 'b', status: 'queued', document_id: null, row_count: null, size_bytes: null, expires_at: null }),
          run({ id: 'c', status: 'running', document_id: null, row_count: null, size_bytes: null, expires_at: null }),
          run({ id: 'd', status: 'failed', error: 'report_too_large', document_id: null, row_count: null, size_bytes: null, expires_at: null }),
          run({ id: 'e', status: 'ready', expired: true }),
        ]}
        onChange={() => {}}
      />,
    );
    expect(html).toContain('Ready');
    expect(html).toContain('Waiting');
    expect(html).toContain('Running');
    expect(html).toContain('Failed');
    expect(html.match(/>Download</g)).toHaveLength(1);
    expect(html.match(/>Cancel</g)).toHaveLength(2);
    expect(html).toContain('6 rows, 2 KB');
    expect(html).toContain('Deleted after 30 days');
    expect(html).toContain('too large to produce');
    expect(html).not.toContain('report_too_large');
  });

  it('names each file type in words', () => {
    expect(formatName('xlsx')).toBe('Excel');
    expect(formatName('pdf')).toBe('PDF');
    const html = renderToString(
      <RunsTable runs={[run({ format: 'xlsx' }), run({ id: 'b', format: 'pdf' })]} onChange={() => {}} />,
    );
    expect(html).toContain('(Excel)');
    expect(html).toContain('(PDF)');
    expect(html).not.toContain('XLSX');
  });

  it('says so when there is nothing yet, and while a stop is on its way', () => {
    expect(renderToString(<RunsTable runs={[]} onChange={() => {}} />)).toContain('You have not run a report yet.');
    const stopping = renderToString(
      <RunsTable runs={[run({ status: 'running', cancel_requested: true, document_id: null })]} onChange={() => {}} />,
    );
    expect(stopping).toContain('stopping');
    expect(stopping).not.toContain('>Cancel<');
  });
});

// JUSTIFICATION-A3: new tests for the report centre of issue #229, beside the tests of the screen they extend.
// The report centre (issue #229): the monthly pack, the catalogue in groups, the run list's filter, a pack's two files and
// Run again.
const pack: ReportCatalogueEntry = {
  key: 'lending.monthly_pack',
  title: 'Monthly report pack',
  description: 'All eight lending reports for one month from one reading of your books.',
  permission: 'lending.reports.portfolio',
  formats: ['pack'],
  params: [
    { name: 'period_end', type: 'date', required: false, description: 'The last day the pack covers.', values: [] },
    { name: 'branch_id', type: 'uuid_list', required: false, description: 'Branches to include.', values: [] },
  ],
};

const register: ReportCatalogueEntry = {
  ...par,
  key: 'lending.collateral_register',
  title: 'Collateral register',
  permission: 'lending.reports.members',
  params: [],
};

const packRun = (over: Partial<ReportRun>): ReportRun =>
  run({
    report_key: 'lending.monthly_pack',
    title: 'Monthly report pack',
    format: 'pack',
    params: { period_end: '2026-10-31' },
    as_of: '2026-10-31',
    files: [
      { document_id: '00000000-0000-4000-8000-0000000000d1', format: 'pdf' },
      { document_id: '00000000-0000-4000-8000-0000000000d2', format: 'xlsx' },
    ],
    ...over,
  });

describe('the report centre', () => {
  it('groups the reports by what they are about and keeps the pack out of the list', () => {
    const groups = grouped([register, par, pack]);
    expect(groups.map((g) => g.title)).toEqual(['Loan book', 'Members and collateral']);
    expect(groups.flatMap((g) => g.entries.map((e) => e.key))).not.toContain('lending.monthly_pack');
    const html = renderToString(<CatalogueList entries={[register, par, pack]} branchIds={undefined} onQueued={() => {}} />);
    expect(html.indexOf('Loan book')).toBeLessThan(html.indexOf('Members and collateral'));
    expect(html).toContain('<details');
    expect(html).not.toContain('Monthly report pack');
  });

  it('offers the pack with a period end and one button, no file type to choose', () => {
    const html = renderToString(<PackCard entry={pack} branchIds={undefined} onQueued={() => {}} />);
    expect(html).toContain('Monthly report pack');
    expect(html).toContain('Period end');
    expect(html).toContain('type="date"');
    expect(html).toContain('Run the pack');
    expect(html).not.toContain('File type');
    expect(html).not.toMatch(/style="/);
  });

  it('offers a pack as a PDF and an Excel workbook, each with its own download', () => {
    const r = packRun({});
    const files = filesOf(r);
    expect(files.map((f) => f.format)).toEqual(['pdf', 'xlsx']);
    expect(downloadLabel(files, files[0]!)).toBe('Download PDF');
    expect(downloadLabel(files, files[1]!)).toBe('Download Excel');
    const html = renderToString(<RunsTable runs={[r]} onChange={() => {}} />);
    expect(html).toContain('(PDF and Excel)');
    expect(html).toContain('>Download PDF<');
    expect(html).toContain('>Download Excel<');
    expect(html).not.toContain('>Download<');
  });

  it('writes the date a report stands at as the reports print it', () => {
    expect(reportDate('2026-10-08')).toBe('8 Oct 2026');
    expect(reportDate('2026-12-31')).toBe('31 Dec 2026');
    expect(reportDate(null)).toBe('');
    const html = renderToString(<RunsTable runs={[packRun({})]} onChange={() => {}} />);
    expect(html).toContain('31 Oct 2026');
    expect(html).not.toContain('2026-10-31<');
  });

  it('offers no file for an expired pack or one still being made', () => {
    expect(filesOf(packRun({ expired: true }))).toEqual([]);
    expect(filesOf(packRun({ status: 'running', files: [] }))).toEqual([]);
    // A run from before files were listed still offers its one file.
    expect(filesOf(run({ files: undefined }))).toEqual([{ document_id: '00000000-0000-4000-8000-0000000000d1', format: 'csv' }]);
  });

  it('offers Run again for a finished report the user may still run, never for an open one', () => {
    const html = renderToString(
      <RunsTable
        runs={[
          run({}),
          run({ id: 'b', status: 'failed', error: 'timeout', document_id: null }),
          run({ id: 'c', status: 'queued', document_id: null }),
          run({ id: 'd', report_key: 'lending.gone', status: 'ready' }),
        ]}
        onChange={() => {}}
        canRun={(key) => key === 'lending.par'}
      />,
    );
    expect(html.match(/>Run again</g)).toHaveLength(2);
  });

  it('filters the runs by status, a cancelled run under All only', () => {
    expect(shows({ status: 'queued' }, 'open')).toBe(true);
    expect(shows({ status: 'running' }, 'open')).toBe(true);
    expect(shows({ status: 'ready' }, 'open')).toBe(false);
    expect(shows({ status: 'failed' }, 'failed')).toBe(true);
    expect(shows({ status: 'cancelled' }, 'ready')).toBe(false);
    expect(shows({ status: 'cancelled' }, 'all')).toBe(true);
    const html = renderToString(<RunsTable runs={[run({})]} onChange={() => {}} />);
    expect(html).toContain('In progress');
    expect(html).toContain('aria-label="Show"');
  });

  it('has one line and one action when there is no run yet', () => {
    const html = renderToString(<RunsTable runs={[]} onChange={() => {}} />);
    expect(html).toContain('You have not run a report yet.');
    expect(html.match(/<button/g)).toHaveLength(1);
    expect(html).toContain('Choose a report');
  });
});

describe('words and sizes', () => {
  it('reads a parameter name as a label', () => {
    expect(paramLabel('as_at')).toBe('As at');
    expect(paramLabel('group_by')).toBe('Group by');
  });

  it('reads a choice in words, the arrears buckets as how late (issue #222)', () => {
    expect(words('none')).toBe('No grouping');
    expect(words('branch')).toBe('Branch');
    expect(words('1_30')).toBe('1 to 30 days late');
    expect(words('61_90')).toBe('61 to 90 days late');
    expect(words('over_90')).toBe('Over 90 days late');
  });

  it('writes rows and size in words', () => {
    expect(sizeText({ row_count: 1, size_bytes: 120 })).toBe('1 row, 120 B');
    expect(sizeText({ row_count: 3, size_bytes: 5 * 1024 * 1024 })).toBe('3 rows, 5.0 MB');
    expect(sizeText({ row_count: null, size_bytes: null })).toBe('');
  });
});
