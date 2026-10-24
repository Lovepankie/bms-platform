// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me, TenantSettings } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// The Two-step sign-in screens in a browser (issue #348; ADR-046) with the API replaced: the saved choice is the starting point, a
// choice is read back in plain words before anything is sent, one tap sends one request, roles need at least one tick, someone who may
// not change the settings sees nothing to change, and the two notices (the reminder and the recommendation) show to the right people.
// Every name below is invented.

const twoStepApi = { settings: vi.fn(), save: vi.fn(), start: vi.fn(), confirm: vi.fn() };
const usersApi = { roles: vi.fn() };

vi.mock('../../../api/two-step', async (original) => {
  const real = await original<typeof import('../../../api/two-step')>();
  const forward = (name: string) => (...args: unknown[]) => ((twoStepApi as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, twoStep: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});
vi.mock('../../../api/users', async (original) => {
  const real = await original<typeof import('../../../api/users')>();
  return { ...real, users: new Proxy({}, { get: (_t, name: string) => (...args: unknown[]) => ((usersApi as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args) }) };
});

const { TwoStepError, bodyOf } = await import('../../../api/two-step');
const { TwoStepPolicyScreen } = await import('./page');
const { TwoStepNotices } = await import('./notices');
const { OwnTwoStep } = await import('./own');

const OFF: TenantSettings = { version: 4, require_mfa_all_staff: false, require_mfa_roles: [], display_name: 'Test Trading Co' };
const ROLES = [
  { key: 'cashier', name: 'Cashier', permissions: [] },
  { key: 'accountant', name: 'Accountant', permissions: [] },
  { key: 'tenant_admin', name: 'Tenant admin', permissions: [] },
];

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });

function shell(permissions: string[] = ['core.settings.manage', 'core.settings.read']) {
  const me = { ...mockMe('admin'), permissions, roles: [{ role_key: 'tenant_admin' }], user_id: 'u-self' } as Me;
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/setup/two-step'] }) });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client()}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <TwoStepPolicyScreen />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

vi.setConfig({ testTimeout: 15000 });

beforeEach(() => {
  twoStepApi.settings.mockResolvedValue(OFF);
  usersApi.roles.mockResolvedValue(ROLES);
});

afterEach(() => {
  cleanup();
  Object.values(twoStepApi).forEach((m) => m.mockReset());
  usersApi.roles.mockReset();
});

const radio = (name: RegExp) => screen.getByRole('radio', { name }) as HTMLInputElement;
const button = (name: RegExp | string) => screen.getByRole('button', { name }) as HTMLButtonElement;

describe('the policy screen', () => {
  it('starts from the saved choice, optional, with nothing to save yet', async () => {
    shell();
    // The first screen of the file loads its modules cold, which can take longer than the default second on a busy machine.
    await waitFor(() => expect(radio(/Optional/).checked).toBe(true), { timeout: 8000 });
    expect(radio(/Required for everyone/).checked).toBe(false);
    expect(button('Review and save').disabled).toBe(true);
    expect(screen.getByText('Nothing has changed yet.')).toBeTruthy();
    expect(screen.queryByLabelText(/How long do people have/)).toBeNull();
  });

  it('reads the choice back in plain words before it sends anything, then saves once', async () => {
    twoStepApi.save.mockResolvedValue({ ...OFF, version: 5, require_mfa_all_staff: true, mfa_grace_days: 14, mfa_enforce_from: '2026-11-03T21:00:00Z' });
    shell();
    await waitFor(() => expect(radio(/Optional/).checked).toBe(true));
    fireEvent.click(radio(/Required for everyone/));
    expect((screen.getByLabelText(/How long do people have/) as HTMLSelectElement).value).toBe('14');
    fireEvent.click(button('Review and save'));
    expect(screen.getByText('Everyone who signs in to the staff area will be asked to use it.')).toBeTruthy();
    expect(screen.getByText(/Nobody is locked out before/)).toBeTruthy();
    expect(screen.getByText('You are covered too, so you will be asked as well.')).toBeTruthy();
    expect(screen.getByText(/If someone loses their phone/)).toBeTruthy();
    expect(twoStepApi.save).not.toHaveBeenCalled();

    const yes = button('Yes, save this');
    fireEvent.click(yes);
    fireEvent.click(yes);
    await waitFor(() => expect(twoStepApi.save).toHaveBeenCalledTimes(1));
    expect(twoStepApi.save.mock.calls[0]?.[0]).toBe(4);
    expect(twoStepApi.save.mock.calls[0]?.[1]).toEqual({ policy: 'everyone', roles: [], graceDays: 14 });
    expect(twoStepApi.save.mock.calls[0]?.[2]).toEqual({ policy: 'off', roles: [], graceDays: 14 });
    expect(await screen.findByText('Saved. People who have not set it up have until the end of 3 Nov 2026.')).toBeTruthy();
  });

  it('lets the grace period be none, and says so', async () => {
    twoStepApi.save.mockResolvedValue({ ...OFF, version: 5, require_mfa_all_staff: true, mfa_grace_days: 0 });
    shell();
    await waitFor(() => expect(radio(/Optional/).checked).toBe(true));
    fireEvent.click(radio(/Required for everyone/));
    fireEvent.change(screen.getByLabelText(/How long do people have/), { target: { value: '0' } });
    fireEvent.click(button('Review and save'));
    expect(screen.getByText(/There is no wait/)).toBeTruthy();
    fireEvent.click(button('Yes, save this'));
    await waitFor(() => expect(twoStepApi.save).toHaveBeenCalledTimes(1));
    expect(twoStepApi.save.mock.calls[0]?.[1]).toMatchObject({ graceDays: 0 });
    expect(await screen.findByText('Saved. People who have not set it up are asked at their next sign-in.')).toBeTruthy();
  });

  it('needs at least one role when roles are chosen, and sends the ones ticked', async () => {
    twoStepApi.save.mockResolvedValue({ ...OFF, version: 5, require_mfa_roles: ['cashier'], mfa_grace_days: 14, mfa_enforce_from: '2026-11-03T21:00:00Z' });
    shell();
    await waitFor(() => expect(radio(/Optional/).checked).toBe(true));
    fireEvent.click(radio(/Required for the roles I choose/));
    expect(await screen.findByText('Tick at least one role.')).toBeTruthy();
    expect(button('Review and save').disabled).toBe(true);
    fireEvent.click(screen.getByLabelText('Cashier'));
    expect(button('Review and save').disabled).toBe(false);
    fireEvent.click(button('Review and save'));
    expect(screen.getByText('People with the role Cashier will be asked to use it.')).toBeTruthy();
    expect(screen.queryByText('You are covered too, so you will be asked as well.')).toBeNull();
    fireEvent.click(button('Yes, save this'));
    await waitFor(() => expect(twoStepApi.save).toHaveBeenCalledTimes(1));
    expect(twoStepApi.save.mock.calls[0]?.[1]).toEqual({ policy: 'roles', roles: ['cashier'], graceDays: 14 });
  });

  it('goes back from the review without sending anything, and a changed choice hides the review', async () => {
    shell();
    await waitFor(() => expect(radio(/Optional/).checked).toBe(true));
    fireEvent.click(radio(/Required for everyone/));
    fireEvent.click(button('Review and save'));
    fireEvent.click(button('Go back'));
    expect(screen.queryByRole('group', { name: 'Check before you save' })).toBeNull();
    fireEvent.click(button('Review and save'));
    fireEvent.click(radio(/Optional/));
    expect(screen.queryByRole('group', { name: 'Check before you save' })).toBeNull();
    expect(twoStepApi.save).not.toHaveBeenCalled();
  });

  it('turns it back to optional with its own words, and sends no grace period', async () => {
    twoStepApi.settings.mockResolvedValue({ ...OFF, require_mfa_all_staff: true, mfa_grace_days: 7 });
    twoStepApi.save.mockResolvedValue({ ...OFF, version: 5 });
    shell();
    await waitFor(() => expect(radio(/Required for everyone/).checked).toBe(true));
    fireEvent.click(radio(/Optional/));
    fireEvent.click(button('Review and save'));
    expect(screen.getByText(/Nobody will be required to use it/)).toBeTruthy();
    // Relaxing the rule needs the administrator's own proof: nothing is sent until it is typed.
    const yes = button('Yes, make it optional');
    expect(yes.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Your own password'), { target: { value: 'Test-Password-1' } });
    expect(yes.disabled).toBe(false);
    fireEvent.click(yes);
    await waitFor(() => expect(twoStepApi.save).toHaveBeenCalledTimes(1));
    expect(twoStepApi.save.mock.calls[0]?.[1]).toMatchObject({ policy: 'off' });
    expect(twoStepApi.save.mock.calls[0]?.[3]).toEqual({ password: 'Test-Password-1' });
    expect(await screen.findByText('Saved. Two-step sign-in is optional again.')).toBeTruthy();
  });

  it('narrowing keeps the deadline: the review says the date does not change, and no grace period is sent', async () => {
    twoStepApi.settings.mockResolvedValue({ ...OFF, require_mfa_all_staff: true, mfa_grace_days: 14, mfa_enforce_from: '2026-10-01T21:00:00Z' });
    twoStepApi.save.mockResolvedValue({ ...OFF, version: 5, require_mfa_roles: ['cashier'], mfa_grace_days: 14, mfa_enforce_from: '2026-10-01T21:00:00Z' });
    shell();
    await waitFor(() => expect(radio(/Required for everyone/).checked).toBe(true));
    fireEvent.click(radio(/Required for the roles I choose/));
    fireEvent.click(await screen.findByLabelText('Cashier'));
    fireEvent.click(button('Review and save'));
    expect(screen.getByText('The date already set does not change: the end of 1 Oct 2026.')).toBeTruthy();
    expect(screen.queryByText(/Nobody is locked out before/)).toBeNull();
    fireEvent.change(screen.getByLabelText('Your own password'), { target: { value: 'Test-Password-1' } });
    fireEvent.click(button('Yes, save this'));
    await waitFor(() => expect(twoStepApi.save).toHaveBeenCalledTimes(1));
    // The real client turns this into a body with no mfa_grace_days (state.test.ts: bodyOf); here the screen hands it the saved choice.
    expect(twoStepApi.save.mock.calls[0]?.[2]).toEqual({ policy: 'everyone', roles: [], graceDays: 14 });
    expect(twoStepApi.save.mock.calls[0]?.[3]).toEqual({ password: 'Test-Password-1' });
    expect(bodyOf(twoStepApi.save.mock.calls[0]?.[1], twoStepApi.save.mock.calls[0]?.[2], twoStepApi.save.mock.calls[0]?.[3])).not.toHaveProperty('mfa_grace_days');
  });

  it('shows a refusal in plain words and keeps the review open', async () => {
    twoStepApi.save.mockRejectedValue(new TwoStepError('Someone else changed the settings while you were looking. Reload the page and choose again.', 409, 'version_conflict'));
    shell();
    await waitFor(() => expect(radio(/Optional/).checked).toBe(true));
    fireEvent.click(radio(/Required for everyone/));
    fireEvent.click(button('Review and save'));
    fireEvent.click(button('Yes, save this'));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText(/Someone else changed the settings/)).toBeTruthy();
    expect(button('Yes, save this').disabled).toBe(false);
  });

  it('shows no choices to someone who may not change the settings', () => {
    shell(['core.settings.read']);
    expect(screen.getByText(/^You do not have access to this page\. The owner or an administrator/)).toBeTruthy();
    expect(screen.queryByRole('radio')).toBeNull();
    expect(twoStepApi.settings).not.toHaveBeenCalled();
  });
});

describe('the notices', () => {
  const admin = (extra: Partial<Me> = {}) => ({ ...mockMe('admin'), permissions: ['core.settings.manage'], user_id: 'u-admin', mfa_enabled: false, two_step_policy: 'off', ...extra }) as Me;

  function notices(me: Me, pathname: string) {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [pathname] }) });
    return render(
      <RouterContextProvider router={router}>
        <TwoStepNotices me={me} pathname={pathname} />
      </RouterContextProvider>,
    );
  }

  beforeEach(() => window.localStorage.clear());

  it('recommends it to an administrator while it is off, on the home page, and "Not now" puts it away', async () => {
    notices(admin(), '/staff');
    expect(await screen.findByText(/Protect your business/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Choose who uses it' })).toBeTruthy();
    fireEvent.click(button('Not now'));
    expect(screen.queryByText(/Protect your business/)).toBeNull();
    cleanup();
    notices(admin(), '/staff');
    expect(screen.queryByText(/Protect your business/)).toBeNull();
  });

  it('does not recommend it on other pages, to a non-manager, or once a policy is on', async () => {
    notices(admin(), '/staff/retail');
    expect(screen.queryByText(/Protect your business/)).toBeNull();
    cleanup();
    notices(admin({ permissions: ['core.settings.read'] }), '/staff');
    expect(screen.queryByText(/Protect your business/)).toBeNull();
    cleanup();
    notices(admin({ two_step_policy: 'roles' }), '/staff');
    expect(screen.queryByText(/Protect your business/)).toBeNull();
  });

  it('reminds a covered person with the date on any page but their own set-up page', async () => {
    const covered = admin({ permissions: [], two_step_policy: 'everyone', mfa_due_by: '2026-11-03T21:00:00Z' });
    notices(covered, '/staff/retail/sale');
    expect(await screen.findByText(/set up two-step sign-in by 3 Nov 2026/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Set it up now' })).toBeTruthy();
    cleanup();
    notices(covered, '/staff/two-step');
    expect(screen.queryByText(/set up two-step sign-in by/)).toBeNull();
    cleanup();
    notices({ ...covered, mfa_enabled: true } as Me, '/staff');
    expect(screen.queryByText(/set up two-step sign-in by/)).toBeNull();
  });
});

describe('your own set-up', () => {
  function own(me: Partial<Me>, blocking = false) {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/two-step'] }) });
    return render(
      <RouterContextProvider router={router}>
        <QueryClientProvider client={client()}>
          <StaffContext.Provider value={{ me: { ...mockMe('admin'), user_id: 'u-self', mfa_enabled: false, ...me } as Me, branch: null }}>
            <OwnTwoStep blocking={blocking} />
          </StaffContext.Provider>
        </QueryClientProvider>
      </RouterContextProvider>,
    );
  }

  it('tells the date, starts, takes the first code and shows the recovery codes once', async () => {
    twoStepApi.start.mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauth_uri: 'otpauth://totp/BMS%20test:person?secret=JBSWY3DPEHPK3PXP' });
    twoStepApi.confirm.mockResolvedValue(['AAAA-1111', 'BBBB-2222']);
    own({ mfa_due_by: '2026-11-03T21:00:00Z' });
    expect(screen.getByText(/Your business asks you to set it up by 3 Nov 2026/)).toBeTruthy();
    // Starting asks for the person's own password first: nothing is sent without it.
    expect(button('Turn on two-step sign-in').disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Your password, to confirm it is you'), { target: { value: 'Test-Password-1' } });
    fireEvent.click(button('Turn on two-step sign-in'));
    await waitFor(() => expect(twoStepApi.start).toHaveBeenCalledWith('Test-Password-1'));
    const code = (await screen.findByLabelText('6 digit code from the app')) as HTMLInputElement;
    expect(button('Turn on two-step sign-in').disabled).toBe(true);
    fireEvent.change(code, { target: { value: '12a3456' } });
    expect(code.value).toBe('123456');
    fireEvent.click(button('Turn on two-step sign-in'));
    await waitFor(() => expect(twoStepApi.confirm).toHaveBeenCalledWith('123456'));
    expect(await screen.findByText('Two-step sign-in is on.')).toBeTruthy();
    expect(screen.getByText('AAAA-1111')).toBeTruthy();
    expect((button('Done') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByLabelText(/I have saved these codes/));
    expect((button('Done') as HTMLButtonElement).disabled).toBe(false);
  });

  it('shows a wrong code in plain words and lets the person try again', async () => {
    twoStepApi.start.mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauth_uri: 'otpauth://totp/BMS%20test:person?secret=JBSWY3DPEHPK3PXP' });
    twoStepApi.confirm.mockRejectedValue(new TwoStepError('That code is not right. Codes change every 30 seconds; try the next one.', 422, 'invalid_mfa_code'));
    own({});
    fireEvent.change(screen.getByLabelText('Your password, to confirm it is you'), { target: { value: 'Test-Password-1' } });
    fireEvent.click(button('Turn on two-step sign-in'));
    fireEvent.change(await screen.findByLabelText('6 digit code from the app'), { target: { value: '000000' } });
    fireEvent.click(button('Turn on two-step sign-in'));
    expect(await screen.findByText(/That code is not right/)).toBeTruthy();
    expect(button('Turn on two-step sign-in').disabled).toBe(false);
  });

  it('blocks a person who must set it up now: no way out, and the reason is in plain words', () => {
    own({ mfa_required: true, mfa_enabled: false }, true);
    expect(screen.getByText(/Nothing else in the app opens until two-step sign-in is on for you/)).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Not now' })).toBeNull();
  });

  it('says it is on for someone who already has it, and offers no second set-up', () => {
    own({ mfa_enabled: true });
    expect(screen.getByText(/Two-step sign-in is on for your account/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Turn on two-step sign-in' })).toBeNull();
  });
});
