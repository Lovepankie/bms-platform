-- journal_entry_balance_check body (every journal line at commit)
-- tenant: tenant
SELECT count(*) FROM journal_lines WHERE entry_id = :'entry'
