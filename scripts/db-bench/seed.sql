-- Synthetic data for the database benchmark (issue #107). Throwaway databases only: run.sh refuses
-- to start unless it created the container itself. Every value is random or invented; nothing here
-- comes from a client.
--
-- Shape, with :scale = 25 (25 times the current staging size of one retail tenant):
--   50 tenants: 1 large retail tenant (40 percent of the retail rows), 9 medium and 15 small retail
--   tenants, 5 medium and 20 small lending tenants, so row-level security and tenant skew both
--   show in the plans.
--   retail: 20000 * scale sales (500,000), 1 to 5 lines each (about 1.5 million), one stock
--   movement per sale line as the application writes them, purchases, usage, transfers,
--   stock-takes, balances equal to the sum of the movements, journals for the live (non-imported)
--   sales and the purchases.
--   lending: 4000 * scale members, 1.5 loans per member with status history, guarantors,
--   appraisals and collateral.
--   core: users, role assignments, rotated sessions, audit rows, idempotency keys, approvals.
--
-- Runs as the container superuser with session_replication_role = replica, so foreign key,
-- append-only and journal balance triggers do not fire during the load. The data is generated
-- consistent (balanced journals, balances equal to movements) so the queries see what a real
-- database holds.

\set ON_ERROR_STOP on
\if :{?scale}
\else
\set scale 25
\endif

SET session_replication_role = replica;
SET synchronous_commit = off;
SET maintenance_work_mem = '256MB';
SET work_mem = '64MB';
SELECT setseed(0.107);

-- ---------------------------------------------------------------------------------------------
-- Tenants and branches
-- ---------------------------------------------------------------------------------------------

CREATE TEMP TABLE bt AS
SELECT n,
       ('00000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid AS id,
       CASE WHEN n <= 25 THEN 'retail' ELSE 'lending' END AS kind,
       CASE WHEN n = 1 THEN 0.40 WHEN n <= 10 THEN 0.04 WHEN n <= 25 THEN 0.016
            WHEN n <= 30 THEN 0.12 ELSE 0.02 END::numeric AS weight,
       CASE WHEN n = 1 THEN 4 WHEN n <= 10 THEN 2 WHEN n <= 25 THEN 1 WHEN n <= 30 THEN 3 ELSE 1 END AS nbranches,
       CASE WHEN n = 1 THEN 3000 WHEN n <= 10 THEN 800 ELSE 200 END AS nproducts,
       NULL::uuid[] AS branches,
       NULL::uuid[] AS products,
       NULL::uuid[] AS users
  FROM generate_series(1, 50) n;

SELECT count(*) AS tenants FROM (
    SELECT platform_create_tenant(id, 'bench-' || n, 'Bench Tenant ' || n, 'starter', 'UGX', 'Africa/Kampala',
                                  ARRAY[kind], gen_random_uuid(), 'B01', 'Branch 01', NULL)
      FROM bt ORDER BY n) created;

INSERT INTO branches (id, tenant_id, code, name)
SELECT gen_random_uuid(), bt.id, 'B' || lpad(b::text, 2, '0'), 'Branch ' || b
  FROM bt, generate_series(2, 4) b
 WHERE b <= bt.nbranches;

UPDATE bt SET branches = (SELECT array_agg(id ORDER BY code) FROM branches WHERE tenant_id = bt.id);

-- ---------------------------------------------------------------------------------------------
-- Users, credentials, roles, sessions
-- ---------------------------------------------------------------------------------------------

INSERT INTO users (id, tenant_id, created_at, kind, full_name, email, status, mfa_enabled, last_login_at)
SELECT gen_random_uuid(), bt.id, now() - interval '400 days', 'staff', 'Bench Staff ' || u,
       'staff' || u || '@' || 'bench-' || bt.n || '.test', 'active', u = 1, now() - interval '1 day'
  FROM bt, generate_series(1, CASE WHEN bt.kind = 'lending' THEN 15 ELSE 8 END) u;

UPDATE bt SET users = (SELECT array_agg(id ORDER BY full_name) FROM users WHERE tenant_id = bt.id);

INSERT INTO user_credentials (user_id, tenant_id, password_hash, password_changed_at)
SELECT id, tenant_id, '$argon2id$bench$not-a-real-hash', now() - interval '300 days' FROM users;

INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)
SELECT gen_random_uuid(), u.tenant_id, u.id,
       CASE WHEN u.full_name = 'Bench Staff 1' THEN 'tenant_admin'
            WHEN bt.kind = 'retail' THEN 'retail_sales' ELSE 'loan_officer' END,
       CASE WHEN u.full_name = 'Bench Staff 1' THEN NULL
            ELSE bt.branches[1 + (abs(hashtext(u.id::text)) % bt.nbranches)] END,
       u.id
  FROM users u JOIN bt ON bt.id = u.tenant_id;

-- Refresh rotation inserts a row per refresh: weeks of use leave many rotated or revoked rows.
INSERT INTO auth_sessions (id, tenant_id, created_at, user_id, family_id, refresh_token_hash, expires_at,
                           idle_expires_at, rotated_at, revoked_at, revoked_reason)
SELECT gen_random_uuid(), bt.id, ts, bt.users[1 + (s % array_length(bt.users, 1))], gen_random_uuid(),
       encode(sha256(convert_to(bt.id::text || s, 'UTF8')), 'hex'), ts + interval '30 days', ts + interval '12 hours',
       CASE WHEN s % 10 <> 0 THEN ts + interval '15 minutes' END,
       CASE WHEN s % 10 = 0 AND ts < now() - interval '1 day' THEN ts + interval '8 hours' END,
       CASE WHEN s % 10 = 0 AND ts < now() - interval '1 day' THEN 'sign_out' END
  FROM bt, generate_series(1, (bt.weight * 160 * :scale)::int) s,
       LATERAL (SELECT now() - (random() * interval '365 days') + 0 * s * interval '1 day' AS ts) t;

-- ---------------------------------------------------------------------------------------------
-- Retail catalogue
-- ---------------------------------------------------------------------------------------------

INSERT INTO retail_categories (id, tenant_id, name)
SELECT gen_random_uuid(), bt.id, 'Category ' || c FROM bt, generate_series(1, 20) c WHERE bt.kind = 'retail';
INSERT INTO retail_units (id, tenant_id, name)
SELECT gen_random_uuid(), bt.id, u FROM bt, unnest(ARRAY['pcs', 'kg', 'litre', 'box', 'metre']) u
 WHERE bt.kind = 'retail';

-- Descriptions are built from a small invented word list so trigram searches find realistic matches.
CREATE TEMP TABLE words AS
SELECT ARRAY['steel', 'cement', 'paint', 'nail', 'pipe', 'wire', 'bolt', 'sheet', 'tile', 'brush', 'tap', 'valve',
             'hinge', 'lock', 'glue', 'sand', 'timber', 'board', 'roller', 'bucket', 'hose', 'cable', 'switch',
             'socket', 'bulb', 'tape', 'screw', 'washer', 'chain', 'rope'] AS w;

INSERT INTO retail_products (id, tenant_id, created_at, code, description, category_id, unit_id, cost_minor,
                             sell_minor, currency, active)
SELECT gen_random_uuid(), bt.id, now() - interval '3 years',
       'P' || lpad(p::text, 5, '0'),
       initcap(w[1 + (abs(hashtext(bt.id::text || p || 'a')) % 30)]) || ' '
           || w[1 + (abs(hashtext(bt.id::text || p || 'b')) % 30)] || ' '
           || (1 + p % 50) || ' size ' || (p % 7),
       (SELECT id FROM retail_categories c WHERE c.tenant_id = bt.id ORDER BY c.name OFFSET p % 20 LIMIT 1),
       (SELECT id FROM retail_units x WHERE x.tenant_id = bt.id ORDER BY x.name OFFSET p % 5 LIMIT 1),
       cost, cost + cost / 4, 'UGX', p % 25 <> 0
  FROM bt, words, generate_series(1, bt.nproducts) p,
       LATERAL (SELECT (100 + (abs(hashtext(bt.id::text || p)) % 9900)) * 10 AS cost) c
 WHERE bt.kind = 'retail';

UPDATE bt SET products = (SELECT array_agg(id ORDER BY code) FROM retail_products WHERE tenant_id = bt.id)
 WHERE kind = 'retail';

INSERT INTO retail_price_history (id, tenant_id, created_at, product_id, source, new_cost_minor, new_sell_minor,
                                  old_cost_minor, old_sell_minor, currency)
SELECT gen_random_uuid(), p.tenant_id, p.created_at + h * interval '200 days', p.id,
       CASE WHEN h = 0 THEN 'initial' ELSE 'purchase' END, p.cost_minor, p.sell_minor,
       CASE WHEN h = 0 THEN NULL ELSE p.cost_minor END, CASE WHEN h = 0 THEN NULL ELSE p.sell_minor END, 'UGX'
  FROM retail_products p, generate_series(0, 2) h;

INSERT INTO retail_customers (id, tenant_id, created_at, name, contact)
SELECT gen_random_uuid(), bt.id, now() - random() * interval '3 years', 'Bench Buyer ' || c, NULL
  FROM bt, generate_series(1, CASE WHEN bt.n = 1 THEN 2000 ELSE 200 END) c
 WHERE bt.kind = 'retail';

-- ---------------------------------------------------------------------------------------------
-- Retail sales, lines and their stock movements
-- ---------------------------------------------------------------------------------------------

CREATE TEMP TABLE bcust AS
SELECT tenant_id, row_number() OVER (PARTITION BY tenant_id ORDER BY name) AS rn, id FROM retail_customers;

CREATE TEMP TABLE bsale AS
SELECT bt.id AS tenant_id, bt.n, s.i, cnt,
       gen_random_uuid() AS id,
       bt.branches[1 + floor(random() * bt.nbranches)::int] AS branch_id,
       timestamptz '2023-10-01 06:00+03' + (s.i::float8 / cnt) * interval '1095 days'
           + random() * interval '10 hours' AS created_at,
       s.i <= cnt * 0.8 AS historical,
       CASE WHEN r < 0.70 THEN 'cash' WHEN r < 0.85 THEN 'mobile_money' WHEN r < 0.90 THEN 'bank' ELSE 'credit' END
           AS payment_method,
       random() < 0.02 AS voided,
       1 + floor(random() * 5)::int AS nlines
  FROM bt, LATERAL (SELECT (bt.weight * 20000 * :scale)::int AS cnt) c,
       generate_series(1, cnt) s(i), LATERAL (SELECT random() + 0 * s.i AS r) rr
 WHERE bt.kind = 'retail';

CREATE TEMP TABLE bline AS
SELECT s.tenant_id, s.id AS sale_id, l AS line_no, gen_random_uuid() AS id,
       p.id AS product_id, q.qty, p.sell_minor AS price, p.cost_minor AS cost,
       (q.qty * p.sell_minor)::bigint AS line_total, (q.qty * p.cost_minor)::bigint AS line_cost
  FROM bsale s
  JOIN bt ON bt.id = s.tenant_id,
       generate_series(1, s.nlines) l,
       LATERAL (SELECT bt.products[1 + floor(random() * array_length(bt.products, 1))::int + 0 * l] AS pid) pick
  JOIN retail_products p ON p.id = pick.pid,
       LATERAL (SELECT (1 + floor(random() * 10))::numeric(14, 3) + 0 * l AS qty) q;

INSERT INTO retail_sales (id, tenant_id, created_at, updated_at, version, branch_id, sale_no, sale_date,
                          payment_method, customer_id, buyer_name, due_date, currency, total_minor, cost_total_minor,
                          paid_minor, status, historical, voided_at, voided_by, void_reason, created_by)
SELECT s.id, s.tenant_id, s.created_at, CASE WHEN s.voided THEN s.created_at + interval '1 hour' END,
       CASE WHEN s.voided THEN 2 ELSE 1 END, s.branch_id,
       'RS' || lpad(s.i::text, 8, '0'), (s.created_at AT TIME ZONE 'Africa/Kampala')::date, s.payment_method,
       CASE WHEN s.payment_method = 'credit' THEN c.id END,
       NULL,
       CASE WHEN s.payment_method = 'credit' THEN (s.created_at AT TIME ZONE 'Africa/Kampala')::date + 30 END,
       'UGX', t.total, t.cost,
       CASE WHEN s.payment_method = 'credit' THEN CASE WHEN s.i % 3 = 0 THEN t.total ELSE t.total / 2 END
            ELSE t.total END,
       CASE WHEN s.voided THEN 'voided' ELSE 'completed' END, s.historical,
       CASE WHEN s.voided THEN s.created_at + interval '1 hour' END,
       CASE WHEN s.voided THEN (SELECT users[1] FROM bt WHERE bt.id = s.tenant_id) END,
       CASE WHEN s.voided THEN 'Bench void' END,
       (SELECT users[2] FROM bt WHERE bt.id = s.tenant_id)
  FROM bsale s
  JOIN (SELECT sale_id, sum(line_total) AS total, sum(line_cost) AS cost FROM bline GROUP BY sale_id) t
    ON t.sale_id = s.id
  LEFT JOIN bcust c ON c.tenant_id = s.tenant_id AND c.rn = 1 + s.i % CASE WHEN s.n = 1 THEN 2000 ELSE 200 END
 ORDER BY s.created_at;

INSERT INTO retail_sale_lines (id, tenant_id, created_at, sale_id, line_no, product_id, qty, unit_price_minor,
                               unit_cost_minor, line_total_minor, line_cost_minor)
SELECT l.id, l.tenant_id, s.created_at, l.sale_id, l.line_no, l.product_id, l.qty, l.price, l.cost, l.line_total,
       l.line_cost
  FROM bline l JOIN bsale s ON s.id = l.sale_id
 ORDER BY s.created_at, l.line_no;

-- Movements: one sale movement per line (JdbcStockLedger.record and recordHistorical), and a
-- reversing return for a voided sale.
CREATE TEMP TABLE bmove (
    id uuid, tenant_id uuid, created_at timestamptz, occurred_at timestamptz, business_date date, branch_id uuid,
    product_id uuid, kind text, qty numeric(14, 3), unit_cost_minor bigint, source_type text, source_id uuid,
    source_line_id uuid, historical boolean, transfer_id uuid);

INSERT INTO bmove
SELECT gen_random_uuid(), l.tenant_id, s.created_at, s.created_at, (s.created_at AT TIME ZONE 'Africa/Kampala')::date,
       s.branch_id, l.product_id, 'sale', -l.qty, l.cost, 'retail.sale', s.id, l.id, s.historical, NULL
  FROM bline l JOIN bsale s ON s.id = l.sale_id;
INSERT INTO bmove
SELECT gen_random_uuid(), l.tenant_id, s.created_at + interval '1 hour', s.created_at + interval '1 hour',
       (s.created_at AT TIME ZONE 'Africa/Kampala')::date, s.branch_id, l.product_id, 'return', l.qty, l.cost,
       'retail.sale', s.id, l.id, false, NULL
  FROM bline l JOIN bsale s ON s.id = l.sale_id
 WHERE s.voided;

-- Purchases: 2 percent of the sales count, three lines each, received at one branch.
CREATE TEMP TABLE bpurchase AS
SELECT bt.id AS tenant_id, i, gen_random_uuid() AS id,
       bt.branches[1 + floor(random() * bt.nbranches)::int] AS branch_id,
       timestamptz '2023-10-01 08:00+03' + (i::float8 / cnt) * interval '1095 days' AS at,
       i <= cnt * 0.8 AS historical
  FROM bt, LATERAL (SELECT greatest(1, (bt.weight * 400 * :scale)::int) AS cnt) c, generate_series(1, cnt) i
 WHERE bt.kind = 'retail';

INSERT INTO retail_purchases (id, tenant_id, created_at, purchase_no, purchased_on, payment_method, currency,
                              total_minor, historical, created_by)
SELECT p.id, p.tenant_id, p.at, 'RP' || lpad(p.i::text, 8, '0'), (p.at AT TIME ZONE 'Africa/Kampala')::date,
       'cash', 'UGX', 0, p.historical, bt.users[1]
  FROM bpurchase p JOIN bt ON bt.id = p.tenant_id ORDER BY p.at;

INSERT INTO retail_purchase_lines (id, tenant_id, created_at, purchase_id, line_no, product_id, cost_minor,
                                   sell_minor, qty_total, line_total_minor)
SELECT gen_random_uuid(), p.tenant_id, p.at, p.id, l, pr.id, pr.cost_minor, pr.sell_minor, 100, pr.cost_minor * 100
  FROM bpurchase p JOIN bt ON bt.id = p.tenant_id, generate_series(1, 3) l,
       LATERAL (SELECT bt.products[1 + floor(random() * array_length(bt.products, 1))::int + 0 * l] AS pid) pick
  JOIN retail_products pr ON pr.id = pick.pid;

UPDATE retail_purchases p SET total_minor = t.total
  FROM (SELECT purchase_id, sum(line_total_minor) AS total FROM retail_purchase_lines GROUP BY purchase_id) t
 WHERE t.purchase_id = p.id;

INSERT INTO bmove
SELECT gen_random_uuid(), l.tenant_id, p.at, p.at, (p.at AT TIME ZONE 'Africa/Kampala')::date, p.branch_id,
       l.product_id, 'purchase', l.qty_total, l.cost_minor, 'retail.purchase', p.id, l.id, p.historical, NULL
  FROM retail_purchase_lines l JOIN bpurchase p ON p.id = l.purchase_id;

-- Opening balances: one legacy_balance movement per branch and product.
INSERT INTO bmove
SELECT gen_random_uuid(), bt.id, timestamptz '2023-09-30 18:00+03', timestamptz '2023-09-30 18:00+03',
       date '2023-09-30', b, p, 'legacy_balance', 500, 0, 'retail.import', NULL, NULL, true, NULL
  FROM bt, unnest(bt.branches) b, unnest(bt.products) p
 WHERE bt.kind = 'retail';

-- Usage and damage: 0.5 percent of the sales count, two lines each.
CREATE TEMP TABLE busage AS
SELECT bt.id AS tenant_id, i, gen_random_uuid() AS id,
       bt.branches[1 + floor(random() * bt.nbranches)::int] AS branch_id,
       timestamptz '2023-10-01 12:00+03' + (i::float8 / cnt) * interval '1095 days' AS at
  FROM bt, LATERAL (SELECT greatest(1, (bt.weight * 100 * :scale)::int) AS cnt) c, generate_series(1, cnt) i
 WHERE bt.kind = 'retail';

INSERT INTO retail_usage_reports (id, tenant_id, created_at, branch_id, kind, reason, occurred_on, currency,
                                  cost_total_minor, created_by)
SELECT u.id, u.tenant_id, u.at, u.branch_id, CASE WHEN u.i % 3 = 0 THEN 'damaged' ELSE 'used' END, 'Bench usage',
       (u.at AT TIME ZONE 'Africa/Kampala')::date, 'UGX', 0, bt.users[2]
  FROM busage u JOIN bt ON bt.id = u.tenant_id ORDER BY u.at;

INSERT INTO retail_usage_lines (id, tenant_id, created_at, report_id, line_no, product_id, qty, unit_cost_minor,
                                line_cost_minor)
SELECT gen_random_uuid(), u.tenant_id, u.at, u.id, l, pr.id, 1, pr.cost_minor, pr.cost_minor
  FROM busage u JOIN bt ON bt.id = u.tenant_id, generate_series(1, 2) l,
       LATERAL (SELECT bt.products[1 + floor(random() * array_length(bt.products, 1))::int + 0 * l] AS pid) pick
  JOIN retail_products pr ON pr.id = pick.pid;

UPDATE retail_usage_reports r SET cost_total_minor = t.total
  FROM (SELECT report_id, sum(line_cost_minor) AS total FROM retail_usage_lines GROUP BY report_id) t
 WHERE t.report_id = r.id;

INSERT INTO bmove
SELECT gen_random_uuid(), l.tenant_id, u.at, u.at, (u.at AT TIME ZONE 'Africa/Kampala')::date, u.branch_id,
       l.product_id, CASE WHEN r.kind = 'damaged' THEN 'damage' ELSE 'usage' END, -l.qty, l.unit_cost_minor,
       'retail.usage', u.id, l.id, false, NULL
  FROM retail_usage_lines l JOIN busage u ON u.id = l.report_id JOIN retail_usage_reports r ON r.id = u.id;

-- Transfers between branches: tenants with two or more branches, 0.4 percent of the sales count.
CREATE TEMP TABLE btransfer AS
SELECT bt.id AS tenant_id, i, gen_random_uuid() AS id, bt.branches[1 + (i % bt.nbranches)] AS from_branch,
       bt.branches[1 + ((i + 1) % bt.nbranches)] AS to_branch,
       timestamptz '2025-01-01 10:00+03' + (i::float8 / cnt) * interval '600 days' AS at
  FROM bt, LATERAL (SELECT greatest(1, (bt.weight * 80 * :scale)::int) AS cnt) c, generate_series(1, cnt) i
 WHERE bt.kind = 'retail' AND bt.nbranches > 1;

INSERT INTO retail_transfers (id, tenant_id, created_at, from_branch_id, to_branch_id, transfer_date, currency,
                              cost_total_minor, status, created_by)
SELECT t.id, t.tenant_id, t.at, t.from_branch, t.to_branch, (t.at AT TIME ZONE 'Africa/Kampala')::date, 'UGX', 0,
       'completed', bt.users[1]
  FROM btransfer t JOIN bt ON bt.id = t.tenant_id ORDER BY t.at;

INSERT INTO retail_transfer_lines (id, tenant_id, created_at, transfer_id, line_no, product_id, qty, unit_cost_minor,
                                   line_cost_minor)
SELECT gen_random_uuid(), t.tenant_id, t.at, t.id, l, bt.products[1 + ((t.i * 7 + l * 13) % array_length(bt.products, 1))],
       5, 1000, 5000
  FROM btransfer t JOIN bt ON bt.id = t.tenant_id, generate_series(1, 3) l;

INSERT INTO bmove
SELECT gen_random_uuid(), l.tenant_id, t.at, t.at, (t.at AT TIME ZONE 'Africa/Kampala')::date, b.branch, l.product_id,
       b.kind, b.sign * l.qty, l.unit_cost_minor, 'retail.transfer', t.id, l.id, false, t.id
  FROM retail_transfer_lines l JOIN btransfer t ON t.id = l.transfer_id,
       LATERAL (VALUES (t.from_branch, 'transfer_out', -1), (t.to_branch, 'transfer_in', 1)) b(branch, kind, sign);

INSERT INTO retail_stock_movements (id, tenant_id, created_at, occurred_at, business_date, branch_id, product_id,
                                    kind, qty, unit_cost_minor, source_type, source_id, source_line_id, historical,
                                    transfer_id)
SELECT * FROM bmove ORDER BY created_at;

INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id, qty, updated_at)
SELECT tenant_id, branch_id, product_id, sum(qty), max(created_at)
  FROM bmove GROUP BY tenant_id, branch_id, product_id;

