// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { StaffUser } from '../../../api/users';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// The Users screens in a browser (issue #319), with the API client replaced: the form refuses a bad phone or an empty role before
// anything is sent, a double tap invites once, the one-time link is shown once with Copy and WhatsApp and is gone when the box is
// closed, a branch is asked for only when the role is not the administrator, the plan's refusal is shown, and someone who may only
// read sees the list and no action. Every name, number and link below is invented.

const api = { list: vi.fn(), roles: vi.fn(), branches: vi.fn(), invite: vi.fn(), reissue: vi.fn(), setRoles: vi.fn(), deactivate: vi.fn(), transferOwnership: vi.fn() };

vi.mock('../../../api/users', async (original) => {
  const real = await original<typeof import('../../../api/users')>();
  const forward = (name: string) => (...args: unknown[]) => ((api as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, users: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});
vi.mock('../../../app/branding', () => ({ useShellBrand: () => ({ name: 'Test Trading Co' }) }));

const resetUserSignIn = vi.fn();
vi.mock('../../../api/sign-in-reset', async (original) => ({ ...(await original<typeof import('../../../api/sign-in-reset')>()), resetUserSignIn: (...args: unknown[]) => resetUserSignIn(...args) }));

const { UsersError, NETWORK_WORDS } = await import('../../../api/users');
const { SignInResetError } = await import('../../../api/sign-in-reset');
const { UsersScreen } = await import('./page');
const { InviteScreen } = await import('./invite');

const ROLES = [
  { key: 'tenant_admin', name: 'Tenant admin', permissions: [] },
  { key: 'cashier', name: 'Cashier', permissions: [] },
  { key: 'retail_sales', name: 'Sales', permissions: [] },
  { key: 'retail_storekeeper', name: 'Storekeeper', permissions: [] },
  { key: 'retail_purchasing', name: 'Purchasing', permissions: [] },
];
const BRANCHES = [
  { id: 'b-head', code: 'HQ', name: 'Head office', status: 'active' },
  { id: 'b-two', code: 'KLA', name: 'Test Branch Two', status: 'active' },
  { id: 'b-store', code: 'ST', name: 'Test Store', status: 'active', sells_goods: false },
  { id: 'b-old', code: 'OLD', name: 'Test Old Branch', status: 'inactive' },
];
const LINK = 'https://test.example/accept-invitation#token=invented-token-0001';

const SELF: StaffUser = { id: 'u-self', full_name: 'Test Owner 01', email: 'owner01@example.com', status: 'active', roles: [{ role_key: 'tenant_admin' }] };
const WAITING: StaffUser = { id: 'u-wait', full_name: 'Test Seller 02', phone_e164: '+256700000002', status: 'invited', roles: [{ role_key: 'retail_sales', branch_id: 'b-two' }] };
const SECOND_ADMIN: StaffUser = { id: 'u-admin2', full_name: 'Test Admin 03', email: 'admin03@example.com', status: 'active', roles: [{ role_key: 'tenant_admin' }] };
const ON_CLOSED: StaffUser = { id: 'u-closed', full_name: 'Test Cashier 06', email: 'cashier06@example.com', status: 'active', roles: [{ role_key: 'cashier', branch_id: 'b-old' }] };
const LEFT: StaffUser = { id: 'u-left', full_name: 'Test Leaver 04', phone_e164: '+256700000004', status: 'deactivated', roles: [{ role_key: 'cashier' }] };

const ADMIN = ['core.users.read', 'core.users.manage', 'core.branches.read'];
const READER = ['core.users.read'];

let lastClient: QueryClient;

function shell(node: ReactElement, permissions: string[] = ADMIN, mfaEnabled = false, isOwner = false) {
  const me = { ...mockMe('admin'), permissions, user_id: 'u-self', mfa_enabled: mfaEnabled, is_owner: isOwner };
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/users'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  lastClient = client;
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

// The first screen of the file pays for loading the lazy router, which is slow on a busy machine.
vi.setConfig({ testTimeout: 15000 });

const writeText = vi.fn();

beforeEach(() => {
  api.list.mockResolvedValue([SELF, WAITING, SECOND_ADMIN, LEFT]);
  api.roles.mockResolvedValue(ROLES);
  api.branches.mockResolvedValue(BRANCHES);
  writeText.mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
});

afterEach(() => {
  cleanup();
  Object.values(api).forEach((m) => m.mockReset());
  resetUserSignIn.mockReset();
  writeText.mockReset();
});

const TOKEN = 'invented-token-0001';

/** The link left nowhere: not in the text, any input, any address on the page, or the query and mutation caches. */
function expectNoToken() {
  expect(document.body.textContent).not.toContain(TOKEN);
  document.querySelectorAll('input, textarea').forEach((el) => expect((el as HTMLInputElement).value).not.toContain(TOKEN));
  document.querySelectorAll('a[href]').forEach((el) => expect(decodeURIComponent(el.getAttribute('href') ?? '')).not.toContain(TOKEN));
  expectNoTokenInCaches();
}

function expectNoTokenInCaches() {
  expect(JSON.stringify(lastClient.getQueryCache().getAll().map((q) => [q.queryKey, q.state.data]))).not.toContain(TOKEN);
  expect(JSON.stringify(lastClient.getMutationCache().getAll().map((m) => [m.state.data, m.state.variables]))).not.toContain(TOKEN);
}

async function fillInvite(overrides: { name?: string; phone?: string; role?: string; branches?: boolean } = {}) {
  await screen.findByRole('option', { name: 'Cashier' }, { timeout: 5000 });
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: overrides.name ?? 'Test Cashier 05' } });
  fireEvent.change(screen.getByLabelText('Phone number'), { target: { value: overrides.phone ?? '0700 000 005' } });
  fireEvent.change(screen.getByLabelText('What will this person do?'), { target: { value: overrides.role ?? 'cashier' } });
  // Where the person works is an explicit choice (ADR-055): every shop and store unless a test ticks branches itself.
  if (!overrides.branches && (overrides.role ?? 'cashier') !== 'tenant_admin') fireEvent.click(screen.getByRole('checkbox', { name: 'Every shop and store' }));
}

