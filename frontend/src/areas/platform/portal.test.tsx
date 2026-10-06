import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Application, ApplicationDetail, OutboxStatus } from '../../api/onboarding';
import { router } from '../../app/router';
import { ActivateFormView, ActivationDone, ApplicationFacts, DecisionPanel, DuplicateWarning } from './application';
import { ApplicationsTable } from './applications';
import { OutboxView } from './outbox';
import { OperatorSetupView, OperatorSignInView, type OperatorSignInViewProps } from './sign-in';

// Static markup tests of the operator portal screens (ADR-024, spec section 8). Fabricated data.

const noop = () => undefined;

/** Renders inside a memory router, for screens with links. */
async function inRouter(node: ReactElement): Promise<string> {
  const memory = createRouter({
    routeTree: createRootRoute({ component: () => node }),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  await memory.load();
  return renderToStaticMarkup(<RouterProvider router={memory} />);
}

const application: Application = {
  id: '00000000-0000-4000-8000-0000000000a1',
  reference: 'A-TEST0001',
  status: 'verified',
  business_name: 'Test Hardware Shop',
  contact_name: 'Test Applicant 01',
  contact_email: 'applicant01@example.test',
  contact_phone: '+256700000001',
  country: 'UG',
  modules: ['retail'],
  term: 'monthly',
  way_in: 'trial',
  created_at: '2026-10-06T08:00:00Z',
};

describe('operator sign-in (chapter 8 section 8.2)', () => {
  const props: OperatorSignInViewProps = {
    step: { kind: 'password' },
    login: '',
    password: '',
    code: '',
    busy: false,
    message: null,
    onLogin: noop,
    onPassword: noop,
    onCode: noop,
    onSubmitPassword: noop,
    onSubmitChallenge: noop,
    onSubmitEnrolment: noop,
    onCodesSaved: noop,
  };

  it('asks for email and password, then the code, with labels', () => {
    const password = renderToStaticMarkup(<OperatorSignInView {...props} />);
    expect(password).toContain('for="op-login"');
    expect(password).toContain('autoComplete="current-password"');
    const challenge = renderToStaticMarkup(<OperatorSignInView {...props} step={{ kind: 'challenge', mfaToken: 't' }} />);
    expect(challenge).toContain('for="op-code"');
  });

  it('shows the enrolment secret and the recovery codes once', () => {
    const enrol = renderToStaticMarkup(
      <OperatorSignInView
        {...props}
        step={{ kind: 'enrol', mfaToken: 't', enrolment: { secret: 'TESTSECRET', otpauth_uri: 'otpauth://totp/test' } }}
      />,
    );
    expect(enrol).toContain('TESTSECRET');
    const codes = renderToStaticMarkup(<OperatorSignInView {...props} step={{ kind: 'codes', codes: ['AAAAA-BBBBB'] }} />);
    expect(codes).toContain('AAAAA-BBBBB');
    expect(codes).toContain('shown only this once');
  });

  it('shows errors and disables the button while busy', () => {
    const html = renderToStaticMarkup(<OperatorSignInView {...props} busy message="The email or password is wrong." />);
    expect(html).toContain('role="alert"');
    expect(html).toMatch(/disabled="">Signing in/);
  });

  it('sets the first password with the setup token', () => {
    const html = renderToStaticMarkup(
      <OperatorSetupView token="" password="" busy={false} message={null} done={false} onToken={noop} onPassword={noop} onSubmit={noop} />,
    );
    expect(html).toContain('for="op-setup-token"');
    expect(html).toContain('for="op-new-password"');
    const done = renderToStaticMarkup(
      <OperatorSetupView token="t" password="" busy={false} message={null} done onToken={noop} onPassword={noop} onSubmit={noop} />,
    );
    expect(done).toContain('Your password is set');
  });
});

describe('the applications queue (FR-ONB-05)', () => {
  const counts = { submitted: 2, needs_info: 1, verified: 1, activated: 3, rejected: 0, expired: 0 };

  it('lists applications with the counts per status and a link to each', async () => {
    const html = await inRouter(<ApplicationsTable items={[application]} counts={counts} filter="open" onFilter={noop} />);
    expect(html).toContain('Open (4)');
    expect(html).toContain('Activated (3)');
    expect(html).toContain('aria-pressed="true"');
    expect(html).toContain('href="/platform/applications/00000000-0000-4000-8000-0000000000a1"');
    expect(html).toContain('Test Hardware Shop');
    expect(html).toContain('<th scope="col">Status</th>');
  });

  it('says when a filter has nothing', async () => {
    expect(await inRouter(<ApplicationsTable items={[]} counts={counts} filter="expired" onFilter={noop} />)).toContain(
      'No applications here.',
    );
  });
});

describe('one application (FR-ONB-05 to FR-ONB-07)', () => {
  const detail: ApplicationDetail = { application, possible_duplicates: [], suggested_slug: 'test-hardware-shop' };

  it('warns of a possible repeat with what matched', () => {
    const html = renderToStaticMarkup(
      <DuplicateWarning
        duplicates={[{ kind: 'tenant', id: 't1', reference: 'test-hardware', name: 'Test Hardware Shop', status: 'active', matched_on: ['business_name'] }]}
      />,
    );
    expect(html).toContain('Possible repeat');
    expect(html).toContain('same business name');
    expect(renderToStaticMarkup(<DuplicateWarning duplicates={[]} />)).toBe('');
  });

  it('shows the contact details to the operator', () => {
    const html = renderToStaticMarkup(<ApplicationFacts detail={detail} />);
    expect(html).toContain('+256700000001');
    expect(html).toContain('applicant01@example.test');
  });

  it('offers the decisions the status allows, and needs a note for Needs info and Reject', () => {
    const submitted = renderToStaticMarkup(<DecisionPanel status="submitted" busy={false} note="" onNote={noop} onDecide={noop} />);
    expect(submitted).toContain('>Verify<');
    expect(submitted).toMatch(/disabled="">Needs info/);
    expect(submitted).toMatch(/disabled="">Reject/);
    const verified = renderToStaticMarkup(<DecisionPanel status="verified" busy={false} note="A note" onNote={noop} onDecide={noop} />);
    expect(verified).not.toContain('>Verify<');
    expect(verified).toContain('>Needs info<');
    expect(renderToStaticMarkup(<DecisionPanel status="activated" busy={false} note="" onNote={noop} onDecide={noop} />)).toBe('');
  });

  it('has a labelled Activate form that cannot be sent twice', () => {
    const form = { modules: ['retail'], term: 'monthly', way_in: 'paid', payment_note: '', slug: 'test-hardware-shop', plan_code: 'starter' };
    const html = renderToStaticMarkup(
      <ActivateFormView form={form} problems={{ payment_note: 'Note the payment received.' }} busy onChange={noop} onSubmit={noop} />,
    );
    for (const id of ['activate-term', 'activate-note', 'activate-slug']) expect(html).toContain(`for="${id}"`);
    expect(html).toContain('value="test-hardware-shop"');
    expect(html).toContain('Note the payment received.');
    expect(html).toMatch(/disabled="">Activating/);
  });

  it('shows the result, and says when a repeat did nothing', () => {
    const now = renderToStaticMarkup(
      <ActivationDone result={{ activated_now: true, tenant_slug: 'test-hardware-shop', admin_invitation_url: 'https://test/accept-invitation#token=x' }} />,
    );
    expect(now).toContain('is created');
    expect(now).toContain('accept-invitation');
    const repeat = renderToStaticMarkup(<ActivationDone result={{ activated_now: false, tenant_slug: 'test-hardware-shop' }} />);
    expect(repeat).toContain('Nothing was changed');
  });
});

describe('messages not sent (FR-NTF-09)', () => {
  const status: OutboxStatus = {
    enabled_channels: ['email'],
    counts: { pending: 1, sent: 5, failed: 1 },
    failures: [
      {
        id: 'f1',
        channel: 'email',
        recipient: '************test',
        template_key: 'onboarding.activation',
        attempts: 3,
        last_error: 'MailConnectException',
        created_at: '2026-10-06T08:00:00Z',
        failed_at: '2026-10-06T08:10:00Z',
      },
    ],
  };

  it('lists failures masked, with a retry, and warns of an unconfigured sender', () => {
    const html = renderToStaticMarkup(<OutboxView status={status} busyId={null} onRetry={noop} />);
    expect(html).toContain('Activation link');
    expect(html).toContain('************test');
    expect(html).toContain('Send again');
    expect(html).toContain('The Telegram sender is not configured');
    expect(html).not.toContain('The email sender is not configured');
  });

  it('disables every retry while one is sending', () => {
    expect(renderToStaticMarkup(<OutboxView status={status} busyId="f1" onRetry={noop} />)).toMatch(/disabled="">Sending/);
  });
});

describe('routes (ADR-024)', () => {
  it('has the public sign-up, applicant, operator sign-in and portal routes on the shared shell', () => {
    const paths = ((router.routeTree.children ?? []) as unknown as { path?: string }[]).map((route) => route.path);
    for (const path of ['sign-up', 'sign-up/verify', 'application', 'platform/sign-in', 'platform/setup', 'platform']) {
      expect(paths).toContain(path);
    }
  });
});