-- Stock-takes: monthly per branch, 100 counted products each.
INSERT INTO retail_stocktakes (id, tenant_id, created_at, branch_id, status, created_by, committed_by, committed_at)
SELECT gen_random_uuid(), bt.id, ts, b, 'committed', bt.users[1], bt.users[1], ts + interval '2 hours'
  FROM bt, unnest(bt.branches) b, generate_series(0, 35) m,
       LATERAL (SELECT timestamptz '2023-10-28 17:00+03' + m * interval '1 month' AS ts) t
 WHERE bt.kind = 'retail';

INSERT INTO retail_stocktake_lines (id, tenant_id, created_at, stocktake_id, product_id, counted_qty, expected_qty,
                                    committed_variance_qty, unit_cost_minor)
SELECT gen_random_uuid(), s.tenant_id, s.created_at, s.id, p, 10, 10, 0, 1000
  FROM retail_stocktakes s JOIN bt ON bt.id = s.tenant_id,
       LATERAL (SELECT DISTINCT bt.products[1 + ((abs(hashtext(s.id::text)) + k * 31) % array_length(bt.products, 1))] AS p
                  FROM generate_series(1, 100) k) pk;

-- ---------------------------------------------------------------------------------------------
-- Ledger: periods, and balanced entries for the live sales and every purchase
-- ---------------------------------------------------------------------------------------------

