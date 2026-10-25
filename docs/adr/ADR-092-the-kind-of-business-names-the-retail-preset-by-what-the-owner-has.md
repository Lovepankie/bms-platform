# ADR-092: The kind of business names the retail preset by what the owner has

## Status

Proposed (issue #424, owner request of 21 October 2026 and pilot feedback of 25 October 2026). Amends ADR-033 decision 2 (the
`shop` or `trade` profile) and the menu of ADR-086 (a Wholesale group). Builds on ADR-048 (a store is a branch flag). Migration
`V103`. Renumbered at merge if needed.

## Context

The owner asked whether the app shows the difference between retail and wholesale. It does not: the wholesale tools (price lists
and customer prices, quotations, invoices and delivery notes, credit to shops, packs, purchasing) are switches of the one retail
module (ADR-033), and nothing names them as wholesale. On 25 October the pilot business owner read "Shop" and "Trade" on Retail
settings and asked for "another business kind of many stores then also many shops". Trade already allowed that, but its name did
not say so, and since ADR-048 any business can mark a branch as a store without a capability at all.

Options: a second setting, "business type", beside the profile; renaming the two profiles; or turning the one profile into a
choice named by what the owner has.

## Decision

1. **One setting, not two.** The `profile` of the settings group `retail.capabilities` stays the only place the kind is stored
   and becomes one of four values, named by what the owner has: `shops` ("Shops only": one or more shops that sell over the
   counter; the default), `stores_and_shops` ("Stores and shops": stores that receive stock and send it to shops, any number of
   each), `wholesale` ("Wholesale": selling in bulk to other shops) and `wholesale_and_shops` ("Wholesale and shops"). The
   screen asks "What kind of business is this?" and says that each branch is set as a store or a shop under Branches (ADR-048),
   with the link.
2. **Still a preset, never a mode.** A kind sets six switches on or off, the ones it governs: `units`, `price_lists`,
   `customer_accounts`, `documents`, `purchasing` and `transfer_receipt` (`BusinessTypes.GOVERNED`). Shops only turns them all
   off; Stores and shops turns on the two-step stock move; Wholesale turns on the five wholesale tools; Wholesale and shops turns
   on both. `branch_roles` and `returns` belong to no kind and a choice leaves them as they are. Every switch stays individually
   editable after the choice, a switch sent in the same change wins, and the module reads the switches, never the kind
   (ADR-033 decision 2 and its watch item). The trade preset left documents and purchasing as they were; a wholesale business
   uses both, so the kind now sets them, and the review before saving lists every switch the choice changes.
3. **Existing businesses keep every switch.** Migration `V103` renames the stored value only, judged from the switches:
   either old name becomes `wholesale_and_shops` when one of the five wholesale tools is on; otherwise `trade` becomes
   `stores_and_shops`, and `shop` becomes `stores_and_shops` with the two-step move on, else `shops`. So a row an older server
   wrote back is never mislabelled. No switch changes, the
   settings version moves on so an open settings screen reads the change again, and a business that never saved Retail settings
   has no profile and reads as Shops only. The server reads a `shop` or `trade` row written by the previous release during the
   deploy the same way (`BusinessTypes.fromLegacy`) and refuses the old names on a write (422).
4. **A Wholesale group in the grouped menu (ADR-086).** Between Money and Lending, shown while one of its rows is open to the
   person: Price lists, Credit to shops, Quotations, Invoices and delivery notes, Cartons, boxes and other packs (the Items page,
   while packs are on) and Purchase orders. Each row follows its switch and its permissions as before, so the group reads the
   switches, never the kind; a business of shops only has no Wholesale group. The Retail page lists the same pages under a
   Wholesale heading. A one-time tour shows an administrator with price lists on where the pages are.
5. **The operator sees the kind.** `platform_tenant_overview` gains `profile_groups`: the stored settings groups, of the modules
   the business has on, that hold a profile. The server reads each through its module's own group (`normalise`), so a legacy
   `shop` or `trade` reads as the kind it is, and sends the first as `business_type`. The core names no vertical; only retail
   has a profile today. The operator's
   list and Business page show it in the business's words, "Shops only (not chosen yet)" when none was saved.
6. **Permission and audit unchanged.** `core.settings.manage` changes the kind; the change is audited as
   `retail.capabilities.changed` with old and new values, like any switch.
7. **Out of scope (parked by the owner).** Plans, pricing, brochure prices and Pesapal: no plan includes or excludes the wholesale
   tools because of the kind. Choosing the kind at sign-up is not built; a new business chooses it in Retail settings.

## Consequences

**Better:**

- The owner picks by what they have, in their words, and is told where stores and shops are set.
- Wholesale is visible as wholesale in the menu, on the Retail page, in the tours and to the operator.
- No business changes behaviour: every switch is kept, and still only the switches decide what a business can do.

**Worse:**

- Choosing a kind now switches documents and purchasing on or off; an administrator who had them on and chooses Stores and shops
  turns them off (the review before saving says so, and nothing recorded is deleted).
- Two names for one page: the menu row "Credit to shops" opens the page titled Customer accounts.
- The kind is a label after its preset: a business can choose Shops only and switch price lists on, and then has a Wholesale group.

- During a rolling deploy (ADR-083) the previous release reads a renamed kind as unknown and writes `shop` back on its next save
  of Retail settings. No switch changes, and the new server judges that `shop` from the switches (decision 3), so the kind reads
  as before unless a business of stores and shops had none of the two-step or wholesale switches on.
- Old app copies (ADR-078 keeps their files for 14 days) update themselves since ADR-088, so no minimum build is set for this
  change; until one does, it shows the old two choices and a save of one is refused with 422.

**Watch for:**

- `V103` rebuilds `platform_tenant_overview` from its V79 body. `V102` (pull request #585) does not touch that function, the
  `TenantResponse` record or `tenantRow`; a later migration that changes the function must start from the V103 body and keep
  `profile_groups`.

- A feature that reads the kind instead of a switch; the module and the screens must read switches only.
- A new wholesale tool must join `BusinessTypes.GOVERNED`, the presets, the Wholesale group and `WHOLESALE_SCREENS` together.
