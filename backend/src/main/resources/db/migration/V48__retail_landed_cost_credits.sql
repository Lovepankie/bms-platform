-- V48: landed cost and supplier credit notes for the `purchasing` capability (ADR-033, issue #199;
-- docs/specs/trade-capabilities.md sections 7.2, 7.4 and 12.3).
--
-- Expand only, safe on a populated database. Eight new tables, one new stock movement kind, one new column on the
-- small purchasing settings table, and no new permission (the documents use retail.payable.manage of V47) and no new
-- chart account (inventory, cost of goods sold, trade creditors, purchase price variance and inter-branch clearing exist).
-- Nothing is dropped, renamed or rewritten. The capability lives in the tenant settings group retail.capabilities and is
-- off until an administrator turns it on: a tenant that never switches it on never writes a row to any table below, and
-- the plain restock (POST /retail/purchases) is untouched whatever the flag.
--
-- Locks, said plainly. Flyway runs this file in one transaction, so every lock below is held to the commit.
--  1. The one statement that touches a large table is the replacement of the two stock movement checks (kind and sign) to
--     admit the kind return_to_supplier, and it is the last statement of the file. DROP CONSTRAINT and ADD CONSTRAINT take
--     ACCESS EXCLUSIVE on retail_stock_movements, which blocks reads and writes of stock until the commit. Both are metadata
--     changes: the new checks are added NOT VALID, so no row is scanned, and they are strictly looser than the ones they
--     replace, so every existing row satisfies them and a later VALIDATE CONSTRAINT (which takes only SHARE UPDATE EXCLUSIVE
--     and blocks no write) can never fail. New rows are checked from the moment the constraint exists. The danger of an
--     ACCESS EXCLUSIVE lock is queueing behind a long transaction while every later query queues behind it, so the file sets
--     lock_timeout to five seconds: if a lock cannot be had at once the migration fails and is retried, and the shop is never
--     stalled. Placed last, the lock is held for the few milliseconds between taking it and the commit.
--  2. CREATE TABLE with foreign keys takes SHARE ROW EXCLUSIVE on each referenced table (tenants, branches, currencies,
--     retail_suppliers, retail_products, retail_goods_receipt_lines, retail_supplier_bills, retail_supplier_bill_lines,
--     journal_entries) until the commit, which blocks writes to those tables, but not reads, while the file runs. The new
--     tables are empty, so the cost is the lock wait and not a scan.
--  3. ALTER TABLE retail_purchasing_settings ADD COLUMN with a constant default is metadata only on PostgreSQL 11 and
--     later (no rewrite); the table has one row per tenant that opened the settings screen.
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- The setting that holds a receipt back from being moved on until it is costed (trade-capabilities.md section 7.2): off by
-- default, so a business that does not use landed cost sees nothing. Edited with the other tolerances under If-Match.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_purchasing_settings
    ADD COLUMN require_costing_before_sale boolean NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------------------------------
