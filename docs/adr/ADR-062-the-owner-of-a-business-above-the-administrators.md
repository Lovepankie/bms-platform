# ADR-062: The Owner of a business: one administrator who alone manages the administrators

## Status

Accepted (owner decision of 22 Oct 2026, issue #442, FR-IAM-18; revised after the review of pull request #443). Refines the design sketched in issue #325. Builds on ADR-014
(staff authentication and the step-up), ADR-042 (the operator's rescue of a tenant admin), ADR-046 (two-step sign-in optional by
default), ADR-055 (an administrator is never limited by branch) and ADR-056 (the operator's Business page). Migration `V79`, the
first free number after `V78` on main.

## Context

A business has one role at the top, `tenant_admin`, and any administrator could deactivate, demote or change the contact of any
other administrator, guarded only by "a business keeps at least one active administrator". An administrator who leaves on bad
terms could therefore lock the person who owns the business out of it: deactivate them, take the administrator role off them, or
change their email and then use Forgot password. The owner's standing rule is that the owner and the administrators do everything
and see every shop, aggregation, KPI and analytics, so the fix must not split what the owner and the administrators may see or do
day to day.

## Decision

1. **One Owner per business, a flag on the staff user, not a role.** `users.is_owner` (V79), at most one per business (the partial
   unique index `users_one_owner`), never a deactivated user (the check `users_owner_is_staff`), and always an administrator: a
   user can be made the Owner only while they hold an active `tenant_admin` assignment (the trigger `users_owner_is_admin`), and
   that assignment cannot be revoked, moved or deleted while they are the Owner (the trigger `user_role_assignments_owner_keeps_admin`). A flag fits
   the existing model with the least disruption: every permission, branch scope, the permission matrix, `/me` permissions and every
   check that reads a permission stay exactly as they were, and no role name is read anywhere new. A separate role would have had
   to copy every administrator permission and be kept in step with the matrix forever. `users` already has forced row-level
   security, so the flag is the business's own.
2. **Only the Owner manages the administrators.** Inviting someone as an administrator, making someone an administrator, changing
   another administrator's roles (a demotion included), deactivating an administrator, resetting an administrator's second
   factor, sending an invited administrator a new link and changing an administrator's email or phone are the Owner's alone: 403
   `owner_only` for anyone else, and 403 `owner_protected` when the target is the Owner. An administrator still manages every staff
   member who is not an administrator exactly as before, and may change their own roles and contact. An administrator who is not
   the Owner may demote themselves: the product owner's delegation keeps that allowed, since it only reduces their own power. The
   contact is included because a changed email would send the next reset link to someone else; the reset of a sign-in already
   refuses every administrator (ADR-042).
3. **Nobody inside the business removes the Owner, the Owner included.** The Owner cannot be deactivated, demoted or have their
   contact or second factor changed by anyone else, and cannot take the administrator role off themselves (422
   `owner_must_transfer_first`) or deactivate themselves (`cannot_deactivate_self`, as before). Lifting a sign-in lock stays open to
   every administrator: it gives nobody a credential (ADR-059). The Owner's own email or phone change needs the same step-up as
   the transfer (the authenticator code, else the Owner's password; 422 `password_required`, `totp_required`), and the old email
   address is told of it (`identity.contact_changed`), because the Owner's mailbox is where a reset link goes; an Owner with no
   email is told by SMS on the old phone, which is sent only where the business's SMS is switched on (ADR-036).
4. **The Owner hands ownership on.** `POST /users/{user_id}/transfer-ownership` makes another **active administrator** of the same
   business the Owner; the old Owner stays an administrator. The target is checked first, read-only (403 `owner_only`, 409
   `no_owner`, 422 `already_owner`, 422 `owner_must_be_active_admin`, 404 for a user of another business), then the Owner proves it
   is still them exactly as for the reset of a sign-in: a fresh authenticator code with two-step sign-in, otherwise their own password
   (counted on the sign-in lock). The transfer then runs under a per-business advisory lock, checks everything again, clears the
   old flag and sets the new one, and is audited as `core.user.ownership_transferred` with an optional reason.
   **Cooling period:** the Owner's own transfer is refused for 24 hours after the Owner's email, phone
   (`users.contact_changed_at`, V79), two-step sign-in (set up or reset, by anyone, `users.second_factor_changed_at`, V79) or
   password (`user_credentials.password_changed_at`) last changed (422
   `owner_recently_changed`, with the time it is allowed again). Someone who finds the Owner signed in on a shared phone could
   otherwise change the email, use Forgot password and hand the business to themselves in one sitting; the day gives the real
   Owner, told at the old address, time to call the operator. The operator's rescue (decision 5) has no cooling period.
5. **The platform operator rescues an Owner who lost access.** `POST /platform/tenants/{tenant_id}/users/{user_id}/make-owner` with
   the operator's own fresh authenticator code and a reason of 5 to 300 characters makes one of the business's active administrators
   the Owner, audited in the tenant's log (by the operator, with the reason) and in the platform log
   (`platform.tenant.owner_transferred`, before and after). It never makes a non-administrator the Owner in one step. The same route
   assigns an Owner to a business that has none. A new business whose first administrator (its Owner) never joined is not stuck
   when the invitation expires: `POST /platform/tenants/{tenant_id}/users/{user_id}/invitation`, with the operator's code and a
   reason, issues a new 72 hour link for that invited Owner (or the invited first administrator of a business with no Owner and no
   active administrator), shown once, audited in the platform log (`platform.tenant_owner.invitation_reissued`); anyone else is
   422 `not_an_invited_owner`.
6. **Two-step sign-in is not forced on the Owner.** Forcing it could lock a live Owner out of their own business on the day this
   ships. The Users screen shows the Owner a plain prompt to turn it on.
7. **Backfill and new businesses.** V79 gives every business with an active administrator exactly one Owner: the active administrator
   whose email matches, in any case, the contact email of the sign-up application the business was activated from
   (`onboarding_applications.contact_email`, ADR-024, the recorded owner email), and otherwise the earliest-created active
   administrator, so a business whose recorded owner is not one of its administrators gets its earliest active administrator. Each
   assignment writes a system row in the tenant's audit log and a row in the platform log. A business with no active administrator
   is left with no Owner and named in a WARNING in the migration log. A new business gets its Owner at creation: the first
   administrator the operator invites (or the applicant at activation) is the Owner from the start, still invited until they accept.
   An administrator who accepts an invitation to a business that has no Owner and no other active administrator becomes its Owner.
8. **A business with no Owner keeps the rules of before.** Until an Owner is assigned, the owner-only rules are skipped with a warning
   in the server log, so nothing that worked yesterday stops working for such a business; the transfer answers 409 `no_owner`.
9. **Locking.** One advisory lock per business: making or moving the Owner (a transfer, the first Owner at creation, an
   invitation acceptance that may make the joiner the first Owner, after its token was checked without the lock) takes it exclusively, and every staff change that applies an
   owner rule takes it shared, first in its transaction before any row lock. So two transfers, a transfer and the operator's
   rescue, or two first administrators joining at once end with exactly one Owner, and an act an Owner started cannot commit
   after that person stopped being the Owner. A unique-index violation that still reaches the code is a 409 `owner_changed`, not
   a 500. Tenant audit rows keep the reason under `after.reason`, the platform rows at the top level, as each log already does.
10. **What the Owner and the operator see.** `User.is_owner` on `/users`, `Me.is_owner` on `/me`, and on the operator's Business page
   the owner fact is the Owner (null while there is none) and each administrator carries `is_owner`, the Owner listed first. The
   Users screen shows an Owner badge, hides the owner-only actions from administrators, offers the Owner Transfer ownership with a
   plain sentence of what changes, and the "Who can do what" card has an Owner line; the Business page has the badge and Make the
   owner.

## Consequences

**Better:** an administrator who leaves on bad terms can no longer lock the owner out or take the business over through another
administrator, and the database holds one Owner per business whatever the application does. Nothing an administrator sees or does
for the business day to day changes, and no permission or role is added, renamed or removed.

**Worse:** an Owner who changes their email, phone or password waits a day before handing ownership on, and a new Owner who just set their password waits a day too. An Owner who loses access needs the operator (with a reason and the operator's code), as an administrator who forgot the
password already does. A business whose Owner leaves without handing over cannot add or remove administrators until the operator
makes another administrator the Owner. Each owner-only check reads the Owner once more per request.

**Watch for:** a business left with no Owner after V79 (the WARNING lines; the operator assigns one on the Business page); an Owner
who never turns on two-step sign-in (the prompt is the only nudge; a policy can still require it, ADR-046); any new route that changes
a staff user must ask whether it touches an administrator and call the same owner rule; the plan and billing contact and closing the
business, named in #325 as the Owner's, were not part of this change; the note below says which are now built (the plan) and
which are not yet built (a billing contact, a request to close the business, and a list for the operator of the businesses the
backfill gave an Owner or left without one).

## Note: what issue #325 asked for and where it is (25 Oct 2026)

Issue #325 (owner request of 19 Oct 2026) sketched the Owner before this decision. Most of it is on `main`; three parts are not
yet built and stay open on #325 as follow-ups, all three tracked in #571. No new migration or ADR is added for what is built.

| #325 asked for | On `main` | Test |
|---|---|---|
| The person who signs up is the Owner, also when the operator invites the first administrator | `UserService.inviteFirstAdmin` calls `Owners.markFirstOwner`; an invitation accepted in a business with no Owner calls `Owners.claimIfFirstAdmin` | `TenantOwnerIT.theFirstAdministratorOfANewBusinessIsItsOwner`, `theOnlyAdministratorToJoinABusinessWithNoOwnerBecomesItsOwner` |
| A per-business owner marker, `tenants.owner_user_id` | Held as `users.is_owner` (`V79__tenant_owner.sql`), one per business by the index `users_one_owner` (owner decision of 22 Oct 2026) | `TenantOwnerIT.theDatabaseKeepsOneOwnerWhoStaysAnAdministrator` |
| The Owner holds every administrator permission | The Owner keeps `tenant_admin`: the triggers `users_owner_is_admin` and `user_role_assignments_owner_keeps_admin` (`V79__tenant_owner.sql`) | `TenantOwnerIT.theDatabaseRefusesDeletingTheOwnersAdministratorRole` |
| Not deactivated, demoted, given other roles or a new contact by an administrator | `Owners.requireMayChangeAdmin`, called from `UserService`, answers 403 `owner_protected` | `TenantOwnerIT.anAdministratorCannotChangeTheOwnerOrAnotherAdministrator` |
| Administrators manage everyone except the Owner | Narrowed by the owner decision of 22 Oct 2026: administrators manage every member of staff who is not an administrator; only the Owner manages the other administrators (`Owners.requireOwner`, 403 `owner_only`) | `TenantOwnerIT.anAdministratorCannotChangeTheOwnerOrAnotherAdministrator`, `theOwnerManagesTheAdministratorsAndMustTransferBeforeStepping` |
| Only the Owner transfers ownership, to an active administrator, with the step-up and an audit row | `POST /users/{user_id}/transfer-ownership` (`UserController`), audited `core.user.ownership_transferred`, under the per-business advisory lock (`Owners.lockExclusive`) | `TenantOwnerIT.theOwnerTransfersOwnershipToAnActiveAdministrator`, `anOwnerWithTwoStepSignInTransfersWithACode`, `twoTransfersAtOnceLeaveExactlyOneOwner` |
| Only the Owner manages the plan | `PlanService.requireOwner` (403 `owner_only`) on `POST /plan/module-requests`, `POST /plan/modules/{module}/stop`, `POST /plan/cancel` (each with the step-up) and `POST /plan/changes/{change_id}/undo` (`PlanController`) | `SelfServePlanIT.onlyTheOwnerChangesThePlan` (module request, stop and cancel by an administrator: 403 `owner_only`), `stopAndCancelWithoutTheStepUpOrByANonOwnerAreRefused` (stop, cancel and undo by an administrator who is not the Owner: 403 `owner_only`; and without the step-up) |
| Only the Owner manages a billing contact | Not yet built (#571) | None |
| Only the Owner asks to close the business | Not yet built (#571) | None |
| The Users screen shows an Owner badge and hides destructive actions on the Owner | `frontend/src/areas/staff/users/page.tsx` (the badge), `state.ts` (which actions are offered), `transfer-owner.tsx` | `users.dom.test.tsx` (the Owner badge and hidden actions), `state.test.ts` |
| Staff accounts are deactivated, never deleted | No route deletes a user (`UserController` has no `DELETE`); the application role holds no `DELETE` on `users` (`V1__foundation.sql`, `bms_grant_app('users', 'SELECT, INSERT, UPDATE')`) | `TenantOwnerIT.staffAccountsAreNeverDeleted` (a `DELETE /users/{user_id}` by the Owner answers 405 for a cashier, an administrator and the Owner, and the rows stay; `has_table_privilege` is false for `DELETE` and `TRUNCATE` on `users`, inherited roles and `PUBLIC` included). Not covered: a row removed by a foreign-key cascade or a definer function; none deletes users today |
| Backfill to the first active administrator by `created_at` | `V79__tenant_owner.sql`. **Differs from #325:** the active administrator whose email is the recorded sign-up email comes first (the person who signed up, the point of #325), else the earliest active administrator by `created_at`; each assignment is a platform log row and a business left with none is a WARNING in the migration log | `MigrationOrderIT.ownerMigrationGivesEveryBusinessWithAnActiveAdministratorExactlyOneOwner` |
| The backfilled businesses listed for the operator to confirm | Not yet built (#571): no screen lists the businesses the backfill gave an Owner, or left without one | None |