INSERT INTO gl_periods (id, tenant_id, year, month, status)
SELECT gen_random_uuid(), bt.id, extract(year FROM m)::int, extract(month FROM m)::int,
       CASE WHEN m < date '2026-06-01' THEN 'closed' ELSE 'open' END
  FROM bt, generate_series(date '2023-09-01', date '2026-10-01', interval '1 month') m;

CREATE TEMP TABLE bacct AS
SELECT tenant_id, system_key, id FROM gl_accounts WHERE system_key IS NOT NULL;

CREATE TEMP TABLE bentry (
    id uuid, tenant_id uuid, created_at timestamptz, branch_id uuid, entry_date date, reference text, memo text,
    source_module text, source_type text, source_id uuid, idempotency_key text, created_by uuid,
    dr_key text, cr_key text, amount bigint, subledger_type text, subledger_id uuid);

INSERT INTO bentry
SELECT gen_random_uuid(), s.tenant_id, s.created_at, s.branch_id, (s.created_at AT TIME ZONE 'Africa/Kampala')::date,
       'RS' || lpad(s.i::text, 8, '0'), 'Sale', 'retail', 'retail.sale', s.id, 'retail.sale:' || s.id,
       NULL, CASE s.payment_method WHEN 'credit' THEN 'trade_debtors' WHEN 'bank' THEN 'bank'
                                   WHEN 'mobile_money' THEN 'mobile_money' ELSE 'cash_on_hand' END,
       'sales_revenue', x.total_minor,
       CASE WHEN s.payment_method = 'credit' THEN 'retail.sale' END, CASE WHEN s.payment_method = 'credit' THEN s.id END
  FROM bsale s JOIN retail_sales x ON x.id = s.id
 WHERE NOT s.historical AND x.total_minor > 0;
