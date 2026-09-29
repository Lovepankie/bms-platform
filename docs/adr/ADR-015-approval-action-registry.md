# ADR-015: Maker-checker actions are registered by the modules that own them

## Status

Accepted (2026-09-29). Implements FR-APR-01 to FR-APR-08 and chapter 8 section 8.4 in MVP
increment 1 (issue #7).

## Context

Chapter 8 section 8.4 lists thirteen action types that need a second person; all but three belong
to the lending vertical (disbursement, reversal, waiver, write-off, restructure, savings and
investment withdrawals, collateral release, member transfer, credit refund). Chapter 6 drafted
`approval_requests.action_type` as a CHECK list of those thirteen values. ADR-001 and ADR-002 say
the core never names a vertical: a core table whose constraint enumerates lending actions, and a
core service that switches on them, would make the core change with every vertical action and
would not work for the retail vertical.

The mechanism also has to be built and proven in increment 1, before any of the listed actions
exists (the first, the manual journal, arrives in increment 2).

## Decision

- An action that needs a checker is a Spring bean implementing `core.approvals.ApprovalAction`,
  declared by the module that owns the action: its `action_type`, `subject_type`, maker and
  checker permissions, whether the tenant threshold applies, how to read the subject's current
  version, which users may not decide it besides the maker (FR-APR-03), and how to execute a
  stored payload. The approvals module collects the beans and never names one.
- The module's own endpoint validates, then calls `Approvals.request(...)` in its transaction.
  Below the threshold (when allowed) the action executes at once and is audited; otherwise a
  pending request stores the payload snapshot and the subject version, and nothing else happens.
- `approval_requests.action_type` is checked for shape only
  (`^[a-z][a-z0-9_]{2,62}$`); the registry refuses an unknown type at request and decision time.
  The chapter 8 table remains the list of action types and their permissions, and each module's
  bean must match its row.
- Approval executes the stored payload in the transaction that records the decision. An expired
  request becomes `expired` and a changed subject makes it `stale`; both are committed before the
  error is returned. A failure of the action rolls the decision back and records the error on the
  still pending request (FR-APR-06). The checker is never the maker: the service refuses, and the
  database CHECK `approval_checker_is_not_maker` refuses too.
- Every approval route declares `core.approvals.read`; the checker permission of the action type
  is then required in the request's branch (a route can declare only one fixed permission,
  FR-IAM-03). The queue shows what the user may decide in their branches plus what they made.
- Increment 1 proves the mechanism with a test-only action type registered in the test sources;
  production has no action registered until increment 2.

## Consequences

**Better:**

- A vertical adds a checker action without touching the core's schema or code.
- The core stays free of lending vocabulary, as ADR-001 requires.

**Worse:**

- The database no longer rejects a misspelt action type; only the registry does.
- A module that is switched off or removed leaves requests whose type no bean handles; such a
  request cannot be decided (`unknown_action_type`) until the module returns.

**Watch for:**

- Each new action bean needs its row in chapter 8 section 8.4 and a test that its permissions
  match; review item 13 of the spec review (the subject type of each action) is decided when
  each action is built.
- Threshold splitting (review item 14) is not addressed; revisit before disbursements.
