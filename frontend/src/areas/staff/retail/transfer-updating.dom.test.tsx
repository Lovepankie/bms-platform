// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Move stock while a deploy switches the server over (issue #496): the save carries the draft's Idempotency-Key, so it is
// sent once more by itself after a pause; when that meets the gap too, the screen says the app is updating and the form
// keeps the destination, the note and the line. openapi-fetch keeps the fetch it finds when the client is created, so the
// stand-in is put in place before anything is imported. Names, codes and quantities are fabricated.

const fetchImpl = vi.hoisted(() => {
  const state: { seen: Request[] } = { seen: [] };
  const Base = globalThis.Request;
  globalThis.Request = class extends Base {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
    }
  } as typeof Request;
  globalThis.fetch = ((input: Request | string, init?: RequestInit) => {
    // A clone is a plain Request, not the subclass above, so the check is against the base class.
    const request = input instanceof Base ? input : new Request(new URL(String(input), 'http://localhost'), init);
    state.seen.push(request);
    // What Caddy answers when it could not connect to any api container (deploy/caddy/upstreams.caddy).
    if (request.method === 'POST') {
      return Promise.resolve(new Response('{"code":"app_updating"}', { status: 502, headers: { 'content-type': 'application/problem+json', 'Bms-Not-Delivered': '1' } }));
    }
    return Promise.resolve(new Response('{"items":[]}', { status: 200, headers: { 'content-type': 'application/json' } }));
  }) as typeof fetch;
  return state;
});

import { APP_UPDATING, updatingRetry } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { TransferForm } from './transfer';

updatingRetry.delayMs = 0;
afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

describe('Move stock while the app is updating (#496)', () => {
  it('sends the move once more with the same key, then says the app is updating and keeps the form filled', async () => {
    const me = mockMe('admin');
    const from = me.branches?.[0]?.id ?? '';
    const to = me.branches?.[1]?.id ?? '';
    const product = { id: 'p1', code: 'P003', description: 'LED bulb 9W screw', unit: 'piece', sell_minor: 6000, active: true, qty: '12.000' };
    const draft = { toBranchId: to, transferDate: '2026-10-23', note: 'Test move 01', lines: [{ product, qty: '2' }] };
    sessionStorage.setItem(`retail-draft:transfer:${me.user_id}:${from}`, JSON.stringify({ key: 'test-key-496', draft }));
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
    render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <StaffContext.Provider value={{ me, branch: from }}>
            <TransferForm fromBranchId={from} />
          </StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Move stock' }));
    await screen.findByText(APP_UPDATING);
    expect(screen.queryByText(/The server had a problem/)).toBeNull();
    const moves = fetchImpl.seen.filter((r) => r.method === 'POST');
    expect(moves).toHaveLength(2);
    expect(moves.map((r) => r.headers.get('Idempotency-Key'))).toEqual(['test-key-496', 'test-key-496']);
    await waitFor(() => expect((screen.getByLabelText('Note (optional)') as HTMLInputElement).value).toBe('Test move 01'));
    expect((screen.getByLabelText('To branch') as HTMLSelectElement).value).toBe(to);
    expect(screen.getByText(/LED bulb 9W screw/)).toBeTruthy();
    expect(JSON.parse(sessionStorage.getItem(`retail-draft:transfer:${me.user_id}:${from}`) ?? '{}').key).toBe('test-key-496');
  });
});
