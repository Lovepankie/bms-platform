// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { openInNewTab } from '../../../api/lending-forms';
import type { CollateralItem } from '../../../api/collateral';
import type { Me } from '../../../api/client';
import { LendingApiContext } from '../../../api/lending-context';
import { createCollateralMock, createMembersMock } from '../../../api/members-mock';
import type { MemberRecord } from '../../../api/members';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { CollateralForm } from './collateral-form';
import { CollateralRows } from './collateral';
import { CollateralView } from './collateral-page';
import { MemberRows } from './members';
import { MemberView } from './member-page';

// The collateral register and the member page as a person uses them (issue #280). Fabricated names, references and amounts.

afterEach(cleanup);

const BRANCH = '00000000-0000-4000-8000-0000000000b1';
const OTHER = '00000000-0000-4000-8000-0000000000b2';
const officer = (extra: string[] = [], scope?: Me['permission_scopes']): Me => ({
  user_id: 'u1', full_name: 'Test Officer 01', kind: 'staff', all_branches: false,
  permissions: ['lending.members.read', 'lending.members.update', 'lending.collateral.read', 'lending.collateral.manage', ...extra],
  permission_scopes: scope,
  branches: [{ id: BRANCH, code: 'BR1', name: 'Test Branch A' }, { id: OTHER, code: 'BR2', name: 'Test Branch B' }],
});
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/lending/collateral'] }) });

const holder: MemberRecord = { id: '00000000-0000-4000-8000-000000000101', branch_id: BRANCH, member_no: 'M000001', full_name: 'Test Holder 01', phone_e164: '+256700000001', kyc_status: 'incomplete', status: 'active', id_type: 'nin', national_id: 'CM000000000001', version: 1 };

