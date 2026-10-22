// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../api/retail-mock';
import { StaffContext } from './context';

// After a logo or colour is saved on the set-up screen, the admin is told the app has a new icon and how it reaches a phone (issue
// #453, ADR-066), with the iPhone steps one tap away; this device stores the new icon version. Fabricated business and logo.

const settings = { version: 1, display_name: 'Test Traders', display_name_set: true, setup_dismissed: true } as Record<string, unknown>;
const client = {
  GET: vi.fn(async (path: string) =>
    path === '/api/v1/branding'
      ? {
          data: {
            display_name: 'Test Traders',
            logo_url: settings.logo_document_id ? `/api/v1/branding/logo?v=${String(settings.logo_document_id)}` : null,
            theme_primary: settings.theme_primary ?? null,
          },
        }
      : { data: { items: [] } },
  ),
  PUT: vi.fn(async () => {
    settings.logo_document_id = '00000000-0000-4000-8000-0000000000a1';
    return { data: settings };
  }),
  DELETE: vi.fn(async () => {
    delete settings.logo_document_id;
    return { data: settings };
  }),
  PATCH: vi.fn(async (_path: string, init: { body: Record<string, unknown> }) => {
    Object.assign(settings, init.body);
    return { data: settings };
  }),
};

vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()),
  api: new Proxy({}, { get: (_t, name: string) => (client as Record<string, unknown>)[name] }),
  fetchSettings: async () => ({ ...settings }),
}));
vi.mock('../../app/branding', async (original) => ({
  ...(await original<typeof import('../../app/branding')>()),
  useShellBrand: () => ({ name: 'Test Traders', logoSrc: '/logo.png', themePrimary: null, themeText: null }),
  useTenantHost: () => true,
}));
vi.mock('@tanstack/react-router', async (original) => ({
  ...(await original<typeof import('@tanstack/react-router')>()),
  Link: ({ children }: { children: ReactNode }) => <a href="#test">{children}</a>,
}));

const { Setup, hasRetail, reportsWording } = await import('./setup');
const { ICON_VERSION_KEY, NewLogoCard } = await import('../../install/NewIcon');

const REAL_AGENT = navigator.userAgent;
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1';

beforeEach(() => {
  delete settings.logo_document_id;
  delete settings.theme_primary;
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  Object.defineProperty(navigator, 'userAgent', { value: REAL_AGENT, configurable: true });
});

function show({ card = false, modules }: { card?: boolean; modules?: string[] } = {}) {
  const me = mockMe('owner');
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: { ...me, permissions: ['core.settings.manage'], modules: modules ?? me.modules }, branch: null, chooseBranch: () => {} }}>
        {card && <NewLogoCard />}
        <Setup />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

async function uploadLogo() {
  const input = (await screen.findByLabelText(/(Upload a|Replace the) logo/)) as HTMLInputElement;
  fireEvent.change(input, { target: { files: [new File(['x'], 'logo.png', { type: 'image/png' })] } });
  await screen.findByText(/Logo saved/);
}