-- retail_landed_costs: a landed cost document. Freight, duty, clearing and other charges of one payee (the forwarder, the
-- carrier or the tax authority, a supplier row) spread over the lines of one or more goods received notes. Its payable is a
-- supplier bill of kind landed_cost (bill_id, V47's table), so the forwarder is paid by the same payment machinery as any
-- supplier and appears in the same ageing. Numbered LC + branch code + six digits per tenant and branch (the branch of its
-- first received line). method says how the total was spread: by the value of the received lines, by their quantity, by a
-- weight entered per line, or by amounts entered per line (manual, which must add to the total). The total is the sum of
-- the charges and the sum of the allocated lines: a deferred trigger refuses a document whose parts do not add up at
-- commit. Only the void changes a row, once, and not while a payment stands against its bill.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_landed_costs (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    version       integer      NOT NULL DEFAULT 1,
    landed_no     varchar(24)  NOT NULL,
    branch_seq    bigint       NOT NULL CHECK (branch_seq >= 1),
    branch_id     uuid         NOT NULL,
    supplier_id   uuid         NOT NULL,
    bill_id       uuid         NOT NULL,
    goods_bill_id uuid,
    method        text         NOT NULL CHECK (method IN ('value', 'quantity', 'weight', 'manual')),
    cost_date     date         NOT NULL,
    currency      char(3)      NOT NULL REFERENCES currencies (code),
    total_minor   bigint       NOT NULL CHECK (total_minor > 0 AND total_minor <= 10000000000000),
    note          varchar(300),
    status        text         NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'voided')),
    created_by    uuid         NOT NULL,
    voided_at     timestamptz,
    voided_on     date,
    voided_by     uuid,
    void_reason   varchar(300),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, landed_no),
    UNIQUE (tenant_id, branch_id, branch_seq),
    UNIQUE (tenant_id, bill_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES retail_suppliers (tenant_id, id),
    FOREIGN KEY (tenant_id, bill_id) REFERENCES retail_supplier_bills (tenant_id, id),
    FOREIGN KEY (tenant_id, goods_bill_id) REFERENCES retail_supplier_bills (tenant_id, id),
    CHECK ((status = 'voided') = (voided_at IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_on IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_by IS NOT NULL)),
    CHECK ((status = 'voided') = (void_reason IS NOT NULL AND btrim(void_reason) <> ''))
);
CREATE INDEX retail_landed_costs_branch ON retail_landed_costs (tenant_id, branch_id, created_at DESC, id);
CREATE INDEX retail_landed_costs_supplier ON retail_landed_costs (tenant_id, supplier_id, created_at DESC);
CREATE INDEX retail_landed_costs_goods_bill ON retail_landed_costs (tenant_id, goods_bill_id) WHERE goods_bill_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_landed_costs');
SELECT bms_grant_app('retail_landed_costs', 'SELECT, INSERT');
GRANT UPDATE (updated_at, version, status, voided_at, voided_on, voided_by, void_reason)
    ON retail_landed_costs TO bms_app;

CREATE FUNCTION retail_landed_costs_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'posted' OR NEW.status <> 'voided'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.landed_no, NEW.branch_seq, NEW.branch_id, NEW.supplier_id,
           NEW.bill_id, NEW.goods_bill_id, NEW.method, NEW.cost_date, NEW.currency, NEW.total_minor, NEW.note,
           NEW.created_by)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.landed_no, OLD.branch_seq, OLD.branch_id, OLD.supplier_id,
           OLD.bill_id, OLD.goods_bill_id, OLD.method, OLD.cost_date, OLD.currency, OLD.total_minor, OLD.note,
           OLD.created_by)
    THEN
        RAISE EXCEPTION 'retail_landed_costs: a landed cost can only be voided, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE FUNCTION retail_landed_costs_guard_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.status <> 'posted' OR NEW.voided_at IS NOT NULL OR NEW.version <> 1 THEN
        RAISE EXCEPTION 'retail_landed_costs: a landed cost is recorded posted and is voided afterwards'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_landed_costs_guard_insert BEFORE INSERT ON retail_landed_costs
    FOR EACH ROW EXECUTE FUNCTION retail_landed_costs_guard_insert();
CREATE TRIGGER retail_landed_costs_guard_update BEFORE UPDATE ON retail_landed_costs
    FOR EACH ROW EXECUTE FUNCTION retail_landed_costs_guard_update();