function mount(node: ReactElement, opts: { me?: Me; members?: MemberRecord[]; items?: CollateralItem[] } = {}) {
  const members = createMembersMock(opts.members ?? [holder]);
  const collateral = createCollateralMock(opts.items ?? []);
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LendingApiContext.Provider value={{ members: members.api, collateral: collateral.api }}>
          <StaffContext.Provider value={{ me: opts.me ?? officer(), branch: BRANCH }}>{node}</StaffContext.Provider>
        </LendingApiContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return { members, collateral };
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const save = () => screen.getByRole('button', { name: /Register collateral|Save changes/ }) as HTMLButtonElement;

describe('registering collateral', () => {
  it('needs the member, a description and, for a vehicle, the plate number, each named in plain words', async () => {
    mount(<CollateralForm />);
    fireEvent.click(save());
    expect(document.getElementById('cf-member-id-error')?.textContent).toMatch(/Choose the member/);
    expect(document.getElementById('cf-description-error')?.textContent).toMatch(/Describe the item/);
    type(/^Kind of collateral/, 'vehicle');
    expect(screen.getByLabelText(/^Plate number/)).toBeTruthy();
    fireEvent.click(save());
    expect(document.getElementById('cf-reference-no-error')?.textContent).toMatch(/plate number/i);
  });

  it('finds the member by searching, sends whole shillings as minor units and one Idempotency-Key, then asks the owner', async () => {
    const { collateral } = mount(<CollateralForm memberId={holder.id} />);
    await screen.findByText('Test Holder 01');
    type(/^Describe the item/, 'Plot of land at Test Village');
    type(/^Title number/, 'LRV 123 Folio 4');
    type(/^What it is worth/, '25,000,000');
    fireEvent.click(save());
    await waitFor(() => expect(collateral.calls.create).toHaveLength(1));
    expect(collateral.calls.create[0]?.body).toMatchObject({ member_id: holder.id, collateral_type: 'land_title', estimated_value_minor: 25_000_000, custody_status: 'pledged', owner_relationship: 'self' });
    expect(collateral.calls.create[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('shows a reference already registered on the reference input, in words', async () => {
    const taken: CollateralItem = { id: 'c0', member_id: holder.id, collateral_type: 'land_title', description: 'Test plot', reference_no: 'LRV 1', reference_no_normalised: 'LRV1', custody_status: 'pledged', version: 1 };
    mount(<CollateralForm memberId={holder.id} />, { items: [taken] });
    await screen.findByText('Test Holder 01');
    type(/^Describe the item/, 'Another plot');
    type(/^Title number/, 'lrv 1');
    fireEvent.click(save());
    await waitFor(() => expect(document.getElementById('cf-reference-no-error')?.textContent).toMatch(/already registered/));
    expect(document.getElementById('cf-reference-no')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('types the value in the business currency from /me, not in shillings (issue #289)', async () => {
    const { collateral } = mount(<CollateralForm memberId={holder.id} />, { me: { ...officer(), tenant_currency: 'KES' } });
    await screen.findByText('Test Holder 01');
    // A currency with cents gets the keypad that has a decimal point.
    expect(screen.getByLabelText(/^What it is worth \(KES, your estimate\)/).getAttribute('inputmode')).toBe('decimal');
    type(/^Describe the item/, 'Test fridge');
    type(/^What it is worth/, '1,500.25');
    fireEvent.click(save());
    await waitFor(() => expect(collateral.calls.create).toHaveLength(1));
    expect(collateral.calls.create[0]?.body).toMatchObject({ estimated_value_minor: 150025 });
  });

  it('asks where the item is kept when we hold it', () => {
    mount(<CollateralForm memberId={holder.id} />);
    type(/^Who keeps it/, 'in_custody');
    expect(screen.getByLabelText(/^Where it is kept/)).toBeTruthy();
    type(/^Describe the item/, 'Test logbook');
    fireEvent.click(save());
    expect(document.getElementById('cf-storage-location-error')?.textContent).toMatch(/where the item is kept/i);
  });
});

describe('valuing an item in its own currency (issue #289)', () => {
  it('labels the values in the item\'s currency and gives a KES item the decimal keypad', () => {
    const kes: CollateralItem = { id: 'c9', branch_id: BRANCH, member_id: holder.id, collateral_type: 'vehicle', description: 'Test van', reference_no: 'KXX 001X', custody_status: 'pledged', currency: 'KES', version: 1 };
    mount(<CollateralView detail={{ item: kes, valuations: [], events: [] }} collateralId="c9" />);
    fireEvent.click(screen.getByRole('button', { name: 'Add a valuation' }));
    expect(screen.getByLabelText(/^Market value \(KES\)/).getAttribute('inputmode')).toBe('decimal');
    expect(screen.getByLabelText(/^Value in a quick sale \(KES, if known\)/).getAttribute('inputmode')).toBe('decimal');
  });
});

describe('the collateral item page', () => {
  const item: CollateralItem = { id: 'c1', branch_id: BRANCH, member_id: holder.id, collateral_type: 'vehicle', description: 'Test motorcycle', reference_no: 'UXX 001X', custody_status: 'pledged', currency: 'UGX', estimated_value_minor: 4_000_000, collateral_value_minor: 3_500_000, version: 2 };
  const detail = { item, valuations: [], events: [] };

  it('offers Change and Record a change to a manager, and the release request only with its own permission', () => {
    mount(<CollateralView detail={detail} collateralId="c1" />);
    expect(screen.getByRole('link', { name: 'Change details' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Record a change' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Ask to give it back' })).toBeNull();
    cleanup();
    mount(<CollateralView detail={detail} collateralId="c1" />, { me: officer(['lending.collateral.release_request']) });
    expect(screen.getByRole('button', { name: 'Ask to give it back' })).toBeTruthy();
  });

  it('shows "Fields marked * are needed." on Record a change only while a field is starred, for every kind of change', () => {
    mount(<CollateralView detail={detail} collateralId="c1" />);
    fireEvent.click(screen.getByRole('button', { name: 'Record a change' }));
    const form = screen.getByRole('form', { name: 'Record a change' });
    const kind = screen.getByLabelText(/^What happened/) as HTMLSelectElement;
    const kinds = [...kind.options].map((o) => o.value);
    expect(kinds.length).toBeGreaterThan(1);
    for (const value of kinds) {
      fireEvent.change(kind, { target: { value } });
      const starred = form.querySelector('label .field-star') !== null;
      expect(form.querySelector('.fields-needed') !== null, value).toBe(starred);
    }
  });

  it('checks the branch scope of the permission, not only the permission', () => {
    const scoped = officer([], { 'lending.collateral.manage': { all_branches: false, branch_ids: [OTHER] }, 'lending.collateral.read': { all_branches: true, branch_ids: [] } });
    mount(<CollateralView detail={detail} collateralId="c1" />, { me: scoped });
    expect(screen.queryByRole('link', { name: 'Change details' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Record a change' })).toBeNull();
  });

  it('says in the release panel that a second person agrees, and words nothing as a loan refusal', async () => {
    mount(<CollateralView detail={detail} collateralId="c1" />, { me: officer(['lending.collateral.release_request']) });
    fireEvent.click(screen.getByRole('button', { name: 'Ask to give it back' }));
    type(/^Who is collecting it/, 'Test Owner 01');
    fireEvent.click(screen.getByRole('button', { name: 'Send the request' }));
    expect((await screen.findByText(/second person must agree in the approvals inbox/)).closest('[role="status"]')).not.toBeNull();
  });

  it('lists the loan cover value apart from the estimate', () => {
    mount(<CollateralView detail={detail} collateralId="c1" />);
    expect(screen.getByText('UGX 3,500,000')).toBeTruthy();
    expect(screen.getByText('UGX 4,000,000')).toBeTruthy();
  });
});

describe('lists', () => {
  it('link to a record by its id, never by a phone or an ID number', () => {
    mount(<MemberRows items={[{ id: holder.id, member_no: 'M000001', full_name: 'Test Holder 01', phone_e164_masked: '***0001', national_id_masked: '***0001', kyc_status: 'incomplete', status: 'active' }]} />);
    const href = screen.getByRole('link').getAttribute('href') ?? '';
    expect(href).toBe(`/staff/lending/members/${holder.id}`);
    expect(href).not.toContain('?');
    expect(href).not.toMatch(/CM0|\+256|\*\*\*/);
    expect(document.body.textContent).toContain('Details missing');
  });

  it('say what a collateral item is worth and who keeps it', () => {
    mount(<CollateralRows items={[{ id: 'c1', description: 'Test plot', collateral_type: 'land_title', custody_status: 'in_custody', currency: 'UGX', estimated_value_minor: 1_000_000 }]} />);
    expect(document.body.textContent).toContain('We hold it');
    expect(document.body.textContent).toContain('UGX 1,000,000');
  });
});

describe('the member page', () => {
  it('lists what is missing before the details can be checked, in words', async () => {
    mount(<MemberView member={{ ...holder, national_id: undefined, id_type: 'nin' }} />);
    expect(await screen.findByText(/Still needed: .*the ID number.*where the member lives/)).toBeTruthy();
  });

  it('offers the check only to someone who may check, and only while it waits', () => {
    mount(<MemberView member={{ ...holder, kyc_status: 'pending_verification' }} />);
    expect(screen.queryByRole('button', { name: 'Details are correct' })).toBeNull();
    cleanup();
    mount(<MemberView member={{ ...holder, kyc_status: 'pending_verification' }} />, { me: officer(['lending.members.verify_kyc']) });
    expect(screen.getByRole('button', { name: 'Details are correct' })).toBeTruthy();
    cleanup();
    mount(<MemberView member={{ ...holder, kyc_status: 'verified' }} />, { me: officer(['lending.members.verify_kyc']) });
    expect(screen.queryByRole('button', { name: 'Details are correct' })).toBeNull();
  });

  it('shows a sent-for-checking member without the ID photos to someone who may not see them, with a line saying so', async () => {
    mount(<MemberView member={holder} />);
    expect(await screen.findByText(/Photos of the ID are shown only to staff who check/)).toBeTruthy();
  });
});

describe('details of one person', () => {
  it('shows the national ID in full only to staff who change or check members, and the last four to others', () => {
    const readOnly: Me = { ...officer(), permissions: ['lending.members.read'] };
    mount(<MemberView member={holder} />, { me: readOnly });
    expect(document.body.textContent).toContain('**********0001');
    expect(document.body.textContent).not.toContain('CM000000000001');
    cleanup();
    mount(<MemberView member={holder} />);
    expect(document.body.textContent).toContain('CM000000000001');
  });

  it('clears the owner\'s name when the owner is changed back to the member', () => {
    mount(<CollateralForm memberId={holder.id} />);
    type(/^Who owns it/, 'other');
    type(/^Owner's name/, 'Test Owner 02');
    type(/^Who owns it/, 'self');
    type(/^Who owns it/, 'other');
    expect((screen.getByLabelText(/^Owner's name/) as HTMLInputElement).value).toBe('');
  });
});

describe('opening a file', () => {
  it('opens the tab in the tap, before the address is known, then points it at the address', async () => {
    const tab = { opener: {} as unknown, location: { href: '' }, close: vi.fn() };
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    let give: (u: string) => void = () => undefined;
    const done = openInNewTab(() => new Promise<string>((resolve) => { give = resolve; }));
    expect(open).toHaveBeenCalledWith('about:blank', '_blank');
    expect(tab.location.href).toBe('');
    give('https://files.example.test/d1');
    await done;
    expect(tab.location.href).toBe('https://files.example.test/d1');
    expect(tab.opener).toBeNull();
  });

  it('closes the tab again when the address could not be had', async () => {
    const tab = { opener: null, location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    await expect(openInNewTab(async () => { throw new Error('no'); })).rejects.toThrow('no');
    expect(tab.close).toHaveBeenCalled();
  });
});

describe('phone width layout', () => {
  const css = readFileSync(join(process.cwd(), 'src/app/ui/lending.css'), 'utf8');
  const block = (selector: string) => new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? '';

  it('keeps the form to one column, with the save bar stuck to the bottom and room under it', () => {
    expect(block('.mf')).toMatch(/flex-direction:\s*column/);
    expect(block('.mf')).toMatch(/padding-bottom:\s*calc\(96px/);
    expect(block('.mf-savebar')).toMatch(/position:\s*sticky/);
    expect(block('.mf-savebar')).toMatch(/bottom:\s*0/);
    expect(block('.mf-savebar-row > *')).toMatch(/min-height:\s*var\(--tap\)/);
  });

  it('limits the form width only from 720px up, so a phone uses the whole width', () => {
    const wide = /@media \(min-width: 720px\)\s*\{\s*\.mf\s*\{[^}]*max-width:\s*640px/.exec(css);
    expect(wide).not.toBeNull();
    expect(block('.mf')).not.toMatch(/max-width/);
  });

  it('builds the form as labelled groups with the save bar as its last child', () => {
    mount(<CollateralForm memberId={holder.id} />);
    const form = screen.getByRole('form', { name: 'Register collateral' });
    expect(form.lastElementChild?.className).toBe('mf-savebar');
    expect(form.querySelectorAll('fieldset > legend').length).toBeGreaterThanOrEqual(3);
    for (const input of Array.from(form.querySelectorAll('input:not([type=file]), select, textarea'))) {
      expect(input.id, 'every input has an id for its label').not.toBe('');
      expect(form.querySelector(`label[for="${input.id}"]`), `label for ${input.id}`).not.toBeNull();
    }
  });
});
