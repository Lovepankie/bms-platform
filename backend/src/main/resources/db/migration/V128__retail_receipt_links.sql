-- V128: a short link to one sale's receipt, for a receipt sent to a typed WhatsApp number (issue #760, ADR-116).
-- Claimed after V124 to V127 (pull requests #318, #735, #743 and #736); renumbered at merge if that order changes.
--
-- A wa.me link cannot carry a file, so the message sent to a number that is not a saved contact carries a short link,
-- rincoltech.com/<token> (the short link host of V119, ADR-108), that opens the receipt PDF of that one sale: read only,
-- no sign-in, unguessable (26 random characters, 130 bits), for 90 days. Only the SHA-256 of the token is stored, so the
-- table cannot be turned back into links. The receipt is rendered when the link is opened, as the seller's own receipt is.
--
-- Expand only, safe on a populated database: one new tenant table and one definer function. No existing table, column or
-- constraint is touched and no permission is added (whoever may share a sale's receipt may make its link).
-- Times come from the caller (the kernel clock), never from now().

SET LOCAL lock_timeout = '5s';

CREATE TABLE retail_receipt_links (
    id          uuid         NOT NULL PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id),
    sale_id     uuid         NOT NULL,
    -- The hex SHA-256 of the token in the link.
    token_hash  char(64)     NOT NULL UNIQUE,
    created_by  uuid         NOT NULL,
    created_at  timestamptz  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    CHECK (expires_at > created_at),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id)
);
CREATE INDEX retail_receipt_links_by_sale ON retail_receipt_links (tenant_id, sale_id);
SELECT bms_apply_tenant_rls('retail_receipt_links');
-- Never changed once written: a link lives until it expires.
SELECT bms_grant_app('retail_receipt_links', 'SELECT, INSERT');

-- Where a receipt link goes, read on the platform host where no business is bound: the slug of the active business whose
-- live link has this token hash, or nothing (a wrong or expired link). Says nothing else about the sale or the business.
CREATE FUNCTION retail_receipt_link_shop(p_token_hash text, p_now timestamptz)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.slug::text
      FROM retail_receipt_links l JOIN tenants t ON t.id = l.tenant_id
     WHERE l.token_hash = p_token_hash AND l.expires_at > p_now AND t.status = 'active'
$$;
REVOKE ALL ON FUNCTION retail_receipt_link_shop(text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION retail_receipt_link_shop(text, timestamptz) TO bms_app;
