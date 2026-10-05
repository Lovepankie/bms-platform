-- V13: the retail price floor (issue #64; ADR-020 decisions 5 and 10). A sale line priced at or
-- below the product's cost is refused unless the caller holds retail.price.below_cost. The
-- permission is in the catalogue for custom roles only: no default role is granted it, not even
-- tenant_admin.
--
-- Additive only (chapter 6 section 6.9). The next Flyway version free on main and the open pull
-- requests.

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.price.below_cost', 'retail', 'Sales: price a line at or below cost', false);
