# Design system

**Status:** Draft · **Chore:** #95, #106 (visual polish) · **Chapter:** `docs/sdd/11-member-channels.md` section 11.2.6 · **Builds on:** #87 (tenant branding, FR-TEN-08)

The look of the PWA: calm, trustworthy business software in Rincol Tech blue. One design layer serves the
landing page, the sign-in family, the staff area, every retail screen, the member portal and the platform
placeholder. Screens use class names from this catalogue and never write a raw colour, size or shadow.

## Where it lives

| File | Holds |
|---|---|
| `frontend/src/app/theme.css` | The tokens (CSS custom properties), after the shell's `--brand` pair from #87 |
| `frontend/src/app/ui/index.css` | The entry: the Open Sans face, then every file below, imported once by `main.tsx` |
| `ui/base.css` | Reset, typography, links, focus ring, `.lead`, `.muted`, `.hint`, `.eyebrow`, `.visually-hidden` |
| `ui/layout.css` | The page frame around the shell (brand bar, content column, footer), `.page-header`, `.stack`, `.cluster`, `.grid-auto` |
| `ui/buttons.css` | Buttons |
| `ui/forms.css` | Labels, inputs, selects, choice rows, fieldsets, `.form-stack`, `.form-actions`, `.field-hint`, `.field-error`, `.input-code`, `.secret-box`, `.codes-box` |
| `ui/surfaces.css` | `.card`, `.card-muted`, `<dialog>` and `.sheet` |
| `ui/tables.css` | Tables and `.table-wrap` |
| `ui/feedback.css` | `.alert-*`, `.badge-*`, `.empty-state`, `.loading`, `.spinner`, `.skeleton`, `.toast`, `.toast-success`, `.toast-danger` |
| `ui/illustrations.css` | The paint classes of the illustrations (`.ill-*`), the state components (`.empty-state-art`, `.status-panel`, `.brand-loader`, `.skeleton-list`) and the shared keyframes |
| `ui/navigation.css` | The staff bar, `.tabs`, the retail `.bottom-nav` |
| `ui/pages.css` | The landing page (hero, trust line, module cards), `.auth-card` and `.auth-art`, the portal cards, the approvals decision cell, the style page |
| `ui/retail.css` | The retail classes (`.rt`, `.rt-card`, `.rt-row`, `.rt-total`, `.rt-flag`, `.rt-primary`), `.tile-grid`, `.tile`, `.line-foot` |
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
| Retail bottom bar | `RetailNav` (`areas/staff/retail/nav.tsx`) | Retail home and three shortcuts the user may use; fixed to the bottom on a phone, pills above the title from 720px |
| Tile | `.tile-grid` of `.tile` | Retail home: icon, label, hint; two per row on a phone |
| Stat tile | `.an-stats` of `.an-stat` (`.an-stat-label`, `.an-stat-value`, a `.hint`) | The retail dashboard: a figure with its label, two per row on a phone and four from 720px; `.an-placeholder` (dashed) marks a slot for a figure that does not exist yet and shows no number |
| Bars | `Bars` (`areas/staff/retail/analytics-ui.tsx`): an `<ol class="bars">` of a name, a figure and an inline `<svg class="bar">` | A ranking drawn as bars scaled to the largest. The figure is text; the SVG is `aria-hidden` and is colour from `.bar-fill` and `.bar-track`, never an attribute |
| Sparkline | `Sparkline` (same file): `<svg class="spark" role="img" aria-label>` with a `<polyline class="spark-line">` | A trend line from zero to the largest value, with the total in text beside it and the label saying what it shows |
| Alert | `.alert` with `-danger`, `-warning`, `-success`, `-info` | Left rule and tint; the words carry the meaning |
| Badge | `.badge` with `-success`, `-warning`, `-danger`, `-info` | |
| Empty state | `<EmptyState art="noSales" title="...">help</EmptyState>`; bare `.empty-state` for a line of text | The illustration, a title and a line of help in a dashed box; `art` is one of `noSales`, `noProducts`, `noApplications`, `nothingWaiting` |
| Success, error | `<StatusPanel tone="success" title="...">` | `role="status"` or `"alert"`; the title in the status colour |
| Loading | `<BrandLoader />` for a page, `<SkeletonList />` for a list, `.loading` for a line of text, `.spinner`, `.skeleton` | Every loader names what loads in words; motion stops with `prefers-reduced-motion` |
| Dialog | `<dialog>`, `<dialog className="sheet">`, `.dialog-actions` | Rises in over 200ms with a fading backdrop; a bottom sheet that slides up below 560px, actions full width |
| Toast | `.toast`, `.toast-success`, `.toast-danger` with `role="status"` | Above the bottom bar, rises in; no screen raises one yet |
| Illustration | `illustrations.hero`, `.shopfront`, `.secure`, `.success`, `.error`, and the four empty ones | Decorative (`aria-hidden`), under 2 KB each, no colour attribute: `.ill-tint`, `.ill-tint-strong`, `.ill-accent`, `.ill-primary` follow the tenant colour; `.ill-surface`, `.ill-line` are neutral; `.ill-success-*`, `.ill-danger-*` stay status colours. `shopfront` is the sign-up hero for the onboarding pages of #97 to adopt |
| Step progress | `StepHeader` (`areas/auth/steps.tsx`), `.step-progress` | "Step N of M" in words and bars, the step heading takes focus and sets the page title |
| Password pair | `PasswordFields`, `.password-row`, `.checklist` | Show or hide, the server's rules ticked off as the user types |
| Two-step set-up | `TwoStepSetup`, `QrCode`, `.qr-box`, `.manual-key` | QR code drawn in the browser (uqr), Open in authenticator, the key in groups of four behind "Cannot scan?" |
| Recovery codes | `RecoveryCodes`, `.codes-grid`, `.button-pair` | Two columns, Copy all, Download as text, a required tick |
| Guided tour | `tour/` (`.tour-card`, `.tour-hole`, `.tour-backdrop`, `.tour-dots`) | A card docked top or bottom, away from the lit target; the hole is placed with CSS custom properties, never a style attribute (ADR-025) |
| Tour anchor | `data-tour="<id>"` on the element a tour step lights up | Stable ids, never a CSS class; `tour/tours.test.tsx` fails when one disappears |
| Help | `HelpMenu` in the staff bar | A `dialog.sheet` with Take the tour and Show me this page |

