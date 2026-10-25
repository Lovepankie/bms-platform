import type { Business, Plan, PlanPrice, SavePlanRequest, SubscriptionStatus } from '../../api/operator';
import { formatMinor, parseMinor } from '../../components/money';
import { PROFILE_NAMES, profileName, type Profile } from '../staff/retail/capabilities-state';

// The rules and the plain words of the operator screens (issue #407), kept apart from the screens so they are tested without a
// browser: what each status means, what the subscription line says, what an action will do before the operator confirms it, the
// payment form and the price book forms. Dates are calendar days (YYYY-MM-DD) and are worked out in UTC, never in the device's own
// time zone, so a phone in another zone shows the same days as the server.

export const STATUSES: SubscriptionStatus[] = ['trial', 'active', 'past_due', 'suspended', 'cancelled'];

export const STATUS_LABEL: Record<SubscriptionStatus, string> = {
  trial: 'Free month',
  active: 'Paying',
  past_due: 'Payment late',
  suspended: 'Suspended',
  cancelled: 'Cancelled',
};

/** What the status means for the business, in one sentence. */
export const STATUS_MEANING: Record<SubscriptionStatus, string> = {
  trial: 'The business uses everything free for one month from its first sign-in.',
  active: 'The business has paid and works normally.',
  past_due: 'The paid period is over but the business still works normally while you follow up.',
  suspended: 'Read only: staff can sign in, look and export, but nothing can be changed. Nothing is deleted.',
  cancelled: 'Read only, like suspended, and not expected back. Nothing is deleted.',
};

export function statusLabel(status: string | undefined): string {
  return (status && STATUS_LABEL[status as SubscriptionStatus]) || status || '';
}

export function statusBadge(status: string | undefined): string {
  switch (status) {
    case 'active':
      return 'badge badge-success';
    case 'trial':
      return 'badge badge-info';
    case 'past_due':
      return 'badge badge-warning';
    case 'suspended':
    case 'cancelled':
      return 'badge badge-danger';
    default:
      return 'badge';
  }
}

export const MODULES: { key: string; label: string }[] = [
  { key: 'retail', label: 'Shops and stock' },
  { key: 'lending', label: 'Lending' },
];

export function moduleLabel(key: string): string {
  return MODULES.find((m) => m.key === key)?.label ?? key;
}

/**
 * The kind of business its administrator chose in Retail settings (issue #424, ADR-092), in the words the business reads; one that
 * never chose is Shops only, the default. Null for a business without the shop module, which has no kind.
 */
export function businessTypeLabel(b: { modules?: string[]; business_type?: string | null }): string | null {
  if (!(b.modules ?? []).includes('retail')) return null;
  // The server sends the kind already read through the retail group; a value it does not know is shown as it is, never guessed.
  if (!b.business_type) return `${profileName(null)} (not chosen yet)`;
  return PROFILE_NAMES[b.business_type as Profile] ?? b.business_type;
}

// ---- dates ------------------------------------------------------------------------------------------

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function parts(day: string): [number, number, number] {
  const [y, m, d] = day.slice(0, 10).split('-').map(Number);
  return [y ?? 1970, m ?? 1, d ?? 1];
}

function iso(y: number, m: number, d: number): string {
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.toISOString().slice(0, 10);
}

/** 21 Oct 2026. */
export function formatDay(day: string | null | undefined): string {
  if (!day) return '';
  const [y, m, d] = parts(day);
  return `${d} ${MONTHS[m - 1] ?? ''} ${y}`;
}

/** 21 Oct 2026, 14:05 in Kampala time. */
export function formatMoment(at: string | null | undefined): string {
  if (!at) return '';
  const local = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Africa/Kampala',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(at));
  return local.replace(',', '');
}

