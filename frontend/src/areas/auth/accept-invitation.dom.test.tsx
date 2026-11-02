// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Issue #122: the invitation page says an error in an error toast as well as in the error line, which stays on the page and
// is the one read out (the toast is silent). openapi-fetch keeps the fetch it finds when the client is created, so the stand-in
// is put in place before anything is imported. The token and the password are fabricated.

const fetchImpl = vi.hoisted(() => {
  const state: { next: (request: Request) => Promise<Response>; seen: Request[] } = {
    next: async () => new Response('{}', { status: 200 }),
    seen: [],
  };
  const Base = globalThis.Request;
  globalThis.Request = class extends Base {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
    }
  } as typeof Request;
  globalThis.fetch = ((input: Request | string, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init);
    state.seen.push(request);
    return state.next(request);
  }) as typeof fetch;
  return state;
});

import { router as appRouter } from '../../app/router';
import { AcceptInvitation } from './accept-invitation';

afterEach(cleanup);

const TOKEN = 'fabricated-invitation-token-0001';
const PASSWORD = 'Fabricated-pass-2026';

function page() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/accept-invitation'] }) });
  return render(
    <RouterContextProvider router={router}>
      <AcceptInvitation token={TOKEN} />
    </RouterContextProvider>,
  );
}

function choose(password: string, confirm: string) {
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('Type it again'), { target: { value: confirm } });
  fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }));
}

describe('an invitation that fails (#122)', () => {
  it('raises an error toast when the server refuses the password, with the words of the error line', async () => {
    fetchImpl.seen = [];
    fetchImpl.next = () =>
      Promise.resolve(new Response(JSON.stringify({ status: 500 }), { status: 500, headers: { 'content-type': 'application/problem+json' } }));
    const { container } = page();
    choose(PASSWORD, PASSWORD);
    const words = 'Your password could not be saved. Try again.';
    expect((await screen.findByRole('alert')).textContent).toBe(words);
    const toast = container.querySelector('.toast.toast-error');
    expect(toast?.textContent).toContain(words);
    expect(toast?.getAttribute('aria-hidden')).toBe('true');
    expect(fetchImpl.seen).toHaveLength(1);
  });

  it('raises an error toast when the two passwords differ, without asking the server', async () => {
    fetchImpl.seen = [];
    const { container } = page();
    choose(PASSWORD, `${PASSWORD}x`);
    const words = 'Check the password rules below the boxes.';
    expect((await screen.findByRole('alert')).textContent).toBe(words);
    const toast = container.querySelector('.toast.toast-error');
    expect(toast?.textContent).toContain(words);
    expect(toast?.getAttribute('aria-hidden')).toBe('true');
    expect(toast?.querySelectorAll('button, a, input, [tabindex]')).toHaveLength(0);
    expect(fetchImpl.seen).toHaveLength(0);
  });

  // JUSTIFICATION-A3: the review of #739 asks for these cases (no stale toast, one toast); they are tests, not mechanism.
  const refused = () => Promise.resolve(new Response(JSON.stringify({ status: 500 }), { status: 500, headers: { 'content-type': 'application/problem+json' } }));
  const saved = () =>
    Promise.resolve(new Response(JSON.stringify({ status: 'signed_in', access_token: 'fabricated-access-token' }), { status: 200, headers: { 'content-type': 'application/json' } }));
  const save = () => fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }));

  it('drops the error toast when the next try works, so the two-step offer shows no old error', async () => {
    fetchImpl.seen = [];
    const answers = [refused, saved];
    fetchImpl.next = () => (answers.shift() ?? saved)();
    const { container } = page();
    choose(PASSWORD, PASSWORD);
    await screen.findByRole('alert');
    expect(container.querySelector('.toast-error')).not.toBeNull();
    save();
    await screen.findByRole('heading', { name: 'Protect your account' });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(container.querySelector('.toast-error')).toBeNull();
  });

  it('keeps one error toast after two refusals', async () => {
    fetchImpl.seen = [];
    fetchImpl.next = refused;
    const { container } = page();
    choose(PASSWORD, PASSWORD);
    await screen.findByRole('alert');
    save();
    await waitFor(() => expect(fetchImpl.seen).toHaveLength(2));
    await screen.findByRole('alert');
    expect(container.querySelectorAll('.toast')).toHaveLength(1);
    expect(container.querySelector('.toast')?.className).toBe('toast toast-error');
  });
});
