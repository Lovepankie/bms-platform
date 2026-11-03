import { createRoot } from 'react-dom/client';
import { PLATFORM_BRAND, RINCOLTECH_LOGO } from '../src/app/branding';
import { BrandBar, Footer, LogoImg } from '../src/app/shell';
import { THEME_KEY } from '../src/app/theme-mode';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page logo.layout.test.ts lays out in a real browser (issue #115): the Rincol Tech logo where the app draws it, in the brand
// bar, in the Powered-by footer and on the landing hero, under the app's own stylesheets. ?theme= is the person's saved choice
// (light or dark), read by the theme button exactly as in the app, so the page is drawn in that theme.

const theme = new URLSearchParams(window.location.search).get('theme') ?? 'light';
window.localStorage.setItem(THEME_KEY, theme);

// The landing page hides the brand bar of its shell (pages.css), so its hero is drawn beside the shell, not inside it.
createRoot(document.getElementById('root') as HTMLElement).render(
  <>
    <div className="app-shell">
      <BrandBar brand={PLATFORM_BRAND} />
      <main>Page</main>
      <Footer />
    </div>
    <div className="landing">
      <section className="card landing-hero">
        <LogoImg className="brand-logo" src={RINCOLTECH_LOGO} alt={PLATFORM_BRAND.name} />
      </section>
    </div>
  </>,
);
