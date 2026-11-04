# ADR-067: Retail sales work offline: a catalogue and an outbox on the device that sync once, oldest first

## Status

Accepted (owner decision, issue #458, 22 Oct 2026; the offline price rules of decision 11 decided by the product owner,
Hillary, 22 Oct 2026). Amends ADR-009 (staff data "never cached for offline use beyond drafts")
and NFR-OFF-04 and NFR-OFF-05 for retail sales only. Every other money action, and every lending action, stays online only.
Migration `V86` (main has `V80` to `V84` merged; agreed merge order: `V85` shop logos #465, `V86` this, `V87` barcodes
#470, `V88` referrals #467, `V89` existing loans #475, `V90` modules #474, `V91` the data download #478; payments #315
and #318 after `V91`).

Amended by issue #812 (3 Nov 2026, the owner's feedback after comparing the old app screen by screen): decisions 12 and 13
below. The same outbox, waiting count and online dot now carry the other daily forms (expenses, savings on the till's and the
sales staff routes, banking, withdrawals, deliveries in Buy stock, Move stock, usage and damage), waiting for an answer as
long as a sale does (8 seconds), with the time to save on a cheap phone on 3G measured. No migration. Edits of a saved entry
(PR #832) and approval answers stay online only.

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
   refusal, so it never holds up the sales behind it; a try with no answer at all (no network) does not count. A 409
   `idempotency_in_progress` or `client_sale_in_progress` (an earlier request for the same sale has not finished) is
   always "try again later" with the same backoff, never a refusal, and does not count towards those 8 tries or 24 hours
   either (issue #769): the item is never marked failed, reported or offered to a manager to discard while its last answer
   was in progress, since the earlier request may still record the sale. A 401 tries
   one session refresh; a refresh that got no answer is a try to do again later; a refused refresh stops the engine and the
   header asks the person to sign in. The engine goes on whenever a new access token arrives (a sign-in or any refresh). A 2xx removes the item and keeps the server's sale
   number for the receipt. Any other 4xx (a 409 that is not "in progress" included) is a refusal: the item is kept, marked failed with the server's words, reported to
   the server (decision 7) and skipped, so the next one goes. Sync starts on the `online` event, on a successful health ping
   (`GET /healthz` every 30 s while the page is open), after a sale is queued and on Sync now. A ping with no answer
   within 3 s is pinged again at once, and two in a row count as offline (issue #769): on a weak mobile connection a slow
   answer means a sale would hang, and one slow ping on a cold link does not flip the state. For the same reason a cash,
   mobile money or bank sale sent online with no answer within 8 s, or lost on the network (fetch's `TypeError`; a 401 or
   any refusal is not, and with no session left it is never kept), goes to the outbox at once under the same
   `Idempotency-Key` and client sale id, with `occurred_at` (when Save was pressed), `prices_as_of` and `recorded_offline`
   added, and the seller gets the provisional receipt. `prices_as_of` is the oldest time the form read its items, or with
   price lists its resolved prices, from the server: what it priced the sale from, never the copy on the phone. An item
   picked or scanned from that copy is dated by the copy's time. When any line's read time is not known, no `prices_as_of`
   is sent at all, so the server holds each price to today's, never a later time. The oldest is taken by the moment each
   time names (parsed, so an offset or a time without milliseconds compares right, issue #776). An item read online is
   dated by the phone clock and an item from the copy by the server's, so a phone clock that runs slow can make an online
   read look older than the copy; the error is bounded by that clock skew. With no session left the
   form stays locked, since the request may have been recorded, and Try again sends the same sale after sign-in. So when the first request never arrived, the outbox's send hours later is a sale recorded
   offline, dated when it was taken. The answer that comes later is ignored on the phone. The server finds the sale by
   its client sale id, or, while the first request still holds the key, answers 409 `idempotency_in_progress` (tried
   again, never a refusal, `classify` in `outbox.ts`) and then replays from the key, whose hash leaves the delivery
   fields out (decision 5), so the sale is recorded once. A refused first request leaves no key row (the key and the sale
   commit or roll back together), so the outbox copy is never answered with that refusal.
5. **Sent once.** The request carries `Idempotency-Key` (replays within 7 days answer from the key row) and
   `client_sale_id`, stored on `retail_sales` with a unique index per tenant. The server looks for the client sale id
   before any other check, the key's included (issue #769), and for a request with a client sale id the key's request
   hash leaves out `occurred_at`, `recorded_offline` and `prices_as_of`, so an online request and the same sale sent
   from the outbox under its key are one request: a sale already recorded with it is answered as a replay (`Idempotent-Replayed: true`) when the
   same person made it, and refused with 422 `client_sale_id_reused` otherwise. A replay after the key expired, or with a
   new key after a lost answer, is therefore still the same sale. Two concurrent sends that both miss the lookup are settled
   by the unique index: the loser answers 409 `client_sale_in_progress` and its retry finds the sale. The online sale screen
   sends a `client_sale_id` too, so a sale whose answer was lost and that moves to the outbox can never be recorded twice.
   The sale stores `request_hash`, the SHA-256 of a fixed canonical text of what the sale is (the branch, the payment
   method, the credit buyer's `customer_id`, and per line the item, the pack, the quantity and the price), never of how it
   was delivered (`occurred_at`, `recorded_offline`, `prices_as_of`) or of a field added later: a later send under the same client sale id with a different request is 422
   `client_sale_conflict` and records nothing. The hash covers exactly `branch_id`, `payment_method`, `customer_id` and
   per line `product_id`, `unit_id`, `qty` (trailing zeros dropped) and `unit_price_minor`. It leaves out `sale_date`,
   `price_list_id`, `buyer_name`, `buyer_contact`, `due_date` and `credit_override_reason`, so a replay that changes only
   those is answered with the earlier sale, not a conflict; the outbox always resends the same body, so this never
   arises from the device. Once an answer is lost the sale form is locked: Try again sends the same
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
   11 d). A failure whose client sale id already has a sale made from the same request (the reported request's
   `request_hash`, decision 5, equals the sale's: a refusal reported for a sale whose answer was lost) is written, or
   closed, as `recorded` with that sale, and is never discarded: 409 `offline_failure_decided`. Sameness is decided by the
   request hash, never by the refusal code the device sends: a different request under that id stays open as a conflict,
   with no sale, whatever its code. A send of the sale locks
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
   server says it is offline. The refresh cookie is never read or kept by the app. Since only the logout call ends the
   server session behind that HttpOnly cookie, a sign-out that gets no answer notes a pending logout on the device
   (`localStorage`); the next start ends that session (it rotates the cookie for that one call and never shows the
   session) before anything restores one, and no refresh runs and the app stays on sign-in until it has. Those two calls
   share one 10 second limit, and a timeout counts as no answer: the sign-out stays pending, the old session is never
   restored, and the sign-in page says "Could not finish signing out. Connect to the internet and try again." with a Try
   again button, since the installed app has no address bar to leave a loader by. While Try again runs the sign-in form
   is shut, since that call rotates the last person's cookie and its answer must not land on top of a new sign-in's. A
   new sign-in on the device aborts any pending sign-out call still running and clears the note, and a call that began
   before a sign-in never touches the note after it. Where the note cannot be kept, sign-out with no network is refused: "Connect to the
   internet to sign out."
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
12. **The time to save on 3G (issue #812).** A daily entry of decision 13 waits for its answer as long as a sale does (8
    seconds, decision 5 and issue #769; the lead's decision on the review of #839: the sale's wait is not lowered). With no
    answer by then, or lost on the network, it goes to the outbox at once under the same `Idempotency-Key` and the same body,
    and the screen says it is saved on the phone; the late answer and the outbox's send are one record (a second request under
    a key still in progress is tried again later, then answered from the key). Measured in the layout suite
    (`layout/offline-forms.layout.test.ts`) in Chromium, the CPU four times slower, through the real API client to a local
    server on the DevTools profiles: about 1 second on 3G and about 2.5 seconds on Slow 3G (whose 2 second latency is most of
    it), both answered by the server. Nothing was over 3 seconds on 3G, so nothing was changed for speed.
13. **The other daily forms are kept on the phone too (issue #812).** Expenses (`POST /retail/expenses`), savings
    (`POST /retail/savings` and `/retail/savings/sales-staff`), banking (`POST /retail/bankings`), withdrawals
    (`POST /retail/withdrawals`), deliveries (`POST /retail/purchases`), stock moves (`POST /retail/transfers`) and usage or
    damage (`POST /retail/usage`) use the same outbox, in the one order by when each was made, mixed with the sales.
    (a) **The item.** An entry is an outbox item with its route, its body as the form built it and its form's
    `Idempotency-Key` (also its id on the device); a refused entry keeps its form's draft so it can be fixed. (b) **Made
    offline**, the body leaves out its date fields and carries `offline: {occurred_at, online_as_of}`: the server dates the
    entry the business day of `occurred_at`, judged as a sale is (decisions 5 and 6), with the lead's decisions on the
    review of #839, because `occurred_at` comes from the phone. `online_as_of` is required: the server's time at the
    phone's last answered ping of `/healthz` (its Date header, kept on the phone for every person, seller or not) or the
    catalogue's time, whichever is later; a phone never online with this app has neither, and the entry is refused with
    "The phone had not been online with this app before this entry was made, so its day is not known. Fix it on the phone
    to save it as an entry of today." A time ahead of the server is now. A time more than 2 minutes before `online_as_of`
    (a phone clock behind) is refused (422 `offline_clock_behind`). A time more than 7 days ago, the same limit as an offline
    sale (its key is answered for 7 days, decision 5), needs the backdate permission of #811 (`retail.entry.backdate`,
    the owner's, ADR-120) at every branch the entry touches and is kept on its own day for the owner; for
    anyone else it is refused (422 `offline_entry_too_old`) with "Only the owner can save it on its own day. Fix it on the
    phone to save it as an entry of today." and nothing is recorded. A closed accounting period stays refused by the ledger
    (`period_closed`) as for an offline sale: retail has no daily close (decision 6). A date field sent with the entry is
    not used: within the 7 days the person never chooses the day, the phone's clock does, so the backdate check of #831 does
    not apply to it; past them it is the owner's. Within the 7 days a stock move or usage may land on a day already banked,
    as an offline sale may; it shows in that day's records. Offline the forms show no date box. Buy stock's
    `purchased_on` is still required by its route and not used for such an entry. A banking's or withdrawal's time sent with
    it is not used either: the server sets it, which means the server judges it (the lead's decision on the third review of
    #839): the phone's `occurred_at`, a time ahead of the server made now and one behind refused, as above; a `banked_at`
    or `withdrawn_at` from the client is never trusted for an entry kept offline. **A closed day** (the lead's decision on the second review of #839: a day
    whose till is closed for the branch): retail keeps no till-close record of its own; the holder of `retail.till.close`
    ("Can close the till and do the banking") closes a day's till by banking it, so the record of a closed till is an active
    banking record on that day at the branch. An entry kept offline that moves a day's cash, an expense, savings, banking, a
    withdrawal, or a delivery paid in cash or by mobile money (the cash book reads it as money out of the till, third
    review of #839), that lands on a closed day before today needs `retail.entry.backdate` at the branch, at every branch
    a delivery stocks, and is otherwise refused (422 `offline_day_closed`, "That day's till is closed. Ask the owner to
    enter it."). The cash book answers the question (`ClosedTills` in `retail.stock`), so purchasing asks it without
    depending on the cash book. Today is never counted closed: today's till may be banked more than once, as online, so
    an entry made earlier today and sent after a banking today is kept (the lead kept this as it is on the third review).
    An expense kept offline that waits for approval is checked again when it is approved, with the approver's
    permissions: a till closed meanwhile makes the request stale unless the approver holds the backdate permission. A delivery on credit or paid from the bank, a
    stock move and usage take no cash from the till and are not checked. A seller's savings made on
    the last day of a month and sent on the first day of the next are kept on their day (the phone dated them within the 7
    days), unlike a date a seller would choose. **Audit:** every entry kept offline writes `retail.entry.recorded_offline`
    (the day, `occurred_at`, `online_as_of`, the day it was entered) in its transaction, so an owner can tell an entry the
    phone dated; an expense that waits for approval is audited only when it is approved and exists, since its approval
    request keeps the `offline` block. (c) **Lost online**, the same body goes again under the same key, always with when
    it was made (`offline`, the moment the save began; the lead's decision on the third review of #839). The server uses
    an explicit earlier day the owner chose online, with its reason (ADR-120, which asks the backdate permission for it),
    when the body has one, and `occurred_at` otherwise. The `Idempotency-Key` covers the request without
    its `offline` block, as a sale's key leaves its delivery fields out (#769): a request that arrived answers once, and one
    that never did is dated the day it was made, not the day the phone got it through. (d) **Sent.** Any 2xx records it,
    including the 202 of an expense that waits for an administrator (ADR-091), whose approval request carries the day the
    entry was made, so however late it is approved it stays on that day; the approval itself stays online only, as
    every maker-checker answer does. A 409 that is not a key still in progress (the savings suggestion changed, the day is
    saved already) is a refusal, not a retry. (e) **Refused.** The entry stays on the phone with the server's words; it does
    not hold up the entries behind it and is never reported to a manager (the server has no record of it): its maker taps Fix
    (its form opens on it with a new key), Send again, or Discard (asked first). Fix and Discard are not offered when the
    server may hold the entry (it gave up after its tries, or was still recording it): a new key, or the entry discarded
    and typed again, could record it twice, so only Send again, under its own key, is, with "This may already be
    recorded. Send again to check; it is never recorded twice."; Fix and Discard are offered once the server has refused
    that key. An entry sent online first whose answer was lost is treated so after its first refusal too (the request that
    went online may still arrive and record it under the key, lead's decision on the fourth review of #839): Fix and
    Discard appear once the server has refused the key again, after Send again. Two refusals is the accepted bound: the
    server stores no refused answer under a key, so in theory the request that went online could arrive later still, but
    by the second refusal it has been on the network for the 8 second wait, the outbox's first try and the person's Send
    again, and a request that late is not expected. Fix opens the form the entry was
    made in, under that form's own draft name (Buy again on its purchase). (f) **Who.** Whoever records one of these
    forms has offline support now, not only a seller: the outbox, the dot, the sign-out warning and the kept reads (ADR-116).
    Only a seller's phone fills the catalogue copy, which the item pickers of Buy stock, Move stock and usage search while
    offline. The lists these forms wait on are kept for reading: expense categories, beneficiaries, suppliers, the savings
    suggestion and the cash expected for banking. (g) **Edits stay online.** Editing a saved expense or delivery (PR #832)
    says plainly that it needs the internet and keeps nothing on the phone. (h) **Toasts** (#824): one when an entry is kept
    on the phone, one when it is sent. (i) Other people's entries left on a shared phone wait for them to sign in again; the
    managers' list of waiting sales (decision 9) stays a list of sales.

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
