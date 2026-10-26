# Runbook: credit sales count when the buyer pays

For ADR-043 and issue #340. It tells an administrator how to switch the rule on for a retail tenant, what changes on the
screens, how to check the figures and how the operator undoes it. Nothing here touches stock or the ledger.

## What the setting does

`credit_sale_recognition` is a per-tenant setting of the Retail settings group.

- `on_sale` (the default for every tenant): a credit sale counts as a sale, as profit and in the daily savings on the day it is made.
  This is how the system always worked.
- `on_payment` (the owner's rule): a credit sale is Sold and On credit. It counts as profit only as the buyer pays, on
  the day of each payment, with its share of the sale's profit (the payment times the sale's profit margin). Received is separate: it is
  the money recorded as collected, by any method, on the day it was entered: every payment on a credit sale, new or old, imported or
  not (a payment dated earlier counts on the day it was entered). It is not a drawer count. Cash sales count on the day they were entered. A day already reported is never rewritten, except that cancelling (voiding) a cash sale removes it from the day it was entered; a
  reversed account payment and a voided return count on the day they happen.

Stock still leaves at the sale and trade debtors are recorded at the sale, so the accounts balance either way. Imported (historical)
credit sales are outside the rule.

## Before you switch it on

Do not switch the pilot tenant to "When the buyer pays" until the sales summary and the grouped sales lists have caught up (issue #341 and
the one after it): until then they show the profit of sales made, labelled so, beside the profit of money received that the home page,
Daily profit and the savings use.

**The switch is one way.** It starts the next business day, not today, and once it has started the product does not switch it off again
("This rule cannot be switched off once it has started. Ask Rincol support."). Until the start day has come the change is pending and can
be cancelled by choosing "When the sale is made". A credit sale counts from the later of the date written on it and the day it was entered. Sales entered and
dated before the start day keep counting the way they did, so nothing is counted twice and no savings already set aside move, and the
sales you made today are not re-labelled. A sale dated before the start day but entered after it (a backdated sale) is counted when the
buyer pays, so it adds no profit to a day already reported. Under "When the buyer pays" a payment on a sale that counts from the start
day is dated today (a backdated payment is refused: ask your administrator; a payment on a sale entered and dated before the start
keeps its old freedom), counts on the day it is entered, and goes to the branch that took the money; a return paid out in money
takes the profit back from the branch that paid the refund, and a return credited to the buyer's balance takes it back from the
branches that took the sale's payments, in proportion. Once the rule has started, a report that holds a day from the start on, or a credit sale counted when the buyer pays, is read the
when-paid way and says the day it starts; a report of earlier days, such as the month before, keeps the plain layout and is not
relabelled. A report that straddles the start shows the older credit sales apart, as "Credit counted at the sale".

## Switch it on

1. Sign in as an administrator (`core.settings.manage`) and open Retail, then Retail settings.
2. Under "When does a credit sale count as income and profit?", choose "When the buyer pays" and tap Review change.
3. Read the sentences, then tap Confirm. The change is recorded with your name (audit action `retail.capabilities.changed`).
4. The rule starts the next business day, so the Retail home page keeps its cards until then. From that day the cards read Sales today
   (everything sold, credit included), Money collected (money recorded as collected today, by any method, less money paid back), On credit (what buyers still owe) and Profit today
   (from the money received). Until then the page and Daily profit say when the rule starts.

## Check the figures

1. Pick a day with a credit sale that is unpaid. In Daily profit, Sold shows the sale, Received and Profit do not (for a sale counted when the buyer pays), and On credit now
   shows what is owed.
2. Record a part payment on the sale (Record payment). In Daily profit, on the day of the payment, Received is the payment (for any credit sale, old ones included) and Profit
   is, for a sale counted when the buyer pays, the payment times the margin of the sale, rounded once on the running total so the parts add up to the whole.
3. Open the cash book savings for that day: the suggestion is half of the received profit of the shop for the day.
4. A savings amount already saved is not changed by the switch. Void it and enter it again if it should follow the new rule.

## Write off a buyer who never pays

1. Open the credit sale (Credit sales, then the sale) as an administrator (`retail.credit.write_off`).
2. Tap Write off, give the reason and tap Write off again. Unless you set a limit for `retail_sale_write_off`, a second administrator approves it in the approvals inbox first when the business has two or more administrators who count: active ones, locked ones, and ones removed or deactivated in the last 30 days. With a limit set, only a debt at or above it needs a second person. Whenever a write-off is done without a second person, whatever the reason, the audit row says so (`no_second_person`: `only_one_active_administrator`, or `below_threshold` when two or more count but the limit let it through) with `notified_admins`, so raising the limit first does not make a write-off silent. The other administrators who count are emailed that a debt was written off, only those still active; the maker is told only when no other active administrator can be, which for a sole administrator is a notice to themselves plus the audit row. The tenant record has no contact email, so there is no further address.
3. The sale stays and reads Written off. The unpaid amount leaves On credit and the owing and overdue lists. Daily profit shows the loss on that day (with "When the buyer pays" the unpaid share of the cost of goods, with the default the whole unpaid amount), which also lowers that day's savings suggestion.
4. The accounts: trade debtors for the sale are cleared against bad debt loss (account 5140) and, with "When the buyer pays", sales revenue for the rest. Nothing is deleted and a write-off cannot be undone in this release; ask the maintainers if one was made by mistake.

## Undo it (operator only, by SQL)

There is no button for this, on purpose. Do it only when the owner asks and understands that the past is read again by the old rule:
every credit sale counts at the sale again, so the profit that came in as payments leaves those days and returns to the days of the
sales, and the savings amounts already saved keep their amounts. A write-off made
while the rule stood keeps the loss it stored, which is the cost share only. After the revert the reports show the whole profit of that
sale at the sale, including the margin on the unpaid part that was never collected, against that smaller loss, so that margin stays in
the reported profit until someone corrects it; list such sales first (`retail_sale_writeoffs` rows with `recognition = 'on_payment'`).
Take a note of the reason and the ticket first. The change is written to the audit log in the same transaction, as the
operator's act. Then, as the database owner, for the one tenant, with psql:

```sql
\set tenant_id '<the tenant id>'
\set reason '<why>'
\set ticket '<ticket>'
BEGIN;
UPDATE tenant_settings
   SET settings = jsonb_set(settings, '{module_groups,retail}',
                            (settings #> '{module_groups,retail}') - 'credit_recognition_effective_from'
                            || '{"credit_sale_recognition": "on_sale"}'::jsonb),
       version = version + 1
 WHERE tenant_id = :'tenant_id';
INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
VALUES (gen_random_uuid(), :'tenant_id', 'platform', 'retail.capabilities.changed', 'core.tenant_settings', :'tenant_id',
        jsonb_build_object('after', jsonb_build_object('credit_sale_recognition', 'on_sale'),
                           'reason', :'reason', 'ticket', :'ticket', 'by', 'operator SQL, credit-sale-recognition runbook'));
-- Expect UPDATE 1 and INSERT 0 1, then COMMIT. Check Retail settings and Daily profit read "When the sale is made".
COMMIT;
```

If the change is only pending (the start day has not come), the owner cancels it from Retail settings; no SQL is needed.

Residual risk: changing the tenant's time zone can move the entry day of sales entered near midnight, and so whether such a sale counts
from the start day; the start day itself is a stored date and does not move.

## Known differences while both rules exist

The sales analysis, margins and business evaluation reports, and the grouped sales lists, still count what was sold. They say so in
their own words; Sold on the home page and in Daily profit is the figure that matches them.
