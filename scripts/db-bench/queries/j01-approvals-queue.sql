-- ApprovalService.list, the inbox
-- tenant: lending_tenant
SELECT r.id, r.status FROM approval_requests r LEFT JOIN users u ON u.id = r.requested_by
 WHERE (r.requested_by = :'lending_user' OR (r.action_type = 'loan_write_off'))
   AND r.status IN ('pending') ORDER BY r.requested_at DESC, r.id DESC LIMIT 51
