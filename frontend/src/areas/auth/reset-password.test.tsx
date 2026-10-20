import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { router as appRouter } from '../../app/router';
import { invitationToken, plainProblem } from '../../auth/codes';
import { ForgotPassword } from './forgot-password';
import { ResetPassword, type Step } from './reset-password';

// The two pages of the forgotten password (FR-IAM-15, ADR-042), one test per state. Markup only (the test setup has no DOM). The
// token, key and codes are fabricated.

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/forgot-password'] }) });
const page = (node: ReactElement) => renderToStaticMarkup(<RouterContextProvider router={router}>{node}</RouterContextProvider>);
const TOKEN = 'abcdefghijklmnopqrstuvwxyz0123';
const at = (initial: Step) => page(<ResetPassword token={TOKEN} initial={initial} />);
const enrolment = { secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', otpauth_uri: 'otpauth://totp/BMS:test01%40example.test?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=BMS' };

describe('forgot password', () => {
  const html = page(<ForgotPassword />);

  it('asks for one thing, with the label tied to the box', () => {
    expect(html).toContain('<h1 tabindex="-1">Forgot your password?</h1>');
    expect(html).toContain('<label for="forgot-login">Email or phone</label>');
    expect(html).toContain('id="forgot-login"');
    expect(html).toContain('autoComplete="username"');
    expect(html).toContain('Send me a link');
  });

  it('promises nothing about whether an account exists', () => {
    expect(html).toContain('If an account matches');
    expect(html).not.toMatch(/no account|not found|unknown|does not exist/i);
  });

  it('leads back to sign-in', () => {
    expect(html).toContain('href="/sign-in"');
  });

  it('answers with the same words and a way back once sent', () => {
    const sent = page(<ForgotPassword initial="sent" />);
    expect(sent).toContain('Check your email');
    expect(sent).toContain('If an account matches');
    expect(sent).toContain('one hour');
    expect(sent).toContain('ask your administrator');
    expect(sent).toContain('Back to sign-in');
    expect(sent).not.toContain('forgot-login');
  });
});

describe('reset password', () => {
  it('asks for a new password twice, with the rules the server checks', () => {
    const html = at({ kind: 'password' });
    expect(html).toContain('<h1 tabindex="-1">Choose a new password</h1>');
    expect(html).toContain('<label for="new-password">New password</label>');
    expect(html).toContain('<label for="confirm-password">Type it again</label>');
    expect(html.match(/autoComplete="new-password"/g)?.length).toBe(2);
    expect(html).toContain('At least 10 characters');
    expect(html).toContain('Not a common password, and not your email or phone');
    expect(html).toContain('Save and sign in');
    expect(html).toContain('signed out');
  });

  it('never prints the token', () => {
    expect(at({ kind: 'password' })).not.toContain(TOKEN);
  });

  it('tells a user with a broken link how to get a new one', () => {
    const broken = page(<ResetPassword token={null} />);
    expect(broken).toContain('This reset link is not complete.');
    expect(broken).toContain('href="/forgot-password"');
    expect(broken).toContain('An administrator can also give you a link');
    expect(broken).not.toContain('new-password');
  });

  it('asks for the code of a user who has two-step sign-in, whom the reset must not let past it', () => {
    const html = at({ kind: 'challenge', mfaToken: 'fabricated-mfa-token' });
    expect(html).toContain('Enter your code');
    expect(html).toContain('<label for="reset-code">Code</label>');
    expect(html).toContain('autoComplete="one-time-code"');
    expect(html).toContain('recovery codes');
    expect(html).not.toContain('fabricated-mfa-token');
  });

  it('sets up two-step sign-in for a role that requires it, with no way to skip', () => {
    const html = at({ kind: 'enrol', enrolment, mfaToken: 'fabricated-mfa-token' });
    expect(html).toContain('Set up two-step sign-in');
    expect(html).toContain('class="qr-code"');
    expect(html).not.toContain('Skip for now');
    expect(html).not.toContain('fabricated-mfa-token');
  });

  it('sends a locked-out user to sign-in with the reason', () => {
    const html = at({ kind: 'sign-in' });
    expect(html).toContain('Your password is changed');
    expect(html).toContain('wait 15 minutes');
    expect(html).toContain('href="/sign-in"');
  });
});

describe('the words for the reset problems', () => {
  it('maps the server codes to sentences that say what to do', () => {
    expect(plainProblem({ code: 'reset_link_invalid' }, 'x')).toContain('Ask for a new one');
    expect(plainProblem({ code: 'reset_link_expired' }, 'x')).toContain('one hour');
    expect(plainProblem({ code: 'rate_limited' }, 'x')).toContain('Wait a few minutes');
    expect(plainProblem({ code: 'weak_password', detail: 'This password is too common.' }, 'x')).toBe('This password is too common.');
  });

  it('reads the token from the fragment and refuses a short one', () => {
    expect(invitationToken(`#token=${TOKEN}`)).toBe(TOKEN);
    expect(invitationToken('#token=short')).toBeNull();
  });
});