INSERT INTO bentry
SELECT gen_random_uuid(), s.tenant_id, s.created_at, s.branch_id, (s.created_at AT TIME ZONE 'Africa/Kampala')::date,
       'RS' || lpad(s.i::text, 8, '0'), 'Cost of sale', 'retail', 'retail.sale', s.id, 'retail.sale_cost:' || s.id,
       NULL, 'cost_of_goods_sold', 'inventory', x.cost_total_minor, NULL, NULL
  FROM bsale s JOIN retail_sales x ON x.id = s.id
 WHERE NOT s.historical AND x.cost_total_minor > 0;
INSERT INTO bentry
SELECT gen_random_uuid(), p.tenant_id, p.at, p.branch_id, (p.at AT TIME ZONE 'Africa/Kampala')::date,
       'RP' || lpad(p.i::text, 8, '0'), 'Restock', 'retail', 'retail.purchase', p.id, 'retail.purchase:' || p.id,
       NULL, 'inventory', 'cash_on_hand', x.total_minor, NULL, NULL
  FROM bpurchase p JOIN retail_purchases x ON x.id = p.id
 WHERE NOT p.historical;
-- Lending tenants: manual journals (capital, expenses) so ledger reads see volume there too.
INSERT INTO bentry
SELECT gen_random_uuid(), bt.id, ts, bt.branches[1 + (i % bt.nbranches)], (ts AT TIME ZONE 'Africa/Kampala')::date,
       'MJ' || i, 'Bench manual journal', 'core', 'core.manual', NULL, NULL, bt.users[1],
       CASE WHEN i % 2 = 0 THEN 'operating_expenses' ELSE 'cash_on_hand' END,
       CASE WHEN i % 2 = 0 THEN 'cash_on_hand' ELSE 'capital' END, 1000 * (1 + i % 50), NULL, NULL
  FROM bt, generate_series(1, (bt.weight * 2000 * :scale)::int) i,
       LATERAL (SELECT timestamptz '2023-10-01 09:00+03' + random() * interval '1090 days' + 0 * i * interval '1 day' AS ts) t
 WHERE bt.kind = 'lending';

