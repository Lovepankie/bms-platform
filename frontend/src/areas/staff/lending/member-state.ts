import type { CreateMemberBody, DuplicateCandidate, KinBody, MemberRecord, UpdateMemberBody } from '../../../api/members';
import type { FieldMessages } from '../../../api/lending-forms';
import { parseMinor } from '../../../components/money';
import { words } from './loan-state';

// Pure rules of the member screens (issue #280, FR-MEM-01 to FR-MEM-10): the phone and national ID
// normalisation the server applies, the form's draft and its checks in plain words, and the bodies sent.
// Nothing here logs or stores a person's details.

// --- Phone (FR-MEM-02) ------------------------------------------------------------------------------

/**
 * The server's `PhoneNumbers.normaliseUganda`: white space and hyphens dropped, then `+256 7...`,
 * `2567...` (12 digits), `07...` (10 digits) or the bare nine digits `7...` become `+2567XXXXXXXX`.
 * Anything else is null. The shared cases are in member-state.test.ts and mirror chapter 13 section 13.6.
 */
export function normaliseUganda(raw: string): string | null {
  let s = raw.trim().replace(/[\s-]/g, '');
  if (s.startsWith('+256')) s = s.slice(4);
  else if (s.startsWith('256') && s.length === 12) s = s.slice(3);
  else if (s.startsWith('0') && s.length === 10) s = s.slice(1);
  return /^7\d{8}$/.test(s) ? `+256${s}` : null;
}

/**
 * Words for a phone box, or null when the number is fine. Stricter than the server in one way: a bare nine
 * digits ("712345678") is refused here because it cannot be told from a number missing its first digit; the
 * server would accept it. A person who is sure types the 0 or the +256 in front.
 */
export function phoneProblem(raw: string): string | null {
  const text = raw.trim();
  if (text === '') return null;
  const bare = text.replace(/[\s-]/g, '');
  if (/^7\d{8}$/.test(bare)) return 'Start the number with 0 or +256, for example 0712 345 678.';
  return normaliseUganda(text) === null ? 'Use a Uganda mobile number: 07... or +256 7... (ten digits starting 07).' : null;
}

/** The number written to read: +256 712 345 678. */
export function showPhone(e164: string | undefined): string {
  const m = /^\+256(\d{3})(\d{3})(\d{3})$/.exec(e164 ?? '');
  return m ? `+256 ${m[1]} ${m[2]} ${m[3]}` : (e164 ?? '');
}

// --- National ID (FR-MEM-03) ---------------------------------------------------------------------

/** The server's `NationalIds.normaliseNin`: upper case, no spaces, `CM` or `CF` and twelve letters or digits. */
export function normaliseNin(raw: string): string | null {
  const s = raw.replace(/\s/g, '').toUpperCase();
  return /^C[MF][A-Z0-9]{12}$/.test(s) ? s : null;
}

// --- The member form ----------------------------------------------------------------------------

export const ID_TYPES = [
  { value: 'nin', label: 'National ID card' },
  { value: 'passport', label: 'Passport' },
  { value: 'refugee_id', label: 'Refugee ID' },
  { value: 'other', label: 'Another ID' },
  { value: 'none', label: 'No ID yet' },
] as const;

export const GENDERS = [
  { value: '', label: 'Not said' },
  { value: 'female', label: 'Female' },
  { value: 'male', label: 'Male' },
  { value: 'other', label: 'Other' },
] as const;

export const MARITAL = [
  { value: '', label: 'Not said' },
  { value: 'single', label: 'Single' },
  { value: 'married', label: 'Married' },
  { value: 'divorced', label: 'Divorced' },
  { value: 'widowed', label: 'Widowed' },
  { value: 'separated', label: 'Separated' },
] as const;

export interface MemberDraft {
  branchId: string;
  fullName: string;
  phone: string;
  altPhone: string;
  idType: string;
  nationalId: string;
  otherIdNumber: string;
  dateOfBirth: string;
  gender: string;
  maritalStatus: string;
  district: string;
  village: string;
  location: string;
  occupation: string;
  monthlyIncome: string;
  /** Ticked when a member with the same phone is a different person (FR-MEM-04). */
  confirmedNotDuplicate: boolean;
}

