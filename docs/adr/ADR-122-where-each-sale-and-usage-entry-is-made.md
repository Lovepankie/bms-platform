# ADR-122: Where each sale and usage entry is made

## Status

Accepted (issue #826, asked for by the business owner in July 2026; FR-RET-87). Builds on ADR-067 (sales made offline), ADR-017
(branch scope) and ADR-090 (removing a closed business). Migration `V132`, numbered after the open claim of `V131` (#831);
renumbered at merge if the order changes.

## Context

The owner's old app recorded the phone's position on every sale and on every used or spoilt item entry, with its accuracy,
and showed the distance from the shop, each shop having its own position. It never stopped an entry; it kept the record so a
seller could not later deny making it, and so the owner could see a sale typed far from the shop. The owner asked for the same
here.

A position says where a named person was at a given time. It is personal data, so who reads it, what is kept and what is
never written down matters more than the feature itself.

## Decision

1. **What is kept.** A sale (online, or offline and sent later through the outbox of ADR-067) and a usage or damage entry take
   an optional `location` in the request: `{latitude, longitude, accuracy_m}` as the phone reports it. The server keeps it in
   `retail_entry_locations` (one row per entry, keyed by the entry's audit entity type `retail.sale` or `retail.usage` and its
   id, with the branch), in the same transaction as the entry. An entry without a location has no row.
2. **Never refused for want of one.** No location, a refused permission or a phone that gives no fix records the entry exactly
   as before. A location that is sent must be in range: latitude from -90 to 90, longitude from -180 to 180, accuracy 0 or
   more and at most 1,000 km, finite; otherwise 422 on the field (the phone only ever sends what the browser gave it, and drops
   anything out of range before sending). An incomplete location (a value missing) is taken as no location, never refused,
   so a sale from another client or an older build is recorded rather than turned into an offline failure.
3. **A shop's own location.** Each branch may have one in `retail_shop_locations`, set by the owner in Business set-up with
   "Use where I am now" (`PUT /retail/shop-locations/{branch_id}`, `core.settings.manage`) and cleared the same way. The
   distance always uses the shop's location as it is now.
4. **Who reads it: the owner and the administrators.** A new permission `retail.entry_location.read`, the tenant
   administrator's alone by default (the Owner of a business always holds that role, ADR-062) and scoped by branch like every
   retail permission; a business may grant it to a custom role. `GET /retail/entry-locations?source=sale|usage&id=...` (at most 100 ids)
   answers, per entry, `located` with the distance in metres and the phone's accuracy, `no_location`, or `shop_not_set`, with
   the words to show: "40 m from the shop", "3.2 km from the shop", "no location", "shop location not set". The coordinates are
   never returned, not even to the owner, and the shop list says only whether a shop has a location. No such entry reads
   `no_location`, the same as an entry without one, and so does an entry at a branch outside the caller's scope, so an id
   tells nothing. Sellers never see a location or a distance, their own included: no sale, usage or list answer carries one.
5. **Distance.** The haversine great-circle distance on a sphere of radius 6,371,008.8 m, rounded as people say it: under a
   10 m is "at the shop", under a kilometre to the nearest 10 m, then to the nearest 100 m shown with one decimal ("3.2 km", "1 km"), and
   from 100 km to the nearest kilometre.
6. **Never written down elsewhere.** No coordinate is logged, audited, put in a request fingerprint (the client sale id hash of
   ADR-067 and the Idempotency-Key hash of every sale and usage entry leave the location out, every other field staying
   covered, so a resend from a phone
   that has moved is the same sale) or kept in a refused offline sale's stored request, which a manager reads (the field is
   removed before it is stored, so a resent refused sale has no location). Setting or clearing a shop's location is audited
   as `has_location` true or false only.
7. **The phone asks once, with a plain reason.** Before the first sale or usage entry the app explains "The owner asks the app
   to note where each sale is made, so a sale can be checked later. Only the owner and the business administrators see it." and then asks the
   browser. The answer is remembered on the phone; a refusal is never asked again and never blocks anything. A fix is taken
   with a short time limit and a cached position of up to two minutes, so a sale never waits more than a few seconds.
8. **Removal.** Removing a closed business (ADR-090) deletes every entry location and shop location
   (`EntryLocationErasure`, area `retail_locations`, each delete naming the bound tenant as well as relying on RLS). `bms_app` may insert, read and delete entry locations, never update one.

## Consequences

**Better:** the owner sees, on the sale and on the usage or damage entry, how far from the shop it was made, in words, as the
old app showed; a seller cannot later say a sale was not theirs or not made at the shop; nothing is blocked when the phone
says nothing.

**Worse:** another piece of personal data is kept for as long as the business keeps its sales. A phone indoors often reports a
poor fix, so a distance of a few hundred metres is not proof on its own; the screen shows the phone's accuracy next to it. A
shop moved after the fact changes the distance of its past entries. A refused offline sale that is resent loses its location.

**Watch for:** any new read that joins `retail_entry_locations` must keep to `retail.entry_location.read` and never return the
coordinates; a report or export of distances needs its own decision. A request to show staff their own locations, or to keep
positions on a timer rather than per entry, is a new decision, not an extension of this one. A custom owner's role gets the
permission only by a deliberate grant.
