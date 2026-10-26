import { describe, expect, it } from 'vitest';
import type { Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { router } from '../../app/router';
import { offendingWords } from '../../app/glossary';
import { ACTIONS, SYNONYMS, featuresFor, findFeatures } from './feature-search-state';
import { staffAreas } from './menu';
import { REPORT_PERMISSIONS } from './reports/permissions';
import { CATALOGUE_LINKS, SCREENS, canUse, needsOf, screensFor, showRetail } from './retail/permissions';
import { moneyLinks } from './tabs';

// Feature search (issue #551): the list holds exactly what the menu offers the session, by the menu's own checks, and finds a
// screen by its glossary name or by the everyday words people type. Every name is invented.

const CAPABILITIES = ['branch_roles', 'transfer_receipt', 'price_lists', 'customer_accounts', 'documents', 'returns', 'purchasing', 'units'];
const LENDING = [
  'lending.loans.read', 'lending.members.read', 'lending.members.create', 'lending.collateral.read', 'lending.savings.read', 'lending.investments.read',
  'lending.insights.read', 'lending.advisor.configure', 'core.approvals.read', 'core.users.read', 'core.users.manage', 'core.settings.manage', 'core.settings.read',
  REPORT_PERMISSIONS[0] ?? '',
];

/** Everything on: both modules, every capability, every permission a screen of the menu needs. */
const everything = {
  ...mockMe('admin'),
  permissions: [...new Set([...SCREENS, ...CATALOGUE_LINKS].flatMap((s) => needsOf(s.screen)).concat(LENDING, 'retail.advance.create'))],
  permission_scopes: undefined,
  modules: ['retail', 'lending'],
  module_capabilities: { retail: CAPABILITIES },
  reports_enabled: true,
} as unknown as Me;
const admin = mockMe('admin') as Me;
const seller = mockMe('sales') as Me;
const cashier = mockMe('cashier') as Me;
const officer = { ...mockMe('admin'), permissions: ['lending.loans.read', 'lending.members.read', 'core.approvals.read'], modules: ['lending'] } as unknown as Me;

const paths = (me: Me, sells = true) => featuresFor(me, { sells }).map((f) => f.to);
const labels = (me: Me, term: string) => findFeatures(featuresFor(me, { sells: true }), term).map((f) => f.label);

/** What the menu itself offers the session: the areas, the retail screens and catalogue pages, and the Money page. */
function menuPaths(me: Me, sells = true): Set<string> {
  const retail = showRetail(me);
  return new Set([
    ...staffAreas(me, { sells }).map((a) => a.to),
    ...(retail ? screensFor(me, { sells }).map((s) => s.path) : []),
    ...(retail ? CATALOGUE_LINKS.filter((l) => canUse(me, l.screen)).map((l) => l.path) : []),
    ...moneyLinks(me).map((l) => l.to),
  ]);
}

describe('feature search: what it lists (issue #551)', () => {
  it.each([
    ['everything on', everything],
    ['an administrator', admin],
    ['a seller', seller],
    ['a cashier', cashier],
    ['a loan officer', officer],
  ])('lists for %s only what the menu offers, plus the actions of screens it may open', (_, me) => {
    const menu = menuPaths(me);
    const actions: Record<string, string> = {
      '/staff/users/new': '/staff/users',
      '/staff/lending/members/new': '/staff/lending/members',
      '/staff/retail/catalogue/categories': '/staff/retail/catalogue/categories',
    };
    for (const to of paths(me)) expect(menu.has(to) || menu.has(actions[to] ?? to), to).toBe(true);
  });

  it('lists every screen of the menu', () => {
    for (const me of [everything, admin, seller, cashier, officer]) {
      const listed = new Set(paths(me));
      for (const to of menuPaths(me)) expect(listed.has(to), to).toBe(true);
    }
  });

  it('leaves out the screens a seller may not open, whatever is typed', () => {
    for (const term of ['expense', 'spend', 'profit', 'amagoba', 'users', 'staff', 'abakozi', 'settings', 'invite', 'bank', 'add an item', 'price list']) {
      expect(labels(seller, term), term).toEqual([]);
    }
    expect(paths(seller)).not.toContain('/staff/retail/expenses');
    expect(paths(seller)).not.toContain('/staff/users');
  });

  it('follows the modules: no retail screen without retail, no lending screen without lending', () => {
    expect(paths(officer).filter((p) => p.startsWith('/staff/retail'))).toEqual([]);
    expect(paths({ ...everything, modules: ['retail'] } as Me).filter((p) => p.startsWith('/staff/lending'))).toEqual([]);
    expect(paths({ ...everything, modules: ['lending'] } as Me).filter((p) => p.startsWith('/staff/retail'))).toEqual([]);
  });

  it('follows the capabilities: a screen of one that is off never shows', () => {
    const off = { ...everything, module_capabilities: { retail: [] } } as unknown as Me;
    for (const to of ['/staff/retail/purchase-orders', '/staff/retail/returns', '/staff/retail/documents', '/staff/retail/price-lists', '/staff/retail/in-transit']) {
      expect(paths(everything)).toContain(to);
      expect(paths(off)).not.toContain(to);
    }
    expect(labels(off, 'quote')).toEqual([]);
    expect(labels(everything, 'quote')).toContain('Quotations');
  });

  it('leaves out Record a sale where the session sells nowhere, as the menu does', () => {
    expect(paths(seller, true)).toContain('/staff/retail/sale');
    expect(paths(seller, false)).not.toContain('/staff/retail/sale');
  });

  it('lists each screen once', () => {
    const keys = featuresFor(everything, { sells: true }).map((f) => `${f.to} ${f.label}`);
    expect(new Set(keys).size).toBe(keys.length);
  });
});

describe('feature search: how it matches (issue #551)', () => {
  it('finds the main jobs by their glossary names', () => {
    expect(labels(admin, 'record a sale')[0]).toBe('Record a sale');
    expect(labels(admin, 'buy stock')[0]).toBe('Buy stock');
    expect(labels(admin, 'count stock')[0]).toBe('Count stock');
    expect(labels(admin, 'customers who owe')[0]).toBe('Customers who owe');
    expect(labels(admin, 'move stock')[0]).toBe('Move stock');
    expect(labels(cashier, 'expenses')[0]).toBe('Expenses');
    expect(labels(cashier, 'savings')[0]).toBe('Savings');
    expect(labels(everything, 'users')[0]).toBe('Staff');
    expect(labels(admin, 'add an item')[0]).toBe('Add an item');
  });

  it('finds them by the everyday words people type, in English and Luganda', () => {
    expect(labels(seller, 'sell')).toContain('Record a sale');
    expect(labels(admin, 'restock')).toContain('Buy stock');
    expect(labels(admin, 'purchase')).toContain('Buy stock');
    expect(labels(cashier, 'spend')).toContain('Expenses');
    expect(labels(cashier, 'save')).toContain('Savings');
    expect(labels(admin, 'stock take')).toContain('Count stock');
    expect(labels(admin, 'stocktake')).toContain('Count stock');
    expect(labels(admin, 'debt')).toContain('Customers who owe');
    expect(labels(admin, 'transfer')).toContain('Move stock');
    expect(labels(admin, 'product')).toContain('Items');
    expect(labels(everything, 'staff')).toContain('Staff');
    expect(labels(everything, 'abakozi')).toContain('Staff');
    expect(labels(seller, 'okutunda')).toContain('Record a sale');
    expect(labels(admin, 'okugula')).toContain('Buy stock');
    expect(labels(admin, 'bbanja')).toContain('Customers who owe');
  });

  it('ignores case, accents and stray signs, matches word starts, and finds nothing for nothing', () => {
    expect(labels(admin, 'RESTOCK')).toContain('Buy stock');
    expect(labels(admin, '  Bùy   stóck ')[0]).toBe('Buy stock');
    expect(labels(admin, 'stock-take')).toContain('Count stock');
    expect(labels(admin, 'tock')).toEqual([]);
    expect(labels(admin, '')).toEqual([]);
    expect(labels(admin, ' - ')).toEqual([]);
    expect(labels(admin, 'zzzz')).toEqual([]);
  });

  it('puts a name that starts with the words before one found by a synonym or a hint', () => {
    const found = labels(admin, 'stock');
    expect(found[0]).toBe('Stock');
    expect(found.indexOf('Stock value')).toBeLessThan(found.indexOf(found.find((l) => !/stock/i.test(l)) ?? 'none'));
  });

  it('shows only glossary names and menu hints: a banned word typed never comes back', () => {
    for (const term of ['restock', 'stocktake', 'stock take', 'variance', 'credit']) {
      for (const f of findFeatures(featuresFor(everything, { sells: true }), term)) {
        expect(offendingWords(`${f.label} ${f.hint ?? ''}`), `${term}: ${f.label}`).toEqual([]);
      }
    }
  });
});

describe('feature search: the synonym table (issue #551)', () => {
  const routes = Object.keys(router.routesByPath).map((p) => (p.length > 1 ? p.replace(/\/$/, '') : p));

  it('names only real screens', () => {
    for (const to of Object.keys(SYNONYMS)) expect(routes, to).toContain(to);
  });

  it('opens only real routes: every action, and everything listed with everything on', () => {
    expect(ACTIONS.map((a) => a.to)).toEqual(['/staff/retail/catalogue/categories', '/staff/users/new', '/staff/lending/members/new', '/staff/lending/applications/new']);
    for (const a of ACTIONS) expect(routes, a.to).toContain(a.to);
    for (const f of featuresFor(everything, { sells: true })) expect(routes, f.to).toContain(f.to);
  });

  it('offers Add an item only with the catalogue permission the Add an item button asks for', () => {
    const withoutManage = { ...everything, permissions: (everything.permissions ?? []).filter((p) => p !== 'retail.catalogue.manage') } as Me;
    expect(labels(everything, 'add an item')).toContain('Add an item');
    expect(labels(withoutManage, 'add an item')).not.toContain('Add an item');
    expect(labels(seller, 'add an item')).not.toContain('Add an item');
  });

  it('gives every screen of the menu its own everyday words, in lower case', () => {
    for (const f of featuresFor(everything, { sells: true })) expect(f.synonyms.length, f.to).toBeGreaterThan(0);
    for (const words of Object.values(SYNONYMS)) for (const w of words) expect(w).toBe(w.toLowerCase());
    expect(featuresFor(everything, { sells: true }).length).toBeGreaterThan(60);
  });
});
