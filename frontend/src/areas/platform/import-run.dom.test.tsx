// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ImportRun, PlatformRunState, RunAction } from '../../api/imports';
import { router as appRouter } from '../../app/router';

// The stop button of the operator portal (issue #141, spec sections 3.7 and 11): while an import is being brought in the one action
// is Stop; it asks first and sends nothing until confirmed, sends once even when pressed twice, then says the import stops before
// its next file and offers Resume. A run that is ready, approved and has a checked dump offers Start; a run without its dump check
// offers neither. Fabricated data only.

const base: ImportRun = {
  id: 'r-1',
  status: 'committing',
  // Started a minute ago: a healthy run, not stale (issue #897).
  committing_at: new Date(Date.now() - 60_000).toISOString(),
  version: 4,
  halt_requested: false,
  commit_approved: true,
  pre_import_dump_file: 'test-pre-import.dump',
  pre_import_dump_verified: true,
  trading_override_set: false,
  trading_override_approved: false,
};
let state: PlatformRunState;
const sent: RunAction[] = [];
let release: (() => void) | null = null;
/** When set, the server refuses every action with a 409 and these words. */
let refusal: string | null = null;
let refusalCode = 'IMPORT_IN_PROGRESS';

vi.mock('../../api/imports', async (original) => {
  const real = await original<typeof import('../../api/imports')>();
  return {
    ...real,
    platformRun: () => Promise.resolve(state),
    platformAction: (_slug: string, _run: string, action: RunAction) => {
      sent.push(action);
      if (refusal) return Promise.reject(new real.ImportError(refusal, refusalCode));
      return new Promise<PlatformRunState>((resolve) => {
        release = () => {
          state = { ...state, run: { ...state.run, halt_requested: action === 'stop' ? true : state.run?.halt_requested } };
          resolve(state);
        };
      });
    },
  };
});

let permissions: string[] = [];
vi.mock('../../api/onboarding', async (original) => {
  const real = await original<typeof import('../../api/onboarding')>();
  return { ...real, platformMe: () => Promise.resolve({ full_name: 'Test Operator 01', permissions }) };
});

const { OperatorImportRun, operatorAction } = await import('./import-run');

function show() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/platform/imports'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <OperatorImportRun slug="test-shop-five" runId="r-1" />
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

beforeEach(() => {
  state = { tenant_slug: 'test-shop-five', run: { ...base }, commit_enabled: true };
  sent.length = 0;
  release = null;
  refusal = null;
  refusalCode = 'IMPORT_IN_PROGRESS';
  permissions = ['platform.tenants.read', 'platform.tenants.manage'];
});

afterEach(() => cleanup());

describe('the stop button', () => {
  it('asks first, sends one stop, then says it stops before the next file and offers Resume', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Stop the import' }));
    expect(sent).toEqual([]);
    expect(screen.getByText(/Stop this import before its next file/)).toBeTruthy();

    const confirm = screen.getByRole('button', { name: 'Yes, stop the import' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    await waitFor(() => expect(sent).toEqual(['stop']));
    release?.();

    expect(await screen.findByText('Stop pressed. The import stops before its next file.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Stop the import' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Resume the import' })).toBeTruthy();
    expect(sent).toEqual(['stop']);
  });

  it('cancels without sending anything', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Stop the import' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(sent).toEqual([]);
    expect(screen.getByRole('button', { name: 'Stop the import' })).toBeTruthy();
  });
});

describe('a run that stopped by itself (review of e3f95b2 on #895)', () => {
  it('offers no Resume on a healthy run being brought in (issue #897)', async () => {
    show();
    expect(await screen.findByRole('button', { name: 'Stop the import' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Stopped by itself?' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Resume the import' })).toBeNull();
  });

  it('offers no Resume on a run that has no committing_at (review of a5168f7)', async () => {
    state = { ...state, run: { ...base, committing_at: undefined } };
    show();
    expect(await screen.findByRole('button', { name: 'Stop the import' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Stopped by itself?' })).toBeNull();
  });

  it('offers no Resume after a refusal that is not IMPORT_IN_PROGRESS (review of a5168f7)', async () => {
    refusal = 'Only an active operator can do this.';
    refusalCode = 'operator_required';
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Stop the import' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, stop the import' }));
    expect(await screen.findByText(refusal)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('heading', { name: 'Stopped by itself?' })).toBeNull();
  });

  it('offers Resume once the server answered IMPORT_IN_PROGRESS on it (issue #897)', async () => {
    refusal = 'Another import is being committed for this business; wait for it or stop it.';
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Stop the import' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, stop the import' }));
    expect(await screen.findByText(refusal)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(await screen.findByRole('heading', { name: 'Stopped by itself?' })).toBeTruthy();
  });

  it('offers Resume beside Stop once it has been committing past one file’s time limit, and sends one resume', async () => {
    state = { ...state, run: { ...base, committing_at: new Date(Date.now() - 11 * 60_000).toISOString() } };
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Resume the import' }));
    await waitFor(() => expect(sent).toEqual(['resume']));
    release?.();
    expect(await screen.findByText('Resumed. It goes on from the first file not yet brought in.')).toBeTruthy();
  });

  it('offers no Resume once the stop was pressed, but the one main action', async () => {
    state = { ...state, run: { ...base, halt_requested: true } };
    show();
    expect(await screen.findAllByRole('button', { name: 'Resume the import' })).toHaveLength(1);
    expect(screen.queryByRole('heading', { name: 'Stopped by itself?' })).toBeNull();
  });
});

describe('the route permission (review of 1c9568b on #895)', () => {
  it('shows a read-only operator the facts and no action', async () => {
    permissions = ['platform.tenants.read'];
    show();
    expect(await screen.findByRole('heading', { name: 'Before it starts' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Stop the import' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Business already trading' })).toBeNull();
  });

  it('shows an operator without the read permission only a refusal', async () => {
    permissions = [];
    show();
    expect(await screen.findByText('Your operator account cannot see the imports.')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Before it starts' })).toBeNull();
  });
});

describe('the operator’s one action', () => {
  it('is Stop while it is being brought in, Resume once stopped, none while every import is stopped', () => {
    expect(operatorAction(base, true)?.action).toBe('stop');
    expect(operatorAction({ ...base, halt_requested: true }, true)?.action).toBe('resume');
    expect(operatorAction({ ...base, halt_requested: true }, false)).toBeNull();
  });

  it('is Start only for a ready run that a second person approved, with its dump checked', () => {
    const ready = { ...base, status: 'ready' };
    expect(operatorAction(ready, true)?.action).toBe('start');
    expect(operatorAction({ ...ready, pre_import_dump_verified: false }, true)).toBeNull();
    expect(operatorAction({ ...ready, commit_approved: false }, true)).toBeNull();
    expect(operatorAction(ready, false)).toBeNull();
  });
});
