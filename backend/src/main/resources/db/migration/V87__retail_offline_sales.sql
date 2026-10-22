-- V87: retail sales made offline (issue #458, ADR-067). A sale carries the client sale id the device made for it, unique per
-- tenant, so a sale sent again after its Idempotency-Key expired (7 days) is still the same sale; the time it happened on the
-- device; and whether it was recorded offline. A sale recorded offline whose stock, item or price changed meanwhile is recorded
-- and flagged in retail_sale_clashes; a sale the server refused is reported by the device into retail_offline_failures, where a
-- manager has it sent again or discards it. One permission, retail.offline.resolve, for the administrators and the Supervisor.
--
-- Numbered V87: main is at V80, and V81 to V86 are claimed by the open lending (#270, #271, #272, #290) and payments (#315,
-- #318) pull requests. Flyway runs with outOfOrder off; renumbered at merge if needed.
--
-- Expand only, safe on a populated database. Four nullable or constant-default columns on retail_sales, a partial unique
-- index, a small trigger that keeps the four columns as written, two new tables and one permission. No existing row changes.
--
-- Locks, said plainly. Flyway runs this file in one transaction, so the locks are held to the commit. ADD COLUMN with a
-- constant default is a catalogue change in PostgreSQL 16 (no table rewrite) but takes ACCESS EXCLUSIVE on retail_sales, which
-- blocks selling until the commit. The unique index is built under that lock; every existing row has a null client_sale_id, so
-- it is partial and empty. The first statement sets lock_timeout to five seconds (as V70 and V77 do).

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- The sale's client sale id, when it happened on the device, and whether it was recorded offline.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_sales ADD COLUMN client_sale_id uuid;
ALTER TABLE retail_sales ADD COLUMN occurred_at timestamptz;
ALTER TABLE retail_sales ADD COLUMN recorded_offline boolean NOT NULL DEFAULT false;
-- The SHA-256 of the request without how it was delivered: a second send under the client sale id must carry the same sale.
ALTER TABLE retail_sales ADD COLUMN request_hash char(64);
ALTER TABLE retail_sales ADD CONSTRAINT retail_sales_offline_chk
    CHECK ((NOT recorded_offline OR (client_sale_id IS NOT NULL AND occurred_at IS NOT NULL))
      AND (request_hash IS NULL OR client_sale_id IS NOT NULL)) NOT VALID;
ALTER TABLE retail_sales VALIDATE CONSTRAINT retail_sales_offline_chk;
CREATE UNIQUE INDEX retail_sales_client_sale_id ON retail_sales (tenant_id, client_sale_id) WHERE client_sale_id IS NOT NULL;

-- The four columns are written once, with the sale. retail_sales_guard_update (V45) lists the columns that never change; this
-- trigger adds these four without rewriting that function.
CREATE FUNCTION retail_sales_offline_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.client_sale_id, NEW.occurred_at, NEW.recorded_offline, NEW.request_hash)
       IS DISTINCT FROM (OLD.client_sale_id, OLD.occurred_at, OLD.recorded_offline, OLD.request_hash) THEN
        RAISE EXCEPTION 'retail_sales: the client sale id, the time it happened, the offline flag and the request do not change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER retail_sales_offline_guard BEFORE UPDATE ON retail_sales
    FOR EACH ROW EXECUTE FUNCTION retail_sales_offline_guard();

-- ---------------------------------------------------------------------------------------------
-- A clash of a sale recorded offline: written with the sale, resolved once with a note. kind is stock_short (the sale took the
-- balance below zero or into stock kept for orders), item_inactive, price_changed, price_typed (a typed price the online price
-- rules would not take, ADR-067 decision 11), below_cost or clock_ahead (the device clock
-- was more than five minutes ahead; the sale is dated the server's now). words is the plain sentence shown on the sale page; it
-- never carries a cost. clock_behind: the sale said it happened before the server time at which the phone last saved its
-- prices, so its clock was behind; the sale is dated the server's now.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_sale_clashes (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    sale_id          uuid         NOT NULL,
    branch_id        uuid         NOT NULL,
    line_no          integer      CHECK (line_no > 0),
    product_id       uuid,
    kind             text         NOT NULL
                                  CHECK (kind IN ('stock_short', 'item_inactive', 'price_changed', 'price_typed', 'below_cost',
                                                  'clock_ahead', 'clock_behind')),
    words            varchar(400) NOT NULL CHECK (btrim(words) <> ''),
    resolved_at      timestamptz,
    resolved_by      uuid,
    resolution_note  varchar(300),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    CHECK ((kind IN ('clock_ahead', 'clock_behind')) = (line_no IS NULL)),
    CHECK ((line_no IS NULL) = (product_id IS NULL)),
    CHECK ((resolved_at IS NULL) = (resolved_by IS NULL)),
    CHECK ((resolved_at IS NULL) = (resolution_note IS NULL)),
    CHECK (resolution_note IS NULL OR length(btrim(resolution_note)) >= 5)
);
CREATE INDEX retail_sale_clashes_sale ON retail_sale_clashes (tenant_id, sale_id);
CREATE INDEX retail_sale_clashes_open ON retail_sale_clashes (tenant_id, branch_id, created_at) WHERE resolved_at IS NULL;
SELECT bms_apply_tenant_rls('retail_sale_clashes');
SELECT bms_grant_app('retail_sale_clashes', 'SELECT, INSERT');
GRANT UPDATE (resolved_at, resolved_by, resolution_note) ON retail_sale_clashes TO bms_app;

-- A clash is resolved once; nothing else changes.
CREATE FUNCTION retail_sale_clashes_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.resolved_at IS NOT NULL OR NEW.resolved_at IS NULL THEN
        RAISE EXCEPTION 'retail_sale_clashes: a clash is resolved once' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER retail_sale_clashes_guard_update BEFORE UPDATE ON retail_sale_clashes
    FOR EACH ROW EXECUTE FUNCTION retail_sale_clashes_guard_update();

-- ---------------------------------------------------------------------------------------------
-- A sale made offline that the server refused, reported by the device of the person who made it. The request is the sale
-- request exactly as the device holds it; code and message are the refusal. status: open (waiting for a manager), retry (a
-- manager asked for it to be sent again; the device sends it on its next sync), discarded (by a manager, with a reason;
-- final) or recorded (the sale was then recorded, by the client sale id; final).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_offline_failures (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    client_sale_id   uuid         NOT NULL,
    branch_id        uuid         NOT NULL,
    made_by          uuid         NOT NULL,
    occurred_at      timestamptz  NOT NULL,
    payment_method   text         NOT NULL CHECK (payment_method IN ('cash', 'mobile_money', 'bank', 'credit')),
    total_minor      bigint       NOT NULL CHECK (total_minor >= 0),
    request          jsonb        NOT NULL,
    code             varchar(60)  NOT NULL,
    message          varchar(500) NOT NULL,
    status           text         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'retry', 'discarded', 'recorded')),
    decided_by       uuid,
    decided_at       timestamptz,
    discard_reason   varchar(300),
    sale_id          uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, client_sale_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    CHECK ((status = 'recorded') = (sale_id IS NOT NULL)),
    CHECK ((status = 'discarded') = (discard_reason IS NOT NULL)),
    CHECK (discard_reason IS NULL OR length(btrim(discard_reason)) >= 5),
    CHECK (status = 'open' OR status = 'recorded' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE INDEX retail_offline_failures_open ON retail_offline_failures (tenant_id, branch_id, created_at)
    WHERE status IN ('open', 'retry');
SELECT bms_apply_tenant_rls('retail_offline_failures');
SELECT bms_grant_app('retail_offline_failures', 'SELECT, INSERT');
GRANT UPDATE (updated_at, code, message, status, decided_by, decided_at, discard_reason, sale_id)
    ON retail_offline_failures TO bms_app;

-- discarded and recorded are final; a refused retry goes back to open with the new refusal. The identity and the request
-- never change.
CREATE FUNCTION retail_offline_failures_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('discarded', 'recorded') THEN
        RAISE EXCEPTION 'retail_offline_failures: a % failure does not change', OLD.status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT ((OLD.status = 'open' AND NEW.status IN ('retry', 'discarded', 'recorded'))
            OR (OLD.status = 'retry' AND NEW.status IN ('open', 'discarded', 'recorded'))) THEN
        RAISE EXCEPTION 'retail_offline_failures: % to % is not a step', OLD.status, NEW.status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER retail_offline_failures_guard_update BEFORE UPDATE ON retail_offline_failures
    FOR EACH ROW EXECUTE FUNCTION retail_offline_failures_guard_update();

-- ---------------------------------------------------------------------------------------------
-- retail.offline.resolve: resolve a clash, have a refused offline sale sent again or discard it, at the branch. The
-- administrators hold every retail permission; the Supervisor is the manager of its branches (ADR-055). Not money moving: a
-- discard records a decision about a sale that was never recorded, and a retry sends the sale through the sale path.
-- PermissionMatrixIT compares these rows with the matrix in chapter 8 section 8.3.2.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.offline.resolve', 'retail', 'Sales: resolve clashes of offline sales and resend or discard a refused one', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.offline.resolve'),
    ('retail_supervisor', 'retail.offline.resolve')
ON CONFLICT DO NOTHING;
