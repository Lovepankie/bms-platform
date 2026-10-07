-- TenantSequences.next (sale and journal numbers)
-- tenant: tenant
UPDATE tenant_sequences SET next_value = next_value + 1 WHERE sequence_key = 'retail_sale_no' RETURNING next_value - 1
