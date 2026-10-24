// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { LendingApiContext } from '../../../api/lending-context';
import { FormError } from '../../../api/lending-forms';
import { createCollateralMock, createMembersMock } from '../../../api/members-mock';
import type { MemberRecord, MembersApi } from '../../../api/members';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { MemberForm } from './member-form';

// The member form as a person uses it (issue #280, FR-MEM-01 to FR-MEM-04): errors tied to their inputs with focus on the first,
// duplicates shown before saving, a registration that is held for the same national ID or an unconfirmed shared phone, one
// Idempotency-Key per form across retries, a lost answer that does not allow a second registration, and an edit that sends
// the member's version and keeps what was typed on a conflict. All names, numbers and IDs are fabricated.

afterEach(cleanup);

const BRANCH = '00000000-0000-4000-8000-0000000000b1';
const me: Me = {
  user_id: 'u1', full_name: 'Test Officer 01', kind: 'staff', all_branches: false,
  permissions: ['lending.members.read', 'lending.members.create', 'lending.members.update'],
  branches: [{ id: BRANCH, code: 'BR1', name: 'Test Branch A' }],
};
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/lending/members/new'] }) });

const existing: MemberRecord = {
  id: '00000000-0000-4000-8000-000000000101', branch_id: BRANCH, member_no: 'M000001', full_name: 'Test Holder 01', phone_e164: '+256700000001',
  id_type: 'nin', national_id: 'CM000000000001', kyc_status: 'verified', status: 'active', gender: 'unspecified', marital_status: 'unknown', version: 4,
};

