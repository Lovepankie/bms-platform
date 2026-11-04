import { icons } from '../../../components/icons';
import { normalisePhone } from './receipt-share-state';

// Call, Text and WhatsApp next to a phone number (issue #817), as the owner's old app had them: one tap to chase a debt or order
// stock. They are plain links the phone opens in its own dialler, messages or WhatsApp; the app itself sends nothing. The number
// is read with the receipt sender's normaliser (Uganda by default, another country with its + and code). No number draws
// nothing, and a contact the normaliser cannot read (a landline, an email, a number with digits missing) stays text only, as the
// screen already shows it.

export interface ContactHrefs { call: string; text: string; whatsApp: string }

/** The three links for a contact as typed, or null when it is not a phone number the normaliser can read. */
export function contactHrefs(contact: string | null | undefined): ContactHrefs | null {
  if (!contact || contact.trim() === '') return null;
  const read = normalisePhone(contact);
  if (!read.ok) return null;
  return { call: `tel:+${read.digits}`, text: `sms:+${read.digits}`, whatsApp: `https://wa.me/${read.digits}` };
}

/** The Call, Text and WhatsApp buttons for `contact`; `name` says who they reach, for a screen reader. */
export function ContactLinks({ contact, name }: { contact: string | null | undefined; name?: string }) {
  const hrefs = contactHrefs(contact);
  if (!hrefs) return null;
  const who = name ? ` ${name}` : '';
  return (
    <p className="contact-links" data-testid="contact-links">
      <a className="btn btn-sm btn-icon" href={hrefs.call} aria-label={`Call${who}`}>{icons.phone}Call</a>
      <a className="btn btn-sm btn-icon" href={hrefs.text} aria-label={`Text${who}`}>{icons.chat}Text</a>
      <a className="btn btn-sm btn-icon" href={hrefs.whatsApp} target="_blank" rel="noopener noreferrer" aria-label={`WhatsApp${who}`}>{icons.chatRound}WhatsApp</a>
    </p>
  );
}
