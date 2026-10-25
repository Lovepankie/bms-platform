import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { type Progress, capabilityTourFor, mainTourFor } from '../../../tour/model';
import { TOURS, adminWelcome, branchRolesCapability } from '../../../tour/tours';
import { StaffContext } from '../context';
import { BranchRoles } from './branch-roles';
import {
  CAPABILITIES, GOVERNED, KEYS, PRESETS, PROFILE_KEYS, WHOLESALE_TOOLS, changeOf, chooseProfile, draftOf, isChange, requestOf, sentencesOf, toggle,
} from './capabilities-state';
import { staffAreas } from '../menu';
import { SCREENS, SCREEN_CAPABILITY, canUse, screensFor } from './permissions';
import { ChangeReview, RetailSettings } from './settings';
import { GoodsReceivedPage } from './goods-received';
import { LandedCostsPage } from './landed-costs';
import { SupplierCreditsPage } from './supplier-credits';
import { PurchaseOrdersPage } from './purchase-orders';
import { SupplierBillsPage } from './supplier-bills';

// Trade slice 2 (issue #192, ADR-033): the profile preset, the administrator's screen, and menus, screens
// and tours that follow the capabilities. Static markup tests, as the other staff screen tests are.

// The settings page links to Branches, so every page is drawn inside the app's router.
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/settings'] }) });
const renderToString = (node: ReactElement) => render(<RouterContextProvider router={router}>{node}</RouterContextProvider>).replaceAll('<!-- -->', '');

const admin = (capabilities?: string[]): Me => ({ ...mockMe('admin'), modules: ['retail'], module_capabilities: capabilities ? { retail: capabilities } : undefined });

describe('the kind of business is a preset of the flags (spec 1.2, issue #424)', () => {
  it('starts as shops only with every feature off', () => {
    const d = draftOf(undefined);
    expect(d.profile).toBe('shops');
    expect(KEYS.filter((k) => d.flags[k])).toEqual([]);
    expect(isChange(changeOf(d, d))).toBe(false);
  });

  it('offers the four kinds, named by what the owner has, in this order', () => {
    expect(PROFILE_KEYS).toEqual(['shops', 'stores_and_shops', 'wholesale', 'wholesale_and_shops']);
  });

  it('sets each kind\'s switches the same way as the server (BusinessTypes.PRESETS)', () => {
    const on = (p: (typeof PROFILE_KEYS)[number]) => KEYS.filter((k) => chooseProfile(draftOf(undefined), p).flags[k]).sort();
    expect(on('shops')).toEqual([]);
    expect(on('stores_and_shops')).toEqual(['transfer_receipt']);
    expect(on('wholesale')).toEqual(['customer_accounts', 'documents', 'price_lists', 'purchasing', 'units']);
    expect(on('wholesale_and_shops')).toEqual(['customer_accounts', 'documents', 'price_lists', 'purchasing', 'transfer_receipt', 'units']);
    expect([...WHOLESALE_TOOLS].sort()).toEqual(on('wholesale'));
    for (const p of PROFILE_KEYS) for (const k of PRESETS[p]) expect(GOVERNED).toContain(k);
  });

  it('leaves branch roles and returns as they are whatever kind is chosen', () => {
    const mine = toggle(toggle(draftOf(undefined), 'returns', true), 'branch_roles', true);
    for (const p of PROFILE_KEYS) {
      expect(chooseProfile(mine, p).flags.returns).toBe(true);
      expect(chooseProfile(mine, p).flags.branch_roles).toBe(true);
    }
    expect(KEYS.filter((k) => chooseProfile(chooseProfile(mine, 'wholesale'), 'shops').flags[k]).sort()).toEqual(['branch_roles', 'returns']);
  });

  it('keeps every flag editable after a kind is chosen', () => {
    const d = toggle(chooseProfile(draftOf(undefined), 'wholesale'), 'purchasing', false);
    expect(d.profile).toBe('wholesale');
    expect(d.flags.purchasing).toBe(false);
    expect(d.flags.units).toBe(true);
  });

  it('describes a change for the confirmation, and sends the kind and the flags that changed', () => {
    const saved = draftOf(undefined);
    const change = changeOf(saved, chooseProfile(saved, 'wholesale'));
    expect(change.profile).toBe('wholesale');
    expect(change.on.sort()).toEqual([...WHOLESALE_TOOLS].sort());
    expect(requestOf(change)).toMatchObject({ profile: 'wholesale', price_lists: true, units: true });
    expect(requestOf(change)).not.toHaveProperty('transfer_receipt');
    expect(sentencesOf(change).join(' ')).toContain('This business becomes Wholesale.');
    expect(sentencesOf(change).join(' ')).toContain('Price lists');
    const back = changeOf(chooseProfile(saved, 'wholesale'), saved);
    expect(requestOf(back)).toMatchObject({ profile: 'shops', price_lists: false });
    expect(sentencesOf(back).join(' ')).toContain('is kept');
  });

  it('shows the administrator the flags a kind sets before anything is saved', () => {
    const saved = draftOf(undefined);
    const html = renderToString(<ChangeReview change={changeOf(saved, chooseProfile(saved, 'stores_and_shops'))} onConfirm={() => undefined} onBack={() => undefined} />);
    expect(html).toContain('This business becomes Stores and shops.');
    expect(html).toContain('Receive transfers');
    expect(html).not.toContain('Invoices and quotations');
    expect(html).toContain('Confirm');
  });

  it('reads a kind it does not know as shops only', () => {
    expect(draftOf({ profile: 'trade' } as never).profile).toBe('shops');
  });
});

