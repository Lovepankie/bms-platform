# ADR-061: Receipts are shared from the seller's own phone, never sent from a server

## Status

Proposed (owner decision, issue #439, 22 Oct 2026). Follows ADR-055 (retail roles: a seller sees only their own figures, a
storekeeper no prices) with one deliberate, narrow exception in decision 3, and ADR-033 (optional documents: the invoice, delivery note and quotation stay behind the
`documents` capability and `retail.document.issue`). The number follows pending ADR-058 (pull request 427) and pending ADR-059
(pull request 433), and ADR-060 on main. No migration.

## Context

A sales agent who has made a sale wants to give the customer the receipt on the spot, on WhatsApp or any other way the customer
uses. The company's other product sends receipt PDFs to customers from the company's own WhatsApp session on a server. BMS is
multi-tenant, so the same design would have every shop's customers receive receipts from Rincol Tech's number instead of the
shop's, and one WhatsApp session sending for every tenant is the pattern that gets a number banned.

The app had no sharing at all. A sale's priced document today is the invoice, which only an administrator may issue and only with
the `documents` capability on, so a seller could not reach any PDF of their own sale.

## Decision

1. **The receipt leaves from the phone in the seller's hand.** Two buttons on a sale's receipt, right after the sale is saved and on
   the opened sale: **Share receipt** fetches the receipt PDF and calls the Web Share API with it as a file
   (`navigator.canShare({ files })`, then `navigator.share({ files, title, text })`), so the phone offers WhatsApp, email, SMS,
   Telegram and the rest; where a browser cannot share files the PDF downloads and the screen says so; a cancelled share sheet
   (`AbortError`) shows nothing. **WhatsApp customer** opens `https://wa.me/<digits>?text=<caption>` in a new window. A wa.me link
   cannot carry a file, so the caption carries the receipt's facts and the screen says to use Share receipt for the PDF itself.
   BMS keeps no WhatsApp session and sends nothing from a server.
2. **Two read routes, no stored document.** `GET /retail/sales/{sale_id}/receipt/pdf` under `retail.sale.read` renders the
   receipt now from the sale: the invoice layout (`invoice.html`) titled RECEIPT under the sale's own number, with the shop's
   letterhead and footer, signed "Issued by" the seller who made the sale (not whoever shares it). `GET
   /retail/sales/{sale_id}/receipt` returns the same facts as JSON (number, business name, buyer and number, paid, balance,
   returns, net, credit notes, the sale's version) for the caption, so the words sent and the PDF never disagree. Neither is
   stored, numbered in a series or written to a table, so they work with the `documents` capability on or off and need no
   `retail.document.issue`. The numbered invoice of ADR-033 is unchanged. Who may have the receipt and whether the sale has one
   are checked first, holding no render place, so a wrong or foreign sale id is a quick 404 or 422. The PDF is then laid out in a
   render place of its own (`Documents.renderSharedPdf`), `bms.documents.max-concurrent-shared-renders` places (two by default)
   for the whole platform, apart from the document places
   (`bms.documents.max-concurrent-renders`, two by default) and the report places (all but one of those): a report never makes a
   seller wait, and a burst of receipts never takes a place an administrator's documents need. Its model is built only once
   that place is held. At most four PDFs are laid out at once on the smallest host instead of two (two documents or reports and
   two receipts); a receipt is one or two pages.
3. **Who may share.** Only someone who can already view the sale may fetch its receipt, enforced on the server and mirrored in the
   screen: `retail.sale.read` at the sale's branch, and without `retail.sale.all_sellers` there only a sale the caller made (a
   caller with no user, such as a service token, never counts as its maker). This is stricter than reading the sale itself:
   ADR-055 lets a seller open another seller's credit sale that still owes, to take a payment, but not fetch its receipt. A
   storekeeper has no `retail.sale.read` and gets 403; a supervisor gets every seller's at the branches it supervises and an
   administrator everywhere. **A deliberate exception to ADR-055:** ADR-055 hides who made a sale from a caller without
   `retail.profit.read`, but the printed receipt is signed "Issued by" the seller who made the sale, because a customer's receipt
   says who served them, the customer sees it anyway, and the caller may already see the sale. The maker's name appears ONLY in
   that printed signature of a receipt the caller is already allowed to fetch, never in any API field (the facts route carries no
   maker, and `created_by` stays hidden as ADR-055 says). Decided on the owner's delegation on 22 Oct 2026; the owner may reverse
   it, which is a change to the signature line only.
