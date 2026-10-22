import { type ReactNode, useEffect } from 'react';
import { THEME_CHOICES, type ThemeChoice, useThemeChoice } from './theme-mode';
import { PLATFORM_BRAND, RINCOLTECH_LOGO, RINCOLTECH_SITE, type ShellBrand, THEME_PROPERTIES, appTitle, themeVars } from './branding';

// The shared shell (FR-TEN-08): the brand bar, the theme colour and the Powered-by footer are
// drawn here once, around every route, so sign-in, accept-invitation, the staff area, the member
// portal and the platform console all inherit them. An area never draws its own footer or sets
// its own brand colour. The controls at the top right (refresh, and the theme selector of #429) go in `actions`, one group.

const THEME_LABELS: Record<ThemeChoice, string> = { system: 'System', light: 'Light', dark: 'Dark' };

/** The theme selector at the right of the header, on every area (issue #429): System, Light or Dark, kept on this device. */
export function ThemeSelector() {
  const [choice, setChoice] = useThemeChoice();
  return (
    <select
      className="theme-select"
      aria-label="Theme"
      data-tour="theme-select"
      value={choice}
      onChange={(e) => setChoice(e.target.value as ThemeChoice)}
    >
      {THEME_CHOICES.map((value) => (
        <option key={value} value={value}>
          {THEME_LABELS[value]}
        </option>
      ))}
    </select>
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
        <ThemeSelector />
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
