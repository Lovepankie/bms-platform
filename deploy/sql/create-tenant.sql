-- Onboard one tenant (docs/runbooks/onboard-tenant.md). Runs as bms_owner through psql until the
-- platform console implements FR-TEN-01. Idempotent: an existing slug is reported and left alone.
--
--   psql -v slug=demo -v name='Demo Lender (fabricated)' -v plan=starter -v currency=UGX \
--        -v branch_code=HQ -v branch_name='Head Office' -v lending=true -f create-tenant.sql
--
-- Creates, in one transaction: the tenant, its head office branch, the lending module switch and
-- the default lending chart of accounts, plus an audit row. Staff users arrive with the identity
-- module; until then local development uses the AUTH_MODE=dev headers.
\set ON_ERROR_STOP on

SELECT EXISTS (SELECT 1 FROM tenants WHERE slug = :'slug') AS tenant_exists \gset
\if :tenant_exists
  \echo tenant :slug already exists, nothing changed
  \quit
\endif

BEGIN;

INSERT INTO tenants (id, slug, name, currency, plan_id)
SELECT gen_random_uuid(), :'slug', :'name', :'currency', id FROM plans WHERE code = :'plan'
RETURNING id AS tenant_id \gset

INSERT INTO branches (id, tenant_id, code, name, is_head_office)
VALUES (gen_random_uuid(), :'tenant_id', :'branch_code', :'branch_name', true)
RETURNING id AS branch_id \gset

\if :lending
  INSERT INTO tenant_modules (tenant_id, module_key) VALUES (:'tenant_id', 'lending');
  SELECT bms_seed_lending_chart(:'tenant_id') AS accounts_created;
\endif

INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
VALUES (gen_random_uuid(), :'tenant_id', 'platform', 'core.tenant.created', 'core.tenant', :'tenant_id',
        jsonb_build_object('after', jsonb_build_object('slug', :'slug', 'plan', :'plan', 'head_office_branch_id', :'branch_id')));

COMMIT;

\echo created tenant :slug tenant_id=:tenant_id head_office_branch_id=:branch_id
