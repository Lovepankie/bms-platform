# ADR-114: Each person chooses and orders their Home cards, from a default the owner sets

## Status

Proposed (issue #684, owner request of 29 Oct 2026). Plan only: nothing here is built yet. The plan, the size and the build
order are in `docs/specs/home-cards-plan.md`. The build is one later task with one migration (#733, wave 15), after the shop
words build of pending ADR-113 (#683), whose label keys name the cards in Arrange mode. Builds on ADR-072 (a Home card per
module), ADR-076 (the shop's summary cards on Home) and ADR-055 (what a seller may see).

## Context

Home shows a card per module the business uses and, for a shop, the summary cards after it (`frontend/src/areas/staff/home-cards.tsx`):
Shop, Takings, Customers who owe, Top items, Shops, Lending, and the Members list under them. The order is fixed in the code and
every card the person may see is drawn. Owners asked to choose which cards show and in what order, so each person sees first the
jobs they do most. Many users read little, so moving a card must not depend on dragging or on reading a long list.

## Decision

1. **Stable card keys.** Every Home card has a key that never changes meaning: `shop`, `takings`, `credit`, `top-items`,
   `branches`, `lending` and `members`. The PWA keeps the list beside the cards (`HOME_CARDS` in `home-cards.tsx`) and the server
   keeps the same list; a test pins the two equal, as `LENDING_APPROVALS` is pinned today. A new card gets a new key; a retired key
   is ignored where it is stored, and `GET` never returns it (the server drops retired and unknown keys when it reads a stored
   list), so what a PWA sends back after a `GET` always passes the checks of decision 6. The first-items card of issue #615 is not arrangeable: it stays first while the shop has no
   items and then goes away, as today.
2. **A layout is an ordered list of keys, each shown or hidden.** It is stored per person, with one default per business:
   `home_layouts (tenant_id, user_id, cards, version, updated_at, updated_by)`, `user_id` null for the business default,
   unique on the pair with `NULLS NOT DISTINCT` (PostgreSQL 15 and later, already used by `V76`), under the tenant row level
   security. The server always takes `user_id` from the session, never from a request body or path, so one person can never
   write another person's layout in the same business. `cards` is the ordered list of
   `{key, shown}`. No row means the next level: a person's own layout, else the business default, else the app's order of
   today. No backfill is needed and nothing changes for anyone until they arrange.
3. **Permissions still decide.** A layout only orders and hides; it never shows a card the person may not see (`homeParts`,
   ADR-055, ADR-076). Arrange mode lists only the cards the person may see now. Visibility is not fixed: it depends on the
   branch chosen, the modules switched on and what the server sent. So saving never drops what Arrange mode did not show: the
   saved list is the stored list with the visible cards rearranged, and every stored entry for a card not visible now (its key,
   its shown or hidden choice) stays in the same place relative to its neighbours. A seller who hid Takings at branch A and
   later arranges at branch B, where Takings is not drawn, still has it hidden back at branch A. A card that becomes visible
   later (a new permission, a module switched on, or a card added to the app) and is not in the stored list is drawn after the
   listed ones, shown, so it is never lost.
4. **Arrange home, with buttons.** At the end of Home, "Arrange home" turns each card into a row with its name, a Move up and
   a Move down button (an arrow icon and the words) and a Show or Hide switch. Every control in Arrange mode (Move up, Move down,
   Show or Hide, Done, Cancel, Reset to default and Arrange for everyone) is at least 48 by 48 CSS pixels, and the layout suite
   checks every one of them, not only the Move buttons. Done saves; Cancel leaves it as it was; Reset to default deletes the
   person's row. Dragging is not built; it can be added later on top of the same list. The
   card names come from the shop words keys (pending ADR-113), so a business that renamed a word sees its own word here too.
5. **The owner sets the business default.** A person holding `core.settings.manage` also sees "Arrange for everyone" in the same
   mode, which saves the business default. It changes what every person without a layout of their own sees, never what a person
   chose. No new permission.
6. **API.** `GET /api/v1/home-layout` (any signed-in staff user): the person's layout as it applies, and whether it is their
   own, the business default or the app's. `PUT /api/v1/home-layout` and `DELETE /api/v1/home-layout` for the person's own (the
   delete is Reset). `PUT` and `DELETE /api/v1/home-layout/default` for the business default (`core.settings.manage`). Each `PUT`
   takes an `Idempotency-Key` and either `If-None-Match: *` for the first save, when no row exists yet (412 if one does, so two
   first saves never race), or `If-Match` on the row's version to change it (412 if stale); `DELETE` takes `If-Match` too. A write
   without the matching header is 428. The body is checked: each key once (a key twice is 422), at most 50 entries. Version skew
   is handled by ignoring, not refusing: a key the server does not know (retired, or new in a PWA deployed before the server) is
   dropped from the saved list and the response returns the layout as stored, so the PWA redraws from it and a rolling deploy
   never makes a save fail. Changes to the business default are audited; a person's own layout is not (it changes nothing for
   anyone else).
7. **Offline.** The PWA keeps the last layout it read and draws Home with it offline, stored under the business and the person
   (tenant id and user id), so on a shared shop phone the next person never sees the last person's order; with no copy of their
   own they see the app's order. Arrange mode needs the network and says so.
8. **Sellers can arrange too.** Every staff user who sees Home may arrange their own, because each person does different jobs.

## Consequences

Better: each person sees their own jobs first; the owner can set a sensible default for the whole team; nothing changes for
anyone who never arranges; permissions keep deciding what is shown.

Worse: a support call has to ask whether the person arranged their Home (the Arrange mode shows "Your own order" or "The
business's order"); one more migration and one more small table; a person who hid a card may not find it again (Arrange mode
lists hidden cards with Show, and Reset brings every card back).

To watch: the order of cards on a phone at 360 pixels in Arrange mode (the layout suite checks it); keys that drift between the
PWA and the server (the pinning test of decision 1).
