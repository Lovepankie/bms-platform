// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// The "Payment reconciliation" screen in a browser (extras slice A5 part 2, issue #224, task #317), with the API client replaced: the report says
// what differs in plain words, a holder of manage can upload the statement and run the reconciliation, a refused file names its lines, a reader
// without manage sees the report and no buttons, and nobody without read sees the page. Every identifier and code is invented.

const statements = { upload: vi.fn(), list: vi.fn(), get: vi.fn(), markOutside: vi.fn() };
const reconciliation = { latest: vi.fn(), run: vi.fn() };

vi.mock('../../../api/payments', async (original) => {
  const real = await original<typeof import('../../../api/payments')>();
  return { ...real, statements, reconciliation };
});

const { FormError } = await import('../../../api/lending-forms');
const { ReconciliationPage } = await import('./reconciliation');

const report = {
  run: { id: 'r1', created_at: '2026-10-19T01:10:00Z', ran_on: '2026-10-19', trigger: 'job', window_from: '2026-06-21', intents_examined: 12, statements_used: 1, finding_count: 2 },
  counts_by_kind: { completed_not_booked: 1, manual_receipt_duplicate: 1 },
  findings: [
    { id: 'f1', kind: 'completed_not_booked', confirmation_code: 'CONF-TEST-1', bms_amount_minor: 520000, currency: 'UGX' },
    { id: 'f2', kind: 'manual_receipt_duplicate', confirmation_code: 'CONF-TEST-2', bms_amount_minor: 310000, provider_amount_minor: 310000, currency: 'UGX' },
  ],
  findings_truncated: false,
};

function shell(permissions: string[]) {
  const me = { ...mockMe('admin'), permissions };
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/payments/reconciliation'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <ReconciliationPage />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const MANAGER = ['core.payments.read', 'core.payments.manage'];

beforeEach(() => {
  reconciliation.latest.mockResolvedValue(report);
});

afterEach(() => {
  cleanup();
  Object.values(statements).forEach((m) => m.mockReset());
  Object.values(reconciliation).forEach((m) => m.mockReset());
});

describe('the report', () => {
  it('says each difference in plain words with the code and the amounts', async () => {
    shell(MANAGER);
    expect(await screen.findByText('Paid but not recorded')).toBeTruthy();
    expect(screen.getByText('Entered by hand and also paid online')).toBeTruthy();
    expect(screen.getByText(/CONF-TEST-1/)).toBeTruthy();
    expect(screen.getAllByTestId('finding')).toHaveLength(2);
  });

  it('says when a run could not read everything and shows no business-wide counts to a branch reader', async () => {
    reconciliation.latest.mockResolvedValue({ ...report, run: { ...report.run, truncated: true, intents_examined: undefined, statements_used: undefined } });
    shell(['core.payments.read']);
    expect(await screen.findByText(/could not read everything/)).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/payments and .* statements looked at/);
  });

  it('says so when nothing differs and when nothing has run', async () => {
    reconciliation.latest.mockResolvedValue({ ...report, findings: [], run: { ...report.run, finding_count: 0 } });
    shell(MANAGER);
    expect(await screen.findByText('Nothing differs.')).toBeTruthy();
    cleanup();
    reconciliation.latest.mockResolvedValue({ findings: [], counts_by_kind: {}, findings_truncated: false });
    shell(MANAGER);
    expect(await screen.findByText('No reconciliation has run yet.')).toBeTruthy();
  });

  it('shows a reader the report and no buttons, and a stranger nothing', async () => {
    shell(['core.payments.read']);
    await screen.findByText('Paid but not recorded');
    expect(screen.queryByRole('button', { name: 'Run it now' })).toBeNull();
    expect(screen.queryByLabelText('Statement file (CSV)')).toBeNull();
    cleanup();
    shell(['lending.loans.read']);
    expect(await screen.findByText('You do not have access to this page.')).toBeTruthy();
  });

  it('runs the reconciliation on request and shows the new report', async () => {
    reconciliation.run.mockResolvedValue({ ...report, findings: [report.findings[0]] });
    shell(MANAGER);
    fireEvent.click(await screen.findByRole('button', { name: 'Run it now' }));
    await waitFor(() => expect(reconciliation.run).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getAllByTestId('finding')).toHaveLength(1));
  });
});