describe('the invite form', () => {
  it('refuses an empty form and a bad phone before anything is sent', async () => {
    shell(<InviteScreen />);
    await screen.findByRole('option', { name: 'Cashier' }, { timeout: 5000 });
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    expect(await screen.findByText('Type the person\'s full name.')).toBeTruthy();
    expect(screen.getByText(/Give a phone number or an email address/)).toBeTruthy();
    expect(screen.getByText('Choose what this person will do.')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Phone number'), { target: { value: '12345' } });
    expect(screen.getByText(/Use a Uganda mobile number/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Test Cashier 05' } });
    fireEvent.change(screen.getByLabelText('What will this person do?'), { target: { value: 'cashier' } });
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    expect(api.invite).not.toHaveBeenCalled();
  });

  it('shows the plain-language description of the chosen role and asks for a branch unless it is the administrator', async () => {
    shell(<InviteScreen />);
    await fillInvite();
    expect(screen.getAllByText(/Takes and records money at the counter/).length).toBeGreaterThan(0);
    expect(screen.getByRole('group', { name: 'Where does this person work?' })).toBeTruthy();
    expect((screen.getByRole('checkbox', { name: 'Every shop and store' }) as HTMLInputElement).checked).toBe(true);
    // Taking every one off leaves nothing chosen, which is refused, never every branch.
    fireEvent.click(screen.getByRole('checkbox', { name: 'Every shop and store' }));
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    expect(await screen.findByText('Tick where this person works, or every one.')).toBeTruthy();
    expect(api.invite).not.toHaveBeenCalled();
    expect(screen.getByRole('checkbox', { name: 'Test Branch Two (KLA)' })).toBeTruthy();
    expect(screen.getByRole('checkbox', { name: 'Test Store (ST), a store' })).toBeTruthy();
    fireEvent.change(screen.getByLabelText('What will this person do?'), { target: { value: 'tenant_admin' } });
    expect(screen.queryByRole('group', { name: 'Where does this person work?' })).toBeNull();
    expect(screen.getByText(/An administrator works everywhere and sees everything/)).toBeTruthy();
  });

  it('sends the phone as stored, with the chosen branch', async () => {
    api.invite.mockResolvedValue({ user: { id: 'u-new', full_name: 'Test Cashier 05', phone_e164: '+256700000005' }, invitation: { url: LINK, expires_at: '2026-10-22T10:00:00Z' } });
    shell(<InviteScreen />);
    await fillInvite();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Test Branch Two (KLA)' }));
    expect((screen.getByRole('checkbox', { name: 'Every shop and store' }) as HTMLInputElement).checked).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    await waitFor(() => expect(api.invite).toHaveBeenCalledWith({ full_name: 'Test Cashier 05', phone: '+256700000005', roles: [{ role_key: 'cashier', branch_id: 'b-two' }] }));
  });

  // Issue #399, ADR-055: a storekeeper in two places, and a seller who also buys.
  it('sends a role for each shop and store ticked, and Purchasing in the same places when it is ticked', async () => {
    api.invite.mockResolvedValue({ user: { id: 'u-new', full_name: 'Test Cashier 05', phone_e164: '+256700000005' }, invitation: { url: LINK, expires_at: '2026-10-22T10:00:00Z' } });
    shell(<InviteScreen />);
    await fillInvite({ role: 'retail_sales', branches: true });
    expect(screen.queryByRole('option', { name: 'Purchasing' })).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Test Branch Two (KLA)' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Test Store (ST), a store' }));
    fireEvent.click(screen.getByRole('checkbox', { name: /Can also buy stock and record purchases/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    await waitFor(() => expect(api.invite).toHaveBeenCalledWith({
      full_name: 'Test Cashier 05',
      phone: '+256700000005',
      roles: [
        { role_key: 'retail_sales', branch_id: 'b-two' },
        { role_key: 'retail_sales', branch_id: 'b-store' },
        { role_key: 'retail_purchasing', branch_id: 'b-two' },
        { role_key: 'retail_purchasing', branch_id: 'b-store' },
      ],
    }));
  });

  it('invites once when the button is tapped twice before the answer', async () => {
    let finish: (v: unknown) => void = () => undefined;
    api.invite.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    shell(<InviteScreen />);
    await fillInvite();
    const button = screen.getByRole('button', { name: 'Invite' });
    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.submit(screen.getByRole('form', { name: 'Invite a staff user' }));
    expect(api.invite).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Invite' }).getAttribute('aria-busy')).toBe('true');
    finish({ user: { full_name: 'Test Cashier 05' }, invitation: { url: LINK, expires_at: '2026-10-22T10:00:00Z' } });
    await screen.findByRole('heading', { name: 'Test Cashier 05 is invited' });
  });

  // JUSTIFICATION-A3: the busy-button test case issue #456 asks for.
  it('shows the Invite button as busy while the invitation is sent, and a double click invites once', async () => {
    let finish: (v: unknown) => void = () => undefined;
    api.invite.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    shell(<InviteScreen />);
    await fillInvite();
    const button = screen.getByRole('button', { name: 'Invite' });
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
    const busy = screen.getByRole('button', { name: 'Invite' });
    expect(busy).toBe(button);
    expect(busy.getAttribute('aria-disabled')).toBe('true');
    expect(busy.querySelector('.busy-spinner')).not.toBeNull();
    fireEvent.click(busy);
    expect(api.invite).toHaveBeenCalledTimes(1);
    finish({ user: { full_name: 'Test Cashier 05' }, invitation: { url: LINK, expires_at: '2026-10-22T10:00:00Z' } });
    await screen.findByRole('heading', { name: 'Test Cashier 05 is invited' });
    expect(api.invite).toHaveBeenCalledTimes(1);
  });

  it('shows the plan\'s refusal in plain words', async () => {
    api.invite.mockRejectedValue(new UsersError('Your plan allows at most 3 staff users, and that number is used up.', 422, 'plan_limit_reached'));
    shell(<InviteScreen />);
    await fillInvite();
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/at most 3 staff users/);
    // The form is still there, with what was typed, so nothing is lost.
    expect((screen.getByLabelText('Full name') as HTMLInputElement).value).toBe('Test Cashier 05');
  });
});

describe('an invite whose answer was lost', () => {
  it('says so in plain words and points at the staff list, where Send a new link is', async () => {
    api.invite.mockRejectedValue(new UsersError(NETWORK_WORDS, 0, 'network'));
    shell(<InviteScreen />);
    await fillInvite();
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    expect((await screen.findByRole('alert')).textContent).toBe(NETWORK_WORDS);
    expect(screen.queryByText(/Failed to fetch/)).toBeNull();
    const list = screen.getByRole('link', { name: 'the staff list' }) as HTMLAnchorElement;
    expect(list.getAttribute('href')).toBe('/staff/users');
    // What was typed is still there, so nothing has to be typed twice.
    expect((screen.getByLabelText('Full name') as HTMLInputElement).value).toBe('Test Cashier 05');
  });

  it('adds the same hint when the second tap meets the person already invited', async () => {
    api.invite.mockRejectedValue(new UsersError('Someone on your staff already uses this phone number.', 409, 'duplicate_phone'));
    shell(<InviteScreen />);
    await fillInvite();
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/already uses this phone number/);
    expect(screen.getByText(/Did you just invite this person/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'the staff list' })).toBeTruthy();
  });

  it('adds no hint for a refusal that has nothing to do with a repeat', async () => {
    api.invite.mockRejectedValue(new UsersError('Your plan allows at most 3 staff users.', 422, 'plan_limit_reached'));
    shell(<InviteScreen />);
    await fillInvite();
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    await screen.findByRole('alert');
    expect(screen.queryByText(/Did you just invite this person/)).toBeNull();
  });
});

