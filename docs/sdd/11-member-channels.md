# 11. Member Channels

**Status:** Draft · **Owner:** Hillary

## 11.1 Purpose

How a tenant's members reach the platform: the member area of the PWA, SMS, USSD
(phase 2) and mobile money payments. Staff use the staff area only; field officers use it
on Android phones, which is covered by the same PWA rules (ADR-009, chapter 4 section
4.8).

| Channel | Phase | Reach | Member can |
|---|---|---|---|
| PWA member area | P2 (after the staff MVP) | Smartphone with data | See balances, schedules, statements; apply; invest; pay |
| SMS (outbound) | MVP | Every phone | Receive receipts, reminders, arrears notices, one-time codes |
| USSD | Later (phase 2 of channels) | Every phone, no data | Check balance and next due amount, mini statement, pay |
| Mobile money | P2 | Every MTN or Airtel wallet | Pay loan instalments, deposit, fund investments |

## 11.2 PWA member area

### 11.2.1 Access

- The member opens `https://<tenant slug>-bms.rincoltech.com/` (the tenant host, chapter 7 section 7.2) and chooses "Member". The
  tenant's branding (display name, logo) comes from tenant settings.
- First use: portal activation (FR-IAM-09): phone number, SMS one-time code, set a 5
  digit PIN. Staff trigger an invitation SMS with a link from the member record, or the
  member starts activation directly; either way the phone must belong to a member record
  of that tenant.
- Later: phone plus PIN. Refresh sessions last up to 30 days idle, so a member signs in
  rarely.
- Installation: the browser offers "Add to Home screen"; the app shows an install hint on
  Android after the second visit.

### 11.2.2 Screens

| Screen | Content | Requirement |
|---|---|---|
| Home | Next payment due (amount, date), total loan balance, savings balance, active investments, any arrears in red, "Pay now" | FR-MSS-01, FR-MSS-02 |
| Loans | List of loans; each opens to balance, DPD, schedule with paid and outstanding per instalment, transactions, download statement | FR-MSS-01 |
| Apply | Choose product, amount, term, purpose; shows an indicative schedule from the product preview; submits a draft to the officer | FR-MSS-03 |
| Savings | Accounts, balances, transactions | FR-MSS-02 |
| Invest | Products with terms and rates, application, active investments with maturity and return, maturity instruction | FR-MSS-03, FR-INV-05 |
| Pay | Choose what to pay (loan, savings, investment), amount (defaults to the next due amount), wallet phone; shows progress and the outcome | FR-MSS-04 |
| Documents | Receipts and statements | FR-MSS-05 |
| Profile | Name, phone, member number, next of kin (read only), change PIN, sign out | |

### 11.2.3 Offline behaviour

Chapter 4 section 4.8 is normative. In short: the shell loads offline; Home, Loans,
Savings and Investments show the last synced data with a "last updated" time; Apply
drafts are kept locally; Pay and any other money action are disabled offline with a
message.

### 11.2.4 Performance

The member area is its own lazily loaded route tree, under 250 KB of compressed
JavaScript at first load (NFR-PERF-06). Lists page at 20 items. Images are avoided except
the tenant logo.

### 11.2.5 Branding and the shared shell (FR-TEN-08)

The brand bar, the theme colour and the footer belong to the shared shell, the root layout of the
PWA, so every area inherits them (sign-in, accept-invitation, staff, member, platform console).
A tenant host reads the public `GET /api/v1/branding` (display name, theme colour, logo URL and
enabled module keys; no sign-in) and shows the logo with the display name as its alt text, or the name as text when there
is no logo. The platform host and any unknown host show the Rincoltech brand. Every screen ends
with the footer "Powered by" and the Rincoltech logo (`/brand/rincoltech-logo.png`), a link to the
Rincoltech site with an accessible name; a tenant cannot remove it.

The theme colour reaches the screens as two CSS custom properties on `:root`, set once by the
shell and absent when the tenant has no colour (the defaults in `frontend/src/app/theme.css`
apply):

| Variable | Meaning | Use |
|---|---|---|
| `--brand` | The tenant's theme colour, `#RRGGBB` | Backgrounds, borders and accents |
| `--brand-contrast` | `#FFFFFF` or `#111111`, whichever reaches contrast 4.5 on `--brand` | Text and icons drawn on `--brand` |

