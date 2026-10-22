import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { retail, type Sale } from '../../../api/retail';
import { useStaff } from '../context';
import { ALL_SELLERS, holdsAt } from './permissions';
import { normalisePhone, receiptCaption, shownPhone, whatsAppLink } from './receipt-share-state';
import { Problem } from './ui';

// Share a sale's receipt from the seller's own phone (issue #439, ADR-061). The receipt goes out from the phone in the
// seller's hand, never from a server: Share receipt hands the PDF to the phone's share sheet (WhatsApp, Gmail, SMS and the
// rest), and WhatsApp customer opens a chat with the buyer's number and the receipt's facts typed in. The caption and the
// PDF read the same facts from the server (the balance after payments and returns, the credit notes, the net), and both are
// fetched again when the sale changes. Nothing here writes to the server except the optional save of a typed number on the
// buyer's record. The typed number is never logged.

export const DOWNLOADED = 'This browser cannot share files, so the receipt PDF was downloaded. Send it from your downloads.';
export const SHARE_AGAIN = 'The receipt is ready. Tap Share receipt again to send it.';
export const SHARE_REFUSED = 'Your phone would not open its share options, so the receipt PDF was downloaded. Send it from your downloads.';
export const WHATSAPP_HINT = 'WhatsApp opens with the receipt details. To send the PDF itself, use Share receipt and pick WhatsApp.';
export const WHATSAPP_FAILED = 'WhatsApp could not be opened with these details. Check the number and try again.';

const CUSTOMER_MANAGE = 'retail.customer.manage';

const nameOf = (e: unknown) => (typeof e === 'object' && e !== null && 'name' in e ? String((e as { name: unknown }).name) : '');