describe('the one-time link', () => {
  it('is shown once with Copy, WhatsApp and the expiry, and is gone when the box is closed', async () => {
    api.invite.mockResolvedValue({ user: { id: 'u-new', full_name: 'Test Cashier 05', phone_e164: '+256700000005' }, invitation: { url: LINK, expires_at: '2026-10-22T10:00:00Z' } });
    shell(<InviteScreen />);
    await fillInvite();
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));

    await screen.findByRole('heading', { name: 'Test Cashier 05 is invited' });
    expect((screen.getByLabelText('Invitation link') as HTMLInputElement).value).toBe(LINK);
    // While the box is open the link is in the box and nowhere else: not in the query cache, not in a mutation.
    expectNoTokenInCaches();
    expect(screen.getByText(/shown only now/)).toBeTruthy();
    expect(screen.getByText(/works once and stops/)).toBeTruthy();
    const whatsapp = screen.getByRole('link', { name: 'Send on WhatsApp' }) as HTMLAnchorElement;
    expect(whatsapp.href.startsWith('https://wa.me/256700000005?text=')).toBe(true);
    expect(decodeURIComponent(whatsapp.href)).toContain(LINK);
    expect(decodeURIComponent(whatsapp.href)).toContain('Test Trading Co');

    fireEvent.click(screen.getByRole('button', { name: 'Copy the link' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(LINK));
    expect(await screen.findByText(/Copied/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /I have sent it/ }));
    expect(screen.queryByLabelText('Invitation link')).toBeNull();
    expectNoToken();
    // The form is empty again for the next person.
    expect((screen.getByLabelText('Full name') as HTMLInputElement).value).toBe('');
  });

  it('is made again for a person still invited, shown once, and the old one is not kept', async () => {
    api.reissue.mockResolvedValue({ url: LINK, expires_at: '2026-10-22T10:00:00Z' });
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 02')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Send a new link' }));
    await screen.findByRole('heading', { name: 'New link for Test Seller 02' });
    expect(api.reissue).toHaveBeenCalledWith('u-wait');
    expect((screen.getByLabelText('Invitation link') as HTMLInputElement).value).toBe(LINK);
    expectNoTokenInCaches();
    fireEvent.click(screen.getByRole('button', { name: 'I have sent it' }));
    expect(screen.queryByLabelText('Invitation link')).toBeNull();
    expectNoToken();
  });

  it('is closed with the person when they are deactivated, so a dead link is not left on the page', async () => {
    api.reissue.mockResolvedValue({ url: LINK, expires_at: '2026-10-22T10:00:00Z' });
    api.deactivate.mockResolvedValue({ ...WAITING, status: 'deactivated' });
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 02')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Send a new link' }));
    await screen.findByLabelText('Invitation link');
    fireEvent.click(within(card).getByRole('button', { name: 'Deactivate' }));
    fireEvent.click(within(card).getByRole('button', { name: 'Yes, deactivate' }));
    await waitFor(() => expect(api.deactivate).toHaveBeenCalledWith('u-wait'));
    await waitFor(() => expect(screen.queryByLabelText('Invitation link')).toBeNull());
    expectNoToken();
  });

  it('stays open when somebody else is deactivated', async () => {
    api.reissue.mockResolvedValue({ url: LINK, expires_at: '2026-10-22T10:00:00Z' });
    api.deactivate.mockResolvedValue({ ...SECOND_ADMIN, status: 'deactivated' });
    shell(<UsersScreen />);
    fireEvent.click(within((await screen.findByText('Test Seller 02')).closest('li') as HTMLElement).getByRole('button', { name: 'Send a new link' }));
    await screen.findByLabelText('Invitation link');
    const other = screen.getByText('Test Admin 03').closest('li') as HTMLElement;
    fireEvent.click(within(other).getByRole('button', { name: 'Deactivate' }));
    fireEvent.click(within(other).getByRole('button', { name: 'Yes, deactivate' }));
    await waitFor(() => expect(api.deactivate).toHaveBeenCalledWith('u-admin2'));
    expect(screen.getByLabelText('Invitation link')).toBeTruthy();
  });

  it('is not made twice by two taps that land before the page has redrawn', async () => {
    let finish: (v: unknown) => void = () => undefined;
    api.reissue.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    shell(<UsersScreen />);
    const button = within((await screen.findByText('Test Seller 02')).closest('li') as HTMLElement).getByRole('button', { name: 'Send a new link' });
    act(() => {
      button.click();
      button.click();
    });
    expect(api.reissue).toHaveBeenCalledTimes(1);
    finish({ url: LINK, expires_at: '2026-10-22T10:00:00Z' });
    await screen.findByLabelText('Invitation link');
  });

  it('can be copied by hand when the clipboard is refused: selected, and said only when it is', async () => {
    api.reissue.mockResolvedValue({ url: LINK, expires_at: '2026-10-22T10:00:00Z' });
    writeText.mockRejectedValue(new Error('denied'));
    const exec = vi.fn().mockReturnValue(false);
    Object.defineProperty(document, 'execCommand', { value: exec, configurable: true });
    shell(<UsersScreen />);
    fireEvent.click(within((await screen.findByText('Test Seller 02')).closest('li') as HTMLElement).getByRole('button', { name: 'Send a new link' }));
    await screen.findByLabelText('Invitation link');
    fireEvent.click(screen.getByRole('button', { name: 'Copy the link' }));
    expect(await screen.findByText(/The link is selected/)).toBeTruthy();
    expect(exec).toHaveBeenCalledWith('copy');
    const input = screen.getByLabelText('Invitation link') as HTMLInputElement;
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, LINK.length]);
    exec.mockReturnValue(true);
    fireEvent.click(screen.getByRole('button', { name: 'Copy the link' }));
    expect(await screen.findByText(/Copied/)).toBeTruthy();
    expect(screen.queryByText(/The link is selected/)).toBeNull();
    Reflect.deleteProperty(document, 'execCommand');
  });
});

