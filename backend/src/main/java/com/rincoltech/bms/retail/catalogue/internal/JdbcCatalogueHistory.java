package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcCatalogueHistory implements CatalogueHistory {

    /** The created_by of imported catalogue rows: the import, not a staff account (as RetailHistory.IMPORT_ACTOR). */
    static final UUID IMPORT_ACTOR = new UUID(0L, 0L);

    private final JdbcClient jdbc;
    private final CatalogueRepository repo;
    private final CurrentTenant tenant;

    JdbcCatalogueHistory(JdbcClient jdbc, CatalogueRepository repo, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.tenant = tenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureCategory(String name) {
        UUID existing = idByName("retail_categories", name);
        if (existing != null) {
            return new Ensured(existing, false, false);
        }
        UUID id = UUID.randomUUID();
        repo.insertCategory(id, name, IMPORT_ACTOR);
        return new Ensured(id, true, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureUnit(String name) {
        UUID existing = idByName("retail_units", name);
        if (existing != null) {
            return new Ensured(existing, false, false);
        }
        UUID id = UUID.randomUUID();
        repo.insertUnit(id, name, IMPORT_ACTOR);
        return new Ensured(id, true, false);
    }

    private UUID idByName(String table, String name) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE lower(name) = lower(?)")
                .param(name)
                .query(UUID.class)
                .optional()
                .orElse(null);
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
                        description,
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
