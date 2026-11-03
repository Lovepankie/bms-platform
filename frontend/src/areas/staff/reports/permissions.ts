import type { Me } from '../../../api/client';

// What the Reports page offers a session (extras slice D1). The server decides which reports a user may run; this decides
// whether the menu shows the entry and what the page says. A user of a lending business who holds a report permission sees the
// entry (holdsReports); they may run reports only while the tenant has switched report runs on (`/me` says so, and a business
// administrator turns them on in Business set-up), which is showReports. While runs are off the page says so and, for someone
// who may change the business settings, links to the switch (issue #764), instead of sending them to Staff users.

export const REPORT_PERMISSIONS = [
  'lending.reports.portfolio',
  'lending.reports.collections',
  'lending.reports.members',
  'lending.reports.compliance',
];

/** The permission that may change the business settings, the reports switch among them (PATCH /api/v1/settings). */
export const SETTINGS_MANAGE = 'core.settings.manage';

type ReportsMe = Pick<Me, 'permissions'> & { modules?: string[]; reports_enabled?: boolean };

const held = (me: Pick<Me, 'permissions'>): string[] => me.permissions ?? [];

/** A lending user who holds a report permission, whether or not report runs are switched on. */
export function holdsReports(me: ReportsMe): boolean {
  if (me.modules && !me.modules.includes('lending')) return false;
  return held(me).some((p) => REPORT_PERMISSIONS.includes(p));
}

/** A report holder whose business has switched report runs on: they may run reports. */
export function showReports(me: ReportsMe): boolean {
  return me.reports_enabled === true && holdsReports(me);
}

/** Whether this user may switch report runs on in Business set-up. */
export function maySwitchReportsOn(me: Pick<Me, 'permissions'>): boolean {
  return held(me).includes(SETTINGS_MANAGE);
}
