# Runbook: expense item names that differ only in spaces

**Applies to:** staging and production · **Decisions:** ADR-118 decision 2 · **Issue:** #810

## Why this exists

Since issue #810 an expense item's name is stored tidied: no spaces around it and one space between words, on the add in
place, in set-up and on the owner's rename. The checks compare names the same way, so "Generator  fuel" and "Generator fuel"
are one name. Names stored before that may still carry extra spaces or a tab. They keep working: the checks tidy the stored
name before they compare it. What is left is a look at the stored rows, to see whether any category holds two items whose
names differ only in spaces or capitals.

This runbook changes no data. It is a read-only look that the lead runs; nothing here is a migration.

## Look (read only)

On the host, as `bms_owner` (it reads every business). The output is a business's own list of expense items: read it on the
host and never paste it into a ticket, a chat or a pull request.

```sql
-- 1. Names that the tidy would change, and what each would become.
SELECT t.slug, i.category_id, i.id, i.name AS stored, btrim(regexp_replace(i.name, '[ \t\n\r\f\v]+', ' ', 'g')) AS tidied, i.active
  FROM retail_expense_items i
  JOIN tenants t ON t.id = i.tenant_id
 WHERE i.name <> btrim(regexp_replace(i.name, '[ \t\n\r\f\v]+', ' ', 'g'))
 ORDER BY t.slug, i.category_id, i.name;

-- 2. Items of one category whose names are the same once tidied: the tidy could not store both under one name.
SELECT t.slug, i.category_id, lower(btrim(regexp_replace(i.name, '[ \t\n\r\f\v]+', ' ', 'g'))) AS tidied,
       count(*) AS items, string_agg(i.id::text || CASE WHEN i.active THEN '' ELSE ' (off)' END, ', ' ORDER BY i.id) AS ids
  FROM retail_expense_items i
  JOIN tenants t ON t.id = i.tenant_id
 GROUP BY t.slug, i.category_id, lower(btrim(regexp_replace(i.name, '[ \t\n\r\f\v]+', ' ', 'g')))
HAVING count(*) > 1
 ORDER BY t.slug, i.category_id;
```

## What the answer means

- **Both queries empty:** nothing to do. Every stored name is already tidy.
- **Query 1 has rows, query 2 is empty:** the names would only lose their extra spaces. Each business's owner can do it
  in the app: Expense lists, the item, rename it to its own name written plainly (the rename is allowed, because the name is
  the item's own). Nothing else changes: past expenses keep the item name they were recorded with.
- **Query 2 has rows:** a category holds two items that are one name to the checks. An add in place picks the active one
  first. Ask that business's owner which item to keep, and have them switch the other one off in the app. Never delete an item
  and never merge their expenses by hand.

Any change to the data is decided by the lead after this look, in its own reviewed change; this runbook does not make it.
