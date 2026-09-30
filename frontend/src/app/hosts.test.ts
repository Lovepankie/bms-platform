import { describe, expect, it } from 'vitest';
import { LOCAL_HOSTS, classifyHost, parseHostPattern, tenantHost } from './hosts';

// Same cases as the API's TenantHostPatternTest (chapter 7 section 7.2, ADR-018).
const staging = { tenantHostPattern: '{slug}-bms-staging.rincoltech.com', platformHost: 'bms-staging.rincoltech.com' };

describe('classifyHost', () => {
  it('finds the tenant on its exact host, case-insensitively', () => {
    expect(classifyHost('demo-bms-staging.rincoltech.com', staging)).toEqual({ kind: 'tenant', slug: 'demo' });
    expect(classifyHost('DEMO-BMS-STAGING.RINCOLTECH.COM', staging)).toEqual({ kind: 'tenant', slug: 'demo' });
    expect(classifyHost('demo.localhost', LOCAL_HOSTS)).toEqual({ kind: 'tenant', slug: 'demo' });
  });

  it('serves the console only on the platform host', () => {
    expect(classifyHost('bms-staging.rincoltech.com', staging)).toEqual({ kind: 'platform' });
    expect(classifyHost('localhost', LOCAL_HOSTS)).toEqual({ kind: 'platform' });
    expect(classifyHost('bms-staging.rincoltech.com.attacker.com', staging)).toEqual({ kind: 'unknown' });
  });

  it.each([
    'evil-demo-bms-staging.rincoltech.com.attacker.com',
    'demo-bms-staging.rincoltech.com.',
    'x.demo-bms-staging.rincoltech.com',
    'demo.bms-staging.rincoltech.com',
    'demo-bms-staging.other.com',
    'de-bms-staging.rincoltech.com',
    'admin-bms-staging.rincoltech.com',
    'demo--bms-staging.rincoltech.com',
    'rincoltech.com',
    '',
  ])('gives no tenant for the look-alike %s', (host) => {
    expect(classifyHost(host, staging)).toEqual({ kind: 'unknown' });
  });

  it('keeps each slug within one 63 character label', () => {
    expect(classifyHost(`${'a'.repeat(51)}-bms-staging.rincoltech.com`, staging).kind).toBe('tenant');
    expect(classifyHost(`${'a'.repeat(52)}-bms-staging.rincoltech.com`, staging).kind).toBe('unknown');
  });
});

describe('parseHostPattern', () => {
  it.each([
    'rincoltech.com',
    '{slug}',
    '{slug}-{slug}.rincoltech.com',
    'bms.{slug}.rincoltech.com',
    '{slug}-bms-.rincoltech.com',
    '{slug}-bms-staging.rincoltech.com.',
    '{slug}_bms.rincoltech.com',
  ])('refuses %s', (pattern) => {
    expect(parseHostPattern(pattern)).toBeNull();
  });
});

describe('tenantHost', () => {
  it('builds the host from the pattern', () => {
    expect(tenantHost('demo', staging)).toBe('demo-bms-staging.rincoltech.com');
    expect(tenantHost('demo', { ...staging, tenantHostPattern: '{slug}-bms.rincoltech.com' })).toBe(
      'demo-bms.rincoltech.com',
    );
    expect(tenantHost('Demo', staging)).toBeNull();
  });
});