CREATE TRIGGER retail_landed_costs_reject_delete BEFORE DELETE ON retail_landed_costs
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- The charges: what the payee billed, by kind. They add up to the document's total.
CREATE TABLE retail_landed_cost_charges (
    id             uuid         NOT NULL PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenants (id),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    landed_cost_id uuid         NOT NULL,
    line_no        smallint     NOT NULL CHECK (line_no >= 1),
    kind           text         NOT NULL CHECK (kind IN ('freight', 'duty', 'clearing', 'insurance', 'other')),
    description    varchar(120),
    amount_minor   bigint       NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    UNIQUE (tenant_id, id),
    UNIQUE (landed_cost_id, line_no),
    FOREIGN KEY (tenant_id, landed_cost_id) REFERENCES retail_landed_costs (tenant_id, id)
);
CREATE INDEX retail_landed_cost_charges_doc ON retail_landed_cost_charges (tenant_id, landed_cost_id);
SELECT bms_apply_tenant_rls('retail_landed_cost_charges');
SELECT bms_grant_app('retail_landed_cost_charges', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_landed_cost_charges');

-- The allocation: one row per received line the total was spread over. allocated_minor is the line's share (exact integers,
-- the shares add to the total). Of it, to_inventory_minor is the part capitalised into the inventory of the branches that hold
-- the product (in proportion to what each holds) and to_cogs_minor the part posted to cost of goods sold in the receiving
-- branch, because it belongs to units already sold, used or still in transit. on_hand_qty of line_qty units were left across
-- all branches when the charge was recorded, by the first-in first-out reading of the issue #199 amendment of ADR-033; the
-- capitalised part is the cost change per unit times those units, so the stock valuation (balance times cost) rises by what
-- the ledger's inventory account does. The product's cost per base unit rose from cost_before_minor to cost_after_minor when
-- cost_applied, and was left alone, with cost_note saying why, when a newer cost event had set it since.
CREATE TABLE retail_landed_cost_lines (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    landed_cost_id   uuid          NOT NULL,
    line_no          smallint      NOT NULL CHECK (line_no >= 1),
    receipt_line_id  uuid          NOT NULL,
    branch_id        uuid          NOT NULL,
    product_id       uuid          NOT NULL,
    basis            numeric(20,3) NOT NULL CHECK (basis >= 0),
    weight           numeric(14,3) CHECK (weight > 0),
    allocated_minor  bigint        NOT NULL CHECK (allocated_minor >= 0 AND allocated_minor <= 10000000000000),
    line_qty         numeric(14,3) NOT NULL CHECK (line_qty > 0),
    on_hand_qty      numeric(14,3) NOT NULL CHECK (on_hand_qty >= 0),
    to_inventory_minor bigint      NOT NULL CHECK (to_inventory_minor >= 0),
    to_cogs_minor    bigint        NOT NULL CHECK (to_cogs_minor >= 0),
    cost_before_minor bigint       NOT NULL CHECK (cost_before_minor >= 0),
    cost_after_minor bigint        NOT NULL CHECK (cost_after_minor >= 0),
    cost_applied     boolean       NOT NULL,
    cost_note        varchar(200),
    UNIQUE (tenant_id, id),
    UNIQUE (landed_cost_id, line_no),
    UNIQUE (landed_cost_id, receipt_line_id),
    FOREIGN KEY (tenant_id, landed_cost_id) REFERENCES retail_landed_costs (tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_line_id) REFERENCES retail_goods_receipt_lines (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    CHECK (allocated_minor = to_inventory_minor + to_cogs_minor),
    CHECK (on_hand_qty <= line_qty)
);
CREATE INDEX retail_landed_cost_lines_doc ON retail_landed_cost_lines (tenant_id, landed_cost_id);
CREATE INDEX retail_landed_cost_lines_receipt_line ON retail_landed_cost_lines (tenant_id, receipt_line_id);
SELECT bms_apply_tenant_rls('retail_landed_cost_lines');
SELECT bms_grant_app('retail_landed_cost_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_landed_cost_lines');

-- The journal entries of a landed cost, one per branch of the received lines (the entry of a branch debits that branch's
-- inventory and cost of goods sold), kept so a void can reverse each of them.
CREATE TABLE retail_landed_cost_entries (
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    landed_cost_id uuid        NOT NULL,
    branch_id      uuid        NOT NULL,
    entry_id       uuid        NOT NULL,
    PRIMARY KEY (tenant_id, landed_cost_id, branch_id),
    FOREIGN KEY (tenant_id, landed_cost_id) REFERENCES retail_landed_costs (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES journal_entries (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_landed_cost_entries');
SELECT bms_grant_app('retail_landed_cost_entries', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_landed_cost_entries');

-- A landed cost whose parts do not add up cannot be committed: the charges add to the total and so do the allocated
-- lines. Deferred to the commit because the header is inserted before its charges and lines.
CREATE FUNCTION retail_landed_costs_check_parts() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_charges bigint;
    v_lines   bigint;
BEGIN
    SELECT coalesce(sum(amount_minor), 0) INTO v_charges
      FROM retail_landed_cost_charges WHERE tenant_id = NEW.tenant_id AND landed_cost_id = NEW.id;
    SELECT coalesce(sum(allocated_minor), 0) INTO v_lines
      FROM retail_landed_cost_lines WHERE tenant_id = NEW.tenant_id AND landed_cost_id = NEW.id;
    IF v_charges <> NEW.total_minor OR v_lines <> NEW.total_minor THEN
        RAISE EXCEPTION 'retail_landed_costs: the charges (%) and the allocated lines (%) must each add to the total (%)',
            v_charges, v_lines, NEW.total_minor
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER retail_landed_costs_check_parts AFTER INSERT ON retail_landed_costs
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION retail_landed_costs_check_parts();

-- ---------------------------------------------------------------------------------------------
-- retail_receipt_holds: a goods received note held "received, not costed" until its landed cost is in. Released by a landed
-- cost that allocates to any of its lines (release_kind landed_cost) or by hand with a reason (waived: no landed cost will
-- come). While the setting require_costing_before_sale is on, stock of a held receipt's products cannot be moved out of
-- the receiving branch by a transfer. One row per receipt; only the release changes it, once, and the release by a landed
-- cost is undone when that landed cost is voided.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_receipt_holds (
    tenant_id       uuid         NOT NULL REFERENCES tenants (id),
    receipt_id      uuid         NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    held_by         uuid         NOT NULL,
    released_at     timestamptz,
    released_by     uuid,
    release_kind    text         CHECK (release_kind IN ('landed_cost', 'waived')),
    release_reason  varchar(300),
    landed_cost_id  uuid,
    PRIMARY KEY (tenant_id, receipt_id),
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES retail_goods_receipts (tenant_id, id),
    FOREIGN KEY (tenant_id, landed_cost_id) REFERENCES retail_landed_costs (tenant_id, id),
    CHECK ((released_at IS NULL) = (release_kind IS NULL)),
    CHECK ((released_at IS NULL) = (released_by IS NULL)),
    CHECK (release_kind IS DISTINCT FROM 'waived' OR (release_reason IS NOT NULL AND btrim(release_reason) <> '')),
    CHECK ((release_kind = 'landed_cost') = (landed_cost_id IS NOT NULL))
);
CREATE INDEX retail_receipt_holds_open ON retail_receipt_holds (tenant_id, receipt_id) WHERE released_at IS NULL;
SELECT bms_apply_tenant_rls('retail_receipt_holds');
SELECT bms_grant_app('retail_receipt_holds', 'SELECT, INSERT');
GRANT UPDATE (released_at, released_by, release_kind, release_reason, landed_cost_id) ON retail_receipt_holds TO bms_app;

CREATE FUNCTION retail_receipt_holds_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id, NEW.receipt_id, NEW.created_at, NEW.held_by)
       IS DISTINCT FROM (OLD.tenant_id, OLD.receipt_id, OLD.created_at, OLD.held_by)
    THEN
        RAISE EXCEPTION 'retail_receipt_holds: the identity of a hold never changes'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- A hold is released once; the one release that is undone is the release by a landed cost, when that landed cost is
    -- voided and no other stands (the hold is put back).
    IF OLD.released_at IS NOT NULL
       AND NOT (OLD.release_kind = 'landed_cost' AND NEW.released_at IS NULL AND NEW.released_by IS NULL
                AND NEW.release_kind IS NULL AND NEW.release_reason IS NULL AND NEW.landed_cost_id IS NULL)
    THEN
        RAISE EXCEPTION 'retail_receipt_holds: a hold can only be released, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_receipt_holds_guard_update BEFORE UPDATE ON retail_receipt_holds
    FOR EACH ROW EXECUTE FUNCTION retail_receipt_holds_guard_update();
CREATE TRIGGER retail_receipt_holds_reject_delete BEFORE DELETE ON retail_receipt_holds
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_supplier_credits and lines: a supplier credit note against a supplier invoice that stands (V47's bills). kind goods:
-- goods sent back to the supplier (a return_to_supplier movement per line, guarded so stock never goes below zero), credited
-- at the price agreed. kind price: a price adjustment with no goods moving. Numbered SC + branch code + six digits per
-- tenant and branch (the branch of the invoice). supplier_ref is the supplier's own credit note number, unique per supplier
-- among credit notes that stand. A credit note reduces what the invoice leaves to pay and never more than that (the
-- application enforces it under the supplier's lock). Only the void changes a row, once.
-- Per line: line_total_minor is the credit; to_inventory_minor is the part credited to inventory (goods: the carrying value
-- of the units sent back; price: the share capitalised for goods still on hand), to_cogs_minor the part that belongs to goods
-- already sold (price only), variance_minor the rest, posted to purchase price variance (goods: what the supplier credited above
-- or below what the units were carried at; price: the part that corrects the invoice line's own price variance).
-- line_total = inventory + cogs + variance.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_supplier_credits (
    id           uuid         NOT NULL PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenants (id),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz,
    version      integer      NOT NULL DEFAULT 1,
    credit_no    varchar(24)  NOT NULL,
    branch_seq   bigint       NOT NULL CHECK (branch_seq >= 1),
    branch_id    uuid         NOT NULL,
    supplier_id  uuid         NOT NULL,
    bill_id      uuid         NOT NULL,
    supplier_ref varchar(60)  NOT NULL CHECK (btrim(supplier_ref) <> ''),
    credit_date  date         NOT NULL,
    currency     char(3)      NOT NULL REFERENCES currencies (code),
    kind         text         NOT NULL CHECK (kind IN ('goods', 'price')),
    total_minor  bigint       NOT NULL CHECK (total_minor > 0 AND total_minor <= 10000000000000),
    reason       varchar(300) NOT NULL CHECK (char_length(btrim(reason)) >= 5),
    note         varchar(300),
    status       text         NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'voided')),
    created_by   uuid         NOT NULL,
    voided_at    timestamptz,
    voided_on    date,
    voided_by    uuid,
    void_reason  varchar(300),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, credit_no),
    UNIQUE (tenant_id, branch_id, branch_seq),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES retail_suppliers (tenant_id, id),
    FOREIGN KEY (tenant_id, bill_id) REFERENCES retail_supplier_bills (tenant_id, id),
    CHECK ((status = 'voided') = (voided_at IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_on IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_by IS NOT NULL)),
    CHECK ((status = 'voided') = (void_reason IS NOT NULL AND btrim(void_reason) <> ''))
);
CREATE UNIQUE INDEX retail_supplier_credits_ref ON retail_supplier_credits (tenant_id, supplier_id, lower(supplier_ref))
    WHERE status = 'posted';
CREATE INDEX retail_supplier_credits_bill ON retail_supplier_credits (tenant_id, bill_id) WHERE status = 'posted';
CREATE INDEX retail_supplier_credits_branch ON retail_supplier_credits (tenant_id, branch_id, created_at DESC, id);
CREATE INDEX retail_supplier_credits_supplier ON retail_supplier_credits (tenant_id, supplier_id, created_at DESC);
SELECT bms_apply_tenant_rls('retail_supplier_credits');
SELECT bms_grant_app('retail_supplier_credits', 'SELECT, INSERT');
GRANT UPDATE (updated_at, version, status, voided_at, voided_on, voided_by, void_reason)
    ON retail_supplier_credits TO bms_app;

CREATE FUNCTION retail_supplier_credits_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'posted' OR NEW.status <> 'voided'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.credit_no, NEW.branch_seq, NEW.branch_id, NEW.supplier_id,
           NEW.bill_id, NEW.supplier_ref, NEW.credit_date, NEW.currency, NEW.kind, NEW.total_minor, NEW.reason,
           NEW.note, NEW.created_by)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.credit_no, OLD.branch_seq, OLD.branch_id, OLD.supplier_id,
           OLD.bill_id, OLD.supplier_ref, OLD.credit_date, OLD.currency, OLD.kind, OLD.total_minor, OLD.reason,
           OLD.note, OLD.created_by)
    THEN
        RAISE EXCEPTION 'retail_supplier_credits: a credit note can only be voided, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE FUNCTION retail_supplier_credits_guard_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.status <> 'posted' OR NEW.voided_at IS NOT NULL OR NEW.version <> 1 THEN
        RAISE EXCEPTION 'retail_supplier_credits: a credit note is recorded posted and is voided afterwards'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_supplier_credits_guard_insert BEFORE INSERT ON retail_supplier_credits
    FOR EACH ROW EXECUTE FUNCTION retail_supplier_credits_guard_insert();
CREATE TRIGGER retail_supplier_credits_guard_update BEFORE UPDATE ON retail_supplier_credits
    FOR EACH ROW EXECUTE FUNCTION retail_supplier_credits_guard_update();
CREATE TRIGGER retail_supplier_credits_reject_delete BEFORE DELETE ON retail_supplier_credits
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE retail_supplier_credit_lines (
    id                 uuid          NOT NULL PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenants (id),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    credit_id          uuid          NOT NULL,
    line_no            smallint      NOT NULL CHECK (line_no >= 1),
    bill_line_id       uuid          NOT NULL,
    receipt_line_id    uuid          NOT NULL,
    branch_id          uuid          NOT NULL,
    product_id         uuid          NOT NULL,
    qty                numeric(14,3) CHECK (qty > 0),
    unit_qty           numeric(14,3) CHECK (unit_qty > 0),
    unit_credit_minor  bigint        CHECK (unit_credit_minor >= 0),
    line_total_minor   bigint        NOT NULL CHECK (line_total_minor > 0 AND line_total_minor <= 10000000000000),
    to_inventory_minor bigint        NOT NULL,
    to_cogs_minor      bigint        NOT NULL CHECK (to_cogs_minor >= 0),
    variance_minor     bigint        NOT NULL,
    on_hand_qty        numeric(14,3) CHECK (on_hand_qty >= 0),
    cost_before_minor  bigint        NOT NULL CHECK (cost_before_minor >= 0),
    cost_after_minor   bigint        NOT NULL CHECK (cost_after_minor >= 0),
    cost_applied       boolean       NOT NULL,
    cost_note          varchar(200),
    UNIQUE (tenant_id, id),
    UNIQUE (credit_id, line_no),
    UNIQUE (credit_id, bill_line_id),
    FOREIGN KEY (tenant_id, credit_id) REFERENCES retail_supplier_credits (tenant_id, id),
    FOREIGN KEY (tenant_id, bill_line_id) REFERENCES retail_supplier_bill_lines (tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_line_id) REFERENCES retail_goods_receipt_lines (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    CHECK (line_total_minor = to_inventory_minor + to_cogs_minor + variance_minor),
    CHECK ((qty IS NULL) = (unit_credit_minor IS NULL)),
    CHECK ((qty IS NULL) = (on_hand_qty IS NOT NULL))
);
CREATE INDEX retail_supplier_credit_lines_credit ON retail_supplier_credit_lines (tenant_id, credit_id);
CREATE INDEX retail_supplier_credit_lines_bill_line ON retail_supplier_credit_lines (tenant_id, bill_line_id);
CREATE INDEX retail_supplier_credit_lines_receipt_line ON retail_supplier_credit_lines (tenant_id, receipt_line_id);
SELECT bms_apply_tenant_rls('retail_supplier_credit_lines');
SELECT bms_grant_app('retail_supplier_credit_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_supplier_credit_lines');

-- The journal entries of a credit note, one per branch of the received lines it touches, kept so a void can reverse each.
CREATE TABLE retail_supplier_credit_entries (
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    credit_id  uuid        NOT NULL,
    branch_id  uuid        NOT NULL,
    entry_id   uuid        NOT NULL,
    PRIMARY KEY (tenant_id, credit_id, branch_id),
    FOREIGN KEY (tenant_id, credit_id) REFERENCES retail_supplier_credits (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES journal_entries (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_supplier_credit_entries');
SELECT bms_grant_app('retail_supplier_credit_entries', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_supplier_credit_entries');

-- ---------------------------------------------------------------------------------------------
-- Stock movement kind return_to_supplier (negative): goods sent back to a supplier against a credit note. It is
-- guarded like a sale in the application (a balance never goes below zero). Both checks of V22 are replaced by looser
-- ones, added NOT VALID; see the lock note above.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_stock_movements DROP CONSTRAINT retail_stock_movements_kind_check;
ALTER TABLE retail_stock_movements ADD CONSTRAINT retail_stock_movements_kind_check
    CHECK (kind IN ('opening', 'purchase', 'sale', 'usage', 'damage', 'adjustment', 'return', 'legacy_balance',
                    'transfer_out', 'transfer_in', 'return_to_supplier')) NOT VALID;
ALTER TABLE retail_stock_movements DROP CONSTRAINT retail_stock_movements_check;
ALTER TABLE retail_stock_movements ADD CONSTRAINT retail_stock_movements_check
    CHECK ((kind IN ('opening', 'purchase', 'return', 'transfer_in') AND qty > 0)
        OR (kind IN ('sale', 'usage', 'damage', 'transfer_out', 'return_to_supplier') AND qty < 0)
        OR kind IN ('adjustment', 'legacy_balance')) NOT VALID;
