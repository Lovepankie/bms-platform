# ADR-086: One grouped menu, a drawer on a phone and a fixed sidebar on a laptop

## Status

Proposed (owner decisions D38 and D44 of 25 Oct 2026, issue #564; the owner approved the picture in the issue as drawn, kept as
`docs/ui/grouped-menu-564/menu.html`), accepted on merge. Amends the menu decisions of issue #455 (the side menu: the sidebar that
folds to its icons and the retail shortcuts as a group of at most five under Retail) and issue #471 (the headed, foldable Shop and
Lending sections, FR-UXN-04). Keeps the bottom tabs of issue #539 (FR-UXN-08) as they are, and the Home cards and More page of
ADR-072, which still head their parts by module. Frontend only: no migration, no API change, no permission added.

## Context

The staff menu grew by module. A shop owner saw Home, Approvals, Reports, a Retail item with up to five shortcuts under it (the
cap made Buy stock and Count stock vanish for an owner whose cash book shortcuts came first), SMS, Receive payments, Users,
Notifications, Feedback and Business set-up; a business with lending too got two headed sections that fold. On a laptop the
sidebar could fold to its icons, and below 1024px it started folded. On a phone there was no menu at all since issue #539: the
bottom tabs held the daily jobs and the More page everything else. The owner asked for one drawer-like menu, grouped the way a
shop owner thinks, the same on a phone and a laptop (D38), and approved the picture in the issue (D44).

## Decision

1. **One list in groups, in this order:** Home; **Today** (Sell, Orders, Approvals); **Stock** (My stock, Buy stock, Count stock,
   Move stock, Items and categories); **Money** (Cash book, Expenses, Customers who owe, What I owe suppliers, Savings and
   advances); **Lending** for a business with lending, after Money (Loans, Members or Collateral, Savings, Investments, Insights,
   and Loan reports when the shop is on too); **Business** (Reports, Staff, Receive payments, SMS, AI assistant, Business set-up).
   Built once in `staffMenu` (`frontend/src/areas/staff/menu.tsx`) and drawn by the sidebar and the drawer alike.
2. **Every page the session may open is listed, by the same permission rules as before**, and nothing else; a group with no rows
   is left out. A shop row opens the first of its screens the session may use (Cash book: the cash summary, else banking, else
   cash withdrawals; Customers who owe: credit sales, else customer accounts; Savings and advances: savings, else advances).
   There is no cap: the five retail shortcuts, the Retail item they hung under and the headed Shop and Lending sections go.
3. **Reports opens the shop's Retail page** (`/staff/retail`: the owner's figures and every shop page as tiles), so every shop
   page stays one tap from the menu. A lending business without the shop keeps its Reports page there; with both, that page is
   Loan reports in the Lending group.
4. **Top and foot.** The top shows the shop's logo, else the business's own logo (ADR-068), else the business's initials, never
   the platform's logo or name, with the business name, the person's role (Owner for the owner, else the first role) and, on a
   full-width row under them, the branch picker. From 720px the top bar no longer repeats the logo, the business and the branch:
   it keeps Search and the profile button. The foot holds Notifications, Help and feedback (the Help sheet with its tours and
   Send feedback), then the person's initials, name and role, and Sign out.
5. **Layout.** From 720px the menu is a fixed sidebar of 264px that no longer folds; the per-device choice `bms.sidebar` and the
   per-person folded sections `bms.menu.sections.<user>` are no longer read. Below 720px the menu button at the top left of the
   top bar opens it as a drawer of at most 320px over a dimmed page, keeping everything #455 gave the drawer: it closes on a tap
   outside, Escape, a left swipe or a chosen link, traps focus and gives it back, sets `aria-current`, respects the iPhone's safe
   areas, and opens for a tour step that points into it. The bottom tabs stay.
6. **Look (the product-ui-craft rules).** Rows of 48px with one line icon; the current row has a tinted background
   (`--color-accent-tint`) and brand-colour text, no side stripe; group headings are small muted text; counts (the open orders,
   "N low" on My stock) are muted figures on the right, read with the row, never coloured badges; the drawer's is the only shadow;
   no gradients, no emoji. Names come from the glossary (`WORDS`), so the menu, the tabs and the page titles agree.
7. **Rows of sessions the picture does not show, signed off by the owner (25 Oct 2026, on pull request #569).** The picture shows a
   shop owner without approvals, lending, payments, SMS or the AI assistant. Receive payments, SMS and AI assistant are rows of
   Business where their permission allows, as issue #564 says; Approvals is a row of Today, since pending approvals are a daily
   job; Loan reports joins Lending for a business with both modules (decision 3). These are part of this decision, not a
   departure from the picture: a session like the one drawn sees exactly the drawn rows. As the More page (#539, #544) lists the
   menu in its order, Approvals there moves from the top card to the Business card.
8. **The business name comes from the business, never the platform.** `/me` carries no business name, so the top of the menu
   takes the business's own name and logo from its branding (`GET /branding`, `display_name`, ADR-068), the source the top bar
   uses on a business's host (`useBusinessName`: the loaded branding's non-empty `display_name`, never the shell brand's fallback
   name). Off a business's host, while its branding loads, when `/branding` fails (an error answer or no network), and when the
   branding carries no `display_name` (null, empty or blank), the top shows the same plain tile and no name, never the product's
   or the platform's name; the role and the branch still show. The Rincol logo shows only on the operator side and the platform's
   own pages. The owner accepted this source on pull request #569; moving the name to `/me` is an API change left to a later pull
   request.

## Consequences

**Better:** the owner finds Buy stock, Count stock and the cash book in the same place on a phone and a laptop; nothing a session
may open is hidden by a cap; the menu reads as the shop's day (sell, stock, money) rather than as the product's modules; one code
path draws both layouts, so they cannot drift apart.

**Worse:** the sidebar takes 264px on a 720 to 1023px screen, where it used to start folded to 64px; the person's choice to fold
it, and their folded sections, are dropped without a migration of the stored keys (they are simply no longer read). The menu reads
the Home summary on every page (one request held fresh for a minute, shared with Home and the My stock tab). Notifications has no
count yet: there is no list of unread notices to count.

**Watch for:** a new staff screen must take a row in a group (or be reached from a hub such as the Retail page, the Money page or
Business set-up), and the tours that point at menu rows (`retail-nav` on Today, `nav-feedback` on Help and feedback) must move
with them. If the 720 to 1023px width proves cramped, a narrower sidebar there is a design change, not a return to folding.
