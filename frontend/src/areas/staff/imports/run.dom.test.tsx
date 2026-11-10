// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Certificate, ImportRun, RunMetrics } from '../../../api/imports';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// The sign-off flow of one import (issue #141, spec sections 3.10 and 6.4): once the data is in, the one main action takes the owner
// to Imports to sign off; someone who may not sign is told the owner signs; the sign-off progress shows each step with its date;
// after the sign-off the screen says the copies are deleted within 30 days; once they are, it shows the certificate of deletion
// and saves it as a text file. Before the sign-off, a ready import asks a second person for approval. Fabricated data only.

let run: ImportRun;
let metrics: RunMetrics;
let certificate: Certificate | null = null;
let approvals = 0;
const saved: string[] = [];

vi.mock('../../../api/imports', async (original) => {
  const real = await original<typeof import('../../../api/imports')>();
  return {
    ...real,
    importRun: () => Promise.resolve(run),
    importFiles: () => Promise.resolve([{ id: 'b-1', seq: 1, status: 'committed', file_name: 'test-items.csv', row_count: 1200 }]),
    runMetrics: () => Promise.resolve(metrics),
    dryRunOf: () => Promise.resolve({ at: '2026-11-08T08:00:00Z', current: true, preview_matches: true, sources: [] }),
    certificateOf: () => Promise.resolve(certificate),
    askApproval: () => {
      approvals += 1;
      return Promise.resolve();
    },
    saveFile: (_data: Blob, name: string) => {
      saved.push(name);
    },
  };
});

const { ImportRunScreen } = await import('./run');

const manage = [...(mockMe('admin').permissions ?? []), 'core.imports.manage'];
const importer = { ...mockMe('admin'), permissions: manage, tenant_currency: 'UGX' } as Me;
const owner = { ...importer, permissions: [...manage, 'core.imports.sign_off'] } as Me;

function show(me: Me) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/setup/imports/runs/run-3'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <ImportRunScreen runId="run-3" />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const progress = () => {
  const card = screen.getByRole('heading', { name: 'Sign off' }).closest('section') as HTMLElement;
  return within(card).getAllByRole('definition').map((dd) => dd.textContent);
};

beforeEach(() => {
  run = { id: 'run-3', status: 'committed', version: 9, committed_at: '2026-11-09T10:00:00Z', commit_approved: true };
  metrics = { run_id: 'run-3', status: 'committed', stamps: { committed_at: '2026-11-09T10:00:00Z', reconciled_at: '2026-11-09T10:05:00Z' }, issues: [] };
  certificate = null;
  approvals = 0;
  saved.length = 0;
});

afterEach(() => cleanup());

describe('the sign-off flow', () => {
  it('takes the owner to sign once the data is in, with the progress dated', async () => {
    show(owner);
    const sign = await screen.findByRole('link', { name: 'Compare and sign' });
    expect(sign.getAttribute('href')).toBe('/staff/setup/imports?run=run-3');
    await waitFor(() => expect(progress()).toEqual(['9 Nov 2026', '9 Nov 2026', 'Not yet', 'Not yet']));
  });

  it('tells someone who may not sign that the owner signs', async () => {
    show(importer);
    expect(await screen.findByText(/The owner of the business compares the figures and signs/)).toBeTruthy();
  });

  it('after the sign-off says the copies are deleted within 30 days', async () => {
    run = { ...run, status: 'signed_off' };
    metrics = { ...metrics, status: 'signed_off', stamps: { ...metrics.stamps, signed_off_at: '2026-11-10T09:00:00Z' } };
    show(owner);
    expect(await screen.findByText('Signed. The copies of your files are deleted within 30 days.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Compare and sign' })).toBeNull();
    await waitFor(() => expect(progress()).toEqual(['9 Nov 2026', '9 Nov 2026', '10 Nov 2026', 'Not yet']));
  });

  it('shows the certificate of deletion once the copies are deleted and saves it as text', async () => {
    run = { ...run, status: 'purged' };
    metrics = { ...metrics, status: 'purged', stamps: { ...metrics.stamps, signed_off_at: '2026-11-10T09:00:00Z', purged_at: '2026-12-08T02:00:00Z' } };
    certificate = { id: 'c-1', version: 2, issued_at: '2026-12-08T02:00:00Z', reason: 'operator', dump_confirmed: true, document: 'certificate of deletion\nfile: test-items.csv', sha256: 'c'.repeat(64) };
    show(owner);
    expect(await screen.findByRole('heading', { name: 'Certificate of deletion' })).toBeTruthy();
    expect(screen.getByText(/Every copy is confirmed deleted/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Download the certificate' }));
    expect(saved).toEqual(['certificate-of-deletion-2.txt']);
  });

  it('asks a second person to approve a ready import, once', async () => {
    run = { ...run, status: 'ready', commit_approved: false, committed_at: undefined };
    metrics = { ...metrics, status: 'ready' };
    show(importer);
    fireEvent.click(await screen.findByRole('button', { name: 'Ask for approval' }));
    await waitFor(() => expect(approvals).toBe(1));
  });
});