describe('the set-up note on the new icon', () => {
  it('is not there before a logo is saved', async () => {
    show();
    await screen.findByRole('heading', { name: 'Business set-up' });
    expect(screen.queryByTestId('new-icon-note')).toBeNull();
  });

  it('appears after a save and reveals the iPhone steps', async () => {
    show();
    await uploadLogo();
    const note = await screen.findByTestId('new-icon-note');
    expect(note.textContent).toContain('Your app has a new icon. Android phones update it by themselves, usually within a day.');
    expect(note.textContent).toContain('On an iPhone, press and hold Test Traders on the home screen, then remove it and add it again.');
    fireEvent.click(screen.getByRole('button', { name: 'Show me how on an iPhone' }));
    expect(screen.getByTestId('ios-install-steps')).toBeTruthy();
  });

  it('goes when the logo is removed', async () => {
    show();
    await uploadLogo();
    await screen.findByTestId('new-icon-note');
    fireEvent.click(screen.getByRole('button', { name: 'Remove the logo' }));
    await screen.findByText('Logo removed.');
    await waitFor(() => expect(screen.queryByTestId('new-icon-note')).toBeNull());
  });

  it('appears after a colour save while a logo is set', async () => {
    settings.logo_document_id = '00000000-0000-4000-8000-0000000000a0';
    show();
    fireEvent.change(await screen.findByLabelText('Hex'), { target: { value: '#0D5C75' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Saved.');
    expect(await screen.findByTestId('new-icon-note')).toBeTruthy();
  });

  it('stores the new icon version on this device, so its own installed app shows no card', async () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
    );
    Object.defineProperty(navigator, 'userAgent', { value: IPHONE, configurable: true });
    settings.logo_document_id = 'doc-1';
    localStorage.setItem(ICON_VERSION_KEY, '/api/v1/branding/logo?v=doc-1|');
    show({ card: true });
    await uploadLogo();
    await screen.findByTestId('new-icon-note');
    expect(localStorage.getItem(ICON_VERSION_KEY)).toBe('/api/v1/branding/logo?v=00000000-0000-4000-8000-0000000000a1|');
    expect(screen.queryByTestId('new-logo-card')).toBeNull();
  });
});

// Issue #468: the page explains its fields in plain words and speaks only of the modules the business has on.
describe('the set-up hints and module wording', () => {
  it('explains the receipt footer and previews the bottom of a receipt', async () => {
    show();
    const footer = (await screen.findByRole('textbox', { name: 'Receipt footer' })) as HTMLTextAreaElement;
    expect(footer.getAttribute('aria-describedby')).toBe('footer-hint');
    expect(document.getElementById('footer-hint')?.textContent).toContain('A short line printed at the bottom of every receipt');
    expect(footer.placeholder).not.toBe('');
    expect(screen.queryByTestId('footer-preview')).toBeNull();
    fireEvent.change(footer, { target: { value: 'Thank you, call 0700 000000' } });
    expect(screen.getByRole('group', { name: 'Bottom of a receipt' }).textContent).toContain('Thank you, call 0700 000000');
  });

  it('explains the business name and the theme colour', async () => {
    show();
    const name = await screen.findByRole('textbox', { name: 'Business name' });
    expect(name.getAttribute('aria-describedby')).toBe('name-hint');
    expect(document.getElementById('name-hint')?.textContent).toContain('on the app, receipts and text messages');
    expect(document.getElementById('colour-hint')?.textContent).toBe(
      "The colour of the app's top bar and buttons. The app picks white or dark text for it; colours where neither is readable are refused.",
    );
    expect(screen.getByLabelText('Pick a colour').getAttribute('aria-describedby')).toBe('colour-hint');
    const hex = screen.getByRole('textbox', { name: 'Hex' });
    expect(hex.getAttribute('aria-describedby')).toBe('colour-hint');
    fireEvent.change(hex, { target: { value: '#12' } });
    expect(hex.getAttribute('aria-describedby')).toBe('colour-hint colour-problem');
  });

  it('keeps lending words off a retail-only business', async () => {
    show({ modules: ['retail'] });
    const reports = await screen.findByLabelText(/Let staff run reports/);
    expect(reports.parentElement?.textContent).toContain('sales, stock, profit and others');
    expect(document.body.textContent).not.toContain('portfolio at risk');
    expect(document.body.textContent).toContain('new orders, low stock, credit to collect');
  });

  it('keeps retail words off a lending-only business', async () => {
    show({ modules: ['lending'] });
    const reports = await screen.findByLabelText(/Let staff run reports/);
    expect(reports.parentElement?.textContent).toContain('portfolio at risk and others');
    expect(document.body.textContent).not.toMatch(/low stock|sales, stock/);
    expect(screen.queryByRole('group', { name: 'Notifications' })).toBeNull();
  });

  it('names both when both modules are on', async () => {
    show({ modules: ['lending', 'retail'] });
    await screen.findByLabelText(/Let staff run reports/);
    expect(document.body.textContent).toContain('portfolio at risk');
    expect(document.body.textContent).toContain('low stock');
  });

  it('words the reports for the modules on, neutrally for none, and both when /me has no module list', () => {
    expect(reportsWording(['lending', 'retail'])).toBe('sales, stock, profit, portfolio at risk and others');
    expect(reportsWording(undefined)).toBe(reportsWording(['lending', 'retail']));
    expect(reportsWording([])).toBe('and others');
    expect(hasRetail(undefined)).toBe(true);
    expect(hasRetail(['lending'])).toBe(false);
  });
});
