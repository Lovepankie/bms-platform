# ADR-116: A short link to one receipt for a typed WhatsApp number

## Status

Accepted (issue #760, request of a pilot shop of 2 Nov 2026). Builds on ADR-061 (sharing a receipt from the seller's own
phone) and ADR-108 (every shop has a short link on the company domain). Migration `V128`, claimed after `V124` to `V127`.

## Context

A seller shares a receipt two ways (ADR-061): Share receipt hands the PDF to the phone's share sheet, which works only to
a contact saved in the phone, and WhatsApp customer opens a `wa.me` chat with a typed number and the receipt's facts as
text. A `wa.me` link cannot carry a file, so a customer reached by a typed number got the amount paid and not the receipt.
The shop asked that the message carry the full receipt. In the same voice note it asked that a cash customer's name can be
typed so the receipt shows it instead of Cash customer.

## Decision

1. **The name is stored on the sale, not as a customer.** On a sale paid now (cash, mobile money or bank) that names no
   customer, Add customer name opens an optional name and phone; they go in the existing `retail_sales.buyer_name` and
   `buyer_contact` (V11), which the receipt PDF already prints as Bill to and Received by and the WhatsApp text as
   Customer. Blank keeps Cash customer. No customer record is made and nothing about stock or money changes. The button
   keeps the fast sale as it was.
2. **WhatsApp customer adds a short link to that sale's receipt.** `POST /retail/sales/{sale_id}/receipt/link` makes a
   link under the receipt's own check (ADR-061: who may share it, and 422 for a voided, written-off or wholly returned
   sale). The link is `rincoltech.com/<token>`, the short link host of ADR-108, so it is short and goes through the same
   Cloudflare rule with no change to it. The message reads "See your receipt: <link>". The screen makes the link once a
   number is there, so the tap opens WhatsApp at once; when the link cannot be made the message goes without it and the
   screen says so.
3. **The token is unguessable, stored only as a hash, and lives 90 days.** 26 random characters of lower case base32 (130
   bits), longer than any new short name (ADR-108 decision 1) so the two never meet, and lower case because the rule's
   endpoint reads names in lower case. Only its SHA-256 is stored (`retail_receipt_links`), so the table cannot be turned
   back into links. Each tap makes a new link; links are never changed and simply expire.
4. **The platform forwards, the shop's host renders.** The short link endpoint asks the shops first, then each module's
   `ShortLinkTarget` (a hook in `core.tenancy`, so the core names no vertical). Retail answers a token-shaped name: a live
   link is a 302 to `<shop host>/api/v1/retail/receipt-links/<token>`, found with the definer function
   `retail_receipt_link_shop`, which returns the slug and nothing else. On the shop's host the public route reads the link
   under the business's row level security and renders the receipt PDF now, inline, `no-store`: that one receipt as the
   seller shares it, issued by whoever made the link. No sign-in and nothing else of the business is reachable from it.
5. **A wrong or expired link is a plain page.** A token that is not found, has expired, belongs to another business, or
   whose sale no longer has a receipt answers 404 with a small HTML page, "This receipt link has expired", that names
   neither the business nor the sale, on both hosts.

## Consequences

**Better:** a customer reached by a typed number gets the full receipt, with the customer's name when one was typed; the
links reuse the short link host and its one rule; a leaked table gives no working link.

**Worse:** anyone holding the link sees that receipt for 90 days, as with a forwarded PDF. The receipt is rendered on each
open, so a link opened after a payment shows the receipt as it stands then, not as it was sent. Rows are kept after
expiry; the removal of a closed business deletes them with its other rows.

**Watch for:** the receipt links share the short link space with the shops, so a website page needs its path on ADR-108's
reserved list in any case, or the rule takes it. A burst of opens of live links takes receipt render places like the
seller's own receipt (503 `documents_busy` when they are full).
