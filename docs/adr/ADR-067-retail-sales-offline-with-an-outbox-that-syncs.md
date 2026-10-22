# ADR-067: Retail sales work offline: a catalogue and an outbox on the device that sync once, oldest first

## Status

Proposed (owner decision, issue #458, 22 Oct 2026). Amends ADR-009 (staff data "never cached for offline use beyond drafts")
and NFR-OFF-04 and NFR-OFF-05 for retail sales only. Every other money action, and every lending action, stays online only.
Migration `V87` (`V81` to `V86` are claimed by the open lending and payments pull requests; renumbered at merge if needed).

## Context

A shop on a slow or dropped mobile connection could not ring up a sale: the sale screen searched items on the server and
posted the sale at once, and NFR-OFF-04 refused every money action offline. The customer still pays and leaves with the
goods, so the shop wrote the sale on paper and typed it later, or lost it.

The sales route already takes an `Idempotency-Key` (chapter 7 section 7.8), but a key row lives 7 days
(`idempotency_keys.expires_at`, purged by the daily `IDEMPOTENCY_KEY_PURGE` job), and a phone can stay offline longer.
A sale also runs checks that can change while the phone is away: the price, whether the item is still for sale, the stock
left, the branch, the period, the person's roles.

## Decision

1. **Scope.** Only `POST /retail/sales` for cash, mobile money and bank sales may be queued offline. A credit sale needs the
   buyer's limit and is online only. Lending (repayments, disbursements, deposits, withdrawals), approvals, voids, returns,
   payments on account, restocks, transfers, the cash book and every other money action stay refused offline as NFR-OFF-04
   says. Lending data is never cached.
2. **What the device keeps (IndexedDB, database `bms-offline`).** Per business host and per signed-in person: the active
   items of each branch the person may sell at (`GET /retail/products?active=true&branch_id=`, every page, with their pack
   sizes, which are the price rules a seller can read; items with sizes and colours are products and come with them), the
   person's selling branches, the customers list, the four sale payment methods, the person's profile, and `prices_as_of`,
   the time of the last full refresh. It refreshes when the app comes online, on sign-in and every 15 minutes while online.
   Price lists are not cached: a seller may not read them (`retail.pricelist.manage`), so an offline sale is priced at the
   retail price or the pack price on the device, or a typed price.
3. **The outbox.** A sale saved offline, or whose answer was lost, goes into the outbox store with its whole request, a
   `client_sale_id` (a UUID made when the draft starts), an `Idempotency-Key`, `occurred_at` (the device time of Save),
   `prices_as_of`, the person, the branch and a provisional receipt number (`OFF-` and the first 8 characters of the client
   sale id). The screen shows the provisional receipt marked "Not yet sent"; the server's number replaces it after sync.
4. **Sync.** One sale at a time, oldest `occurred_at` first, never two requests in flight. A network failure, a 5xx, a 409
   or a 429 backs off (5 s, 15 s, 1 min, 5 min, then every 15 min) and keeps the order. A 401 tries one session refresh; if
   that fails the engine stops and the header asks the person to sign in. A 2xx removes the item and keeps the server's sale
   number for the receipt. Any other 4xx is a refusal: the item is kept, marked failed with the server's words, reported to
   the server (decision 7) and skipped, so the next one goes. Sync starts on the `online` event, on a successful health ping
   (`GET /healthz` every 30 s while the page is open), after a sale is queued and on Sync now.
5. **Sent once.** The request carries `Idempotency-Key` (replays within 7 days answer from the key row) and
   `client_sale_id`, stored on `retail_sales` with a unique index per tenant. The server looks for the client sale id
   before any other check: a sale already recorded with it is answered as a replay (`Idempotent-Replayed: true`) when the
   same person made it, and refused with 422 `client_sale_id_reused` otherwise. A replay after the key expired, or with a
   new key after a lost answer, is therefore still the same sale. Two concurrent sends that both miss the lookup are settled
   by the unique index: the loser answers 409 `client_sale_in_progress` and its retry finds the sale. The online sale screen
   sends a `client_sale_id` too, so a sale whose answer was lost and that moves to the outbox can never be recorded twice.
   The sale stores `request_hash`, the SHA-256 of the request without how it was delivered (`occurred_at`,
   `recorded_offline`, `prices_as_of`): a later send under the same client sale id with a different request is 422
   `client_sale_conflict` and records nothing. Once an answer is lost the sale form is locked: Try again sends the same
   request with the same key, and Keep it on this phone queues that same request (dated when it was first sent, marked
   offline) with a new key; changing the sale means clearing it, which starts a new client sale id.
6. **When it happened.** `occurred_at` is optional. When given, the sale is dated the day of `occurred_at` in the business's
   time zone (a `sale_date` that disagrees is 422 `sale_date_mismatch`) and `occurred_at` is stored on the sale. Up to 5
   minutes ahead of the server clock is clock skew and is recorded as the server's now. Further ahead, an online request is
   refused (422 `occurred_at_in_future`); an offline one is recorded at the server's now and flagged `clock_ahead`. Retail has
   no daily close; the bound in the past is the accounting period: a sale dated in a closed month is refused by the ledger
   (`period_closed`) and becomes a failure (decision 7), never a silent drop. If a daily close is built later it adds the same
   refusal.
7. **Clashes, recorded and flagged, never dropped.** A request with `recorded_offline: true` (which needs `client_sale_id`
   and `occurred_at`) is a sale whose money was already taken. For it, four checks that refuse an online sale record the sale
   and write a row in `retail_sale_clashes` instead: `stock_short` (the sale takes the balance below zero, or into stock kept
   for orders; the movement is written and the balance may go negative), `item_inactive` (switched off since), `price_changed`
   (with price lists on, the resolved price differs from the device's; the line keeps the device price as an override whose
   reason names the offline sale and `prices_as_of`, without the `retail.price.override` check, or a strict list has no price)
   and `below_cost` (the price is not above cost without `retail.price.below_cost`; the clash words never carry the cost).
   `clock_ahead` is the fifth kind. A push notice `retail.sale_clash` goes to every holder of `retail.offline.resolve` at the
   branch (the administrators, the owner among them, and the branch's Supervisors), once per sale. The sale page shows its
   clashes; `GET /retail/sale-clashes` lists the open ones; `POST /retail/sale-clashes/{clash_id}/resolve` closes one with a
   note, audited. Resolving changes nothing else: a stock count or a correction is done the usual way.
8. **Failures, kept and decided by a manager.** A refusal for any other reason (a closed branch or period, a lost
   permission, an unknown item, a credit sale, a validation problem) leaves the sale in the outbox, marked failed, with the
   server's message in plain words. The device reports it with `POST /retail/offline-failures` (the request, the code and the
   message; `retail.sale.create`; idempotent on the client sale id), so a manager sees it on their own phone. A holder of
   `retail.offline.resolve` at the branch, in the sync sheet, either asks for it to be sent again after fixing the cause
   (`.../retry`: the seller's device sends it on its next sync, with the same client sale id and a new key) or discards it
   with a reason of at least 5 characters after a confirmation (`.../discard`, audited `retail.offline_sale.discarded`). A
   discarded failure is marked discarded on the seller's device on its next sync, shown for a day and never sent again. The
   server refuses a sale whose client sale id belongs to a discarded failure, before any other check of the sale, with 422
   `offline_sale_discarded`, whatever key it carries; the device marks it discarded on that answer too. A failure later recorded as a sale is closed by
   that sale in the same transaction. The seller may send their own failed sale again at any time; they cannot discard it.
9. **Sign-out safety.** Signing out while anything is queued or failed asks first ("3 sales are waiting to sync. Sync now,
   or keep them on this phone and sign out?"). The waiting sales stay in the person's own outbox on this device and are sent
   when the same person signs in here again; the catalogue copy is cleared at sign-out. Another person on the same device
   never sees or sends them. Reopening the app with no network after a sign-in shows the last profile of the last person
   signed in on this device, read from IndexedDB, with only the sale screen and the outbox working; anything needing the
   server says it is offline. The refresh cookie is never read or kept by the app.
10. **Status in the header.** A dot in the staff bar on every staff screen: green online, grey offline, amber while syncing,
    with "3 waiting to sync" beside it when the outbox is not empty and "1 failed" when one is failed. Tapping it opens a sheet
    listing queued and failed sales (time, branch, total, the reason of a failure), a Sync now button and, for a holder of
    `retail.offline.resolve`, the failures other people reported. It is mounted in `frontend/src/areas/staff/route.tsx`, not in
    the shell, and is drawn only for a person who holds `retail.sale.create`.
11. **Product owner decisions for review.** (a) A price changed while the phone was offline: the sale keeps the price the
    customer paid and is flagged `price_changed`; it is never repriced. (b) An offline sale is never a credit sale. (c) A sale
    refused for a closed branch or a closed month is kept on the device and decided by a manager, never recorded against the
    rule. (d) A seller cannot discard their own failed sale. (e) Waiting sales survive a sign-out on that device.

## Consequences

**Better:**

- A shop keeps selling through a dropped connection and every sale it takes reaches the books once.
- A replay after any delay, a lost answer, a retried tap or two devices racing cannot make two sales.
- Every conflict the server finds is written down and told to the people who resolve it.

**Worse:**

- Stock can go negative for a short time, which ADR-020 decision 4 refused without exception; it is flagged, not silent.
- A seller who sets `recorded_offline` by hand bypasses the price floor and the stock check; each such sale is flagged,
  noticed and audited, which is the control.
- The device holds the catalogue with retail prices and the customers list of a business. It is cleared at sign-out.

**Watch for:**

- A device that stays offline for weeks: its sales still post, dated when they happened, but a closed month refuses them.
- Many clashes at one branch, which points at a phone that sells offline most of the time or at misuse of the flag.
- The size of the catalogue copy for a business with tens of thousands of items.

## Related ADRs

- ADR-009 (the PWA; amended for retail sales here), ADR-020 (retail, decision 4 on stock), ADR-033 (price lists and customer
  accounts), ADR-050 (push notices), ADR-055 (retail roles and the Supervisor).
