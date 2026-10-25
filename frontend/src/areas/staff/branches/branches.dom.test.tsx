// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AdminBranch } from '../../../api/branches';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// The Branches screen in a browser (issue #329) with the API client replaced: closed branches are hidden until asked for and carry a
// Closed badge, closing an empty branch asks once, closing over stock shows the server's warning with the stock value and needs a
// reason before it is sent again, nothing is sent twice by a double tap, a reopen and the stocking switch work, and someone who may only
// read sees the list and no action. Each open shop has a logo control for an administrator (issue #461). Every name below is invented.

const api = { list: vi.fn(), create: vi.fn(), close: vi.fn(), reopen: vi.fn(), setStocking: vi.fn(), setSelling: vi.fn(), uploadLogo: vi.fn(), removeLogo: vi.fn() };

vi.mock('../../../api/branches', async (original) => {
  const real = await original<typeof import('../../../api/branches')>();
  const forward = (name: string) => (...args: unknown[]) => ((api as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, branches: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});

const { BranchesError } = await import('../../../api/branches');
const { BranchesScreen } = await import('./page');

const HEAD: AdminBranch = { id: 'b-head', code: 'HQ', name: 'Head office', is_head_office: true, status: 'active', stocks_goods: true, version: 1 };
const SHOP: AdminBranch = { id: 'b-two', code: 'KLA', name: 'Test Shop Two', is_head_office: false, status: 'active', stocks_goods: true, version: 3 };
const OLD: AdminBranch = {
  id: 'b-old', code: 'OLD', name: 'Test Old Shop', is_head_office: false, status: 'inactive', stocks_goods: true, version: 5,
  closed_at: '2026-08-14T10:00:00Z', closed_reason: 'Test shop shut for good', stock_items: 2, stock_value_minor: 45000,
};

const ADMIN = ['core.branches.read', 'core.branches.manage'];

function shell(permissions: string[] = ADMIN) {
  const me = { ...mockMe('admin'), permissions, user_id: 'u-self' };
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/setup/branches'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <BranchesScreen />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

vi.setConfig({ testTimeout: 15000 });

beforeEach(() => {
  api.list.mockResolvedValue([HEAD, SHOP, OLD]);
});

afterEach(() => {
  cleanup();
  Object.values(api).forEach((m) => m.mockReset());
});

describe('the list', () => {
  it('hides closed branches until asked for, then shows the Closed badge, the date and the reason', async () => {
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    expect(screen.queryByText('Test Old Shop (OLD)')).toBeNull();
    fireEvent.click(screen.getByLabelText(/Show closed branches \(1\)/));
    expect(await screen.findByText('Test Old Shop (OLD)')).toBeTruthy();
    expect(screen.getByText('Closed', { selector: '.badge' })).toBeTruthy();
    expect(screen.getByText('Closed on 14 Aug 2026')).toBeTruthy();
    expect(screen.getByText('Reason: Test shop shut for good')).toBeTruthy();
    expect(screen.getByText(/Still holds 2 items worth UGX 45,000, kept on record/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Reopen this branch' })).toBeTruthy();
  });

  it('offers no close for the head office, and a reader sees no action at all', async () => {
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    expect(screen.getAllByRole('button', { name: 'Close this branch' })).toHaveLength(1);
    cleanup();
    shell(['core.branches.read']);
    await screen.findByText('Test Shop Two (KLA)');
    expect(screen.getByText('Only an administrator can add, close or reopen a branch.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Close this branch' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Mark as keeping no goods/ })).toBeNull();
  });
});

describe('closing', () => {
  it('asks once, then closes an empty branch with no reason', async () => {
    api.close.mockResolvedValue({ ...SHOP, status: 'inactive' });
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    fireEvent.click(screen.getByRole('button', { name: 'Close this branch' }));
    expect(screen.getByText(/Its sales, stock records and history are kept/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, close it' }));
    fireEvent.click(screen.getByRole('button', { name: /Yes, close it|Closing/ }));
    await waitFor(() => expect(api.close).toHaveBeenCalledTimes(1));
    expect(api.close.mock.calls[0]?.[0]).toMatchObject({ id: 'b-two' });
    expect(api.close.mock.calls[0]?.[1]).toBeUndefined();
    expect(await screen.findByText('Test Shop Two (KLA) is closed. Its history is kept.')).toBeTruthy();
  });

  it('shows the warning with the stock value and sends the reason the second time', async () => {
    const warning = new BranchesError(
      'Test Shop Two still holds stock worth UGX 20,000 (1 item). Closing keeps all of it on record and posts nothing. To close it anyway, say why.',
      422,
      'branch_close_needs_reason',
      { currency: 'UGX', stockItems: 1, stockValueMinor: 20000 },
    );
    api.close.mockRejectedValueOnce(warning).mockResolvedValueOnce({ ...SHOP, status: 'inactive' });
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    fireEvent.click(screen.getByRole('button', { name: 'Close this branch' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, close it' }));
    expect(await screen.findByText(/still holds stock worth UGX 20,000/)).toBeTruthy();
    const anyway = screen.getByRole('button', { name: 'Close it anyway' }) as HTMLButtonElement;
    expect(anyway.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Why are you closing it?'), { target: { value: 'Test shop shut for good' } });
    expect(ready(anyway)).toBe(true);
    fireEvent.click(anyway);
    await waitFor(() => expect(api.close).toHaveBeenCalledTimes(2));
    expect(api.close.mock.calls[1]?.[1]).toBe('Test shop shut for good');
    expect(await screen.findByText(/is closed/)).toBeTruthy();
  });

  it('shows any other refusal in plain words and keeps the panel closed', async () => {
    api.close.mockRejectedValue(new BranchesError('This branch still has open loans, savings or investments. Close or move those first.', 422, 'branch_has_open_accounts'));
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    fireEvent.click(screen.getByRole('button', { name: 'Close this branch' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, close it' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText(/still has open loans/)).toBeTruthy();
  });
});

describe('reopening and the stocking switch', () => {
  it('reopens a closed branch', async () => {
    api.reopen.mockResolvedValue({ ...OLD, status: 'active' });
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    fireEvent.click(screen.getByLabelText(/Show closed branches/));
    fireEvent.click(await screen.findByRole('button', { name: 'Reopen this branch' }));
    await waitFor(() => expect(api.reopen).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('Test Old Shop (OLD) is open again.')).toBeTruthy();
  });

  it('marks a head office as keeping no goods, and shows the refusal in plain words when it holds stock', async () => {
    api.setStocking
      .mockRejectedValueOnce(new BranchesError('Head office still holds stock worth UGX 3,000 (1 item). Move the stock to a shop first.', 422, 'branch_still_has_stock'))
      .mockResolvedValueOnce({ ...HEAD, stocks_goods: false });
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    fireEvent.click(screen.getAllByRole('button', { name: 'Mark as keeping no goods' })[0] as HTMLElement);
    // One tap only asks: nothing is sent until the person confirms in plain words.
    expect(screen.getByText(/Nothing can be sold, bought in, received or counted there afterwards/)).toBeTruthy();
    expect(api.setStocking).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, mark it' }));
    expect(await screen.findByText(/still holds stock worth UGX 3,000/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, mark it' }));
    await waitFor(() => expect(api.setStocking).toHaveBeenCalledTimes(2));
    expect(api.setStocking.mock.calls[1]?.[1]).toBe(false);
  });
});

describe('the store switch (#366)', () => {
  it('asks in plain words, then makes a shop a store, and shows the refusal over a credit sale still owing', async () => {
    api.setSelling
      .mockRejectedValueOnce(new BranchesError('Test Shop Two (KLA) has 2 credit sales that still owe money. Let the buyers pay first.', 422, 'branch_has_open_credit_sales'))
      .mockResolvedValueOnce({ ...SHOP, sells_goods: false });
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    fireEvent.click(screen.getAllByRole('button', { name: 'Mark as a store (does not sell)' })[1] as HTMLElement);
    // One tap only asks: nothing is sent until the person confirms.
    expect(screen.getByText(/No takings, savings, banking or repayments here/)).toBeTruthy();
    expect(api.setSelling).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, make it a store' }));
    expect(await screen.findByText(/2 credit sales that still owe money/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, make it a store' }));
    await waitFor(() => expect(api.setSelling).toHaveBeenCalledTimes(2));
    expect(api.setSelling.mock.calls[1]?.[0]).toMatchObject({ id: 'b-two' });
    expect(api.setSelling.mock.calls[1]?.[1]).toBe(false);
    expect(await screen.findByText(/is now a store. It holds stock but does not sell/)).toBeTruthy();
  });

  it('marks a store with a badge and offers the way back', async () => {
    api.list.mockResolvedValue([HEAD, { ...SHOP, sells_goods: false }]);
    api.setSelling.mockResolvedValue({ ...SHOP, sells_goods: true });
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    expect(screen.getByText('Store (does not sell)', { selector: '.badge' })).toBeTruthy();
    // A store keeps goods by definition, so the keeps-no-goods switch is not offered on it (head office still has it).
    expect(screen.getAllByRole('button', { name: 'Mark as keeping no goods' })).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Sells again (make it a shop)' }));
    await waitFor(() => expect(api.setSelling).toHaveBeenCalledTimes(1));
    expect(api.setSelling.mock.calls[0]?.[1]).toBe(true);
  });
});

describe('the shop logo (#461)', () => {
  const LOGO = '/api/v1/branches/b-two/logo?v=doc-1';

  it('uploads a logo for one shop, then shows it and removes it', async () => {
    api.uploadLogo.mockResolvedValue({ ...SHOP, logo_url: LOGO, version: 4 });
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    // Every open branch has its own control; the closed one has none.
    expect(screen.getAllByLabelText(/Upload a logo \(PNG, JPEG or WebP/)).toHaveLength(2);
    expect(screen.getAllByText('No logo of its own: its receipts and documents carry the business logo.')).toHaveLength(2);

    const file = new File([new Uint8Array([1, 2, 3])], 'shop.png', { type: 'image/png' });
    const control = screen.getByRole('group', { name: 'Logo of Test Shop Two (KLA)' });
    api.list.mockResolvedValue([HEAD, { ...SHOP, logo_url: LOGO, version: 4 }, OLD]);
    fireEvent.change(control.querySelector('input[type="file"]') as HTMLInputElement, { target: { files: [file] } });
    await screen.findByText('Logo saved for Test Shop Two (KLA). Its receipts and documents now carry it.');
    expect(api.uploadLogo).toHaveBeenCalledTimes(1);
    expect(api.uploadLogo.mock.calls[0]?.[0]).toMatchObject({ id: 'b-two' });
    expect(api.uploadLogo.mock.calls[0]?.[1]).toBe(file);

    const image = (await screen.findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    expect(screen.getByLabelText(/Replace the logo/)).toBeTruthy();

    api.removeLogo.mockResolvedValue({ ...SHOP, version: 5 });
    api.list.mockResolvedValue([HEAD, { ...SHOP, version: 5 }, OLD]);
    fireEvent.click(screen.getByRole('button', { name: 'Remove the logo' }));
    await screen.findByText('Logo removed from Test Shop Two (KLA). It shows the business logo again.');
    expect(api.removeLogo).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.queryByAltText('Logo of Test Shop Two (KLA)')).toBeNull());
  });

  it('shows the server refusal in plain words', async () => {
    api.uploadLogo.mockRejectedValue(new BranchesError('The image is too small: it needs at least 128 px on its short side.', 422, 'image_too_small'));
    shell();
    await screen.findByText('Test Shop Two (KLA)');
    const control = screen.getByRole('group', { name: 'Logo of Test Shop Two (KLA)' });
    fireEvent.change(control.querySelector('input[type="file"]') as HTMLInputElement, {
      target: { files: [new File([new Uint8Array([1])], 'tiny.png', { type: 'image/png' })] },
    });
    expect((await screen.findByRole('alert')).textContent).toContain('at least 128 px');
  });

  it('shows the logo to a reader, with no control to change it', async () => {
    api.list.mockResolvedValue([HEAD, { ...SHOP, logo_url: LOGO }]);
    shell(['core.branches.read']);
    expect(((await screen.findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement).getAttribute('src')).toBe(LOGO);
    expect(screen.queryByLabelText(/Upload a logo/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove the logo' })).toBeNull();
  });
});

describe('adding a branch (#581)', () => {
  const STORE: AdminBranch = { id: 'b-new', code: 'GAYAZA', name: 'Test Gayaza Store', is_head_office: false, status: 'active', stocks_goods: true, sells_goods: false, version: 1 };

  async function openForm() {
    await screen.findByText('Test Shop Two (KLA)');
    fireEvent.click(screen.getByRole('button', { name: 'Add a branch' }));
    return screen.getByRole('form', { name: 'Add a branch' });
  }

  const type = (label: RegExp, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

  it.each([
    ['Shop (sells)', { sells_goods: true, stocks_goods: true }, 'Test Kasubi Shop added. It is a shop: it keeps stock and sells.'],
    ['Store (keeps stock, does not sell)', { sells_goods: false, stocks_goods: true }, 'Test Kasubi Shop added. It is a store: it keeps stock and does not sell.'],
    ['Head office (keeps no goods)', { sells_goods: true, stocks_goods: false }, 'Test Kasubi Shop added. It keeps no goods, like a head office.'],
  ])('adds a %s with no code typed, then says so in a dark toast and lists it', async (kind, flags, words) => {
    api.create.mockResolvedValue({ ...SHOP, id: 'b-new', code: 'TESTKA', name: 'Test Kasubi Shop', ...flags });
    shell();
    await openForm();
    expect(screen.getByText(/are needed\./)).toBeTruthy();
    type(/^Name/, '  Test Kasubi Shop ');
    fireEvent.click(screen.getByRole('radio', { name: kind }));
    api.list.mockResolvedValue([HEAD, SHOP, { ...SHOP, id: 'b-new', code: 'TESTKA', name: 'Test Kasubi Shop', ...flags }]);
    fireEvent.click(screen.getByRole('button', { name: 'Add the branch' }));
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1));
    expect(api.create.mock.calls[0]?.[0]).toEqual({ name: 'Test Kasubi Shop', location: undefined, ...flags });
    const toast = await screen.findByText(words);
    expect(toast.closest('.toast')).toBeTruthy();
    expect(await screen.findByText('Test Kasubi Shop (TESTKA)')).toBeTruthy();
    expect(screen.queryByRole('form', { name: 'Add a branch' })).toBeNull();
  });

  it('asks for no code and shows the one the app makes from the name', async () => {
    api.create.mockResolvedValue(STORE);
    shell();
    await openForm();
    expect(screen.queryByLabelText(/^Code/)).toBeNull();
    expect(screen.getByText('The app makes the branch code from the name.')).toBeTruthy();
    type(/^Name/, 'Test Gayaza Store');
    expect(screen.getByText('Code TESTGA, made by the app from the name.')).toBeTruthy();
    // A code already listed takes a number, as the server does.
    type(/^Name/, 'Klaus');
    expect(screen.getByText('Code KLAUS, made by the app from the name.')).toBeTruthy();
    type(/^Name/, 'KLA');
    expect(screen.getByText('Code KLA2, made by the app from the name.')).toBeTruthy();
    type(/^Name/, 'Test Gayaza Store');
    fireEvent.click(screen.getByRole('radio', { name: 'Store (keeps stock, does not sell)' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add the branch' }));
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1));
    expect(api.create.mock.calls[0]?.[0]).toEqual({ name: 'Test Gayaza Store', location: undefined, sells_goods: false, stocks_goods: true });
    expect(await screen.findByText('Test Gayaza Store added. It is a store: it keeps stock and does not sell.')).toBeTruthy();
  });

  it('keeps the chosen kind and its focus on the same control while the person types', async () => {
    shell();
    await openForm();
    const group = screen.getByRole('radiogroup', { name: 'What is it?' });
    const store = screen.getByRole('radio', { name: 'Store (keeps stock, does not sell)' });
    const office = screen.getByRole('radio', { name: 'Head office (keeps no goods)' });
    store.focus();
    fireEvent.click(store);
    expect(store.getAttribute('aria-checked')).toBe('true');
    // The form renders again on every key in the name; the picker is the same element, so the focused option keeps focus.
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Test Kasubi Store' } });
    expect(document.activeElement).toBe(store);
    expect(screen.getByRole('radiogroup', { name: 'What is it?' })).toBe(group);
    fireEvent.click(office);
    expect(office.getAttribute('aria-checked')).toBe('true');
    expect(store.getAttribute('aria-checked')).toBe('false');
    expect(screen.getByRole('radio', { name: 'Store (keeps stock, does not sell)' })).toBe(store);
    expect(screen.getByText('Keeps no goods. Cash records and expenses still work.')).toBeTruthy();
  });

  it('needs a name, marked with the asterisk, and sends nothing without it', async () => {
    shell();
    await openForm();
    expect(screen.getByLabelText(/^Name/).getAttribute('aria-required')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: 'Add the branch' }));
    expect(await screen.findByText('Type the branch name.')).toBeTruthy();
    expect(api.create).not.toHaveBeenCalled();
  });

  it('says a full plan in plain words with a link to Plan and billing, never the raw refusal', async () => {
    api.create.mockRejectedValue(new BranchesError('The plan allows at most 2 (max_branches); the limit has been reached.', 422, 'plan_limit_reached'));
    shell([...ADMIN, 'core.billing.read']);
    await openForm();
    type(/^Name/, 'Test One Too Many');
    fireEvent.click(screen.getByRole('button', { name: 'Add the branch' }));
    expect(await screen.findByText(/Your plan has no room for another branch/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open Plan and billing' }).getAttribute('href')).toBe('/staff/setup/billing');
    expect(screen.queryByText(/max_branches/)).toBeNull();
    expect(screen.getByRole('form', { name: 'Add a branch' })).toBeTruthy();
  });

  it('tells someone who may not open Plan and billing to ask the owner', async () => {
    api.create.mockRejectedValue(new BranchesError('The plan allows at most 2 (max_branches); the limit has been reached.', 422, 'plan_limit_reached'));
    shell();
    await openForm();
    type(/^Name/, 'Test One Too Many');
    fireEvent.click(screen.getByRole('button', { name: 'Add the branch' }));
    expect(await screen.findByText(/Ask the owner to change the plan/)).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Open Plan and billing' })).toBeNull();
  });

  it('offers the button only to someone with core.branches.manage', async () => {
    shell(['core.branches.read']);
    await screen.findByText('Test Shop Two (KLA)');
    expect(screen.queryByRole('button', { name: 'Add a branch' })).toBeNull();
  });
});
