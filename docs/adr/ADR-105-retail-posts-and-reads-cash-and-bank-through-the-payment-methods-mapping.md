# ADR-105: Retail posts and reads cash and bank through the payment methods mapping

## Status

Proposed (issue #236; FR-GL-08, FR-RET-11). Supersedes the part of ADR-026 that keeps retail on the seeded `cash_on_hand`
and `bank` accounts and refuses to remap `cash` or `bank` while retail is on (`account_used_by_retail`,
`payment_methods_remap_blocks_retail`). Builds on ADR-020 decision 7 (retail posts through its books) and ADR-022 (the cash
book). Migration `V117`, after `V114` (#127), `V115` (#676) and `V116` (#239).

## Context

Since #160 (`V43`), lending's loans, savings and investments post a payment to the account the tenant maps the method to in
`payment_methods`, the branch's row before the tenant's, and to the seeded account (ADR-026) when there is no row. Retail did
not: its sales, purchases, supplier payments, returns, payments on account and cash book events named `cash_on_hand` and
`bank` by system key, and the cash book read the balance of `cash_on_hand`. A tenant with both verticals that remapped `cash`
would split its cash: lending cash on the mapped till, outside the retail cash book, and retail cash on `cash_on_hand`.
ADR-026 closed the gap by refusing the remap while retail is on, and refusing to switch retail on while a remap exists. Issue
#236 asked retail to choose between keeping that refusal and posting through the mapping; the retail session chose the
mapping.

## Decision

1. **Retail resolves the till and the bank through `PaymentMethods`.** `RetailBooks.accountRef(systemKey, branch)` maps a
   leg on `cash_on_hand` to the method `cash` and a leg on `bank` to the method `bank`, and asks `core.ledger`'s
   `PaymentMethods.accountRefIfMapped` at the entry's branch. Every retail posting goes through `RetailBooks.post`, so every
   rule (sales, restocks, supplier payments, returns, payments on account, customer orders, the cash book and its imported
   opening balances) follows the mapping without naming it. Any other system key posts as before.
2. **Retail has no switch-off.** When the method is unmapped, switched off (`is_active = false`) or mapped to an account
   that is no longer postable and active, retail posts to the seeded account, as it did before the mapping existed. Lending
   still refuses a switched-off method (`payment_method_unmapped`); a shop cannot stop taking cash.
3. **The cash book reads every account the method has gone to.** The daily cash position, the cash on hand that the banking
   check compares and the bank balance that the withdrawal check compares read, each account once, the seeded account, the
   current mappings for the branch and the tenant, and every account a save ever mapped the method to for them
   (`PaymentMethods.accountRefsEverUsed`, from `payment_methods` and the audit of `core.payment_method.set`). A remap,
   first or later, therefore moves no past day and no cash on hand, and the cash left on an earlier till counts until the
   business moves it with a journal, which nets to nothing. `LedgerAccounts.balanceByBranch` and `movementByDay` take a
   reference (`gl:<account id>` or a system key) for this. Lending cash on the shared till shows in the cash book as other
   movements, never hidden (ADR-022 decision 10).
4. **The restrictions go.** `PUT /ledger/payment-methods` no longer refuses `cash` or `bank` with retail on, and `V117`
   replaces `platform_set_tenant_modules` with its V10 body, without the check and the advisory lock that V43 added.
5. **Mobile money stays on retail's own account.** Retail's `mobile_money` (1035) has no method in `payment_methods`
   (lending's are `mtn_momo` and `airtel_money`), so it stays on its system key.

## Consequences

**Better:** a tenant that remaps cash keeps all its cash, retail and lending, on one till that the cash book shows; a branch
mapping gives each shop its own till. A tenant that never remapped posts to exactly the accounts it did before
(`RetailPaymentMethodsIT`).

**Worse:** a leg named `cash_on_hand` may now land on another account, so a reader of the journal must resolve the mapping
rather than trust the key. Mapping cash to an account that already holds other entries brings those into the cash book,
on their own days, as other movements; a fresh till is the expected choice. The cash book's read now depends on the audit
of the mapping saves, which is append-only.

**Watch for:** a new retail rule that posts outside `RetailBooks`, or reads a cash balance by system key, would split the cash
again. A future mapping for retail's `mobile_money` would need a method key of its own.