CREATE TEMP TABLE bentry_no AS
SELECT id, 'JE' || lpad(row_number() OVER (PARTITION BY tenant_id ORDER BY created_at, id)::text, 8, '0') AS entry_no
  FROM bentry;

INSERT INTO journal_entries (id, tenant_id, created_at, branch_id, entry_no, entry_date, period_id, reference, memo,
                             source_module, source_type, source_id, idempotency_key, created_by)
SELECT e.id, e.tenant_id, e.created_at, e.branch_id, n.entry_no, e.entry_date, p.id, e.reference, e.memo,
       e.source_module, e.source_type, e.source_id, e.idempotency_key, e.created_by
  FROM bentry e JOIN bentry_no n ON n.id = e.id
  JOIN gl_periods p ON p.tenant_id = e.tenant_id AND p.year = extract(year FROM e.entry_date)
                   AND p.month = extract(month FROM e.entry_date)
 ORDER BY e.created_at;

INSERT INTO journal_lines (id, tenant_id, created_at, entry_id, line_no, account_id, debit, credit, currency,
                           subledger_type, subledger_id)
SELECT gen_random_uuid(), e.tenant_id, e.created_at, e.id, l.line_no, a.id,
       CASE WHEN l.line_no = 1 THEN e.amount ELSE 0 END, CASE WHEN l.line_no = 2 THEN e.amount ELSE 0 END, 'UGX',
       CASE WHEN l.line_no = 1 THEN e.subledger_type END, CASE WHEN l.line_no = 1 THEN e.subledger_id END
  FROM bentry e
  JOIN LATERAL (VALUES (1, e.dr_key), (2, e.cr_key)) l(line_no, key) ON true
  JOIN bacct a ON a.tenant_id = e.tenant_id AND a.system_key = l.key
 ORDER BY e.created_at, l.line_no;

