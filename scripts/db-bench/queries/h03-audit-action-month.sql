-- AuditSearch.query, an action in a month
-- tenant: tenant
SELECT id, created_at, actor_user_id, actor_kind, branch_id, action, entity_type, entity_id, request_id,
       data::text AS data
  FROM audit_log WHERE true AND created_at >= '2026-09-01' AND created_at <= '2026-09-30' AND action = 'retail.sale.created'
 ORDER BY created_at DESC, id DESC LIMIT 51