describe('menus and screens follow the capabilities (ADR-033)', () => {
  const screens = (me: Me) => screensFor(me).map((s) => s.screen);
  const menu = (me: Me) => staffAreas(me, { sells: true }).map((l) => l.to);

  it('all off equals today: no list, one list without the capability, and an empty list offer the same screens', () => {
    expect(screens(admin(undefined))).toEqual(screens(admin([])));
    expect(menu(admin(undefined))).toEqual(menu(admin([])));
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
  const settings = (me: Me, saved: Record<string, unknown> = { profile: 'shops' }) => {
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

  it('asks what kind of business this is, with the four kinds and all eight features, every one off for a new shop', () => {
    const html = settings(admin([]));
    expect(html).toContain('What kind of business is this?');
    for (const name of ['Shops only', 'Stores and shops', 'Wholesale', 'Wholesale and shops']) expect(html).toContain(`<strong>${name}</strong>`);
    expect(html).toContain('One or more shops that sell over the counter.');
    expect(html).toContain('Any number of each.');
    expect(html).toContain('price lists and customer prices, quotations, invoices and delivery notes, credit to shops, packs and purchase orders');
    expect(html).not.toContain('Trade');
    expect(html).toMatch(/checked="" value="shops"/);
    for (const c of CAPABILITIES) expect(html).toContain(c.label);
    expect(html).not.toContain('checked=""><span><strong>Branch roles');
    expect(html).toContain('Review changes');
  });

  it('shows what is saved', () => {
    const html = settings(admin(['branch_roles']), { profile: 'wholesale_and_shops', branch_roles: true });
    expect(html).toMatch(/checked=""[^>]*\/?><span><strong>Branch roles/);
    expect(html).toMatch(/checked="" value="wholesale_and_shops"/);
    expect(html).not.toMatch(/checked="" value="shops"/);
  });

  it('says that each branch is set as a store or a shop under Branches, with the link', () => {
    const html = settings(admin([]));
    expect(html).toContain('Each branch is set as a store or a shop under');
    expect(html).toContain('href="/staff/setup/branches"');
  });

  it('says plainly when the pages of a feature are not released, and only then', () => {
    // Every feature has its pages now (the last, purchasing, with #198), so the note is shown for none; it returns with a feature
    // that is added before its pages.
    expect(settings(admin([])).includes('not released yet')).toBe(CAPABILITIES.some((c) => !c.ready));
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

// Optional purchasing (issue #198, ADR-033): everything is off by default, and "off" is today's menu and today's Restock.
describe('purchasing follows its capability', () => {
  // Supplier payments and What I owe suppliers are not among them since issue #557: they pay what Buy stock bought on credit.
  const NEW_SCREENS = ['purchaseOrders', 'goodsReceived', 'supplierBills', 'landedCosts', 'supplierCredits'];
  const screens = (me: Me) => screensFor(me).map((s) => s.screen);
  const menu = (me: Me) => staffAreas(me, { sells: true }).map((l) => l.to);

  it('offers none of the purchasing screens or menu rows while purchasing is absent, and leaves Restock where it was', () => {
    for (const me of [admin(undefined), admin([]), admin(['branch_roles', 'units', 'returns'])]) {
      const offered = screens(me);
      for (const screen of NEW_SCREENS) expect(offered, screen).not.toContain(screen);
      expect(offered).toContain('restock');
      for (const screen of NEW_SCREENS) expect(menu(me), screen).not.toContain(SCREENS.find((s) => s.screen === screen)?.path);
    }
  });

  it('adds exactly the five screens when it is on, and only Purchase orders under Wholesale in the menu (issue #424)', () => {
    const off = screens(admin([]));
    const on = screens(admin(['purchasing']));
    expect(on.filter((s) => !off.includes(s)).sort()).toEqual([...NEW_SCREENS].sort());
    expect(off.filter((s) => !on.includes(s))).toEqual([]);
    const added = menu(admin(['purchasing'])).filter((to) => !menu(admin([])).includes(to));
    expect(added).toEqual(['/staff/retail/purchase-orders']);
    expect(menu(admin([])).filter((to) => !menu(admin(['purchasing'])).includes(to))).toEqual([]);
  });

  it('gives orders and goods received to the restock permission, and the money screens to administrators only', () => {
    const withPermissions = (permissions: string[]): Me => ({ ...admin(['purchasing']), permissions });
    const buyer = withPermissions(['retail.purchase.create', 'retail.stock.read']);
    expect(canUse(buyer, 'purchaseOrders')).toBe(true);
    expect(canUse(buyer, 'goodsReceived')).toBe(true);
    // Landed costs and supplier credit notes are the money side too: no new permission, the same one as supplier invoices.
    for (const screen of ['supplierBills', 'supplierPayments', 'supplierPayables', 'landedCosts', 'supplierCredits'] as const) expect(canUse(buyer, screen), screen).toBe(false);
    const clerk = withPermissions(['retail.payable.manage']);
    for (const screen of ['supplierBills', 'supplierPayments', 'supplierPayables', 'landedCosts', 'supplierCredits'] as const) expect(canUse(clerk, screen), screen).toBe(true);
    expect(canUse(clerk, 'purchaseOrders')).toBe(false);
    expect(canUse(admin(['purchasing']), 'supplierPayables')).toBe(true);
    // The seeded sales role holds neither, so it sees no new screen with the capability on.
    expect(canUse({ ...mockMe('sales'), module_capabilities: { retail: ['purchasing'] } }, 'purchaseOrders')).toBe(false);
  });

  it('refuses every purchasing page by address while the capability is off', () => {
    const client = new QueryClient();
    const page = (me: Me, node: ReactElement) =>
      renderToString(
        <QueryClientProvider client={client}>
          <StaffContext.Provider value={{ me, branch: null }}>{node}</StaffContext.Provider>
        </QueryClientProvider>,
      );
    for (const node of [<PurchaseOrdersPage key="o" />, <GoodsReceivedPage key="g" />, <SupplierBillsPage key="b" />, <LandedCostsPage key="l" />, <SupplierCreditsPage key="c" />]) {
      expect(page(admin([]), node)).toContain('You do not have access');
      expect(page(admin(undefined), node)).toContain('You do not have access');
    }
    // Issue #557: paying suppliers and what is owed open without the capability, and still need the payables permission.
    for (const screen of ['supplierPayments', 'supplierPayables'] as const) {
      expect(canUse(admin([]), screen), screen).toBe(true);
      expect(canUse({ ...mockMe('sales'), module_capabilities: { retail: [] } }, screen), screen).toBe(false);
    }
  });

  it('shows the purchasing block of the settings only while it is on', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'capabilities'], { capabilities: { profile: 'shops' }, version: 1 });
    client.setQueryData(['retail', 'purchasing-settings'], { bill_tolerance_bp: 100, over_receipt_bp: 0, version: 1 });
    const settings = (me: Me) =>
      renderToString(
        <QueryClientProvider client={client}>
          <StaffContext.Provider value={{ me, branch: null }}>
            <RetailSettings />
          </StaffContext.Provider>
        </QueryClientProvider>,
      );
    expect(settings(admin([]))).not.toContain('Purchasing');
    expect(settings(admin(['purchasing']))).toContain('Save purchasing settings');
    const noSettingsPermission = { ...admin(['purchasing']), permissions: (admin().permissions ?? []).filter((p) => p !== 'core.settings.manage') };
    expect(settings(noSettingsPermission)).not.toContain('Save purchasing settings');
  });

  it('starts its tour once when first enabled, for whoever may order', () => {
    const progress: Progress = { [adminWelcome.id]: { status: 'completed', version: adminWelcome.version } };
    const tour = capabilityTourFor(TOURS, { ...admin(['purchasing']), tours: {} } as Me, progress);
    expect(tour?.id).toBe('capability-purchasing');
    expect(capabilityTourFor(TOURS, { ...admin([]), tours: {} } as Me, progress)).toBeUndefined();
  });
});
