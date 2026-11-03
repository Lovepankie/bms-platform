// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { HostnameCheck } from '../../api/onboarding';
import type { ActivateForm } from '../onboarding/forms';

// The hostname check on the operator's Activate form (issue #155), in a browser with the API replaced: the operator asks
// whether the business's own address answers, reads each outcome in plain words, and is pointed at the runbook step when it
// does not. A changed address name hides the old answer. Every host is invented.

const checkHostname = vi.fn<(slug: string) => Promise<HostnameCheck>>();

vi.mock('../../api/onboarding', async (original) => {
  const real = await original<typeof import('../../api/onboarding')>();
  return { ...real, checkHostname: (slug: string) => checkHostname(slug) };
});

const { ActivateFormView, RUNBOOK_STEP, hostnameWords } = await import('./application');
const { OnboardingError } = await import('../../api/onboarding');

const HOST = 'test-shop-155-bms-staging.example.test';

function Form() {
  const [form, setForm] = useState<ActivateForm>({ modules: ['retail'], term: 'monthly', way_in: 'trial', payment_note: '', slug: 'test-shop-155', plan_code: 'starter' });
  return <ActivateFormView form={form} problems={{}} busy={false} onChange={setForm} onSubmit={(e) => e.preventDefault()} />;
}

async function check(answer: HostnameCheck) {
  checkHostname.mockResolvedValueOnce(answer);
  render(<Form />);
  fireEvent.click(screen.getByRole('button', { name: 'Check the address' }));
  return screen.findByRole('status');
}

afterEach(() => {
  cleanup();
  checkHostname.mockReset();
});

describe('the hostname check on the Activate form (#155)', () => {
  it('says the address is ready when the business host answers, and asks for the slug in the form', async () => {
    const status = await check({ host: HOST, outcome: 'answers' });
    expect(checkHostname).toHaveBeenCalledWith('test-shop-155');
    expect(status.textContent).toBe(`${HOST} answers. The address is ready for this business.`);
    expect(status.className).toContain('alert-success');
    expect(status.textContent).not.toContain('runbook');
  });

  it('says there is no DNS record yet and points at the runbook step', async () => {
    const status = await check({ host: HOST, outcome: 'not_found' });
    expect(status.textContent).toBe(`${HOST} has no DNS record yet, so nobody can open it. ${RUNBOOK_STEP}`);
    expect(status.className).toContain('alert-warning');
  });

  it('says nothing answered and points at the runbook step', async () => {
    const status = await check({ host: HOST, outcome: 'no_answer' });
    expect(status.textContent).toContain(`${HOST} did not answer.`);
    expect(status.textContent).toContain('docs/runbooks/onboard-tenant.md');
  });

  it('says something else answered, with its status, and points at the runbook step', async () => {
    const status = await check({ host: HOST, outcome: 'wrong_answer', http_status: 404 });
    expect(status.textContent).toContain(`Something answered at ${HOST}, but not this app (HTTP 404).`);
    expect(status.textContent).toContain('Make the tenant host reachable');
  });

  it('hides the answer once the address name changes, so an old answer is never read as the new one', async () => {
    await check({ host: HOST, outcome: 'answers' });
    fireEvent.change(screen.getByLabelText(/Address name/), { target: { value: 'test-shop-156' } });
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull());
    // Changed back, the old answer stays gone until the operator checks again.
    fireEvent.change(screen.getByLabelText(/Address name/), { target: { value: 'test-shop-155' } });
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('says a 403 or 503 may be a Cloudflare security rule, not a missing tunnel entry', async () => {
    const status = await check({ host: HOST, outcome: 'wrong_answer', http_status: 403 });
    expect(status.textContent).toContain(`${HOST} refused the check (HTTP 403).`);
    expect(status.textContent).toContain('Cloudflare security rule');
    expect(status.textContent).not.toContain('The tunnel entry may be missing');
  });

  it('shows the server words when the check is refused, and cannot run with no address name', async () => {
    checkHostname.mockRejectedValueOnce(new OnboardingError('Use 3 to 63 lower case letters, digits and hyphens.', 'invalid_slug'));
    render(<Form />);
    fireEvent.click(screen.getByRole('button', { name: 'Check the address' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Use 3 to 63 lower case letters, digits and hyphens.');
    fireEvent.change(screen.getByLabelText(/Address name/), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Check the address' }));
    expect(checkHostname).toHaveBeenCalledTimes(1);
  });

  it('uses plain words with no dashes as punctuation', () => {
    const dashes = new RegExp('[\\u2013\\u2014]| - ');
    for (const outcome of ['answers', 'not_found', 'no_answer', 'wrong_answer', 'later']) {
      expect(hostnameWords({ host: 'test-shop-155-bms-staging.example.test', outcome, http_status: 404 }).text).not.toMatch(dashes);
    }
  });
});
