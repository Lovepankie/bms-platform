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
  };
}

/** The CSS custom properties a brand sets on :root; none for the platform look. */
export function themeVars(brand: ShellBrand): Record<string, string> {
  return brand.themePrimary && brand.themeText
    ? { '--brand': brand.themePrimary, '--brand-contrast': brand.themeText }
    : {};
}

export const THEME_PROPERTIES = ['--brand', '--brand-contrast'] as const;

/** The logo image. In local development the tenant travels as a header, which an <img> cannot send. */
async function loadLogo(url: string): Promise<string> {
  const headers = tenantHeaders();
  if (Object.keys(headers).length === 0) return url;
  const response = await fetch(url, { headers });
  if (!response.ok) throw new Error('no logo');
  return URL.createObjectURL(await response.blob());
}

export function useShellBrand(): ShellBrand {
  const hosts = useQuery({ queryKey: ['app-config'], queryFn: loadHostConfig, staleTime: Infinity });
  const tenantHost =
    hosts.data !== undefined &&
    (Boolean(import.meta.env.DEV && import.meta.env.VITE_DEV_TENANT) ||
      classifyHost(window.location.hostname, hosts.data).kind === 'tenant');
  const branding = useQuery({
    queryKey: ['branding'],
    enabled: tenantHost,
    staleTime: 60_000,
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/branding');
      if (error || !data) throw new Error('Could not load the branding');
      return data;
    },
  });
  const logoUrl = branding.data?.logo_url ?? null;
  const logo = useQuery({
    queryKey: ['branding-logo', logoUrl],
    enabled: logoUrl !== null,
    staleTime: Infinity,
    queryFn: () => loadLogo(logoUrl as string),
  });
  if (!tenantHost || !branding.data) return PLATFORM_BRAND;
  return brandOf(branding.data, logoUrl === null ? null : (logo.data ?? null));
}
