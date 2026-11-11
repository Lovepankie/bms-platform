// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StaffUser } from '../../../api/users';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { DoneToast } from '../retail/toast';

// My details and Change email or phone (issue #883) against the real typed client, with a stand-in for the server. The server keeps
// every rule (ADR-062): the Owner's own email or phone needs the Owner's password first, and an administrator's contact is the
// Owner's alone. These tests prove the screens send the proof, never send without it, and show each refusal in the client's plain
// words. Every name, number, address and password below is invented.

const server = vi.hoisted(() => {
  const state: { handle: (method: string, path: string, body: unknown, request: Request) => Response; calls: { method: string; path: string; body: unknown; ifMatch: string | null }[] } = {
    handle: () => new Response('{}', { status: 404 }),
    calls: [],
  };
  // The client builds a Request from a relative address, which Node cannot; give it a base.
  const Base = globalThis.Request;
  globalThis.Request = class extends Base {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
    }
  } as typeof Request;
  globalThis.fetch = (async (input: Request | string, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init);
    const path = new URL(request.url).pathname;
    const text = request.method === 'GET' ? '' : await request.clone().text();
    const body = text === '' ? null : (JSON.parse(text) as unknown);
    state.calls.push({ method: request.method, path, body, ifMatch: request.headers.get('If-Match') });
    return state.handle(request.method, path, body, request);
  }) as typeof fetch;
  return state;
});

vi.mock('../../../app/branding', () => ({ useShellBrand: () => ({ name: 'Test Trading Co' }) }));

const { MyDetailsSheet } = await import('./contact-editor');
const { UsersScreen } = await import('./page');

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': status < 300 ? 'application/json' : 'application/problem+json' } });
const refusal = (status: number, code: string, detail: string) => json(status, { type: 'about:blank', title: 'Refused', status, code, detail });

const OWNER: StaffUser = { id: 'u-owner', full_name: 'Test Owner 01', email: 'owner01@example.test', phone_e164: '+256700000101', status: 'active', version: 3, is_owner: true, roles: [{ role_key: 'tenant_admin' }] };
const ADMIN_USER: StaffUser = { id: 'u-admin', full_name: 'Test Admin 02', email: 'admin02@example.test', status: 'active', version: 1, roles: [{ role_key: 'tenant_admin' }] };
const SELLER: StaffUser = { id: 'u-seller', full_name: 'Test Seller 03', phone_e164: '+256700000103', status: 'active', version: 5, roles: [{ role_key: 'retail_sales' }] };

const MANAGE = ['core.users.read', 'core.users.manage', 'core.branches.read'];

function meOf(user: StaffUser, permissions: string[], isOwner: boolean) {
  return { ...mockMe('admin'), permissions, user_id: user.id, full_name: user.full_name, email: user.email, phone_e164: user.phone_e164, mfa_enabled: false, is_owner: isOwner };
}

function shell(node: ReactElement, me: ReturnType<typeof meOf>) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/users'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>{node}</StaffContext.Provider>
        <DoneToast />
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

vi.setConfig({ testTimeout: 15000 });

afterEach(() => {
  cleanup();
  server.calls = [];
  server.handle = () => new Response('{}', { status: 404 });
});

const patches = () => server.calls.filter((c) => c.method === 'PATCH');

/** The server as ADR-062 says: the Owner's own contact needs the password, which is the invented one below. */
function ownerServer(owner: StaffUser) {
  server.handle = (method, path, body) => {
    if (method === 'GET' && path === `/api/v1/users/${owner.id}`) return json(200, owner);
    if (method === 'PATCH' && path === `/api/v1/users/${owner.id}`) {
      const change = body as { email?: string; password?: string };
      if (change.password === undefined) return refusal(422, 'password_required', 'Send your password to change your email or phone.');
      if (change.password !== 'Test-Password-1') return refusal(422, 'invalid_password', 'Wrong password.');
      return json(200, { ...owner, email: change.email ?? owner.email, version: (owner.version ?? 0) + 1 });
    }
    return json(404, {});
  };
}