Verticals (retail, lending) and every area use only these variables, never a hard-coded brand
colour, and draw text on `--brand` only in `--brand-contrast`; `--brand` alone is not guaranteed
readable as text on white. The contrast rule lives in one tested pure function
(`frontend/src/app/contrast.ts`) and in the same rule in the API (`BrandColour`), both checked
against the same reference values (`fixtures/brand-contrast.json`, read by both tests). The rule
is about text. A very light `--brand` is allowed and makes the brand bar's border faint on a white
page; the logo stays the main brand mark.

### 11.2.6 Design tokens and components (#95)

Every area draws with one design layer in the Rincol Tech look: white surfaces on a light grey page,
Open Sans (self-hosted from `@fontsource-variable/open-sans`, so no font CDN and nothing extra in the
CSP), bold headings, 8px corners and soft shadows. The tokens are CSS custom properties in
`frontend/src/app/theme.css`; the component styles are in `frontend/src/app/ui/`, imported once by
`main.tsx`. The catalogue and the rules for new screens are in `docs/ui/design-system.md`.

| Group | Tokens (examples) |
|---|---|
| Colour roles | `--color-bg`, `--color-surface`, `--color-text`, `--color-text-muted`, `--color-border`, `--color-border-strong`, `--color-primary`, `--color-on-primary`, `--color-accent`, `--color-link`, `--color-focus`, `--color-danger`, `--color-success`, `--color-warning` and their `-tint` backgrounds |
| Type | `--font-sans`, `--font-mono`, `--text-xs` to `--text-3xl`, `--weight-regular` to `--weight-bold` |
| Space, shape, depth | `--space-1` (4px) to `--space-8` (56px), `--radius-sm`, `--radius`, `--radius-lg`, `--shadow-sm`, `--shadow`, `--shadow-lg` |
| Layers and interaction | `--z-nav`, `--z-overlay`, `--z-modal`, `--z-toast`, `--tap` (44px), `--focus-ring` |

The Rincol blue `#00adef` reads at only 2.5:1 on white, so it is the accent (borders, focus halos,
tints, icons) and never text; text, links and button fills use `#0077b6` (4.87:1 with white). The
shell's `--brand` pair defaults to that blue, and when a tenant has a colour it replaces the
primary and the accent, while links and focus rings use a darkened mix of it (section 11.2.5:
`--brand` alone is not guaranteed readable on white). `src/app/ui/tokens.test.ts` checks the text
tokens against 4.5:1 and input edges against 3:1 with the same `contrast.ts` the branding rule uses.

Components: buttons (a bare `<button>` is the secondary style; `.btn-primary`, `.btn-danger`,
`.btn-ghost`, `.btn` for links), labelled inputs, selects and choice rows of at least 44px, field
hints and errors, cards, tables in a `.table-wrap` that scrolls sideways inside its card on a phone
with right-aligned tabular numbers, tabs, the staff bar, the retail bottom bar, badges, alerts, empty
states, a spinner and skeleton, the `<dialog>` sheet and a toast. Screens carry no inline `<style>`
element, which the production CSP (`style-src 'self'`) refuses.

The visual polish (#106) adds original inline SVG illustrations (`src/components/illustrations.tsx`:
empty lists, success, error, the landing and sign-up heroes, the sign-in drawing) painted only by
token classes, so a tenant colour tints the accent family and status colours stay; shared state
components (`src/components/states.tsx`: `EmptyState`, `StatusPanel`, `BrandLoader`, `SkeletonList`);
pressed and disabled buttons; focus on fields however it came; card elevation; tables that become
cards below 560px (`.table-cards`); and motion of 150 to 250 ms that stops under
`prefers-reduced-motion`. The public landing page follows the tenant's enabled modules (#99). A
living style page at `/style` (development builds and the platform host only, a chunk of its own)
shows every component and illustration, and `src/app/ui/design-guard.test.ts` fails a component
that renders a `<style>` element or writes a hex colour outside `theme.css`. Dark mode is still
left out: the tenant colour rule and the AA checks are written for light surfaces.