UPDATE retail_sales s SET sale_entry_id = e.id
  FROM journal_entries e WHERE e.idempotency_key = 'retail.sale:' || s.id AND e.tenant_id = s.tenant_id;
UPDATE retail_sales s SET cost_entry_id = e.id
  FROM journal_entries e WHERE e.idempotency_key = 'retail.sale_cost:' || s.id AND e.tenant_id = s.tenant_id;

-- ---------------------------------------------------------------------------------------------
-- Lending
-- ---------------------------------------------------------------------------------------------

INSERT INTO lending_loan_products (id, tenant_id, code, name, status)
SELECT gen_random_uuid(), bt.id, 'LP' || k, 'Bench product ' || k, 'active'
  FROM bt, generate_series(1, 3) k WHERE bt.kind = 'lending';
INSERT INTO lending_loan_product_versions (id, tenant_id, product_id, version_no, currency, interest_method,
    interest_rate_bp, rate_unit, term_unit, min_term_count, max_term_count, default_term_count, repayment_pattern,
    instalment_frequency, min_principal_minor, max_principal_minor, created_by)
SELECT gen_random_uuid(), p.tenant_id, p.id, 1, 'UGX', 'flat', 1000, 'per_month', 'month', 1, 12, 6, 'instalments',
       'monthly', 10000, 100000000, bt.users[1]
  FROM lending_loan_products p JOIN bt ON bt.id = p.tenant_id;
