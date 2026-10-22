# ADR-067: Retail sales work offline: a catalogue and an outbox on the device that sync once, oldest first

## Status

Accepted (owner decision, issue #458, 22 Oct 2026; the offline price rules of decision 11 decided by the product owner,
Hillary, 22 Oct 2026). Amends ADR-009 (staff data "never cached for offline use beyond drafts")
and NFR-OFF-04 and NFR-OFF-05 for retail sales only. Every other money action, and every lending action, stays online only.
Migration `V86` (main has `V80` to `V84` merged; agreed merge order: `V85` shop logos #465, `V86` this, `V87` barcodes
#470, `V88` referrals #467, `V89` existing loans #475, `V90` modules #474, `V91` the data download #478; payments #315
and #318 after `V91`).

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
   the time of the last full refresh by the server's clock (the `Date` header of its first answer; the phone's clock only when
   the server sent none). It refreshes when the app comes online, on sign-in and every 15 minutes while online. Offline, the
   sale screen offers the saved customers list and a typed buyer's name for a cash, mobile money or bank sale, sent as
   `customer_id` or `buyer_name`.
   Price lists are not cached: a seller may not read them (`retail.pricelist.manage`), so an offline sale is priced at the
   retail price or the pack price on the device, or a price typed under the rules of decision 11.
3. **The outbox.** A sale saved offline, or whose answer was lost, is added to the outbox store (never written over: a second
   add under the same client sale id leaves the first as it is) with its whole request, a
   `client_sale_id` (a UUID made when the draft starts), an `Idempotency-Key`, `occurred_at` (the device time of Save),
   `prices_as_of`, the person, the branch and a provisional receipt number (`OFF-` and the first 8 characters of the client
   sale id). The screen shows the provisional receipt marked "Not yet sent"; the server's number replaces it after sync. A write to
   IndexedDB counts as saved only when its transaction completes (an abort or an error after the request succeeded rejects
   it, and the sale is not shown as kept), and the app asks the browser for persistent storage
   (`navigator.storage.persist()`) when offline selling is mounted, so the outbox is not cleared under storage pressure.
