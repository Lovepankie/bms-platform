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
   Cloudflare rule with no change to it. The message reads "See your receipt: <link>". The link is made only when
   WhatsApp customer is tapped, never by viewing the screen, so no link exists that nobody sent (review of #762). The
   chat's window is opened inside the tap, so a phone does not block it as a pop-up while the link is made, and is sent
   to WhatsApp when the link is back; when the link cannot be made, or is not back within 8 seconds on a stalled
   connection, the message goes without it and the screen says so.
3. **The token is unguessable, stored only as a hash, and lives 90 days; a sale has one link.** 26 characters of lower
   case base32 (130 bits), longer than any new short name (ADR-108 decision 1) so the two never meet, and lower case
   because the rule's endpoint reads names in lower case. The token is random, found by its SHA-256 and kept sealed with
   the application data key (`DataKeys`, AES-256-GCM, the sealer of stored provider credentials) in
   `retail_receipt_links.token_sealed`, so a share can give it again and the table holds no token in clear. A sale has
   one current link, held by the database: a unique partial index on `(tenant_id, sale_id) WHERE revoked_at IS NULL`.
   Every share, from any phone or person and at any time, gets that link while it is live and its box opens. When it has
   expired, or no configured data key opens its box any more (a retired key), the share sets its `revoked_at` and
   inserts the new link, valid 90 days, in one transaction; only a new link is audited. Two shares at once meet at the
   index: the second's insert does nothing and it reads the first one's link. Liveness for the index is `revoked_at`,
   not the clock, since an index predicate may not read the time. `revoked_at` is the only column the application may
   update (a column grant), and only once, from null (a guard trigger). There is no advisory lock: the index alone keeps
   one current link per sale; a revoked link never opens again. The hash is a `text` column checked to
   64 hex digits, so the lookups, bound as text, use its unique index and a stream of wrong links never scans the table.
4. **The platform forwards, the shop's host renders.** The short link endpoint asks the shops first, then each module's
   `ShortLinkTarget` (a hook in `core.tenancy`, so the core names no vertical). Retail answers a token-shaped name: a live
   link is a 302 to `<shop host>/api/v1/retail/receipt-links/<token>`, found with the definer function
   `retail_receipt_link_shop`, which returns the slug and nothing else. On the shop's host the public route reads the link
   under the business's row level security and renders the receipt PDF now, inline, `no-store`: that one receipt as the
   seller shares it, issued by whoever made the link. No sign-in and nothing else of the business is reachable from it.
5. **A wrong or expired link is a plain page.** A token that is not found, has expired, belongs to another business, or
   whose sale no longer has a receipt answers 404 with a small HTML page, "This receipt link has expired", that names
   neither the business nor the sale, on both hosts. A name of the token's shape that is no link gets this page too, not
   ADR-108's "No shop uses this link"; no new short name is that long.
6. **The public routes are limited.** On the platform host a name no shop has (a receipt link or a mistyped name) counts
   against 60 a client address may ask in ten minutes (429 beyond, with a plain "Too many tries" page). An address over
   its limit adds no count for a link, so one client sending many random tokens cannot fill the limiter and turn every
   customer away. On the shop's host one link may be opened 20 times
   and one address may open 30 in ten minutes; beyond that the answer is 429 with a plain "Too many tries" page, before
   anything is read or drawn, so one leaked link cannot fill the render places the sellers' own receipts share. Both are in
   memory per instance, behind the edge's own limits. The definer function relies on its owner `bms_owner` having
   BYPASSRLS (`deploy/postgres/initdb/01-roles.sh`), as `help_ask_platform_spend` (V108) does.

## Consequences

**Better:** a customer reached by a typed number gets the full receipt, with the customer's name when one was typed; the
links reuse the short link host and its one rule; a leaked table gives no working link.

**Worse:** anyone holding the link sees that receipt for 90 days, as with a forwarded PDF. The receipt is rendered on each
open, so a link opened after a payment shows the receipt as it stands then, while the message text keeps the amounts it
was sent with. Rows are kept after expiry; the removal of a closed business deletes them with its other rows (the purge
finds every table with a `tenant_id` and retries one a foreign key holds; `BusinessClosureIT` seeds a link).

**Watch for:** the receipt links share the short link space with the shops, so a website page needs its path on ADR-108's
reserved list in any case, or the rule takes it. A burst of opens of live links takes receipt render places like the
seller's own receipt (503 `documents_busy` when they are full).