describe('the statement upload', () => {
  const csv = (name = 'statement.csv', size = 100) => new File([new Uint8Array(size)], name, { type: 'text/csv' });

  it('uploads a CSV and shows how each line matched and what fee was recorded', async () => {
    statements.upload.mockResolvedValue({
      statement: { id: 's1', file_name: 'statement.csv', row_count: 2, period_from: '2026-10-16', period_to: '2026-10-16', currency: 'UGX', matched_count: 1, different_count: 0, unmatched_count: 1, fees_posted_minor: 5200 },
      rows: [
        { row_no: 1, confirmation_code: 'CONF-TEST-1', amount_minor: 520000, fee_minor: 5200, match_status: 'matched', fee_status: 'posted' },
        { row_no: 2, confirmation_code: 'CONF-TEST-9', amount_minor: 70000, fee_minor: 0, match_status: 'unmatched', fee_status: 'none' },
      ],
      rows_truncated: false,
    });
    shell(MANAGER);
    fireEvent.change(await screen.findByLabelText('Statement file (CSV)'), { target: { files: [csv()] } });
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await waitFor(() => expect(statements.upload).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/Fee recorded/)).toBeTruthy();
    expect(screen.getByText('No payment of ours')).toBeTruthy();
    expect(screen.getByText('Matches')).toBeTruthy();
  });

  it('refuses a file that is not a CSV or is too large before sending anything', async () => {
    shell(MANAGER);
    const input = await screen.findByLabelText('Statement file (CSV)');
    fireEvent.change(input, { target: { files: [csv('statement.pdf')] } });
    expect(screen.getByText('Use the CSV export of the statement.')).toBeTruthy();
    fireEvent.change(input, { target: { files: [csv('statement.csv', 1_000_001)] } });
    expect(screen.getByText(/larger than 1 MB/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Upload' }) as HTMLButtonElement).disabled).toBe(true);
    expect(statements.upload).not.toHaveBeenCalled();
  });

  it('names the lines of a file the server refused', async () => {
    statements.upload.mockRejectedValue(new FormError('One or more fields are invalid.', 422, 'validation_failed', { 'line 3 amount': 'Not a whole, positive amount in UGX.' }));
    shell(MANAGER);
    fireEvent.change(await screen.findByLabelText('Statement file (CSV)'), { target: { files: [csv()] } });
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }));
    expect(await screen.findByText(/line 3 amount: Not a whole, positive amount in UGX\./)).toBeTruthy();
  });

  it('lets a manager mark a line no payment of ours has as outside this system, with a note', async () => {
    statements.upload.mockResolvedValue({
      statement: { id: 's1', file_name: 'statement.csv', row_count: 1, period_from: '2026-10-16', period_to: '2026-10-16', currency: 'UGX', matched_count: 0, different_count: 0, unmatched_count: 1, fees_posted_minor: 0 },
      rows: [{ row_no: 1, confirmation_code: 'CONF-TEST-9', amount_minor: 70000, fee_minor: 0, match_status: 'unmatched', fee_status: 'none', reviewed_outside: false }],
      rows_truncated: false,
    });
    statements.markOutside.mockResolvedValue({ row_no: 1, reviewed_outside: false, outside_awaiting_confirmation: true });
    shell(MANAGER);
    fireEvent.change(await screen.findByLabelText('Statement file (CSV)'), { target: { files: [csv()] } });
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Not ours' }));
    const save = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('What is it'), { target: { value: 'Sale at the shop' } });
    fireEvent.click(save);
    await waitFor(() => expect(statements.markOutside).toHaveBeenCalledWith('s1', 1, 'Sale at the shop'));
    expect(await screen.findByText(/waiting for a second person to confirm/)).toBeTruthy();
  });

  it('sends the days the export is complete for, both or neither', async () => {
    statements.upload.mockResolvedValue({ statement: { id: 's2', file_name: 'statement.csv', row_count: 1, currency: 'UGX' }, rows: [], rows_truncated: false });
    shell(MANAGER);
    fireEvent.change(await screen.findByLabelText('Statement file (CSV)'), { target: { files: [csv()] } });
    fireEvent.change(screen.getByLabelText('Complete from (optional)'), { target: { value: '2026-10-14' } });
    expect(screen.getByText('Give both days, or neither.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Upload' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Complete to (optional)'), { target: { value: '2026-10-16' } });
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await waitFor(() => expect(statements.upload).toHaveBeenCalledTimes(1));
    expect(statements.upload.mock.calls[0]?.[2]).toEqual({ from: '2026-10-14', to: '2026-10-16' });
  });
});