export const blankMember = (branchId = ''): MemberDraft => ({
  branchId, fullName: '', phone: '', altPhone: '', idType: 'nin', nationalId: '', otherIdNumber: '', dateOfBirth: '', gender: '', maritalStatus: '',
  district: '', village: '', location: '', occupation: '', monthlyIncome: '', confirmedNotDuplicate: false,
});

/** A draft to edit, from the member as the server holds it. */
export function draftOf(m: MemberRecord): MemberDraft {
  return {
    branchId: m.branch_id ?? '',
    fullName: m.full_name ?? '',
    phone: m.phone_e164 ?? '',
    altPhone: m.alt_phone_e164 ?? '',
    idType: m.id_type ?? 'none',
    nationalId: m.national_id ?? '',
    otherIdNumber: m.other_id_number ?? '',
    dateOfBirth: m.date_of_birth ?? '',
    gender: m.gender === 'unspecified' ? '' : (m.gender ?? ''),
    maritalStatus: m.marital_status === 'unknown' ? '' : (m.marital_status ?? ''),
    district: m.district ?? '',
    village: m.village ?? '',
    location: m.location ?? '',
    occupation: m.occupation ?? '',
    monthlyIncome: m.monthly_income_minor != null ? String(m.monthly_income_minor) : '',
    confirmedNotDuplicate: false,
  };
}

const iso = /^\d{4}-\d{2}-\d{2}$/;

/** A calendar date that exists (not 30 February). */
function realDate(text: string): boolean {
  if (!iso.test(text)) return false;
  const [y, m, d] = text.split('-').map(Number) as [number, number, number];
  const day = new Date(Date.UTC(y, m - 1, d));
  return day.getUTCFullYear() === y && day.getUTCMonth() === m - 1 && day.getUTCDate() === d;
}

/**
 * What is wrong with the draft, per field and in plain words; empty when it can be sent. `today` is the local
 * calendar date (YYYY-MM-DD), passed in so a test needs no clock. `editing` relaxes nothing but the branch.
 */
export function memberProblems(d: MemberDraft, today: string, editing = false, original?: MemberDraft): FieldMessages {
  const out: FieldMessages = {};
  if (!editing && d.branchId === '') out.branch_id = 'Choose the branch the member belongs to.';
  if (d.fullName.trim() === '') out.full_name = 'Type the member\'s full name.';
  if (d.phone.trim() === '') out.phone = 'Type a phone number the member can be reached on.';
  else {
    const problem = phoneProblem(d.phone);
    if (problem) out.phone = problem;
  }
  if (d.altPhone.trim() !== '') {
    const problem = phoneProblem(d.altPhone);
    if (problem) out.alt_phone = problem;
  }
  if (d.idType === 'nin') {
    if (d.nationalId.trim() === '') out.national_id = 'Type the national ID number from the card.';
    else if (normaliseNin(d.nationalId) === null) out.national_id = 'A national ID number starts with CM or CF and has 14 letters and digits in all.';
  } else if (d.idType !== 'none' && d.otherIdNumber.trim() === '') out.other_id_number = 'Type the number on the ID.';
  if (d.dateOfBirth !== '') {
    if (!realDate(d.dateOfBirth)) out.date_of_birth = 'Enter the date as day, month and year.';
    else if (d.dateOfBirth >= today) out.date_of_birth = 'The date of birth must be before today.';
    else if (d.dateOfBirth < '1900-01-01') out.date_of_birth = 'Check the year.';
  }
  if (d.monthlyIncome.trim() !== '' && parseMinor(d.monthlyIncome, 'UGX') === null) out.monthly_income_minor = 'Type the amount in shillings, with no letters or decimals.';
  // The server keeps what it has when a field is left out, so a saved date of birth or income cannot be emptied: say so, not nothing.
  if (original && original.dateOfBirth !== '' && d.dateOfBirth === '') out.date_of_birth = 'A saved date of birth cannot be removed. Correct it instead.';
  if (original && original.monthlyIncome !== '' && d.monthlyIncome.trim() === '') out.monthly_income_minor = 'A saved income cannot be removed. Type 0 if the member has no regular income.';
  return out;
}

