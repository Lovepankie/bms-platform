import type { CollateralItem, CreateCollateralBody, EventBody, UpdateCollateralBody, ValuationBody } from '../../../api/collateral';
import type { FieldMessages } from '../../../api/lending-forms';
import { parseMinor } from '../../../components/money';
import { amountRule, amountText, currencyOf, words } from './loan-state';

// Pure rules of the collateral screens (issue #280, FR-COL-01 to FR-COL-05): plain words for the kinds and states, the
// form's draft and its checks, and the bodies sent. Amounts are typed by a person in the item's currency (a new item's is the
// business's, issue #289) and sent as integer minor units (ADR-004).

export const TYPES = [
  { value: 'land_title', label: 'Land title' },
  { value: 'vehicle_logbook', label: 'Vehicle logbook' },
  { value: 'vehicle', label: 'Vehicle' },
  { value: 'household_item', label: 'Household item' },
  { value: 'national_id', label: 'National ID card held as security' },
  { value: 'other', label: 'Other' },
] as const;

export const typeWords = (value: string | undefined): string => TYPES.find((t) => t.value === value)?.label ?? words(value);

/** What the reference number is called for each kind. */
export function referenceLabel(type: string): string {
  switch (type) {
    case 'land_title': return 'Title number';
    case 'vehicle_logbook': return 'Logbook number';
    case 'vehicle': return 'Plate number';
    case 'national_id': return 'ID number';
    default: return 'Reference number (if any)';
  }
}

export const OWNERS = [
  { value: 'self', label: 'The member' },
  { value: 'spouse', label: 'The member\'s husband or wife' },
  { value: 'other', label: 'Someone else' },
] as const;

export const ownerWords = (value: string | undefined): string => OWNERS.find((o) => o.value === value)?.label ?? words(value);

export const CUSTODY = [
  { value: 'pledged', label: 'The owner keeps it' },
  { value: 'in_custody', label: 'We hold it' },
] as const;

const CUSTODY_WORDS: Record<string, string> = {
  pledged: 'The owner keeps it',
  in_custody: 'We hold it',
  released: 'Given back',
  seized: 'Taken over',
  disposed: 'Sold or disposed of',
};

export const custodyWords = (status: string | undefined): string => (status ? (CUSTODY_WORDS[status] ?? words(status)) : '');

export function custodyBadge(status: string | undefined): string {
  switch (status) {
    case 'in_custody': return 'badge badge-info';
    case 'released': return 'badge badge-success';
    case 'seized': return 'badge badge-warning';
    case 'disposed': return 'badge badge-danger';
    default: return 'badge';
  }
}

export interface CollateralDraft {
  memberId: string;
  type: string;
  description: string;
  referenceNo: string;
  ownerName: string;
  ownerRelationship: string;
  value: string;
  custody: string;
  storageLocation: string;
}

export const blankCollateral = (memberId = ''): CollateralDraft => ({
  memberId, type: 'land_title', description: '', referenceNo: '', ownerName: '', ownerRelationship: 'self', value: '', custody: 'pledged', storageLocation: '',
});

export function draftOf(c: CollateralItem): CollateralDraft {
  return {
    memberId: c.member_id ?? '',
    type: c.collateral_type ?? 'other',
    description: c.description ?? '',
    referenceNo: c.reference_no ?? '',
    ownerName: c.owner_name ?? '',
    ownerRelationship: c.owner_relationship ?? 'self',
    value: c.estimated_value_minor != null ? amountText(c.estimated_value_minor, c.currency) : '',
    custody: c.custody_status ?? 'pledged',
    storageLocation: c.storage_location ?? '',
  };
}

/** Per field, in plain words; empty when the draft can be sent. */
export function collateralProblems(d: CollateralDraft, editing = false, original?: CollateralDraft, currency = 'UGX'): FieldMessages {
  const out: FieldMessages = {};
  if (!editing && d.memberId === '') out.member_id = 'Choose the member this collateral belongs to.';
  if (d.description.trim() === '') out.description = 'Describe the item, for example "Plot of land at Test Village, 50 by 100 feet".';
  if (d.type === 'vehicle' && d.referenceNo.trim() === '') out.reference_no = 'A vehicle needs its plate number.';
  if (d.ownerRelationship === 'other' && d.ownerName.trim() === '') out.owner_name = 'Type the name of the person who owns it.';
  if (d.value.trim() !== '' && parseMinor(d.value, currency) === null) out.estimated_value_minor = `Type the value ${amountRule(currency)}.`;
  // An omitted value keeps the saved one, so a saved value cannot be emptied from this form: say so, not nothing.
  if (editing && original && original.value !== '' && d.value.trim() === '') out.estimated_value_minor = 'A saved value cannot be removed. Type the new value instead.';
  if (!editing && d.custody === 'in_custody' && d.storageLocation.trim() === '') out.storage_location = 'Say where the item is kept.';
  return out;
}

