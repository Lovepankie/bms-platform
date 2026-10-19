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

const api = { list: vi.fn(), roles: vi.fn(), branches: vi.fn(), invite: vi.fn(), reissue: vi.fn(), setRoles: vi.fn(), deactivate: vi.fn() };

vi.mock('../../../api/users', async (original) => {
  const real = await original<typeof import('../../../api/users')>();
  const forward = (name: string) => (...args: unknown[]) => ((api as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, users: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});
vi.mock('../../../app/branding', () => ({ useShellBrand: () => ({ name: 'Test Trading Co' }) }));

const { UsersError, NETWORK_WORDS } = await import('../../../api/users');
const { UsersScreen } = await import('./page');
const { InviteScreen } = await import('./invite');

const ROLES = [
  { key: 'tenant_admin', name: 'Tenant admin', permissions: [] },
  { key: 'cashier', name: 'Cashier', permissions: [] },
  { key: 'retail_sales', name: 'Sales', permissions: [] },
];
const BRANCHES = [
  { id: 'b-head', code: 'HQ', name: 'Head office', status: 'active' },
  { id: 'b-two', code: 'KLA', name: 'Test Branch Two', status: 'active' },
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

function shell(node: ReactElement, permissions: string[] = ADMIN) {
  const me = { ...mockMe('admin'), permissions, user_id: 'u-self' };
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

async function fillInvite(overrides: { name?: string; phone?: string; role?: string } = {}) {
  await screen.findByRole('option', { name: 'Cashier' }, { timeout: 5000 });
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: overrides.name ?? 'Test Cashier 05' } });
  fireEvent.change(screen.getByLabelText('Phone number'), { target: { value: overrides.phone ?? '0700 000 005' } });
  fireEvent.change(screen.getByLabelText('What will this person do?'), { target: { value: overrides.role ?? 'cashier' } });
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
    expect(screen.getByText(/Takes and records money at the counter/)).toBeTruthy();
    expect(screen.getByLabelText('Where?')).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Every branch' })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Test Branch Two (KLA)' })).toBeTruthy();
    fireEvent.change(screen.getByLabelText('What will this person do?'), { target: { value: 'tenant_admin' } });
    expect(screen.queryByLabelText('Where?')).toBeNull();
    expect(screen.getByText('An administrator works in every branch.')).toBeTruthy();
  });

  it('sends the phone as stored, with the chosen branch', async () => {
    api.invite.mockResolvedValue({ user: { id: 'u-new', full_name: 'Test Cashier 05', phone_e164: '+256700000005' }, invitation: { url: LINK, expires_at: '2026-10-22T10:00:00Z' } });
    shell(<InviteScreen />);
    await fillInvite();
    fireEvent.change(screen.getByLabelText('Where?'), { target: { value: 'b-two' } });
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    await waitFor(() => expect(api.invite).toHaveBeenCalledWith({ full_name: 'Test Cashier 05', phone: '+256700000005', roles: [{ role_key: 'cashier', branch_id: 'b-two' }] }));
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
    expect((screen.getByRole('button', { name: 'Inviting' }) as HTMLButtonElement).disabled).toBe(true);
    finish({ user: { full_name: 'Test Cashier 05' }, invitation: { url: LINK, expires_at: '2026-10-22T10:00:00Z' } });
    await screen.findByRole('heading', { name: 'Test Cashier 05 is invited' });
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
    fireEvent.change(within(card).getByLabelText('Where?'), { target: { value: '' } });
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
    expect(screen.getByText('You do not have access to this page.')).toBeTruthy();
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
    const where = within(card).getByLabelText('Where?') as HTMLSelectElement;
    // The select shows the branch the person really has, not "Every branch".
    expect(where.value).toBe('b-old');
    expect(within(card).getByRole('option', { name: 'Test Old Branch (closed)' })).toBeTruthy();
    expect(within(card).getByText(/This branch is closed. Pick another branch/)).toBeTruthy();
    expect((within(card).getByRole('button', { name: 'Save the roles' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(where, { target: { value: 'b-two' } });
    expect(within(card).queryByText(/This branch is closed/)).toBeNull();
    fireEvent.click(within(card).getByRole('button', { name: 'Save the roles' }));
    await waitFor(() => expect(api.setRoles).toHaveBeenCalledWith('u-closed', [{ role_key: 'cashier', branch_id: 'b-two' }]));
  });

  it('is never offered for a new person', async () => {
    shell(<InviteScreen />);
    await fillInvite();
    expect(screen.getByRole('option', { name: 'Test Branch Two (KLA)' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: /Test Old Branch/ })).toBeNull();
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
