// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// The staff sign-in button in a browser (issue #456): while the sign-in request is on its way the button is disabled, busy and
// shows the spinner, and a double click sends one request. openapi-fetch keeps the fetch it finds when the client is created, so
// the stand-in is put in place before anything is imported. The login and password are fabricated.

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
import { SignIn } from './sign-in';

afterEach(cleanup);

function shell() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/sign-in'] }) });
  return render(
    <RouterContextProvider router={router}>
      <SignIn />
    </RouterContextProvider>,
  );
}

describe('the staff sign-in button (#456)', () => {
  it('keeps its label, is busy, aria-disabled and spinning while the sign-in is on its way, and a double click signs in once', async () => {
    fetchImpl.seen = [];
    fetchImpl.next = () => new Promise<Response>(() => undefined);
    shell();
    fireEvent.change(screen.getByLabelText('Email or phone'), { target: { value: 'staff01@example.test' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-password-01' } });
    const button = screen.getByRole('button', { name: 'Sign in' });
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
    const busy = screen.getByRole('button', { name: 'Sign in' });
    expect(busy).toBe(button);
    expect(busy.getAttribute('aria-disabled')).toBe('true');
    expect(busy.querySelector('.busy-spinner')).not.toBeNull();
    fireEvent.click(busy);
    await new Promise((resolve) => setTimeout(resolve, 10));
    const logins = fetchImpl.seen.filter((r) => new URL(r.url).pathname === '/api/v1/auth/staff/login');
    expect(logins).toHaveLength(1);
    expect(fetchImpl.seen).toHaveLength(1);
  });
});

// Issue #496: while a deploy switches the server over, the proxy answers 502 or the connection drops. The page says the app
// is updating instead of 'Something went wrong', and what was typed stays in the form for the next try.
describe('signing in while the app is updating (#496)', () => {
  const typeAndSubmit = () => {
    fireEvent.change(screen.getByLabelText('Email or phone'), { target: { value: 'staff01@example.test' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-password-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  };

  it.each([
    ['a 502 from the proxy', () => Promise.resolve(new Response(null, { status: 502 }))],
    ['a 504 page from Cloudflare', () => Promise.resolve(new Response('<html>Gateway time-out</html>', { status: 504, headers: { 'content-type': 'text/html' } }))],
    ['a dropped connection', () => Promise.reject(new TypeError('Failed to fetch'))],
  ])('says the app is updating on %s and keeps the form filled', async (_name, answer) => {
    fetchImpl.seen = [];
    fetchImpl.next = answer;
    shell();
    typeAndSubmit();
    await screen.findByText('The app is updating. Nothing was saved yet. Try again in a minute.');
    expect(screen.queryByText(/Something went wrong/)).toBeNull();
    expect((screen.getByLabelText('Email or phone') as HTMLInputElement).value).toBe('staff01@example.test');
    expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('test-password-01');
    // Sign-in carries no Idempotency-Key, so it is not sent again by itself.
    expect(fetchImpl.seen).toHaveLength(1);
  });
});
