# Design system

**Status:** Draft · **Chore:** #95, #106 (visual polish), #429 (light and dark theme), #455 (phone side menu), #471 (menu sections and Home cards) · **Chapter:** `docs/sdd/11-member-channels.md` section 11.2.6 · **Builds on:** #87 (tenant branding, FR-TEN-08)

The look of the PWA: calm, trustworthy business software in Rincol Tech blue. One design layer serves the
landing page, the sign-in family, the staff area, every retail screen, the member portal and the platform
placeholder. Screens use class names from this catalogue and never write a raw colour, size or shadow.

## Where it lives

| File | Holds |
|---|---|
| `frontend/src/app/theme.css` | The tokens (CSS custom properties), after the shell's `--brand` pair from #87 |
| `frontend/src/app/ui/index.css` | The entry: the Open Sans face, then every file below, imported once by `main.tsx` |
| `ui/base.css` | Reset, typography, links, focus ring, `.lead`, `.muted`, `.hint`, `.eyebrow`, `.visually-hidden` |
| `ui/layout.css` | The page frame around the shell (brand bar with its `.header-actions` group at the top right, content column, footer), `.page-header`, `.stack`, `.cluster`, `.grid-auto` |
| `ui/buttons.css` | Buttons |
| `ui/forms.css` | Labels, inputs, selects, choice rows, fieldsets, `.form-stack`, `.form-actions`, `.field-hint`, `.field-error`, `.input-code`, `.secret-box`, `.codes-box` |
| `ui/surfaces.css` | `.card`, `.card-muted`, `<dialog>` and `.sheet` |
| `ui/tables.css` | Tables and `.table-wrap` |
| `ui/feedback.css` | `.alert-*`, `.badge-*`, `.empty-state`, `.loading`, `.spinner`, `.skeleton`, `.toast`, `.toast-success`, `.toast-error`, `.toast-info`, `.toast-icon`, the header refresh button (`.refresh`, `.refresh-status` readable by assistive tech but off screen below 720px, `.refresh-problem` visible at every width) |
| `ui/illustrations.css` | The paint classes of the illustrations (`.ill-*`), the state components (`.empty-state-art`, `.status-panel`, `.brand-loader`, `.skeleton-list`) and the shared keyframes |
| `ui/navigation.css` | The staff bar, the side menu: the sidebar from 720px (`.nav-layout`, `.side-nav`) and the phone drawer (`.menu-button`, `.side-menu`) |
| `ui/pages.css` | The landing page (hero, trust line, module cards), `.auth-card` and `.auth-art`, the portal cards, the approvals decision cell, the style page |
| `ui/retail.css` | The retail classes (`.rt`, `.rt-card`, `.rt-row`, `.rt-total`, `.rt-flag`, `.rt-primary`), `.line-foot` |
| `ui/search.css` | The shared search box (#241): `.sb`, `.sb-field`, `.sb-clear`, `.sb-status`, `.sb-searching`, `.sb-results` (dimmed while `aria-busy`), `.sb-mark` |
| `ui/onboarding.css` | The sign-up, applicant and operator portal additions (ADR-024): `.facts` (a definition list that stacks on a phone), `.filters` (the pressed status filter), `.pre-line`, `.wrap-anywhere`; those screens otherwise use `.auth-card`, `.form-stack`, `.alert`, `.table-wrap` and `.cluster` |
| `frontend/src/components/icons.tsx` | Inline SVG line icons (decorative, `aria-hidden`), so no image is fetched |
| `frontend/src/components/illustrations.tsx` | Inline SVG illustrations (#106), painted only by `.ill-*` classes |
| `frontend/src/components/states.tsx` | `EmptyState`, `StatusPanel`, `BrandLoader`, `SkeletonList` |
| `frontend/src/areas/style/route.tsx` | The living style page at `/style`: every component and illustration (development builds and the platform host only) |

## Colour

| Token | Value | Use |
|---|---|---|
| `--rt-blue` | `#00adef` | The Rincol blue. Accent only: borders, focus halos, the top stripe, icons, active markers. 2.5:1 on white, so never text |
| `--rt-blue-strong` | `#0077b6` | Text, links, button fills. 4.87:1 with white |
| `--rt-blue-tint` | `#eef9fe` | Tinted backgrounds (info alerts, chosen options, icon chips); the strong blue reads at 4.55:1 on it |
| `--rt-grey-50`, `-100`, `-200`, `-400` | `#f5f7f9`, `#eeeeee`, `#e5e5e5`, `#9e9e9e` | Page, dividers, card borders |
| `--rt-grey-500` | `#868e96` | Input edges (3.3:1 on white) |
| `--rt-grey-600`, `-900` | `#5c6370`, `#1d2939` | Muted text (6:1), body text (14.7:1) |
| `--color-danger`, `-success`, `-warning` | `#b42318`, `#067647`, `#b54708` | State text, each 4.5:1 or more on white and on its `-tint` |

Screens use the role tokens (`--color-primary`, `--color-on-primary`, `--color-accent`, `--color-link`,
`--color-focus`, `--color-text`, `--color-text-muted`, `--color-surface`, `--color-border`, ...), not the
palette. `src/app/ui/tokens.test.ts` checks these ratios with `src/app/contrast.ts`.

**Tenant colour.** The shell (#87) sets `--brand` and `--brand-contrast` inline on `<html>` when the tenant
has a theme colour. Then `--color-primary` is the tenant colour with `--brand-contrast` text (the API
guarantees 4.5:1), the accent and tints follow it, and links and focus rings use
`color-mix(in srgb, var(--brand) 70%, #000)`, because `--brand` alone may be too light as text on white.
The platform host and a tenant without a colour show the Rincol blue.

## Type, space, shape

- Open Sans Variable, self-hosted (`@fontsource-variable/open-sans`, weight axis only). The CSP needs
  nothing extra: the font files are bundled assets on the same origin. The fallback is the system UI font.
- Scale: `--text-xs` 12px, `--text-sm` 14px, `--text-base` 16px (inputs never go below, so phones do not
  zoom), `--text-lg` 18px, `--text-xl` 22px, `--text-2xl` 28px, `--text-3xl` 36px (page titles from 720px).
  Headings are bold.
- Spacing in 4px steps: `--space-1` 4px to `--space-8` 56px.
- `--radius` 8px for controls and cards, `--radius-lg` 12px for dialogs, `--radius-pill` for badges.
- Shadows are soft: `--shadow-sm` on controls, `--shadow` on cards, `--shadow-lg` on hover and dialogs.
- Elevation has three steps: flat (`.card-muted`), resting (`.card`), raised (`.card-raised`, the one card a
  page leads with, such as the landing hero and the sign-in card).
- Motion (#106, #508, `docs/ui-rules.md`): `--motion-fast` 120ms (hover, press), `--motion` 200ms (dialogs, toasts), `--motion-slow`
  250ms (a card or state rising in), all on `--ease-out`, `cubic-bezier(0.2, 0, 0, 1)`; never ease-in or ease-in-out, never a bounce, never `transition: all`, and only `transform` and `opacity` move, bar the guard's named colour fades (the design guard checks).
  Under `prefers-reduced-motion` the motion tokens are 0ms, `--press-scale` is 1, and every animation
  and transition runs once in 1ms (`base.css`), so a spinner stands still.

## Components

| Component | Markup | Notes |
|---|---|---|
| Secondary button | `<button>` | The default; 48px tall (`--tap`). Pressed: it sinks to 98% and darkens. Disabled (`disabled` or `aria-disabled="true"`): flat grey with muted text |
| Primary button | `.btn-primary` (retail: `.rt-primary`; #87: `.btn-brand`) | One per view, the main action, at the bottom of a form |
| Danger, ghost | `.btn-danger`, `.btn-ghost` | Reject or destroy; quiet actions such as Remove and Sign out |
| Busy button | `<BusyButton busy={save.isPending} className="btn-primary">Save</BusyButton>` (`app/ui/BusyButton.tsx`, `.busy-button`, `.busy-spinner`; #456) | Every button that writes (POST, PUT, PATCH, DELETE: a sale, a payment, an invitation, a setting, a sign-in, an approval). While busy it keeps its label (never swap it for Saving or Sending: the spinner shows the work), shows a small spinner placed absolutely in its end padding (the width does not jump; while busy the end padding is at least 1.1em, so the spinner never covers the label; `.btn-sm`, tighter than that, has the same 1.1em end padding when idle too, so it keeps its width as well), and is `aria-busy="true"` and `aria-disabled="true"` rather than `disabled`, so it keeps keyboard focus while its own press guard drops every press. It has no live region of its own: the one `BusyAnnouncer` the app shell mounts says Working once while any button is busy (twenty rows sharing one flag are announced once) and clears when the last is done. An `aria-modal` dialog hides the shell's announcer, so the phone menu drawer mounts its own `<BusyAnnouncer modal />` inside itself while the person has it open, and Sign out there is heard; while it is mounted the shell's announcer renders nothing, so there is only one Working live region at a time. A press holds it until busy shows, or until an `onClick` that awaits has settled (released in a `finally`, so a throw cannot stick it) and two tasks have passed, and for at most 10 seconds when busy never shows, so a double click sends once; a submit of its form that gets through (from code or another submit button) holds it the same way, so two submits in one task send once, and while busy its form drops an Enter re-submit. Under reduced motion the spinner is a pulsing dot. Takes every `<button>` prop |
| Link as button | `<Link className="btn btn-primary">` | Sizes `.btn-lg`, `.btn-sm` (still 48px tall), `.btn-block` |
| Field | `<label>Text<input/></label>` or `<label htmlFor>` then the input | Label above, `.field-hint` and `.field-error` under it; `aria-invalid="true"` turns the edge red; a focused field shows the focus edge and halo however it got focus, and a wrapping label turns the link colour |
| Choice row | `<label><input type="radio"/>Text</label>` | The whole row is the tap target; the chosen row is tinted |
| Vertical form | `.form-stack` | Fields 16px apart, buttons full width |
| One-time code | `.input-code` | Monospace, spaced |
| Value to copy | `.secret-box` with a `<code>` and a Copy button | The TOTP setup key |
| Card | `.card`, `.card-muted`, `.card-raised`, `.card-interactive` | White, 8px corners, soft shadow; `.card-interactive` (a card that is a link) lifts on hover and sinks on press |
| Table | `<div className="table-wrap" tabIndex={0}><table>` | Scrolls inside its card on a phone with edge shadows; `.num` cells right-aligned in tabular figures; `tabIndex` lets a keyboard scroll it |
| Table as cards | `.table-wrap.table-cards` and `data-label` on every `<td>` | Below 560px each row is a card of label and value pairs (no sideways scroll); `.cell-stack` puts controls under their label. Used by approvals and the member list |
| Side menu | `SideNav` and `SideMenu` (`components/side-menu.tsx`), `.side-nav`, `.side-nav-folded`, `.menu-button`, `.side-menu`, `.side-menu-panel`, `.side-menu-group` | The one navigation of the staff area and the operator portal: a sidebar from 720px that folds to icons, a drawer of the same items below it (see Navigation) |
| Breadcrumbs | `StaffBreadcrumbs` (`components/breadcrumbs.tsx`): `<nav aria-label="Breadcrumb">` with an `<ol>` | One trail under the staff bar from the routes' `crumb` labels (FR-UXN-01); the last item is `aria-current="page"`; below 720px only Back to the parent and the current page |
| Category group | `GroupControls`, `GroupedBodies`, `GroupedCards` (`areas/staff/retail/category-groups.tsx`), `.cat-head` | A list in expandable category sections: a full-width header button (`aria-expanded`, `aria-controls`) with the count and subtotal, a Group by category switch, Expand all and Collapse all (FR-UXN-02) |
| Home cards | `HomeCards` (`areas/staff/home-cards.tsx`), `.home-cards`, `.home-card`, `.home-figures` | Home's card per module the business uses (#471, FR-UXN-05, ADR-072): a `.card` headed with the module's menu icon and name, its figures as a `dl` (label muted, value large and semibold, a count as a hint), and a link to the module; a grid of columns at least 280px wide, so one column on a phone |
| Shop summary cards | `TakingsCard`, `CreditCard`, `TopItemsCard`, `BranchesCard` (`areas/staff/home-cards.tsx`), `.home-summary`, `.home-summary-body`, `.home-list` | The shop's cards after the Shop card on Home (#485, FR-UXN-06, ADR-076): a `.card` headed by its name without an icon (the icons stay the modules'), figures as a `dl` or a `.home-list` (a name, then its figure at the end of the line, semibold), Today and This week as two `aria-pressed` buttons, a plain sentence when there is nothing to show, and a link to the full page |
| Retail shortcuts | `RetailMenuGroup` (`areas/staff/retail/nav.tsx`) | Up to five retail shortcuts the user may use, as a group under Retail in the side menu on every size (#455, which removed the retail bottom bar) |
| Tile | `<TileGrid label><Tile to icon label hint /></TileGrid>` (`app/ui/Tile.tsx`, `.tile-grid`, `.tile`, `.tile-label`, `.tile-hint` in `ui/navigation.css`; #508) | Every hub page (Retail home, Catalogue, Business set-up): an icon above the label and a one-line hint under it, two per row on a phone. The link is named by its label and described by its hint. A press shrinks it to 98% over 120ms; hover changes the edge and background; focus adds a 3px ring; the shadow never grows |
| Sale bar | `<SaleBar>` around the sale's submit button (`areas/staff/retail/sale.tsx`, `.sale-bar`, `.sale-bar-action`, `.sale-bar-amount`; #508) | The sale screen's one primary action, Record sale with the live total (Record sale UGX 45,000). Below 720px it sticks full width to the bottom of the screen over the page gutter, and `SaleBar` measures it with a `ResizeObserver` into `--bottom-bar` on the root (cleared on unmount), so the toast rises above it and `scroll-padding-bottom` with the form's bottom padding keep a focused field clear of it; from 720px it sits in the form. The total above it is `.rt-total-hero` with the amount in `.rt-total-amount` at `--text-display`. A new line on the sale slides (`.line-new`) up 8px and fades in over 200ms; the count beside Items (`.count-badge`) pops on every add or scan top-up, never on a removal; and a phone that can vibrates for 10ms. A duplicate add gives none of these |
| Amounts | `.num`, `.rt-total`, `.rt-group-total`, `.rc-total`, `.line-foot`, `.sale-bar-amount`, `.an-stat-value`, `.iv-card-value`, `.iv-bars-value`, `.iv-value-btn`, `.home-figure dd` (`ui/base.css`) | Every amount in tabular figures, formatted only by `formatMinor` (`money()` on the retail screens); UGX has no decimals |
| Stat tile | `.an-stats` of `.an-stat` (`.an-stat-label`, `.an-stat-value`, a `.hint`) | The retail dashboard: a figure with its label, two per row on a phone and four from 720px; `.an-placeholder` (dashed) marks a slot for a figure that does not exist yet and shows no number |
| Bars | `Bars` (`areas/staff/retail/analytics-ui.tsx`): an `<ol class="bars">` of a name, a figure and an inline `<svg class="bar">` | A ranking drawn as bars scaled to the largest. The figure is text; the SVG is `aria-hidden` and is colour from `.bar-fill` and `.bar-track`, never an attribute |
| Sparkline | `Sparkline` (same file): `<svg class="spark" role="img" aria-label>` with a `<polyline class="spark-line">` | A trend line from zero to the largest value, with the total in text beside it and the label saying what it shows |
| Alert | `.alert` with `-danger`, `-warning`, `-success`, `-info` | Left rule and tint; the words carry the meaning |
| Badge | `.badge` with `-success`, `-warning`, `-danger`, `-info` | |
| Segmented (#520) | `<Segmented label options value onChange tour />`, `clearable` to let a second press clear it (`app/ui/Segmented.tsx`, `.segmented` in `ui/kit.css`) | The one "pick one" control, for 2 to 5 options: a Radix ToggleGroup of one choice styled by our tokens, so a named radio group that is one Tab stop with the arrow keys between options, drawn as one neutral tray with the chosen option raised, never a brand fill. It replaced every `.cluster.filters` row; the design guard fails a `filters` class |
| Stepper (#520) | `<Stepper id label name value onChange min max step inputMode />` (`app/ui/Stepper.tsx`, `.stepper`) | A quantity: 48px minus and plus either side of a centred, tabular number field that still takes typing. Minus and plus stay within `min` and `max` and go grey at the ends; ArrowUp, ArrowDown, Home and End work in the field. The buttons are named "One less" and "One more" with what is counted. On the Sale screen's quantity |
| Field (#520) | `<Field label hint error showError required optional {...inputProps} />` or `control={(props) => <select {...props} />}`, with `<FieldsNeededNote />` and `focusFirstMissing(form)` (`app/ui/Field.tsx`, `.field`) | The label above, a hint under it, and the error with its icon in place of the hint once the person leaves the field (or `showError` after a send), never while they type. Wires the id, `aria-describedby`, `aria-invalid` and `aria-required`. Owner rule for required fields: a red asterisk after the label (`aria-hidden`), the form starts with "Fields marked * are needed.", or on a form where most fields are needed the few others say "(optional)", never both on one form; the send button stays enabled and a press goes to the first missing field. The lending forms (`form-fields.tsx`) use the same asterisk and note |
| MoneyInput (#520) | `<MoneyInput label valueMinor onChange hint error />` (`app/ui/MoneyInput.tsx`, `.money-input`) | UGX in whole shillings on the numeric keypad, UGX drawn inside the field, the commas drawn as the person types (through `formatMinor`), in tabular semibold figures. Hands back minor units or null. Built on Field |
| ListRow and Card (#520) | `<Card title action><RowList label><ListRow title meta amount to onClick chevron /></RowList></Card>` (`app/ui/ListRow.tsx`, `.ui-card`, `.row-list`, `.list-row`) | One row style for items, sales and people: the title, one muted line, the amount on the right in tabular figures, a chevron when it opens something. The whole row (at least 64px) is the link or the button. The Card is the one surface: hairline edge, `--radius-lg`, `--shadow-sm` |
| Sheet (#520) | `<Sheet open onClose title actions>content</Sheet>` (`app/ui/Sheet.tsx`, `.sheet-layer`, `.sheet-panel`) | Pickers and confirmations, a Radix Dialog styled by our tokens. Under 720px a bottom sheet with a grab bar that a swipe down closes, by touch only; wider, a centred dialog. Focus starts on the first control of the content and Tab stays inside, Escape, Close and a tap outside close it, the page behind is hidden from a screen reader (never a live region, so a toast is heard) and does not scroll, and focus returns to the opener. Arrives in 200ms by transform and opacity. Replaces `dialog.sheet` in phase 3 |
| Empty state, kit (#520) | `<EmptyState text action={{ label, to }} />` (`app/ui/EmptyState.tsx`, `.empty-line`) | One muted line and at most one action, centred. Replaces the illustrated `EmptyState` of `components/states.tsx` in phase 3 |
| Skeleton (#520) | `<Skeleton loading={query.isPending} of={key} rows>content</Skeleton>` (`app/ui/Skeleton.tsx`, `.skeleton-rows`) | Nothing for the first second, then still grey rows faded in once, never a loop. Once the content has been on the screen a reload never covers it; a new list (`of` changes) starts again |
| Success screen (#520) | `<SuccessScreen title amount detail actions />` (`app/ui/SuccessScreen.tsx`, `.success-screen`) | After a sale or a payment: a check drawn in 400ms, the title (focused), the amount at the display size, then the next steps as plain buttons, the first primary. Centred |
| Search box | `useLiveSearch` and `<SearchBox id label search fetching count more>` (`app/ui/`) with `<SearchResults fetching>`, `<NoResults term onClear noun>` and `<Highlight text term>` | Every search on every screen: the label, the input with a Clear button, and a polite status line ("Searching", "12 results", `No results for "x"`). The text is live, the term follows after 250 ms, Escape clears, the old list stays dimmed until the new answer arrives. Pass `placeholderData: keepPreviousData` and the query function's `signal`. Rules: `docs/specs/search.md` section 2 |
| Form fields | `TextField`, `SelectField`, `Group`, `SaveBar`, `useFormErrors` (`areas/staff/lending/form-fields.tsx`; `.mf`, `.mf-field`, `.mf-group`, `.mf-savebar`) | A label above each input, hint and error tied with `aria-describedby`, `aria-invalid`, focus to the first refused input, groups as fieldset and legend, and a save bar stuck to the bottom of a phone with what blocks it in words |
| Photo control | `PhotoQueueControl`, `usePhotoQueue` (`areas/staff/lending/photo-queue.tsx`, `image-prep.ts`; `.ph-*`) | Take a photo (camera) or Choose a file, checked and shrunk in the browser, progress, Send again that does not shrink twice |
| Empty state | `<EmptyState art="noSales" title="...">help</EmptyState>`; bare `.empty-state` for a line of text | The illustration, a title and a line of help in a dashed box; `art` is one of `noSales`, `noProducts`, `noApplications`, `nothingWaiting` |
| Success, error | `<StatusPanel tone="success" title="...">` | `role="status"` or `"alert"`; the title in the status colour |
| Loading | `<BrandLoader />` for a page, `<SkeletonList />` for a list, `.loading` for a line of text, `.spinner`, `.skeleton` | Every loader names what loads in words; motion stops with `prefers-reduced-motion` |
| Dialog | `<dialog>`, `<dialog className="sheet">`, `.dialog-actions` | Rises in over 200ms with a fading backdrop; a bottom sheet that slides up below 560px, actions full width |
| Toast | `<Toast message tone />` and `useToast()` (`areas/staff/retail/toast.tsx`), `.toast`, `.toast-success`, `.toast-error`, `.toast-info` | A dark neutral pill (`--color-toast`, dark in both themes) with a leading check, error or info icon and no coloured edge; at the bottom of the screen, centred on the content column beside the sidebar and above the sticky sale bar, rises in, gone after 4 seconds. A status for success and info, an alert for an error, hidden where the screen already says the same |
| Illustration | `illustrations.hero`, `.shopfront`, `.secure`, `.success`, `.error`, and the four empty ones | Decorative (`aria-hidden`), under 2 KB each, no colour attribute: `.ill-tint`, `.ill-tint-strong`, `.ill-accent`, `.ill-primary` follow the tenant colour; `.ill-surface`, `.ill-line` are neutral; `.ill-success-*`, `.ill-danger-*` stay status colours. `shopfront` is the sign-up hero for the onboarding pages of #97 to adopt |
| iPhone install pictures | `IosSteps` in `frontend/src/install/InstallPrompt.tsx`, drawings in `src/install/ios-pictures.tsx` (#448) | One drawing of the part of Safari beside each of the four install steps (a normal tab, Share in the toolbar, Add to Home Screen and View More in the share sheet, Add at the top right): our own simple shapes, never screenshots or Apple artwork; unlike the decorative illustrations each is `role="img"` with a short `aria-label` that describes the drawing ("Picture: ..."), so a screen reader does not read the step twice; a 100 unit viewBox drawn at 104px with 11 unit labels, so they read at about 11px on a 360px phone; under 2 KB each; painted by the `.ill-*` classes plus `.ill-accent-ring` (what to tap), `.ill-thin` (the Share icon), `.ill-dashed` and `.ill-text*`; View More sits in a frame of its own under the Add to Home Screen row, since it is a second place to look, not a row seen beside it; step 2 says that on an iPad Share is at the top right; `.ios-step` keeps the words first and moves the picture under them on a narrow phone |
| New logo on the home screen | `NewIconNote` and `NewLogoCard` in `frontend/src/install/NewIcon.tsx` (#453, ADR-066) | The note sits in the Logo fieldset of set-up after a logo or colour save while a logo is set: plain words with the home screen name, no `role="status"` (the save message already is one), and a Show me how on an iPhone button that reveals `IosSteps`. The card is a `.card` headed Your app has a new icon, in the staff area, only in the installed app on an iPhone or iPad, once per new icon version, with the business's address, `IosSteps` and a Done button; never on the first run, nor on the device that made the save. Neither names an iOS menu item: press and hold the icon, then remove it |
| Step progress | `StepHeader` (`areas/auth/steps.tsx`), `.step-progress` | "Step N of M" in words and bars, the step heading takes focus and sets the page title |
| Password pair | `PasswordFields`, `.password-row`, `.checklist` | Show or hide, the server's rules ticked off as the user types |
| Two-step set-up | `TwoStepSetup`, `QrCode`, `.qr-box`, `.manual-key` | QR code drawn in the browser (uqr), Open in authenticator, the key in groups of four behind "Cannot scan?" |
| Recovery codes | `RecoveryCodes`, `.codes-grid`, `.button-pair` | Two columns, Copy all, Download as text, a required tick |
| Guided tour | `tour/` (`.tour-card`, `.tour-hole`, `.tour-backdrop`, `.tour-dots`) | A card docked top or bottom, away from the lit target; the hole is placed with CSS custom properties, never a style attribute (ADR-025) |
| Tour anchor | `data-tour="<id>"` on the element a tour step lights up | Stable ids, never a CSS class; `tour/tours.test.tsx` fails when one disappears |
| Tour rules (issue #292) | `tour/model.ts` `autoTourFor`, `host.tsx` | At most one tour starts by itself per page load; the rest wait for the next visit. A user with no tour history gets only the first-run (Welcome) tour, and every "New:" tour is marked seen for them at that moment, since they have nothing to compare against. One "Do not show tours again" checkbox (progress key `tours-off`) stops every automatic tour, "New:" ones included; Help still replays any tour |
| Help | `HelpMenu` in the staff bar | A `dialog.sheet` with Take the tour and Show me this page |
| Sync status (issue #458, ADR-067) | `SyncStatus` in `frontend/src/offline/sync-status.tsx`, mounted in the staff bar before Help; `.sync-status`, `.sync-dot`, `.sync-dot-offline`, `.sync-dot-syncing`, `.sync-word`, `.sync-count` (`.sync-long`, `.sync-short`), `.sync-list`, `.sync-row`, `.sync-row-failed`, `.sync-reason` | A 12px dot, `--color-success` online, `--color-text-muted` offline, `--color-warning` while sending (a slow pulse, still with `prefers-reduced-motion`), with the state as visible text beside it (Online, Offline, Syncing) and "3 waiting to sync" ("3 waiting" at 480px and below). The button's accessible name says the state, the count and the failed ones ("Offline, 3 waiting to sync, 1 failed"), so colour never carries the meaning alone (WCAG 1.4.1); it is 44px high and keeps its size on a phone. Tapping it opens a `dialog.sheet` (a bottom sheet below 560px): the queued and failed sales with the time, branch, way of paying, total and the reason of a failure, Send again, Sync now, and for a manager the sales other phones could not record (Send it again, Discard with a reason) and the open clashes (Mark resolved with a note). It lives in the staff bar, not the shell's header, so it never sits beside the theme selector or a phone menu. Drawn only for a person who sells |
| Sign-out warning (issue #458) | `SignOutWarning` in `frontend/src/offline/sign-out-warning.tsx` | A `dialog.sheet` when sales wait: "3 sales are waiting to sync.", Sync now, Keep them and sign out, Cancel |

## Navigation (#455)

One navigation pattern on every screen size: the staff area and the operator portal list their areas in a side menu with an
icon on each item, like the owner's other apps. There are no area tabs and no separate retail row. Only where the menu sits
changes, at the design system's phone width, `(max-width: 719px)`, read in code by `useIsPhone` (`components/side-menu.tsx`;
the retail screens re-export it) and in CSS by the `720px` media queries. CSS custom properties cannot be used in a media
query, so the number is the token.

- **One list.** Each layout builds its areas once (`staffAreas` in `areas/staff/menu.tsx`, `AREAS` in
  `areas/platform/operator-areas.ts`) with the permission checks, and both the sidebar and the drawer draw that list with the same
  component (`MenuLinks`, one `MenuItemLink` per item): the same items, icons (inline SVG in `currentColor`), groups and the
  current item marked `aria-current="page"` (tinted, with an accent edge). The retail shortcuts are a group under Retail
  (`RetailMenuGroup`, `data-tour="retail-nav"`), drawn with `MenuItemLink` too. Notifications and Feedback are always there.
- **Retail and its shortcuts:** Retail is current on every page under `/staff/retail`. On the page of one of its shortcuts
  (the item's `groupPaths`) the shortcut is the current item and Retail is drawn as its section (`.side-menu-section`: the
  link colour, no tint or edge, no `aria-current`). Sale is a shortcut only for a session that can sell (`useSells` in
  `areas/staff/retail/nav.tsx`: retail on, the sale screen allowed and, with branch roles, a branch that is not a store); the
  branch roles are read only when the first two hold.
- **From 720px, the sidebar** (`SideNav`, a `div.side-nav` around the `nav`): a column at the left of the page, about 240px wide,
  that stays in view while the page scrolls (`position: sticky`, the height of the window, scrolling inside when the list is
  longer). The page becomes two columns (`.nav-layout`, `.nav-layout-main`): the content takes the remaining width, centred up
  to `--content-width`, and clips the staff bar's full-bleed band, so nothing scrolls sideways at 1024px. It comes first in the
  page, so Tab reaches the menu before the content. Sign out stays in the staff bar.
- **Sections for a business with Shop and Lending (#471, FR-UXN-04):** each item of `staffAreas` names its module with
  `section` (`shop` for Retail, `lending` for Loans, Members, Collateral, Savings, Investments, Insights and the AI assistant),
  and the layout passes `STAFF_SECTIONS` (Shop with the `shop` icon, a shop front, which also heads the Shop card on Home; Lending with its own `lending` icon, a coin on an open hand). When the links name two
  sections, `MenuLinks` draws the shared items before the first sectioned one (Home, Approvals), then each section, then the
  other shared items (Reports, SMS, Receive payments, Users, Notifications, Feedback, Business set-up). A section is a
  `.side-menu-block` with a rule above it and a heading button (`.side-menu-heading`: small muted capitals, its icon, a caret,
  `aria-expanded`, `aria-controls` on the list) that folds its links; folded, the links stay in the page under `hidden`, and the
  drawer's Tab cycle skips any control inside a `[hidden]` ancestor. The folded sections are kept per person in `localStorage`
  under `bms.menu.sections.<user id>` (a list of ids, read and written inside `try`/`catch`), read again when another person
  signs in. A folded section shows its links while the page is one of them or of their groups (`groupPaths`), and while a tour
  step points at one of its links or its `tours` (`retail-nav` for Shop). The heading acts on what the person sees, as the
  sidebar button does: a press on a section held open folds it and wins until the page or the tour step changes. In the folded
  sidebar the heading is its icon, with the label as its accessible name and `title`. With one section, or none, the list stays
  flat with no heading, as before. On a phone the one-tap Sell is there only while retail is on, and Sale stays in Shop too.
- **Folding the sidebar:** a button at its top (the `menu` icon, the constant name `aria-label="Menu labels"`, `aria-expanded`
  true while the labels show, a `title` that says Show or Hide menu labels) folds it to 64px of icons and back. It starts
  folded between 720 and 1023px and unfolded from 1024px. A folded item keeps its label as its accessible name (a
  `visually-hidden` span) and as its `title` tooltip, the retail group too. Once the person folds or unfolds it, the choice is
  kept per device in `localStorage` under `bms.sidebar` (`folded` or `open`; nothing kept means the width decides), read and
  written inside `try`/`catch`, so a browser that blocks storage still folds until the page is reloaded. The width changes
  over `--motion`, and not at all under reduced motion. A tour step that points into a folded sidebar unfolds it while the step
  shows. The button acts on what the person sees and the press wins for the rest of that step: pressed during the step, the
  sidebar folds (or unfolds again on a second press) at once, with `aria-expanded`, the `side-nav-folded` class and the
  `title` following, and the choice is kept in `bms.sidebar`. A step that points somewhere else, or the tour ending, forgets the press: the next step that points into the sidebar, going back to the step, or the next run of the tour unfolds it again.
- **Below 720px, the drawer** (`SideMenu`): a menu button (`.menu-button`, the `menu` icon, `aria-label="Menu"`) at the top
  left of the staff bar opens the list in a side drawer from the left: 85% of the width, at most 320px, over a dimmed backdrop
  (`--color-scrim`). The person's name and Sign out sit at the bottom (one `BusyButton` in `SideMenu`, with an `onSignOut` prop and a `busy`
  prop held true while signing out runs, like Sign out in the bar). It
  closes on the backdrop, Escape, a left swipe on the drawer (50px, more sideways than down), a chosen link, or any change of
  page (the back button, a tour, a redirect).
- **One-tap Sell on a phone:** beside the menu button, a primary Sell button (`.staff-sell`, a link to the sale screen) for
  anyone who can sell (`useSells`), so a sale is one tap as it was on the bottom bar. With branch roles on, it waits until the
  roles are read, so it never flashes up for a session whose every branch is a store. From 720px Sale is in the sidebar instead.
- **Drawer accessibility:** the drawer is `role="dialog"` with the heading Menu, and `aria-modal="true"` only while the person
  opened it (a tour step that opens it leaves the page reachable); focus moves to its
  first control, Tab and Shift+Tab stay inside, and focus returns to the menu button on close. The menu button carries
  `aria-expanded`. The page behind does not scroll: `body.side-menu-open` fixes the body at the scroll position (iOS Safari
  ignores `overflow` on the body), and closing gives the position back, except after a change of page or a tapped link. If the window widens
  past the phone width while the drawer is open, the drawer, its backdrop and the lock go, and focus moves to the sidebar's
  first link; narrowing with focus in the sidebar moves it to the menu button.
- **Phone edges, motion and theme:** the drawer pads itself with `env(safe-area-inset-top)`, `-bottom` and `-left` and slides
  in over `--motion` (once in 1ms under reduced motion). The sidebar and the drawer draw only with theme roles, so both
  follow light and dark.
- **Guided tours:** every `data-tour` anchor of a menu item (`nav-areas`, `nav-approvals`, `nav-retail`, `retail-nav` and the
  rest) is in the page on every size: in the sidebar from 720px, and in the closed drawer (kept with `hidden`) on a phone.
  When a step points at one, the tour host's current target (`useTour().target`) opens the drawer at once, without animation
  and without taking focus from the tour card, or unfolds a folded sidebar; both return when the step moves on. A tour that
  starts while the drawer is open closes it first. The tour copy says "the menu", and "on a phone, open the menu at the top
  left".
- **One icon per item (#483):** no two items of one menu draw the same picture, counting the Retail shortcuts and the section
  headings as items of the side menu, and each retail tile and catalogue tile as an item of its page. The same item keeps its
  icon wherever it shows: a retail shortcut and its tile both read `icons[screen]`, and the Shop heading and the Shop card on
  Home both use `shop`. A new item gets a new drawing in `components/icons.tsx` chosen for its meaning, never another item's.
  The side menu: Home `home`, Approvals `approve`, Loans `calendar`, Members `people`, Collateral `lock`, Savings `coins`,
  Investments `investments` (a seedling), Insights `insights` (a light bulb), AI assistant `assistant` (a sparkle), Reports
  `reports` (a page with bars), Retail `retail` (a shopping bag) with Sale `sale` (a cart), Orders `orders` (a delivery
  motorbike), Banked `banking` (a bank building), Savings `savings` (a safe), Expenses `expenses` (a wallet), Advances
  `advances`, Stock `stock`, Restock `restock`, Count `stocktake` and Usage `usage`, SMS `phone`, Receive payments
  `receivePayments` (an arrow coming in to a coin), Users `user`, Notifications `bell`, Feedback `chat`, Business set-up
  `settings`; the headings Shop `shop` and Lending `lending`. Stock moves (`transfers`) is redrawn as a list with arrows,
  so it no longer looks like a plain list. The member portal cards (`member/features.ts`): Balances `coins`, Loan schedule
  `calendar`, Payments `phone`.
- **Where it applies:** the staff area and the operator portal. The member area has no area list and keeps its layout.

## Light and dark theme (#429, #452)

An icon button in the header, at the top right of every area (staff, member, operator, public pages), offers **Light**,
**Dark** and **Same as my phone** (issue #452 replaced the `<select>` of #429, which read "System" and was not recognised
as a theme control). The button shows a sun for Light, a moon for Dark and a phone outline for Same as my phone, drawn in
`src/components/icons.tsx` at the size and stroke of the refresh icon, in `currentColor`. Its `aria-label` and `title` say
"Theme: " and the current choice. It follows the menu button pattern: `aria-haspopup="menu"` and `aria-expanded`; a tap,
or the down or up arrow, opens a menu of three `menuitemradio` items with the current one `aria-checked` and ticked; the
arrow keys, Home and End move, Escape closes and gives focus back to the button, a tap outside or Tab closes it. The menu
hangs from the button's right edge above the page (`--z-overlay`), so it fits a 360px screen. It sits last in the
`.header-actions` group, after the refresh button of #430, and the welcome tours point at it (`data-tour="theme-select"`,
the step "Light or dark"). The same three choices are the **Appearance** radio group on the Notifications and app page
("How BMS looks on this device."), bound to the same choice, with a step in the page tour (`page-notifications` v3).

- **Remembered per person per device.** The choice is kept in the browser's `localStorage` under `bms.theme` (`light` or
  `dark`; Same as my phone, stored as `system`, keeps nothing). It is never sent to the server: a person's browser profile on a device is the person.
- **Follows the system by default.** While the choice is Same as my phone the theme follows `prefers-color-scheme`, live.
- **No flash.** `frontend/public/theme-init.js` is a blocking script in the `<head>` (a file, because the production CSP is
  `script-src 'self'`). Before the first paint it sets `data-theme="light|dark"` and the inline `color-scheme` on `<html>` (so the canvas and scrollbars match the choice, not the
  system, before the stylesheet lands) and the `theme-color` meta tag. The app's `src/app/theme-mode.ts` does the same afterwards and holds the same key and colours.
- **One dark set.** `theme.css` has a single `:root[data-theme='dark']` block, so nothing repeats in a media query. The
  palette tokens (`--rt-*`) do not change with the theme; a stylesheet draws with the colour roles below, which do, and
  `design-guard.test.ts` fails a stylesheet that uses a palette token other than white text on a strong fill, black for a
  darker mix and the QR code's fixed pair (a scanner needs black on white in both themes).
- **The installed app's theme colour.** The `theme-color` meta tag is `#0077b6` in the light theme and `#0e141b` (the dark
  page) in the dark one, and changes with the choice. The manifest keeps `theme_color: #0077b6` and
  `background_color: #ffffff`, because a manifest is one static file: they colour only the launch splash, and the page's own
  meta tag replaces the theme colour as soon as it loads. **iOS limitation:** an installed iPhone app takes its status bar
  from `apple-mobile-web-app-status-bar-style`, which stays `default` (a light bar) whatever the choice, so in Dark the
  status bar of the installed iOS app stays light; Android and desktop browsers follow the `theme-color` meta tag.
- **Blocked storage.** If the browser blocks `localStorage`, the choice is held in memory for the visit, so the button, the
  page and the system-follow listener stay in step until the app is closed.
- **Logos.** A logo may be dark on transparent, so in the dark theme the brand bar and footer logos sit on a white plate
  (`--color-logo-plate`).

Token pairs (light, dark). Every text role is 4.5:1 or more on its surface in both themes; edges, the focus colour and the tenant accent 3:1.
`src/app/ui/tokens.test.ts` proves each pair.

| Role | Light | Dark |
|---|---|---|
| `--color-bg` (page) | `#f5f7f9` | `#0e141b` |
| `--color-surface` | `#ffffff` | `#161e28` |
| `--color-surface-muted` | `#fafbfc` | `#1b2430` |
| `--color-border` | `#e5e5e5` | `#2f3b4b` |
| `--color-border-strong` (input edges, 3:1) | `#868e96` | `#7d8896` |
| `--color-text` | `#1d2939` | `#e8edf3` |
| `--color-text-muted` | `#5c6370` | `#a9b4c3` |
| `--color-link`, `--color-focus` | `#0077b6` | `#5cc0f2` |
| `--color-accent` (tenant stripe, active tab, chosen card edge; 3:1) | `--brand` | `--brand` 45% mixed into white |
| `--color-primary-hover` / `-active` (primary button, label stays 4.5:1) | `--color-primary` mixed toward black | unchanged: still toward black, because the label is white |
| `--color-accent-tint`, `--color-info-tint` | `#eef9fe` | `#10283a` |
| `--color-danger` / `-tint` | `#b42318` / `#fef3f2` | `#f97066` / `#3b1614` |
| `--color-success` / `-tint` | `#067647` / `#ecfdf3` | `#47cd89` / `#0f2c1e` |
| `--color-warning` / `-tint` | `#b54708` / `#fffaeb` | `#fdb022` / `#352509` |
| `--color-danger-fill` (destructive button, white text) | `#b42318` | `#d92d20` |
| `--color-neutral-tint` (disabled, neutral badge, skeleton, hairline) | `#eeeeee` | `#222c39` |
| `--color-hover` (button hover) | `#f5f7f9` | `#1b2430` |
| `--color-inverse` / `--color-on-inverse` (toast, chart read-out) | `#1d2939` / `#ffffff` | `#e8edf3` / `#0e141b` |
| `--color-scrim` (behind a tour step) | `rgba(16,24,40,.62)` | `rgba(0,0,0,.72)` |
| `--color-logo-plate` | transparent | `#ffffff` |
| Chart slot 1, 2, 3 (`--viz-1-value` ...) | `#0077b6`, `#eb6834`, `#1baf7a` | `#4db8f0`, `#f08a5d`, `#3ccf97` |

**Meaning is kept.** Danger is red, success green and warning amber in both themes, badges and alerts keep their words
and their tint pair, and the chart slots keep their order and hues (blue, orange, green), lifted to stand out from the
dark surface at 3:1; every chart still carries its values in a table and on hover.

**Tenant colour in the dark theme.** `--brand` and `--brand-contrast` are untouched: buttons and badges keep the tenant
fill with its 4.5:1 text. What a tenant colour draws as text or a line on the page is derived again, because a darkened mix
cannot be read on a dark page: `--color-link` and `--color-focus` become `color-mix(in srgb, var(--brand) 35%, #fff)`
(4.5:1 on the dark surface for any colour, tested down to black), `--color-accent` becomes `color-mix(in srgb, var(--brand) 45%, #fff)` (3:1 on the dark surface, tested for dark brands such as `#7a0019` and `#004d40`) and the tints mix 18% of `--brand` into the dark surface.

## Rules for a new screen

1. Use the components above; add a class to this catalogue rather than a style attribute. Never add an
   inline `<style>` element: the production CSP (`style-src 'self'`) refuses it. Never write a hex
   colour outside `theme.css`; a new illustration uses the `.ill-*` classes. Add anything new to the
   style page.
2. Phone first: one column at 360px, nothing scrolls sideways except inside a `.table-wrap`, every tap
   target at least 48px (`--tap`), the main action last and full width.
3. State in words as well as colour; errors in `role="alert"` with `.alert-danger`.
4. Money through `formatMinor` (`src/components/money.ts`), in `.num` cells.
5. A tenant colour is applied for you; never write a brand colour. Draw with the colour roles, never a palette token, so
   the screen follows the theme (see Light and dark theme above); check a new screen in both.
6. A button that writes is a `BusyButton` with the mutation's `isPending` (or the screen's own submitting state) as
   `busy`; never a plain `<button disabled={save.isPending}>`. Its label stays the same while busy: no
   `{save.isPending ? 'Saving' : 'Save'}` and no busy label prop. Read-only buttons (refresh, filters, tabs, navigation,
   opening a dialog) stay plain, and so does a Cancel that only waits, with `/* busy-exempt: <reason> */` in its tag. A
   mutation that needs no busy button of its own (a save as you type, a switch that says Working itself, cards that
   show their own busy state) has `// busy-exempt: <reason>` in the `//` comment lines directly above its
   `const ... = useMutation(` line. `src/app/ui/busy-guard.test.ts` fails a mutation that a press (onClick, onSubmit or
   a component callback, directly or through a local function) sends unless its pending flag reaches a `busy={...}`
   attribute in that file, that comment exempts it (a comment with no reason does not), or the guard's `ALLOWED` list
   names it with a reason. It is a text heuristic: it checks that the flag reaches some `busy=` in the file, not that
   it is the busy of the button that sends.
7. Give the main controls of a new screen `data-tour` ids and add the screen's steps to
   `frontend/src/tour/tours.ts` (a page tour at least), with plain, short sentences.

## Checks

- `npm test` runs the busy guard (`src/app/ui/busy-guard.test.ts`, #456: every mutation a press sends, including
  `useMutation<T>(`, destructured ones and local hooks that return one, hands its pending flag to `busy`, and no plain
  button is disabled by a pending state; fixture tests prove it fails both wrong shapes) and `BusyButton.dom.test.tsx`.
- `npm test` runs `src/areas/staff/menu-icons.test.tsx` (#483): two items of the staff side menu (its full list asserted, the shortcuts as their group draws them), the
  retail tiles and catalogue tiles (both as their pages draw them), the operator portal or the member portal cards that draw
  the same picture fail it, compared by the drawing itself. `home-cards.dom.test.tsx` checks the Home cards draw their side
  menu headings' icons, no two alike, and
  `setup.dom.test.tsx` that the Business set-up page, with everything rendered beside it, draws no picture twice.
- `npm test` runs the token contrast test and the design guard (`src/app/ui/design-guard.test.ts`: no
  `<style>` element, no inline HTML, and no hex colour in a component or a design layer stylesheet, and no palette token that would not follow the theme);
  Since #520 it also fails an emoji or a dash in any source, a gradient, glow or glass outside the two named stylesheets, a
  `.cluster.filters` row in place of Segmented, and, in `ui/kit.css`, a third radius or shadow, a target under 48px, an
  amount without tabular figures, a motion off the tokens or in a loop (bar the 400ms check), and a kit animation that still
  moves under reduced motion;
  `src/app/theme-mode.dom.test.tsx` covers the three choices, persistence, following the system, the pre-paint script and the
  theme colour.
- `/style` in `npm run dev` shows every component at the width of the window.
- `/staff/kit` (administrators only, not in the menu) shows every control of the component kit (#520) with made-up figures, for review on a phone.
- The screenshot walk in `docs/ui/design-system/README.md` checks every screen route for sideways
  overflow at 360px and runs axe-core (WCAG 2.1 A and AA rules, colour contrast included).

## Not yet

- The workbox precache does not list the font files, so an offline start falls back to the system font.
