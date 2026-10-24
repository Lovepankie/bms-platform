import type { CapabilityChange, RetailCapabilities } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';

// The retail capabilities and the shop or trade profile (ADR-033, docs/specs/trade-capabilities.md
// section 1). A profile is a preset that writes a bundle of the flags; the flags stay individually
// editable and the module reads the flags, never the profile. The server is the authority: it applies
// the preset and validates the group. This file mirrors the preset only to show the administrator what
// a choice will do before it is saved, and the screen shows what the server answered after.

export type CapabilityKey = 'branch_roles' | 'units' | 'price_lists' | 'customer_accounts' | 'documents' | 'purchasing' | 'transfer_receipt' | 'returns';
export type Profile = 'shop' | 'trade';

export interface CapabilityInfo {
  key: CapabilityKey;
  label: string;
  /** One plain sentence for the administrator: what the switch adds. */
  hint: string;
  /**
   * True once the screens of the capability exist. Each later slice of the trade build plan (section 17)
   * flips its flag here, adds its screens to the menu under this key, and adds its tour to tours.ts;
   * a test fails if a ready capability has no tour.
   */
  ready: boolean;
}

export const CAPABILITIES: readonly CapabilityInfo[] = [
  { key: 'branch_roles', label: 'Branch roles', hint: 'Mark each branch as a store, a shop or both. Only shops sell to customers.', ready: true },
  { key: 'units', label: WORDS.packs, hint: 'Sell and buy in packs, like a box or a roll, as well as single pieces.', ready: true },
  { key: 'price_lists', label: 'Price lists', hint: 'Different prices for different customers, with discounts for larger quantities.', ready: true },
  { key: 'customer_accounts', label: 'Customer accounts', hint: 'Credit limits, payment terms, ageing and a statement for each customer.', ready: true },
  { key: 'documents', label: 'Invoices and quotations', hint: 'Quotations, invoices and delivery notes, numbered for each branch. The till receipt stays the default.', ready: true },
  { key: 'purchasing', label: 'Purchase orders', hint: 'Purchase orders, goods received, supplier invoices, payments, landed costs such as freight and duty, and supplier credit notes. Buy stock stays as it is.', ready: true },
  { key: 'transfer_receipt', label: 'Receive transfers', hint: 'A stock move is sent first and received after, so stock can be on the way.', ready: true },
  { key: 'returns', label: 'Customer returns', hint: 'Take goods back from a customer and give a credit note.', ready: true },
];

export const KEYS: readonly CapabilityKey[] = CAPABILITIES.map((c) => c.key);

/** What choosing the trade profile switches on; documents and purchasing are never assumed (spec 1.2). */
export const TRADE_PRESET: readonly CapabilityKey[] = ['branch_roles', 'units', 'price_lists', 'customer_accounts', 'returns', 'transfer_receipt'];

export const labelOf = (key: CapabilityKey): string => CAPABILITIES.find((c) => c.key === key)?.label ?? key;

/** The flags as the screen holds them: every key present, absent meaning off. */
export type Flags = Record<CapabilityKey, boolean>;

export interface Draft {
  profile: Profile;
  flags: Flags;
}

export function draftOf(saved: RetailCapabilities | undefined): Draft {
  const flags = Object.fromEntries(KEYS.map((k) => [k, saved?.[k] === true])) as Flags;
  return { profile: saved?.profile === 'trade' ? 'trade' : 'shop', flags };
}

/** The draft after choosing a profile: its preset is written over the flags, the others are left as they are. */
export function chooseProfile(draft: Draft, profile: Profile): Draft {
  const flags = { ...draft.flags };
  for (const k of KEYS) {
    if (profile === 'shop') flags[k] = false;
    else if (TRADE_PRESET.includes(k)) flags[k] = true;
  }
  return { profile, flags };
}

export function toggle(draft: Draft, key: CapabilityKey, on: boolean): Draft {
  return { ...draft, flags: { ...draft.flags, [key]: on } };
}

export interface Change {
  profile?: Profile;
  on: CapabilityKey[];
  off: CapabilityKey[];
}

/** What saving the draft would change against what is saved, for the confirmation. */
export function changeOf(saved: Draft, draft: Draft): Change {
  return {
    profile: draft.profile !== saved.profile ? draft.profile : undefined,
    on: KEYS.filter((k) => draft.flags[k] && !saved.flags[k]),
    off: KEYS.filter((k) => !draft.flags[k] && saved.flags[k]),
  };
}

export const isChange = (c: Change): boolean => c.profile !== undefined || c.on.length > 0 || c.off.length > 0;

/** The request for a change: the profile when it changed, and every flag that changed (the server lets the flags win over the preset). */
export function requestOf(c: Change): CapabilityChange {
  const body: CapabilityChange = {};
  if (c.profile) body.profile = c.profile;
  for (const k of c.on) body[k] = true;
  for (const k of c.off) body[k] = false;
  return body;
}

/** Plain words for the confirmation, one sentence per kind of change. */
export function sentencesOf(c: Change): string[] {
  const names = (keys: CapabilityKey[]) => keys.map(labelOf).join(', ');
  const out: string[] = [];
  if (c.profile === 'trade') out.push('The trade profile switches on a set of features for a business that sells in bulk. You can still turn each one off.');
  if (c.profile === 'shop') out.push('The shop profile switches every feature off. Nothing you recorded is deleted.');
  if (c.on.length > 0) out.push(`Switched on: ${names(c.on)}.`);
  if (c.off.length > 0) out.push(`Switched off: ${names(c.off)}. What you recorded with them is kept.`);
  return out;
}