describe('closing the box and coming back', () => {
  it('leaves no link on the next visit to the invite screen', async () => {
    api.invite.mockResolvedValue({ user: { id: 'u-new', full_name: 'Test Cashier 05' }, invitation: { url: LINK, expires_at: '2026-10-22T10:00:00Z' } });
    shell(<InviteScreen />);
    await fillInvite();
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    await screen.findByLabelText('Invitation link');
    cleanup();
    shell(<InviteScreen />);
    await screen.findByLabelText('Full name');
    expect(screen.queryByLabelText('Invitation link')).toBeNull();
    expectNoToken();
  });
});

describe('the staff list', () => {
  it('shows each person with the roles and branch and whether they have joined', async () => {
    shell(<UsersScreen />);
    const waiting = (await screen.findByText('Test Seller 02')).closest('li') as HTMLElement;
    expect(within(waiting).getByText('Invited, not joined yet')).toBeTruthy();
    expect(within(waiting).getByText('Sales person, Test Branch Two')).toBeTruthy();
    expect(within(waiting).getByText('+256 700 000 002')).toBeTruthy();
    const owner = screen.getByText('Test Owner 01').closest('li') as HTMLElement;
    expect(within(owner).getByText('Administrator, every branch')).toBeTruthy();
    expect(within(owner).getByText('Active')).toBeTruthy();
    expect(within(owner).getByText(/\(you\)/)).toBeTruthy();
    expect(within(screen.getByText('Test Leaver 04').closest('li') as HTMLElement).getByText('Deactivated')).toBeTruthy();
  });

  it('offers a new link only to someone still invited, and no deactivate on yourself or on someone already gone', async () => {
    shell(<UsersScreen />);
    await screen.findByText('Test Seller 02');
    expect(screen.getAllByRole('button', { name: 'Send a new link' })).toHaveLength(1);
    const owner = screen.getByText('Test Owner 01').closest('li') as HTMLElement;
    expect(within(owner).queryByRole('button', { name: 'Deactivate' })).toBeNull();
    expect(within(owner).getByRole('button', { name: 'Change roles' })).toBeTruthy();
    const leaver = screen.getByText('Test Leaver 04').closest('li') as HTMLElement;
    expect(within(leaver).queryByRole('button')).toBeNull();
  });

  it('says so when nobody is on the staff yet, and when a search finds nobody', async () => {
    api.list.mockResolvedValue([]);
    shell(<UsersScreen />);
    expect(await screen.findByText('No staff yet.')).toBeTruthy();
    cleanup();
    api.list.mockResolvedValue([SELF, WAITING]);
    shell(<UsersScreen />);
    await screen.findByText('Test Seller 02');
    fireEvent.change(screen.getByLabelText('Find a person'), { target: { value: 'zzz nobody' } });
    expect(await screen.findByText('No people for "zzz nobody".')).toBeTruthy();
  });

  it('shows an error when the list cannot be loaded', async () => {
    api.list.mockRejectedValue(new UsersError('Could not load your staff.', 500));
    shell(<UsersScreen />);
    expect((await screen.findByRole('alert')).textContent).toBe('Could not load your staff.');
  });

  it('asks before deactivating, and the last administrator is refused in plain words', async () => {
    api.deactivate.mockRejectedValue(new UsersError('A business must always keep one active administrator. Make someone else an administrator first, then try again.', 422, 'last_tenant_admin'));
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Admin 03')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Deactivate' }));
    expect(api.deactivate).not.toHaveBeenCalled();
    expect(within(card).getByText(/signed out at once/)).toBeTruthy();
    fireEvent.click(within(card).getByRole('button', { name: 'Yes, deactivate' }));
    await waitFor(() => expect(api.deactivate).toHaveBeenCalledWith('u-admin2'));
    expect((await screen.findByRole('alert')).textContent).toMatch(/keep one active administrator/);
  });

  it('keeps the person when the confirmation is declined', async () => {
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Admin 03')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Deactivate' }));
    fireEvent.click(within(card).getByRole('button', { name: 'Keep them' }));
    expect(api.deactivate).not.toHaveBeenCalled();
    expect(within(card).getByRole('button', { name: 'Deactivate' })).toBeTruthy();
  });

  it('changes roles: the whole list is sent, with the branch only for a role that is not the administrator', async () => {
    api.setRoles.mockResolvedValue({ ...WAITING });
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 02')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Change roles' }));
    fireEvent.change(within(card).getByLabelText('Role'), { target: { value: 'cashier' } });
    fireEvent.click(within(card).getByRole('checkbox', { name: 'Every shop and store' }));
    fireEvent.click(within(card).getByRole('button', { name: 'Save the roles' }));
    await waitFor(() => expect(api.setRoles).toHaveBeenCalledWith('u-wait', [{ role_key: 'cashier' }]));
    expect(await screen.findByText('The roles of Test Seller 02 are saved.')).toBeTruthy();
  });
});

