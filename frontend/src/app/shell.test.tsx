import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { PLATFORM_BRAND, RINCOLTECH_LOGO, RINCOLTECH_SITE, brandOf, themeVars } from './branding';
import { router } from './router';
import { Shell } from './shell';

const tenant = brandOf(
  { display_name: 'Test Trading Co', theme_primary: '#0D5C75', theme_text: '#FFFFFF', logo_url: '/api/v1/branding/logo?v=1' },
  '/api/v1/branding/logo?v=1',
);
const tenantNoLogo = brandOf({ display_name: 'Test Trading Co' }, null);

describe('the shared shell (FR-TEN-08)', () => {
  const brands = { tenant, 'tenant without a logo': tenantNoLogo, 'platform host': PLATFORM_BRAND };

  for (const [name, brand] of Object.entries(brands)) {
    it(`has the Powered-by footer with the Rincoltech logo and an accessible link: ${name}`, () => {
      const html = renderToStaticMarkup(
        <Shell brand={brand}>
          <main>content</main>
        </Shell>,
      );
      expect(html).toContain('Powered by');
      expect(html).toContain(`src="${RINCOLTECH_LOGO}"`);
      expect(html).toContain(`href="${RINCOLTECH_SITE}"`);
      expect(html).toContain('aria-label="Rincoltech (opens the Rincoltech website)"');
      expect(html).toContain('rel="noopener noreferrer"');
      expect(html).toContain('<main>content</main>');
    });
  }

  it('shows the tenant logo with the display name as its alt text, or the name as text', () => {
    const withLogo = renderToStaticMarkup(<Shell brand={tenant}>x</Shell>);
    expect(withLogo).toContain('<img src="/api/v1/branding/logo?v=1" alt="Test Trading Co"/>');
    const withoutLogo = renderToStaticMarkup(<Shell brand={tenantNoLogo}>x</Shell>);
    expect(withoutLogo).toContain('<span class="brand-name">Test Trading Co</span>');
  });

  it('shows the Rincoltech brand on the platform host', () => {
    const html = renderToStaticMarkup(<Shell brand={PLATFORM_BRAND}>x</Shell>);
    expect(html).toContain(`<img src="${RINCOLTECH_LOGO}" alt="Rincoltech"/>`);
  });

  it('sets the theme variables only for a tenant with a colour', () => {
    expect(themeVars(tenant)).toEqual({ '--brand': '#0D5C75', '--brand-contrast': '#FFFFFF' });
    expect(themeVars(tenantNoLogo)).toEqual({});
    expect(themeVars(PLATFORM_BRAND)).toEqual({});
  });

  it('is the root of every area, so each inherits the footer and the theme', () => {
    const areas = ((router.routeTree.children ?? []) as unknown as { path?: string }[]).map((route) => route.path);
    for (const path of ['/', 'sign-in', 'accept-invitation', 'staff', 'member', 'style']) {
      expect(areas).toContain(path);
    }
  });
});
