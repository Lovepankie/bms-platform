// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PLATFORM_BRAND, brandOf } from './branding';
import { Shell } from './shell';

// iOS reads the home screen name from apple-mobile-web-app-title when the page is added, not from the manifest (ADR-066): the
// shell keeps the tag on the business's short name, and on BMS for the platform. Fabricated names only.

afterEach(cleanup);

beforeEach(() => {
  document.head.innerHTML = '<meta name="apple-mobile-web-app-title" content="BMS" />';
});

const title = () => document.querySelector('meta[name="apple-mobile-web-app-title"]')?.getAttribute('content');

describe('the iPhone home screen name', () => {
  it('is the business short name on its host', () => {
    render(<Shell brand={brandOf({ display_name: 'Fabricated Trading Company' }, null)}>x</Shell>);
    expect(title()).toBe('Fabricated');
  });

  it('is BMS on the platform host', () => {
    render(<Shell brand={PLATFORM_BRAND}>x</Shell>);
    expect(title()).toBe('BMS');
  });

  it('versions the tab icon by logo and colour, and keeps it plain without a logo', () => {
    document.head.innerHTML = '<link rel="icon" href="/api/v1/branding/app/icon-192.png" />';
    const href = () => document.querySelector('link[rel="icon"]')?.getAttribute('href');
    const shop = { display_name: 'Fabricated Trading Company', logo_url: '/api/v1/branding/logo?v=a1', theme_primary: '#112233' };
    const { rerender } = render(<Shell brand={brandOf(shop, null)}>x</Shell>);
    const first = href();
    rerender(<Shell brand={brandOf({ ...shop, logo_url: '/api/v1/branding/logo?v=b2' }, null)}>x</Shell>);
    expect(first).toMatch(/icon-192\.png\?v=/);
    expect(href()).not.toBe(first);
    rerender(<Shell brand={PLATFORM_BRAND}>x</Shell>);
    expect(href()).toBe('/api/v1/branding/app/icon-192.png');
  });
});
