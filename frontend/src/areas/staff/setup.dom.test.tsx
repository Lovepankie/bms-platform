// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TenantSettings } from '../../api/client';
import { mockMe } from '../../api/retail-mock';
import { icons } from '../../components/icons';
import { StaffContext } from './context';
import { DoneToast } from './retail/toast';

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

const { Setup, brandDone, hasRetail, reportsWording } = await import('./setup');
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

function show({ card = false, modules, permissions = ['core.settings.manage'], extra, owner = false }: { card?: boolean; modules?: string[]; permissions?: string[]; extra?: ReactNode; owner?: boolean } = {}) {
  const me = mockMe('owner');
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: { ...me, permissions, modules: modules ?? me.modules, is_owner: owner }, branch: null, chooseBranch: () => {} }}>
        {card && <NewLogoCard />}
        <Setup />
        {extra}
      </StaffContext.Provider>
      <DoneToast />
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
    await screen.findByText('Logo removed');
    await waitFor(() => expect(screen.queryByTestId('new-icon-note')).toBeNull());
  });

  it('appears after a colour save while a logo is set', async () => {
    settings.logo_document_id = '00000000-0000-4000-8000-0000000000a0';
    show();
    fireEvent.change(await screen.findByLabelText('Hex'), { target: { value: '#0D5C75' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Settings saved');
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

// ADR-071, issue #466: a lending business sets the day the app became its register; existing loans come in only from before it.
describe('the end of the financial year (ADR-090)', () => {
  it('is June until the Owner chooses another month, saved as financial_year_end_month', async () => {
    delete settings.financial_year_end_month;
    show({ owner: true });
    const select = (await screen.findByLabelText('The month your financial year ends in')) as HTMLSelectElement;
    expect(select.value).toBe('6');
    expect(select.disabled).toBe(false);
    fireEvent.change(select, { target: { value: '12' } });
    await waitFor(() => expect(settings.financial_year_end_month).toBe(12));
    expect(await screen.findByText('Year end set to December')).toBeTruthy();
    expect(screen.queryByText('The financial year ends on the last day of December.')).toBeNull();
  });

  it('is shown to another administrator, who cannot change it', async () => {
    show();
    const select = (await screen.findByLabelText('The month your financial year ends in')) as HTMLSelectElement;
    expect(select.disabled).toBe(true);
  });
});

describe('the go-live date', () => {
  it('is offered to a lending business and saved as go_live_date', async () => {
    client.PATCH.mockClear();
    show({ modules: ['lending'] });
    const input = (await screen.findByLabelText(/The day the app became your register/)) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '2026-03-01' } });
    fireEvent.blur(input);
    await screen.findByText('Go live date saved');
    expect(client.PATCH).toHaveBeenCalledWith('/api/v1/settings', expect.objectContaining({ body: { go_live_date: '2026-03-01' } }));
  });

  it('is not offered to a business without lending', async () => {
    show({ modules: ['retail'] });
    await screen.findByRole('heading', { name: 'Business set-up' });
    expect(screen.queryByLabelText(/The day the app became your register/)).toBeNull();
  });
});

// Issue #514: an item of the checklist is Done when its value is saved, the business name given at onboarding included.
// JUSTIFICATION-A3: a new block of tests for the fix, no new mechanism.
describe('the set-up checklist', () => {
  // Each test starts from the same settings: the whole object is restored, so no logo or colour leaks into the next test.
  let saved: Record<string, unknown> = {};
  beforeEach(() => {
    saved = { ...settings };
  });
  afterEach(() => {
    for (const k of Object.keys(settings)) delete settings[k];
    Object.assign(settings, saved);
  });

  function status(label: string): string | undefined {
    const item = screen.getAllByRole('listitem').find((li) => li.textContent?.startsWith(`${label}:`));
    return item?.querySelector('strong')?.textContent ?? undefined;
  }

  it('shows Done for a name, logo, colour and footer that are already set', async () => {
    Object.assign(settings, {
      setup_dismissed: false,
      display_name_set: false,
      logo_document_id: '00000000-0000-4000-8000-0000000000a2',
      theme_primary: '#7A0019',
      receipt_footer: 'Thank you for coming',
    });
    show();
    await screen.findByRole('heading', { name: 'Checklist' });
    for (const label of ['Business name', 'Logo', 'Theme colour', 'Receipt footer']) expect(status(label)).toBe('Done');
  });

  it('shows To do for no logo, the platform colour and a blank footer', async () => {
    Object.assign(settings, { setup_dismissed: false, receipt_footer: '   ' });
    show();
    await screen.findByRole('heading', { name: 'Checklist' });
    expect(status('Business name')).toBe('Done');
    for (const label of ['Logo', 'Theme colour', 'Receipt footer']) expect(status(label)).toBe('To do');
  });

  it('judges each item from the saved settings', () => {
    const base = { version: 1, display_name_set: false } as TenantSettings;
    expect(brandDone(base)).toEqual({ name: false, logo: false, colour: false, footer: false });
    expect(brandDone({ ...base, display_name: 'Test Shop 01' }).name).toBe(true);
    expect(brandDone({ ...base, display_name: '  ' }).name).toBe(false);
    expect(brandDone({ ...base, display_name_set: true }).name).toBe(true);
    expect(brandDone({ ...base, theme_primary: '#0D5C75' }).colour).toBe(true);
    expect(brandDone({ ...base, receipt_footer: ' \n' }).footer).toBe(false);
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

// Issue #483: no two items of the set-up menu draw the same picture. The More set-up tiles (#508) each draw their own.
const repeated = (root: ParentNode) => {
  const drawings = [...root.querySelectorAll('svg')].map((svg) => svg.outerHTML);
  return drawings.filter((svg, i) => drawings.indexOf(svg) !== i);
};

// Everything the page renders, with the checklist and every section shown, as `show` draws it: the page's <main> and anything
// beside it.
async function wholePage(extra?: ReactNode) {
  const { container } = show({ modules: ['lending', 'retail'], permissions: ['core.settings.manage', 'core.branches.read', 'core.users.read', 'core.referrals.read'], card: true, extra });
  await screen.findByRole('link', { name: 'Referrals' });
  await screen.findByRole('heading', { name: 'Checklist' });
  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Business set-up');
  return container;
}

describe('the set-up menu icons', () => {
  beforeEach(() => {
    settings.setup_dismissed = false;
  });
  afterEach(() => {
    settings.setup_dismissed = true;
  });

  it('catches one picture drawn twice beside the page', async () => {
    const container = await wholePage(<>{icons.settings}{icons.settings}</>);
    expect(repeated(container)).toHaveLength(1);
  });

  it('draws no picture twice', async () => {
    expect(repeated(await wholePage())).toEqual([]);
  });

  it('lists the other set-up pages as tiles, each an icon above its label and a hint (#508)', async () => {
    await wholePage();
    const tiles = within(screen.getByRole('list', { name: 'More set-up' })).getAllByRole('listitem');
    expect(tiles.map((t) => t.querySelector('.tile-label')?.textContent)).toEqual([
      'Branches', 'Users', 'Approval rules', 'Two-step sign-in', 'Two-step for yourself', 'Notifications for the business', 'Referrals', 'Text messages', 'Close the business',
    ]);
    for (const t of tiles) {
      expect(t.querySelector('.icon-chip svg')).not.toBeNull();
      expect(t.querySelector('.tile-hint')?.textContent).not.toBe('');
    }
  });
});
