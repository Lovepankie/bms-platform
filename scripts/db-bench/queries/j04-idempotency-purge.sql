-- CoreJobs idempotency purge (nightly, per tenant)
-- tenant: tenant
DELETE FROM idempotency_keys WHERE expires_at < now()
