-- V88: an optional logo for each shop (branch) of a business (issue #461, ADR-068).
--
-- V88 is the first free number above every open branch: V81 to V84 are claimed by lending, V85 and V86 by payments and
-- V87 by offline sales. Flyway runs with outOfOrder off (AGENTS.md), so this file is renamed at merge if the numbers below
-- it land in another order.
--
-- Expand only, safe on a populated database. The logo itself is a row of documents (V4) stored through core.documents with
-- the same subject as the business logo (core.tenant, the tenant itself), re-encoded at upload so no metadata survives.
-- This nullable column points at it; every existing branch has none and keeps showing the business logo.
--
-- Locks, said plainly. ADD COLUMN with no default and the foreign key take ACCESS EXCLUSIVE on branches and SHARE ROW
-- EXCLUSIVE on documents until the commit; every value is null, so the key is checked at once. lock_timeout is five seconds
-- as in V26 and V68: if a lock cannot be had at once the migration fails and is retried.

SET LOCAL lock_timeout = '5s';

ALTER TABLE branches ADD COLUMN logo_document_id uuid;
ALTER TABLE branches
    ADD CONSTRAINT branches_logo_fk
        FOREIGN KEY (tenant_id, logo_document_id) REFERENCES documents (tenant_id, id);
