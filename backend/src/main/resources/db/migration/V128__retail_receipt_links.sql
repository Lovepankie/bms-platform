-- V128: a short link to one sale's receipt, for a receipt sent to a typed WhatsApp number (issue #760, ADR-116).
-- Claimed after V124 to V127 (pull requests #318, #735, #743 and #736); renumbered at merge if that order changes.
--
-- A wa.me link cannot carry a file, so the message sent to a number that is not a saved contact carries a short link,
-- rincoltech.com/<token> (the short link host of V119, ADR-108), that opens the receipt PDF of that one sale: read only,
-- no sign-in, unguessable (26 random characters, 130 bits), for 90 days. A sale has one link at a time: it is made when
-- the seller first taps WhatsApp customer and given again while it is still valid. The link is found by the SHA-256 of
-- its token, and the token itself is kept only sealed with the application data key (DataKeys, chapter 8 section 8.7), so
-- the same link can be given again but the table alone cannot be turned back into links. The receipt is rendered when the
-- link is opened, as the seller's own receipt is.
--
-- Expand only, safe on a populated database: one new tenant table and two definer functions. No existing table, column or
-- constraint is touched and no permission is added (whoever may share a sale's receipt may make its link).
-- Times come from the caller (the kernel clock), never from now().

SET LOCAL lock_timeout = '5s';

CREATE TABLE retail_receipt_links (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    sale_id       uuid         NOT NULL,
    -- The hex SHA-256 of the token in the link. text, not char(n), so a lookup by a text value uses the unique index.
    token_hash    text         NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    -- The token sealed with the application data key, so the sale's link can be given again while it is valid.
    token_sealed  bytea        NOT NULL,
    -- Who made the link, whom the receipt it opens names as issuing it; like retail_sales.created_by, no foreign key.
    created_by    uuid         NOT NULL,
    created_at    timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    CHECK (expires_at > created_at),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id)
);
CREATE INDEX retail_receipt_links_by_sale ON retail_receipt_links (tenant_id, sale_id, expires_at);
SELECT bms_apply_tenant_rls('retail_receipt_links');
-- Never changed once written: a link lives until it expires, or until the business is removed (below).
SELECT bms_grant_app('retail_receipt_links', 'SELECT, INSERT');

-- Where a receipt link goes, read on the platform host where no business is bound: the slug of the active business whose
-- live link has this token hash, or nothing (a wrong or expired link). Says nothing else about the sale or the business.
-- It reads across businesses as its owner, bms_owner, which has BYPASSRLS (deploy/postgres/initdb/01-roles.sh), like the
-- definer functions of V102 and V119. row_security = off makes it raise instead of quietly finding no link should an owner
-- ever lack BYPASSRLS.
CREATE FUNCTION retail_receipt_link_shop(p_token_hash text, p_now timestamptz)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp SET row_security = off AS $$
    SELECT t.slug::text
      FROM retail_receipt_links l JOIN tenants t ON t.id = l.tenant_id
     WHERE l.token_hash = p_token_hash AND l.expires_at > p_now AND t.status = 'active'
$$;
REVOKE ALL ON FUNCTION retail_receipt_link_shop(text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION retail_receipt_link_shop(text, timestamptz) TO bms_app;

-- The removal of a closed business (ADR-090): its receipt links stop opening and are deleted, before the shop's eraser
-- (retail_erase_personal_data, V102) anonymises the sales they point at. The shop's PersonalDataEraser calls this first, in
-- the same transaction. The purge of V102 section 8 finds this table in the catalogue like any other and deletes its rows
-- before retail_sales, a table still referenced waiting for a later pass.
CREATE FUNCTION retail_erase_receipt_links() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := bms_removing_tenant();
    v_n      integer;
BEGIN
    DELETE FROM retail_receipt_links WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    RETURN v_n;
END
$$;
REVOKE ALL ON FUNCTION retail_erase_receipt_links() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION retail_erase_receipt_links() TO bms_app;
