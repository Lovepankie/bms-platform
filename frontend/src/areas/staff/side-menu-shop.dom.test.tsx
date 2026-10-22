// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { PLATFORM_BRAND, type ShellBrand } from '../../app/branding';
import { router as appRouter } from '../../app/router';
import { ALL_BRANCHES } from '../../auth/branch';
import { TourProvider } from '../../tour/host';
import { StaffContext } from './context';
import { StaffBar, StaffSideNav } from './route';

// The shop in use at the top of the side menu and the phone drawer (issue #461, ADR-068): its own logo, small and rounded, beside
// its name; a shop without one shows the business logo, and with no business logo either, only the name. The folded sidebar keeps
// the image with the name as its tooltip. Every name below is invented.

const LOGO = '/api/v1/branches/b-two/logo?v=doc-1';
const BUSINESS = '/api/v1/branding/logo?v=doc-9';

let brand: ShellBrand = PLATFORM_BRAND;
vi.mock('../../app/branding', async (original) => ({
  ...(await original<typeof import('../../app/branding')>()),
  useShellBrand: () => brand,
}));

const me = {
  user_id: 'u-self',
  full_name: 'Test Cashier 01',
  kind: 'staff',
  permissions: [],
  modules: [],
  all_branches: true,
  branches: [
    { id: 'b-one', code: 'HQ', name: 'Test Shop One', is_head_office: true, stocks_goods: true, sells_goods: true, logo_url: null },
    { id: 'b-two', code: 'KLA', name: 'Test Shop Two', is_head_office: false, stocks_goods: true, sells_goods: true, logo_url: LOGO },
  ],
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
} as unknown as Me;

function widthIs(px: number) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: px <= Number(/max-width: (\d+)px/.exec(query)?.[1] ?? Infinity),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

function show(branch: string) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })}>
        <StaffContext.Provider value={{ me, branch }}>
          <TourProvider me={me} saveProgress={false}>
            <StaffSideNav me={me} />
            <StaffBar me={me} branch={branch} onBranch={() => undefined} onSignOut={() => undefined} />
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const sidebarShop = () => document.querySelector('.side-nav .current-shop') as HTMLElement;

beforeEach(() => {
  brand = PLATFORM_BRAND;
  window.scrollTo = vi.fn();
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  document.body.className = '';
  window.localStorage.clear();
});

describe('the shop in use in the sidebar (issue #461)', () => {
  beforeEach(() => widthIs(1280));

  it('shows the shop logo, rounded, beside the shop name', async () => {
    show('b-two');
    const image = (await within(sidebarShop()).findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    expect(image.className).toBe('current-shop-logo');
    expect(within(sidebarShop()).getByText('Test Shop Two (KLA)').className).toBe('current-shop-name');
  });

  it('falls back to the business logo for a shop without its own', async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    show('b-one');
    const image = (await within(sidebarShop()).findByAltText('Logo of Test Shop One (HQ)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(BUSINESS);
  });

  it('shows the name with no image when neither the shop nor the business has a logo', () => {
    show('b-one');
    expect(within(sidebarShop()).getByText('Test Shop One (HQ)')).toBeTruthy();
    expect(within(sidebarShop()).queryByRole('img')).toBeNull();
  });

  it('shows All branches with the business logo', async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    show(ALL_BRANCHES);
    expect((await within(sidebarShop()).findByAltText('Logo of All branches')).getAttribute('src')).toBe(BUSINESS);
  });

  it('keeps only the image when folded, the name as its tooltip', async () => {
    show('b-two');
    fireEvent.click(screen.getByRole('button', { name: 'Menu labels' }));
    await within(sidebarShop()).findByAltText('Logo of Test Shop Two (KLA)');
    expect(sidebarShop().getAttribute('title')).toBe('Test Shop Two (KLA)');
    expect(within(sidebarShop()).getByText('Test Shop Two (KLA)').className).toBe('visually-hidden');
  });
});

describe('the shop in use in the phone drawer (issue #461)', () => {
  beforeEach(() => widthIs(360));

  it('shows the shop logo beside the name above the areas', async () => {
    show('b-two');
    expect(document.querySelector('.side-nav')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Menu' }));
    const dialog = screen.getByRole('dialog', { name: 'Menu' });
    const image = (await within(dialog).findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    expect(within(dialog).getByText('Test Shop Two (KLA)')).toBeTruthy();
  });

  it('falls back to the business logo for a shop without its own', async () => {
    brand = { name: 'Test Traders', logoSrc: BUSINESS, themePrimary: null, themeText: null };
    show('b-one');
    fireEvent.click(screen.getByRole('button', { name: 'Menu' }));
    const dialog = screen.getByRole('dialog', { name: 'Menu' });
    expect((await within(dialog).findByAltText('Logo of Test Shop One (HQ)')).getAttribute('src')).toBe(BUSINESS);
  });
});
