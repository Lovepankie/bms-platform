// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { router as appRouter } from '../app/router';
import { AcceptInvitation } from '../areas/auth/accept-invitation';
import { ForgotPassword } from '../areas/auth/forgot-password';
import { ResetPassword } from '../areas/auth/reset-password';
import { SignIn } from '../areas/auth/sign-in';

// Where the install offer is made (ADR-050): the sign-in page, the invitation page where a new person chooses their first password,
// and the forgot password and reset password pages the links of an email open. Each shows it when the browser says an install is
// possible. The staff area shows it too, under the bar, after sign-in (areas/staff/route.tsx); that layout needs a session and is not
// rendered here.

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/sign-in'] }) });
const page = (node: ReactElement) => render(<RouterContextProvider router={router}>{node}</RouterContextProvider>);
const TOKEN = 'abcdefghijklmnopqrstuvwxyz0123';

beforeAll(() => {
  // The browser's event arrives once, early; this module graph keeps it for every page below.
  const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
    prompt: () => Promise.resolve(),
    userChoice: Promise.resolve({ outcome: 'dismissed' as const }),
  });
  act(() => {
    window.dispatchEvent(event);
  });
});

afterEach(cleanup);

const offered = () => screen.getByRole('complementary', { name: 'Install the app' });

describe('the pages that offer the install', () => {
  it('the staff sign-in page', () => {
    page(<SignIn />);
    expect(offered().textContent).toContain('Install');
  });

  it('the invitation page, where the first password is chosen', () => {
    page(<AcceptInvitation token={TOKEN} />);
    expect(screen.getByRole('heading', { name: 'Choose a password' })).toBeTruthy();
    expect(offered()).toBeTruthy();
  });

  it('the invitation page once the password is set and only a sign-in is left', () => {
    page(<AcceptInvitation token={TOKEN} initial={{ kind: 'sign-in' }} />);
    expect(offered()).toBeTruthy();
  });

  it('the forgot password page, before and after the link is asked for', () => {
    page(<ForgotPassword />);
    expect(offered()).toBeTruthy();
    cleanup();
    page(<ForgotPassword initial="sent" />);
    expect(offered()).toBeTruthy();
  });

  it('the reset password page that the emailed link opens, and its done page', () => {
    page(<ResetPassword token={TOKEN} initial={{ kind: 'password' }} />);
    expect(screen.getByRole('heading', { name: 'Choose a new password' })).toBeTruthy();
    expect(offered()).toBeTruthy();
    cleanup();
    page(<ResetPassword token={TOKEN} initial={{ kind: 'sign-in' }} />);
    expect(offered()).toBeTruthy();
  });

  it('can be waved away from a page and stays away on the next', () => {
    page(<SignIn />);
    expect(offered()).toBeTruthy();
    act(() => {
      screen.getByRole('button', { name: 'Not now' }).click();
    });
    expect(screen.queryByRole('complementary', { name: 'Install the app' })).toBeNull();
    cleanup();
    page(<ForgotPassword />);
    expect(screen.queryByRole('complementary', { name: 'Install the app' })).toBeNull();
    expect(typeof localStorage.getItem('bms.install.dismissed')).toBe('string');
  });
});
