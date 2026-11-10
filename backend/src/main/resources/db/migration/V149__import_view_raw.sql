-- V149: customer data onboarding 15/16, the screens of the whole pipeline (issue #141, ADR-027, spec
-- docs/specs/customer-data-onboarding.md sections 6.3 and 11). Directly after V148. Expand only, safe on a populated database:
-- one permission and one grant. Nothing existing is changed.
--
-- `core.imports.view_raw`: see the cells of a staged row as uploaded, and the original value of an issue, on the review
-- screen of an import (spec section 6.3). Every row opened is audited by the application (`core.import_row.raw_viewed`), with
-- the run, the file and the line, never a value. Held by the tenant administrator: the person Rincol Tech signs in with to
-- run a business's onboarding holds that role in the business, and in self service (#142) it is the business's own admin, who
-- is looking at the business's own sheet. Issue messages, counts and reports stay with `core.imports.manage` and never hold a
-- value. System roles are global rows, so every business has it from this migration. PermissionMatrixIT compares the row with
-- the matrix of chapter 8 section 8.3.2.
--
-- Locks, said plainly: two inserts into the catalogue tables, which take row locks only. The lock_timeout below bounds any
-- wait.

SET LOCAL lock_timeout = '5s';

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.imports.view_raw', 'core', 'Imports: see the cells of a staged row as uploaded, audited per row', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.imports.view_raw')
ON CONFLICT DO NOTHING;