4. **A receipt says what the money is now, or is refused.** The balance is the one the sale carries (payments, and returns taken
   off the balance, already counted), never the total less the payments. What the buyer paid is the money actually paid: a return
   taken off the balance is booked on the sale as paid in the ledger (unchanged), and the receipt and its facts show it as a
   return, never as a payment, so a credit sale with nothing paid says so. A sale with a completed return is titled RECEIPT AFTER
   RETURNS and lists each credit note with the goods taken back, what was paid back and what was taken off the balance, and the
   net; the caption names the goods returned, the credit notes and the net too. No receipt is given (422) for a voided sale
   (`sale_voided`), a written-off sale (`sale_written_off`), or a sale whose goods were all taken back (`sale_fully_returned`):
   a clean receipt is never printed for money that is not owed or was given back.
5. **The caption** is the business's own name from its branding (never the platform's), then the receipt number, the buyer when
   there is one, the amount paid and how, the goods returned and the net when there were returns, and the balance outstanding or
   "Fully settled, thank you!", and the thanks. The same text is the share sheet's `text`. Money is formatted as the app formats
   UGX. WhatsApp has no escape for its markup, so `*`, `_`, `~` and the backtick are taken out of the business and buyer names,
   and half an emoji (a lone surrogate) is dropped before the text is put in the link.
6. **Phone numbers are normalised, never guessed.** The rules are ported from the other product's number normaliser: spaces,
   dashes, brackets and dots are dropped; `00` is the international prefix; a national `07XXXXXXXX` (10 digits, leading 0) becomes
   `2567XXXXXXXX`; a doubled `2560XXXXXXXXX` collapses to `256XXXXXXXXX`; a bare 9-digit number gets `256` only when it starts
   with 7 (a Uganda mobile), and `256` with six digits or any other 9 digits is refused; anything with country code 256 must be
   exactly 12 digits; a number may not start with 0 once its prefix is read, and a Uganda number may not have a 0 right after
   256 (this is a Uganda rule: another country's number keeps whatever follows its code, such as a trunk 0, as typed); another
   country is accepted only with `+` and 8 to 15 digits. An ambiguous number is refused in plain words. The number is prefilled from the buyer's record
   (`retail_customers.contact`, read by id with the receipt's facts, so every buyer is found) or the sale (`buyer_contact`,
   which an order delivered as a sale carries), else typed.
7. **Writes nothing but an optional phone.** Sharing and WhatsApp write nothing to the server, so there is no new audit row. When the
   agent types a number for a buyer who has a record and no number, "Save this number for <buyer>" (unticked) saves it through the
   existing `PATCH /retail/customers/{id}` (`retail.customer.manage` at the sale's branch, audited as today), as `+256...`; the
   order phone lookup matches on the last nine digits, so it finds the buyer whether a number is stored as `+256...` or `07...`.
   The typed number is never logged. The screen fetches the facts and the PDF again whenever the sale's version, paid amount or
   balance changes (a payment taken on the same screen), so a stale receipt is never shared.

## Consequences

**Better:** each shop's receipts come from the shop's own people and numbers; no shared WhatsApp session to be banned or to run; a
seller can send a receipt without the documents capability or an administrator; the customer can get it on any app.

**Worse:** the agent's phone does the sending, so the business has no record of which receipts were sent or to whom. A wa.me
message carries the facts only, not the PDF; the PDF goes through the share sheet. Some desktop browsers cannot share files and
download instead. On an iPhone a slow PDF fetch can outlast the tap that allows a share; the screen then asks for a second tap,
which shares the already fetched file at once, and if the phone refuses again the PDF downloads instead. Names lose WhatsApp
markup characters in the caption.

**Watch for:** a public, no-login receipt link (to put the PDF itself in a wa.me message) is deliberately left out and needs its own
security review. Sending from a server is ruled out for BMS by this decision; a per-tenant sender would need its own ADR. The
receipt renders on each request like the customer statement, so it shares the PDF render places and can answer 503
`documents_busy` under load.
