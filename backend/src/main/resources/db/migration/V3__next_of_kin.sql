-- V3: next of kin and the member relationship view (MVP increment 3, issue #11).
--
-- Additive only (chapter 6 section 6.9): one tenant-owned table and one view. Nothing created by
-- V1 or V2 is altered.

-- ---------------------------------------------------------------------------------------------
-- lending_next_of_kin (chapter 6 section 6.7; FR-MEM-06, FR-MEM-07)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_next_of_kin (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz,
    version           integer      NOT NULL DEFAULT 1,
    member_id         uuid         NOT NULL,
    full_name         varchar(200) NOT NULL,
    phone_e164        varchar(16),
    national_id       varchar(14)  CHECK (national_id ~ '^C[MF][A-Z0-9]{12}$'),
    relationship      text         NOT NULL
                                   CHECK (relationship IN ('spouse', 'parent', 'child', 'sibling', 'relative', 'friend', 'employer', 'other')),
    relationship_text varchar(60),
    location          varchar(200),
    is_primary        boolean      NOT NULL DEFAULT false,
    linked_member_id  uuid,
    link_method       text         CHECK (link_method IN ('nin', 'phone', 'manual')),
    link_status       text         NOT NULL DEFAULT 'none'
                                   CHECK (link_status IN ('none', 'suggested', 'confirmed', 'rejected')),
    created_by        uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, member_id) REFERENCES lending_members (tenant_id, id),
    FOREIGN KEY (tenant_id, linked_member_id) REFERENCES lending_members (tenant_id, id),
    CHECK (linked_member_id IS NULL OR linked_member_id <> member_id),
    CHECK ((linked_member_id IS NULL) = (link_method IS NULL))
);
CREATE UNIQUE INDEX lending_next_of_kin_one_primary ON lending_next_of_kin (tenant_id, member_id) WHERE is_primary;
CREATE INDEX lending_next_of_kin_member ON lending_next_of_kin (tenant_id, member_id);
CREATE INDEX lending_next_of_kin_linked ON lending_next_of_kin (tenant_id, linked_member_id);
CREATE INDEX lending_next_of_kin_nin ON lending_next_of_kin (tenant_id, national_id);
CREATE INDEX lending_next_of_kin_phone ON lending_next_of_kin (tenant_id, phone_e164);
SELECT bms_apply_tenant_rls('lending_next_of_kin');
-- DELETE: a next of kin is personal data the member may withdraw (chapter 7 section 7.11.11).
SELECT bms_grant_app('lending_next_of_kin', 'SELECT, INSERT, UPDATE, DELETE');

-- ---------------------------------------------------------------------------------------------
-- lending_member_links_v (chapter 6 section 6.7): the relationship graph for FR-MEM-08 and the
-- exposure rule of chapter 3 section 3.18.1. Next of kin edges only for now; guarantor edges join
-- when lending_loan_guarantors exists (increment 4).
--
-- security_invoker: a plain view runs with its owner's rights, and the owner is not subject to
-- the tenant policy; invoker rights keep the base table's row-level security in force (ADR-003).
-- ---------------------------------------------------------------------------------------------

-- source_id is the row an edge comes from (the next of kin here; the guarantee, later), so a
-- reader can join back to it exactly even when two rows link the same pair of members.
CREATE VIEW lending_member_links_v WITH (security_invoker = true) AS
    SELECT tenant_id, member_id, linked_member_id AS related_member_id, 'names_as_kin'::text AS link_kind,
           id AS source_id
      FROM lending_next_of_kin
     WHERE linked_member_id IS NOT NULL AND (link_status = 'confirmed' OR link_method = 'nin')
    UNION ALL
    SELECT tenant_id, linked_member_id, member_id, 'named_as_kin_by'::text, id
      FROM lending_next_of_kin
     WHERE linked_member_id IS NOT NULL AND (link_status = 'confirmed' OR link_method = 'nin');
SELECT bms_grant_app('lending_member_links_v', 'SELECT');
