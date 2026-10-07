package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageLine;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageLineRequest;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageReport;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageRequest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usage and damage reports (FR-RET-07, FR-RET-11): each line is valued at the product's cost now
 * (a snapshot on the line), stock moves out of the branch, and the total posts to stock shrinkage
 * against inventory, all in one transaction.
 */
@Service
class UsageService {

    static final String USAGE = "retail.usage";
    static final String PATH = "/api/v1/retail/usage";

    private final JdbcClient jdbc;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailBranchContext branches;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    UsageService(
            JdbcClient jdbc,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            Idempotency idempotency,
            RetailBranchContext branches,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.stock = stock;
        this.books = books;
        this.idempotency = idempotency;
        this.branches = branches;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    Outcome<UsageReport> report(String idempotencyKey, UsageRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH, r, UsageReport.class, () -> record(r));
    }

    private UsageReport record(UsageRequest r) {
        UUID branch = branches.resolve("retail.usage.report", r.branchId());
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate on = r.occurredOn() == null ? today : r.occurredOn();
        List<FieldProblem> problems = new ArrayList<>();
        if (on.isAfter(today)) {
            problems.add(new FieldProblem("occurred_on", "invalid", "Usage cannot be dated in the future."));
        }
        List<UsageLine> lines = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            UsageLineRequest l = r.lines().get(i);
            ProductSnapshot p = catalogue.find(l.productId()).orElse(null);
            if (p == null) {
                problems.add(new FieldProblem("lines[" + i + "].product_id", "unknown_product", "No such product."));
                continue;
            }
            if (!Quantities.exact(l.qty())) {
                problems.add(new FieldProblem("lines[" + i + "].qty", "invalid", "At most three decimal places."));
                continue;
            }
            lines.add(new UsageLine(
                    i + 1,
                    p.id(),
                    p.code(),
                    p.description(),
                    Quantities.format(l.qty()),
                    p.costMinor(),
                    Quantities.value(l.qty(), p.costMinor())));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        String currency = tenant.profile().currency();
        long total = lines.stream().mapToLong(UsageLine::lineCostMinor).reduce(0, Math::addExact);
        String movementKind = r.kind().equals("used") ? "usage" : "damage";
        String reason = r.reason().trim();
        List<Movement> movements = new ArrayList<>();
        List<UUID> lineIds = new ArrayList<>();
        for (UsageLine l : lines) {
            UUID lineId = UUID.randomUUID();
            lineIds.add(lineId);
            movements.add(new Movement(
                    branch,
                    l.productId(),
                    movementKind,
                    r.lines().get(l.lineNo() - 1).qty().negate(),
                    l.unitCostMinor(),
                    USAGE,
                    id,
                    lineId,
                    null,
                    reason));
        }
        stock.record(on, movements);
        UUID entry = books.post(new Posting(
                        branch,
                        on,
                        "Usage " + id.toString().substring(0, 8),
                        (r.kind().equals("used") ? "Stock used: " : "Stock damaged: ") + reason,
                        USAGE,
                        id,
                        "retail.usage:" + id,
                        List.of(Leg.debit("stock_shrinkage", total), Leg.credit("inventory", total))))
                .map(PostedEntry::entryId)
                .orElse(null);
        jdbc.sql("""
                        INSERT INTO retail_usage_reports (id, tenant_id, branch_id, kind, reason, occurred_on, currency,
                            cost_total_minor, journal_entry_id, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(id, branch, r.kind(), reason, Date.valueOf(on), currency, total, entry, by)
                .update();
        for (int i = 0; i < lines.size(); i++) {
            UsageLine l = lines.get(i);
            jdbc.sql("""
                            INSERT INTO retail_usage_lines (id, tenant_id, report_id, line_no, product_id, qty,
                                unit_cost_minor, line_cost_minor)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            lineIds.get(i),
                            id,
                            l.lineNo(),
                            l.productId(),
                            r.lines().get(l.lineNo() - 1).qty(),
                            l.unitCostMinor(),
                            l.lineCostMinor())
                    .update();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("kind", r.kind());
        after.put("reason", reason);
        after.put("lines", lines.size());
        audit.record(AuditLog.Entry.created("retail.usage.reported", USAGE, id, branch, after));
        UsageReport report = new UsageReport(id, branch, r.kind(), reason, on, currency, total, lines, clock.now(), by);
        return StockService.mayReadCost(branch) ? report : withoutCost(report);
    }

    static UsageReport withoutCost(UsageReport r) {
        return new UsageReport(
                r.id(),
                r.branchId(),
                r.kind(),
                r.reason(),
                r.occurredOn(),
                r.currency(),
                null,
                r.lines().stream()
                        .map(l -> new UsageLine(
                                l.lineNo(), l.productId(), l.code(), l.description(), l.qty(), null, null))
                        .toList(),
                r.createdAt(),
                r.createdBy());
    }
}