/**
 * What goes over the wire for a phone or a national ID: the normalised form, not the typed text. JavaScript's trim strips characters
 * (a no-break space from a pasted number) that Java's does not, so sending typed text could pass the form and fail on the server.
 */
export const sendPhone = (typed: string): string => normaliseUganda(typed) ?? typed.trim();
export const sendNin = (typed: string): string => normaliseNin(typed) ?? typed.trim();

const blank = (s: string): string | undefined => (s.trim() === '' ? undefined : s.trim());

/** The registration request. Call only when `memberProblems` is empty. */
export function createBody(d: MemberDraft): CreateMemberBody {
  const income = d.monthlyIncome.trim() === '' ? undefined : (parseMinor(d.monthlyIncome, 'UGX') ?? undefined);
  return {
    branch_id: d.branchId,
    full_name: d.fullName.trim(),
    phone: sendPhone(d.phone),
    alt_phone: d.altPhone.trim() === '' ? undefined : sendPhone(d.altPhone),
    id_type: d.idType,
    national_id: d.idType === 'nin' ? sendNin(d.nationalId) : undefined,
    other_id_number: d.idType !== 'nin' && d.idType !== 'none' ? d.otherIdNumber.trim() : undefined,
    date_of_birth: blank(d.dateOfBirth),
    gender: blank(d.gender),
    marital_status: blank(d.maritalStatus),
    district: blank(d.district),
    village: blank(d.village),
    location: blank(d.location),
    occupation: blank(d.occupation),
    monthly_income_minor: income,
    confirmed_not_duplicate: d.confirmedNotDuplicate ? true : undefined,
  };
}

/**
 * The edit request: only what differs from the member as loaded, so a stale untouched field never overwrites
 * someone else's change. A cleared optional text is sent empty so the server clears it. An unchanged phone is
 * not sent (and so is not checked for a duplicate again).
 */
export function updateBody(before: MemberRecord, d: MemberDraft): UpdateMemberBody {
  const was = draftOf(before);
  const body: UpdateMemberBody = {};
  const text = <K extends keyof MemberDraft>(key: K, field: keyof UpdateMemberBody, send: string = d[key] as string) => {
    if (d[key] !== was[key]) (body as Record<string, unknown>)[field] = send.trim();
  };
  text('fullName', 'full_name');
  if (d.phone !== was.phone) body.phone = sendPhone(d.phone);
  if (d.altPhone !== was.altPhone) body.alt_phone = d.altPhone.trim() === '' ? '' : sendPhone(d.altPhone);
  if (d.idType !== was.idType) body.id_type = d.idType;
  if (d.idType === 'nin' && (d.nationalId !== was.nationalId || d.idType !== was.idType)) body.national_id = sendNin(d.nationalId);
  if (d.idType !== 'nin' && d.idType !== 'none' && (d.otherIdNumber !== was.otherIdNumber || d.idType !== was.idType)) body.other_id_number = d.otherIdNumber.trim();
  if (d.dateOfBirth !== was.dateOfBirth && d.dateOfBirth !== '') body.date_of_birth = d.dateOfBirth;
  // The server has no empty choice: "not said" is its unspecified and unknown.
  if (d.gender !== was.gender) body.gender = d.gender === '' ? 'unspecified' : d.gender;
  if (d.maritalStatus !== was.maritalStatus) body.marital_status = d.maritalStatus === '' ? 'unknown' : d.maritalStatus;
  text('district', 'district');
  text('village', 'village');
  text('location', 'location');
  text('occupation', 'occupation');
  if (d.monthlyIncome !== was.monthlyIncome && d.monthlyIncome.trim() !== '') body.monthly_income_minor = parseMinor(d.monthlyIncome, 'UGX') ?? undefined;
  if (d.confirmedNotDuplicate) body.confirmed_not_duplicate = true;
  return body;
}

export const isEmpty = (body: object): boolean => Object.keys(body).filter((k) => k !== 'confirmed_not_duplicate').length === 0;