describe('who sees what', () => {
  it('shows a reader the list and none of the actions', async () => {
    shell(<UsersScreen />, READER);
    await screen.findByText('Test Seller 02');
    expect(screen.queryByRole('link', { name: 'Invite someone' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Send a new link' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Change roles' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Deactivate' })).toBeNull();
    expect(screen.getByText(/Only an administrator can invite/)).toBeTruthy();
  });

  it('shows an administrator the Invite button', async () => {
    shell(<UsersScreen />);
    expect(await screen.findByRole('link', { name: 'Invite someone' })).toBeTruthy();
  });

  it('sends someone without the permission away from both pages, with no request', () => {
    shell(<UsersScreen />, ['core.settings.manage']);
    expect(screen.getByText(/^You do not have access to this page\. The owner or an administrator/)).toBeTruthy();
    cleanup();
    shell(<InviteScreen />, READER);
    expect(screen.getByText(/Only an administrator can invite staff/)).toBeTruthy();
    expect(api.list).not.toHaveBeenCalled();
    expect(api.roles).not.toHaveBeenCalled();
  });
});

describe('a role on a branch that has closed', () => {
  it('lists the closed branch as closed, asks for another, and will not save until one is chosen', async () => {
    api.list.mockResolvedValue([SELF, ON_CLOSED]);
    api.setRoles.mockResolvedValue(ON_CLOSED);
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Cashier 06')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Change roles' }));
    const old = within(card).getByRole('checkbox', { name: 'Test Old Branch (closed)' }) as HTMLInputElement;
    // The tick boxes show the branch the person really has, not "Every shop and store".
    expect(old.checked).toBe(true);
    expect((within(card).getByRole('checkbox', { name: 'Every shop and store' }) as HTMLInputElement).checked).toBe(false);
    expect(within(card).getByText(/This branch is closed. Take it off/)).toBeTruthy();
    expect((within(card).getByRole('button', { name: 'Save the roles' }) as HTMLButtonElement).getAttribute('data-not-ready')).toBe('true');
    fireEvent.click(old);
    fireEvent.click(within(card).getByRole('checkbox', { name: 'Test Branch Two (KLA)' }));
    expect(within(card).queryByText(/This branch is closed/)).toBeNull();
    fireEvent.click(within(card).getByRole('button', { name: 'Save the roles' }));
    await waitFor(() => expect(api.setRoles).toHaveBeenCalledWith('u-closed', [{ role_key: 'cashier', branch_id: 'b-two' }]));
  });

  it('is never offered for a new person', async () => {
    shell(<InviteScreen />);
    await fillInvite();
    expect(screen.getByRole('checkbox', { name: 'Test Branch Two (KLA)' })).toBeTruthy();
    expect(screen.queryByRole('checkbox', { name: /Test Old Branch/ })).toBeNull();
  });
});

// JUSTIFICATION-A3: a new test that the role editor's added row never offers a closed branch (issue #329).
describe('adding a role in the role editor', () => {
  it('does not offer a closed branch for the new role (issue #329)', async () => {
    api.list.mockResolvedValue([SELF, WAITING]);
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 02')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Change roles' }));
    fireEvent.click(within(card).getByRole('button', { name: 'Add another role' }));
    const second = within(card).getByLabelText('Role 2') as HTMLSelectElement;
    fireEvent.change(second, { target: { value: 'cashier' } });
    expect(within(card).queryByRole('checkbox', { name: /Test Old Branch/ })).toBeNull();
    expect(within(card).getAllByRole('checkbox', { name: 'Test Branch Two (KLA)' }).length).toBeGreaterThan(0);
  });
});