## Rules for a new screen

1. Use the components above; add a class to this catalogue rather than a style attribute. Never add an
   inline `<style>` element: the production CSP (`style-src 'self'`) refuses it. Never write a hex
   colour outside `theme.css`; a new illustration uses the `.ill-*` classes. Add anything new to the
   style page.
2. Phone first: one column at 360px, nothing scrolls sideways except inside a `.table-wrap`, every tap
   target at least 44px, the main action last and full width.
3. State in words as well as colour; errors in `role="alert"` with `.alert-danger`.
4. Money through `formatMinor` (`src/components/money.ts`), in `.num` cells.
5. A tenant colour is applied for you; never write a brand colour.
6. Give the main controls of a new screen `data-tour` ids and add the screen's steps to
   `frontend/src/tour/tours.ts` (a page tour at least), with plain, short sentences.

## Checks

- `npm test` runs the token contrast test and the design guard (`src/app/ui/design-guard.test.ts`: no
  `<style>` element, no inline HTML, and no hex colour in a component or a design layer stylesheet).
- `/style` in `npm run dev` shows every component at the width of the window.
- The screenshot walk in `docs/ui/design-system/README.md` checks every screen route for sideways
  overflow at 360px and runs axe-core (WCAG 2.1 A and AA rules, colour contrast included).

## Not yet

- Dark theme tokens (`prefers-color-scheme: dark`): left out again in #106. The tenant colour rule and
  the AA checks are written for light surfaces, so a dark theme needs its own contrast rule for `--brand`.
- The workbox precache does not list the font files, so an offline start falls back to the system font.
