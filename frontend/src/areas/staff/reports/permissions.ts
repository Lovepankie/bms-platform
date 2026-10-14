import type { Me } from '../../../api/client';

// What the Reports page offers a session (extras slice D1). The server decides which reports a user may run; this decides
// whether the menu shows the entry: the tenant has lending, the tenant has switched report runs on (`/me` says so, and a
// business administrator turns them on in Business set-up), and the user holds a report permission.

export const REPORT_PERMISSIONS = [
  'lending.reports.portfolio',
  'lending.reports.collections',
  'lending.reports.members',
  'lending.reports.compliance',
];

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

export function showReports(me: Pick<Me, 'permissions'> & { modules?: string[]; reports_enabled?: boolean }): boolean {
  if (me.modules && !me.modules.includes('lending')) return false;
  if (me.reports_enabled !== true) return false;
  return held(me).some((p) => REPORT_PERMISSIONS.includes(p));
}
