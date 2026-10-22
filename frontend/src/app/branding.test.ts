import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { PLATFORM_BRAND, appTitle, brandOf, shortAppName } from './branding';

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
