import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { router as appRouter } from '../../app/router';
import { groupSecret, passwordChecks, plainProblem, sixDigits } from '../../auth/codes';
import { AcceptInvitation, type Step } from './accept-invitation';
import { ErrorLine, QrCode, RecoveryCodes } from './steps';

// The first-run screens (issue #86, with the two-step choice of #93), one test per step and per
// error state. Markup only (the test setup has no DOM). The key and codes are fabricated.

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/accept-invitation'] }) });
const page = (node: ReactElement) => renderToStaticMarkup(<RouterContextProvider router={router}>{node}</RouterContextProvider>);
const TOKEN = 'abcdefghijklmnopqrstuvwxyz0123';
const at = (initial: Step) => page(<AcceptInvitation token={TOKEN} initial={initial} />);
const enrolment = { secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', otpauth_uri: 'otpauth://totp/BMS:test01%40example.test?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=BMS' };
const CODES = ['AAAAA-BBBBB', 'CCCCC-DDDDD', 'EEEEE-FFFFF', 'GGGGG-HHHHH', 'JJJJJ-KKKKK', 'LLLLL-MMMMM', 'NNNNN-PPPPP', 'QQQQQ-RRRRR', 'SSSSS-TTTTT', 'UUUUU-VVVVV'];

describe('step 1: choose a password', () => {
  const html = at({ kind: 'password' });

  it('says where the user is and asks for one thing', () => {
    expect(html).toContain('Step 1 of 3');
    expect(html).toContain('<h1 tabindex="-1">Choose a password</h1>');
    expect(html).toContain('Save and continue');
  });

  it('ties labels to inputs, asks the browser for a new password, and can show it', () => {
    expect(html).toContain('<label for="new-password">New password</label>');
    expect(html).toContain('<label for="confirm-password">Type it again</label>');
    expect(html.match(/autoComplete="new-password"/g)?.length).toBe(2);
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain('>Show</button>');
  });

  it('lists the rules the server checks, and only those', () => {
    expect(html).toContain('id="password-rules"');
    expect(html).toContain('At least 10 characters');
    expect(html).toContain('Not a common password, and not your email or phone');
    expect(passwordChecks('short', 'short').map((c) => c.ok)).toEqual([false, true, true]);
    expect(passwordChecks('long enough pass', 'long enough pass').every((c) => c.ok)).toBe(true);
    expect(passwordChecks('long enough pass', 'different one!').map((c) => c.ok)).toEqual([true, true, false]);
  });

  it('tells a user with a broken link what to do', () => {
    const broken = page(<AcceptInvitation token={null} />);
    expect(broken).toContain('This invitation link is not complete.');
    expect(broken).toContain('ask your administrator for a new one');
    expect(broken).not.toContain('new-password');
  });
});

describe('step 2: two-step sign-in, recommended not required', () => {
  it('offers to turn it on, with a visible Skip for now', () => {
    const html = at({ kind: 'offer' });
    expect(html).toContain('Step 2 of 3');
    expect(html).toContain('Recommended, not required');
    expect(html).toContain('Turn on two-step sign-in');
    expect(html).toContain('Skip for now');
    expect(html).not.toMatch(/TOTP|otpauth|secret/i);
  });

  it('shows a QR code, an Open in authenticator button and the key in groups of four', () => {
    const html = at({ kind: 'enrol', enrolment, mfaToken: null });
    expect(html).toContain('class="qr-code"');
    expect(html).toContain('role="img" aria-label="QR code to scan with your authenticator app"');
    expect(html).toContain(`href="${enrolment.otpauth_uri.replaceAll('&', '&amp;')}"`);
    expect(html).toContain('Open in authenticator');
    expect(html).toContain('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP');
    expect(html).toContain('Google Authenticator and Microsoft Authenticator');
    expect(html).toContain('Skip for now');
  });

  it('asks for the code with a numeric keypad and the one-time-code hint', () => {
    const html = at({ kind: 'enrol', enrolment, mfaToken: null });
    expect(html).toContain('<label for="totp-code">6 digit code from the app</label>');
    // React's server renderer keeps the camel case names; the browser reads them the same.
    expect(html).toContain('inputMode="numeric"');
    expect(html).toContain('autoComplete="one-time-code"');
    expect(sixDigits('12a 34-56789')).toBe('123456');
  });

  it('has no Skip when the role requires it, and says why', () => {
    const html = at({ kind: 'enrol', enrolment, mfaToken: 'fabricated-mfa-token' });
    expect(html).toContain('Your role needs two-step sign-in, so this step cannot be skipped.');
    expect(html).not.toContain('Skip for now');
    expect(html).not.toContain('fabricated-mfa-token');
  });
});

describe('step 3: recovery codes', () => {
  it('shows the ten codes in a grid with copy, download and a required tick', () => {
    const html = at({ kind: 'codes', codes: CODES });
    expect(html).toContain('Step 3 of 3');
    expect(html.match(/<li><code>/g)?.length).toBe(10);
    expect(html).toContain('Copy all');
    expect(html).toContain('Download as text');
    expect(html).toContain('I have saved these codes');
    expect(html).toMatch(/<button type="button" class="btn-primary btn-lg" disabled="">Continue<\/button>/);
  });

  it('the Continue label can change for sign-in', () => {
    expect(renderToStaticMarkup(<RecoveryCodes codes={CODES} onDone={() => undefined} doneLabel="Go to my work" />)).toContain('Go to my work');
  });
});

describe('error states', () => {
  it('reads out problems in plain words, in an alert that takes focus', () => {
    const html = renderToStaticMarkup(<ErrorLine message="That code is not right." />);
    expect(html).toBe('<p tabindex="-1" role="alert" class="alert alert-danger">That code is not right.</p>');
    expect(renderToStaticMarkup(<ErrorLine message={null} />)).toBe('');
  });

  it('maps the server codes to sentences and never echoes a secret', () => {
    expect(plainProblem({ code: 'invalid_mfa_code', detail: 'The code is not valid.' }, 'x')).toBe(
      'That code is not right. Codes change every 30 seconds; try the next one.',
    );
    expect(plainProblem({ code: 'invitation_expired' }, 'x')).toContain('expired');
    expect(plainProblem({ code: 'weak_password', detail: 'This password is too common.' }, 'x')).toBe('This password is too common.');
    expect(plainProblem(undefined, 'Try again.')).toBe('Try again.');
  });

  it('groups a key for reading and copies it whole', () => {
    expect(groupSecret('ABCDEFGHIJ')).toBe('ABCD EFGH IJ');
  });
});

describe('the QR code', () => {
  it('is drawn in the browser as one SVG path, with no network and no style attribute', () => {
    const html = renderToStaticMarkup(<QrCode value={enrolment.otpauth_uri} label="QR" />);
    expect(html).toMatch(/^<svg class="qr-code" viewBox="0 0 \d+ \d+"/);
    expect(html).toContain('<path class="qr-dark" d="M');
    expect(html).not.toMatch(/fill="#/);
    expect(html).not.toMatch(/https?:|style=/);
  });
});
