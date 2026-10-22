# ADR-073: Owners change their own plan: modules and cancellation take effect at the end of the paid period

## Status

Proposed until #474 merges (owner decision of 22 Oct 2026, issue #472, FR-SUB-03 to FR-SUB-07). Builds on ADR-024 (per-module subscriptions and
manual payments), ADR-056 (the operator's Business page, the paid-until date, a reason on every operator change), ADR-062 (the
Owner of a business and the owner step-up) and ADR-016 (platform operations through definer functions). Migration `V93`
(the number claimed for this work; `V80` to `V83` are merged and `V84` to `V91` are claimed by open pull requests; renumbered at merge if needed).

## Context

Until now only the platform operator could change what a business uses: switch a module on or off, or cancel. The owner
decided (Hillary, 22 Oct 2026) that a business changes what it uses itself, like a normal subscription product: add or drop a
module and cancel the whole subscription, effective at the end of the period already paid for, as most software sold by the
month does. It is generic for every business and the Owner's alone: administrators see the plan, they do not change it.

## Decision

1. **The Owner alone changes the plan.** The Business set-up screen gains a Plan and modules card (`GET /plan`,
   `core.settings.manage`, so administrators see it) with the plan, the modules on, the paid-until date, the requests and
   the changes still to come. Asking for a module, stopping one, cancelling and undoing are the Owner's alone (403
   `owner_only` for anyone else, administrators included; a business with no Owner is refused too, and the operator names
   one, ADR-062 decision 5). No permission is added: the owner rule rides on `core.settings.manage`, as the owner rules of
   ADR-062 ride on the user permissions.
2. **Adding a module is a request the operator decides, unless the plan already includes it.** `POST /plan/module-requests`
   writes a `subscription_module_requests` row. When the business's plan already allows the module there is no price change,
   so the request is approved at once by the system (`decided_automatically`) and the module is switched on through the
   existing `platform_set_tenant_modules` (data kept, the chart seeded); this is how "the operator approves automatically"
   is read: the operator agreed to it when they put the module in the plan. Otherwise the request waits; the operator lists
   the pending ones (`GET /platform/module-requests`) and approves with a reason and their own authenticator code, optionally
   moving the business to another plan first (the plan or the price changes; the plan move keeps every check of FR-TEN-11),
   or declines with a reason the Owner sees. The Owner is emailed the decision and the card shows it. One pending request per
   module. The request row is a record only: approving it switches nothing on by itself, so a row written by hand grants
   nothing.
3. **Stopping a module and cancelling take effect the day after the last day paid for.** The change is a
   `subscription_scheduled_changes` row with its first day in force (`effective_on`): the day after
   `subscriptions.current_period_end` while that is today or later, otherwise today, and then it is applied at once. That
   one rule covers a free month and a pilot (nothing paid ahead, so a stop is at once, as the issue asks for a free trial)
   and a late business. Both need the Owner's step-up of ADR-062 (the authenticator code when two-step sign-in is on, else
   the password; 422 `totp_required`, `password_required`) because both take something away. Stopping says plainly that the
   records are kept but hidden and come back when the module is switched on again (FR-TEN-03: data kept). A business keeps
   at least one module, counting the stops already scheduled (422 `last_module`; to stop everything, cancel). The Owner may
   undo a change still to come before its day (`POST /plan/changes/{id}/undo`); on its day it is being applied and can no
   longer be undone (422 `change_already_due`).
4. **A nightly job applies what is due, once.** `core.platform.plan-scheduled-changes` runs every quarter hour on
   db-scheduler (ADR-008), lists the businesses with a change due through `platform_tenants_with_due_changes`, and for each,
   in a transaction bound to that business, applies every scheduled row whose day has come in the business's own time zone:
   a stop through `platform_set_tenant_modules`, a cancellation through `platform_end_subscription`. The row is locked and
   moved to `applied` in the same transaction as the change, so a second run, an overlapping run or a retry changes nothing.
   One business's failure is logged and does not stop the others. It is switched off by
   `bms.platform.plan-changes-job-enabled=false`, which the integration tests set; they call the same work themselves.
5. **After the end date the business is read only, and its data is kept.** A cancellation applied sets the subscription to
   `cancelled` with `subscriptions.ended_on` and the tenant to `suspended`, so every scheduled job already skips it and nothing
   further is billed (no billing is automatic in this release; the operator records payments by hand, ADR-056). The tenant
   resolver answers `ended` for such a business, and the existing read-only interceptor refuses every write with 423
   `subscription_ended` in plain words, while reads, staff sign-in, refresh, sign-out and the exports that are a POST (the audit
   CSV and a report run) still work, so the business can take its data away. Until the end date everything works and every
   member of staff sees a banner with the date (`GET /plan/status`, any signed-in staff); after it, a read-only notice. Data is
   never deleted automatically: deletion is a separate explicit request of the Owner that the operator processes (FR-TEN-09,
   later). The operator brings a business back with the existing resume, which clears `ended_on` and restores the status it
   had before (ADR-056 decision 5).
6. **The operator is told and sees what is coming.** Every request, stop, cancellation and undo emails every active platform
   operator through the outbox (in a savepoint: a mail failure never refuses the Owner's change), and the operator console API
   lists the pending module requests and the scheduled changes across businesses (`GET /platform/scheduled-changes`), read
   through definer functions that refuse a tenant-bound transaction.
7. **Everything is audited.** The Owner's acts are written in the business's own log (`core.plan.module_requested`,
   `core.plan.change_scheduled`, `core.plan.change_undone`, `core.plan.change_applied` by the system) and in
   `platform_audit_log` (`platform.tenant.module_requested`, `platform.tenant.plan_change_scheduled`,
   `platform.tenant.plan_change_undone`, `platform.tenant.plan_change_applied`, `platform.tenant.module_request_decided`,
   `platform.tenant.subscription_ended`, and the existing `platform.tenant.modules_changed` with no operator when the Owner or
   the job switched them).

## Consequences

**Better:** a business adds, drops or cancels without waiting for the operator, and the rules are the ones people know from
other software sold by the month; nothing paid for is lost; nothing is deleted; the operator sees every change coming.

**Worse:** a module stopped in a free month is gone at once; a business that cancels during its free month is read only at
once (the confirm says so). A business with no Owner cannot change its plan until the operator names one. The price itself
does not change automatically when a module is added or dropped: the operator moves the plan when the price must change.

**Watch for:** a scheduled change that the job could not apply (the error log names the business); a business on a plan that
includes a module it then asks for, which the system switches on without the operator; the deletion request of decision 5 and a
single-file "Download my data", both still to build (FR-TEN-09).
