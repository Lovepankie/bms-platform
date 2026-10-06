# Design system

**Status:** Draft · **Chore:** #95 · **Chapter:** `docs/sdd/11-member-channels.md` section 11.2.6 · **Builds on:** #87 (tenant branding, FR-TEN-08)

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
| `ui/feedback.css` | `.alert-*`, `.badge-*`, `.empty-state`, `.loading`, `.spinner`, `.skeleton`, `.toast` |
| `ui/navigation.css` | The staff bar, `.tabs`, the retail `.bottom-nav` |
| `ui/pages.css` | The landing page, `.auth-card`, the portal cards, the approvals decision cell |
| `ui/retail.css` | The retail classes (`.rt`, `.rt-card`, `.rt-row`, `.rt-total`, `.rt-flag`, `.rt-primary`), `.tile-grid`, `.tile`, `.line-foot` |
| `frontend/src/components/icons.tsx` | Inline SVG line icons (decorative, `aria-hidden`), so no image is fetched |

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

## Components

| Component | Markup | Notes |
|---|---|---|
| Secondary button | `<button>` | The default; 44px tall |
| Primary button | `.btn-primary` (retail: `.rt-primary`; #87: `.btn-brand`) | One per view, the main action, at the bottom of a form |
| Danger, ghost | `.btn-danger`, `.btn-ghost` | Reject or destroy; quiet actions such as Remove and Sign out |
| Link as button | `<Link className="btn btn-primary">` | Sizes `.btn-lg`, `.btn-sm` (still 44px tall), `.btn-block` |
| Field | `<label>Text<input/></label>` or `<label htmlFor>` then the input | Label above, `.field-hint` and `.field-error` under it; `aria-invalid="true"` turns the edge red |
| Choice row | `<label><input type="radio"/>Text</label>` | The whole row is the tap target; the chosen row is tinted |
| Vertical form | `.form-stack` | Fields 16px apart, buttons full width |
| One-time code | `.input-code` | Monospace, spaced |
| Value to copy | `.secret-box` with a `<code>` and a Copy button | The TOTP setup key |
| Card | `.card`, `.card-muted` | White, 8px corners, soft shadow |
| Table | `<div className="table-wrap" tabIndex={0}><table>` | Scrolls inside its card on a phone with edge shadows; `.num` cells right-aligned in tabular figures; `tabIndex` lets a keyboard scroll it |
| Tabs | `<nav className="tabs">` of `<Link>` | Active by the router's `data-status="active"`; scroll sideways on a phone |
| Retail bottom bar | `RetailNav` (`areas/staff/retail/nav.tsx`) | Retail home and three shortcuts the user may use; fixed to the bottom on a phone, pills above the title from 720px |
| Tile | `.tile-grid` of `.tile` | Retail home: icon, label, hint; two per row on a phone |
| Alert | `.alert` with `-danger`, `-warning`, `-success`, `-info` | Left rule and tint; the words carry the meaning |
| Badge | `.badge` with `-success`, `-warning`, `-danger`, `-info` | |
| Empty state | `.empty-state` | Dashed box, centred text |
| Loading | `.loading` (text with a spinner), `.spinner`, `.skeleton` | Motion stops with `prefers-reduced-motion` |
| Dialog | `<dialog>`, `<dialog className="sheet">` | A bottom sheet below 560px |
| Toast | `.toast` | Above the bottom bar; no screen raises one yet |

## Rules for a new screen

1. Use the components above; add a class to this catalogue rather than a style attribute. Never add an
   inline `<style>` element: the production CSP (`style-src 'self'`) refuses it.
2. Phone first: one column at 360px, nothing scrolls sideways except inside a `.table-wrap`, every tap
   target at least 44px, the main action last and full width.
3. State in words as well as colour; errors in `role="alert"` with `.alert-danger`.
4. Money through `formatMinor` (`src/components/money.ts`), in `.num` cells.
5. A tenant colour is applied for you; never write a brand colour.

## Checks

- `npm test` runs the token contrast test.
- The screenshot walk in `docs/ui/design-system/README.md` checks every screen route for sideways
  overflow at 360px and runs axe-core (WCAG 2.1 A and AA rules, colour contrast included).

## Not yet

- Dark theme tokens (`prefers-color-scheme: dark`): left out of the first release.
- The workbox precache does not list the font files, so an offline start falls back to the system font.
