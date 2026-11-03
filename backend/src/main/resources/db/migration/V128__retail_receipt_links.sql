-- V128: a short link to one sale's receipt, for a receipt sent to a typed WhatsApp number (issue #760, ADR-116).
-- Directly after V124 to V127 (pull requests #318, #735, #778 and #736).
--
-- A wa.me link cannot carry a file, so the message sent to a number that is not a saved contact carries a short link,
-- rincoltech.com/<token> (the short link host of V119, ADR-108), that opens the receipt PDF of that one sale: read only,
-- no sign-in, unguessable (26 random characters, 130 bits), for 90 days. The receipt is rendered when the link is opened,
-- as the seller's own receipt is.
--
-- One sale has one current link (review of #762 and the lead's note): a unique partial index on the links not yet
-- replaced, so the database itself refuses a second. Liveness for that index is revoked_at, never the clock: an index
-- predicate may not read the time. A link past its 90 days, or one whose sealed token can no longer be opened after a data
-- key rotation, is replaced: its row gets revoked_at in the transaction that inserts the new one. The token is kept
-- sealed with the application data key (DataKeys, AES-256-GCM) so it can be given again on every share, and found by the
-- SHA-256 of the token alone.
--
-- Expand only, safe on a populated database: one new tenant table, its guard trigger and one definer function. No existing
-- table, column or constraint is touched and no permission is added (whoever may share a sale's receipt may ask for its
-- link). Times come from the caller (the kernel clock), never from now().

SET LOCAL lock_timeout = '5s';

CREATE TABLE retail_receipt_links (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    sale_id       uuid         NOT NULL,
    -- The hex SHA-256 of the token in the link. text, as the lookups bind it, so the unique index serves them (a char(n)
    -- column compared with text is cast and can miss its index; review of #762). retail_receipt_link_shop below relies
    -- on this uniqueness to answer one row.
    token_hash    text         NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    -- The token sealed with the application data key, so a share can give the same link again.
    token_sealed  bytea        NOT NULL,
    -- Who made the link, whom the receipt it opens names as issuing it; like retail_sales.created_by, no foreign key.
    created_by    uuid         NOT NULL,
    created_at    timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    -- Set once, when a new link replaces this one; a link with it set never opens again.
    revoked_at    timestamptz,
    CHECK (expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id)
);
-- One current link per sale; also the index the share reads it by.
CREATE UNIQUE INDEX retail_receipt_links_one_current ON retail_receipt_links (tenant_id, sale_id) WHERE revoked_at IS NULL;
SELECT bms_apply_tenant_rls('retail_receipt_links');
SELECT bms_grant_app('retail_receipt_links', 'SELECT, INSERT');
-- revoked_at is the one column the application may update (as V38 and V124 grant one column).
GRANT UPDATE (revoked_at) ON retail_receipt_links TO bms_app;

-- And only once, from null to a time. The owner may change more only with bms.allow_mutation on, as reject_mutation (V1)
-- allows for restores and the tests.
CREATE FUNCTION retail_receipt_links_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
       OR (NEW.id, NEW.tenant_id, NEW.sale_id, NEW.token_hash, NEW.token_sealed, NEW.created_by, NEW.created_at,
           NEW.expires_at)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.sale_id, OLD.token_hash, OLD.token_sealed, OLD.created_by, OLD.created_at,
           OLD.expires_at) THEN
        RAISE EXCEPTION 'a receipt link only takes revoked_at, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_receipt_links_guard BEFORE UPDATE ON retail_receipt_links
    FOR EACH ROW EXECUTE FUNCTION retail_receipt_links_guard();
REVOKE ALL ON FUNCTION retail_receipt_links_guard() FROM PUBLIC;

-- Where a receipt link goes, read on the platform host where no business is bound: the slug of the active business whose
-- live link has this token hash, or nothing (a wrong, expired or replaced link). Says nothing else about the sale or the
-- business. token_hash is UNIQUE, so this is at most one row. It reads every business's rows with the forced row level
-- security of the table, so it relies on its owner bms_owner having BYPASSRLS (deploy/postgres/initdb/01-roles.sh), as
-- help_ask_platform_spend (V108) does.
CREATE FUNCTION retail_receipt_link_shop(p_token_hash text, p_now timestamptz)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.slug::text
      FROM retail_receipt_links l JOIN tenants t ON t.id = l.tenant_id
     WHERE l.token_hash = p_token_hash AND l.revoked_at IS NULL AND l.expires_at > p_now AND t.status = 'active'
$$;
REVOKE ALL ON FUNCTION retail_receipt_link_shop(text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION retail_receipt_link_shop(text, timestamptz) TO bms_app;
