// Which area a host serves (chapter 7 section 7.2, ADR-018): the platform console on the platform
// host, a tenant on the tenant host pattern (for example {slug}-bms-staging.rincoltech.com), and
// nothing on any other host. The rules match the API's TenantHostPattern: exactly one {slug} in the
// leftmost label, an exact and case-insensitive match, and a valid slug. The hosts come at run time
// from /app-config.json, served by the web container from its environment, so one image serves
// staging and production.

export type HostConfig = { tenantHostPattern: string; platformHost: string };

export type HostArea = { kind: 'tenant'; slug: string } | { kind: 'platform' } | { kind: 'unknown' };

/** Local development: http://demo.localhost:8000 is the demo tenant, localhost the platform host. */
export const LOCAL_HOSTS: HostConfig = { tenantHostPattern: '{slug}.localhost', platformHost: 'localhost' };

const PLACEHOLDER = '{slug}';
const SLUG = /^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])$/;
const RESERVED = new Set(['www', 'api', 'app', 'admin', 'static', 'mail']);
const LABEL = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const LABEL_PART = /^[a-z0-9-]*$/;

type Pattern = { prefix: string; suffix: string; labelRest: number };

/** Parses a tenant host pattern; returns null when it breaks a rule the API enforces at startup. */
export function parseHostPattern(raw: string): Pattern | null {
  const pattern = raw.trim().toLowerCase();
  const at = pattern.indexOf(PLACEHOLDER);
  if (at < 0 || pattern.indexOf(PLACEHOLDER, at + 1) >= 0) return null;
  const labels = pattern.split('.');
  const first = labels[0] ?? '';
  if (labels.length < 2 || !first.includes(PLACEHOLDER)) return null;
  const before = first.slice(0, first.indexOf(PLACEHOLDER));
  const after = first.slice(first.indexOf(PLACEHOLDER) + PLACEHOLDER.length);
  if (!LABEL_PART.test(before) || !LABEL_PART.test(after) || before.startsWith('-') || after.endsWith('-')) {
    return null;
  }
  if (before.length + after.length + 3 > 63) return null;
  if (!labels.slice(1).every((label) => LABEL.test(label))) return null;
  return { prefix: before, suffix: after + pattern.slice(first.length), labelRest: before.length + after.length };
}

function validSlug(slug: string, pattern: Pattern): boolean {
  return SLUG.test(slug) && !RESERVED.has(slug) && slug.length + pattern.labelRest <= 63;
}

/** The area a host name (window.location.hostname, no port) serves under the configuration. */
export function classifyHost(hostname: string, config: HostConfig): HostArea {
  const host = hostname.toLowerCase();
  if (config.platformHost && host === config.platformHost.trim().toLowerCase()) return { kind: 'platform' };
  const pattern = parseHostPattern(config.tenantHostPattern);
  if (!pattern) return { kind: 'unknown' };
  const { prefix, suffix } = pattern;
  if (host.length <= prefix.length + suffix.length || !host.startsWith(prefix) || !host.endsWith(suffix)) {
    return { kind: 'unknown' };
  }
  const slug = host.slice(prefix.length, host.length - suffix.length);
  return validSlug(slug, pattern) ? { kind: 'tenant', slug } : { kind: 'unknown' };
}

/** The tenant's host for a slug, for links from the platform console; null for an invalid slug. */
export function tenantHost(slug: string, config: HostConfig): string | null {
  const pattern = parseHostPattern(config.tenantHostPattern);
  return pattern && validSlug(slug, pattern) ? pattern.prefix + slug + pattern.suffix : null;
}

/** Reads /app-config.json; unset or unreadable values fall back to the local development hosts. */
export async function loadHostConfig(): Promise<HostConfig> {
  try {
    const response = await fetch('/app-config.json', { cache: 'no-cache' });
    if (!response.ok) return LOCAL_HOSTS;
    const body = (await response.json()) as Partial<HostConfig>;
    return {
      tenantHostPattern: body.tenantHostPattern || LOCAL_HOSTS.tenantHostPattern,
      platformHost: body.platformHost || LOCAL_HOSTS.platformHost,
    };
  } catch {
    return LOCAL_HOSTS;
  }
}