function mount(node: ReactElement, api: MembersApi) {
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LendingApiContext.Provider value={{ members: api, collateral: createCollateralMock().api }}>
          <StaffContext.Provider value={{ me, branch: BRANCH }}>{node}</StaffContext.Provider>
        </LendingApiContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const save = () => screen.getByRole('button', { name: /Save member|Save changes/ }) as HTMLButtonElement;
const fill = (nin = 'CM000000000099', phone = '0700000099') => {
  type(/^Full name/, 'Test Borrower 99');
  type(/^Phone number/, phone);
  type(/^National ID number/, nin);
};

describe('the new member form', () => {
  it('starts with the needed line and marks each required field with a hidden red asterisk and aria-required', () => {
    mount(<MemberForm />, createMembersMock().api);
    expect(document.querySelector('form .fields-needed')?.textContent).toBe('Fields marked * are needed.');
    const name = screen.getByLabelText(/^Full name/);
    expect(name.getAttribute('aria-required')).toBe('true');
    const star = document.querySelector(`label[for="${name.id}"] .field-star`);
    expect(star?.textContent).toBe(' *');
    expect(star?.getAttribute('aria-hidden')).toBe('true');
    const branch = screen.getByLabelText(/^Branch/);
    expect(branch.tagName).toBe('SELECT');
    expect(branch.getAttribute('aria-required')).toBe('true');
    expect(document.body.textContent).not.toMatch(/\(needed\)/);
  });

  it('names what is wrong in plain words, ties it to the input and moves focus to the first one', async () => {
    mount(<MemberForm />, createMembersMock().api);
    fireEvent.click(save());
    const name = screen.getByLabelText(/^Full name/) as HTMLInputElement;
    await waitFor(() => expect(document.activeElement).toBe(name));
    expect(name.getAttribute('aria-invalid')).toBe('true');
    const described = name.getAttribute('aria-describedby') ?? '';
    expect(document.getElementById(described.split(' ').find((id) => id.endsWith('-error')) ?? '')?.textContent).toMatch(/full name/i);
    expect(screen.getByLabelText(/^Phone number/).getAttribute('aria-invalid')).toBe('true');
    expect(document.body.textContent).not.toMatch(/national_id|full_name|phone_e164/);
  });

  it('refuses an ambiguous or foreign phone number before sending, and accepts the usual forms', () => {
    const mock = createMembersMock();
    mount(<MemberForm />, mock.api);
    fill('CM000000000099', '712345678');
    fireEvent.click(save());
    expect(document.getElementById('mf-phone-error')?.textContent).toMatch(/Start the number with 0/);
    type(/^Phone number/, '+255 712 345 678');
    expect(document.getElementById('mf-phone-error')?.textContent).toMatch(/Uganda mobile/);
    expect(mock.calls.create).toHaveLength(0);
    type(/^Phone number/, '+256 712 345 678');
    expect(document.getElementById('mf-phone-error')).toBeNull();
  });

  it('shows who already has the national ID before saving, and will not save it', async () => {
    const mock = createMembersMock([existing]);
    mount(<MemberForm />, mock.api);
    fill('cm 000000000001');
    expect(await screen.findByText(/Someone like this is already registered/, undefined, { timeout: 2000 })).toBeTruthy();
    expect(screen.getByText('M000001')).toBeTruthy();
    expect(screen.getByText(/cannot be saved/i)).toBeTruthy();
    expect(save().disabled).toBe(true);
    expect(mock.calls.create).toHaveLength(0);
  });

  it('holds a shared phone until the person says it is someone else, then sends that word', async () => {
    const mock = createMembersMock([existing]);
    const saved = vi.fn();
    mount(<MemberForm onSaved={saved} />, mock.api);
    fill('CM000000000099', '0700 000 001');
    const tick = await screen.findByLabelText(/different person who shares the phone/i, undefined, { timeout: 2000 });
    expect(save().disabled).toBe(true);
    fireEvent.click(tick);
    expect(save().disabled).toBe(false);
    fireEvent.click(save());
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(mock.calls.create[0]?.body).toMatchObject({ confirmed_not_duplicate: true, phone: '+256700000001' });
  });

  it('keeps one Idempotency-Key across a retry, and shows a refused national ID on its own input with the member number', async () => {
    const mock = createMembersMock([existing]);
    // The check is silent here, so the save itself meets the server's refusal.
    const api: MembersApi = { ...mock.api, duplicateCheck: async () => [] };
    const saved = vi.fn();
    mount(<MemberForm onSaved={saved} />, api);
    fill('CM000000000001', '0700000099');
    fireEvent.click(save());
    await waitFor(() => expect(document.getElementById('mf-national-id-error')?.textContent).toMatch(/M000001/));
    expect(screen.getByLabelText(/^National ID number/).getAttribute('aria-invalid')).toBe('true');
    type(/^National ID number/, 'CM000000000098');
    expect(document.getElementById('mf-national-id-error')).toBeNull();
    mock.failNextCreate(new FormError('The server had a problem.', 503, undefined), false);
    fireEvent.click(save());
    await waitFor(() => expect(mock.calls.create).toHaveLength(2));
    await waitFor(() => expect(screen.getByText(/server had a problem/i)).toBeTruthy());
    fireEvent.click(save());
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const keys = mock.calls.create.map((c) => c.key);
    expect(keys).toHaveLength(3);
    expect(new Set(keys).size).toBe(1);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('after an answer that may have been lost, finds the member already saved and does not allow another registration', async () => {
    const mock = createMembersMock();
    mock.failNextCreate(new FormError('The upload did not reach the server.', 0, 'network'), true);
    mount(<MemberForm />, mock.api);
    fill('CM000000000099', '0700000099');
    fireEvent.click(save());
    expect(await screen.findByText(/already saved/i, undefined, { timeout: 2000 })).toBeTruthy();
    expect(mock.members).toHaveLength(1);
    expect(save().disabled).toBe(true);
    fireEvent.click(save());
    expect(mock.calls.create).toHaveLength(1);
  });
});

describe('editing a member', () => {
  it('sends only what changed, with the version it loaded, and says "not said" in the server words', async () => {
    const mock = createMembersMock([existing]);
    const saved = vi.fn();
    mount(<MemberForm member={existing} onSaved={saved} />, mock.api);
    expect((screen.getByLabelText(/^Gender/) as HTMLSelectElement).value).toBe('');
    type(/^Work or business/, 'Test trader');
    fireEvent.click(save());
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(mock.calls.update).toEqual([{ id: existing.id, version: 4, body: { occupation: 'Test trader' } }]);
  });

  it('on a stale version keeps what was typed and says someone else changed it', async () => {
    const mock = createMembersMock([{ ...existing, version: 5 }]);
    mount(<MemberForm member={existing} />, mock.api);
    type(/^Work or business/, 'Test trader');
    fireEvent.click(save());
    expect(await screen.findByText(/Someone else changed this member/)).toBeTruthy();
    expect((screen.getByLabelText(/^Work or business/) as HTMLInputElement).value).toBe('Test trader');
  });

  it('does not let a saved income or date of birth be emptied', () => {
    const mock = createMembersMock([{ ...existing, date_of_birth: '1990-01-01', monthly_income_minor: 0 }]);
    mount(<MemberForm member={{ ...existing, date_of_birth: '1990-01-01', monthly_income_minor: 0 }} />, mock.api);
    expect((screen.getByLabelText(/^Usual income a month/) as HTMLInputElement).value).toBe('0');
    type(/^Date of birth/, '');
    type(/^Usual income a month/, '');
    fireEvent.click(save());
    expect(document.getElementById('mf-date-of-birth-error')?.textContent).toMatch(/cannot be removed/);
    expect(document.getElementById('mf-monthly-income-minor-error')?.textContent).toMatch(/cannot be removed/);
    expect(mock.calls.update).toHaveLength(0);
  });
});
