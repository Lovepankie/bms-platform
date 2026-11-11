// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { BulkResolveRequest, ImportIssue, RunMetrics } from '../../../api/imports';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Large problem groups (issue #897): the screen lists at most 2,000 open lines, while Fix all acts on every open problem of the
// kind. So the count on the card, on Fix all and on the sheet is the server's, from the run's metrics, and the sheet sends that
// one kind. Fabricated data only.

let listed: ImportIssue[];
let more: boolean;
let metrics: RunMetrics | Promise<RunMetrics>;
const bulk: BulkResolveRequest[] = [];

vi.mock('../../../api/imports', async (original) => {
  const real = await original<typeof import('../../../api/imports')>();
  return {
    ...real,
    openIssues: () => Promise.resolve({ items: listed, more }),
    runMetrics: () => Promise.resolve(metrics),
    // A single fix: the server then counts one fewer open line of the kind.
    resolveIssue: (issueId: string) => {
      listed = listed.filter((i) => i.id !== issueId);
      const m = metrics as RunMetrics;
      metrics = { ...m, issues: (m.issues ?? []).map((c) => ({ ...c, open: (c.open ?? 0) - 1 })) };
      return Promise.resolve();
    },
    resolveAll: (_run: string, body: BulkResolveRequest) => {
      bulk.push(body);
      return Promise.resolve(2500);
    },
  };
});

const { ImportProblems } = await import('./problems');

const me = { ...mockMe('admin'), permissions: [...(mockMe('admin').permissions ?? []), 'core.imports.manage'] } as Me;

function show() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/setup/imports/runs/run-9/problems'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <ImportProblems runId="run-9" />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const line = (n: number): ImportIssue => ({
  id: `i-${n}`,
  code: 'TYPE_INVALID',
  severity: 'blocking',
  line: n + 1,
  field: 'phone',
  version: 1,
  message: 'phone on test-buyers.csv is not a valid phone.',
});

beforeEach(() => {
  // Fewer than the 2,000 a real screen reads, so the test stays quick; the server says 2,500 are open.
  listed = Array.from({ length: 40 }, (_, i) => line(i));
  more = true;
  metrics = { run_id: 'run-9', status: 'in_review', issues: [{ code: 'TYPE_INVALID', severity: 'blocking', total: 2600, open: 2500 }] };
  bulk.length = 0;
});

afterEach(() => cleanup());

describe('a kind with more open problems than the screen lists', () => {
  it('shows the server count on the card, Fix all and the sheet, and fixes that kind', async () => {
    show();
    expect(await screen.findByRole('heading', { name: 'Not the right kind of value (2500)' })).toBeTruthy();
    expect(screen.getByText(/The first 40 lines are listed here/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Fix all 2500 the same way' }));
    expect(await screen.findByText('2500 lines: not the right kind of value.')).toBeTruthy();
    // Not every line is listed, so the suggestion is not offered; leaving them out keeps a reason.
    expect(screen.getByRole('radio', { name: 'Leave the lines out' })).toBeTruthy();
    expect(screen.queryByRole('radio', { name: 'Use the suggestion' })).toBeNull();
    fireEvent.change(screen.getByLabelText(/^Why/), { target: { value: 'Test lines from an old sheet' } });
    fireEvent.click(screen.getByRole('button', { name: 'Fix all 2500' }));
    await waitFor(() => expect(bulk).toEqual([{ code: 'TYPE_INVALID', resolution: 'exclude_row', note: 'Test lines from an old sheet' }]));
  });

  it('counts again after a single fix, so Fix all says what is left (review of a5168f7)', async () => {
    show();
    expect(await screen.findByRole('button', { name: 'Fix all 2500 the same way' })).toBeTruthy();
    fireEvent.click(screen.getByText('See each line'));
    fireEvent.click(screen.getByRole('button', { name: 'Fix line 2' }));
    fireEvent.change(await screen.findByLabelText(/^New value/), { target: { value: '+256700000001' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the fix' }));
    expect(await screen.findByRole('button', { name: 'Fix all 2499 the same way' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Not the right kind of value (2499)' })).toBeTruthy();
  });

  it('says "or more" and offers no Fix all until the server count is in', async () => {
    metrics = new Promise<RunMetrics>(() => {});
    show();
    expect(await screen.findByRole('heading', { name: 'Not the right kind of value (40 or more)' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Fix all/ })).toBeNull();
  });

  it('counts a kind the list holds whole the same way', async () => {
    listed = [line(1), line(2), line(3)];
    more = false;
    metrics = { run_id: 'run-9', status: 'in_review', issues: [{ code: 'TYPE_INVALID', severity: 'blocking', total: 3, open: 3 }] };
    show();
    expect(await screen.findByRole('button', { name: 'Fix all 3 the same way' })).toBeTruthy();
    expect(screen.queryByText(/lines are listed here/)).toBeNull();
  });
});
