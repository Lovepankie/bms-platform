-- AuditSearch.query, one entity
-- tenant: tenant
SELECT id, created_at, actor_user_id, actor_kind, branch_id, action, entity_type, entity_id, request_id,
       data::text AS data
  FROM audit_log WHERE true AND entity_type = 'retail.sale' AND entity_id = :'sale' ORDER BY created_at DESC, id DESC LIMIT 51
