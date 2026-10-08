import type { Me } from '../../../api/client';

// What the insights page offers a session (#153). The API is the authority: it narrows every
// figure to the caller's branches and, without all_officers, to their own loans; this only
// decides what the menu and the page show, from the /me permissions.

export const INSIGHTS_READ = 'lending.insights.read';
export const INSIGHTS_ALL_OFFICERS = 'lending.insights.all_officers';
export const INSIGHTS_EXPORT = 'lending.insights.export';
export const SETTINGS_MANAGE = 'core.settings.manage';

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** The Insights menu entry: the tenant has lending and the user may read insights. */
export function showInsights(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  if (me.modules && !me.modules.includes('lending')) return false;
  return held(me).includes(INSIGHTS_READ);
}

/** True when the figures cover only the user's own loans. */
export function ownPortfolioOnly(me: Pick<Me, 'permissions'>): boolean {
  return !held(me).includes(INSIGHTS_ALL_OFFICERS);
}

export function canExport(me: Pick<Me, 'permissions'>): boolean {
  return held(me).includes(INSIGHTS_EXPORT);
}

export function canManageDigest(me: Pick<Me, 'permissions'>): boolean {
  return held(me).includes(SETTINGS_MANAGE);
}
