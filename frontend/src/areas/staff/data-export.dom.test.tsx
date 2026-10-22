// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// "Download my data" in Business set-up (issue #334 step 1, ADR-074): the card shows when the business's data was last
// exported and emailed, asks for a workbook with an Idempotency-Key, offers the ready file through its signed address, and
// switches the weekly email. Fabricated ids and dates.

const DOC = '00000000-0000-4000-8000-0000000000d1';
const status = {
  weekly_email: true,
  last_export_at: '2026-10-05T03:10:00Z',
  last_emailed_at: null as string | null,
  latest: null as Record<string, unknown> | null,
};
const client = {
  GET: vi.fn(async () => ({ data: { ...status }, response: new Response(null, { status: 200 }) })),
  POST: vi.fn(async (path: string) =>
    path === '/api/v1/data-export/runs'
      ? { data: { id: 'run-1', status: 'queued' }, response: new Response(null, { status: 202 }) }
      : { data: { url: '/files/workbook' }, response: new Response(null, { status: 200 }) },
  ),
  PUT: vi.fn(async (_path: string, init: { body: { weekly_email: boolean } }) => {
    status.weekly_email = init.body.weekly_email;
    return { data: { ...status }, response: new Response(null, { status: 200 }) };
  }),
};

vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()),
  api: new Proxy({}, { get: (_t, name: string) => (client as Record<string, unknown>)[name] }),
}));

const { DataExportCard, exportWording, when } = await import('./data-export');

function show() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <DataExportCard />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  status.weekly_email = true;
  status.latest = null;
});

describe('the Download my data card', () => {
  it('shows the last export and email, and asks for a workbook with an Idempotency-Key', async () => {
    show();
    expect(await screen.findByText(/Last export:/)).toBeTruthy();
    expect(screen.getByText('never')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Download my data' }));
    await waitFor(() => expect(client.POST).toHaveBeenCalled());
    const [path, init] = client.POST.mock.calls[0] as unknown as [string, { params: { header: Record<string, string> } }];
    expect(path).toBe('/api/v1/data-export/runs');
    expect(init.params.header['Idempotency-Key']).toMatch(/^data-export-/);
  });

  it('offers a ready workbook through its signed address', async () => {
    status.latest = { id: 'run-1', status: 'ready', document_id: DOC, expired: false, expires_at: '2026-11-04T03:10:00Z' };
    const assign = vi.fn();
    Object.defineProperty(window, 'location', { value: { ...window.location, assign }, configurable: true });
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Save the workbook' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('/files/workbook'));
    expect(client.POST.mock.calls[0]?.[0]).toBe('/api/v1/documents/{document_id}/download-url');
  });

  it('says when a workbook is being made and when one failed', async () => {
    status.latest = { id: 'run-1', status: 'running', document_id: null, expired: false };
    show();
    expect(await screen.findByText(/Preparing your workbook/)).toBeTruthy();
    cleanup();
    status.latest = { id: 'run-1', status: 'failed', document_id: null, expired: false, error: 'timeout' };
    show();
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('names only the tables of the modules the business has on', () => {
    expect(exportWording(['retail'])).not.toContain('loans');
    expect(exportWording(['lending'])).toBe('members, loans, repayments and arrears');
    expect(exportWording(undefined)).toContain('sales');
    expect(exportWording(undefined)).toContain('loans');
  });

  it('switches the weekly email off', async () => {
    show();
    const box = (await screen.findByRole('checkbox')) as HTMLInputElement;
    expect(box.checked).toBe(true);
    fireEvent.click(box);
    await waitFor(() => expect(client.PUT).toHaveBeenCalledWith('/api/v1/data-export/settings', { body: { weekly_email: false } }));
    expect(await screen.findByText('The weekly email is off.')).toBeTruthy();
  });
});

describe('when', () => {
  it('writes a moment in the business time zone, whatever the device says', () => {
    // 22:30 UTC on 7 October is 01:30 on 8 October in Kampala.
    expect(when('2026-10-07T22:30:00Z', 'Africa/Kampala')).toMatch(/^8 Oct 2026,? 01:30$/);
    expect(when('2026-10-07T22:30:00Z', 'UTC')).toMatch(/^7 Oct 2026,? 22:30$/);
    expect(when(null, 'Africa/Kampala')).toBe('never');
  });
});