// --- Likely duplicates (FR-MEM-04) ----------------------------------------------------------------

/** Why a likely duplicate matched, in words. */
export function reasonWords(reasons: readonly string[] | undefined): string {
  const w = (reasons ?? []).map((r) => (r === 'nin' ? 'the same national ID' : r === 'phone' ? 'the same phone number' : 'a similar name'));
  return w.length === 0 ? 'a likely match' : w.join(', ');
}

/** The ID is already registered: the member cannot be saved, whoever the candidate is. */
export const sameNin = (c: DuplicateCandidate): boolean => (c.match_reasons ?? []).includes('nin');
export const samePhone = (c: DuplicateCandidate): boolean => (c.match_reasons ?? []).includes('phone');

/**
 * Whether saving is held back by what the check found: a national ID already registered always stops it; a phone
 * already on another member stops it until the person ticks that they are different people.
 */
export function duplicateHold(candidates: readonly DuplicateCandidate[], confirmed: boolean): 'nin' | 'phone' | null {
  if (candidates.some(sameNin)) return 'nin';
  if (candidates.some(samePhone) && !confirmed) return 'phone';
  return null;
}

// --- Next of kin (FR-MEM-06) ------------------------------------------------------------------------

export const RELATIONSHIPS = [
  { value: 'spouse', label: 'Husband or wife' },
  { value: 'parent', label: 'Parent' },
  { value: 'child', label: 'Child' },
  { value: 'sibling', label: 'Brother or sister' },
  { value: 'relative', label: 'Other relative' },
  { value: 'friend', label: 'Friend' },
  { value: 'employer', label: 'Employer' },
  { value: 'other', label: 'Other' },
] as const;

export const relationshipWords = (value: string | undefined): string => RELATIONSHIPS.find((r) => r.value === value)?.label ?? words(value);

export interface KinDraft {
  fullName: string;
  phone: string;
  relationship: string;
  location: string;
  isPrimary: boolean;
}

export const blankKin = (): KinDraft => ({ fullName: '', phone: '', relationship: 'spouse', location: '', isPrimary: false });

export function kinProblems(d: KinDraft): FieldMessages {
  const out: FieldMessages = {};
  if (d.fullName.trim() === '') out.full_name = 'Type the next of kin\'s full name.';
  if (d.phone.trim() === '') out.phone = 'Type a phone number the next of kin can be reached on.';
  else {
    const problem = phoneProblem(d.phone);
    if (problem) out.phone = problem;
  }
  return out;
}

export function kinBody(d: KinDraft): KinBody {
  return { full_name: d.fullName.trim(), phone: sendPhone(d.phone), relationship: d.relationship, location: blank(d.location), is_primary: d.isPrimary };
}

// --- Status in words --------------------------------------------------------------------------------

const KYC_WORDS: Record<string, string> = {
  incomplete: 'Details missing',
  pending_verification: 'Waiting to be checked',
  verified: 'Checked',
  rejected: 'Not accepted',
};

export const kycWords = (status: string | undefined): string => (status ? (KYC_WORDS[status] ?? words(status)) : '');

export function kycBadge(status: string | undefined): string {
  switch (status) {
    case 'verified': return 'badge badge-success';
    case 'pending_verification': return 'badge badge-warning';
    case 'rejected': return 'badge badge-danger';
    default: return 'badge';
  }
}

/** What a member is missing before their details can be checked (FR-MEM-05), in words, from what the screen already holds. */
export function kycMissing(m: Pick<MemberRecord, 'id_type' | 'national_id' | 'other_id_number' | 'district' | 'village' | 'location' | 'sub_county'>, kinCount: number, hasIdFront: boolean): string[] {
  const out: string[] = [];
  const hasId = m.id_type === 'nin' ? !!m.national_id : m.id_type !== 'none' && !!m.other_id_number;
  if (!hasId) out.push('the ID number');
  if (!(m.district || m.village || m.location || m.sub_county)) out.push('where the member lives');
  if (kinCount === 0) out.push('a next of kin');
  if (!hasIdFront) out.push('a photo of the front of the ID');
  return out;
}
