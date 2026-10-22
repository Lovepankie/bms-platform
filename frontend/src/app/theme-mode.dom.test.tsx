// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PLATFORM_BRAND } from './branding';
import { BrandBar } from './shell';
import { THEME_COLOURS, THEME_KEY, applyTheme, parseChoice, resetThemeMemory, resolveTheme } from './theme-mode';

// The theme choice (issue #429) and its button and menu in the header (issue #452): Same as my phone, Light and Dark, remembered on the device, following the system
// setting, set before the first paint (public/theme-init.js) and carried to the installed app's theme colour.

type Listener = () => void;
let dark = false;
let listeners: Listener[] = [];

function stubSystem() {
  window.matchMedia = ((query: string) => ({
    media: query,
    get matches() {
      return query.includes('dark') && dark;
    },
    addEventListener: (_: string, fn: Listener) => listeners.push(fn),
    removeEventListener: (_: string, fn: Listener) => (listeners = listeners.filter((l) => l !== fn)),
  })) as unknown as typeof window.matchMedia;
}

const root = () => document.documentElement;
const themeButton = () => screen.getByRole('button', { name: /^Theme: / });
const pickerValue = () => themeButton().getAttribute('aria-label');
const LABEL = { system: 'Theme: Same as my phone', light: 'Theme: Light', dark: 'Theme: Dark' } as const;

/** Opens the menu with a tap and picks a choice by its words. */
function pick(name: 'Light' | 'Dark' | 'Same as my phone') {
  fireEvent.click(themeButton());
  fireEvent.click(screen.getByRole('menuitemradio', { name }));
}

beforeEach(() => {
  dark = false;
  listeners = [];
  window.localStorage.clear();
  resetThemeMemory();
  document.head.innerHTML = '<meta name="theme-color" content="#0077b6" />';
  root().removeAttribute('data-theme');
  root().style.colorScheme = '';
  stubSystem();
});
afterEach(cleanup);

describe('the theme choice', () => {
  it('reads anything but light or dark as System', () => {
    expect(['light', 'dark', 'system', 'x', '', null, undefined].map(parseChoice)).toEqual(['light', 'dark', 'system', 'system', 'system', 'system', 'system']);
  });

  it('resolves System from the system setting and the others as chosen', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('dark', false)).toBe('dark');
  });
});

