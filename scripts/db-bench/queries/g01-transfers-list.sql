-- TransferRepository.page, newest first
-- tenant: tenant
SELECT id, from_branch_id, to_branch_id, transfer_date, note, status, currency, cost_total_minor,
       out_entry_id, in_entry_id, created_at, created_by, voided_at, voided_by, void_reason
  FROM retail_transfers t WHERE true ORDER BY created_at DESC, id DESC LIMIT 51
