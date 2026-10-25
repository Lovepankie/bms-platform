import { useQuery } from '@tanstack/react-query';
import { api, type Branding } from '../api/client';
import { tenantHeaders } from '../auth/session';
import { classifyHost, loadHostConfig } from './hosts';

// What the shell shows as the brand (FR-TEN-08): a tenant host shows that tenant's logo, name and
// theme colour, read from the public GET /api/v1/branding (no sign-in, resolved from the host);
// the platform host and any other host show the Rincoltech brand.

export const RINCOLTECH_LOGO = '/brand/rincoltech-logo.png';
export const RINCOLTECH_SITE = 'https://rincoltech.com';

export interface ShellBrand {
  name: string;
  /** The logo's address, or null: the name is shown as text instead. */
  logoSrc: string | null;
  themePrimary: string | null;
  themeText: string | null;
  /** Changes when the logo or the colour changes, so the tab icon's address changes with them (ADR-066). */
  iconVersion?: string;
}

export const PLATFORM_BRAND: ShellBrand = {
  name: 'Rincoltech',
  logoSrc: RINCOLTECH_LOGO,
  themePrimary: null,
  themeText: null,
};

export function brandOf(branding: Branding, logoSrc: string | null): ShellBrand {
  return {
    name: branding.display_name ?? 'BMS Platform',
    logoSrc,
    themePrimary: branding.theme_primary ?? null,
    themeText: branding.theme_text ?? null,
    iconVersion: branding.logo_url ? `${branding.logo_url}|${branding.theme_primary ?? ''}` : undefined,
  };
}

/** The CSS custom properties a brand sets on :root; none for the platform look. */
export function themeVars(brand: ShellBrand): Record<string, string> {
  return brand.themePrimary && brand.themeText
    ? { '--brand': brand.themePrimary, '--brand-contrast': brand.themeText }
    : {};
}

export const THEME_PROPERTIES = ['--brand', '--brand-contrast'] as const;

/** The home screen label's length, as the API's manifest cuts its short_name (ADR-066). */
export const SHORT_NAME_MAX = 12;
/** A cut at a word boundary is preferred when it keeps at least this many characters. */
export const SHORT_NAME_WORD_MIN = 6;

/**
 * The name cut to {@link SHORT_NAME_MAX} characters as a reader counts them (an emoji is never split), at the last word boundary
 * when that keeps at least {@link SHORT_NAME_WORD_MIN}: the same cut as the manifest's short_name.
 */
export function shortAppName(name: string): string {
  const characters = [...new Intl.Segmenter('en', { granularity: 'grapheme' }).segment(name)];
  if (characters.length <= SHORT_NAME_MAX) return name;
  const cut = characters[SHORT_NAME_MAX]?.index ?? name.length;
  const minimum = characters[SHORT_NAME_WORD_MIN]?.index ?? 0;
  const word = [...new Intl.Segmenter('en', { granularity: 'word' }).segment(name)]
    .map((s) => s.index)
    .filter((i) => i <= cut)
    .pop();
  return name.slice(0, word !== undefined && word >= minimum ? word : cut).trim();
}

/** The iPhone's home screen name: the business's short name on its host, BMS on the platform's (as the manifest says). */
export function appTitle(brand: ShellBrand): string {
  return brand === PLATFORM_BRAND ? 'BMS' : shortAppName(brand.name);
}

/**
 * The logo image. In local development the tenant travels as a header, which an <img> cannot send. The object URL is never
 * revoked: a cached query or an image still on screen may hold an older version of the same logo, and it must keep showing.
 */
export async function loadLogo(url: string): Promise<string> {
  const headers = tenantHeaders();
  if (Object.keys(headers).length === 0) return url;
  const response = await fetch(url, { headers });
  if (!response.ok) throw new Error('no logo');
  return URL.createObjectURL(await response.blob());
}

/** The public branding of the host's tenant; one cached query shared by the shell and the landing page. */
export const BRANDING_QUERY = {
  queryKey: ['branding'],
  staleTime: 60_000,
  queryFn: async (): Promise<Branding> => {
    const { data, error } = await api.GET('/api/v1/branding');
    if (error || !data) throw new Error('Could not load the branding');
    return data;
  },
};

/** Whether this host serves a tenant (the Vite dev server's X-Tenant slug counts as one), or null while unknown. */
export function useTenantHost(): boolean | null {
  const hosts = useQuery({ queryKey: ['app-config'], queryFn: loadHostConfig, staleTime: Infinity });
  if (hosts.data === undefined) return null;
  return Boolean(import.meta.env.DEV && import.meta.env.VITE_DEV_TENANT) || classifyHost(window.location.hostname, hosts.data).kind === 'tenant';
}

/**
 * The business's own name for the staff menu (issue #564, ADR-086 decision 8): the trimmed `display_name` of this host's tenant
 * branding once it has loaded, else null. Null on a host that serves no tenant, while the branding loads, when it fails, and
 * when it carries no name, so a caller never falls back to a product name the way the shell brand does.
 */
export function useBusinessName(): string | null {
  const tenantHost = useTenantHost() === true;
  const branding = useQuery({ ...BRANDING_QUERY, enabled: tenantHost });
  return (tenantHost && branding.data?.display_name?.trim()) || null;
}

/** A public logo URL (the business's or a shop's, issue #461): the address an <img> can show, or null, and whether it is still loading. */
export function useLogo(logoUrl: string | null): { src: string | null; loading: boolean } {
  const logo = useQuery({
    queryKey: ['branding-logo', logoUrl],
    enabled: logoUrl !== null,
    staleTime: Infinity,
    queryFn: () => loadLogo(logoUrl as string),
  });
  if (logoUrl === null) return { src: null, loading: false };
  return { src: logo.data ?? null, loading: logo.isPending };
}

/** The address an <img> can show for a public logo URL, or null while none. */
export function useLogoSrc(logoUrl: string | null): string | null {
  return useLogo(logoUrl).src;
}

export function useShellBrand(): ShellBrand {
  const tenantHost = useTenantHost() === true;
  const branding = useQuery({ ...BRANDING_QUERY, enabled: tenantHost });
  const logoSrc = useLogoSrc(branding.data?.logo_url ?? null);
  if (!tenantHost || !branding.data) return PLATFORM_BRAND;
  return brandOf(branding.data, logoSrc);
}
