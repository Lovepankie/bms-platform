# Design system

**Status:** Draft · **Chore:** #95, #106 (visual polish), #429 (light and dark theme) · **Chapter:** `docs/sdd/11-member-channels.md` section 11.2.6 · **Builds on:** #87 (tenant branding, FR-TEN-08)

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
| `ui/feedback.css` | `.alert-*`, `.badge-*`, `.empty-state`, `.loading`, `.spinner`, `.skeleton`, `.toast`, `.toast-success`, `.toast-danger`, the header refresh button (`.refresh`, `.refresh-status` readable by assistive tech but off screen below 720px, `.refresh-problem` visible at every width) |
| `ui/illustrations.css` | The paint classes of the illustrations (`.ill-*`), the state components (`.empty-state-art`, `.status-panel`, `.brand-loader`, `.skeleton-list`) and the shared keyframes |
| `ui/navigation.css` | The staff bar, `.tabs`, the retail `.bottom-nav` |
| `ui/pages.css` | The landing page (hero, trust line, module cards), `.auth-card` and `.auth-art`, the portal cards, the approvals decision cell, the style page |
| `ui/retail.css` | The retail classes (`.rt`, `.rt-card`, `.rt-row`, `.rt-total`, `.rt-flag`, `.rt-primary`), `.tile-grid`, `.tile`, `.line-foot` |
| `ui/search.css` | The shared search box (#241): `.sb`, `.sb-field`, `.sb-clear`, `.sb-status`, `.sb-searching`, `.sb-results` (dimmed while `aria-busy`), `.sb-mark` |
| `ui/onboarding.css` | The sign-up, applicant and operator portal additions (ADR-024): `.facts` (a definition list that stacks on a phone), `.filters` (the pressed status filter), `.pre-line`, `.wrap-anywhere`; those screens otherwise use `.auth-card`, `.form-stack`, `.alert`, `.table-wrap`, `.tabs` and `.cluster` |
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
- Motion (#106): `--motion-fast` 150ms (hover, press), `--motion` 200ms (dialogs, toasts), `--motion-slow`
  250ms (a card or state rising in), all on `--ease-out`. Under `prefers-reduced-motion` every animation
  and transition runs once in 1ms (`base.css`), so a spinner stands still.

## Components

| Component | Markup | Notes |
|---|---|---|
| Secondary button | `<button>` | The default; 44px tall. Pressed: it sinks to 98% and darkens. Disabled (`disabled` or `aria-disabled="true"`): flat grey with muted text |
| Primary button | `.btn-primary` (retail: `.rt-primary`; #87: `.btn-brand`) | One per view, the main action, at the bottom of a form |
| Danger, ghost | `.btn-danger`, `.btn-ghost` | Reject or destroy; quiet actions such as Remove and Sign out |
| Link as button | `<Link className="btn btn-primary">` | Sizes `.btn-lg`, `.btn-sm` (still 44px tall), `.btn-block` |
| Field | `<label>Text<input/></label>` or `<label htmlFor>` then the input | Label above, `.field-hint` and `.field-error` under it; `aria-invalid="true"` turns the edge red; a focused field shows the focus edge and halo however it got focus, and a wrapping label turns the link colour |
| Choice row | `<label><input type="radio"/>Text</label>` | The whole row is the tap target; the chosen row is tinted |
| Vertical form | `.form-stack` | Fields 16px apart, buttons full width |
| One-time code | `.input-code` | Monospace, spaced |
| Value to copy | `.secret-box` with a `<code>` and a Copy button | The TOTP setup key |
| Card | `.card`, `.card-muted`, `.card-raised`, `.card-interactive` | White, 8px corners, soft shadow; `.card-interactive` (a card that is a link) lifts on hover and sinks on press |
| Table | `<div className="table-wrap" tabIndex={0}><table>` | Scrolls inside its card on a phone with edge shadows; `.num` cells right-aligned in tabular figures; `tabIndex` lets a keyboard scroll it |
| Table as cards | `.table-wrap.table-cards` and `data-label` on every `<td>` | Below 560px each row is a card of label and value pairs (no sideways scroll); `.cell-stack` puts controls under their label. Used by approvals and the member list |
| Tabs | `<nav className="tabs">` of `<Link>` | Active by the router's `data-status="active"`; scroll sideways on a phone |
| Breadcrumbs | `StaffBreadcrumbs` (`components/breadcrumbs.tsx`): `<nav aria-label="Breadcrumb">` with an `<ol>` | One trail under the staff bar from the routes' `crumb` labels (FR-UXN-01); the last item is `aria-current="page"`; below 720px only Back to the parent and the current page |
| Category group | `GroupControls`, `GroupedBodies`, `GroupedCards` (`areas/staff/retail/category-groups.tsx`), `.cat-head` | A list in expandable category sections: a full-width header button (`aria-expanded`, `aria-controls`) with the count and subtotal, a Group by category switch, Expand all and Collapse all (FR-UXN-02) |
| Retail bottom bar | `RetailNav` (`areas/staff/retail/nav.tsx`) | Retail home and three shortcuts the user may use; fixed to the bottom on a phone, pills above the title from 720px |
| Tile | `.tile-grid` of `.tile` | Retail home: icon, label, hint; two per row on a phone |
| Stat tile | `.an-stats` of `.an-stat` (`.an-stat-label`, `.an-stat-value`, a `.hint`) | The retail dashboard: a figure with its label, two per row on a phone and four from 720px; `.an-placeholder` (dashed) marks a slot for a figure that does not exist yet and shows no number |
| Bars | `Bars` (`areas/staff/retail/analytics-ui.tsx`): an `<ol class="bars">` of a name, a figure and an inline `<svg class="bar">` | A ranking drawn as bars scaled to the largest. The figure is text; the SVG is `aria-hidden` and is colour from `.bar-fill` and `.bar-track`, never an attribute |
| Sparkline | `Sparkline` (same file): `<svg class="spark" role="img" aria-label>` with a `<polyline class="spark-line">` | A trend line from zero to the largest value, with the total in text beside it and the label saying what it shows |
| Alert | `.alert` with `-danger`, `-warning`, `-success`, `-info` | Left rule and tint; the words carry the meaning |
| Badge | `.badge` with `-success`, `-warning`, `-danger`, `-info` | |
| Search box | `useLiveSearch` and `<SearchBox id label search fetching count more>` (`app/ui/`) with `<SearchResults fetching>`, `<NoResults term onClear noun>` and `<Highlight text term>` | Every search on every screen: the label, the input with a Clear button, and a polite status line ("Searching", "12 results", `No results for "x"`). The text is live, the term follows after 250 ms, Escape clears, the old list stays dimmed until the new answer arrives. Pass `placeholderData: keepPreviousData` and the query function's `signal`. Rules: `docs/specs/search.md` section 2 |
| Form fields | `TextField`, `SelectField`, `Group`, `SaveBar`, `useFormErrors` (`areas/staff/lending/form-fields.tsx`; `.mf`, `.mf-field`, `.mf-group`, `.mf-savebar`) | A label above each input, hint and error tied with `aria-describedby`, `aria-invalid`, focus to the first refused input, groups as fieldset and legend, and a save bar stuck to the bottom of a phone with what blocks it in words |
| Photo control | `PhotoQueueControl`, `usePhotoQueue` (`areas/staff/lending/photo-queue.tsx`, `image-prep.ts`; `.ph-*`) | Take a photo (camera) or Choose a file, checked and shrunk in the browser, progress, Send again that does not shrink twice |
| Empty state | `<EmptyState art="noSales" title="...">help</EmptyState>`; bare `.empty-state` for a line of text | The illustration, a title and a line of help in a dashed box; `art` is one of `noSales`, `noProducts`, `noApplications`, `nothingWaiting` |
| Success, error | `<StatusPanel tone="success" title="...">` | `role="status"` or `"alert"`; the title in the status colour |
| Loading | `<BrandLoader />` for a page, `<SkeletonList />` for a list, `.loading` for a line of text, `.spinner`, `.skeleton` | Every loader names what loads in words; motion stops with `prefers-reduced-motion` |
| Dialog | `<dialog>`, `<dialog className="sheet">`, `.dialog-actions` | Rises in over 200ms with a fading backdrop; a bottom sheet that slides up below 560px, actions full width |
| Toast | `.toast`, `.toast-success`, `.toast-danger` with `role="status"` | Above the bottom bar, rises in; no screen raises one yet |
| Illustration | `illustrations.hero`, `.shopfront`, `.secure`, `.success`, `.error`, and the four empty ones | Decorative (`aria-hidden`), under 2 KB each, no colour attribute: `.ill-tint`, `.ill-tint-strong`, `.ill-accent`, `.ill-primary` follow the tenant colour; `.ill-surface`, `.ill-line` are neutral; `.ill-success-*`, `.ill-danger-*` stay status colours. `shopfront` is the sign-up hero for the onboarding pages of #97 to adopt |
| iPhone install pictures | `IosSteps` in `frontend/src/install/InstallPrompt.tsx`, drawings in `src/install/ios-pictures.tsx` (#448) | One drawing of the part of Safari beside each of the four install steps (a normal tab, Share in the toolbar, Add to Home Screen and View More in the share sheet, Add at the top right): our own simple shapes, never screenshots or Apple artwork; unlike the decorative illustrations each is `role="img"` with a short `aria-label` that describes the drawing ("Picture: ..."), so a screen reader does not read the step twice; a 100 unit viewBox drawn at 104px with 11 unit labels, so they read at about 11px on a 360px phone; under 2 KB each; painted by the `.ill-*` classes plus `.ill-accent-ring` (what to tap), `.ill-thin` (the Share icon), `.ill-dashed` and `.ill-text*`; View More sits in a frame of its own under the Add to Home Screen row, since it is a second place to look, not a row seen beside it; step 2 says that on an iPad Share is at the top right; `.ios-step` keeps the words first and moves the picture under them on a narrow phone |
| Step progress | `StepHeader` (`areas/auth/steps.tsx`), `.step-progress` | "Step N of M" in words and bars, the step heading takes focus and sets the page title |
| Password pair | `PasswordFields`, `.password-row`, `.checklist` | Show or hide, the server's rules ticked off as the user types |
| Two-step set-up | `TwoStepSetup`, `QrCode`, `.qr-box`, `.manual-key` | QR code drawn in the browser (uqr), Open in authenticator, the key in groups of four behind "Cannot scan?" |
| Recovery codes | `RecoveryCodes`, `.codes-grid`, `.button-pair` | Two columns, Copy all, Download as text, a required tick |
| Guided tour | `tour/` (`.tour-card`, `.tour-hole`, `.tour-backdrop`, `.tour-dots`) | A card docked top or bottom, away from the lit target; the hole is placed with CSS custom properties, never a style attribute (ADR-025) |
| Tour anchor | `data-tour="<id>"` on the element a tour step lights up | Stable ids, never a CSS class; `tour/tours.test.tsx` fails when one disappears |
| Tour rules (issue #292) | `tour/model.ts` `autoTourFor`, `host.tsx` | At most one tour starts by itself per page load; the rest wait for the next visit. A user with no tour history gets only the first-run (Welcome) tour, and every "New:" tour is marked seen for them at that moment, since they have nothing to compare against. One "Do not show tours again" checkbox (progress key `tours-off`) stops every automatic tour, "New:" ones included; Help still replays any tour |
| Help | `HelpMenu` in the staff bar | A `dialog.sheet` with Take the tour and Show me this page |

## Light and dark theme (#429)

A selector in the header, at the top right of every area (staff, member, operator, public pages), offers **System**,
**Light** and **Dark**. It is a native `<select>` with `aria-label="Theme"` and the shared focus ring, so it is reachable
by keyboard (it sits last in the `.header-actions` group, after the refresh button of #430), and the welcome tours point at it (`data-tour="theme-select"`, the step "Light or dark").

- **Remembered per person per device.** The choice is kept in the browser's `localStorage` under `bms.theme` (`light` or
  `dark`; System keeps nothing). It is never sent to the server: a person's browser profile on a device is the person.
- **Follows the system by default.** While the choice is System the theme follows `prefers-color-scheme`, live.
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
- **Blocked storage.** If the browser blocks `localStorage`, the choice is held in memory for the visit, so the picker, the
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
   target at least 44px, the main action last and full width.
3. State in words as well as colour; errors in `role="alert"` with `.alert-danger`.
4. Money through `formatMinor` (`src/components/money.ts`), in `.num` cells.
5. A tenant colour is applied for you; never write a brand colour. Draw with the colour roles, never a palette token, so
   the screen follows the theme (see Light and dark theme above); check a new screen in both.
6. Give the main controls of a new screen `data-tour` ids and add the screen's steps to
   `frontend/src/tour/tours.ts` (a page tour at least), with plain, short sentences.

## Checks

- `npm test` runs the token contrast test and the design guard (`src/app/ui/design-guard.test.ts`: no
  `<style>` element, no inline HTML, and no hex colour in a component or a design layer stylesheet, and no palette token that would not follow the theme);
  `src/app/theme-mode.dom.test.tsx` covers the three choices, persistence, following the system, the pre-paint script and the
  theme colour.
- `/style` in `npm run dev` shows every component at the width of the window.
- The screenshot walk in `docs/ui/design-system/README.md` checks every screen route for sideways
  overflow at 360px and runs axe-core (WCAG 2.1 A and AA rules, colour contrast included).

## Not yet

- The workbox precache does not list the font files, so an offline start falls back to the system font.
