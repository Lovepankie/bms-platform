package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory;
import com.rincoltech.bms.retail.catalogue.CatalogueNames;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcCatalogueHistory implements CatalogueHistory {

    /** The created_by of imported catalogue rows: the import, not a staff account (as RetailHistory.IMPORT_ACTOR). */
    static final UUID IMPORT_ACTOR = new UUID(0L, 0L);

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final CatalogueRepository repo;
    private final CurrentTenant tenant;

    JdbcCatalogueHistory(JdbcClient jdbc, DataSource dataSource, CatalogueRepository repo, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.batch = new JdbcTemplate(dataSource);
        this.repo = repo;
        this.tenant = tenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureCategory(String name) {
        // Issue #698: as categories are stored and compared, spaces inside counted once.
        UUID existing = repo.categoryIdsByName().get(CatalogueNames.nameKey(name));
        if (existing != null) {
            return new Ensured(existing, false, false);
        }
        UUID id = UUID.randomUUID();
        repo.insertCategory(id, name, null, IMPORT_ACTOR);
        return new Ensured(id, true, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUnits(java.util.Collection<String> names) {
        names.stream().map(CatalogueNames::unitKey).distinct().sorted().forEach(repo::lockUnitKey);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureUnit(String name) {
        // Issue #698 (ADR-109): "pcs" in a file is the shop's Piece, whatever spelling it has. It is read under the
        // key's lock, so a unit added on a screen at this moment is never made twice.
        repo.lockUnitKey(name);
        UUID existing = repo.unitIdsByName().get(CatalogueNames.unitKey(name));
        if (existing != null) {
            return new Ensured(existing, false, false);
        }
        UUID id = UUID.randomUUID();
        repo.insertUnit(id, name, IMPORT_ACTOR);
        return new Ensured(id, true, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureProduct(
            String code,
            String description,
            UUID categoryId,
            UUID unitId,
            long costMinor,
            long sellMinor,
            boolean active) {
        String normal = RetailCatalogue.normaliseCode(code)
                .orElseThrow(() -> new IllegalArgumentException("product code is not acceptable: " + code));
        String currency = tenant.profile().currency();
        UUID existing = jdbc.sql("SELECT id FROM retail_products WHERE lower(code) = lower(?)")
                .param(normal)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            // A re-run never changes an existing product's prices (issue #73): a price edited in the
            // app between two runs is kept, and no history row is written.
            CatalogueApi.Product current = repo.find(existing).orElseThrow();
            boolean differs = current.costMinor() != costMinor || current.sellMinor() != sellMinor;
            return new Ensured(existing, false, differs);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                     sell_minor, currency, active, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        normal,
                        CatalogueNames.display(description),
                        categoryId,
                        unitId,
                        costMinor,
                        sellMinor,
                        currency,
                        active,
                        IMPORT_ACTOR)
                .update();
        repo.insertHistory(
                new PriceChange(
                        UUID.randomUUID(),
                        null,
                        null,
                        "initial",
                        null,
                        null,
                        costMinor,
                        null,
                        sellMinor,
                        currency,
                        null),
                id);
        return new Ensured(id, true, false);
    }

    // JUSTIFICATION-A3: issue #880 replaces three round trips per imported item with two batches and one query.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createProducts(List<NewProduct> products) {
        if (products.isEmpty()) {
            return;
        }
        String currency = tenant.profile().currency();
        List<Object[]> rows = new ArrayList<>(products.size());
        List<Object[]> history = new ArrayList<>(products.size());
        for (NewProduct p : products) {
            String normal = RetailCatalogue.normaliseCode(p.code())
                    .orElseThrow(() -> new IllegalArgumentException("product code is not acceptable: " + p.code()));
            rows.add(new Object[] {
                p.id(),
                normal,
                CatalogueNames.display(p.description()),
                p.categoryId(),
                p.unitId(),
                p.costMinor(),
                p.sellMinor(),
                currency,
                p.active(),
                IMPORT_ACTOR
            });
            // The initial history row ensureProduct writes through repo.insertHistory, with the same columns.
            history.add(new Object[] {UUID.randomUUID(), p.id(), p.costMinor(), p.sellMinor(), currency});
        }
        batch.batchUpdate("""
                INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                             sell_minor, currency, active, created_by)
                VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
        batch.batchUpdate("""
                INSERT INTO retail_price_history (id, tenant_id, created_at, product_id, source, source_id,
                    old_cost_minor, new_cost_minor, old_sell_minor, new_sell_minor, currency, reason, changed_by)
                VALUES (?, current_setting('app.tenant_id')::uuid, clock_timestamp(), ?, 'initial', NULL, NULL, ?, NULL,
                    ?, ?, NULL, NULL)
                """, history);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, List<Product>> sameName(Collection<UUID> productIds) {
        Map<UUID, List<Product>> out = new HashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        // Each product by its id, then its twins by the name key (V63), each an index lookup of its own: OFFSET 0 keeps
        // the planner from joining them by a scan of every product, which the big file test measured (issue #880).
        // Never lower(code), which row-level security keeps from its index, nor id = ANY(?).
        jdbc.sql("""
                        SELECT n.id AS product, o.id, o.code, o.cost_minor, o.sell_minor
                          FROM unnest(CAST(? AS uuid[])) AS made(id)
                         CROSS JOIN LATERAL (SELECT p.id, p.name_key, p.unit_id FROM retail_products p
                                              WHERE p.id = made.id OFFSET 0) n
                         CROSS JOIN LATERAL (SELECT p.id, p.code, p.cost_minor, p.sell_minor FROM retail_products p
                                              WHERE p.name_key = n.name_key AND p.unit_id = n.unit_id AND p.id <> n.id
                                             OFFSET 0) o
                         ORDER BY o.code, o.id
                        """)
                .param(productIds.toArray(UUID[]::new))
                .query((rs, n) -> out.computeIfAbsent(rs.getObject("product", UUID.class), k -> new ArrayList<>())
                        .add(new Product(
                                rs.getObject("id", UUID.class),
                                rs.getString("code"),
                                rs.getLong("cost_minor"),
                                rs.getLong("sell_minor"))))
                .list();
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Product> productsByCode() {
        Map<String, Product> byCode = new LinkedHashMap<>();
        jdbc.sql("SELECT id, code, cost_minor, sell_minor FROM retail_products ORDER BY lower(code)")
                .query((rs, n) -> new Product(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getLong("cost_minor"),
                        rs.getLong("sell_minor")))
                .list()
                .forEach(p -> byCode.put(p.code().toLowerCase(Locale.ROOT), p));
        return byCode;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void importPriceChange(
            UUID productId,
            long oldCostMinor,
            long newCostMinor,
            long oldSellMinor,
            long newSellMinor,
            UUID purchaseId,
            Instant changedAt,
            String reason) {
        jdbc.sql("""
                        INSERT INTO retail_price_history (id, tenant_id, created_at, product_id, source, source_id,
                            old_cost_minor, new_cost_minor, old_sell_minor, new_sell_minor, currency, reason)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 'import', ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        Timestamp.from(changedAt),
                        productId,
                        purchaseId,
                        oldCostMinor,
                        newCostMinor,
                        oldSellMinor,
                        newSellMinor,
                        tenant.profile().currency(),
                        reason)
                .update();
    }
}
