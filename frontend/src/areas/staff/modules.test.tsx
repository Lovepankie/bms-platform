import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { BranchRole } from '../../api/retail';
import { mockMe } from '../../api/retail-mock';
import { StaffContext } from './context';
import { showInsights } from './insights/permissions';
import { showInvestments } from './lending/investment-state';
import { showLending } from './lending/permissions';
import { showSavings } from './lending/savings-permissions';
import { staffAreas } from './menu';
import { capabilityOn, screensFor, sellingBranches, sellsAt, showRetail } from './retail/permissions';
import { RecordSale } from './retail/sale';

// Issue #185 (the menu follows the tenant's modules) and ADR-033 (screens follow the capabilities and
// the branch roles). Static markup tests, as the other staff screen tests are.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

const LENDING_PERMS = ['lending.loans.read', 'lending.savings.read', 'lending.investments.read', 'lending.insights.read'];

/** A tenant admin: every retail permission and every lending permission the menu looks at. */
const admin = (modules?: string[], capabilities?: Record<string, string[]>) => {
  const base = mockMe('admin');
  return { ...base, permissions: [...(base.permissions ?? []), ...LENDING_PERMS], modules, module_capabilities: capabilities };
};

describe('the menu follows the enabled modules (issue #185)', () => {
  const combos: { name: string; modules: string[]; lending: boolean; retail: boolean }[] = [
    { name: 'lending only', modules: ['lending'], lending: true, retail: false },
    { name: 'retail only', modules: ['retail'], lending: false, retail: true },
    { name: 'both', modules: ['lending', 'retail'], lending: true, retail: true },
    { name: 'neither', modules: [], lending: false, retail: false },
  ];

  for (const c of combos) {
    it(`offers ${c.name} what the tenant has, though the admin role holds every permission`, () => {
      const me = admin(c.modules);
      expect(showLending(me)).toBe(c.lending);
      expect(showSavings(me)).toBe(c.lending);
      expect(showInvestments(me)).toBe(c.lending);
      expect(showInsights(me)).toBe(c.lending);
      expect(showRetail(me)).toBe(c.retail);
    });
  }

  it('falls back to the permissions for a payload without a module list', () => {
    const me = admin(undefined);
    expect(showLending(me)).toBe(true);
    expect(showRetail(me)).toBe(true);
  });
});

describe('capabilities and branch roles (ADR-033)', () => {
  const A = mockMe('admin').branches?.[0]?.id ?? '';
  const B = mockMe('admin').branches?.[1]?.id ?? '';
  const roles: BranchRole[] = [
    { branch_id: A, code: 'BR1', name: 'Test Branch A', role: 'store', can_sell: false },
    { branch_id: B, code: 'BR2', name: 'Test Branch B', role: 'shop', can_sell: true },
  ];

  it('reads a capability from /me and treats an absent list as off', () => {
    expect(capabilityOn(admin(['retail'], { retail: ['branch_roles'] }), 'retail', 'branch_roles')).toBe(true);
    expect(capabilityOn(admin(['retail'], { retail: [] }), 'retail', 'branch_roles')).toBe(false);
    expect(capabilityOn(admin(['retail']), 'retail', 'branch_roles')).toBe(false);
  });

  it('all off equals today: unknown roles mean every branch sells and the sale screen stays', () => {
    const me = admin(['retail']);
    expect(sellsAt(undefined, A)).toBe(true);
    expect(sellingBranches(me.branches ?? [], undefined)).toHaveLength((me.branches ?? []).length);
    expect(screensFor(me).map((s) => s.screen)).toContain('sale');
    expect(screensFor(me, { sells: true })).toEqual(screensFor(me));
  });

  it('leaves stores out of the selling branches', () => {
    const me = admin(['retail'], { retail: ['branch_roles'] });
    expect(sellsAt(roles, A)).toBe(false);
    expect(sellsAt(roles, B)).toBe(true);
    expect(sellingBranches(me.branches ?? [], roles).map((b) => b.id)).toEqual([B]);
  });

  it('drops the sale screen and the Sell row for a user who can sell nowhere', () => {
    const me = admin(['retail'], { retail: ['branch_roles'] });
    expect(screensFor(me, { sells: false }).map((s) => s.screen)).not.toContain('sale');
    expect(screensFor(me, { sells: false }).map((s) => s.screen)).toContain('stock');
    expect(staffAreas(me, { sells: false }).map((l) => l.label)).not.toContain('Sell');
    expect(staffAreas(me, { sells: true }).map((l) => l.label)).toContain('Sell');
  });

  const sale = (me: ReturnType<typeof admin>, branch: string, seeded?: BranchRole[]) => {
    const client = new QueryClient();
    if (seeded) client.setQueryData(['retail', 'branch-roles'], seeded);
    return renderToString(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>
          <RecordSale />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
  };

  it('tells the user a store does not sell and offers the shop', () => {
    const html = sale(admin(['retail'], { retail: ['branch_roles'] }), A, roles);
    expect(html).toContain('is a store, and a store does not sell');
    expect(html).toContain('Sell at');
    expect(html).not.toContain('for="sale-search"');
  });

  it('shows the sale form at a shop', () => {
    const html = sale(admin(['retail'], { retail: ['branch_roles'] }), B, roles);
    expect(html).not.toContain('does not sell');
    expect(html).toContain('class="sale-form"');
  });

  it('ignores roles while the capability is off', () => {
    const html = sale(admin(['retail'], { retail: [] }), A, roles);
    expect(html).not.toContain('does not sell');
  });
});
