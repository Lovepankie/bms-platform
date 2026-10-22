import { type KeyboardEvent, type ReactNode, useEffect, useId, useRef, useState } from 'react';
import { icons } from '../components/icons';
import { THEME_CHOICES, type ThemeChoice, useThemeChoice } from './theme-mode';
import { PLATFORM_BRAND, RINCOLTECH_LOGO, RINCOLTECH_SITE, type ShellBrand, THEME_PROPERTIES, appTitle, themeVars } from './branding';

// The shared shell (FR-TEN-08): the brand bar, the theme colour and the Powered-by footer are
// drawn here once, around every route, so sign-in, accept-invitation, the staff area, the member
// portal and the platform console all inherit them. An area never draws its own footer or sets
// its own brand colour. The controls at the top right (refresh, and the theme button of #452) go in `actions`, one group.

/** What each choice is called on screen (issue #452): plain words, the device one as people say it. */
export const THEME_LABELS: Record<ThemeChoice, string> = { system: 'Same as my phone', light: 'Light', dark: 'Dark' };

const THEME_ICONS: Record<ThemeChoice, ReactNode> = { system: icons.phone, light: icons.sun, dark: icons.moon };

/**
 * The theme button at the right of the header, on every area (issue #452, replacing the select of #429): a sun, a moon or a
 * phone for the current choice, and a small menu of the three (the menu button pattern: arrow keys, Escape, focus back).
 */
export function ThemeMenu() {
  const [choice, setChoice] = useThemeChoice();
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const items = useRef<(HTMLButtonElement | null)[]>([]);
  const label = `Theme: ${THEME_LABELS[choice]}`;

  useEffect(() => {
    if (!open) return;
    items.current[THEME_CHOICES.indexOf(choice)]?.focus();
    const onPointer = (event: PointerEvent) => {
      if (!wrap.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', onPointer);
    return () => document.removeEventListener('pointerdown', onPointer);
    // Focus goes to the current choice when the menu opens, not again when the choice changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const close = () => {
    setOpen(false);
    button.current?.focus();
  };

  const onMenuKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const at = items.current.indexOf(document.activeElement as HTMLButtonElement);
    const last = THEME_CHOICES.length - 1;
    const move: Record<string, number> = { ArrowDown: at + 1, ArrowUp: at - 1, Home: 0, End: last };
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
    } else if (event.key === 'Tab') {
      setOpen(false);
    } else if (event.key in move) {
      event.preventDefault();
      const next = move[event.key] ?? 0;
      items.current[next > last ? 0 : next < 0 ? last : next]?.focus();
    }
  };

  return (
    <div className="theme-menu" ref={wrap}>
      <button
        ref={button}
        type="button"
        className="btn-ghost btn-sm theme-button"
        aria-label={label}
        title={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        data-tour="theme-select"
        onClick={() => setOpen(!open)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <span className="theme-icon">{THEME_ICONS[choice]}</span>
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label="Theme" className="theme-menu-list" onKeyDown={onMenuKey}>
          {THEME_CHOICES.map((value, i) => (
            <button
              key={value}
              ref={(el) => {
                items.current[i] = el;
              }}
              type="button"
              role="menuitemradio"
              aria-checked={value === choice}
              tabIndex={-1}
              className="theme-menu-item"
              onClick={() => {
                setChoice(value);
                close();
              }}
            >
              <span className="theme-icon">{THEME_ICONS[value]}</span>
              <span className="theme-menu-text">{THEME_LABELS[value]}</span>
              <span className="theme-tick">{value === choice ? icons.check : null}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function BrandBar({ brand, actions }: { brand: ShellBrand; actions?: ReactNode }) {
  return (
    <div className="brand-bar">
      {brand.logoSrc ? (
        <img src={brand.logoSrc} alt={brand.name} />
      ) : (
        <span className="brand-name">{brand.name}</span>
      )}
      <div className="header-actions">
        {actions}
        <ThemeMenu />
      </div>
    </div>
  );
}

/** "Powered by" and the Rincoltech logo, linked to the Rincoltech site. Not removable by a tenant. */
export function Footer() {
  return (
    <footer className="app-footer">
      <span>Powered by</span>
      <a href={RINCOLTECH_SITE} target="_blank" rel="noopener noreferrer" aria-label="Rincoltech (opens the Rincoltech website)">
        <img src={RINCOLTECH_LOGO} alt="" width={84} height={24} />
      </a>
    </footer>
  );
}

export function Shell({ brand = PLATFORM_BRAND, actions, children }: { brand?: ShellBrand; actions?: ReactNode; children: ReactNode }) {
  useEffect(() => {
    const root = document.documentElement;
    const vars = themeVars(brand);
    THEME_PROPERTIES.forEach((name) => {
      const value = vars[name];
      if (value) root.style.setProperty(name, value);
      else root.style.removeProperty(name);
    });
    // iOS takes the home screen name from this tag when the page is added, not from the manifest (ADR-066).
    document.querySelector('meta[name="apple-mobile-web-app-title"]')?.setAttribute('content', appTitle(brand));
    // The tab icon is the logo too; a new address when the logo or colour changes, as Safari keeps favicons a long time.
    const icon = '/api/v1/branding/app/icon-192.png';
    document
      .querySelector('link[rel="icon"]')
      ?.setAttribute('href', brand.iconVersion ? `${icon}?v=${encodeURIComponent(brand.iconVersion)}` : icon);
  }, [brand]);

  return (
    <div className="app-shell">
      <BrandBar brand={brand} actions={actions} />
      <div className="app-content">{children}</div>
      <Footer />
    </div>
  );
}
