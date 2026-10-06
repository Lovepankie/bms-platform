import type { ActivateRequest, ApplicationDetail, SignUpRequest } from '../../api/onboarding';

// Form state and checks for the sign-up page and the Activate form (spec sections 4 and 10). The
// server checks everything again; these give plain-words feedback before a request is sent.

export const COUNTRIES = [
  { code: 'UG', name: 'Uganda' },
  { code: 'KE', name: 'Kenya' },
  { code: 'TZ', name: 'Tanzania' },
  { code: 'RW', name: 'Rwanda' },
] as const;

/** The module keys the API knows (the vertical manifests), with plain descriptions. */
export const MODULES = [
  { key: 'retail', label: 'Shop: sales, stock and restocking' },
  { key: 'lending', label: 'Lending: members, loans and savings' },
] as const;

export const TERMS = [
  { key: 'monthly', label: 'Monthly' },
  { key: 'annual', label: 'Yearly' },
] as const;

export const WAYS_IN = [
  { key: 'trial', label: 'Try one month free' },
  { key: 'paid', label: 'Subscribe now' },
] as const;

export interface SignUpForm {
  business_name: string;
  contact_name: string;
  contact_email: string;
  contact_phone: string;
  country: string;
  modules: string[];
  term: string;
  way_in: string;
  agent_code: string;
  message: string;
  /** The hidden field: people never see it, so it stays empty. */
  website: string;
}

export const EMPTY_SIGN_UP: SignUpForm = {
  business_name: '',
  contact_name: '',
  contact_email: '',
  contact_phone: '',
  country: 'UG',
  modules: [],
  term: 'monthly',
  way_in: 'trial',
  agent_code: '',
  message: '',
  website: '',
};

export type Problems = Partial<Record<string, string>>;

/** ASCII only, as the API: an address that needs SMTPUTF8 could never be sent to. */
const EMAIL = /^[\x21-\x3F\x41-\x7E]+@[\x21-\x3F\x41-\x7E]+\.[\x21-\x3F\x41-\x7E]+$/;
const PHONE = /^\+?[0-9 ()-]{9,20}$/;
const AGENT = /^[A-Za-z0-9-]*$/;

export function signUpProblems(form: SignUpForm): Problems {
  const problems: Problems = {};
  if (form.business_name.trim().length < 2) problems.business_name = 'Enter the name of the business.';
  if (form.business_name.length > 200) problems.business_name = 'Use at most 200 characters.';
  if (form.contact_name.trim().length < 2) problems.contact_name = 'Enter your name.';
  if (form.contact_name.length > 200) problems.contact_name = 'Use at most 200 characters.';
  if (!EMAIL.test(form.contact_email.trim()) || form.contact_email.length > 254) {
    problems.contact_email = 'Enter an email address in plain letters, for example name@example.com.';
  }
  if (!PHONE.test(form.contact_phone.trim())) problems.contact_phone = 'Enter your phone number, for example 0700 000 000.';
  if (form.modules.length === 0) problems.modules = 'Choose at least one.';
  if (!AGENT.test(form.agent_code) || form.agent_code.length > 40) {
    problems.agent_code = 'Letters, digits and hyphens only.';
  }
  if (form.message.length > 1000) problems.message = 'Use at most 1000 characters.';
  return problems;
}

export function toSignUpRequest(form: SignUpForm): SignUpRequest {
  return {
    business_name: form.business_name.trim(),
    contact_name: form.contact_name.trim(),
    contact_email: form.contact_email.trim(),
    contact_phone: form.contact_phone.trim(),
    country: form.country,
    modules: form.modules,
    term: form.term,
    way_in: form.way_in,
    agent_code: form.agent_code.trim() || undefined,
    message: form.message.trim() || undefined,
    website: form.website || undefined,
  };
}

/** The token of an emailed link: in the fragment (#token=...), which never reaches a server log. */
export function readLinkToken(hash: string): string | null {
  const match = /(?:^#|&)token=([A-Za-z0-9_-]{43})(?:&|$)/.exec(hash);
  return match?.[1] ?? null;
}

/** Where the applicant page keeps the link token for this tab only. */
export const APPLICANT_TOKEN_KEY = 'bms.applicant-link';

export function saveApplicantToken(token: string): void {
  try {
    sessionStorage.setItem(APPLICANT_TOKEN_KEY, token);
  } catch {
    // Storage can be blocked; the page then works until it is reloaded.
  }
}

export function loadApplicantToken(): string | null {
  try {
    return sessionStorage.getItem(APPLICANT_TOKEN_KEY);
  } catch {
    return null;
  }
}

// ---- Activate --------------------------------------------------------------------------------

export interface ActivateForm {
  modules: string[];
  term: string;
  way_in: string;
  payment_note: string;
  slug: string;
  plan_code: string;
}

export function activateFormFrom(detail: ApplicationDetail): ActivateForm {
  return {
    modules: [...(detail.application?.modules ?? [])],
    term: detail.application?.term ?? 'monthly',
    way_in: detail.application?.way_in ?? 'trial',
    payment_note: '',
    slug: detail.suggested_slug ?? '',
    plan_code: 'starter',
  };
}

const SLUG = /^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])$/;
const RESERVED = new Set(['www', 'api', 'app', 'admin', 'static', 'mail']);

export function activateProblems(form: ActivateForm): Problems {
  const problems: Problems = {};
  if (form.modules.length === 0) problems.modules = 'Choose at least one module.';
  if (!SLUG.test(form.slug) || RESERVED.has(form.slug)) {
    problems.slug = '3 to 63 lower case letters, digits or hyphens, not starting or ending with a hyphen.';
  }
  if (form.way_in === 'paid' && form.payment_note.trim().length === 0) {
    problems.payment_note = 'Note the payment received: method, reference and date.';
  }
  if (form.payment_note.length > 500) problems.payment_note = 'Use at most 500 characters.';
  return problems;
}

export function toActivateRequest(form: ActivateForm): ActivateRequest {
  return {
    modules: form.modules,
    term: form.term,
    way_in: form.way_in,
    payment_note: form.payment_note.trim() || undefined,
    slug: form.slug.trim(),
    plan_code: form.plan_code || undefined,
  };
}

export function toggle(list: string[], key: string, on: boolean): string[] {
  return on ? [...new Set([...list, key])] : list.filter((k) => k !== key);
}
