# Design system

**Status:** Draft · **Chore:** #95, #106 (visual polish), #429 (light and dark theme), #455 (phone side menu), #471 (menu sections and Home cards), #564 (one grouped menu) · **Chapter:** `docs/sdd/11-member-channels.md` section 11.2.6 · **Builds on:** #87 (tenant branding, FR-TEN-08)

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
| `ui/navigation.css` | The staff bar, the side menu: the sidebar from 720px (`.nav-layout`, `.side-nav`) and the phone drawer (`.menu-button`, `.side-menu`), its groups, counts, top and foot (#564); the staff area's bottom tabs on a phone (`.tab-bar`, `.tab`, `.has-tab-bar`, #539) |
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
| New badge (#603) | `<NewBadge feature="ask-bms" />` on a menu entry (`MenuLink.badge`), `<NewDot feature="ask-bms" />` on a tab, `markFeatureSeen('ask-bms')` on the feature's page (`app/ui/NewBadge.tsx`, `.new-badge`, `.new-dot`, `.new-blink` in `navigation.css`) | A small New pill in the brand fill after the entry's name (read as ", New") and a dot on the More tab (the tab is named "More, New"). Both blink, opacity 1 to 0.35 and back each second on `--ease-out`, for at most five seconds per page load (the clock starts with the first badge drawn; one drawn again later blinks only for what is left; inside WCAG 2.2.2), and show until the person opens the feature once (for the help page, `/staff/help` or any article); then this device remembers it (`localStorage` key `bms.new.<feature>.seen`, every read and write in try/catch, so blocked storage leaves the badge showing) and every badge of the feature goes at once. Under reduced motion they show without the blink. The blink is the one attention loop the design guard allows (the owner asked for it); any other pulse that is not work in progress fails it |
| Link as button | `<Link className="btn btn-primary">` | Sizes `.btn-lg`, `.btn-sm` (still 48px tall), `.btn-block` |
| Field | `<label>Text<input/></label>` or `<label htmlFor>` then the input | Label above, `.field-hint` and `.field-error` under it; `aria-invalid="true"` turns the edge red; a focused field shows the focus edge and halo however it got focus, and a wrapping label turns the link colour |
| Choice row | `<label><input type="radio"/>Text</label>` | The whole row is the tap target; the chosen row is tinted |
| Vertical form | `.form-stack` | Fields 16px apart, buttons full width |
| One-time code | `.input-code` | Monospace, spaced |
| Value to copy | `.secret-box` with a `<code>` and a Copy button | The TOTP setup key |
| Card | `.card`, `.card-muted`, `.card-raised`, `.card-interactive` | White, 8px corners, soft shadow; `.card-interactive` (a card that is a link) lifts on hover and sinks on press |
| Table | `<div className="table-wrap" tabIndex={0}><table>` | Scrolls inside its card on a phone with edge shadows; `.num` cells right-aligned in tabular figures; `tabIndex` lets a keyboard scroll it |
| Table as cards | `.table-wrap.table-cards` and `data-label` on every `<td>` | Below 560px each row is a card of label and value pairs (no sideways scroll); `.cell-stack` puts controls under their label. Used by approvals and the member list |
| Side menu | `SideNav` and `SideMenu` (`components/side-menu.tsx`), `.side-nav`, `.menu-button`, `.side-menu`, `.side-menu-panel`, `.side-menu-group`, `.side-menu-heading`, `.side-menu-count`, `.side-menu-foot`, `.menu-shop` | The navigation of the staff area and the operator portal: a fixed 264px sidebar from 720px and the same groups in a drawer below it (#564, see Navigation) |
| Bottom tabs | `StaffTabBar` and `TabBar` (`areas/staff/tabs.tsx`), `MoneyPage` and `MorePage` (`areas/staff/tab-pages.tsx`), `.tab-bar`, `.tab`, `.tab-icon`, `.tab-badge` | The staff area's navigation below 720px (#539): Sell, My stock, Buy, Money and More (see Navigation) |
| Breadcrumbs | `StaffBreadcrumbs` (`components/breadcrumbs.tsx`): `<nav aria-label="Breadcrumb">` with an `<ol>` | One trail under the staff bar from the routes' `crumb` labels (FR-UXN-01); the last item is `aria-current="page"`; below 720px only Back to the parent and the current page |
| Category group | `GroupControls`, `GroupedBodies`, `GroupedCards` (`areas/staff/retail/category-groups.tsx`), `.cat-head` | A list in expandable category sections: a full-width header button (`aria-expanded`, `aria-controls`) with the count and subtotal, a Group by category switch, Expand all and Collapse all (FR-UXN-02). The Stock page and the catalogue items only; never an item picker |
| Item picker | `ItemCombobox` (`app/ui/ItemCombobox.tsx`), `ProductPicker` (`areas/staff/retail/ui.tsx`), `.cb-pop`, `.cb-option`, `.cb-empty` in `ui/search.css`; `--z-dropdown` | The one way to find and add an item (#534, FR-UXN-07): a labelled search box whose matches open under it, over the page, only while something is typed, at most 10, each a 48px row; a pick adds the item, empties the box, closes the list and keeps the cursor in the box. Combobox pattern: `aria-expanded`, `aria-controls`, `aria-activedescendant`, Up, Down, Enter and Escape. The list fades and rises 4px over 150ms, off under reduced motion |
| Category picker | `CategoryPicker` (`areas/staff/retail/category-picker.tsx`), the kit's `Sheet` (`tall`) on a phone and `@radix-ui/react-popover` on a wider screen, `.cp-trigger`, `.cp-pop`, `.cp-option`, `.cp-add-row`, `.sheet-tall` in `ui/search.css` and `ui/kit.css` | The one way to choose a retail category (#578, FR-UXN-10): a field drawn as a select (`role="combobox"`, `aria-haspopup="dialog"`, inside the kit's `Field`) that opens a search box with the cursor in it, Add a new category for catalogue managers (an inline form with the name and the code start the app makes), and the list: rows of at least 48px, the chosen one tinted with a tick, the one the arrow keys reached with the focus ring, no stripe. Escape closes and the focus goes back to the field; a new category is chosen at once with the screen's own dark toast (`ScreenToast`, so a screen shows one toast) and the same words in a live region by the field. `noneLabel` adds an empty first choice for a filter (`allowAdd={false}`). |
| Feature search | `FeatureSearch` (`areas/staff/feature-search.tsx`), the list in `feature-search-state.ts`, `.find-open`, `.find-open-more`, `.find-screen` in `ui/search.css`; the `search` icon | Find any screen by what you want to do (#551, FR-UXN-09): a Search button in the staff bar (icon alone below 720px, named Search) and at the top of the More page opens the kit's `Sheet` holding the item picker, whose list sits in the sheet's flow rather than over it. / and Ctrl+K open it on a laptop. It lists only what the menu offers the session |
| Home cards | `HomeCards` (`areas/staff/home-cards.tsx`), `.home-cards`, `.home-card`, `.home-figures` | Home's card per module the business uses (#471, FR-UXN-05, ADR-072): a `.card` headed with the module's menu icon and name, its figures as a `dl` (label muted, value large and semibold, a count as a hint), and a link to the module; a grid of columns at least 280px wide, so one column on a phone |
| Shop summary cards | `TakingsCard`, `CreditCard`, `TopItemsCard`, `BranchesCard` (`areas/staff/home-cards.tsx`), `.home-summary`, `.home-summary-body`, `.home-list` | The shop's cards after the Shop card on Home (#485, FR-UXN-06, ADR-076): a `.card` headed by its name without an icon (the icons stay the modules'), figures as a `dl` or a `.home-list` (a name, then its figure at the end of the line, semibold), Today and This week as two `aria-pressed` buttons, a plain sentence when there is nothing to show, and a link to the full page |
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
| Empty state, kit (#520) | `<EmptyState text action={{ label, to }} />` (`app/ui/EmptyState.tsx`, `.empty-line`) | One muted line and at most one action, centred, with no border, box or drawing (#526). The `EmptyState` of `components/states.tsx` and a bare `.empty-state` line draw the same way; the design guard fails a dashed or bordered empty state |
| Empty choice (#619) | `<EmptyChoice id label text ask add />` (`app/ui/EmptyChoice.tsx`, `.empty-choice`) | A list a form needs is empty: the field's label, one muted line ("No units yet.") and one way to fill it, left aligned like a field: an inline add by name that chooses the new row (`AddByName`), or a link to the screen that adds one, which comes back (`?back=`, `useComeBack`). Without the permission, who to ask ("Ask the owner to add a unit first."). The design guard fails a select of a run-time list without it or an `empty:` reason |
| No access (#526) | `<NoAccess what? />` (`app/ui/EmptyState.tsx`) | A page or part the session may not open: "You do not have access to this page. The owner or an administrator of the business can give it to you in Staff users." and a Go to Home button. Every no-access line uses it |
| Key figure (#526) | `<KeyFigure label value tour><KeyFigureRow label value /></KeyFigure>` (`app/ui/KeyFigure.tsx`, `.key-figure`) | The one key amount of a screen: the label small and muted above, the amount (already through `money()`) at `--text-display` in tabular figures, the parts behind it as plain rows. On Home's Takings today, Daily profit, Credit control, Savings and Stock value |
| Action bar (#526) | `<ActionBar>` around the primary button (`app/ui/ActionBar.tsx`, `.action-bar`) | The sale's bar made shared: on a phone stuck full width to the bottom over the bottom tabs, its measured height in `--bottom-bar`; from 720px in the form. On Sale, Banking, Expenses, Savings, Cash withdrawals, Usage and damage, Move stock, Import items and Barcode labels. A `BusyButton` given `notReady` (its form's fields are not yet right) stays enabled in its fill with `data-not-ready`, and a press or Enter goes to the first missing field (or says to check the fields) instead of sending; another submit button of the form still works. `disabled` keeps its plain meaning for a block that is not about the fields (another request running, a server not set up, a person who may only look) |
| Period picker (#526) | `<PeriodPicker from to onChange label maxDays tour />`, `showDate`, `showRange` (`app/ui/PeriodPicker.tsx`, `.period-picker`) | Every date range: one Segmented of Today, This week, This month and Custom, the days in force under it in words ("17 Oct to 24 Oct 2026"), Custom in the kit's Sheet with Show these days. A screen's own default window shows as Custom. `openStart` lets a list start with every day (All sales), with Show every day in the sheet. `showDate` is the one date formatter ("23 Oct 2026") |
| Hint (#526) | `<Hint tour>text</Hint>` (`app/ui/Hint.tsx`, `.hint-line`) | A help text on one line, cut with an ellipsis, and an info button that opens the rest when it does not fit; the whole text stays in the page for a screen reader. A warning or a consequence (cannot be undone, shown once, never goes back into stock) takes `full` and is always drawn whole |
| Link row (#526) | `<Link className="link-row">Label<RowChevron /></Link>`, `ScreenLink` (`action` for a button), `.figure-link` | A related screen is a row with a chevron, an action a button, a figure that opens its rows link coloured with a chevron: never an underlined word |
| Skeleton (#520) | `<Skeleton loading={query.isPending} of={key} rows>content</Skeleton>` (`app/ui/Skeleton.tsx`, `.skeleton-rows`) | Nothing for the first second, then still grey rows faded in once, never a loop. Once the content has been on the screen a reload never covers it; a new list (`of` changes) starts again |
| Success screen (#520) | `<SuccessScreen title amount detail actions />` (`app/ui/SuccessScreen.tsx`, `.success-screen`) | After a sale or a payment: a check drawn in 400ms, the title (focused), the amount at the display size, then the next steps as plain buttons, the first primary. Centred |
| Search box | `useLiveSearch` and `<SearchBox id label search fetching count more>` (`app/ui/`) with `<SearchResults fetching>`, `<NoResults term onClear noun>` and `<Highlight text term>` | Every search on every screen: the label, the input with a Clear button, and a polite status line ("Searching", "12 results", `No results for "x"`). The text is live, the term follows after 250 ms, Escape clears, the old list stays dimmed until the new answer arrives. Pass `placeholderData: keepPreviousData` and the query function's `signal`. Rules: `docs/specs/search.md` section 2 |
| Form fields | `TextField`, `SelectField`, `Group`, `SaveBar`, `useFormErrors` (`areas/staff/lending/form-fields.tsx`; `.mf`, `.mf-field`, `.mf-group`, `.mf-savebar`) | A label above each input, hint and error tied with `aria-describedby`, `aria-invalid`, focus to the first refused input, groups as fieldset and legend, and a save bar stuck to the bottom of a phone with what blocks it in words |
| Photo control | `PhotoQueueControl`, `usePhotoQueue` (`areas/staff/lending/photo-queue.tsx`, `image-prep.ts`; `.ph-*`) | Take a photo (camera) or Choose a file, checked and shrunk in the browser, progress, Send again that does not shrink twice |
| Empty state | `<EmptyState title="...">help</EmptyState>`; bare `.empty-state` for a line of text | One plain line of the title and its help, centred, no drawing and no box (#526) |
| Success, error | `<StatusPanel tone="success" title="...">` | `role="status"` or `"alert"`; the title in the status colour |
| Loading | `<BrandLoader />` for a page, `<SkeletonList />` for a list, `.loading` for a line of text, `.spinner`, `.skeleton` | Every loader names what loads in words; motion stops with `prefers-reduced-motion` |
| Dialog | `<dialog>`, `<dialog className="sheet">`, `.dialog-actions` | Rises in over 200ms with a fading backdrop; a bottom sheet that slides up below 560px, actions full width |
| Toast | `<Toast message tone />` and `useToast()` (`areas/staff/retail/toast.tsx`), `.toast`, `.toast-success`, `.toast-error`, `.toast-info` | A dark neutral pill (`--color-toast`, dark in both themes) with a leading check, error or info icon and no coloured edge; at the bottom of the screen, centred on the content column beside the sidebar and above the sticky sale bar, rises in, gone after 4 seconds. A status for success and info, an alert for an error, hidden where the screen already says the same. `useItemAdded()` (#498) is the same toast for an item going on Sell, Buy stock, Move stock or Count stock: "Added <item>, now 3" for 2.5 seconds, and the line's `.line-added` flash (`.line-card`, a tint that fades over 1.2s on `--ease-out`, a still tint gone in one step under reduced motion), picked or scanned alike |
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
| Top bar (#526) | `StaffBar` (`areas/staff/route.tsx`) with `BranchTitle`, `TopMenu` and `OfflineBanner` (`areas/staff/top-bar.tsx`); `.staff-bar`, `.top-title`, `.top-menu`, `.offline-banner` in `ui/navigation.css` | The staff area's one 56px bar, sticky: on a phone the business small above the branch in use as the title (a native select laid over it switches branch; from 720px both are at the top of the sidebar instead, #564), Search, the sync dot only with news, and one menu (a disclosure, Escape and a press outside close it) holding who is signed in, Help, Refresh with when the page was loaded, the theme as a Segmented, and Sign out (from 720px, or while two-step set-up blocks the page). The shell draws no brand bar in the staff area, and its Powered-by footer only on More, Business set-up and Retail settings. Offline, a neutral banner under the bar says so |
| Ask button (#603) | `AskButton` (`areas/staff/help/how-do-i.tsx`) in `StaffBar` after Search; `.ask-open`, `.ask-open-word` in `ui/navigation.css`; the `ask` icon | On every staff screen but `/staff/help` itself and the minimal header of blocking two-step set-up: the ask icon and the word Ask (named Ask BMS) while the business has Ask BMS on, else How do I? (never Help, the top menu's tours sheet), ", New" in its name while the dot shows, a 48px target, the word hidden below 430px so the branch name stays readable (#605); `layout/header.layout.test.ts` lays the bar out at 360, 390 and 412px. It opens `/staff/help#ask`, where the cursor lands in the question box (the article search while Ask BMS is off or the phone is offline), and carries the help page's New dot until the person first opens `/staff/help` |
| Help | `useHelpSheet` and `HelpMenu` (`tour/host.tsx`) | A `dialog.sheet` with Take the tour and Show me this page; the staff area opens it from the top bar's menu |
| Sync status (issue #458, ADR-067) | `SyncStatus` in `frontend/src/offline/sync-status.tsx`, mounted in the top bar before Search, its dot drawn only while offline, sending, or with sales waiting or failed, and always for a manager (`retail.offline.resolve`), whose sheet settles other phones' sales (#526); `.sync-status`, `.sync-dot`, `.sync-dot-offline`, `.sync-dot-syncing`, `.sync-word`, `.sync-count` (`.sync-long`, `.sync-short`), `.sync-list`, `.sync-row`, `.sync-row-failed`, `.sync-reason` | A 12px dot, `--color-success` online, `--color-text-muted` offline, `--color-warning` while sending (a slow pulse, still with `prefers-reduced-motion`), with the state as visible text beside it (Online, Offline, Syncing) and "3 waiting to sync" ("3 waiting" at 480px and below); below 430px, in every state, it is the dot and the number waiting alone ("3"), so the branch name keeps its room (#605). The button's accessible name says the state, the count and the failed ones ("Offline, 3 waiting to sync, 1 failed"), so colour never carries the meaning alone (WCAG 1.4.1), and offline the banner under the bar says it in words; it is a 48px target (`--tap`, width and height, held by `layout/header.layout.test.ts`). Tapping it opens a `dialog.sheet` (a bottom sheet below 560px): the queued and failed sales with the time, branch, way of paying, total and the reason of a failure, Send again, Sync now, and for a manager the sales other phones could not record (Send it again, Discard with a reason) and the open clashes (Mark resolved with a note). It lives in the staff bar, not the shell's header, so it never sits beside the theme selector or a phone menu. Drawn only for a person who sells |
| Sign-out warning (issue #458) | `SignOutWarning` in `frontend/src/offline/sign-out-warning.tsx` | A `dialog.sheet` when sales wait: "3 sales are waiting to sync.", Sync now, Keep them and sign out, Cancel |

## Navigation (#455, #564)

One menu, grouped the way a shop owner thinks, the same on a phone and a laptop (#564, owner decisions D38 and D44, ADR-086,
which amends the menu decisions of #455 and #471; the approved picture is `docs/ui/grouped-menu-564/menu.html`). From 720px it
is a fixed sidebar; below 720px the menu button at the top left of the top bar opens the same list in a drawer from the left, and
the bottom tabs for the five daily jobs stay as they are (#539, decisions D29 and D31). Where the navigation sits changes at the
design system's phone width, `(max-width: 719px)`, read in code by `useIsPhone` (`components/side-menu.tsx`; the retail screens
re-export it) and in CSS by the `720px` media queries. CSS custom properties cannot be used in a media query, so the number is
the token. Tablets from 720 to 1023px therefore keep the sidebar and get no tabs; whether decision D29 ("side menu for laptops only")
means tablets should have the tabs too waits for the owner's sign-off (#544), and nothing changes until then.

- **One list, in groups.** `staffMenu` (`areas/staff/menu.tsx`) builds the groups once with the permission checks, and both the
  sidebar and the drawer draw them with the same component (`MenuLinks`, one `MenuItemLink` per row), so the rows, icons and the
  current row cannot drift apart. In order: **Home** and the help page (#603: Ask BMS while the business has it on, else How do I?, never Help, which names the tours sheet, with
  a New pill until the person first opens it); **Today**: Sell (for `useSells`), Orders, Approvals; **Stock**: My stock,
  Buy stock, Count stock, Move stock, Items and categories; **Money**: Cash book (the cash summary, else banking or cash
  withdrawals), Expenses, Customers who owe (credit sales, else customer accounts), What I owe suppliers, Savings and advances
  (savings, else advances); **Lending**, only for a business with lending: Loans, Members (or Collateral), Savings, Investments,
  Insights and, with the shop too, Loan reports; **Business**: Reports (the shop's figures and every shop page, `/staff/retail`;
  a lending business without the shop gets its own Reports page), Staff, Receive payments, SMS, AI assistant, Business set-up.
  A row the session may not open is not drawn, and a group with no rows is left out. There is no cap on the rows (the five
  shortcuts of #455 went), no nested Retail group and no headed Shop or Lending section that folds (#471). The names come from
  the glossary (`WORDS`), so the menu, the tabs and the page titles use the same words.
- **The look (product-ui-craft):** rows are 48px (`--tap`) with one 22px line icon in `currentColor` and the label; a group
  heading is small muted text (`.side-menu-heading`, `--text-xs`, `--color-text-muted`), not a button; the current row
  (`aria-current="page"`) has a tinted background (`--color-accent-tint`) and brand-colour text (`--color-link`), with no side
  stripe; a count is a muted number at the right of its row (`.side-menu-count`), never a coloured badge: Orders shows the open
  orders and My stock "N low", both from Home's own summary read (`useMenuCounts`, `homeSummaryQuery`), and nothing while the
  figure is 0. The count is read with the row ("Orders, 3": a `visually-hidden` comma). The one coloured mark is the New pill of a
  new feature (`NewBadge`, below), read as "Ask BMS, New". The only shadow is the drawer's
  (`--shadow-lg`); no gradients, no coloured stripes at a side or the top, no emoji.
- **Top and foot.** The top (`MenuShop` in `areas/staff/branch-picker.tsx`) shows the shop's logo, else the business's own logo
  (ADR-068), else the business's initials on a tint, never the platform's logo or name: the name is the loaded branding's
  non-empty `display_name` (`useBusinessName`), and off a business's host, while the branding loads, when it fails or when it
  has no name, the top shows a plain tile (`.menu-shop-blank`) and no name, the role and the branch still shown; the image's text names the shop or the business, whichever logo it is. Then the
  business name and the person's role (Owner for the owner, else the first role), and under them, on a full-width row of its own so nothing is cut off at 264px, the branch in
  use, a native select named Switch branch laid over it when there is more than one branch to choose. From 720px this is the
  only place the logo, the business and the branch show: the top bar keeps Search, the sync dot and the profile button, and the
  branch tour step (`branch-picker`, worded for both layouts) points at the sidebar's branch row, or at the whole top for a
  session with no branch to show; with no branch in use but several to choose, the row says Choose a branch. On a phone the
  top bar keeps its title and picker. Today carries the selling tour's `retail-nav` anchor only while it holds Sell or Orders.
  The foot (`StaffMenuFoot` in `areas/staff/route.tsx`) holds Notifications, Help and feedback (a button that opens the Help
  sheet, with its tours and Send feedback; in the drawer it closes the drawer first), then the person's initials, name and role,
  and Sign out on a row of its own under them, so nothing is cut off at 264px (a `BusyButton`, busy while signing out runs).
- **From 720px, the sidebar** (`SideNav`, a `div.side-nav`): a column at the left of the page, 264px wide, that stays in view
  while the page scrolls (`position: sticky`, the height of the window, the list scrolling between the top and the foot). It
  no longer folds to its icons. The page becomes two columns (`.nav-layout`, `.nav-layout-main`): the content takes the
  remaining width, centred up to `--content-width`, and clips the staff bar's full-bleed band, so nothing scrolls sideways. It
  comes first in the page, so Tab reaches the menu before the content.
- **Below 720px, the staff area's bottom tabs (#539):** `StaffTabBar` draws a `nav` named Main fixed to the bottom of the
  screen, one link per tab, in this order: **Sell** (the sale screen, for `useSells`), **My stock** (the stock list, for the
  `stock` screen), **Buy** (restock, the D24 word for it, for the `restock` screen), **Money** (`/staff/money`, while
  `moneyLinks` lists a page: the cash book pages, Customers who owe, Credit control, the customer accounts and ageing pages
  while `customer_accounts` is on, What you owe suppliers, Member savings with lending and Receive payments; the shop's own Savings is
  named Shop savings there, with its own hint, so the two are told apart, #544) and **More**
  (`/staff/more`, always). A tab whose module is off or whose page the session may not use is not drawn, so the bar holds one
  to five tabs; a business with lending only has Money (when it has a money page) and More, or More alone. A bar holding only More
  stays (#544): More lists the other areas and Sign out as well as the drawer does (#564). More lists `staffAreas` as rows; for a
  business with both modules the areas before the first one of a module (Home) come first, then the Shop and Lending headings
  with the rows of each module in menu order, then the other shared areas under Business; the Reports row stands for every shop
  page; then the shop in
  use (`CurrentShop`), the person's name and Sign out (the staff layout passes `signOut` and `signingOut` in `StaffContext`).
  Each tab is an icon above its label on a target 64px tall and at least 48px wide; the current one (`aria-current="page"`, on
  its page and the pages under it, such as Count under My stock) takes `--color-link`, a semibold label and a tinted pill
  (`--color-accent-tint`) behind the icon that fades and widens in by opacity and transform over `--motion`: no stripe, no
  glow, no shadow. Home and the pages only More lists have no current tab. My stock carries a badge with the count of items low
  on stock (Home's own summary read, `homeSummaryQuery` in `home-cards.tsx`: one key, one gate and one minute of freshness for both, so they share one request and the badge never asks for a session Home would not ask for); the
  number is `aria-hidden` and the link says ", 3 items low on stock" after its label. No other tab has a badge. The bar pads itself
  with `env(safe-area-inset-bottom)`, `-left` and `-right`; while it shows, the root has `has-tab-bar` and `--tab-bar`, the bar's
  measured height (a `ResizeObserver`, as the sale bar measures `--bottom-bar`, so a large font or text zoom is counted; the CSS
  value, `--tab-height` and the hairline plus the bottom inset, is only the first paint's). Every size of the bar is a token derived from `--tap`, `--space-*` and `--text-xs` (`--tab-height` 64px, `--tab-pill-width` 56px, `--tab-pill-height` 32px, `--tab-gap` 2px, `--tab-badge` 18px at the default font size, in `theme.css`, #544), and the badge's number is `--color-on-danger` on `--color-danger-fill`, its text growing with the font size up to 16px (`--tab-badge-text`) so it never covers the icon. `frontend/layout/tabs.layout.test.ts` (`npm run test:layout`, Chromium through Playwright, a CI step) lays the bar out at 360 and 412 wide, with a 34px home indicator inset simulated, at the default and double font size: five tabs of at least 48px that never overlap, each label under its icon, the badge off the next tab, the bar on the inset, the action bar on top of it and the page ending above both, and no sideways scroll. The body ends that far up, the sale bar and the
  member form's save bar stick at `bottom: var(--tab-bar-offset)` (the bar's height) so they sit on top of the tabs and never under them, and a toast rises
  above both. The bar steps aside only while the soft keyboard is up: a text field (an input that types, a textarea, or a `contenteditable` that is not `"false"`, so `""` and `"plaintext-only"` count, #544) has focus and `visualViewport` is more than
  150px shorter than the window, its height multiplied by its scale so a pinch zoom is not a keyboard. Under `interactive-widget=resizes-content` (Android) the window shrinks with the viewport and the bar stays, which is accepted. A field that only has focus (a page that focuses its Scan box, a barcode scanner or a Bluetooth
  keyboard) leaves it in place, and a browser without `visualViewport` never hides it. While aside it stays mounted with `hidden`,
  so its read is not asked again, and the root keeps `has-tab-bar` and the measured `--tab-bar`, so the body keeps its padding and
  the page does not get shorter under the field while the keyboard opens; the root takes `tab-bar-aside`, which only drops
  `--tab-bar-offset` to 0 so the sticky bars sit at the bottom (#544). A test fails if `index.html` ever sets
  `interactive-widget=resizes-content`. The staff bar has the menu button at its left on a phone (#564) and no Sell button; Sign out
  stays in its one menu while two-step set-up blocks the page (`signOutInBar`), since the tabs and More are not drawn then.
- **Below 720px, the drawer** (`SideMenu`): a menu button (`.menu-button`, the `menu` icon, `aria-label="Menu"`) at the top
  left of the bar opens the list in a drawer from the left: 85% of the width, at most 320px, over a dimmed backdrop
  (`--color-scrim`). It closes on the backdrop, Escape, a left swipe on the drawer (50px, more sideways than down), a chosen link,
  or any change of page (the back button, a tour, a redirect). The staff drawer's close button is out of sight until the keyboard
  reaches it. The operator portal's drawer keeps its Menu heading, and the person's name and Sign out at the bottom.
- **Drawer accessibility:** the drawer is `role="dialog"` named Menu, and `aria-modal="true"` only while the person opened it
  (a tour step that opens it leaves the page reachable); focus moves to its first control, Tab and Shift+Tab stay inside, and
  focus returns to the menu button on close. The menu button carries `aria-expanded`. Each group is a `role="group"` named by
  its heading. The page behind does not scroll: `body.side-menu-open` fixes the body at the scroll position (iOS Safari ignores
  `overflow` on the body), and closing gives the position back, except after a change of page or a tapped link. If the window
  widens past the phone width while the drawer is open, the drawer, its backdrop and the lock go, and focus moves to the
  sidebar's first link; narrowing with focus in the sidebar moves it to the menu button.
- **Phone edges, motion and theme:** the drawer pads itself with `env(safe-area-inset-top)`, `-bottom` and `-left` and slides
  in over `--motion` (once in 1ms under reduced motion). The sidebar and the drawer draw only with theme roles, so both
  follow light and dark.
- **Guided tours:** every `data-tour` anchor of the menu is in the sidebar from 720px and in the drawer below it: `nav-areas`
  (the list), `retail-nav` (the Today group of a shop), `nav-approvals`, `nav-lending`, `nav-members`, `nav-insights`,
  `nav-users`, `nav-retail` (Reports of a shop), `nav-notifications` and `nav-feedback` (the foot). A step that points at one
  opens the drawer at once, without animation and without taking focus from the tour card. The More tab still carries
  `data-tour-also` for them (#539); the tour host prefers the element with the id itself, so on a phone the drawer answers.
  The welcome tours have a `tabs` step on `data-tour="tab-bar"`, skipped on a laptop, worded for the tabs the person has
  (`bodyFor` on a step, `tabNamesFor` in `tour/tours.ts`, which a test holds to `staffTabs`; #544). The menu copy says "On a
  phone, tap the menu button at the top left".
- **One icon per item (#483):** no two items of one menu draw the same picture, and the same item keeps its icon wherever it
  shows: a shop row and its tile on the Retail page both read `icons[screen]`. Today: Sell `sale`, Orders `orders`, Approvals
  `approve`; Stock: My stock `stock`, Buy stock `restock`, Count stock `stocktake`, Move stock `transfer`, Items and categories
  `catalogue`; Money: Cash book `cashSummary`, Expenses `expenses`, Customers who owe `creditSales`, What I owe suppliers
  `supplierPayables`, Savings and advances `savings`; Lending: Loans `calendar`, Members `people`, Collateral `lock`, Savings
  `coins`, Investments `investments`, Insights `insights`, Loan reports `documents`; Business: Reports `reports`, Staff `user`,
  Receive payments `receivePayments`, SMS `phone`, AI assistant `assistant`, Business set-up `settings`; the foot:
  Notifications `bell`, Help and feedback `help` (a question mark in a circle). The Home cards and the More page head the
  modules with Shop `shop` and Lending `lending` (`STAFF_SECTIONS`). The member portal cards (`member/features.ts`): Balances
  `coins`, Loan schedule `calendar`, Payments `phone`.
- **Where it applies:** the staff area and the operator portal (one group, no head, its own foot). The member area has no
  area list and keeps its layout.

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
| `--color-on-danger` (text on the danger fill: the tab badge, #544) | `#ffffff` | `#ffffff` |
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
- `npm test` runs `src/areas/staff/menu-icons.test.tsx` (#483): two items of the staff side menu (its full list asserted, with Help and feedback and the module headings), the
  retail tiles and catalogue tiles (both as their pages draw them), the operator portal or the member portal cards that draw
  the same picture fail it, compared by the drawing itself. `home-cards.dom.test.tsx` checks the Home cards draw their side
  menu headings' icons, no two alike, and
  `setup.dom.test.tsx` that the Business set-up page, with everything rendered beside it, draws no picture twice.
- `npm test` runs the token contrast test and the design guard (`src/app/ui/design-guard.test.ts`: no
  `<style>` element, no inline HTML, and no hex colour in a component or a design layer stylesheet, and no palette token that would not follow the theme);
  Since #520 it also fails an emoji or a dash in any source, a gradient, glow or glass outside the two named stylesheets, a
  `.cluster.filters` row in place of Segmented, and, in `ui/kit.css`, a third radius or shadow, a target under 48px, an
  amount without tabular figures, a motion off the tokens or in a loop (bar the 400ms check), and a kit animation that still
  moves under reduced motion; since #603 it bans `infinite` (shorthand, longhands, `-webkit-`), since #605 a bare count above one (an exponent too) and an iteration-count longhand that is not a plain number up to one, and any `var()` but the motion tokens
  in an animation value in every stylesheet, bar the named work-in-progress loops (the spinners, the skeleton shimmer, the
  busy button, the loading mark's `.brand-loader-arc` and `.brand-loader-core`, the sync dot while sending); every `.new-blink`
  rule must run `rt-new-blink` five times; and no TS or TSX file may set an animation from code (an imported value as a style key
  included) or loop one with `Infinity` or `Number.POSITIVE_INFINITY`;
  `src/app/theme-mode.dom.test.tsx` covers the three choices, persistence, following the system, the pre-paint script and the
  theme colour.
- `/style` in `npm run dev` shows every component at the width of the window.
- `/staff/kit` (administrators only, not in the menu) shows every control of the component kit (#520) with made-up figures, for review on a phone.
- The screenshot walk in `docs/ui/design-system/README.md` checks every screen route for sideways
  overflow at 360px and runs axe-core (WCAG 2.1 A and AA rules, colour contrast included).

## Not yet

- The workbox precache does not list the font files, so an offline start falls back to the system font.