function download(file: File) {
  const url = URL.createObjectURL(file);
  const a = document.createElement('a');
  a.href = url;
  a.download = file.name;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/**
 * Hands the file to the phone's share sheet when the browser can share files, else downloads it. A cancelled share sheet
 * rejects with an AbortError, which the caller ignores.
 */
export async function shareOrDownload(file: File, title: string, text: string): Promise<'shared' | 'downloaded'> {
  const nav = typeof navigator === 'undefined' ? undefined : navigator;
  if (nav && typeof nav.share === 'function' && typeof nav.canShare === 'function' && nav.canShare({ files: [file] })) {
    await nav.share({ files: [file], title, text });
    return 'shared';
  }
  download(file);
  return 'downloaded';
}

/** Whether this person may share the receipt: whoever may read the sale, and for a seller who sees only their own figures, their own sale. */
export function canShareReceipt(me: Parameters<typeof holdsAt>[0] & { user_id?: string }, sale: Sale, madeHere = false): boolean {
  if (!sale.id || sale.status !== 'completed' || sale.write_off) return false;
  if (!holdsAt(me, 'retail.sale.read', sale.branch_id)) return false;
  if (holdsAt(me, ALL_SELLERS, sale.branch_id)) return true;
  return sale.created_by === undefined ? madeHere : sale.created_by === me.user_id;
}

/** What the receipt depends on: a payment or a return changes the version and the money, and with it the PDF and the caption. */
export const receiptKey = (sale: Pick<Sale, 'id' | 'version' | 'paid_minor' | 'balance_minor'>) =>
  `${sale.id ?? ''}:${sale.version ?? ''}:${sale.paid_minor ?? ''}:${sale.balance_minor ?? ''}`;

/** Share receipt and WhatsApp customer for one sale. `madeHere`: the sale was just made on this screen by this person. */
export function ShareReceipt({ sale, madeHere = false }: { sale: Sale; madeHere?: boolean }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = canShareReceipt(me, sale, madeHere);
  const key = receiptKey(sale);
  const facts = useQuery({ queryKey: ['retail', 'sale-receipt', key], queryFn: () => retail.saleReceipt(sale.id ?? ''), enabled: allowed, retry: false });
  const [typed, setTyped] = useState<string | null>(null);
  const [phoneProblem, setPhoneProblem] = useState<string | null>(null);
  const [keep, setKeep] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  // The PDF once fetched, for this state of the sale only: a second tap shares it at once, inside the tap, which a phone that
  // let the first tap's wait run out needs. `refused` counts the phone's refusals of the share sheet for this file.
  const file = useRef<{ key: string; pdf: File; refused: number } | null>(null);
  const save = useMutation({
    mutationFn: (contact: string) => retail.updateCustomer(facts.data?.customer_id ?? '', facts.data?.customer_version ?? 0, { contact }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['retail', 'sale-receipt'] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'customers'] });
    },
  });
  if (!allowed) return null;
  const f = facts.data;
  const phone = typed ?? f?.buyer_contact ?? '';
  // Offered only for a buyer with a record and no number on it, to someone who may edit buyers here, once a number is typed.
  const mayKeep =
    f?.customer_id !== undefined && f.customer_version !== undefined && !f.customer_has_contact && holdsAt(me, CUSTOMER_MANAGE, sale.branch_id) && (typed ?? '').trim() !== '';
  const title = `Receipt ${f?.receipt_no ?? sale.sale_no ?? ''}`.trim();

  async function share() {
    if (!f) return;
    setNote(null);
    setError(null);
    let held = file.current?.key === key ? file.current : null;
    try {
      if (!held) {
        setBusy(true);
        const blob = await retail.saleReceiptPdf(sale.id ?? '');
        held = { key, pdf: new File([blob], `${title}.pdf`, { type: 'application/pdf' }), refused: 0 };
        file.current = held;
      }
      if ((await shareOrDownload(held.pdf, title, receiptCaption(f))) === 'downloaded') setNote(DOWNLOADED);
    } catch (e) {
      if (nameOf(e) === 'AbortError') return;
      if (nameOf(e) === 'NotAllowedError' && held) {
        held.refused += 1;
        if (held.refused === 1) return setNote(SHARE_AGAIN);
        download(held.pdf);
        return setNote(SHARE_REFUSED);
      }
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  function whatsApp() {
    if (!f) return;
    const check = normalisePhone(phone);
    if (!check.ok) return setPhoneProblem(check.problem);
    let link: string;
    try {
      link = whatsAppLink(check.digits, receiptCaption(f));
    } catch {
      return setPhoneProblem(WHATSAPP_FAILED);
    }
    setPhoneProblem(null);
    window.open(link, '_blank', 'noopener,noreferrer');
    if (mayKeep && keep) save.mutate(shownPhone(check.digits));
  }

  return (
    <section className="rt-card stack" aria-label="Share the receipt" data-tour="receipt-share">
      <h2>Send the receipt</h2>
      {facts.isPending && <p className="loading">Loading</p>}
      <Problem error={facts.error} />
      {f && (
        <>
          <p className="cluster">
            <button type="button" className="rt-primary" disabled={busy} onClick={() => void share()}>{busy ? 'Getting the receipt' : 'Share receipt'}</button>
          </p>
          {note && <p role="status" className="alert alert-info">{note}</p>}
          <Problem error={error} />
          <label htmlFor={`receipt-phone-${sale.id}`}>Customer&apos;s WhatsApp number</label>
          <input
            id={`receipt-phone-${sale.id}`}
            type="tel"
            inputMode="tel"
            autoComplete="off"
            value={phone}
            placeholder="0700 000 000"
            aria-invalid={phoneProblem !== null}
            onChange={(e) => { setTyped(e.target.value); setPhoneProblem(null); }}
          />
          {phoneProblem && <p role="alert" className="field-error">{phoneProblem}</p>}
          {mayKeep && (
            <label className="check">
              <input type="checkbox" checked={keep} onChange={(e) => setKeep(e.target.checked)} /> Save this number for {f.buyer_name ?? 'the buyer'}
            </label>
          )}
          <p className="cluster">
            <button type="button" className="btn" onClick={whatsApp}>WhatsApp customer</button>
          </p>
          <p className="field-hint">{WHATSAPP_HINT}</p>
          {save.isSuccess && <p role="status" className="alert alert-success">Number saved for {f.buyer_name ?? 'the buyer'}.</p>}
          <Problem error={save.error} />
        </>
      )}
    </section>
  );
}