/** Today in Kampala, as YYYY-MM-DD. */
export function todayInKampala(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Kampala', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

export function addDays(day: string, days: number): string {
  const [y, m, d] = parts(day);
  return iso(y, m, d + days);
}

/** As PostgreSQL adds a month: the same day of the month, or the month's last day when it has no such day. */
export function addMonths(day: string, months: number): string {
  const [y, m, d] = parts(day);
  const target = new Date(Date.UTC(y, m - 1 + months, 1));
  const last = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate();
  return iso(target.getUTCFullYear(), target.getUTCMonth() + 1, Math.min(d, last));
}

export function daysBetween(from: string, to: string): number {
  const [a, b, c] = parts(from);
  const [x, y, z] = parts(to);
  return Math.round((Date.UTC(x, y - 1, z) - Date.UTC(a, b - 1, c)) / 86_400_000);
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** The one line under a business's name: where its subscription stands today. */
export function subscriptionLine(b: Business, today: string): string {
  const status = b.subscription_status as SubscriptionStatus | undefined;
  if (b.pilot && !b.paid_until) return 'Pilot: free, with no end date.';
  if (status === 'trial') {
    if (!b.trial_ends_on) return 'Free month: it starts when the owner first signs in.';
    const left = daysBetween(today, b.trial_ends_on);
    if (left < 0) return `Free month ended on ${formatDay(b.trial_ends_on)} and nothing is paid yet.`;
    return `Free month until ${formatDay(b.trial_ends_on)} (${plural(left + 1, 'day', 'days')} left).`;
  }
  if (b.paid_until) {
    const left = daysBetween(today, b.paid_until);
    if (left < 0) return `Paid until ${formatDay(b.paid_until)}, which has passed.`;
    return `Paid until ${formatDay(b.paid_until)} (${plural(left + 1, 'day', 'days')} left).`;
  }
  return status === 'active' ? 'Paying, with no payment recorded here yet.' : STATUS_MEANING[status ?? 'active'] ?? '';
}

// ---- plans and prices ---------------------------------------------------------------------------------

export function limitsLine(p: Pick<Plan, 'max_branches' | 'max_staff_users' | 'max_active_members' | 'allowed_modules'>): string {
  const out: string[] = [];
  const modules = p.allowed_modules ?? [];
  if (modules.includes('retail')) {
    out.push(p.max_branches == null ? 'unlimited shops and stores' : plural(p.max_branches, 'shop or store', 'shops or stores'));
  } else if (p.max_branches != null) {
    out.push(plural(p.max_branches, 'branch', 'branches'));
  }
  out.push(p.max_staff_users == null ? 'unlimited staff' : plural(p.max_staff_users, 'staff user', 'staff users'));
  if (modules.includes('lending')) {
    out.push(p.max_active_members == null ? 'unlimited members' : `up to ${p.max_active_members.toLocaleString('en-US')} members`);
  }
  const text = out.join(', ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function priceLine(price: PlanPrice | null | undefined): string {
  if (!price || price.monthly_minor == null || !price.currency) return 'No price entered yet';
  const month = formatMinor(price.monthly_minor, price.currency);
  const year = formatMinor(price.annual_minor ?? price.monthly_minor * (price.annual_months ?? 12), price.currency);
  return `${month} a month, ${year} a year (${plural(price.annual_months ?? 12, 'month', 'months')} charged)`;
}

export interface PlanForm {
  code: string;
  name: string;
  description: string;
  modules: string[];
  branches: string;
  staff: string;
  members: string;
  active: boolean;
  sort: string;
}

export const EMPTY_PLAN: PlanForm = {
  code: '',
  name: '',
  description: '',
  modules: ['retail'],
  branches: '',
  staff: '',
  members: '',
  active: true,
  sort: '0',
};

export function planFormFrom(p: Plan): PlanForm {
  const n = (v: number | null | undefined) => (v == null ? '' : String(v));
  return {
    code: p.code ?? '',
    name: p.name ?? '',
    description: p.description ?? '',
    modules: [...(p.allowed_modules ?? [])],
    branches: n(p.max_branches),
    staff: n(p.max_staff_users),
    members: n(p.max_active_members),
    active: p.active ?? true,
    sort: String(p.sort_order ?? 0),
  };
}

export type Problems = Partial<Record<string, string>>;

const LIMIT = /^\d{1,7}$/;

/** A blank limit is unlimited; anything else is a whole number from 0. */
function limit(text: string): number | null | 'bad' {
  const t = text.trim().replace(/,/g, '');
  if (t === '') return null;
  if (!LIMIT.test(t)) return 'bad';
  return Number(t);
}

export function planProblems(form: PlanForm, isNew: boolean): Problems {
  const out: Problems = {};
  if (isNew && !/^[a-z0-9_-]{1,40}$/.test(form.code.trim())) {
    out.code = 'Use 1 to 40 small letters, digits, hyphens or underscores, for example starter.';
  }
  if (!form.name.trim()) out.name = 'Give the plan a name the owner will recognise.';
  if (form.name.trim().length > 80) out.name = 'Keep the name to 80 characters.';
  if (form.description.length > 500) out.description = 'Keep the description to 500 characters.';
  if (form.modules.length === 0) out.modules = 'Choose at least one module.';
  for (const [key, text] of [
    ['branches', form.branches],
    ['staff', form.staff],
    ['members', form.members],
  ] as const) {
    if (limit(text) === 'bad') out[key] = 'A whole number from 0, or leave it empty for unlimited.';
  }
  if (!/^\d{1,4}$/.test(form.sort.trim()) || Number(form.sort) > 1000) out.sort = 'A number from 0 to 1000.';
  return out;
}

export function toSavePlan(form: PlanForm, reason: string): SavePlanRequest {
  const value = (t: string) => {
    const v = limit(t);
    return v === 'bad' ? null : v;
  };
  return {
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    allowed_modules: [...form.modules].sort(),
    max_branches: value(form.branches) ?? undefined,
    max_staff_users: value(form.staff) ?? undefined,
    max_active_members: form.modules.includes('lending') ? (value(form.members) ?? undefined) : undefined,
    active: form.active,
    sort_order: Number(form.sort.trim() || '0'),
    reason: reason.trim(),
  };
}

export interface PriceForm {
  monthly: string;
  annualMonths: string;
  from: string;
}

/** The cap on a monthly price and on a payment, in minor units (UGX 50 million), as the server. */
export const MAX_MINOR = 50_000_000;

export function priceProblems(form: PriceForm, today: string): Problems {
  const out: Problems = {};
  const monthly = parseMinor(form.monthly, 'UGX');
  if (monthly === null) out.monthly = 'Type the monthly price in shillings.';
  else if (monthly > MAX_MINOR) out.monthly = 'At most UGX 50,000,000 a month. Check the zeros.';
  const months = Number(form.annualMonths);
  if (!/^\d{1,2}$/.test(form.annualMonths.trim()) || months < 1 || months > 12) {
    out.annualMonths = 'The months charged for a year: a number from 1 to 12.';
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(form.from)) out.from = 'Choose the day the price starts.';
  else if (form.from < today) out.from = 'A new price starts today or later. It never changes what was charged before.';
  return out;
}

/** The sentence the operator confirms before a price is saved. */
export function priceSentence(planName: string, form: PriceForm, today: string): string {
  const monthly = parseMinor(form.monthly, 'UGX') ?? 0;
  const months = Number(form.annualMonths) || 12;
  const when = form.from === today ? 'from today' : `from ${formatDay(form.from)}`;
  return `${planName} will cost ${formatMinor(monthly, 'UGX')} a month and ${formatMinor(monthly * months, 'UGX')} a year, ${when}. Businesses already paid are not changed.`;
}

/**
 * A new monthly price more than a quarter away from the one in force (a slip of a zero) is shown again for a second look;
 * the server refuses it without the confirmation too (422 price_change_unusual). Undefined when the change is usual.
 */
export function largePriceChange(current: PlanPrice | null | undefined, monthly: number): string | undefined {
  if (!current || current.monthly_minor == null) return undefined;
  const now = current.monthly_minor;
  if (Math.abs(monthly - now) * 4 <= now) return undefined;
  return `The price in force is ${formatMinor(now, current.currency ?? 'UGX')} a month, and the new price is ${formatMinor(monthly, 'UGX')}. That is more than a quarter away. Check it, then confirm it.`;
}

// ---- payments -----------------------------------------------------------------------------------------

export const METHODS: { key: string; label: string }[] = [
  { key: 'mtn_momo', label: 'MTN Mobile Money' },
  { key: 'airtel_money', label: 'Airtel Money' },
  { key: 'bank_transfer', label: 'Bank transfer' },
  { key: 'other', label: 'Other' },
];

export function methodLabel(key: string | undefined): string {
  return METHODS.find((m) => m.key === key)?.label ?? key ?? '';
}

export interface PaymentForm {
  amount: string;
  method: string;
  reference: string;
  paidOn: string;
  months: string;
  note: string;
}

export function paymentFormFor(price: PlanPrice | null | undefined, today: string): PaymentForm {
  return { amount: expectedAmount(price, 1), method: 'mtn_momo', reference: '', paidOn: today, months: '1', note: '' };
}

/** What the plan's price asks for that many months: a year (12) at the annual price, otherwise the monthly price times the months. */
export function expectedAmount(price: PlanPrice | null | undefined, months: number): string {
  if (!price || price.monthly_minor == null) return '';
  const minor = months === 12 ? (price.annual_minor ?? price.monthly_minor * 12) : price.monthly_minor * months;
  return String(minor);
}

export function paymentProblems(form: PaymentForm, currency: string, today: string): Problems {
  const out: Problems = {};
  const amount = parseMinor(form.amount, currency);
  if (amount === null || amount <= 0) out.amount = 'Type the amount received, in shillings.';
  else if (amount > MAX_MINOR) out.amount = 'At most 50,000,000. Check the zeros.';
  const months = Number(form.months);
  if (!/^\d{1,2}$/.test(form.months.trim()) || months < 1 || months > 24) out.months = 'From 1 to 24 months.';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(form.paidOn)) out.paidOn = 'Choose the day the money came in.';
  else if (form.paidOn > today) out.paidOn = 'The day the money came in cannot be after today.';
  if (form.reference.length > 100) out.reference = 'Keep the reference to 100 characters.';
  if (form.note.length > 500) out.note = 'Keep the note to 500 characters.';
  return out;
}

/** The period a payment will cover, worked out as the server does: from the day after what is already covered, or today. */
export function coverFor(b: Business, months: number, today: string): { start: string; end: string; freeMonthEnds?: string } {
  const candidates = [today];
  if (b.paid_until) candidates.push(addDays(b.paid_until, 1));
  // A free month that has not started (nobody signed in, not a pilot) starts at the payment; the paid months follow it.
  const freeMonthEnds =
    b.subscription_status === 'trial' && !b.trial_ends_on && !b.pilot ? addDays(addMonths(today, 1), -1) : undefined;
  const trialEnd = b.subscription_status === 'trial' ? (b.trial_ends_on ?? freeMonthEnds) : undefined;
  if (trialEnd) candidates.push(addDays(trialEnd, 1));
  const start = candidates.sort().at(-1) ?? today;
  return { start, end: addDays(addMonths(start, months), -1), freeMonthEnds };
}

export function paymentSentence(b: Business, form: PaymentForm, today: string): string {
  const currency = b.currency ?? 'UGX';
  const amount = parseMinor(form.amount, currency) ?? 0;
  const months = Number(form.months) || 1;
  const cover = coverFor(b, months, today);
  const status = b.subscription_status;
  const after =
    status === 'suspended' || status === 'cancelled'
      ? ' It stays read only until you resume it.'
      : status === 'active'
        ? ''
        : ' It becomes Paying.';
  const free = cover.freeMonthEnds ? ` Its free month starts today and runs to ${formatDay(cover.freeMonthEnds)} first.` : '';
  return `Record ${formatMinor(amount, currency)} by ${methodLabel(form.method)} from ${b.name}, covering ${formatDay(cover.start)} to ${formatDay(cover.end)} (${plural(months, 'month', 'months')}).${free}${after}`;
}

// ---- the actions on a business ---------------------------------------------------------------------------

export type Action = 'suspend' | 'resume' | 'status' | 'plan' | 'trial' | 'paid' | 'modules' | 'pilot';

/** The sentence the operator reads before confirming an action; it says what will happen, in plain words. */
export function actionSentence(action: Action, b: Business, value?: string, planName?: string): string {
  const name = b.name ?? 'this business';
  switch (action) {
    case 'suspend':
      return `Suspend ${name}? Its staff can still sign in, look and export, but nothing can be changed until you resume it. Nothing is deleted.`;
    case 'resume':
      return `Resume ${name}? Its staff can work normally again and it goes back to ${statusLabel(b.suspended_from ?? 'active')}, as before the suspension.`;
    case 'pilot':
      return b.pilot
        ? `Stop treating ${name} as a pilot? Its free month starts at its next sign-in if it is still on a free month.`
        : `Mark ${name} as a pilot? It is free with no end date, and nothing will suspend it by itself. Its free month end is cleared.`;
    case 'status':
      return `Change ${name} from ${statusLabel(b.subscription_status)} to ${statusLabel(value)}? ${STATUS_MEANING[(value ?? 'active') as SubscriptionStatus] ?? ''}`;
    case 'plan':
      return `Move ${name} from ${b.plan_name ?? b.plan_code} to ${planName ?? value}? Its limits change at once. Nothing it already has is removed.`;
    case 'trial':
      return `Make the free month of ${name} end on ${formatDay(value)}?`;
    case 'paid':
      return `Make ${name} paid until ${formatDay(value)}? Use this only to correct a mistake; record a payment for money received.`;
    case 'modules':
      return `Change the modules of ${name} to ${value || 'none'}? A module switched off is hidden from its staff; its data is kept and comes back when it is switched on.`;
  }
}

/** What a confirm says when the operator's own code is missing or not 6 digits. */
export const CODE_PROBLEM = 'Type the 6 digit code from your own authenticator app.';

export function reasonProblem(reason: string): string | undefined {
  const t = reason.trim();
  if (t.length < 5) return 'Say why in a few words (at least 5 characters). It is kept with the change.';
  if (t.length > 300) return 'Keep the reason to 300 characters.';
  return undefined;
}

/** Plain words for the messages the outbox holds for a business (template keys of OutboxTemplates). */
const MESSAGE_LABEL: Record<string, string> = {
  'onboarding.activation': 'Activation link to the owner',
  'identity.password_reset': 'Password reset link',
  'identity.password_reset_issued': 'Notice that a reset link was made',
  'identity.password_changed': 'Notice that the password changed',
  'identity.find_sign_in': 'Their sign-in page',
  'identity.two_step_policy_weakened': 'Notice that two-step sign-in was relaxed',
  'payments.account_changed': 'Notice that the payment account changed',
  'insights.daily_digest': 'Daily summary',
};

export function messageLabel(key: string | undefined): string {
  return (key && MESSAGE_LABEL[key]) || key || '';
}

const HISTORY_LABEL: Record<string, string> = {
  'platform.tenant.created': 'Business created',
  'platform.tenant.subscription_changed': 'Status changed',
  'platform.tenant.plan_changed': 'Plan changed',
  'platform.tenant.subscription_dates_changed': 'Dates changed',
  'platform.tenant.payment_recorded': 'Payment recorded',
  'platform.tenant.modules_changed': 'Modules changed',
  'platform.tenant.trial_started': 'Free month started',
  'platform.tenant.pilot_changed': 'Pilot mark changed',
  'platform.tenant_admin.sign_in_reset': 'Owner sign-in reset link made',
  'platform.tenant_admin.mfa_reset': 'Owner two-step sign-in reset',
};

export function historyLabel(action: string | undefined): string {
  return (action && HISTORY_LABEL[action]) || action || '';
}
