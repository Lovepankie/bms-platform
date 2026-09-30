-- V5: the collateral register (MVP increment 3, issue #13).
--
-- Additive only (chapter 6 section 6.9). lending_loan_collateral waits for lending_loans
-- (increment 4); until then the duplicate pledge rule looks at items that are not released or
-- disposed (FR-COL-01, interim).

-- ---------------------------------------------------------------------------------------------
-- lending_collateral_items (chapter 6 section 6.7; FR-COL-01, FR-COL-03)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_collateral_items (
    id                      uuid         NOT NULL PRIMARY KEY,
    tenant_id               uuid         NOT NULL REFERENCES tenants (id),
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz,
    version                 integer      NOT NULL DEFAULT 1,
    branch_id               uuid         NOT NULL,
    member_id               uuid         NOT NULL,
    collateral_type         text         NOT NULL
                                         CHECK (collateral_type IN ('land_title', 'vehicle_logbook', 'vehicle', 'national_id',
                                                                    'household_item', 'other')),
    description             varchar(300) NOT NULL,
    reference_no            varchar(60),
    reference_no_normalised varchar(60),
    owner_name              varchar(200),
    owner_relationship      text         NOT NULL DEFAULT 'self' CHECK (owner_relationship IN ('self', 'spouse', 'other')),
    estimated_value_minor   bigint       CHECK (estimated_value_minor >= 0),
    currency                char(3)      NOT NULL REFERENCES currencies (code),
    custody_status          text         NOT NULL
                                         CHECK (custody_status IN ('pledged', 'in_custody', 'released', 'seized', 'disposed')),
    storage_location        varchar(200),
    created_by              uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, member_id) REFERENCES lending_members (tenant_id, id),
    CHECK (collateral_type <> 'vehicle' OR reference_no_normalised IS NOT NULL),
    CHECK (custody_status <> 'in_custody' OR storage_location IS NOT NULL)
);
CREATE INDEX lending_collateral_items_reference
    ON lending_collateral_items (tenant_id, collateral_type, reference_no_normalised);
CREATE INDEX lending_collateral_items_member ON lending_collateral_items (tenant_id, member_id);
CREATE INDEX lending_collateral_items_list ON lending_collateral_items (tenant_id, branch_id, created_at, id);
SELECT bms_apply_tenant_rls('lending_collateral_items');
SELECT bms_grant_app('lending_collateral_items', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- lending_collateral_valuations (FR-COL-02). Written once; a new valuation supersedes by date.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_collateral_valuations (
    id                      uuid         NOT NULL PRIMARY KEY,
    tenant_id               uuid         NOT NULL REFERENCES tenants (id),
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz,
    collateral_id           uuid         NOT NULL,
    valued_on               date         NOT NULL,
    valuer_name             varchar(200),
    market_value_minor      bigint       NOT NULL CHECK (market_value_minor > 0),
    forced_sale_value_minor bigint       CHECK (forced_sale_value_minor > 0),
    note                    text,
    recorded_by             uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, collateral_id) REFERENCES lending_collateral_items (tenant_id, id),
    CHECK (forced_sale_value_minor IS NULL OR forced_sale_value_minor <= market_value_minor)
);
CREATE INDEX lending_collateral_valuations_item
    ON lending_collateral_valuations (tenant_id, collateral_id, valued_on DESC, created_at DESC);
SELECT bms_apply_tenant_rls('lending_collateral_valuations');
SELECT bms_grant_app('lending_collateral_valuations', 'SELECT, INSERT');

-- ---------------------------------------------------------------------------------------------
-- lending_collateral_events (FR-COL-03), append-only: the custody timeline.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_collateral_events (
    id                  uuid         NOT NULL PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    collateral_id       uuid         NOT NULL,
    event_type          text         NOT NULL
                                     CHECK (event_type IN ('registered', 'received_into_custody', 'moved', 'released', 'seized',
                                                           'disposed', 'note')),
    from_status         text,
    to_status           text,
    location            varchar(200),
    counterparty_name   varchar(200),
    note                text,
    occurred_at         timestamptz  NOT NULL,
    recorded_by         uuid         NOT NULL,
    approval_request_id uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, collateral_id) REFERENCES lending_collateral_items (tenant_id, id)
);
CREATE INDEX lending_collateral_events_item ON lending_collateral_events (tenant_id, collateral_id, occurred_at, id);
SELECT bms_apply_tenant_rls('lending_collateral_events');
SELECT bms_grant_app('lending_collateral_events', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_collateral_events');

-- ---------------------------------------------------------------------------------------------
-- lending_collateral_documents: photos and scans (FR-COL-01), through the documents module.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_collateral_documents (
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    collateral_id uuid        NOT NULL,
    document_id   uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, collateral_id, document_id),
    FOREIGN KEY (tenant_id, collateral_id) REFERENCES lending_collateral_items (tenant_id, id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id)
);
SELECT bms_apply_tenant_rls('lending_collateral_documents');
SELECT bms_grant_app('lending_collateral_documents', 'SELECT, INSERT');