## 11.3 SMS

### 11.3.1 Events and default templates

Templates are per tenant and editable (FR-NTF-03). Defaults, all fabricated wording, kept
within one 160 character GSM-7 segment where possible:

| Event key | When | Default text |
|---|---|---|
| `loan.disbursed` | Disbursement executed | `{tenant_name}: Loan {loan_no} of {currency} {amount} disbursed on {date}. First payment {next_amount} due {next_due_date}.` |
| `loan.repayment_received` | Repayment recorded | `{tenant_name}: Received {currency} {amount} for loan {loan_no}. Receipt {receipt_no}. Balance {balance}.` |
| `loan.due_in_3_days` | 3 days before a due date, inside the sending window | `{tenant_name}: Reminder, {currency} {amount} for loan {loan_no} is due on {due_date}.` |
| `loan.due_today` | On the due date | `{tenant_name}: {currency} {amount} for loan {loan_no} is due today.` |
| `loan.overdue_1`, `loan.overdue_7`, `loan.overdue_30` | At 1, 7 and 30 DPD | `{tenant_name}: Loan {loan_no} is {dpd} days overdue. Amount in arrears {currency} {arrears}. Please pay to avoid penalties.` |
| `loan.closed` | Loan closed | `{tenant_name}: Loan {loan_no} is fully paid. Thank you.` |
| `savings.deposit`, `savings.withdrawal` | Transaction recorded | `{tenant_name}: {txn_type} of {currency} {amount} on account {account_no}. Balance {balance}.` |
| `investment.funded`, `investment.maturing`, `investment.paid_out` | Lifecycle | Similar pattern |
| `auth.member_otp` | Activation or PIN reset | `{code} is your {tenant_name} code. It expires in 10 minutes. Do not share it.` |

### 11.3.2 Rules

- Reminders and arrears messages only inside the tenant's sending window (FR-NTF-04);
  receipts and codes immediately.
- One message per event per schedule item (FR-NTF-06).
- Messages go to the member's primary phone; never to next of kin in the MVP (contacting
  next of kin about a debt raises privacy questions the tenant must decide; listed as an
  open question).
- Sender ID: the tenant's approved sender name if registered with the aggregator, else the
  aggregator's shared sender.
- Cost: segments are counted per tenant (FR-NTF-07).

## 11.4 USSD (later)

Depends on the aggregator decision (pending ADR-013) and a USSD code assignment. Design
intent, so the API and data model do not block it:

```
*<code>#
1. Loan balance        -> balance, next due amount and date
2. Mini statement      -> last 3 transactions
3. Pay loan            -> amount (default next due) -> confirm -> mobile money prompt
4. Savings balance
0. Exit
```

- The member is identified by the calling number (the aggregator passes it) and confirms
  with their PIN before any balance is shown.
- Tenant identification: one USSD code per tenant, or a shared code with a tenant choice
  menu; decided with the aggregator.
- Sessions are stateless on the server apart from a short-lived session record keyed by
  the aggregator's session id (expires after 3 minutes; a small PostgreSQL table purged by a
  job, ADR-008).
- USSD responses must return within the aggregator's time limit (typically a few seconds);
  the payment step only creates the payment intent and returns.

## 11.5 Payments from the member's side

1. The member chooses what to pay and the amount, and confirms the wallet phone (default:
   their own).
2. The platform creates a payment intent (FR-PAY-01) and asks the gateway to prompt the
   phone.
3. The member approves the prompt on their phone with their mobile money PIN (the platform
   never sees it).
4. The app polls the intent every 3 seconds for up to 2 minutes and shows `succeeded`,
   `failed` or "still waiting, we will SMS you".
5. On success the repayment or deposit is booked (FR-PAY-03) and the receipt SMS is sent.

Gateway specifics are in chapter 12; the choice of gateway is pending ADR-011.

## 11.6 Staff in the field

Loan officers register members, capture applications and log collection visits from a
phone. The staff area is responsive to 360 px, forms save drafts offline
(NFR-OFF-03), and the document capture step uses the phone camera for ID photos.
Money-moving actions stay online-only (NFR-OFF-04).
