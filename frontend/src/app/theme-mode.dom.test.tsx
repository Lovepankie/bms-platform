// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PLATFORM_BRAND } from './branding';
import { BrandBar } from './shell';
import { THEME_COLOURS, THEME_KEY, applyTheme, parseChoice, resetThemeMemory, resolveTheme } from './theme-mode';

// The theme selector (issue #429): System, Light and Dark, remembered on the device, following the system
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
const pickerValue = () => (screen.getByLabelText('Theme') as HTMLSelectElement).value;

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

describe('the selector in the header', () => {
  it('is a labelled control with the three choices, System first and selected by default', () => {
    render(<BrandBar brand={PLATFORM_BRAND} />);
    const select = screen.getByLabelText('Theme') as HTMLSelectElement;
    expect(select.tagName).toBe('SELECT');
    expect(select.getAttribute('data-tour')).toBe('theme-select');
    expect([...select.options].map((o) => [o.value, o.text])).toEqual([['system', 'System'], ['light', 'Light'], ['dark', 'Dark']]);
    expect(select.value).toBe('system');
    expect(root().getAttribute('data-theme')).toBe('light');
  });

  it('Dark sets the theme, remembers it on the device and colours the installed app', () => {
    render(<BrandBar brand={PLATFORM_BRAND} />);
    fireEvent.change(screen.getByLabelText('Theme'), { target: { value: 'dark' } });
    expect(root().getAttribute('data-theme')).toBe('dark');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('dark');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(THEME_COLOURS.dark);
  });

  it('Light stays light on a dark system, and is remembered', () => {
    dark = true;
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(root().getAttribute('data-theme')).toBe('dark');
    fireEvent.change(screen.getByLabelText('Theme'), { target: { value: 'light' } });
    expect(root().getAttribute('data-theme')).toBe('light');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('light');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(THEME_COLOURS.light);
  });

  it('System forgets the saved choice and follows the system setting', () => {
    window.localStorage.setItem(THEME_KEY, 'dark');
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(pickerValue()).toBe('dark');
    fireEvent.change(screen.getByLabelText('Theme'), { target: { value: 'system' } });
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull();
    expect(root().getAttribute('data-theme')).toBe('light');
  });

  it('shows a remembered choice again when the app is opened later', () => {
    window.localStorage.setItem(THEME_KEY, 'dark');
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(pickerValue()).toBe('dark');
    expect(root().getAttribute('data-theme')).toBe('dark');
  });

  it('follows the system setting while on System, live, and not once a choice is made', () => {
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(root().getAttribute('data-theme')).toBe('light');
    dark = true;
    act(() => listeners.forEach((l) => l()));
    expect(root().getAttribute('data-theme')).toBe('dark');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(THEME_COLOURS.dark);
    fireEvent.change(screen.getByLabelText('Theme'), { target: { value: 'light' } });
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
    fireEvent.change(screen.getByLabelText('Theme'), { target: { value: 'dark' } });
    expect(root().getAttribute('data-theme')).toBe('dark');
    expect(pickerValue()).toBe('dark');
    // A fresh mount (the next navigation) still shows the choice, and the OS flipping does not throw it away.
    cleanup();
    render(<BrandBar brand={PLATFORM_BRAND} />);
    expect(pickerValue()).toBe('dark');
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
    expect(config).toContain(`theme_color: '${THEME_COLOURS.light}'`);
    expect(config).toContain("'theme-init.js'");
    const css = readFileSync(join(process.cwd(), 'src/app/theme.css'), 'utf8');
    const dark = css.match(/:root\[data-theme='dark'\]\s*\{([^}]*)\}/)?.[1] ?? '';
    expect(dark).toContain(`--color-bg: ${THEME_COLOURS.dark};`);
    const html = readFileSync(join(process.cwd(), 'index.html'), 'utf8');
    expect(html).toContain(`<meta name="theme-color" content="${THEME_COLOURS.light}" />`);
  });
});
