import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { type Progress, capabilityTourFor, mainTourFor } from '../../../tour/model';
import { TOURS, adminWelcome, branchRolesCapability } from '../../../tour/tours';
import { StaffContext } from '../context';
import { BranchRoles } from './branch-roles';
import {
  CAPABILITIES, KEYS, TRADE_PRESET, changeOf, chooseProfile, draftOf, isChange, requestOf, sentencesOf, toggle,
} from './capabilities-state';
import { shortcutsFor } from './nav';
import { SCREEN_CAPABILITY, canUse, screensFor } from './permissions';
import { ChangeReview, RetailSettings } from './settings';

// Trade slice 2 (issue #192, ADR-033): the profile preset, the administrator's screen, and menus, screens
// and tours that follow the capabilities. Static markup tests, as the other staff screen tests are.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

const admin = (capabilities?: string[]): Me => ({ ...mockMe('admin'), modules: ['retail'], module_capabilities: capabilities ? { retail: capabilities } : undefined });

describe('the profile is a preset of the flags (spec 1.2)', () => {
  it('starts as a shop with every feature off', () => {
    const d = draftOf(undefined);
    expect(d.profile).toBe('shop');
    expect(KEYS.filter((k) => d.flags[k])).toEqual([]);
    expect(isChange(changeOf(d, d))).toBe(false);
  });

  it('switches on the trade bundle and leaves documents and purchasing off', () => {
    const d = chooseProfile(draftOf(undefined), 'trade');
    expect(KEYS.filter((k) => d.flags[k]).sort()).toEqual([...TRADE_PRESET].sort());
    expect(d.flags.documents).toBe(false);
    expect(d.flags.purchasing).toBe(false);
  });

  it('keeps a flag the administrator switched on when the trade profile is chosen, and the shop profile clears all', () => {
    const withDocs = toggle(draftOf(undefined), 'documents', true);
    expect(chooseProfile(withDocs, 'trade').flags.documents).toBe(true);
    expect(KEYS.filter((k) => chooseProfile(chooseProfile(withDocs, 'trade'), 'shop').flags[k])).toEqual([]);
  });

  it('keeps every flag editable after a profile is chosen', () => {
    const d = toggle(chooseProfile(draftOf(undefined), 'trade'), 'returns', false);
    expect(d.profile).toBe('trade');
    expect(d.flags.returns).toBe(false);
    expect(d.flags.units).toBe(true);
  });

  it('describes a change for the confirmation, and sends the profile and the flags that changed', () => {
    const saved = draftOf(undefined);
    const change = changeOf(saved, chooseProfile(saved, 'trade'));
    expect(change.profile).toBe('trade');
    expect(change.on.sort()).toEqual([...TRADE_PRESET].sort());
    expect(requestOf(change)).toMatchObject({ profile: 'trade', branch_roles: true, units: true });
    expect(requestOf(change)).not.toHaveProperty('documents');
    expect(sentencesOf(change).join(' ')).toContain('Branch roles');
    const back = changeOf(chooseProfile(saved, 'trade'), saved);
    expect(requestOf(back)).toMatchObject({ profile: 'shop', branch_roles: false });
    expect(sentencesOf(back).join(' ')).toContain('is kept');
  });

  it('shows the administrator the flags a trade preset sets before anything is saved', () => {
    const saved = draftOf(undefined);
    const html = renderToString(<ChangeReview change={changeOf(saved, chooseProfile(saved, 'trade'))} onConfirm={() => undefined} onBack={() => undefined} />);
    for (const k of TRADE_PRESET) expect(html).toContain(CAPABILITIES.find((c) => c.key === k)?.label);
    expect(html).not.toContain('Invoices and quotations');
    expect(html).toContain('Confirm');
  });
});

describe('menus and screens follow the capabilities (ADR-033)', () => {
  const screens = (me: Me) => screensFor(me).map((s) => s.screen);
  const shortcuts = (me: Me) => shortcutsFor(me).map((s) => s.screen);

  it('all off equals today: no list, one list without the capability, and an empty list offer the same screens', () => {
    expect(screens(admin(undefined))).toEqual(screens(admin([])));
    expect(shortcuts(admin(undefined))).toEqual(shortcuts(admin([])));
    expect(screens(admin([]))).not.toContain('branchRoles');
  });

  for (const key of KEYS) {
    it(`${key} on changes only the screens that belong to it`, () => {
      const off = screens(admin([]));
      const on = screens(admin([key]));
      const owned = (Object.entries(SCREEN_CAPABILITY) as [string, string][]).filter(([, c]) => c === key).map(([s]) => s);
      expect(on.filter((s) => !off.includes(s)).sort()).toEqual(owned.sort());
      expect(off.filter((s) => !on.includes(s))).toEqual([]);
    });
  }

  it('hides a capability screen from a user without its permission, and shows it with the capability and the permission', () => {
    expect(canUse(admin(['branch_roles']), 'branchRoles')).toBe(true);
    expect(canUse(admin([]), 'branchRoles')).toBe(false);
    const withoutPermission = { ...admin(['branch_roles']), permissions: (admin().permissions ?? []).filter((p) => p !== 'retail.branch_role.manage') };
    expect(canUse(withoutPermission, 'branchRoles')).toBe(false);
  });

  it('offers the settings screen to the administrator only, whatever the capabilities', () => {
    expect(canUse(admin([]), 'settings')).toBe(true);
    expect(canUse(mockMe('sales'), 'settings')).toBe(false);
  });

  it('gives a sales user the same screens with every capability on as with every one off', () => {
    const seller = (caps: string[]): Me => ({ ...mockMe('sales'), modules: ['retail'], module_capabilities: { retail: caps } });
    expect(screens(seller([...KEYS]))).toEqual(screens(seller([])));
  });

  it('refuses the branch roles page while the capability is off', () => {
    const client = new QueryClient();
    const page = (me: Me) =>
      renderToString(
        <QueryClientProvider client={client}>
          <StaffContext.Provider value={{ me, branch: null }}>
            <BranchRoles />
          </StaffContext.Provider>
        </QueryClientProvider>,
      );
    expect(page(admin([]))).toContain('You do not have access');
    expect(page(admin(['branch_roles']))).not.toContain('You do not have access');
  });
});

