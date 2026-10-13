# ADR-019: Collateral release as an approval action, and the interim duplicate pledge rule

## Status

Accepted (2026-10-01, merged in pull request #24, issue #13). Builds on ADR-015. Implements FR-COL-01 and
FR-COL-04 in MVP increment 3.

## Context

FR-COL-04 makes releasing collateral a maker-checker action that is allowed only when every loan
the item secures is `closed`, `cancelled` or `rejected`, and chapter 8 section 8.4 lists
`collateral_release` with no threshold. ADR-015 gives the core an approval mechanism that executes
actions registered by the modules that own them. Increment 3 delivers the collateral register
before loans exist (increment 4), so the loan condition cannot be evaluated yet.

FR-COL-01 refuses a plate or title number "already pledged on an open loan". With no loans, the
register needs a rule of its own now, and a rule enforced only by a check before insert lets two
concurrent registrations of the same reference both pass.

## Decision

- **Release is an `ApprovalAction`.** `lending.collateral` registers `CollateralReleaseAction`
  (`collateral_release`, subject `lending.collateral`, maker `lending.collateral.release_request`,
  checker `lending.collateral.release_approve`, no threshold). `POST /lending/collateral/{id}/release`
  locks the item, requires `If-Match`, refuses items that are `released`, `seized` or `disposed`
  (409 `invalid_status_transition`) and stores the request with the item's version; it answers
  202 with the approval id. On approval the item becomes `released` and the custody timeline records
  who collected it and the approval id. Any custody change in between bumps the version, so the
  request goes stale (`subject_changed`) instead of releasing a seized item; `execute` re-checks the
  status and fails with a 409 of its own if it ever runs anyway.
- **Interim duplicate rule.** Until loans exist, `collateral_already_pledged` applies to another item
  of the same type and normalised reference that is not `released` or `disposed`. A partial unique
  index on `(tenant_id, collateral_type, reference_no_normalised)` for those rows enforces it in the
  database; the service checks first for a readable error and maps the index violation to the same
  409.
- **When loans arrive** (increment 4), release also requires that no linked loan is open
  (`collateral_secures_open_loan`), and the duplicate rule is revisited against
  `lending_loan_collateral`: whether an item may secure several loans is a product question for
  that increment.

## Consequences

**Better:** release gets the whole approval mechanism (queue, self-approval refusal backed by the
database CHECK, stale detection, expiry, audit) with no new approval code. The duplicate rule holds
under concurrency, proven by a concurrent registration test.

**Worse:** until increment 4 the duplicate rule is stricter than the spec: a reference cannot be
registered twice while pledged even if no loan uses it yet. Releasing or disposing the first item
frees the reference.

**Watch for:** increment 4 must replace the interim index if one item may back several loans, and
must add the open-loan check to both the release request and `execute`.

## Amendment: the rule once loans exist (2026-10-02, issue #41)

Accepted with increment 4. The product question left open above is decided: **an item secures at
most one open loan.**

- A pledge is a `lending_loan_collateral` row; it is open while `released_at` is null. A pledge is
  released when its loan becomes `cancelled`, `rejected` or `closed`. A `written_off` loan keeps
  its pledges (the collateral is being recovered). A draft holds its pledges, and a loan returned
  for correction keeps them.
- Setting a draft's pledges and submitting it lock each item row (in id order) before checking,
  and the release request and `CollateralReleaseAction.execute` lock the same row before they ask
  `CollateralPledges`. A pledge and a release of one item, or two pledges of it, therefore run one
  after the other.
- The partial unique index `lending_loan_collateral_one_open_pledge (tenant_id, collateral_id)
  WHERE released_at IS NULL` is the backstop, mapped to the same 409 `collateral_already_pledged`.
- The interim index on the register (one live item per type and reference) stays: it is about
  registering the same asset twice, which the one-open-loan rule does not cover.

A two-thread test pledges one item to two drafts at once and expects one 200 and one 409.

Addendum (2026-10-10, issue #176): a repayment reversal that reopens a closed loan restores that
loan's released pledges under the same item locks (in pledging's order: the text order of the UUID,
which is PostgreSQL's, never `UUID.compareTo`), for each item still held
and securing no other loan. An item handed back or pledged elsewhere stays where it is: the reversal
request lists it for the checker, and the reopen is audited as partly unsecured. The reversal is
never refused for collateral. A request that reopens a loan without some items says so
(`reopen_would_be_unsecured`) and needs a checker who holds `lending.collateral.release_approve`, an
existing seeded permission, so the accountant who approves other reversals cannot approve it; no new
permission was added. The run refuses (409 `collateral_facts_changed`, nothing written, the request ends as stale
with the reason so the maker can ask again) if it would leave out an item the request did not list, because handing an item back does not bump the loan version. The one-open-pledge index stays the backstop; V42 narrows the guard
exception to the reopened loan alone.
