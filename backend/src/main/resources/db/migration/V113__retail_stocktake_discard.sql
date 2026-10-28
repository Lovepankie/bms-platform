-- V113: a stock count draft can be discarded (issue #656), directly after V112 (#626).
--
-- Sell makes a count of a new item when the person ticks it, then commits it. Should the commit be lost and the person then
-- clear the sale, leave the screen or switch branch, the draft stays open and blocks a correction of the item (409
-- stocktake_in_progress) and the closing of the branch. POST /retail/stocktakes/{id}/discard now ends such a draft: its status
-- becomes discarded, with who and when, and it never writes stock. A discarded count is kept, as a committed one is, and
-- neither can change again.
--
-- Expand only, safe on a populated database. ADD COLUMN with no default is a catalogue change in PostgreSQL 16, with no table
-- rewrite. The status check is replaced as in V38 (every row is draft or committed, so it cannot fail), then the old one is
-- dropped; ADD COLUMN already holds ACCESS EXCLUSIVE, so the NOT VALID split saves no lock, only a short scan of a small table.
-- The guard function is replaced in place; its triggers stay.
-- lock_timeout is five seconds as in V96 and V111, so the migration fails and is retried rather than queueing every count
-- behind the ACCESS EXCLUSIVE lock on retail_stocktakes. bms_app keeps its SELECT, INSERT and UPDATE and forced RLS.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_stocktakes ADD COLUMN discarded_by uuid;
ALTER TABLE retail_stocktakes ADD COLUMN discarded_at timestamptz;

ALTER TABLE retail_stocktakes
    ADD CONSTRAINT retail_stocktakes_status_chk CHECK (status IN ('draft', 'committed', 'discarded')) NOT VALID;
ALTER TABLE retail_stocktakes VALIDATE CONSTRAINT retail_stocktakes_status_chk;
ALTER TABLE retail_stocktakes DROP CONSTRAINT retail_stocktakes_status_check;

ALTER TABLE retail_stocktakes
    ADD CONSTRAINT retail_stocktakes_discarded_chk
        CHECK ((status = 'discarded') = (discarded_at IS NOT NULL AND discarded_by IS NOT NULL)) NOT VALID;
ALTER TABLE retail_stocktakes VALIDATE CONSTRAINT retail_stocktakes_discarded_chk;

-- As V14, and a discarded count is as final as a committed one.
CREATE OR REPLACE FUNCTION retail_stocktake_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_status text;
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME = 'retail_stocktakes' THEN
        v_status := OLD.status;
    ELSE
        SELECT status INTO v_status FROM retail_stocktakes WHERE tenant_id = OLD.tenant_id AND id = OLD.stocktake_id;
    END IF;
    IF v_status IN ('committed', 'discarded') THEN
        RAISE EXCEPTION '% of a % stock-take is not allowed', TG_TABLE_NAME, v_status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON COLUMN retail_stocktakes.discarded_at IS
    'When a draft count was discarded without writing stock (issue #656); set only with status discarded.';
COMMENT ON COLUMN retail_stocktakes.discarded_by IS
    'Who discarded the draft count (issue #656): the person who made it, or anyone who may count at the branch.';
