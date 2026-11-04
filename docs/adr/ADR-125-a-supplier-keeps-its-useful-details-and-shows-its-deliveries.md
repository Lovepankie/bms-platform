# ADR-125: A supplier keeps its useful details and shows its deliveries

## Status

Accepted (issue #820, from the owner's old app compared screen by screen with BMS on 3 November 2026; FR-RET-90). Builds on
FR-RET-06 (suppliers and restocks), #146 (editing and switching off a supplier), ADR-039 (the one search), ADR-055 (a buyer
without `retail.profit.read` reads only their own restocks) and #817 (the Call, Text and WhatsApp links). Migration `V134`;
renumbered at merge if the order changes.

## Context

BMS kept a supplier's name and one contact. The owner's old app kept, for each supplier, an email, an address, the payment
terms, an account number, a supplier type, notes, the date added and the last delivery date, and the owner looked a supplier up
by name, phone or type before ringing them about a delivery. In BMS the deliveries of a supplier could only be found by
filtering Past purchases.

## Decision

1. **Eight optional details, all text.** `retail_suppliers` gains `email` (254), `address` (300), `country` (100), `website`
   (300), `payment_terms` (100, as typed, for example "30 days"), `bank_account` (60), `supplier_type` (60, a short free
   label) and `notes` (1000), nullable with no default, so V134 rewrites nothing and every existing supplier keeps working
   with every detail empty. They travel as `details` on the supplier; in a write an omitted detail is unchanged and an empty
   one clears it. The API checks the lengths first (422 in words); an email must look like `name@example.com` and a website
   must be a web address, with or without `http://` or `https://` (any other scheme is refused, so a link the page draws
   never runs anything).
2. **The date added is `created_at`, the last delivery date is derived.** `last_delivery_on` is the day of the newest
   restock naming the supplier that is not voided, read in the query and never stored or typed. It follows the reader's
   scope exactly as the restock list does (the branches where they may buy, and only their own restocks without
   `retail.profit.read`), so the date never tells of a restock the list would hide.
3. **A delivery is a restock.** The supplier's page lists its restocks (Buy stock) through `GET
   /retail/suppliers/{supplier_id}/deliveries`, the newest day first, paged by the keyset `(purchased_on, created_at, id)`,
   each with its number, day, the count of its lines in scope and whether it is voided, never an amount; each opens the
   restock's own page (`/staff/retail/restocks/$purchaseId`). A supplier of another business, or none, is 404. Goods received
   under the optional purchasing capability keep their own list, as before.
4. **Search by name, type or phone.** `GET /retail/suppliers?q=` matches the name and the type as text and the contact as a
   phone number through the one search of ADR-039, best matches first; without `q` the list is every supplier, as before, so
   every supplier picker is unchanged. `GET /retail/suppliers/types` gives the types already used, the most used first, as
   the form's suggestions.
5. **Who may see and edit is unchanged, except the bank account.** Reading and adding stay `retail.purchase.create`,
   editing stays `retail.catalogue.manage`. The bank account number is in a response only for a caller holding
   `retail.catalogue.manage`, and only such a caller may save one (422 `not_allowed` otherwise), so nobody types what they
   could not read back. The audit of an edit names the details that changed, never their values. Removing a closed
   business clears the details (ADR-090).
6. **Reaching a supplier.** The supplier's page draws Call, Text and WhatsApp for the phone (#817), a `mailto:` link for the
   email and a plain link for the website, opened in a new tab without the opener.

## Consequences

**Better:** the owner has the old app's supplier card back, finds a supplier as they remember it, and sees what came from
them and when without filtering a report. Nothing changes for an existing supplier or a supplier picker.

**Worse:** the last delivery date is worked out on every read of the list, one indexed subquery per supplier
(`retail_purchases_supplier`); fine for the few hundred suppliers of a shop. Two people with different scopes may see two
different last delivery dates for one supplier, by design.

**Watch for:** if the owner wants goods received counted as deliveries too, the list becomes a union of the two and the
cursor gains the kind. If a bank account ever needs more than a number (a bank, a branch, a mobile money account), it becomes
its own table rather than more free text.
