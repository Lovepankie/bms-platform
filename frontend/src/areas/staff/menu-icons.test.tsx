import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement, ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { router as appRouter } from '../../app/router';
import { icons } from '../../components/icons';
import { FEATURES } from '../member/features';
import { AREAS } from '../platform/operator-areas';
import { StaffContext } from './context';
import { STAFF_SECTIONS, staffAreas } from './menu';
import { CatalogueLinks } from './retail/catalogue';
import { RetailHome } from './retail/home';
import { RetailMenuGroup, SHORTCUTS } from './retail/nav';
import { CATALOGUE_LINKS, CATALOGUE_SCREENS, SCREENS, SCREEN_CAPABILITY, needsOf } from './retail/permissions';

// Every item of a menu has its own icon (issue #483): two items of one menu never draw the same picture, compared by the drawing
// itself, so two names for one drawing are caught too. The same item keeps its icon wherever it shows (a retail shortcut and its
// tile, the Shop and Lending headings and their cards on Home, checked in home-cards.dom.test.tsx), which is why the tiles and
// shortcuts read their icon by the screen's name. Business set-up is one page of fields that draws no icons today;
// setup.dom.test.tsx fails if it ever draws one picture twice.

// An icon as its markup; a tile's icon already is.
const drawing = (icon: ReactNode) => (typeof icon === 'string' ? icon : icon ? renderToStaticMarkup(<>{icon}</>) : '');

/** The labels that share a drawing with an earlier item, or have none, as `label = earlier label`. */
function clashes(items: { label: string; icon: ReactNode }[]): string[] {
  const seen = new Map<string, string>();
  const found: string[] = [];
  for (const { label, icon } of items) {
    const svg = drawing(icon);
    if (!svg) found.push(`${label} has no icon`);
    else if (seen.has(svg)) found.push(`${label} = ${seen.get(svg)}`);
    else seen.set(svg, label);
  }
  return found;
}

// A session that holds every permission, apart from those `without` names, in a business with both modules and report runs on.
// The gates ask with includes or some; `without` reaches includes only, as some asks about a family of permissions and is always
// true here. The full list of labels below fails if a gate reads the permissions another way and drops an item.
function everything(without: string[] = []): Me {
  const permissions = Object.assign([] as string[], { includes: (p: string) => !without.includes(p), some: () => true });
  return { permissions, modules: ['retail', 'lending'], module_capabilities: [], branches: [], reports_enabled: true } as unknown as Me;
}

// An administrator who holds every retail permission with every retail capability on, so every tile is drawn.
function wholeShop(): Me {
  const admin = mockMe('admin');
  const screens = [...SCREENS.map((s) => s.screen), ...CATALOGUE_SCREENS, 'importer' as const];
  const permissions = [...new Set([...(admin.permissions ?? []), ...screens.flatMap((s) => needsOf(s))])];
  const scope = { all_branches: true, branch_ids: [] };
  return { ...admin, permissions, permission_scopes: Object.fromEntries(permissions.map((p) => [p, scope])), module_capabilities: { retail: [...new Set(Object.values(SCREEN_CAPABILITY))] } } as Me;
}

// What a page draws, for the session, as static markup.
function draw(me: Me, page: ReactElement): string {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  return renderToStaticMarkup(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null }}>{page}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

/** The icons a page draws in its tiles, in order, as each tile's label and drawing. */
function tileIcons(me: Me, page: ReactElement): { label: string; icon: string }[] {
  return [...draw(me, page).matchAll(/<span class="icon-chip">(.*?<\/svg>)?<\/span><strong>(.*?)<\/strong>/g)].map((m) => ({ label: m[2] ?? '', icon: m[1] ?? '' }));
}

/** Every retail shortcut as the side menu's Retail group draws it, as each link's label and drawing. */
function shortcutIcons(): { label: string; icon: string }[] {
  const all = SCREENS.filter((s) => s.screen in SHORTCUTS);
  const html = draw(wholeShop(), <RetailMenuGroup shortcuts={all} />);
  return [...html.matchAll(/<a [^>]*>(<svg.*?<\/svg>)?<span[^>]*>(.*?)<\/span><\/a>/g)].map((m) => ({ label: `Retail ${m[2] ?? ''}`, icon: m[1] ?? '' }));
}

// The side menu: its items, the retail shortcuts under Retail as their group draws them, and the two section headings.
function sideMenu(me: Me) {
  const shortcuts = shortcutIcons();
  expect(shortcuts.map((s) => s.label).sort()).toEqual(Object.values(SHORTCUTS).map((l) => `Retail ${l}`).sort());
  return [...staffAreas(me, { sells: true }), ...shortcuts, ...STAFF_SECTIONS.map((s) => ({ label: `${s.label} heading`, icon: s.icon }))];
}

const AREA_LABELS = ['Home', 'Approvals', 'Loans', 'Members', 'Savings', 'Investments', 'Insights', 'AI assistant', 'Reports', 'Retail', 'SMS', 'Receive payments', 'Users', 'Notifications', 'Feedback', 'Business set-up'];

describe('menu icons (issue #483)', () => {
  it('gives every item of the staff side menu its own icon', () => {
    expect(staffAreas(everything(), { sells: true }).map((l) => l.label)).toEqual(AREA_LABELS);
    expect(clashes(sideMenu(everything()))).toEqual([]);
    // Collateral takes the place of Members for a session that may not read members.
    const collateral = everything(['lending.members.read']);
    expect(staffAreas(collateral, { sells: true }).map((l) => l.label)).toEqual(AREA_LABELS.map((l) => (l === 'Members' ? 'Collateral' : l)));
    expect(clashes(sideMenu(collateral))).toEqual([]);
  });

  it('gives every retail tile its own icon, as the Retail page draws them', () => {
    const tiles = tileIcons(wholeShop(), <RetailHome />);
    expect(tiles.map((t) => t.label)).toEqual(SCREENS.map((s) => s.label));
    expect(clashes(tiles)).toEqual([]);
  });

  it('gives every catalogue tile its own icon, as the Catalogue page draws them, the import tile among them', () => {
    const tiles = tileIcons(wholeShop(), <CatalogueLinks />);
    expect(tiles.map((t) => t.label)).toEqual([...CATALOGUE_LINKS.map((l) => l.label), 'Import items']);
    expect(clashes(tiles)).toEqual([]);
  });

  it('gives every area of the operator portal its own icon', () => {
    expect(clashes(AREAS)).toEqual([]);
  });

  it('gives every card of the member portal its own icon', () => {
    expect(clashes(FEATURES.map((f) => ({ label: f.title, icon: icons[f.icon] })))).toEqual([]);
  });

  it('catches two items that share a drawing', () => {
    expect(clashes([{ label: 'Banked', icon: icons.banking }, { label: 'Receive payments', icon: icons.banking }])).toEqual(['Receive payments = Banked']);
  });
});
