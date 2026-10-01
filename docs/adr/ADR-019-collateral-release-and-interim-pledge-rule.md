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