describe('the theme button in the header', () => {
  it('is a menu button with the phone icon and Same as my phone by default, anchored for the tour', () => {
    render(<BrandBar brand={PLATFORM_BRAND} />);
    const button = themeButton();
    expect(button.tagName).toBe('BUTTON');
    expect(button.getAttribute('data-tour')).toBe('theme-select');
    expect(button.getAttribute('aria-haspopup')).toBe('menu');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.getAttribute('aria-label')).toBe(LABEL.system);
    expect(button.getAttribute('title')).toBe(LABEL.system);
    expect(button.querySelector('rect')).not.toBeNull();
    expect(screen.queryByRole('menu')).toBeNull();
    expect(root().getAttribute('data-theme')).toBe('light');
  });

  it.each([
    ['light', LABEL.light, 'circle'],
    ['dark', LABEL.dark, 'path'],
    ['system', LABEL.system, 'rect'],
  ] as const)('shows the right icon and label for %s', (saved, label, shape) => {
    if (saved !== 'system') window.localStorage.setItem(THEME_KEY, saved);
    render(<BrandBar brand={PLATFORM_BRAND} />);
    const svg = themeButton().querySelector('svg');
    expect(themeButton().getAttribute('aria-label')).toBe(label);
    expect(themeButton().getAttribute('title')).toBe(label);
    expect(svg?.getAttribute('aria-hidden')).toBe('true');
    expect(svg?.firstElementChild?.tagName.toLowerCase()).toBe(shape);
  });

  it('opens a menu of the three choices in words, the current one ticked and focused', () => {
    window.localStorage.setItem(THEME_KEY, 'dark');
    render(<BrandBar brand={PLATFORM_BRAND} />);
    fireEvent.click(themeButton());
    expect(themeButton().getAttribute('aria-expanded')).toBe('true');
    const menu = screen.getByRole('menu');
    expect(themeButton().getAttribute('aria-controls')).toBe(menu.id);
    const items = screen.getAllByRole('menuitemradio') as [HTMLElement, HTMLElement, HTMLElement];
    expect(items.map((i) => [i.textContent, i.getAttribute('aria-checked')])).toEqual([['Same as my phone', 'false'], ['Light', 'false'], ['Dark', 'true']]);
    expect(items[2].querySelector('.theme-tick svg')).not.toBeNull();
    expect(items[0].querySelector('.theme-tick svg')).toBeNull();
    expect(document.activeElement).toBe(items[2]);
  });

  it('picking a choice changes it, closes the menu and gives focus back to the button', () => {
    render(<BrandBar brand={PLATFORM_BRAND} />);
    pick('Dark');
    expect(screen.queryByRole('menu')).toBeNull();
    expect(themeButton().getAttribute('aria-expanded')).toBe('false');
    expect(pickerValue()).toBe(LABEL.dark);
    expect(document.activeElement).toBe(themeButton());
  });

  it('moves with the arrow keys, wrapping round, and Escape closes and returns focus', () => {
    render(<BrandBar brand={PLATFORM_BRAND} />);
    fireEvent.keyDown(themeButton(), { key: 'ArrowDown' });
    const items = screen.getAllByRole('menuitemradio') as [HTMLElement, HTMLElement, HTMLElement];
    expect(document.activeElement).toBe(items[0]);
    fireEvent.keyDown(items[0], { key: 'ArrowDown' });
    expect(document.activeElement).toBe(items[1]);
    fireEvent.keyDown(items[1], { key: 'End' });
    expect(document.activeElement).toBe(items[2]);
    fireEvent.keyDown(items[2], { key: 'ArrowDown' });
    expect(document.activeElement).toBe(items[0]);
    fireEvent.keyDown(items[0], { key: 'ArrowUp' });
    expect(document.activeElement).toBe(items[2]);
    fireEvent.keyDown(items[2], { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(themeButton());
    expect(pickerValue()).toBe(LABEL.system);
  });

  it('closes on a tap outside, changing nothing', () => {
    render(
      <>
        <BrandBar brand={PLATFORM_BRAND} />
        <p>Elsewhere</p>
      </>,
    );
    fireEvent.click(themeButton());
    fireEvent.pointerDown(screen.getByRole('menuitemradio', { name: 'Light' }));
    expect(screen.getByRole('menu')).toBeTruthy();
    fireEvent.pointerDown(screen.getByText('Elsewhere'));
    expect(screen.queryByRole('menu')).toBeNull();
    expect(pickerValue()).toBe(LABEL.system);
  });

  it('Dark sets the theme, remembers it on the device and colours the installed app', () => {
    render(<BrandBar brand={PLATFORM_BRAND} />);
    pick('Dark');
    expect(root().getAttribute('data-theme')).toBe('dark');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('dark');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(THEME_COLOURS.dark);
  });

  it('Light stays light on a dark system, and is remembered', () => {
    dark = true;
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(root().getAttribute('data-theme')).toBe('dark');
    pick('Light');
    expect(root().getAttribute('data-theme')).toBe('light');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('light');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(THEME_COLOURS.light);
  });

  it('Same as my phone forgets the saved choice and follows the system setting', () => {
    window.localStorage.setItem(THEME_KEY, 'dark');
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(pickerValue()).toBe(LABEL.dark);
    pick('Same as my phone');
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull();
    expect(root().getAttribute('data-theme')).toBe('light');
  });

  it('shows a remembered choice again when the app is opened later', () => {
    window.localStorage.setItem(THEME_KEY, 'dark');
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(pickerValue()).toBe(LABEL.dark);
    expect(root().getAttribute('data-theme')).toBe('dark');
  });

  it('follows the system setting while on Same as my phone, live, and not once a choice is made', () => {
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(root().getAttribute('data-theme')).toBe('light');
    dark = true;
    act(() => listeners.forEach((l) => l()));
    expect(root().getAttribute('data-theme')).toBe('dark');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(THEME_COLOURS.dark);
    pick('Light');
    expect(listeners).toHaveLength(0);
    dark = false;
    expect(root().getAttribute('data-theme')).toBe('light');
  });

  it('still works when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    render(<BrandBar brand={PLATFORM_BRAND} />);
    pick('Dark');
    expect(root().getAttribute('data-theme')).toBe('dark');
    expect(pickerValue()).toBe(LABEL.dark);
    // A fresh mount (the next navigation) still shows the choice, and the OS flipping does not throw it away.
    cleanup();
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(pickerValue()).toBe(LABEL.dark);
    expect(listeners).toHaveLength(0);
    dark = false;
    act(() => listeners.forEach((l) => l()));
    expect(root().getAttribute('data-theme')).toBe('dark');
    vi.restoreAllMocks();
  });
});

