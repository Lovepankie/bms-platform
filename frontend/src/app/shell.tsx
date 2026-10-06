import { type ReactNode, useEffect } from 'react';
import { PLATFORM_BRAND, RINCOLTECH_LOGO, RINCOLTECH_SITE, type ShellBrand, THEME_PROPERTIES, themeVars } from './branding';

// The shared shell (FR-TEN-08): the brand bar, the theme colour and the Powered-by footer are
// drawn here once, around every route, so sign-in, accept-invitation, the staff area, the member
// portal and the platform console all inherit them. An area never draws its own footer or sets
// its own brand colour.

export function BrandBar({ brand }: { brand: ShellBrand }) {
  return (
    <div className="brand-bar">
      {brand.logoSrc ? (
        <img src={brand.logoSrc} alt={brand.name} />
      ) : (
        <span className="brand-name">{brand.name}</span>
      )}
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

export function Shell({ brand = PLATFORM_BRAND, children }: { brand?: ShellBrand; children: ReactNode }) {
  useEffect(() => {
    const root = document.documentElement;
    const vars = themeVars(brand);
    THEME_PROPERTIES.forEach((name) => {
      const value = vars[name];
      if (value) root.style.setProperty(name, value);
      else root.style.removeProperty(name);
    });
  }, [brand]);

  return (
    <div className="app-shell">
      <BrandBar brand={brand} />
      <div className="app-content">{children}</div>
      <Footer />
    </div>
  );
}
