# ADR-073: Owners change their own plan: modules and cancellation take effect at the end of the paid period

## Status

Proposed until #474 merges (owner decision of 22 Oct 2026, issue #472, FR-SUB-03 to FR-SUB-07). Builds on ADR-024 (per-module subscriptions and
manual payments), ADR-056 (the operator's Business page, the paid-until date, a reason on every operator change), ADR-062 (the
Owner of a business and the owner step-up) and ADR-016 (platform operations through definer functions). Migration `V90`
(this work's place in the agreed merge order; `V80` to `V87` are merged, `V88` and `V89` merge before it and `V91` and `V92` after it;
renumbered at merge if the order changes).

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
   writes a `subscription_module_requests` row, after the Owner's step-up of ADR-062 (the authenticator code when two-step
   sign-in is on, else the password), because a module the plan includes is switched on at once and a stolen session must not
   do that. When the business's plan already allows the module there is no price change,
   so the request is approved at once by the system (`decided_automatically`) and the module is switched on through the
   existing `platform_set_tenant_modules` (data kept, the chart seeded); this is how "the operator approves automatically"
   is read: the operator agreed to it when they put the module in the plan. Otherwise the request waits; the operator lists
   the pending ones (`GET /platform/module-requests`) and approves with a reason and their own authenticator code, optionally
   moving the business to another plan first (the plan or the price changes; the plan move keeps every check of FR-TEN-11),
   or declines with a reason the Owner sees and the same authenticator code. The Owner is emailed the decision and the card shows it. One pending request per
   module. The request row is a record only: approving it switches nothing on by itself, so a row written by hand grants
   nothing.
3. **Stopping a module and cancelling take effect the day after the last day paid for.** The change is a
   `subscription_scheduled_changes` row with its first day in force (`effective_on`): the day after
   `subscriptions.current_period_end` while that is today or later, otherwise today, and then it is applied at once. That
   one rule covers a free month and a pilot (nothing paid ahead, so a stop is at once, as the issue asks for a free trial)
   and a late business. Both need the Owner's step-up of ADR-062 (the authenticator code when two-step sign-in is on, else
   the password; 422 `totp_required`, `password_required`), as asking for a module does (decision 2). Stopping says plainly that the
   records are kept but hidden and come back when the module is switched on again (FR-TEN-03: data kept). A business keeps
   at least one module, counting the stops already scheduled (422 `last_module`; to stop everything, cancel). The Owner may
   undo a change still to come before its day (`POST /plan/changes/{id}/undo`); on its day it is being applied and can no
   longer be undone (422 `change_already_due`). A change's `effective_on` is fixed when it is asked for: a payment the
   operator records afterwards does not move a scheduled cancellation or stop; the Owner undoes it and asks again if they
   want the later date.
4. **A nightly job applies what is due, once.** `core.platform.plan-scheduled-changes` runs every quarter hour on
   db-scheduler (ADR-008), lists the businesses with a change due through `platform_tenants_with_due_changes`, and for each,
   in a transaction bound to that business, applies every scheduled row whose day has come in the business's own time zone:
   a stop through `platform_set_tenant_modules`, a cancellation through `platform_end_subscription`. The row is locked and
   moved to `applied` in the same transaction as the change, so a second run, an overlapping run or a retry changes nothing.
   One business's failure is logged and does not stop the others. The job, the Owner's ask, stop and cancel and the operator's
   decision on a request each take the subscription's row lock (`app_lock_subscription`) first and read the modules after it,
   because switching the modules replaces the whole set: two changes at once are made one after the other and neither
   overwrites the other. It is switched off by
   `bms.platform.plan-changes-job-enabled=false`, which the integration tests set; they call the same work themselves.
   When a cancellation applies, the stops still scheduled for a later day are closed as `undone` with it: they would change
   nothing for a read-only business and must not switch a module off after the operator's resume. Between the business's
   midnight and the job's next run (up to a quarter hour) the resolver already answers `ended` while `tenants.status` is still
   `active`, so a background job may still run for the business in that window; that is harmless, since the nightly jobs run
   later in the night.
   A suspension by the operator always wins over the Owner's cancellation. The resolver answers `ended` for a scheduled
   cancellation whose day has come only while the tenant is `active`; `platform_end_subscription` changes nothing and returns
   null for a business the operator suspended or cancelled (or a tenant not active), so the job leaves the cancellation
   `scheduled`; and the job's list counts a due cancellation only for an active business, so it is not retried each tick.
   An end would make payments post, report runs resume and exports open again for a business the operator stopped (for
   fraud or non-payment, say). When the operator resumes the business, the cancellation, whose day has passed, applies at
   the job's next run.
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
   Two things still reach an ended business, on purpose. Money that arrives after the end posts: a payment gateway's callback
   (`/api/v1/payments/callbacks/...`) and a payment the poller confirms find the business by the merchant reference, not the
   host, so they never meet the read-only interceptor, and a borrower or buyer who paid gets the payment recorded and a receipt
   rather than money with no record. Both take the business through one definer predicate, `app_tenant_takes_payments`
   (active, or suspended with the subscription `cancelled` and `ended_on` set), which the poller's list
   `app_tenants_with_due_payment_checks` uses too; a business the operator suspended is still ignored. And member portal sign-in (FR-IAM-09, not built yet) stays allowed after the end, read
   only: a member can still sign in and see their balances and statements, while every member write is refused with
   `subscription_ended`. This differs from a suspension by the operator, where member sign-in is refused (FR-TEN-06).
6. **The operator is told and sees what is coming.** Every request, stop, cancellation and undo emails every active platform
   operator through the outbox (in a savepoint: a mail failure never refuses the Owner's change), and the operator console API
   lists the pending module requests and the scheduled changes across businesses (`GET /platform/scheduled-changes`), read
   through definer functions that refuse a tenant-bound transaction.
7. **Everything is audited.** The Owner's acts are written in the business's own log (`core.plan.module_requested`,
   `core.plan.change_scheduled`, `core.plan.change_undone`, `core.plan.change_applied` by the system,
   `core.plan.module_request_decided` by the platform operator, so the business's administrators see the decision) and in
   `platform_audit_log` (`platform.tenant.module_requested`, `platform.tenant.plan_change_scheduled`,
   `platform.tenant.plan_change_undone`, `platform.tenant.plan_change_applied`, `platform.tenant.module_request_decided`,
   `platform.tenant.subscription_ended`, and the existing `platform.tenant.modules_changed` with no operator when the Owner or
   the job switched them). The Owner is not a platform user, so the platform rows of the Owner's acts carry no
   `platform_user_id`; their data names the Owner as `owner_user_id` (for an applied change, the Owner who asked for it).

## Consequences

**Better:** a business adds, drops or cancels without waiting for the operator, and the rules are the ones people know from
other software sold by the month; nothing paid for is lost; nothing is deleted; the operator sees every change coming.

**Worse:** a module stopped in a free month is gone at once; a business that cancels during its free month is read only at
once (the confirm says so). A business with no Owner cannot change its plan until the operator names one. The price itself
does not change automatically when a module is added or dropped: the operator moves the plan when the price must change.

**Watch for:** a scheduled change that the job could not apply (the error log names the business); a business on a plan that
includes a module it then asks for, which the system switches on without the operator; the deletion request of decision 5 and a
single-file "Download my data", both still to build (FR-TEN-09).