describe('the pre-paint script (no flash of the wrong theme)', () => {
  const script = readFileSync(join(process.cwd(), 'public/theme-init.js'), 'utf8');
  const html = readFileSync(join(process.cwd(), 'index.html'), 'utf8');
  const run = () => window.eval(script);

  it('is a plain script in the head, before the app and after the theme-color tag, from the same origin', () => {
    const tag = html.indexOf('<meta name="theme-color"');
    const init = html.indexOf('<script src="/theme-init.js"></script>');
    expect(tag).toBeGreaterThan(-1);
    expect(init).toBeGreaterThan(tag);
    expect(init).toBeLessThan(html.indexOf('</head>'));
    expect(init).toBeLessThan(html.indexOf('/src/main.tsx'));
    expect(html.slice(init - 5, init + 40)).not.toMatch(/type="module"|async|defer/);
    expect(script).not.toMatch(/\bimport\b|\bfetch\(/);
  });

  it.each([
    ['nothing saved, light system', null, false, 'light'],
    ['nothing saved, dark system', null, true, 'dark'],
    ['saved dark, light system', 'dark', false, 'dark'],
    ['saved light, dark system', 'light', true, 'light'],
    ['an unknown value', 'purple', true, 'dark'],
  ])('%s', (_name, saved, systemDark, theme) => {
    if (saved) window.localStorage.setItem(THEME_KEY, saved);
    dark = systemDark;
    run();
    expect(root().getAttribute('data-theme')).toBe(theme);
    expect(root().style.colorScheme).toBe(theme);
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(THEME_COLOURS[theme as 'light' | 'dark']);
  });

  it('sets the same attributes the app does, from the same key', () => {
    for (const [saved, systemDark] of [[null, true], ['dark', false], ['light', true]] as const) {
      window.localStorage.clear();
      if (saved) window.localStorage.setItem(THEME_KEY, saved);
      dark = systemDark;
      run();
      const fromScript = [root().getAttribute('data-theme'), root().style.colorScheme];
      root().removeAttribute('data-theme');
      applyTheme(parseChoice(saved), systemDark);
      expect(fromScript).toEqual([root().getAttribute('data-theme'), root().style.colorScheme]);
    }
  });

  it('survives blocked storage and a missing matchMedia', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    (window as { matchMedia?: unknown }).matchMedia = undefined;
    expect(run).not.toThrow();
    expect(root().getAttribute('data-theme')).toBe('light');
    vi.restoreAllMocks();
  });
});

describe('the installed app theme colour', () => {
  it('has a manifest theme_color equal to the light colour, and a dark colour that is the dark surface', () => {
    const config = readFileSync(join(process.cwd(), 'vite.config.ts'), 'utf8');
    expect(config).toContain("'theme-init.js'");
    // The manifest is the API's (ADR-066); a business without a readable colour gets the contract's default.
    const contract = JSON.parse(readFileSync(join(process.cwd(), '../docs/api/openapi.json'), 'utf8')) as {
      components: { schemas: { AppManifest: { properties: { theme_color: { default?: string } } } } };
    };
    expect(contract.components.schemas.AppManifest.properties.theme_color.default).toBe(THEME_COLOURS.light.toUpperCase());
    const css = readFileSync(join(process.cwd(), 'src/app/theme.css'), 'utf8');
    const dark = css.match(/:root\[data-theme='dark'\]\s*\{([^}]*)\}/)?.[1] ?? '';
    expect(dark).toContain(`--color-bg: ${THEME_COLOURS.dark};`);
    const html = readFileSync(join(process.cwd(), 'index.html'), 'utf8');
    expect(html).toContain(`<meta name="theme-color" content="${THEME_COLOURS.light}" />`);
  });
});
