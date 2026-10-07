-- ApprovalService.expireOverdue (nightly job)
-- tenant: lending_tenant
SELECT r.id FROM approval_requests r LEFT JOIN users u ON u.id = r.requested_by
 WHERE r.status = 'pending' AND r.expires_at <= now() FOR UPDATE OF r