UPDATE lending_loan_products p SET current_version_id = v.id
  FROM lending_loan_product_versions v WHERE v.product_id = p.id;

CREATE TEMP TABLE bmember AS
SELECT bt.id AS tenant_id, bt.n, i, gen_random_uuid() AS id, bt.branches[1 + (i % bt.nbranches)] AS branch_id,
       timestamptz '2023-10-01 09:00+03' + (i::float8 / cnt) * interval '1090 days' AS at
  FROM bt, LATERAL (SELECT (bt.weight * 4000 * :scale)::int AS cnt) c, generate_series(1, cnt) i
 WHERE bt.kind = 'lending';

INSERT INTO lending_members (id, tenant_id, created_at, branch_id, member_no, full_name, first_name, last_name,
    phone_e164, id_type, national_id, currency, kyc_status, status, source, created_by)
SELECT m.id, m.tenant_id, m.at, m.branch_id, 'M' || lpad(m.i::text, 6, '0'),
       'Test Borrower ' || w[1 + (m.i % 30)] || ' ' || w[1 + ((m.i / 30) % 30)] || ' ' || m.i,
       'Test', 'Borrower ' || m.i,
       '+2567' || lpad((m.n * 100000 + m.i)::text, 8, '0'), 'nin',
       'CMTEST' || lpad((m.n * 100000 + m.i)::text, 8, '0'),
       'UGX', CASE WHEN m.i % 5 = 0 THEN 'incomplete' ELSE 'verified' END,
       CASE WHEN m.i % 20 = 0 THEN 'exited' ELSE 'active' END, 'staff', bt.users[2]
  FROM bmember m JOIN bt ON bt.id = m.tenant_id, words ORDER BY m.at;

CREATE TEMP TABLE bloan AS
SELECT m.tenant_id, m.id AS member_id, m.branch_id, m.at + k * interval '120 days' AS at, k, m.i,
       gen_random_uuid() AS id, row_number() OVER (PARTITION BY m.tenant_id ORDER BY m.at, k) AS no
  FROM bmember m, generate_series(1, CASE WHEN m.i % 2 = 0 THEN 2 ELSE 1 END) k;

INSERT INTO lending_loans (id, tenant_id, created_at, branch_id, loan_no, member_id, product_version_id,
    officer_user_id, status, channel, purpose_category, currency, requested_principal_minor, requested_term_count,
    term_unit, interest_method, interest_rate_bp, rate_unit, repayment_pattern, instalment_frequency,
    principal_outstanding_minor, days_past_due, next_due_date, submitted_by, created_by)
SELECT l.id, l.tenant_id, l.at, l.branch_id, 'L' || lpad(l.no::text, 7, '0'), l.member_id,
       (SELECT v.id FROM lending_loan_product_versions v WHERE v.tenant_id = l.tenant_id ORDER BY v.id
         OFFSET (l.i % 3) LIMIT 1),
       bt.users[2 + (l.i % 13)],
       CASE WHEN l.at < now() - interval '400 days' THEN (ARRAY['closed', 'closed', 'closed', 'written_off', 'rejected'])[1 + l.i % 5]
            ELSE (ARRAY['active', 'active', 'active', 'submitted', 'appraised', 'approved', 'draft', 'closed'])[1 + l.i % 8] END,
       'staff', 'business', 'UGX', 100000 * (1 + l.i % 40), 6, 'month', 'flat', 1000, 'per_month', 'instalments',
       'monthly', 0, (l.i % 90), (l.at + interval '30 days')::date, bt.users[2], bt.users[2]
  FROM bloan l JOIN bt ON bt.id = l.tenant_id ORDER BY l.at;

