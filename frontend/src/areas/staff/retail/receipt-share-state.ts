import type { SaleReceipt } from '../../../api/retail';
import { money } from './ui';

// Sharing a sale's receipt from the seller's own phone (issue #439, ADR-061). Nothing here talks to a server: the phone
// rules and the caption are pure, so they are tested on their own. The phone rules are ported from the company's other
// product (its receipt sender's number normaliser): a number that could mean two things is refused, never guessed.

export type PhoneCheck = { ok: true; digits: string } | { ok: false; problem: string };

const UGANDA = '256';
/** A landline or any other national number that is not a mobile: WhatsApp runs on a mobile number. */
const WHATSAPP_UGANDA = 'A WhatsApp number in Uganda starts with 07, like 0700 000 000. Check it and type it again.';

/**
 * A phone number as WhatsApp takes it: E.164 digits without the plus (`256` and 9 digits for Uganda), or the reason it is
 * refused. Spaces, dashes, brackets and dots are dropped; `00` is the international prefix; a national `07XXXXXXXX` and a
 * bare 9-digit number starting with 7 are Ugandan (any other bare 9 digits is refused); a doubled `2560...` collapses; a Ugandan number is exactly 12 digits; any other country
 * needs the `+` and 8 to 15 digits; no number starts with 0 once its prefix is read, and a Uganda number never has a 0 right after 256 (another country keeps what follows its code as typed).
 */
export function normalisePhone(typed: string): PhoneCheck {
  let s = typed.trim().replace(/[\s\-().]/g, '');
  if (s === '') return { ok: false, problem: "Type the customer's phone number." };
  let international = false;
  if (s.startsWith('+')) {
    international = true;
    s = s.slice(1);
  } else if (s.startsWith('00')) {
    international = true;
    s = s.slice(2);
  }
  if (!/^\d+$/.test(s)) return { ok: false, problem: 'A phone number has digits only, and a + only at the start.' };
  if (/^2560\d{9}$/.test(s)) s = UGANDA + s.slice(4);
  else if (!international && /^07\d{8}$/.test(s)) s = UGANDA + s.slice(1);
  else if (!international && /^0[1-689]\d{8}$/.test(s)) return { ok: false, problem: WHATSAPP_UGANDA };
  else if (!international && /^\d{9}$/.test(s)) {
    // A bare 9 digits is a Uganda mobile without its 0 only when it starts with 7. "256" and six digits is a number
    // with digits missing, and any other 9 digits could be anything: refused, never guessed.
    if (s.startsWith(UGANDA)) return { ok: false, problem: 'This looks like 256 with digits missing. A Uganda number is 256 and 9 digits, like 0700 000 000.' };
    if (s.startsWith('7')) s = UGANDA + s;
    else if (!s.startsWith('0')) return { ok: false, problem: WHATSAPP_UGANDA };
  }
  if (s.startsWith('0') || s.startsWith(`${UGANDA}0`)) return { ok: false, problem: 'This number has a 0 where it cannot be: at the start, or right after 256. Check it and type it again.' };
  if (s.startsWith(UGANDA)) {
    if (s.length !== 12) return { ok: false, problem: `A Uganda number is 256 and 9 digits, like 0700 000 000. This one has ${s.length - 3} digits after 256.` };
    return { ok: true, digits: s };
  }
  if (!international) return { ok: false, problem: 'For a number outside Uganda, type it with + and the country code.' };
  if (s.length < 8 || s.length > 15) return { ok: false, problem: 'A number with a country code has 8 to 15 digits. Check it and type it again.' };
  return { ok: true, digits: s };
}

/** The number to show a person: `+` and the digits. */
export const shownPhone = (digits: string) => `+${digits}`;

/** The text with any half of an emoji (a lone surrogate, as a name cut short leaves) dropped: encodeURIComponent throws on one. */
export function wellFormed(text: string): string {
  return text.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '');
}

/** The wa.me address that opens a chat with the number and the message typed in. A wa.me link cannot carry a file. */
export function whatsAppLink(digits: string, text: string): string {
  return `https://wa.me/${digits}?text=${encodeURIComponent(wellFormed(text))}`;
}

/**
 * A name as it goes into the caption. WhatsApp has no escape for its markup, so the characters that would turn bold,
 * italic, struck or code on and off (`*`, `_`, `~` and the backtick) are taken out of the name.
 */
export function plainName(name: string | undefined): string {
  return wellFormed((name ?? '').replace(/[*_~`]/g, '')).replace(/\s+/g, ' ').trim();
}

const METHODS: Record<string, string> = { cash: 'Cash', mobile_money: 'Mobile money', bank: 'Bank', credit: 'Credit' };

/** What the caption reads: the receipt's facts as the server gives them, the same figures its PDF prints. */
export type CaptionFacts = Pick<SaleReceipt, 'receipt_no' | 'business_name' | 'buyer_name' | 'payment_method' | 'paid_minor' | 'balance_minor' | 'returned_minor' | 'net_minor' | 'credit_notes'>;

/**
 * The words that go with the receipt, in the share sheet and in WhatsApp: the BUSINESS's own name (never the platform's),
 * the receipt number, the buyer when there is one, what was paid and how, the goods taken back and the net when there were
 * returns, and what is still owed.
 */
export function receiptCaption(f: CaptionFacts): string {
  const name = plainName(f.business_name);
  const who = plainName(f.buyer_name);
  const owed = f.balance_minor ?? 0;
  const returned = f.returned_minor ?? 0;
  const method = METHODS[f.payment_method ?? ''] ?? f.payment_method ?? '';
  const notes = (f.credit_notes ?? []).join(', ');
  return [
    `${name ? `*${name}*: ` : ''}Receipt ${f.receipt_no ?? ''}`.trim(),
    ...(who ? [`Customer: ${who}`] : []),
    // A credit sale with nothing paid says so; a return taken off the balance is never shown as paid (the facts' paid_minor
    // is the money actually paid).
    f.payment_method === 'credit' && (f.paid_minor ?? 0) === 0 ? 'Sold on credit, nothing paid yet' : `Amount paid: ${money(f.paid_minor ?? 0)}${method ? ` (${method})` : ''}`,
    ...(returned > 0 ? [`Goods returned: ${money(returned)}${notes ? ` (credit note ${notes})` : ''}`, `Net after returns: ${money(f.net_minor ?? 0)}`] : []),
    owed > 0 ? `Balance outstanding: ${money(owed)}` : 'Fully settled, thank you!',
    'Thank you for your business.',
  ].join('\n');
}
