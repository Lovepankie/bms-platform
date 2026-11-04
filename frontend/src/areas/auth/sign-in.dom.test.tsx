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

// JUSTIFICATION-A3: issue #823 asks for a DOM test of the sign-in field; these are new test cases, not new mechanism.
// Issue #823: an email typed or pasted with spaces inside, before or after it, or with a non-breaking space, is sent without them;
// a phone keeps its spaces, because the server reads a phone by its own format check. The box still shows what was typed.
describe('spaces typed in the email or phone box (#823)', () => {
  const NBSP = String.fromCharCode(0xa0);
  const sentLogin = async () => {
    await waitFor(() => expect(fetchImpl.seen).toHaveLength(1));
    const body = (await fetchImpl.seen[0]!.clone().json()) as { login: string };
    return body.login;
  };

  it.each([
    ['inside', 'staff 01@exam ple.test'],
    ['before and after', '   staff01@example.test  '],
    ['a non-breaking space', `staff01${NBSP}@example.test${NBSP}`],
    ['capitals and a tab', 'Staff01@Example.TEST\t'],
  ])('sends the email without spaces when they are %s', async (_name, typed) => {
    fetchImpl.seen = [];
    fetchImpl.next = () => new Promise<Response>(() => undefined);
    shell();
    fireEvent.change(screen.getByLabelText('Email or phone'), { target: { value: typed } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-password-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await sentLogin()).toBe('staff01@example.test');
    expect((screen.getByLabelText('Email or phone') as HTMLInputElement).value).toBe(typed);
  });

  it('sends a phone with its spaces, only trimmed', async () => {
    fetchImpl.seen = [];
    fetchImpl.next = () => new Promise<Response>(() => undefined);
    shell();
    fireEvent.change(screen.getByLabelText('Email or phone'), { target: { value: ' 0700 000 001 ' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-password-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await sentLogin()).toBe('0700 000 001');
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
    // The error line; the silent error toast says the same words (#122).
    expect((await screen.findByRole('alert')).textContent).toBe('The app is updating. Nothing was saved yet. Try again in a minute.');
    expect(screen.queryByText(/Something went wrong/)).toBeNull();
    expect((screen.getByLabelText('Email or phone') as HTMLInputElement).value).toBe('staff01@example.test');
    expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('test-password-01');
    // Sign-in carries no Idempotency-Key, so it is not sent again by itself.
    expect(fetchImpl.seen).toHaveLength(1);
  });
});

// JUSTIFICATION-A3: issue #122 asks for tests of the new error toast on this screen; they are new cases, not new mechanism.
// Issue #122: a sign-in that fails says so in an error toast as well as in the error line, which stays on the page and is the
// one that is read out (the toast is silent, so the words are not said twice).
describe('a sign-in that fails (#122)', () => {
  const typeAndSubmit = () => {
    fireEvent.change(screen.getByLabelText('Email or phone'), { target: { value: 'staff01@example.test' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-password-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  };
  const answer = (status: number, body: object) => () =>
    Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/problem+json' } }));

  it('raises an error toast with the same words as the error line', async () => {
    fetchImpl.seen = [];
    fetchImpl.next = answer(401, { code: 'invalid_credentials', status: 401 });
    const { container } = shell();
    typeAndSubmit();
    const words = 'That email, phone or password is not right. Check them and try again.';
    expect((await screen.findByRole('alert')).textContent).toBe(words);
    const toast = container.querySelector('.toast.toast-error');
    expect(toast?.textContent).toContain(words);
    expect(toast?.getAttribute('aria-hidden')).toBe('true');
  });

  it('raises an error toast for a code that is not six digits or a recovery code, without asking the server', async () => {
    fetchImpl.seen = [];
    fetchImpl.next = answer(200, { status: 'mfa_required', mfa_token: 'fabricated-mfa-token' });
    const { container } = shell();
    typeAndSubmit();
    fireEvent.change(await screen.findByLabelText('Code'), { target: { value: '12' } });
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    const words = 'Enter the 6 digit code from your app, or one recovery code.';
    expect((await screen.findByRole('alert')).textContent).toBe(words);
    const toast = container.querySelector('.toast.toast-error');
    expect(toast?.textContent).toContain(words);
    expect(toast?.getAttribute('aria-hidden')).toBe('true');
    expect(fetchImpl.seen).toHaveLength(1);
  });

  // JUSTIFICATION-A3: the review of #739 asks for these cases (no stale toast, one toast, no button); they are tests, not mechanism.
  it('drops the error toast when the next try works, so the two-step step shows no old error', async () => {
    fetchImpl.seen = [];
    const twoStep = answer(200, { status: 'mfa_required', mfa_token: 'fabricated-mfa-token' });
    const answers = [answer(401, { code: 'invalid_credentials', status: 401 }), twoStep];
    fetchImpl.next = () => (answers.shift() ?? twoStep)();
    const { container } = shell();
    typeAndSubmit();
    await screen.findByRole('alert');
    expect(container.querySelector('.toast-error')).not.toBeNull();
    typeAndSubmit();
    await screen.findByLabelText('Code');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(container.querySelector('.toast-error')).toBeNull();
  });

  it('keeps one error toast, with no button in it, after two refusals; a lockout is said the same way', async () => {
    fetchImpl.seen = [];
    const lockedOut = answer(423, { code: 'account_locked', status: 423 });
    const answers = [answer(401, { code: 'invalid_credentials', status: 401 }), lockedOut];
    fetchImpl.next = () => (answers.shift() ?? lockedOut)();
    const { container } = shell();
    typeAndSubmit();
    await screen.findByText('That email, phone or password is not right. Check them and try again.', { selector: '[role="alert"]' });
    typeAndSubmit();
    const locked = 'Too many tries. Your account is locked for 15 minutes. Try again later.';
    await screen.findByText(locked, { selector: '[role="alert"]' });
    const toasts = container.querySelectorAll('.toast');
    expect(toasts).toHaveLength(1);
    // The one dark neutral pill of rule 15 (.toast); the error kind only colours its icon (.toast-error .toast-icon).
    expect(toasts[0]?.className).toBe('toast toast-error');
    expect(toasts[0]?.textContent).toContain(locked);
    expect(toasts[0]?.querySelectorAll('button, a, input, [tabindex]')).toHaveLength(0);
  });
});