describe('taking the administrator role off yourself', () => {
  it('warns first, changes nothing until confirmed, then reads who you are again', async () => {
    api.setRoles.mockResolvedValue({ ...SELF, roles: [{ role_key: 'cashier' }] });
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Owner 01')).closest('li') as HTMLElement;
    const invalidate = vi.spyOn(lastClient, 'invalidateQueries');
    fireEvent.click(within(card).getByRole('button', { name: 'Change roles' }));
    fireEvent.change(within(card).getByLabelText('Role'), { target: { value: 'cashier' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Save the roles' }));
    expect(within(card).getByText(/taking the administrator role off/)).toBeTruthy();
    expect(api.setRoles).not.toHaveBeenCalled();
    await act(async () => { await new Promise((resolve) => setTimeout(resolve)); await new Promise((resolve) => setTimeout(resolve)); });
    fireEvent.click(within(card).getByRole('button', { name: 'Yes, remove my administrator role' }));
    await waitFor(() => expect(api.setRoles).toHaveBeenCalledWith('u-self', [{ role_key: 'cashier' }]));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['me'] }));
  });

  it('lets the warning be backed out of by changing the choice', async () => {
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Owner 01')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Change roles' }));
    fireEvent.change(within(card).getByLabelText('Role'), { target: { value: 'cashier' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Save the roles' }));
    fireEvent.change(within(card).getByLabelText('Role'), { target: { value: 'tenant_admin' } });
    expect(within(card).queryByText(/taking the administrator role off/)).toBeNull();
    expect(api.setRoles).not.toHaveBeenCalled();
  });

  it('does not warn when the roles are somebody else\'s', async () => {
    api.setRoles.mockResolvedValue(SECOND_ADMIN);
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Admin 03')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Change roles' }));
    fireEvent.change(within(card).getByLabelText('Role'), { target: { value: 'cashier' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Save the roles' }));
    await waitFor(() => expect(api.setRoles).toHaveBeenCalledWith('u-admin2', [{ role_key: 'cashier' }]));
    expect(screen.queryByText(/taking the administrator role off/)).toBeNull();
  });
});

describe('finding a person by phone', () => {
  it('finds a number however it is typed, and shows phones written to read', async () => {
    shell(<UsersScreen />);
    await screen.findByText('Test Seller 02');
    for (const typed of ['0700 000 002', '+256700000002', '700000002', '000 002']) {
      fireEvent.change(screen.getByLabelText('Find a person'), { target: { value: typed } });
      await waitFor(() => expect(screen.queryByText('Test Owner 01')).toBeNull(), { timeout: 3000 });
      expect(screen.getByText('Test Seller 02')).toBeTruthy();
    }
    // The digits that matched are marked, which splits the text; the whole line still reads as the phone.
    expect((screen.getByText('Test Seller 02').closest('li') as HTMLElement).textContent).toContain('+256 700 000 002');
  });
});

