import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// The installed app is the business's own (ADR-066, issue #446): the page links the manifest and the iPhone icon
// the API draws from the business's logo, and the build writes no manifest of its own that could compete with it.

const html = readFileSync(join(process.cwd(), 'index.html'), 'utf8');
const config = readFileSync(join(process.cwd(), 'vite.config.ts'), 'utf8');

describe('the app manifest and icon of the business', () => {
  it('links the manifest and the apple-touch-icon of the API, and no static ones', () => {
    expect(html).toContain('<link rel="manifest" href="/api/v1/branding/app/manifest.webmanifest" />');
    expect(html).toContain('<link rel="apple-touch-icon" href="/api/v1/branding/app/apple-touch-icon.png" />');
    expect(html).not.toContain('href="/icons/apple-touch-icon.png"');
    expect(html).toContain('<meta name="apple-mobile-web-app-title" content="BMS" />');
    expect(html.match(/rel="manifest"/g)).toHaveLength(1);
  });

  it('uses the business logo as the browser tab icon, and no static one', () => {
    expect(html).toContain('<link rel="icon" type="image/png" sizes="192x192" href="/api/v1/branding/app/icon-192.png" />');
    expect(html.match(/rel="icon"/g)).toHaveLength(1);
    expect(html).not.toContain('href="/icon.svg"');
  });

  it('turns off the plugin manifest, keeps the BMS icons and keeps the API out of the service worker', () => {
    expect(config).toMatch(/manifest: false,/);
    expect(config).toContain("'icons/icon-maskable-512.png'");
    const deny = /navigateFallbackDenylist: \[(.*)\]/.exec(config)?.[1] ?? '';
    expect(deny).toContain('/^\\/api\\//');
  });
});