4. **Sync.** One sale at a time, oldest `occurred_at` first, never two requests in flight, and only one tab of the app at a
   time sends an outbox (a Web Lock named for it, taken only if free; a BroadcastChannel where Web Locks are missing); the
   tab that holds it reads the outbox again first, and the others show what it did. A network failure, a 5xx, a 409
   or a 429 backs off (5 s, 15 s, 1 min, 5 min, then every 15 min) and keeps the order. A sale the server answers without
   taking it (a 5xx, a 409, a 429) 8 times, or for 24 hours, is marked failed (`not_taken_after_retries`) and reported like a
   refusal, so it never holds up the sales behind it; a try with no answer at all (no network) does not count. A 401 tries
   one session refresh; a refresh that got no answer is a try to do again later; a refused refresh stops the engine and the
   header asks the person to sign in. The engine goes on whenever a new access token arrives (a sign-in or any refresh). A 2xx removes the item and keeps the server's sale
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
   The sale stores `request_hash`, the SHA-256 of a fixed canonical text of what the sale is (the branch, the payment
   method, the credit buyer's `customer_id`, and per line the item, the pack, the quantity and the price), never of how it
   was delivered (`occurred_at`, `recorded_offline`, `prices_as_of`) or of a field added later: a later send under the same client sale id with a different request is 422
   `client_sale_conflict` and records nothing. Once an answer is lost the sale form is locked: Try again sends the same
   request with the same key, and Keep it on this phone queues that same request (dated when it was first sent, marked
   offline) with a new key, except a credit sale, which is only tried again (decision 1: the server refuses a credit sale
   recorded offline); changing the sale means clearing it, which starts a new client sale id, after a confirmation.
6. **When it happened.** `occurred_at` is optional. When given, the sale is dated the day of `occurred_at` in the business's
   time zone (a `sale_date` that disagrees is 422 `sale_date_mismatch`) and `occurred_at` is stored on the sale. Up to 5
   minutes ahead of the server clock is clock skew and is recorded as the server's now. Further ahead, an online request is
   refused (422 `occurred_at_in_future`); an offline one is recorded at the server's now and flagged `clock_ahead`. An offline
   sale whose `occurred_at` is more than 2 minutes earlier than its `prices_as_of` (the server's time when the phone last
   saved its prices; a `prices_as_of` later than the server's now is taken as now) had a phone clock behind: it is recorded at the server's now and flagged `clock_behind`, so a wrong phone clock never makes it
   `period_closed`. Retail has
   no daily close; the bound in the past is the accounting period: a sale dated in a closed month is refused by the ledger
   (`period_closed`) and becomes a failure (decision 7), never a silent drop. If a daily close is built later it adds the same
   refusal.
7. **Clashes, recorded and flagged, never dropped.** A request with `recorded_offline: true` (which needs `client_sale_id`
   and `occurred_at`) is a sale whose money was already taken. For it, four checks that refuse an online sale record the sale
   and write a row in `retail_sale_clashes` instead: `stock_short` (the sale takes the balance below zero, or into stock kept
   for orders; the movement is written and the balance may go negative), `item_inactive` (switched off since), `pack_inactive` (the
   pack size sold was switched off since, or pack sizes were turned off for the business; the line is recorded in that pack), `price_changed`
   (the device charged the price it had saved, and today's price, or with price lists on the resolved price, differs; the line
   keeps the device price as an override whose reason names the offline sale and `prices_as_of`, or a strict list has no
   price), `price_typed` (decision 11: a typed price the online rules would not have taken)
   and `below_cost` (the price is not above cost without `retail.price.below_cost`; the clash words never carry the cost).
   `clock_ahead` and `clock_behind` (decision 6) are the clock kinds. A push notice `retail.sale_clash` goes to every holder of `retail.offline.resolve` at the
   branch (the administrators, the owner among them, and the branch's Supervisors), once per sale. The sale page shows its
   clashes; `GET /retail/sale-clashes` lists the open ones; `POST /retail/sale-clashes/{clash_id}/resolve` closes one with a
   note, audited. Resolving changes nothing else: a stock count or a correction is done the usual way.
8. **Failures, kept and decided by a manager.** A refusal for any other reason (a closed branch or period, a lost
   permission, an unknown item, a credit sale, a validation problem) leaves the sale in the outbox, marked failed, with the
   server's message in plain words. The device reports it with `POST /retail/offline-failures` (the request, the code and the
   message; `retail.sale.create`; idempotent on the client sale id), so a manager sees it on their own phone. A holder of
   `retail.offline.resolve` at the branch, in the sync sheet, either asks for it to be sent again after fixing the cause
   (`.../retry`: the seller's device sends it on its next sync, with the same client sale id and the same
   `Idempotency-Key`: a refused request leaves no key row, since the key row and the sale commit or roll back together, so
   the key is free to send again) or discards it
   with a reason of at least 5 characters after a confirmation (`.../discard`, audited `retail.offline_sale.discarded`). A
   discarded failure is marked discarded on the seller's device on its next sync, shown for a day and never sent again. The
   server refuses a sale whose client sale id belongs to a discarded failure, before any other check of the sale, with 422
   `offline_sale_discarded`, whatever key it carries; the device marks it discarded on that answer too. A failure later recorded as a sale is closed by
   that sale in the same transaction. The seller may send their own failed sale again at any time. A seller or a Supervisor never decides (retry or
   discard) a sale they made themselves: 403 `own_offline_sale`. An administrator (`tenant_admin`, the Owner included) may
   discard their own, never ask for it again, and only with a second written reason (`self_discard_reason`, at least 5
   characters and not the first reason again; else 422), audited `retail.offline_sale.self_discard` with both reasons (decision
   11 d). A failure whose client sale id already has a sale (a refusal reported for a sale whose answer was lost) is written,
   or closed, as `recorded` with that sale, and is never discarded: 409 `offline_failure_decided`. A send of the sale locks
   the failure row before it checks for a discard, so a discard and a send of the same sale at once end with exactly one of
   them.
9. **Sign-out safety.** Signing out while anything is queued or failed asks first ("3 sales are waiting to sync. Sync now,
   or keep them on this phone and sign out?"). The waiting sales stay in the person's own outbox on this device and are sent
   when the same person signs in here again; the catalogue copy and the person's saved profile are cleared at sign-out. Another person on the same device
   never sees or sends them, but is told "2 sales from another person are waiting on this phone", and whenever any session
   on that phone is online it sends a short summary of them (client sale id, branch, total, `occurred_at`, the seller) to
   `POST /retail/offline-waiting`; managers see them in the sync sheet as "waiting on a phone" (`GET
   /retail/offline-waiting`, `retail.offline.resolve`, branch scoped) until the sale is recorded or its refusal reported,
   which removes the summary. Nothing is posted from a summary. Reopening the app with no network after a sign-in shows the last profile of the last person
   signed in on this device, read from IndexedDB, with only the sale screen and the outbox working; anything needing the
   server says it is offline. The refresh cookie is never read or kept by the app.
10. **Status in the header.** A dot in the staff bar on every staff screen: green online, grey offline, amber while syncing,
    with the state in words beside it (Online, Offline, Syncing; never colour alone) and "3 waiting to sync" ("3 waiting" on a
    narrow screen) when the outbox is not empty and "1 failed" when one is failed. Tapping it opens a sheet
    listing queued and failed sales (time, branch, total, the reason of a failure), a Sync now button and, for a holder of
    `retail.offline.resolve`, the failures other people reported. It is mounted in `frontend/src/areas/staff/route.tsx`, not in
    the shell, and is drawn only for a person who holds `retail.sale.create`.
11. **Product owner decisions.** (a) Offline sales follow the same price rules as online (decided 22 Oct 2026). On the phone, a
    person without `retail.price.override` cannot type a price offline: only the catalogue or pack price saved on the phone is
    used; a holder types another with a reason of at least 5 characters, as online. On the server, a device price stands when
    it equals the catalogue or pack price in force at `prices_as_of` (from `retail_price_history`) or today's, or when the
    seller holds `retail.price.override` at the branch and gave a reason (an override with that reason, as online). Any other
    device price is recorded as charged with a `price_typed` clash for a manager to review, never silently. A saved price that
    changed since is kept and flagged `price_changed`; a sale is never repriced. A price not above cost stays a `below_cost`
    clash. (b) An offline sale is never a credit sale. (c) A sale
    refused for a closed branch or a closed month is kept on the device and decided by a manager, never recorded against the
    rule. (d) A seller or a Supervisor cannot discard their own failed sale. An administrator (an owner or administrator,
    `tenant_admin`) may, with a second written reason, audited as `self_discard` (Hillary's default, decided 22 Oct 2026,
    review of #463). (e) Waiting sales survive a sign-out on that device.

## Consequences

**Better:**

- A shop keeps selling through a dropped connection and every sale it takes reaches the books once.
- A replay after any delay, a lost answer, a retried tap or two devices racing cannot make two sales.
- Every conflict the server finds is written down and told to the people who resolve it.

**Worse:**

- Stock can go negative for a short time, which ADR-020 decision 4 refused without exception; it is flagged, not silent.
- A seller who sets `recorded_offline` by hand bypasses the price floor and the stock check; each such sale is flagged,
  noticed and audited, which is the control.
- The device holds the catalogue with retail prices and the customers list of a business. It is cleared at sign-out, with
  the person's saved profile.
- Opening the app with no network uses the last person's saved profile without authenticating them on the device: anyone
  holding the unlocked phone can ring up sales as that person until it is online again (the server then needs a valid
  session to take them). A device PIN is a later option.

**Watch for:**

- A device that stays offline for weeks: its sales still post, dated when they happened, but a closed month refuses them.
- Many clashes at one branch, which points at a phone that sells offline most of the time or at misuse of the flag.
- The size of the catalogue copy for a business with tens of thousands of items.

## Related ADRs

- ADR-009 (the PWA; amended for retail sales here), ADR-020 (retail, decision 4 on stock), ADR-033 (price lists and customer
  accounts), ADR-050 (push notices), ADR-055 (retail roles and the Supervisor).
