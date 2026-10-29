# ADR-104: A store is the sells_goods flag and no longer a branch role

## Status

Proposed (2026-10-29, issue #371, from the review of #368; the meaning was chosen in the review of #676). Supersedes ADR-033
decision 3 where it makes `store` a branch role, and the paragraph "Relationship to branch roles (ADR-033)" of ADR-048. Migration
`V115`, which merges after `V114` (#127) and is renumbered at merge if main moves.

## Context

Two things said "this branch does not sell". ADR-033 gave a retail branch a role, `store`, `shop` or `both`, in
`retail_branch_roles`, read only while the `branch_roles` capability is on and refused with `branch_role_forbids_sales`. The
role refused a sale, a quotation, a price resolution, an order and an offline sale report; the till kept working. ADR-048
then made a Store a property of every branch, `branches.sells_goods = false`, always enforced, refused with
`branch_does_not_sell`, and shown as the Store badge. A Store also takes no money at its till.

A branch could be role `shop` with the Store flag, or role `store` without it, with no warning. The Branches screen showed only
the flag, and a tenant that set both saw whichever refusal fired first. ADR-048 said a later change would fold the role into the
flag. This is that change. On staging only the demo tenant `tradedemo` has branches with the role `store`; every pilot tenant's
branches are `both`.

## Decision

- **A store means ADR-048's Store: it keeps stock and takes no money at its till.** This is the one meaning #371 asked for. A
  Store holds stock, receives purchases and takes transfers. It refuses a sale, a quotation, a price resolution and an order. It
  also refuses a payment on a credit sale or on account, a refund paid out, savings, bankings, withdrawals and advance
  repayments; expenses stay allowed. This applies to every business, with or without `branch_roles`. It is marked on the
  Branches screen (`PATCH /branches/{id}` or the add form, ADR-089), with ADR-048's checks (open credit sales, a Store must
  keep goods).
- **The retail role keeps only the selling kinds, `shop` and `both`.** They differ only in how the stock-by-location report
  groups and orders branches. With `branch_roles` on, a branch reads as `store` when its flag says so and as its stored role
  (or `both`) otherwise; with it off every branch reads as `both`, as before. `GET /retail/branch-roles` reports `store` and
  `can_sell` false for a Store.
- **Contradictory pairs are refused.** The role table's check allows only `shop` and `both`. `PUT /retail/branch-roles/{id}`
  refuses `store` (422 `validation_failed`, pointing to the Branches screen) and refuses any role for a branch that is a Store
  (422 `branch_is_store`).
- **One refusal.** `BranchRoles.requireMaySell` and its code `branch_role_forbids_sales` are removed. Every selling path
  already called `RetailBranchContext.requireSells` next to it, which now answers alone with `branch_does_not_sell`.
- **A branch that keeps no goods does not quote or report offline sales.** The role used to refuse these at a head office
  with the role `store`. As `both`, such a branch would have started quoting, so quotations now resolve a stocking branch and
  the offline failure and waiting reports refuse a branch that keeps no goods with `branch_not_stocking`, as the sale already
  does.
- **Migration V115 never stops for the data.** A stopped migration stops the automatic staging and production deploy, so no
  branch's state stops it; only locks held by traffic for about two minutes do (step 0). The role was in
  force while the business had the retail module on and its `branch_roles` switch on.
  0. **Locks first, retried under live traffic.** The deploy is zero downtime, so the previous release keeps serving. Before
     anything else, in one retried step, V115 takes three kinds of lock. It takes them in the order the previous release's
     sale does (the branch row, then the role table), so the two never wait on each other in a circle:
     - `branches` in EXCLUSIVE mode, so the fold never waits on a branch row lock.
     - `retail_branch_roles` in ACCESS EXCLUSIVE mode. No role is written between the fold and the narrowed check. The
       constraint swap needs this mode anyway, so taking it once avoids a second lock step; SHARE ROW EXCLUSIVE or a NOT VALID
       check validated later would both need one.
     - With an owner that lacks BYPASSRLS, every table whose FORCE it toggles. Staging and production run as `bms_owner`
       with BYPASSRLS, so no toggle runs there.

     Each attempt waits at most two seconds, so a shop never queues long behind it. On a timeout or a deadlock the attempt
     is undone, a notice names it, and it is retried after a second, for about two minutes (40 attempts). Only then does V115
     stop, with a message that traffic held the locks and nothing was changed. `statement_timeout` is off during the loop,
     and both timeouts go back to the session's own values once the locks are held.
  1. **Fold a clean store.** A `store` branch in force with no blocker becomes a Store (`sells_goods = false`). From then on
     its till takes no money, as a Store's does.
  2. **Skip a store with a blocker.** `retail_store_fold_blockers(tenant, branch, since)` names the blockers:
     - `keeps_no_goods`: an open branch that keeps no goods, which cannot be a Store;
     - `credit_sales_owing`: a credit sale still owing, counted as `BranchTillCheck` counts it (written-off and imported sales
       left out, a delivery fee owed on the account in), whose payments are taken at that till;
     - till money taken since the role was set: `credit_payments` (a payment made after its sale and booked at that branch),
       `account_payments`,
       `refunds`, `savings`, `bankings`, `withdrawals`, `advance_repayments`.

     A branch with any blocker keeps `sells_goods` true and can do everything it does today with its money. A branch that
     keeps no goods is still refused quotations and offline sale reports by the application, as above, not by the migration.
     An administrator settles what is open and marks it a Store on Branches later.
  3. **Other `store` roles.** A `store` role that was not in force was never read, so that branch keeps selling. A closed
     branch that keeps no goods cannot be a Store and is left as it is.
  4. **Roles and audit.** Every `store` row becomes `both`, and no branch or role row is deleted. Each folded branch gets a
     system audit row `core.branch.updated`. Each role row gets one system audit row `retail.branch_role.changed` with the value
     before, the `blockers` and the reason: now a Store, not made a Store because of the named blockers, already a Store, keeps
     no goods, or not in force.
  5. **Check and permission.** The role check is narrowed to `shop` and `both`, and the permission
     `retail.branch_role.manage` is now described as setting a selling branch as a shop or both. The function stays in the
     schema for the test and is not granted to the application.

  `RetailStoreRoleFoldIT` covers this in five ways:
  - it runs the lock step under a sale holding a branch row: a notice shows the retry, the sale's role read is not
    blocked, and with the attempts used up the step stops with its message;
  - it replays V115 on a scratch database at the migration before it, reading both numbers from the folder, with a
    fabricated case for each rule; the migration completes, a skipped branch is unchanged with its audit row, and a second
    run changes nothing;
  - it migrates a mixed business (one clean store, one with credit owing) in one run while another transaction holds a lock
    on the role table past one attempt;
  - it checks the blockers against a credit sale owing, a written-off one, a later payment, a banking and a refund made
    through the API;
  - it pins that a folded store refuses a credit payment, a refund and a banking with `branch_does_not_sell`.
- **Permission unchanged.** Marking a Store needs `core.branches.manage`; `retail.branch_role.manage` sets shop or both.

## Consequences

**Better:**
- One meaning of "store", one place to set it, one badge and one refusal; the role and the flag cannot disagree.
- The stock-by-location report with roles on now shows a Store as a store, where before it showed the role.
- No credit sale is stranded at a till that stops taking money.

**Worse, and to watch:**
- **A folded store branch takes no money at its till any more.** It refuses payments on credit sales and on account, refunds,
  savings, bankings, withdrawals and advance repayments, as an ADR-048 Store does. Under the role only a sale, a quotation, a
  price resolution, an order and an offline report were refused. This is the chosen meaning. Only a store whose till took
  none of that since its role was set is folded. Today the role exists only on the 18 store branches of the demo tenant.
  "Sells again" on the Branches screen undoes it for a branch.
- A skipped store branch starts selling again after V115, since it is `both` with the flag unset. Its audit row names the
  blockers, and the administrator marks it a Store once they are settled.
- A head office that keeps no goods no longer issues quotations or takes offline sale reports, with or without the old role.
- A caller that matched `branch_role_forbids_sales` now gets `branch_does_not_sell`; the web app maps both.
- An administrator of a business with `branch_roles` off who had set a dormant `store` role will find `both` and an audit row,
  not a Store; turning the capability on no longer makes that branch a store.
- During the deploy the previous release may try to write `store` and fail the check (a 500, not a 422) until the new
  release is serving. The release note says so.
