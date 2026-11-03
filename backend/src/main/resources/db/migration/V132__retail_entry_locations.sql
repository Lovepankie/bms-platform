-- V132: where each sale and each usage or damage entry was made, and each shop's own location (issue #826, ADR-122).
-- Numbered the highest on main plus one after the open claim of V131 (the entry dates, issue #811, pull request #831);
-- the merger renumbers at merge time if the order changes, because outOfOrder is off.
--
-- Expand only (chapter 6 section 6.9): two new tables and one new permission. Nothing is dropped, no column changes
-- and no existing table is locked beyond the foreign key checks of the new, empty tables.
--
-- The coordinates are degrees with six decimals (about 11 cm), the accuracy is metres as the phone reports it. They
-- are never logged, never audited and never part of a request hash (ADR-122). Only holders of
-- retail.entry_location.read read a distance computed from them; nobody reads the coordinates through the API.

-- ---------------------------------------------------------------------------------------------
-- 1. One row per entry the phone gave a location for. An entry without one simply has no row ("no location").
-- source is the entry's audit entity type; source_id its id. No foreign key on source_id, since it points at one of
-- two tables, written in the same transaction as the row. bms_app may insert and read; it never updates a row, and
-- deletes only when a closed business is removed (ADR-090).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_entry_locations (
    tenant_id   uuid          NOT NULL REFERENCES tenants (id),
    source      text          NOT NULL CHECK (source IN ('retail.sale', 'retail.usage')),
    source_id   uuid          NOT NULL,
    branch_id   uuid          NOT NULL,
    latitude    numeric(8, 6) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude   numeric(9, 6) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy_m  numeric(9, 1) NOT NULL CHECK (accuracy_m >= 0),
    recorded_at timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source, source_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_entry_locations');
SELECT bms_grant_app('retail_entry_locations', 'SELECT, INSERT, DELETE');

-- ---------------------------------------------------------------------------------------------
-- 2. A shop's own location, optional, set by the owner in Business set-up ("Use where I am now"). A missing row means
-- the shop has no location, and a distance cannot be given.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_shop_locations (
    tenant_id  uuid          NOT NULL REFERENCES tenants (id),
    branch_id  uuid          NOT NULL,
    latitude   numeric(8, 6) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude  numeric(9, 6) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy_m numeric(9, 1) NOT NULL CHECK (accuracy_m >= 0),
    set_by     uuid          NOT NULL,
    set_at     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, branch_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_shop_locations');
SELECT bms_grant_app('retail_shop_locations', 'SELECT, INSERT, UPDATE, DELETE');

-- ---------------------------------------------------------------------------------------------
-- 3. retail.entry_location.read: see how far from the shop each sale and each usage or damage entry was made. It
-- tells where a named person was, so it is the tenant administrator's alone by default (system roles are global rows,
-- so every existing tenant has it from this migration); a business may give it to a custom owner's role.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.entry_location.read', 'retail', 'See how far from the shop each sale and usage or damage entry was made', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.entry_location.read')
ON CONFLICT DO NOTHING;
