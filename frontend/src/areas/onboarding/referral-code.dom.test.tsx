// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// The referral code on the public sign-up (issue #464, ADR-069): a link with ?ref= fills in the Referred by box, the applicant can
// change or clear it, a malformed code is refused before anything is sent, and the code goes to the server in upper case. The
// server's refusal of a code nobody has shows on the field. Fabricated people and businesses only.

const applyForAccount = vi.fn();

vi.mock('../../api/onboarding', async (original) => {
  const real = await original<typeof import('../../api/onboarding')>();
  return { ...real, applyForAccount: (...args: unknown[]) => applyForAccount(...args) };
});

const { OnboardingError } = await import('../../api/onboarding');
const { SignUp } = await import('./sign-up');
const { EMPTY_SIGN_UP, referralCodeFrom, signUpProblems, toSignUpRequest } = await import('./forms');

afterEach(() => {
  cleanup();
  applyForAccount.mockReset();
  window.history.replaceState(null, '', '/');
});

function fill() {
  fireEvent.change(screen.getByLabelText('Business name'), { target: { value: 'Test Hardware Shop' } });
  fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Test Applicant 01' } });
  fireEvent.change(screen.getByLabelText('Phone number'), { target: { value: '0700 000 001' } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'applicant01@example.test' } });
  fireEvent.click(screen.getByRole('checkbox', { name: /Shop/ }));
}

describe('the code in the link', () => {
  it('reads ?ref= in upper case, and ignores a malformed one', () => {
    expect(referralCodeFrom('?ref=dodos7k')).toBe('DODOS7K');
    expect(referralCodeFrom('?x=1&ref=%20TESTS7K%20')).toBe('TESTS7K');
    expect(referralCodeFrom('?ref=no!')).toBe('');
    expect(referralCodeFrom('?ref=ab')).toBe('');
    expect(referralCodeFrom('')).toBe('');
  });

  it('checks a typed code and sends it in upper case, or not at all when empty', () => {
    const form = { ...EMPTY_SIGN_UP, referral_code: 'bad code' };
    expect(signUpProblems(form).referral_code).toBeDefined();
    expect(signUpProblems({ ...form, referral_code: '' }).referral_code).toBeUndefined();
    expect(toSignUpRequest({ ...form, referral_code: ' tests7k ' }).referral_code).toBe('TESTS7K');
    expect(toSignUpRequest({ ...form, referral_code: '' }).referral_code).toBeUndefined();
  });
});

describe('the sign-up page', () => {
  it('fills in Referred by from a referral link and sends it with the application', async () => {
    window.history.replaceState(null, '', '/sign-up?ref=tests7k');
    applyForAccount.mockResolvedValue(undefined);
    render(<SignUp />);
    const box = screen.getByLabelText('Referred by (optional)') as HTMLInputElement;
    expect(box.value).toBe('TESTS7K');
    fill();
    fireEvent.click(screen.getByRole('button', { name: 'Send application' }));
    await waitFor(() => expect(applyForAccount).toHaveBeenCalledTimes(1));
    expect(applyForAccount.mock.calls[0]?.[0]?.referral_code).toBe('TESTS7K');
    expect(await screen.findByText('Check your email')).toBeTruthy();
  });

  it('leaves Referred by empty without a link, and sends no code', async () => {
    window.history.replaceState(null, '', '/sign-up');
    applyForAccount.mockResolvedValue(undefined);
    render(<SignUp />);
    expect((screen.getByLabelText('Referred by (optional)') as HTMLInputElement).value).toBe('');
    fill();
    fireEvent.click(screen.getByRole('button', { name: 'Send application' }));
    await waitFor(() => expect(applyForAccount).toHaveBeenCalledTimes(1));
    expect(applyForAccount.mock.calls[0]?.[0]?.referral_code).toBeUndefined();
  });

  it('refuses a malformed code before sending, and shows the server refusing a code nobody has on the field', async () => {
    window.history.replaceState(null, '', '/sign-up');
    render(<SignUp />);
    fill();
    fireEvent.change(screen.getByLabelText('Referred by (optional)'), { target: { value: 'no!' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send application' }));
    expect(await screen.findByText(/A referral code is 4 to 10 letters and digits/)).toBeTruthy();
    expect(applyForAccount).not.toHaveBeenCalled();

    applyForAccount.mockRejectedValue(
      new OnboardingError('No business has that referral code.', 'referral_code_unknown', [
        { field: 'referral_code', message: 'No business has that referral code. Check it, or leave it empty.' },
      ]),
    );
    fireEvent.change(screen.getByLabelText('Referred by (optional)'), { target: { value: 'NOPE99ZZ' } });
    // The refused press holds the busy button for two timer ticks (#456); a person never presses again that fast.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve));
      await new Promise((resolve) => setTimeout(resolve));
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send application' }));
    expect(await screen.findByText('No business has that referral code. Check it, or leave it empty.')).toBeTruthy();
    expect(screen.getByLabelText('Referred by (optional)').getAttribute('aria-invalid')).toBe('true');
  });
});
