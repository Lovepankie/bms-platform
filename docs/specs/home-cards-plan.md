# Custom Home cards: plan

**Status:** Plan (issue #684; decision in ADR-114, Proposed) · **Build:** #733 wave 15, after the shop words build (#683,
`docs/specs/shop-words-plan.md` once merged) · **Reads with:** `frontend/src/areas/staff/home-cards.tsx`, ADR-072, ADR-076

Each person chooses which Home cards show and in what order, from a default the owner sets. ADR-114 is the decision; this file
is what main has, the steps, their size and their order.

## 1. What main has today

- `frontend/src/areas/staff/home.tsx`: Home draws the first-items card (issue #615), then `HomeCards`, then the Members list.
- `frontend/src/areas/staff/home-cards.tsx`: `homeParts(me, branch)` decides which figures the session may see (ADR-055,
  ADR-076); `HomeCards` draws, in a fixed order, Shop, Takings, Customers who owe, Top items and Shops (the shop's cards, from one
  read of `GET /retail/reports/home`), then Lending.
- No card keys, no stored layout, no Arrange mode. The only per-person choices kept today are the list views of the retail lists,
  in the phone's storage (`list-chain.tsx`), which do not follow a person to another phone.

## 2. The cards and their keys

| Key | Card | Shown today when | Notes |
|---|---|---|---|
| `shop` | Shop: today's sales, low on stock, open orders | `homeParts`: `sales`, `lowStock` or `orders` | |
| `takings` | Today's takings by way of paying | `takings` and the server sent it | |
| `credit` | Customers who owe | `sales` and the server sent it | |
| `top-items` | Top items today or this week | `sales` and the server sent it | |
| `branches` | A line per shop | `branches` and the server sent it | |
| `lending` | Lending: due today, in arrears, waiting for approval | `loans` or `approvals` | |
| `members` | The Members list with its search | `lending.members.read` and the lending module | Moves from `home.tsx` into the card list |

The first-items card is not in the list: it stays first while the shop has no items (ADR-114 decision 1).

## 3. Steps, size and order

Each step is one pull request. Sizes: S up to a day, M a few days, L a week or more.

| Step | What | Size | Migration | Depends on |
|---|---|---|---|---|
| 1 | Card keys: `HOME_CARDS` in the PWA, the same list on the server, the pinning test; `HomeCards` draws from an ordered list (the app's order), with Members moved in. Nothing a person sees changes | S | no | none |
| 2 | `home_layouts` table with row level security; `GET`, `PUT` and `DELETE /api/v1/home-layout` and `/home-layout/default`, with `If-None-Match: *` for the first save and `If-Match` after; `user_id` from the session; unknown keys dropped, never refused; audit of the default | M | yes (one) | step 1 |
| 3 | Arrange home: Move up, Move down, Show or Hide, Done, Cancel, Reset, Arrange for everyone, every control at least 48 by 48; saving keeps the entries of cards not visible now in place; Home drawn from the layout that applies; the last layout kept for offline under the tenant and the user; the page tour step; layout suite at 360 and 412 pixels | M | no | step 2, and the shop words keys for the card names |

Total: M to L, about one to two weeks for one builder. Only step 2 adds a migration, at the next free number when it merges
(`AGENTS.md`); it is the one migration of its wave.

## 4. Tests the build adds

- Unit:
  - resolving a layout (own, then the business default, then the app's order);
  - a card the person may not see is never drawn even when stored as shown;
  - a card visible now but missing from the stored list is drawn last, shown;
  - unknown and retired keys are ignored;
  - saving from Arrange mode keeps the entries of cards not visible now in place: a stored list with Takings hidden, rearranged
    at a branch where Takings is not drawn, still has Takings hidden and between the same neighbours.
- IT:
  - a key twice and too many entries refused in plain words;
  - version skew: a stored retired key is never returned by `GET`; a `PUT` with an unknown key (a newer PWA) is saved without
    it and answers the layout as stored; a `PUT` of exactly what `GET` returned always passes;
  - first save with `If-None-Match: *` (412 when a row exists), change and Reset with `If-Match` (412 when stale), 428 without the
    header;
  - `user_id` comes from the session: a body or path naming another user changes nothing of theirs;
  - the default needs `core.settings.manage`; the default is audited and a person's own layout is not; tenant isolation of
    `home_layouts`.
- DOM:
  - Move up and Move down change the order; Hide takes a card away and Show brings it back; Reset returns to the business
    default; Cancel keeps the old order; Arrange for everyone shows only to an owner;
  - the offline copy is stored under the tenant and the user: a second person signing in on the same phone offline sees the
    app's order, not the first person's.
- Layout suite: at 360 and 412 pixels, every control of Arrange mode (Move up, Move down, Show or Hide, Done, Cancel, Reset,
  Arrange for everyone) is at least 48 by 48 CSS pixels and nothing overflows.

## 5. Open points for the owner

- Should the owner be able to lock a card in place for everyone (for example Shop always first)? The plan says no: a default,
  not a rule.
- Should the retail home tiles (`/staff/retail`) be arrangeable the same way? The plan leaves them as they are; the same table
  could hold a second list later under its own key.
