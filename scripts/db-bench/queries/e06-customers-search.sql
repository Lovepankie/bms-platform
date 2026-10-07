-- SalesRepository.customers, search
-- tenant: tenant
SELECT id, name, contact, created_at FROM retail_customers
 WHERE name ILIKE '%Buyer 19%' ORDER BY lower(name), id LIMIT 51
