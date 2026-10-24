// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CollateralApi } from '../../../api/collateral';
import type { Me } from '../../../api/client';
import { LendingApiContext } from '../../../api/lending-context';
import { createCollateralMock, createMembersMock } from '../../../api/members-mock';
import type { MemberRecord, MembersApi } from '../../../api/members';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { CollateralForm } from './collateral-form';
import { MemberForm } from './member-form';
import { usePhotoQueue } from './photo-queue';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// A save whose answer may have been lost (issue #280): the raw failure a dropped connection really gives, a look at what was saved
// that itself can fail, and no second record in any of those cases. Also the shared phone box when the check did not find anyone,
// a phone that was not changed, and a photo still being shrunk. Fabricated names, numbers and amounts.

afterEach(cleanup);

const BRANCH = '00000000-0000-4000-8000-0000000000b1';
const me: Me = {
  user_id: 'u1', full_name: 'Test Officer 01', kind: 'staff', all_branches: false,
  permissions: ['lending.members.read', 'lending.members.create', 'lending.members.update', 'lending.collateral.read', 'lending.collateral.manage'],
  branches: [{ id: BRANCH, code: 'BR1', name: 'Test Branch A' }],
};
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/lending/members/new'] }) });
const holder: MemberRecord = { id: '00000000-0000-4000-8000-000000000101', branch_id: BRANCH, member_no: 'M000001', full_name: 'Test Holder 01', phone_e164: '+256700000001', id_type: 'none', kyc_status: 'incomplete', status: 'active', version: 1 };