INSERT INTO lending_loan_status_history (id, tenant_id, created_at, loan_id, from_status, to_status, changed_by)
SELECT gen_random_uuid(), l.tenant_id, l.created_at + h * interval '2 days', l.id,
       (ARRAY[NULL, 'draft', 'submitted'])[h + 1], (ARRAY['draft', 'submitted', 'appraised'])[h + 1], l.created_by
  FROM lending_loans l, generate_series(0, 2) h;

INSERT INTO lending_loan_appraisals (id, tenant_id, created_at, loan_id, appraised_by, score, band, components, flags,
                                     exposure, weights, recommendation)
SELECT gen_random_uuid(), l.tenant_id, l.created_at + interval '4 days', l.id, l.created_by, 70, 'B',
       '{"capacity": 30, "history": 25, "collateral": 15}', '{}', '{"loans": []}', '{"capacity": 40}', 'approve'
  FROM lending_loans l WHERE l.status <> 'draft';

INSERT INTO lending_loan_guarantors (id, tenant_id, loan_id, guarantor_member_id, guaranteed_amount_minor, status)
SELECT gen_random_uuid(), l.tenant_id, l.id, g.id, 50000, 'active'
  FROM bloan b JOIN lending_loans l ON l.id = b.id
  JOIN bmember g ON g.tenant_id = b.tenant_id AND g.i = greatest(1, b.i - 1) AND g.id <> b.member_id
 WHERE b.i % 2 = 0;

INSERT INTO lending_collateral_items (id, tenant_id, created_at, branch_id, member_id, collateral_type, description,
    reference_no, reference_no_normalised, currency, custody_status)
SELECT gen_random_uuid(), m.tenant_id, m.at, m.branch_id, m.id, 'household_item', 'Bench item ' || m.i, NULL, NULL,
       'UGX', 'pledged'
  FROM bmember m WHERE m.i % 5 = 0;

INSERT INTO approval_requests (id, tenant_id, created_at, branch_id, action_type, subject_type, subject_id, payload,
                               status, requested_by, requested_at, expires_at, decided_by, decided_at)
SELECT gen_random_uuid(), l.tenant_id, l.created_at, l.branch_id, 'loan_write_off', 'lending.loan', l.id, '{}',
       CASE WHEN l.created_at > now() - interval '20 days' THEN 'pending' ELSE 'approved' END, l.created_by,
       l.created_at, l.created_at + interval '7 days',
       CASE WHEN l.created_at > now() - interval '20 days' THEN NULL ELSE bt.users[1] END,
       CASE WHEN l.created_at > now() - interval '20 days' THEN NULL ELSE l.created_at + interval '1 day' END
  FROM lending_loans l JOIN bt ON bt.id = l.tenant_id
 WHERE l.status IN ('active', 'written_off') AND (hashtext(l.id::text) % 10) = 0
   AND l.created_by <> bt.users[1];

-- ---------------------------------------------------------------------------------------------
-- Audit, idempotency keys, sequences
-- ---------------------------------------------------------------------------------------------

INSERT INTO audit_log (id, tenant_id, created_at, actor_user_id, actor_kind, branch_id, action, entity_type, entity_id,
                       data)
SELECT gen_random_uuid(), s.tenant_id, s.created_at + k * interval '1 minute', bt.users[2], 'staff', s.branch_id,
       (ARRAY['retail.sale.created', 'retail.stock.viewed', 'core.session.refreshed'])[k], 'retail.sale', s.id,
       jsonb_build_object('after', jsonb_build_object('sale_no', 'RS' || s.i, 'lines', s.nlines))
  FROM bsale s JOIN bt ON bt.id = s.tenant_id, generate_series(1, 3) k
 ORDER BY 3;
INSERT INTO audit_log (id, tenant_id, created_at, actor_user_id, actor_kind, branch_id, action, entity_type, entity_id,
                       data)
SELECT gen_random_uuid(), l.tenant_id, l.created_at + k * interval '1 hour', l.created_by, 'staff', l.branch_id,
       (ARRAY['lending.loan.created', 'lending.loan.submitted', 'lending.loan.appraised', 'lending.loan.viewed'])[k],
       'lending.loan', l.id, '{"after": {"status": "submitted"}}'
  FROM lending_loans l, generate_series(1, 4) k
 ORDER BY 3;

INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status, response_status,
                              response_body, created_at, expires_at)
SELECT s.tenant_id, bt.users[2], 'bench-' || s.id, 'POST', '/api/v1/retail/sales', repeat('a', 64), 'completed', 201,
       '{"id": "x"}', s.created_at, s.created_at + interval '7 days'
  FROM bsale s JOIN bt ON bt.id = s.tenant_id
 WHERE s.created_at > timestamptz '2026-09-23';

INSERT INTO tenant_sequences (tenant_id, sequence_key, next_value)
SELECT tenant_id, 'retail_sale_no', max(i) + 1 FROM bsale GROUP BY tenant_id
UNION ALL
SELECT tenant_id, 'journal_no', count(*) + 1 FROM bentry GROUP BY tenant_id
ON CONFLICT (tenant_id, sequence_key) DO UPDATE SET next_value = excluded.next_value;

RESET session_replication_role;
