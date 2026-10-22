import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PLATFORM_BRAND, appTitle, brandOf, loadLogo, shortAppName } from './branding';

vi.mock('../auth/session', async (original) => ({
  ...(await original<typeof import('../auth/session')>()),
  tenantHeaders: () => ({ 'X-Tenant': 'test-traders' }),
}));

// The iPhone's home screen name (ADR-066): the same cut as the manifest's short_name, from the cases the backend's
// AppManifestShortNameTest reads too, so the two cannot drift. Fabricated names only.

const cases = (JSON.parse(readFileSync('../fixtures/app-short-names.json', 'utf8')) as { cases: { name: string; short: string }[] }).cases;

describe('the home screen name', () => {
  it.each(cases.map((c) => [c.name, c.short]))('cuts %s to %s', (name, short) => {
    expect(shortAppName(name)).toBe(short);
  });

  it('is BMS on the platform host and the business short name on its own', () => {
    expect(appTitle(PLATFORM_BRAND)).toBe('BMS');
    expect(appTitle(brandOf({ display_name: 'Fabricated Trading Company' }, null))).toBe('Fabricated');
  });
});

// In local development a logo is fetched with the tenant header and shown from an object URL; a replaced logo's URL is let go.
describe('a logo loaded with the tenant header', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('revokes the object URL of the logo it replaces, and only that one', async () => {
    let made = 0;
    const revoke = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new Blob(['png']))));
    vi.stubGlobal('URL', Object.assign(class extends URL {}, { createObjectURL: () => `blob:logo-${++made}`, revokeObjectURL: revoke }));
    const first = await loadLogo('/api/v1/branches/b-two/logo?v=doc-1');
    await loadLogo('/api/v1/branding/logo?v=doc-9');
    expect(revoke).not.toHaveBeenCalled();
    await loadLogo('/api/v1/branches/b-two/logo?v=doc-2');
    expect(revoke).toHaveBeenCalledTimes(1);
    expect(revoke).toHaveBeenCalledWith(first);
  });
});