// JUSTIFICATION-A3: new tests for the new Reset sign-in action, appended after the last describe; the anchor is only the insertion point.
describe('Reset sign-in (issue #339)', () => {
  const SELLER: StaffUser = { id: 'u-seller', full_name: 'Test Seller 07', phone_e164: '+256700000007', status: 'active', roles: [{ role_key: 'retail_sales', branch_id: 'b-two' }] };
  const RESET_LINK = 'https://test.example/reset-password#token=invented-token-0001';

  beforeEach(() => api.list.mockResolvedValue([SELF, SELLER, SECOND_ADMIN, WAITING, LEFT]));

  it('is offered only for an active person who is not an administrator and not you', async () => {
    shell(<UsersScreen />);
    await screen.findByText('Test Seller 07');
    expect(screen.getAllByRole('button', { name: 'Reset sign-in' })).toHaveLength(1);
    expect(within(screen.getByText('Test Seller 07').closest('li') as HTMLElement).getByRole('button', { name: 'Reset sign-in' })).toBeTruthy();
    for (const name of ['Test Owner 01', 'Test Admin 03', 'Test Seller 02']) {
      expect(within(screen.getByText(name).closest('li') as HTMLElement).queryByRole('button', { name: 'Reset sign-in' })).toBeNull();
    }
  });

  it('is not offered to someone who may only read the list', async () => {
    shell(<UsersScreen />, READER);
    await screen.findByText('Test Seller 07');
    expect(screen.queryByRole('button', { name: 'Reset sign-in' })).toBeNull();
  });

  it('asks for the code from the authenticator app when you have two-step sign-in, sends it, and shows the link once', async () => {
    resetUserSignIn.mockResolvedValue({ url: RESET_LINK, expiresAt: '2026-10-22T10:00:00Z' });
    shell(<UsersScreen />, ADMIN, true);
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    fireEvent.change(within(card).getByLabelText('Code from your authenticator app'), { target: { value: '12' } });
    expect(within(card).getByText('Type the 6 digits from your authenticator app.')).toBeTruthy();
    expect((within(card).getByRole('button', { name: 'Make the link' }) as HTMLButtonElement).getAttribute('data-not-ready')).toBe('true');
    fireEvent.change(within(card).getByLabelText('Code from your authenticator app'), { target: { value: '123456' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Make the link' }));

    await screen.findByRole('heading', { name: 'Sign-in link for Test Seller 07' });
    expect(resetUserSignIn).toHaveBeenCalledTimes(1);
    expect(resetUserSignIn).toHaveBeenCalledWith('u-seller', { totpCode: '123456' });
    expect((screen.getByLabelText('Sign-in link') as HTMLInputElement).value).toBe(RESET_LINK);
    const whatsapp = screen.getByRole('link', { name: 'Send on WhatsApp' }) as HTMLAnchorElement;
    expect(whatsapp.href.startsWith('https://wa.me/256700000007?text=')).toBe(true);
    expect(decodeURIComponent(whatsapp.href)).toContain('choose a new password');
    expect(decodeURIComponent(whatsapp.href)).toContain(RESET_LINK);
    // The panel closed and the code is gone; the link is in the box and not in any cache.
    expect(screen.queryByLabelText('Code from your authenticator app')).toBeNull();
    expect(JSON.stringify(lastClient.getQueryCache().getAll().map((q) => q.state.data))).not.toContain('invented-token-0001');
    fireEvent.click(screen.getByRole('button', { name: 'I have sent it' }));
    expect(screen.queryByLabelText('Sign-in link')).toBeNull();
    expect(document.body.textContent).not.toContain('invented-token-0001');
  });

  it('asks for your own password when you have no authenticator app', async () => {
    resetUserSignIn.mockResolvedValue({ url: RESET_LINK, expiresAt: '2026-10-22T10:00:00Z' });
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    expect(within(card).queryByLabelText('Code from your authenticator app')).toBeNull();
    fireEvent.change(within(card).getByLabelText('Your own password'), { target: { value: 'invented-password-1' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Make the link' }));
    await screen.findByRole('heading', { name: 'Sign-in link for Test Seller 07' });
    expect(resetUserSignIn).toHaveBeenCalledWith('u-seller', { password: 'invented-password-1' });
  });

  it('shows a wrong code in plain words, keeps the panel open with the box empty, and shows no link', async () => {
    resetUserSignIn.mockRejectedValue(new SignInResetError('That code is not right. Codes change every 30 seconds; type the next one.', 422, 'invalid_mfa_code'));
    shell(<UsersScreen />, ADMIN, true);
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    fireEvent.change(within(card).getByLabelText('Code from your authenticator app'), { target: { value: '000000' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Make the link' }));
    expect(await within(card).findByText(/That code is not right/)).toBeTruthy();
    expect((within(card).getByLabelText('Code from your authenticator app') as HTMLInputElement).value).toBe('');
    expect(screen.queryByLabelText('Sign-in link')).toBeNull();
  });

  it('sends nothing when cancelled, and one request for two taps before the first answer', async () => {
    let finish: (v: { url: string; expiresAt: string }) => void = () => {};
    resetUserSignIn.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    fireEvent.click(within(card).getByRole('button', { name: 'Cancel' }));
    expect(within(card).queryByLabelText('Your own password')).toBeNull();
    expect(resetUserSignIn).not.toHaveBeenCalled();
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    fireEvent.change(within(card).getByLabelText('Your own password'), { target: { value: 'invented-password-1' } });
    const form = within(card).getByRole('form');
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(resetUserSignIn).toHaveBeenCalledTimes(1);
    await act(async () => finish({ url: RESET_LINK, expiresAt: '2026-10-22T10:00:00Z' }));
    await screen.findByRole('heading', { name: 'Sign-in link for Test Seller 07' });
  });

  // Issue #369, from the review of #360.
  it('says why nothing was sent when another action is still being sent, instead of clearing the password silently', async () => {
    let finish: (v: { url: string; expires_at: string }) => void = () => {};
    api.reissue.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    fireEvent.click(within(screen.getByText('Test Seller 02').closest('li') as HTMLElement).getByRole('button', { name: 'Send a new link' }));
    fireEvent.change(within(card).getByLabelText('Your own password'), { target: { value: 'invented-password-1' } });
    fireEvent.submit(within(card).getByRole('form'));
    expect(await within(card).findByText(/Another change is still being sent/)).toBeTruthy();
    expect(resetUserSignIn).not.toHaveBeenCalled();
    await act(async () => finish({ url: LINK, expires_at: '2026-10-22T10:00:00Z' }));
  });

  it('keeps the other actions disabled while the reset is being sent', async () => {
    let finish: (v: { url: string; expiresAt: string }) => void = () => {};
    resetUserSignIn.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    fireEvent.change(within(card).getByLabelText('Your own password'), { target: { value: 'invented-password-1' } });
    fireEvent.submit(within(card).getByRole('form'));
    const resend = within(screen.getByText('Test Seller 02').closest('li') as HTMLElement).getByRole('button', { name: 'Send a new link' }) as HTMLButtonElement;
    await waitFor(() => expect(resend.disabled).toBe(true));
    await act(async () => finish({ url: RESET_LINK, expiresAt: '2026-10-22T10:00:00Z' }));
    await waitFor(() => expect(resend.disabled).toBe(false));
  });

  it('reads who you are again when the server asks for the other kind of proof', async () => {
    resetUserSignIn.mockRejectedValue(new SignInResetError('Enter the current 6 digit code from your authenticator app.', 422, 'totp_required'));
    shell(<UsersScreen />);
    const invalidate = vi.spyOn(lastClient, 'invalidateQueries');
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    fireEvent.change(within(card).getByLabelText('Your own password'), { target: { value: 'invented-password-1' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Make the link' }));
    expect(await within(card).findByText(/6 digit code/)).toBeTruthy();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['me'] });
  });

  it('does not read who you are again for a wrong code', async () => {
    resetUserSignIn.mockRejectedValue(new SignInResetError('That code is not right.', 422, 'invalid_mfa_code'));
    shell(<UsersScreen />, ADMIN, true);
    const invalidate = vi.spyOn(lastClient, 'invalidateQueries');
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    fireEvent.change(within(card).getByLabelText('Code from your authenticator app'), { target: { value: '000000' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Make the link' }));
    expect(await within(card).findByText(/not right/)).toBeTruthy();
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ['me'] });
  });

  it('keeps the digits of a pasted code with a space in it', async () => {
    resetUserSignIn.mockResolvedValue({ url: RESET_LINK, expiresAt: '2026-10-22T10:00:00Z' });
    shell(<UsersScreen />, ADMIN, true);
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    const field = within(card).getByLabelText('Code from your authenticator app') as HTMLInputElement;
    expect(field.maxLength).toBe(-1);
    fireEvent.change(field, { target: { value: ' 123 456 ' } });
    expect(field.value).toBe('123456');
    fireEvent.click(within(card).getByRole('button', { name: 'Make the link' }));
    await screen.findByRole('heading', { name: 'Sign-in link for Test Seller 07' });
    expect(resetUserSignIn).toHaveBeenCalledWith('u-seller', { totpCode: '123456' });
  });

  it('warns that the link already shown closes when a reset link is made', async () => {
    api.reissue.mockResolvedValue({ url: LINK, expires_at: '2026-10-22T10:00:00Z' });
    shell(<UsersScreen />);
    const card = (await screen.findByText('Test Seller 07')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    expect(within(card).queryByRole('note')).toBeNull();
    fireEvent.click(within(card).getByRole('button', { name: 'Cancel' }));
    fireEvent.click(within(screen.getByText('Test Seller 02').closest('li') as HTMLElement).getByRole('button', { name: 'Send a new link' }));
    await screen.findByRole('heading', { name: 'New link for Test Seller 02' });
    fireEvent.click(within(card).getByRole('button', { name: 'Reset sign-in' }));
    expect(within(card).getByRole('note').textContent).toContain('The link for Test Seller 02 shown above closes when this one is made');
  });
});

// The Owner of the business (ADR-062, FR-IAM-18): the badge, the actions an administrator is not offered, and the transfer.
describe('the owner', () => {
  const OWNER: StaffUser = { id: 'u-owner', full_name: 'Test Owner 09', email: 'owner09@example.com', status: 'active', is_owner: true, roles: [{ role_key: 'tenant_admin' }] };
  const SELF_ADMIN: StaffUser = { ...SELF, full_name: 'Test Admin 01' };

  const card = (name: string) => screen.getByText(name).closest('li') as HTMLElement;

  it('shows the Owner badge and offers an administrator no action on the owner or another administrator', async () => {
    api.list.mockResolvedValue([OWNER, SELF_ADMIN, SECOND_ADMIN, WAITING]);
    shell(<UsersScreen />);
    await screen.findByText('Test Owner 09', {}, { timeout: 5000 });
    expect(within(card('Test Owner 09')).getByText('Owner')).toBeTruthy();
    expect(within(card('Test Owner 09')).queryByRole('button')).toBeNull();
    expect(within(card('Test Admin 03')).queryByRole('button')).toBeNull();
    expect(within(card('Test Seller 02')).getByRole('button', { name: 'Change roles' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Transfer ownership' })).toBeNull();
  });

  it('lets the owner hand ownership to an active administrator after saying what changes', async () => {
    api.list.mockResolvedValue([{ ...SELF, is_owner: true }, SECOND_ADMIN, WAITING, LEFT]);
    api.transferOwnership.mockResolvedValue({ ...SECOND_ADMIN, is_owner: true });
    shell(<UsersScreen />, ADMIN, false, true);
    await screen.findByText('Test Admin 03', {}, { timeout: 5000 });
    expect(screen.getByText(/Turn on two-step sign-in/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Transfer ownership' }));
    const form = screen.getByRole('form', { name: 'Transfer ownership' });
    // Only the other active administrators are offered.
    expect(within(form).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose an administrator', 'Test Admin 03']);
    fireEvent.change(within(form).getByLabelText('Who becomes the owner'), { target: { value: 'u-admin2' } });
    expect(within(form).getByText(/You stay an administrator/)).toBeTruthy();
    fireEvent.change(within(form).getByLabelText('Your own password'), { target: { value: 'Fabricated-Pass-2026' } });
    const yes = within(form).getByRole('button', { name: 'Yes, make Test Admin 03 the owner' });
    fireEvent.click(yes);
    fireEvent.click(yes);
    await screen.findByText(/Test Admin 03 is now the owner of the business/);
    expect(api.transferOwnership).toHaveBeenCalledTimes(1);
    expect(api.transferOwnership).toHaveBeenCalledWith('u-admin2', { password: 'Fabricated-Pass-2026' });
  });

  it('shows the refusal in plain words and keeps nothing typed', async () => {
    api.list.mockResolvedValue([{ ...SELF, is_owner: true }, SECOND_ADMIN]);
    api.transferOwnership.mockRejectedValue(new UsersError('That password is not right.', 422, 'invalid_password'));
    shell(<UsersScreen />, ADMIN, false, true);
    await screen.findByText('Test Admin 03', {}, { timeout: 5000 });
    fireEvent.click(screen.getByRole('button', { name: 'Transfer ownership' }));
    const form = screen.getByRole('form', { name: 'Transfer ownership' });
    fireEvent.change(within(form).getByLabelText('Who becomes the owner'), { target: { value: 'u-admin2' } });
    fireEvent.change(within(form).getByLabelText('Your own password'), { target: { value: 'Wrong-Pass-0000' } });
    await act(async () => {
      fireEvent.click(within(form).getByRole('button', { name: 'Yes, make Test Admin 03 the owner' }));
    });
    await waitFor(() => expect(within(form).getByText('That password is not right.')).toBeTruthy());
    expect((within(form).getByLabelText('Your own password') as HTMLInputElement).value).toBe('');
  });
});
