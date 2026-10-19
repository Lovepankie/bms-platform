-- V60: savings deposit by mobile money into the lender's own account (extras slice A4, issue #219; ADR-035 and its
-- amendments of 2026-10-15 and 2026-10-18; docs/specs/client-extras.md sections 3.4, 3.4.1 and 3.7). The twin of V59 for
-- savings. Flyway runs with outOfOrder off: this number was the next free one on main and on every open branch when the
-- work began. The open lending pull requests #270 to #272 and #290 carry V54__lending_arrears_and_collections.sql and must
-- be renumbered to V61 or above before they merge.
--
-- Expand only, safe on a populated database. Two things and nothing else:
--
--   1. `lending_savings_transactions.payment_method_key` accepts `gateway` (the payment method of a deposit the payments
--      module booked, mapped to a ledger account by the tenant, FR-GL-08). The old check is replaced by a superset, so
--      every existing row satisfies it. The staff routes still accept only the four cash methods (SavingsApi), so a
--      cashier cannot book a `gateway` deposit by hand.
--   2. A unique index over (tenant, payment method, external reference) for the method `gateway`, so an order tracking id
--      (the provider's reference, unique per payment; the short confirmation code is not) books one deposit however many
--      times it is presented (spec 3.4.1). It is partial: no existing row has the method `gateway` (the check above
--      refused it until now), so building it scans the table once and finds nothing to collide with. A reversal row never
--      copies the method or the reference, and the index names the type that carries one.
--
-- The table `unallocated_receipts` and the one-booking-per-intent index on `payment_intents` came with V59 and serve this
-- slice unchanged: a deposit the account refused (closed, frozen, a day the books cannot take) is posted to unallocated
-- receipts exactly like a refused repayment.
--
-- Locks, said plainly: the check swap takes ACCESS EXCLUSIVE on `lending_savings_transactions` and holds it to the commit
-- (one transaction), so the validation scan and the index build both run with writes to that table blocked. Both are short
-- on the tables of the first tenants; `lock_timeout` makes the migration fail rather than queue every writer
-- behind it. Flyway does not retry on its own: a failed migration fails the deploy, and the deploy must be run again. NOT VALID followed by VALIDATE in the same transaction would gain no lock relief, so the check is added
-- validated.

SET LOCAL lock_timeout = '5s';

ALTER TABLE lending_savings_transactions DROP CONSTRAINT lending_savings_transactions_payment_method_key_check;
ALTER TABLE lending_savings_transactions ADD CONSTRAINT lending_savings_transactions_payment_method_key_check
    CHECK (payment_method_key IN ('cash', 'bank', 'mtn_momo', 'airtel_money', 'gateway'));

CREATE UNIQUE INDEX lending_savings_transactions_gateway_reference
    ON lending_savings_transactions (tenant_id, payment_method_key, external_reference)
    WHERE payment_method_key = 'gateway' AND external_reference IS NOT NULL AND txn_type = 'deposit';
