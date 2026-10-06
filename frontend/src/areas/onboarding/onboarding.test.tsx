import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ApplicantView } from '../../api/onboarding';
import { ApplicantPage } from './applicant';
import {
  activateProblems,
  EMPTY_SIGN_UP,
  readLinkToken,
  type SignUpForm,
  signUpProblems,
  toActivateRequest,
  toSignUpRequest,
} from './forms';
import { SignUpView } from './sign-up';

// Static markup tests (the test setup has no DOM): what each public screen shows in each state.
// Fabricated businesses and people only.

/** Renders inside a memory router, for screens with links. */
async function inRouter(node: ReactElement): Promise<string> {
  const router = createRouter({
    routeTree: createRootRoute({ component: () => node }),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  await router.load();
  return renderToStaticMarkup(<RouterProvider router={router} />);
}

const filled: SignUpForm = {
  ...EMPTY_SIGN_UP,
  business_name: 'Test Hardware Shop',
  contact_name: 'Test Applicant 01',
  contact_email: 'applicant01@example.test',
  contact_phone: '0700 000 001',
  modules: ['retail'],
};

const noop = () => undefined;

describe('sign-up form checks (FR-ONB-01)', () => {
  it('accepts a complete form and names each missing field', () => {
    expect(signUpProblems(filled)).toEqual({});
    const problems = signUpProblems(EMPTY_SIGN_UP);
    expect(Object.keys(problems).sort()).toEqual(['business_name', 'contact_email', 'contact_name', 'contact_phone', 'modules']);
  });

  it('caps lengths and refuses a bad agent code', () => {
    expect(signUpProblems({ ...filled, business_name: 'x'.repeat(201) }).business_name).toBeDefined();
    expect(signUpProblems({ ...filled, message: 'x'.repeat(1001) }).message).toBeDefined();
    expect(signUpProblems({ ...filled, agent_code: 'bad code!' }).agent_code).toBeDefined();
    expect(signUpProblems({ ...filled, contact_email: 'josé@example.test' }).contact_email).toBeDefined();
  });

  it('trims the request and leaves empty optional fields out', () => {
    const request = toSignUpRequest({ ...filled, business_name: '  Test Hardware Shop ' });
    expect(request.business_name).toBe('Test Hardware Shop');
    expect(request.agent_code).toBeUndefined();
    expect(request.website).toBeUndefined();
  });
});

describe('the sign-up page (FR-ONB-01, FR-ONB-03)', () => {
  const view = (patch: Partial<Parameters<typeof SignUpView>[0]> = {}) =>
    renderToStaticMarkup(
      <SignUpView form={filled} problems={{}} busy={false} error={null} done={false} onChange={noop} onSubmit={noop} {...patch} />,
    );

  it('labels every field and asks for the phone before the email', () => {
    const html = view();
    for (const id of ['business_name', 'contact_name', 'contact_phone', 'contact_email', 'country', 'agent_code', 'message']) {
      expect(html).toContain(`for="${id}"`);
      expect(html).toContain(`id="${id}"`);
    }
    expect(html.indexOf('id="contact_phone"')).toBeLessThan(html.indexOf('id="contact_email"'));
    expect(html).toContain('type="tel"');
    expect(html).toContain('<legend>What do you want to use?</legend>');
  });

  it('hides the honeypot from people, keyboards and screen readers', () => {
    const html = view();
    expect(html).toMatch(/<div class="visually-hidden" aria-hidden="true">/);
    expect(html).toMatch(/id="website"[^>]*tabindex="-1"|tabindex="-1"[^>]*id="website"/);
  });

  it('disables the button while sending, so a double tap sends once', () => {
    expect(view({ busy: true })).toMatch(/<button class="btn-brand" type="submit" disabled="">Sending<\/button>/);
    expect(view()).toContain('Send application');
  });

  it('shows an error and marks the field', () => {
    const html = view({ error: 'Check the fields marked below.', problems: { contact_phone: 'Enter your phone number.' } });
    expect(html).toContain('role="alert"');
    expect(html).toContain('Enter your phone number.');
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('aria-describedby="contact_phone-problem"');
  });

  it('says only "check your email" once sent', () => {
    const html = view({ done: true });
    expect(html).toContain('Check your email');
    expect(html).not.toContain('<form');
  });
});

describe('the applicant page (FR-ONB-02, FR-ONB-04)', () => {
  const base: ApplicantView = {
    reference: 'A-TEST0001',
    status: 'submitted',
    business_name: 'Test Hardware Shop',
    modules: ['retail'],
    term: 'monthly',
    way_in: 'trial',
    email_verified: true,
    created_at: '2026-10-06T08:00:00Z',
  };
  const page = (patch: Partial<Parameters<typeof ApplicantPage>[0]>) =>
    inRouter(
      <ApplicantPage
        view={base}
        loading={false}
        error={null}
        reply=""
        replying={false}
        confirming={false}
        onReplyChange={noop}
        onReply={noop}
        onConfirm={noop}
        {...patch}
      />,
    );

  it('reads the token from the link fragment only', () => {
    const token = 'A'.repeat(43);
    expect(readLinkToken(`#token=${token}`)).toBe(token);
    expect(readLinkToken('#token=short')).toBeNull();
    expect(readLinkToken('')).toBeNull();
  });

  it('shows the status in plain words with the reference', async () => {
    const html = await page({});
    expect(html).toContain('A-TEST0001');
    expect(html).toContain('Waiting for review');
    expect(html).toContain('Shop: sales, stock and restocking');
  });

  it('confirms the email only on the button, never on opening the link (review N4)', async () => {
    const unconfirmed = await page({ view: { ...base, email_verified: false } });
    expect(unconfirmed).toContain('Confirm my email');
    expect(unconfirmed).not.toContain('We are looking at your application');
    expect(await page({ view: { ...base, email_verified: false }, confirming: true })).toMatch(/disabled="">Confirming/);
    expect(await page({})).not.toContain('Confirm my email');
    expect(await page({ view: { ...base, status: 'needs_info', email_verified: false, operator_note: 'Q?' } })).toContain(
      'Confirm my email',
    );
  });

  it('shows the operator question and an answer form when more information is needed', async () => {
    const html = await page({ view: { ...base, status: 'needs_info', operator_note: 'Which town is the shop in?' } });
    expect(html).toContain('Which town is the shop in?');
    expect(html).toContain('for="reply"');
    expect(html).toMatch(/disabled="">Send answer/);
  });

  it('shows loading, an invalid link and the reason of a rejection', async () => {
    expect(await page({ loading: true })).toContain('Opening your application');
    const invalid = await page({ view: null, error: 'This link is not valid or has expired.' });
    expect(invalid).toContain('role="alert"');
    expect(invalid).toContain('href="/sign-up"');
    expect(await page({ view: { ...base, status: 'rejected', reject_reason: 'Test reason.' } })).toContain('Test reason.');
  });
});

describe('the Activate form checks (FR-ONB-07)', () => {
  const form = { modules: ['retail'], term: 'monthly', way_in: 'trial', payment_note: '', slug: 'test-hardware', plan_code: 'starter' };

  it('needs a module, a valid slug, and a payment note on the paid way', () => {
    expect(activateProblems(form)).toEqual({});
    expect(activateProblems({ ...form, modules: [] }).modules).toBeDefined();
    expect(activateProblems({ ...form, slug: 'admin' }).slug).toBeDefined();
    expect(activateProblems({ ...form, slug: '-bad' }).slug).toBeDefined();
    expect(activateProblems({ ...form, way_in: 'paid' }).payment_note).toBeDefined();
    expect(activateProblems({ ...form, way_in: 'paid', payment_note: 'Test MoMo TEST-0001' })).toEqual({});
  });

  it('sends no empty note', () => {
    expect(toActivateRequest(form).payment_note).toBeUndefined();
  });
});
