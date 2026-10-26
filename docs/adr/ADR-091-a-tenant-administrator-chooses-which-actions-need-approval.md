# ADR-091: A tenant administrator chooses which actions need approval, and from what amount

## Status

Accepted (2026-10-26, by the owner on #588), built in pull request #588 (issue #343, go-live priority; the owner asked for it to be
built in the best order without waiting on him).
Builds on ADR-015 (actions registered by the modules that own them), ADR-043 (the write-off of a credit sale), ADR-046 (the
step-up of an administrator who loosens a safeguard) and ADR-050 (push notices). No migration: the choices are two keys of the
tenant settings row. The number follows ADR-088 (#579), ADR-089 (#583) and ADR-090 (#571, merged with #585); ADR-092 (#589) came after it.

## Context

The owner call of 20 October asked which actions need an administrator's approval and said clients should manage their own rules.
Today a business has one place to say anything about approvals: `approval_thresholds_minor` in `PATCH /settings`, a map of action
type to an amount, with no screen. What the amount means differs by action:

| Action type | Module | Has an amount | Today with no amount set | Today with an amount set |
|---|---|---|---|---|
| `loan_disbursement`, `charge_waiver`, `savings_withdrawal`, `investment_funding` | lending | yes | every one waits for a checker | below the amount runs at once |
| `loan_write_off`, `repayment_reversal`, `collateral_release`, `loan_bring_in`, `loan_bring_in_reversal`, `savings_reversal`, `investment_early_withdrawal`, `investment_reversal` | lending | no | every one waits | (no amount) |
| `retail_sale_return` (and its void, `retail_sale_return_void`) | retail | yes | no approval at all | the returns of one sale that reach the amount wait |
| `retail_sale_write_off` | retail | yes | waits when two or more administrators count (ADR-043) | a debt that reaches the amount waits |
| `retail_purchase_order` | retail (purchasing) | yes | no approval at all | an order that reaches the amount waits |

So "0" means "always" for lending and "never" for returns and purchase orders, and nothing says whether an action asks at all.
The retail owner also named four actions that never ask today: sale voids, a price below cost, stock quantity corrections and
expenses.

## Decision

1. **One list of approvable actions, read from the registered actions.** `GET /api/v1/settings/approvals`
   (`core.settings.read`) lists every `ApprovalAction` bean of a module the business uses, with whether it asks now (`on`), its
   amount (`threshold_minor`, for an action with an amount), whether the business may switch it (`switchable`), whether it has an
   amount (`has_amount`) and the module. Only actions the server enforces are listed: the list is the registry itself, so a row
   on the screen always has a service behind it. `retail_sale_return_void` is not its own row: the void of a return follows the
   returns row (decision 6).

2. **Each action says what it may be.** `ApprovalAction` gains `switchable()` (false by default) and `onByDefault(threshold)`
   (true by default). The approvals module resolves one rule per action, `Approvals.rule(actionType)`, as `on` (the stored switch,
   else the default) and the amount (the stored threshold, else 0); `Rule.asks(amount)` is `on && amount >= threshold`. Every
   module that decides whether to ask reads that rule and passes it to `Approvals.request(request, rule)`, so the rule is read
   once: a switch saved between the module's read and its request never turns a request the module answers as waiting into one
   that ran (a waiting void, expense, correction, return, write-off or order stays waiting, and a write-off is never made without
   ADR-043's flag and notice). A caller that read no rule uses `Approvals.request(request)`, which reads it there and runs a
   switched-off action at once, audited as `core.approval.executed_switched_off`. The modules still answer with the record if a
   request ever runs at once.

3. **Lending stays on.** Every lending action keeps `switchable() == false`: it always asks, as today, and its row reads "Always
   on". A licensed lender keeps a second person on money that leaves or is forgiven; turning that off is not one click. Lending
   actions that have an amount keep their amount, now editable on the screen; 0 still means every one asks.

4. **Retail actions are switches, and their defaults are today's behaviour.** A business that never saved a switch reads exactly
   what it read yesterday:
   - Customer returns (`retail_sale_return`, amount): on when the business set a positive amount, else off.
   - Purchase orders (`retail_purchase_order`, amount): on when the business set a positive amount, else off. While on, a
     delivery with no order is refused from the same amount (as today), so on with no amount means every delivery needs an order.
   - Credit write-offs (`retail_sale_write_off`, amount): **on**, as ADR-043 has it today. On with no amount keeps ADR-043's rule
     (a second administrator when two or more count); on with an amount asks from that amount. Switched off, a write-off never
     waits, and it is still flagged and told exactly as ADR-043 decision 10 requires, with the reason `approval_switched_off`.
   - Sale voids (`retail_sale_void`, new, amount: the sale's total): off.
   - Stock quantity corrections (`retail_stock_correction`, new, amount: the value of the difference at the branch's average
     cost): off.
   - Expenses (`retail_expense`, new, amount): off.

   With a switch on, 0 means every one asks, except a credit write-off: on with 0 keeps ADR-043's rule, so a second
   administrator is needed only while two or more administrators count, and a business with one writes off alone (flagged and
   told). The screen says so in those words, never "every one". A price below cost is **not** an approval and is not listed: the sale is made at the
   till while the customer waits, so it cannot wait for a second person, and today only a holder of `retail.price.below_cost` may
   sell at or below cost (the others are refused). The screen says so in one line and links to Staff users, where roles are given.

5. **The new retail approvals.** Each is a bean in the module that owns the action, wired in its service, with the maker's own
   permission and an administrator (`core.settings.manage` in the branch) as the checker, so a seller never approves another
   seller. No new permission and no migration.
   - `retail_sale_void`: `POST /retail/sales/{id}/void` checks the sale as today, then, when the rule asks for its total, stores
     the reason and answers 202 with the waiting request. The sale stays completed until an administrator approves; the void then
     runs with the maker as the person who voided it, and is stale when the sale changed (its version) meanwhile.
   - `retail_stock_correction`: `POST /retail/stock/corrections` checks as today, then asks for the value of the difference. The
     correction runs on approval only if the quantity is still the one the maker saw; otherwise the request becomes stale with
     the reason ("The correction can no longer be made as it was asked for: ..."), like the 409 `stock_moved` of a direct correction. One correction
     per item waits at a time (`approval_already_pending`).
   - `retail_expense`: `POST /retail/expenses` checks as today, then asks for the amount. The expense is not in the cash book
     until it is approved; it is then recorded with the maker as the person who recorded it, on the business date the maker gave.
   A 202 body is `ApprovalWaiting` (`approval_id`, `action_type`, `amount_minor`, `currency`). A switch left off changes nothing:
   the same 200 or 201 and the same body as before.

6. **The void of a return follows returns.** With returns on, a return that needed a second person needs one to be voided, and so
   does a return worth the amount alone (as today). With returns off, a void of a return never waits.

7. **Saving the rules needs the step-up and is audited.** `PUT /api/v1/settings/approvals` (`core.settings.manage`, `If-Match`
   on the settings version) takes the rules that change and the administrator's proof: the current code of their authenticator,
   or their own password when they have none (`PolicyChangeGuard.requireProof`, ADR-046). Any change needs it, tighter or looser,
   because the rules are the business's safeguard. A refused proof counts toward the lock as at sign-in. The change is audited as
   `core.settings.approval_rules_changed` with the old and new rule of each action that changed. A lending action cannot be
   switched off (422 `approval_always_on`), an unknown or unlisted action is refused (422), and an amount is 0 or more.
   `PATCH /settings` still takes `approval_thresholds_minor` for API callers, but now needs the same proof whenever it changes an
   amount, so there is no way round the step-up.

8. **The other administrators are told when a safeguard is loosened.** Switching an action off or raising its amount sends the
   push `core.approval_policy_weakened` to the holders of `core.settings.manage` with a device (ADR-050), never to the person who
   changed it, naming the actions in words and opening the approval rules. The same holds for `PATCH /settings`: the settings
   module publishes `ApprovalAmountsChanged` and the approvals module, which knows each action's default, tells a raised amount and
   an amount lowered to 0 that switches an action off by its default (returns and purchase orders with no saved switch).
   Every caller of `Approvals.request` answers by `executed()` (`ApprovalOutcomeGuardTest` fails on one that does not), and a
   write-off the approvals module runs at once is written through the same flag-and-tell as any write-off done alone.

9. **What a person sees while an action waits.** The maker's screen says it in plain words at once: "Sent for approval. Nothing
   changes until an administrator approves it. You can see it, or cancel it, on Approvals." The approvers hear it by the push
   `core.approval_waiting` (already sent by `Approvals.request`) and see it on Approvals with the action in words and its amount.
   The maker sees their own request on Approvals with Cancel my request.

10. **The screen.** Business set-up, Approval rules (`/staff/setup/approvals`, tour `page-approval-rules`): the business's actions
    grouped by module, each with a switch (or "Always on"), its amount where it has one, and one line on what happens; a review
    in plain words before saving; the proof; the saved message. Retail settings' returns Hint (#575) links to the returns row.

11. **One stale policy for every action the approval rules govern.** When an approved action no longer passes as it was asked for
    (a 4xx refusal on approval: the sale changed, the quantity moved, the category was switched off, the branch no longer stocks),
    the request goes stale with that refusal as its reason and the maker asks again; it never fails on every approval until it
    expires. `ApprovalService` applies this in one place to every action the rules govern (`ApprovalAction.governedByRules()`:
    every switchable action, and the void of a return, which follows the returns rule), and a module may word the reason (a stock
    correction says it can no longer be made as it was asked for). A refusal worth trying again (a concurrent write,
    `version_conflict`, a lock, `idempotency_in_progress` or `lock_timeout`, or 429) leaves the request pending. The lending actions
    keep FR-APR-06: a failed execution leaves the request pending with the error shown.
12. **Answers stored before this change still replay.** The idempotent answer of an expense and of a stock correction is now
    `{record, waiting}`; one stored before the deploy (the bare record, an object with `id`) still replays as the record, so a
    retry after the deploy gets the expense or correction it made and never makes it twice.

## Consequences

**Better:**

- A business decides for itself which retail actions need a second person, and the screen never lists a rule the server does not
  keep.
- Every existing business keeps exactly today's behaviour until an administrator saves a change.
- One rule object replaces three readings of "0".

**Worse:**

- A 202 is a second success shape for three routes. Only a business that switches the approval on sees it; an old app version
  shows the request as done until it reloads (ADR-088 makes old versions update).
- A pending expense or correction is not in the cash book or the stock list, so a till count made while it waits differs by it.
- One waiting correction per item across branches.

**Watch for:**

- A price below cost that waits for an administrator (a held sale) is a follow-up if the owner asks for it.
- An approval rule per branch is not built: the rule is the business's.
- `SaleWriteOffAction` takes a write-off run with no checker (`approvalId` null) as one the switch let through and flags it
  `approval_switched_off`. Every write-off caller today passes its rule, so that path is reached only with the switch off; a future
  caller that runs a write-off below its amount through `Approvals.request` would be flagged the same, and should pass the reason
  in the execution instead.
- Lowering the credit write-off amount to 0 falls back to ADR-043's rule (a second administrator only while two or more count), so a
  business with one administrator then writes off alone. Neither the approval rules nor `PATCH /settings` tells the others of that
  change today; a follow-up should count it as a loosening when fewer than two administrators count.
