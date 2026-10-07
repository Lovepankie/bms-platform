-- JdbcLedgerAccounts.balanceByBranch, small tenant
-- tenant: small_tenant
SELECT e.branch_id, sum(l.debit - l.credit) AS balance
  FROM journal_lines l
  JOIN journal_entries e ON e.id = l.entry_id
  JOIN gl_accounts a ON a.id = l.account_id
 WHERE a.system_key = 'inventory' AND e.entry_date <= '2026-09-30'
 GROUP BY e.branch_id