function mount(node: ReactElement, members: MembersApi, collateral: CollateralApi = createCollateralMock().api, who: Me = me) {
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LendingApiContext.Provider value={{ members, collateral }}>
          <StaffContext.Provider value={{ me: who, branch: BRANCH }}>{node}</StaffContext.Provider>
        </LendingApiContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const dropped = () => new TypeError('Failed to fetch');

describe('a member saved while the answer is lost', () => {
  const fillMember = () => {
    type(/^Full name/, 'Test Borrower 99');
    type(/^Phone number/, '0700000099');
    type(/^Kind of ID/, 'none');
  };
  const saveButton = () => screen.getByRole('button', { name: /Save member/ }) as HTMLButtonElement;

  it('takes the raw failure of a dropped connection as a lost answer, finds the member and blocks a second save', async () => {
    const mock = createMembersMock();
    const api: MembersApi = {
      ...mock.api,
      create: async (body, key) => {
        await mock.api.create(body, key);
        throw dropped();
      },
    };
    mount(<MemberForm />, api);
    fillMember();
    fireEvent.click(saveButton());
    expect(await screen.findByText(/is already saved\. It was not saved a second time/)).toBeTruthy();
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    fireEvent.click(saveButton());
    expect(mock.members).toHaveLength(1);
  });

  it('stays blocked while the look itself fails, says so, and frees the save only when the look worked and found nothing', async () => {
    const mock = createMembersMock();
    let looks = 0;
    const api: MembersApi = {
      ...mock.api,
      create: async () => {
        throw dropped();
      },
      duplicateCheck: async (body, signal) => {
        // The check as you type is the first call; the look after the loss is the calls with only a phone.
        if (body.full_name !== undefined || body.national_id !== undefined) return [];
        looks++;
        if (looks === 1) throw dropped();
        return mock.api.duplicateCheck(body, signal);
      },
    };
    mount(<MemberForm />, api);
    fillMember();
    fireEvent.click(saveButton());
    expect(await screen.findByText(/could not check whether the member was saved/)).toBeTruthy();
    expect(saveButton().getAttribute('data-not-ready')).toBe('true');
    fireEvent.click(saveButton());
    expect(mock.calls.create).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
    await waitFor(() => expect(saveButton().getAttribute('data-not-ready')).toBeNull());
    expect(looks).toBe(2);
  });
});

describe('collateral saved while the answer is lost', () => {
  it('finds an item with no reference number, which the server cannot guard, and blocks a second one', async () => {
    const mock = createCollateralMock();
    const api: CollateralApi = {
      ...mock.api,
      create: async (body, key) => {
        await mock.api.create(body, key);
        throw dropped();
      },
    };
    mount(<CollateralForm memberId={holder.id} />, createMembersMock([holder]).api, api);
    await screen.findByText('Test Holder 01');
    type(/^Describe the item/, 'Test television set');
    const save = () => screen.getByRole('button', { name: /Register collateral/ }) as HTMLButtonElement;
    fireEvent.click(save());
    expect(await screen.findByText(/item is already saved\. It was not saved a second time/)).toBeTruthy();
    expect(save().getAttribute('data-not-ready')).toBe('true');
    fireEvent.click(save());
    expect(mock.items).toHaveLength(1);
    // A person who knows it is a second, different item can say so.
    fireEvent.click(screen.getByRole('button', { name: 'This is a different one, save again' }));
    expect(save().getAttribute('data-not-ready')).toBeNull();
  });
});

describe('the shared phone box', () => {
  it('shows from the server refusal even when the check found nobody, and sends the word', async () => {
    const mock = createMembersMock([holder]);
    const api: MembersApi = { ...mock.api, duplicateCheck: async () => [] };
    mount(<MemberForm onSaved={() => undefined} />, api);
    type(/^Full name/, 'Test Borrower 99');
    type(/^Phone number/, '0700000001');
    type(/^Kind of ID/, 'none');
    const save = () => screen.getByRole('button', { name: /Save member/ }) as HTMLButtonElement;
    fireEvent.click(save());
    const tick = await screen.findByLabelText(/different person who shares the phone/i);
    expect(save().getAttribute('data-not-ready')).toBe('true');
    fireEvent.click(tick);
    fireEvent.click(save());
    await waitFor(() => expect(mock.calls.create).toHaveLength(2));
    expect(mock.calls.create[1]?.body).toMatchObject({ confirmed_not_duplicate: true });
  });

  it('does not hold an edit for a phone the member already has, and asks no check of a user who may only change members', async () => {
    const other: MemberRecord = { ...holder, id: '00000000-0000-4000-8000-000000000102', member_no: 'M000002', full_name: 'Test Holder 02' };
    const mock = createMembersMock([holder, other]);
    const check = vi.fn(mock.api.duplicateCheck);
    const updateOnly: Me = { ...me, permissions: ['lending.members.read', 'lending.members.update'] };
    mount(<MemberForm member={holder} onSaved={() => undefined} />, { ...mock.api, duplicateCheck: check }, undefined, updateOnly);
    type(/^Work or business/, 'Test trader');
    const save = screen.getByRole('button', { name: /Save changes/ }) as HTMLButtonElement;
    expect(ready(save)).toBe(true);
    fireEvent.click(save);
    await waitFor(() => expect(mock.calls.update).toHaveLength(1));
    expect(check).not.toHaveBeenCalled();
  });

  it('does hold an edit that changes the phone to one another member has', async () => {
    const other: MemberRecord = { ...holder, id: '00000000-0000-4000-8000-000000000102', member_no: 'M000002', full_name: 'Test Holder 02', phone_e164: '+256700000002' };
    mount(<MemberForm member={holder} onSaved={() => undefined} />, createMembersMock([holder, other]).api);
    type(/^Phone number/, '0700000002');
    expect(await screen.findByLabelText(/different person who shares the phone/i, undefined, { timeout: 2000 })).toBeTruthy();
    expect((screen.getByRole('button', { name: /Save changes/ }) as HTMLButtonElement).getAttribute('data-not-ready')).toBe('true');
  });
});

describe('Use their changes and keep mine', () => {
  it('replaces the stale version with the latest and keeps what was typed, then saves against the latest', async () => {
    const mock = createMembersMock([{ ...holder, version: 5, occupation: 'Their change' }]);
    const saved = vi.fn();
    mount(<MemberForm member={{ ...holder, version: 4 }} onSaved={saved} />, mock.api);
    type(/^Work or business/, 'My change');
    fireEvent.click(screen.getByRole('button', { name: /Save changes/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Use their changes and keep mine' }));
    await waitFor(() => expect(screen.queryByText(/Someone else changed this member/)).toBeNull());
    expect((screen.getByLabelText(/^Work or business/) as HTMLInputElement).value).toBe('My change');
    fireEvent.click(screen.getByRole('button', { name: /Save changes/ }));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(mock.calls.update.at(-1)).toMatchObject({ version: 5, body: { occupation: 'My change' } });
  });
});

describe('a photo still being shrunk', () => {
  it('counts as busy until it is ready, so the record is not saved without it', async () => {
    let finish: () => void = () => undefined;
    const codec = {
      decode: () => new Promise<null>((resolve) => { finish = () => resolve(null); }),
    };
    const { result } = renderHook(() => usePhotoQueue(codec));
    expect(result.current.busy).toBe(false);
    let added: Promise<number | null> = Promise.resolve(null);
    act(() => { added = result.current.add(new File([new Uint8Array(10)], 'a.png', { type: 'image/png' })); });
    expect(result.current.busy).toBe(true);
    await act(async () => { finish(); await added; });
    expect(result.current.busy).toBe(false);
  });

  it('sends a held picture once when asked twice in the same tick', async () => {
    const codec = { decode: async () => ({ width: 10, height: 10, close: () => undefined, encode: async () => null }) };
    const { result } = renderHook(() => usePhotoQueue(codec));
    let id: number | null = null;
    await act(async () => { id = await result.current.add(new File([new Uint8Array(10)], 'a.png', { type: 'image/png' })); });
    let release: () => void = () => undefined;
    const sender = vi.fn(() => new Promise<unknown>((resolve) => { release = () => resolve({}); }));
    let results: boolean[] = [];
    await act(async () => {
      const both = Promise.all([result.current.send(id ?? 0, sender), result.current.send(id ?? 0, sender)]);
      release();
      results = await both;
    });
    expect(sender).toHaveBeenCalledTimes(1);
    expect(results).toEqual([true, false]);
  });
});
