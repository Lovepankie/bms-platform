-- JdbcRetailIdempotency claim (every money-moving request)
-- tenant: tenant
INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status)
VALUES (current_setting('app.tenant_id')::uuid, :'user', 'bench-new-key', 'POST', '/api/v1/retail/sales', repeat('b', 64), 'in_progress')
ON CONFLICT (tenant_id, principal_id, key) DO NOTHING