const blank = (s: string): string | undefined => (s.trim() === '' ? undefined : s.trim());

export function createBody(d: CollateralDraft, currency = 'UGX'): CreateCollateralBody {
  return {
    member_id: d.memberId,
    collateral_type: d.type,
    description: d.description.trim(),
    reference_no: blank(d.referenceNo),
    owner_name: d.ownerRelationship === 'self' ? undefined : blank(d.ownerName),
    owner_relationship: d.ownerRelationship,
    estimated_value_minor: d.value.trim() === '' ? undefined : (parseMinor(d.value, currency) ?? undefined),
    custody_status: d.custody,
    storage_location: d.custody === 'in_custody' ? blank(d.storageLocation) : undefined,
  };
}

/** Only what changed, so an untouched field never overwrites someone else's change. */
export function updateBody(before: CollateralItem, d: CollateralDraft): UpdateCollateralBody {
  const was = draftOf(before);
  const body: UpdateCollateralBody = {};
  if (d.description !== was.description) body.description = d.description.trim();
  if (d.referenceNo !== was.referenceNo) body.reference_no = d.referenceNo.trim();
  if (d.ownerName !== was.ownerName) body.owner_name = d.ownerName.trim();
  if (d.ownerRelationship !== was.ownerRelationship) body.owner_relationship = d.ownerRelationship;
  if (d.value !== was.value && d.value.trim() !== '') body.estimated_value_minor = parseMinor(d.value, currencyOf(before.currency)) ?? undefined;
  return body;
}

// --- Valuations (FR-COL-02) ---------------------------------------------------------------------------

export interface ValuationDraft {
  valuedOn: string;
  valuer: string;
  market: string;
  forcedSale: string;
  note: string;
}

export const blankValuation = (today: string): ValuationDraft => ({ valuedOn: today, valuer: '', market: '', forcedSale: '', note: '' });

export function valuationProblems(d: ValuationDraft, today: string, currency = 'UGX'): FieldMessages {
  const out: FieldMessages = {};
  const market = d.market.trim() === '' ? null : parseMinor(d.market, currency);
  const forced = d.forcedSale.trim() === '' ? undefined : parseMinor(d.forcedSale, currency);
  if (!market || market <= 0) out.market_value_minor = `Type what the item would sell for in the market, ${amountRule(currency)}.`;
  if (forced === null || forced === 0) out.forced_sale_value_minor = `Type a value ${amountRule(currency)}, or leave this empty.`;
  else if (forced !== undefined && market && forced > market) out.forced_sale_value_minor = 'A quick sale cannot fetch more than the market value.';
  if (d.valuedOn === '' || d.valuedOn > today) out.valued_on = 'The valuation date cannot be in the future.';
  return out;
}

export function valuationBody(d: ValuationDraft, currency = 'UGX'): ValuationBody {
  return {
    valued_on: d.valuedOn,
    valuer_name: blank(d.valuer),
    market_value_minor: parseMinor(d.market, currency) ?? 0,
    forced_sale_value_minor: d.forcedSale.trim() === '' ? undefined : (parseMinor(d.forcedSale, currency) ?? undefined),
    note: blank(d.note),
  };
}

// --- Custody changes (FR-COL-03) ----------------------------------------------------------------------

export type EventType = 'received_into_custody' | 'moved' | 'seized' | 'disposed' | 'note';

export const EVENT_WORDS: Record<EventType, string> = {
  received_into_custody: 'We now hold it',
  moved: 'Moved to another place',
  seized: 'Taken over by the business',
  disposed: 'Sold or disposed of',
  note: 'Add a note',
};

/** The custody changes the server accepts from a state (the release has its own request). */
export function eventsFrom(custody: string | undefined): EventType[] {
  switch (custody) {
    case 'pledged': return ['received_into_custody', 'seized', 'note'];
    case 'in_custody': return ['moved', 'seized', 'note'];
    case 'seized': return ['disposed', 'note'];
    case 'released':
    case 'disposed':
      return ['note'];
    default: return ['note'];
  }
}

/** The release can be asked for while the item is held by the owner or by us. */
export const canRequestRelease = (custody: string | undefined): boolean => custody === 'pledged' || custody === 'in_custody';

export function eventProblems(type: EventType, location: string, note: string): FieldMessages {
  const out: FieldMessages = {};
  if ((type === 'received_into_custody' || type === 'moved') && location.trim() === '') out.location = 'Say where the item is kept now.';
  if (type === 'note' && note.trim() === '') out.note = 'Type the note.';
  return out;
}

export function eventBody(type: EventType, location: string, counterparty: string, note: string): EventBody {
  return { event_type: type, location: blank(location), counterparty_name: blank(counterparty), note: blank(note) };
}