describe('My details for the Owner (issue #883, ADR-062)', () => {
  it('changes the Owner\'s own email after the password is typed on a second step', async () => {
    ownerServer(OWNER);
    const onClose = vi.fn();
    shell(<MyDetailsSheet me={meOf(OWNER, MANAGE, true)} open onClose={onClose} />, meOf(OWNER, MANAGE, true));
    const email = await screen.findByLabelText('Email address');
    expect(screen.getByLabelText(/Full name/)).toHaveProperty('value', 'Test Owner 01');
    fireEvent.change(email, { target: { value: 'Owner01.New@Example.test ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    const proof = await screen.findByLabelText(/Your own password/);
    expect(screen.getByText('owner01.new@example.test')).toBeTruthy();
    expect(patches()).toHaveLength(0);
    fireEvent.change(proof, { target: { value: 'Test-Password-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the change' }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(patches()).toEqual([{ method: 'PATCH', path: '/api/v1/users/u-owner', body: { email: 'owner01.new@example.test', password: 'Test-Password-1' }, ifMatch: '"3"' }]);
    expect(await screen.findByText('Your details are saved')).toBeTruthy();
  });

  it('sends nothing without the password, and a wrong one is refused in plain words with the box emptied', async () => {
    ownerServer(OWNER);
    shell(<MyDetailsSheet me={meOf(OWNER, MANAGE, true)} open onClose={() => {}} />, meOf(OWNER, MANAGE, true));
    fireEvent.change(await screen.findByLabelText('Email address'), { target: { value: 'owner01.new@example.test' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Save the change' }));
    expect(await screen.findByText('Type your own password.')).toBeTruthy();
    expect(patches()).toHaveLength(0);

    // A person types between two presses; the busy button's double-tap guard lets go a moment after the first.
    await new Promise((resolve) => setTimeout(resolve, 20));
    fireEvent.change(screen.getByLabelText(/Your own password/), { target: { value: 'Not-The-Password-9' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the change' }));
    expect(await screen.findByText(/That password is not right/)).toBeTruthy();
    expect(screen.getByLabelText(/Your own password/)).toHaveProperty('value', '');
    // Only the server's answer shows; the emptied box says nothing of its own until the next press.
    expect(screen.queryByText('Type your own password.')).toBeNull();
  });

  it('shows the server\'s refusal in plain words when the screen did not yet know the person is the Owner, then asks for the password', async () => {
    ownerServer(OWNER);
    const onClose = vi.fn();
    // /me is out of date: the person was made the Owner after it was read, so the first save goes without the password.
    shell(<MyDetailsSheet me={meOf(OWNER, MANAGE, false)} open onClose={onClose} />, meOf(OWNER, MANAGE, false));
    fireEvent.change(await screen.findByLabelText('Email address'), { target: { value: 'owner01.new@example.test' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Enter your own password to confirm.')).toBeTruthy();
    expect(patches()[0]?.body).toEqual({ email: 'owner01.new@example.test' });
    // Back and Save again asks for the password first, without a second request the server would refuse.
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    await new Promise((resolve) => setTimeout(resolve, 20));
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }));
    await screen.findByLabelText(/Your own password/);
    expect(patches()).toHaveLength(1);
    await new Promise((resolve) => setTimeout(resolve, 20));
    fireEvent.change(screen.getByLabelText(/Your own password/), { target: { value: 'Test-Password-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the change' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(patches()[1]?.body).toEqual({ email: 'owner01.new@example.test', password: 'Test-Password-1' });
  });

  it('shows the step-up refusal in plain words', async () => {
    server.handle = (method, path) => (method === 'GET' && path === '/api/v1/users/u-owner' ? json(200, OWNER) : refusal(422, 'step_up_required', 'Step up first.'));
    shell(<MyDetailsSheet me={meOf(OWNER, MANAGE, false)} open onClose={() => {}} />, meOf(OWNER, MANAGE, false));
    fireEvent.change(await screen.findByLabelText('Phone number'), { target: { value: '0700 000 109' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Enter your password or authenticator code to change your email or phone.')).toBeTruthy();
    expect(patches()[0]?.body).toEqual({ phone: '+256700000109' });
  });

  it('asks for the name with a red star and refuses an empty saved email before sending', async () => {
    ownerServer(OWNER);
    shell(<MyDetailsSheet me={meOf(OWNER, MANAGE, true)} open onClose={() => {}} />, meOf(OWNER, MANAGE, true));
    const name = await screen.findByLabelText(/Full name/);
    expect(name.getAttribute('aria-required')).toBe('true');
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Type the new email. A saved email cannot be left empty.')).toBeTruthy();
    expect(patches()).toHaveLength(0);
  });
});

describe('My details for someone who may not manage the staff', () => {
  it('shows the details, asks nothing of the server and says to ask an administrator', async () => {
    const me = meOf(SELLER, ['retail.sale.create'], false);
    shell(<MyDetailsSheet me={me} open onClose={() => {}} />, me);
    expect(await screen.findByText('To change your name, phone or email, ask an administrator of your business.')).toBeTruthy();
    expect(screen.getByText('+256 700 000 103')).toBeTruthy();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(server.calls).toHaveLength(0);
  });
});

describe('Change email or phone on the Users page', () => {
  function listServer(people: StaffUser[], onPatch: (path: string, body: unknown) => Response) {
    server.handle = (method, path, body) => {
      if (method === 'GET' && path === '/api/v1/users') return json(200, { items: people });
      if (method === 'GET' && path === '/api/v1/roles') return json(200, { items: [{ key: 'tenant_admin', name: 'Tenant admin' }, { key: 'retail_sales', name: 'Sales' }] });
      if (method === 'GET' && path === '/api/v1/branches') return json(200, { items: [] });
      if (method === 'PATCH') return onPatch(path, body);
      return json(404, {});
    };
  }

  it('lets an administrator change a seller\'s phone, with no proof and only the phone sent', async () => {
    listServer([OWNER, ADMIN_USER, SELLER], () => json(200, { ...SELLER, phone_e164: '+256700000199', version: 6 }));
    shell(<UsersScreen />, meOf(ADMIN_USER, MANAGE, false));
    const card = (await screen.findByText('Test Seller 03')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Change email or phone' }));
    fireEvent.change(within(card).getByLabelText('Phone number'), { target: { value: '0700 000 199' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('New email or phone saved for Test Seller 03')).toBeTruthy();
    expect(patches()).toEqual([{ method: 'PATCH', path: '/api/v1/users/u-seller', body: { phone: '+256700000199' }, ifMatch: '"5"' }]);
  });

  it('offers an administrator who is not the Owner no change on the Owner\'s card, and says why', async () => {
    listServer([OWNER, ADMIN_USER, SELLER], () => json(500, {}));
    shell(<UsersScreen />, meOf(ADMIN_USER, MANAGE, false));
    const card = (await screen.findByText('Test Owner 01')).closest('li') as HTMLElement;
    expect(within(card).queryByRole('button', { name: 'Change email or phone' })).toBeNull();
    expect(within(card).getByText('Only the owner can change the owner\'s email or phone, from My details.')).toBeTruthy();
    const other = (await screen.findByText('Test Admin 02')).closest('li') as HTMLElement;
    expect(within(other).getByRole('button', { name: 'Change email or phone' })).toBeTruthy();
  });

  it('shows the server\'s refusal for the Owner\'s contact in plain words to an administrator who is not the Owner', async () => {
    // The list was read before the operator made Test Owner 01 the Owner (ADR-062 decision 5), so the card still offers the
    // change; the server refuses it.
    const unmarked = { ...OWNER, is_owner: false };
    listServer([unmarked, ADMIN_USER, SELLER], () => refusal(403, 'owner_protected', 'The owner cannot be changed.'));
    shell(<UsersScreen />, meOf(ADMIN_USER, MANAGE, false));
    const card = (await screen.findByText('Test Owner 01')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Change email or phone' }));
    fireEvent.change(within(card).getByLabelText('Email address'), { target: { value: 'someone.else@example.test' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Save' }));
    expect(
      await within(card).findByText('Nobody can remove, deactivate or change the owner of the business. The owner can hand ownership to another administrator.'),
    ).toBeTruthy();
    expect(patches()[0]?.body).toEqual({ email: 'someone.else@example.test' });
    expect(patches()[0]?.body).not.toHaveProperty('password');
  });

  it('shows the server\'s refusal for another administrator\'s contact in plain words', async () => {
    const second = { ...ADMIN_USER, id: 'u-admin3', full_name: 'Test Admin 04', email: 'admin04@example.test' };
    // No Owner on the list yet, so every administrator is offered the change; the server, which knows the Owner, refuses it.
    listServer([{ ...OWNER, is_owner: false }, ADMIN_USER, second], () => refusal(403, 'owner_only', 'Owner only.'));
    shell(<UsersScreen />, meOf(ADMIN_USER, MANAGE, false));
    const card = (await screen.findByText('Test Admin 04')).closest('li') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Change email or phone' }));
    fireEvent.change(within(card).getByLabelText('Email address'), { target: { value: 'admin04.new@example.test' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Save' }));
    expect(await within(card).findByText('Only the owner of the business can add, remove or change an administrator.')).toBeTruthy();
  });
});