describe('the administrator screen', () => {
  const settings = (me: Me, saved: Record<string, unknown> = { profile: 'shop' }) => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'capabilities'], { capabilities: saved, version: 3 });
    return renderToString(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <RetailSettings />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
  };

  it('lists the profile and all eight features, every one off for a new shop', () => {
    const html = settings(admin([]));
    expect(html).toContain('Shop');
    expect(html).toContain('Trade');
    for (const c of CAPABILITIES) expect(html).toContain(c.label);
    expect(html).not.toContain('checked=""><span><strong>Branch roles');
    expect(html).toContain('Review changes');
  });

  it('shows what is saved', () => {
    const html = settings(admin(['branch_roles']), { profile: 'trade', branch_roles: true });
    expect(html).toMatch(/checked=""[^>]*\/?><span><strong>Branch roles/);
  });

  it('says plainly when the pages of a feature are not released', () => {
    expect(settings(admin([]))).toContain('not released yet');
  });

  it('is closed to anyone without core.settings.manage', () => {
    const html = settings({ ...mockMe('sales'), modules: ['retail'] });
    expect(html).toContain('You do not have access');
    expect(html).not.toContain('Review changes');
  });
});

describe('a tour per capability starts once when first enabled', () => {
  const admins = (caps: string[]): Me => ({ ...admin(caps), tours: {} } as Me);
  const welcomeDone: Progress = { [adminWelcome.id]: { status: 'completed', version: adminWelcome.version } };

  it('has a tour for every capability whose pages are released, and no tour for an unknown capability', () => {
    for (const c of CAPABILITIES.filter((x) => x.ready)) {
      expect(TOURS.some((t) => t.capability?.module === 'retail' && t.capability.key === c.key), `${c.key} has no tour`).toBe(true);
    }
    for (const t of TOURS.filter((x) => x.capability)) {
      expect(KEYS as readonly string[]).toContain(t.capability?.key);
    }
    expect(Object.values(SCREEN_CAPABILITY)).toSatisfy((v: string[]) => v.every((k) => (KEYS as readonly string[]).includes(k)));
  });

  it('starts when the capability is on and the first-run tour is done', () => {
    expect(capabilityTourFor(TOURS, admins(['branch_roles']), welcomeDone)?.id).toBe(branchRolesCapability.id);
  });

  it('does not start while the capability is off', () => {
    expect(capabilityTourFor(TOURS, admins([]), welcomeDone)).toBeUndefined();
    expect(capabilityTourFor(TOURS, admin(undefined), welcomeDone)).toBeUndefined();
  });

  it('waits for the first-run tour, then runs once', () => {
    expect(capabilityTourFor(TOURS, admins(['branch_roles']), {})).toBeUndefined();
    const done: Progress = { ...welcomeDone, [branchRolesCapability.id]: { status: 'completed', version: branchRolesCapability.version } };
    expect(capabilityTourFor(TOURS, admins(['branch_roles']), done)).toBeUndefined();
  });

  it('never starts again once dismissed, and shows again only for a newer version', () => {
    const dismissed: Progress = { ...welcomeDone, [branchRolesCapability.id]: { status: 'dismissed', version: 1 } };
    expect(capabilityTourFor(TOURS, admins(['branch_roles']), dismissed)).toBeUndefined();
    const old: Progress = { ...welcomeDone, [branchRolesCapability.id]: { status: 'completed', version: 0 } };
    expect(capabilityTourFor(TOURS, admins(['branch_roles']), old)?.id).toBe(branchRolesCapability.id);
  });

  it('is not for a user without the permission, and is never the first-run tour', () => {
    const seller: Me = { ...mockMe('sales'), modules: ['retail'], module_capabilities: { retail: ['branch_roles'] } };
    expect(capabilityTourFor(TOURS, seller, { 'retail-sales': { status: 'completed', version: 9 } })).toBeUndefined();
    expect(mainTourFor(TOURS, admins(['branch_roles']))?.id).toBe(adminWelcome.id);
  });
});
