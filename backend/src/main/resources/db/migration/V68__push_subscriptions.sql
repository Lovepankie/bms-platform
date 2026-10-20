-- V68: Web Push (ADR-050): installable app notifications. Numbered V68: V67 is taken by #361. Flyway runs with outOfOrder off, so a
-- number below one already applied would never run.
--
-- Expand only, safe on a populated database. One new table, push_subscriptions (one row per user per device: the push
-- service's address for that browser, and the two public values a message is encrypted with), and the outbox's channel CHECK
-- widened to accept 'push'. No existing row changes and no tenant is switched on: with no VAPID keys in the environment the
-- channel is off and nothing is queued (docs/runbooks/push-notifications.md).
--
-- Locks, said plainly. Flyway runs this file in one transaction. CREATE TABLE with foreign keys takes SHARE ROW EXCLUSIVE on
-- tenants and users until the commit (reads go on; the new table is empty, so the cost is the wait for the lock). The channel
-- CHECK is replaced with a ten line statement pair on notification_outbox, which holds an ACCESS EXCLUSIVE lock to the commit
-- while the new CHECK scans the table once; the outbox is purged nightly, so it is small. lock_timeout is set to five seconds
-- as in V26, V48, V49 and V59: if a lock cannot be had at once the migration fails and is retried, rather than every writer
-- queueing behind it.

SET LOCAL lock_timeout = '5s';

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_channel_check;
ALTER TABLE notification_outbox
    ADD CONSTRAINT notification_outbox_channel_check CHECK (channel IN ('email', 'telegram', 'sms', 'push'));

-- ---------------------------------------------------------------------------------------------
-- push_subscriptions (ADR-050). A subscription belongs to one user on one device (one browser profile). The endpoint is the
-- push service's address for that browser and is unique within the tenant: when a second person signs in on the same device
-- and turns notifications on, the row moves to them (the endpoint is the device, and the previous person must stop hearing
-- what is meant for the next one). endpoint_hash is the SHA-256 of the endpoint in hex, which is what the unique index and
-- the upsert use. p256dh and auth are the subscription's public key (65 bytes) and secret (16 bytes) in unpadded base64url:
-- they let this server encrypt a message only that browser can read, and they are never returned by the API after they are
-- stored, never logged and never audited. user_agent is a short description of the device kept so a person can tell their
-- devices apart ("Chrome on Android"); it is cut to 300 characters by the service.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE push_subscriptions (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    user_id       uuid         NOT NULL,
    endpoint      text         NOT NULL CHECK (length(endpoint) <= 2048 AND endpoint LIKE 'https://%'),
    endpoint_hash char(64)     NOT NULL CHECK (endpoint_hash ~ '^[0-9a-f]{64}$'),
    p256dh        varchar(100) NOT NULL CHECK (p256dh ~ '^[A-Za-z0-9_-]{86,88}$'),
    auth          varchar(30)  NOT NULL CHECK (auth ~ '^[A-Za-z0-9_-]{20,24}$'),
    user_agent    varchar(300),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_used_at  timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, endpoint_hash),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
CREATE INDEX push_subscriptions_by_user ON push_subscriptions (tenant_id, user_id);
SELECT bms_apply_tenant_rls('push_subscriptions');
SELECT bms_grant_app('push_subscriptions', 'SELECT, INSERT, UPDATE, DELETE');
