import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { retail, type ReceiptPreview, type Sale } from '../../../api/retail';
import { useStaff } from '../context';
import { ALL_SELLERS, holdsAt } from './permissions';
import { normalisePhone, receiptCaption, shownPhone, whatsAppLink } from './receipt-share-state';
import { Problem } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';

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

export const NOT_DRAWN = 'The receipt could not be shown on the screen. Download the PDF to see it.';

const CUSTOMER_MANAGE = 'retail.customer.manage';

type Held = { key: string; pdf: File; refused: number };

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

/** The receipt as the PDF prints it, in its order, from what the server read off the PDF's own model (issue #500). */
function ReceiptPaper({ printed: p }: { printed: NonNullable<ReceiptPreview['printed']> }) {
  const [logo, setLogo] = useState(true);
  return (
    <article className="receipt-paper" aria-label={`${p.title} ${p.number}`}>
      <header className="receipt-head">
        {p.logo_url && logo && <img className="receipt-logo" src={p.logo_url} alt="" onError={() => setLogo(false)} />}
        <div>
          <p className="receipt-business">{p.business_name}</p>
          {p.branch_name && <p className="muted">{p.branch_name}</p>}
          {p.branch_location && <p className="muted">{p.branch_location}</p>}
        </div>
        <div className="receipt-docline">
          <p className="receipt-no">{p.number}</p>
          <p>{p.date}</p>
        </div>
      </header>
      <h3 className="receipt-title">{p.title}</h3>
      <div>
        <p className="muted">Bill to</p>
        <p className="receipt-strong">{p.buyer_name}</p>
        {p.buyer_contact && <p className="muted">{p.buyer_contact}</p>}
      </div>
      <dl className="receipt-facts">
        <dt>Sale</dt><dd>{p.sale_no}</dd>
        <dt>Sale date</dt><dd>{p.sale_date}</dd>
        <dt>Payment</dt><dd>{p.payment}</dd>
        {p.due_date && <><dt>Due date</dt><dd>{p.due_date}</dd></>}
        {p.price_list && <><dt>Price list</dt><dd>{p.price_list}</dd></>}
        {p.quotation && <><dt>Quotation</dt><dd>{p.quotation}</dd></>}
      </dl>
      {/* The PDF's items table, column for column, with the server's own strings: nothing is joined or worded here. */}
      <table className="receipt-lines" aria-label="Items">
        <thead>
          <tr>
            <th scope="col">No</th>
            <th scope="col">Item</th>
            <th scope="col" className="num">Quantity</th>
            <th scope="col" className="num">Unit price ({p.currency})</th>
            <th scope="col" className="num">Amount ({p.currency})</th>
          </tr>
        </thead>
        <tbody>
          {(p.lines ?? []).map((l, i) => (
            <tr key={i}>
              <td>{l.no}</td>
              <td>{l.description}<br /><span className="muted">{l.detail}</span></td>
              <td className="num">{l.quantity}{l.pack && <><br /><span className="muted">{l.pack}</span></>}</td>
              <td className="num">{l.unit_price}</td>
              <td className="num">{l.amount}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="receipt-total">
        <span>Total ({p.currency})</span>
        <span className="num">{p.total}</span>
      </p>
      <p className="receipt-words">{p.total_words}</p>
      {p.status && (
        <div className="receipt-box">
          <p>{p.status}</p>
          {p.due && <p className="muted">{p.due}</p>}
        </div>
      )}
      {p.net && (
        <div className="receipt-box">
          {(p.returns ?? []).map((r, i) => <p key={i}>{r}</p>)}
          <p>Net after returns ({p.currency}): <span className="num">{p.net}</span></p>
        </div>
      )}
      {p.notes && <p className="muted">{p.notes}</p>}
      <p>Issued by {p.issued_by}<br /><span className="muted">{p.issued_on}</span></p>
      {p.received && <p>{p.received}</p>}
      {p.footer && <p className="receipt-footer">{p.footer}</p>}
    </article>
  );
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

/** The receipt facts of a sale the person may share, read once for every part of the screen that needs them. */
function useReceiptFacts(sale: Sale, madeHere: boolean) {
  const { me } = useStaff();
  const allowed = canShareReceipt(me, sale, madeHere);
  const key = receiptKey(sale);
  const facts = useQuery({ queryKey: ['retail', 'sale-receipt', key], queryFn: () => retail.saleReceipt(sale.id ?? ''), enabled: allowed, retry: false });
  return { allowed, key, facts };
}

/**
 * True once Share receipt is drawn for this sale (issue #534): until then, while the facts load or when they fail, the
 * sale-complete screen keeps New sale as its main action, so the screen is never without one.
 */
export function useShareReady(sale: Sale, madeHere = false): boolean {
  const { allowed, facts } = useReceiptFacts(sale, madeHere);
  return allowed && facts.data !== undefined;
}

/** Share receipt and WhatsApp customer for one sale. `madeHere`: the sale was just made on this screen by this person. */
export function ShareReceipt({ sale, madeHere = false }: { sale: Sale; madeHere?: boolean }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const { allowed, key, facts } = useReceiptFacts(sale, madeHere);
  const [typed, setTyped] = useState<string | null>(null);
  const [phoneProblem, setPhoneProblem] = useState<string | null>(null);
  const [keep, setKeep] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  // View receipt (Hillary, 23 Oct 2026; issue #500): what the PDF prints, drawn on the screen, since a phone's browser
  // cannot be trusted to show a PDF inline. Open for the state of the sale it was opened at only, so a payment closes it.
  const [shownFor, setShownFor] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  // The printed receipt is its own request, made only once View receipt is tapped, so the facts above stay a cheap read.
  const printed = useQuery({
    queryKey: ['retail', 'sale-receipt-printed', key],
    queryFn: () => retail.saleReceiptPrinted(sale.id ?? ''),
    enabled: allowed && shownFor === key,
    retry: false,
  });
  // The PDF once fetched, for this state of the sale only: a second tap shares it at once, inside the tap, which a phone that
  // let the first tap's wait run out needs. `refused` counts the phone's refusals of the share sheet for this file. `pending`
  // is the fetch under way, which Share and Download the PDF both wait on rather than fetching it twice.
  const file = useRef<Held | null>(null);
  const pending = useRef<{ key: string; promise: Promise<Held> } | null>(null);
  const save = useMutation({
    mutationFn: (contact: string) => retail.updateCustomer(facts.data?.customer_id ?? '', facts.data?.customer_version ?? 0, { contact }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['retail', 'sale-receipt'] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'customers'] });
    },
    meta: { success: 'Number saved' },
  });
  if (!allowed) return null;
  const f = facts.data;
  const phone = typed ?? f?.buyer_contact ?? '';
  // Offered only for a buyer with a record and no number on it, to someone who may edit buyers here, once a number is typed.
  const mayKeep =
    f?.customer_id !== undefined && f.customer_version !== undefined && !f.customer_has_contact && holdsAt(me, CUSTOMER_MANAGE, sale.branch_id) && (typed ?? '').trim() !== '';
  const title = `Receipt ${f?.receipt_no ?? sale.sale_no ?? ''}`.trim();

  const shown = shownFor === key;
  const previewId = `receipt-preview-${sale.id}`;

  /** The receipt PDF for this state of the sale, fetched once and kept for Share and Download the PDF. */
  function pdf(): Promise<Held> {
    if (file.current?.key === key) return Promise.resolve(file.current);
    if (pending.current?.key === key) return pending.current.promise;
    const at = key;
    const promise = retail.saleReceiptPdf(sale.id ?? '').then(
      (blob) => {
        const held = { key: at, pdf: new File([blob], `${title}.pdf`, { type: 'application/pdf' }), refused: 0 };
        if (pending.current?.promise === promise) {
          file.current = held;
          pending.current = null;
        }
        return held;
      },
      (e: unknown) => {
        if (pending.current?.promise === promise) pending.current = null;
        throw e;
      },
    );
    pending.current = { key: at, promise };
    return promise;
  }

  async function downloadPdf() {
    setNote(null);
    setError(null);
    setDownloading(true);
    try {
      download((await pdf()).pdf);
    } catch (e) {
      setError(e);
    } finally {
      setDownloading(false);
    }
  }

  async function share() {
    if (!f) return;
    setNote(null);
    setError(null);
    let held = file.current?.key === key ? file.current : null;
    try {
      if (!held) {
        // toast-exempt: fetching the receipt PDF saves nothing; the share sheet or the downloaded note says what happened
        setBusy(true);
        held = await pdf();
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
          {/* Share receipt first, the main action of the sale-complete screen (issue #534); New sale sits below these. */}
          <BusyButton type="button" className="rt-primary" busy={busy} onClick={() => void share()}>Share receipt</BusyButton>
          <p className="cluster">
            <button type="button" className="btn" aria-expanded={shown} aria-controls={previewId} onClick={() => setShownFor(shown ? null : key)}>
              View receipt
            </button>
          </p>
          <div id={previewId} className="receipt-preview" hidden={!shown}>
            {shown && printed.isPending && <p className="loading">Loading</p>}
            {shown && printed.data?.printed && <ReceiptPaper printed={printed.data.printed} />}
            {shown && (printed.isError || (printed.data && !printed.data.printed)) && <p className="alert alert-info">{NOT_DRAWN}</p>}
            {shown && !printed.isPending && (
              <BusyButton type="button" className="receipt-download" busy={downloading} onClick={() => void downloadPdf()}>
                Download the PDF
              </BusyButton>
            )}
          </div>
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
            <BusyButton type="button" className="btn" busy={save.isPending} onClick={whatsApp}>WhatsApp customer</BusyButton>

          </p>
          <p className="field-hint">{WHATSAPP_HINT}</p>
          <Problem error={save.error} />
        </>
      )}
    </section>
  );
}
