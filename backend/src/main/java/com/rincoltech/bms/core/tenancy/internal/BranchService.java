package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.tenancy.BranchClosingCheck;
import com.rincoltech.bms.core.tenancy.BranchClosingCheck.Finding;
import com.rincoltech.bms.core.tenancy.BranchDeactivationGuard;
import com.rincoltech.bms.core.tenancy.BranchProvisioning;
import com.rincoltech.bms.core.tenancy.BranchSellingCheck;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlanLimits;
import com.rincoltech.bms.core.tenancy.internal.BranchController.BranchResponse;
import com.rincoltech.bms.core.tenancy.internal.BranchController.CreateBranchRequest;
import com.rincoltech.bms.core.tenancy.internal.BranchController.UpdateBranchRequest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch administration (FR-BR-01). Reads are filtered by the caller's scope for
 * {@code core.branches.read}: a branch outside it is indistinguishable from a missing one
 * (NFR-ISO-04).
 */
@Service
class BranchService implements BranchProvisioning {

    private static final String COLUMNS =
            "id, code, name, location, is_head_office, status, stocks_goods, sells_goods, closed_at,"
                    + " closed_reason, logo_document_id, created_at, updated_at, version";
    private static final String READ = "core.branches.read";

    private final JdbcClient jdbc;
    private final PlanLimits planLimits;
    private final AuditLog audit;
    private final List<BranchDeactivationGuard> guards;
    private final List<BranchClosingCheck> checks;
    private final List<BranchSellingCheck> sellingChecks;
    private final CurrentTenant tenant;
    private final Documents documents;

    BranchService(
            JdbcClient jdbc,
            PlanLimits planLimits,
            AuditLog audit,
            List<BranchDeactivationGuard> guards,
            List<BranchClosingCheck> checks,
            List<BranchSellingCheck> sellingChecks,
            CurrentTenant tenant,
            Documents documents) {
        this.jdbc = jdbc;
        this.planLimits = planLimits;
        this.audit = audit;
        this.guards = List.copyOf(guards);
        this.checks = List.copyOf(checks);
        this.sellingChecks = List.copyOf(sellingChecks);
        this.tenant = tenant;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    List<BranchResponse> list() {
        Principal principal = CurrentPrincipal.require();
        return jdbc
                .sql("SELECT " + COLUMNS + " FROM branches ORDER BY is_head_office DESC, code")
                .query(BranchService::map)
                .list()
                .stream()
                .filter(b -> principal.may(READ, b.id()))
                .map(this::withHoldings)
                .toList();
    }

    /** A closed branch shows what is still on its shelves (ADR-041); an open one, or an empty one, shows nothing. */
    private BranchResponse withHoldings(BranchResponse b) {
        if ("active".equals(b.status())) {
            return b;
        }
        return findings(b.id()).stream()
                .filter(f -> f.kind().equals("stock"))
                .findFirst()
                .map(f -> new BranchResponse(
                        b.id(),
                        b.code(),
                        b.name(),
                        b.location(),
                        b.isHeadOffice(),
                        b.status(),
                        b.stocksGoods(),
                        b.sellsGoods(),
                        b.closedAt(),
                        b.closedReason(),
                        f.count(),
                        f.valueMinor(),
                        b.logoUrl(),
                        b.createdAt(),
                        b.updatedAt(),
                        b.version()))
                .orElse(b);
    }

    @Transactional(readOnly = true)
    BranchResponse get(UUID branchId) {
        Principal principal = CurrentPrincipal.require();
        return find(branchId).filter(b -> principal.may(READ, b.id())).orElseThrow(ApiException::notFound);
    }

    @Transactional
    BranchResponse create(CreateBranchRequest request) {
        long active = jdbc.sql("SELECT count(*) FROM branches WHERE status = 'active'")
                .query(Long.class)
                .single();
        planLimits.checkRoomFor(PlanLimits.MAX_BRANCHES, active);
        boolean codeTaken = jdbc.sql("SELECT count(*) FROM branches WHERE code = ?")
                        .param(request.code())
                        .query(Long.class)
                        .single()
                > 0;
        if (codeTaken) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "duplicate_branch_code", "Duplicate branch code", "The code is in use.");
        }
        UUID id = UUID.randomUUID();
        boolean sells = request.sellsGoods() == null || request.sellsGoods();
        jdbc.sql("""
                        INSERT INTO branches (id, tenant_id, code, name, location, sells_goods)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """)
                .params(id, request.code(), request.name().trim(), request.location(), sells)
                .update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", request.code());
        after.put("sells_goods", sells);
        after.put("name", request.name().trim());
        after.put("location", request.location());
        audit.record(AuditLog.Entry.created("core.branch.created", "core.branch", id, id, after));
        return find(id).orElseThrow();
    }

    @Override
    @Transactional
    public UUID create(String code, String name) {
        if (code == null || !code.matches("^[A-Z0-9]{2,10}$") || name == null || name.isBlank()) {
            throw ApiException.rule(
                    "invalid_branch", "A branch needs a code of 2 to 10 capital letters or digits and a name.");
        }
        return create(new CreateBranchRequest(code, name, null, null)).id();
    }

    @Transactional
    BranchResponse update(UUID branchId, String ifMatch, UpdateBranchRequest request) {
        int expected = Versions.fromIfMatch(ifMatch);
        Principal principal = CurrentPrincipal.require();
        BranchResponse seen = find(branchId)
                .filter(b -> principal.may("core.branches.manage", b.id()))
                .orElseThrow(ApiException::notFound);
        // Only a change of the stocking flag takes the explicit row lock that stock writes queue behind (ADR-041); a
        // rename or a
        // new location must not hold up a sale, so it relies on the version check of its own update.
        boolean flagChange = (request.stocksGoods() != null && request.stocksGoods() != seen.stocksGoods())
                || (request.sellsGoods() != null && request.sellsGoods() != seen.sellsGoods());
        BranchResponse before = flagChange ? lockForUpdate(branchId) : seen;
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = request.name() == null ? before.name() : request.name().trim();
        String location = request.location() == null ? before.location() : request.location();
        boolean stocks = request.stocksGoods() == null ? before.stocksGoods() : request.stocksGoods();
        boolean sells = request.sellsGoods() == null ? before.sellsGoods() : request.sellsGoods();
        if (!sells && !stocks) {
            throw ApiException.rule(
                    "store_must_stock",
                    "A Store holds stock, so it cannot also be a branch that keeps no goods. Make it a shop that sells,"
                            + " or let it keep goods.");
        }
        if (before.stocksGoods() && !stocks) {
            refuseWhileGoodsRemain(before);
        }
        if (before.sellsGoods() && !sells) {
            refuseWhileSalesOpen(before);
            refuseWhileOrdersOpen(before, "marked as a store that does not sell");
        }
        int changed = jdbc.sql("""
                        UPDATE branches SET name = ?, location = ?, stocks_goods = ?, sells_goods = ?, updated_at = now(),
                               version = version + 1 WHERE id = ? AND version = ?
                        """)
                .params(name, location, stocks, sells, branchId, before.version())
                .update();
        if (changed == 0) {
            throw Versions.conflict(find(branchId).map(BranchResponse::version).orElse(before.version()));
        }
        audit.record(new AuditLog.Entry(
                "core.branch.updated",
                "core.branch",
                branchId,
                branchId,
                values(before.name(), before.location(), before.stocksGoods(), before.sellsGoods()),
                values(name, location, stocks, sells)));
        return find(branchId).orElseThrow();
    }

    // JUSTIFICATION-A3: close and reopen replace deactivate and add the findings check, the reason and the plan limit
    // (ADR-041).
    /**
     * Closes a branch (ADR-041). Nothing is deleted or posted: the branch leaves pickers, defaults and forms, and its history,
     * closing stock and sales stay for reports. Stock still held, or a transfer still on its way, is refused unless the
     * administrator gives a reason; the audit row then carries the figures that were shown.
     */
    @Transactional
    BranchResponse close(UUID branchId, String ifMatch, String reason) {
        int expected = Versions.fromIfMatch(ifMatch);
        BranchResponse before = lockForUpdate(branchId);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        if (before.isHeadOffice()) {
            throw ApiException.rule(
                    "head_office_required",
                    "The head office cannot be closed. If it keeps no goods, mark it as not stocking instead.");
        }
        if (!"active".equals(before.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The branch is already closed.");
        }
        if (guards.stream().anyMatch(g -> g.hasOpenAccounts(branchId))) {
            throw ApiException.rule(
                    "branch_has_open_accounts", "The branch still has open loans, savings or investments.");
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        List<Finding> findings = findings(branchId);
        refuseWhileOrdersOpen(before, findings, "closed");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "inactive");
        if (!findings.isEmpty()) {
            Map<String, Object> facts = facts(findings);
            if (why == null) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "branch_close_needs_reason",
                        "Confirm closing this branch",
                        describe(before, findings)
                                + " Closing keeps all of it on record and posts nothing, but nobody can sell, restock"
                                + " or transfer there afterwards. To close it anyway, say why.",
                        List.of(),
                        facts);
            }
            after.put("reason", why);
            after.putAll(facts);
        }
        jdbc.sql("""
                        UPDATE branches SET status = 'inactive', closed_at = now(), closed_reason = ?, updated_at = now(),
                               version = version + 1 WHERE id = ?
                        """).params(why, branchId).update();
        audit.record(new AuditLog.Entry(
                "core.branch.closed", "core.branch", branchId, branchId, Map.of("status", "active"), after));
        return find(branchId).orElseThrow();
    }

    @Transactional
    BranchResponse reopen(UUID branchId, String ifMatch) {
        int expected = Versions.fromIfMatch(ifMatch);
        BranchResponse before = lockForUpdate(branchId);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        if ("active".equals(before.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The branch is already open.");
        }
        long active = jdbc.sql("SELECT count(*) FROM branches WHERE status = 'active'")
                .query(Long.class)
                .single();
        planLimits.checkRoomFor(PlanLimits.MAX_BRANCHES, active);
        jdbc.sql("""
                        UPDATE branches SET status = 'active', closed_at = NULL, closed_reason = NULL, updated_at = now(),
                               version = version + 1 WHERE id = ?
                        """).param(branchId).update();
        audit.record(new AuditLog.Entry(
                "core.branch.reopened",
                "core.branch",
                branchId,
                branchId,
                Map.of("status", "inactive"),
                Map.of("status", "active")));
        return find(branchId).orElseThrow();
    }

    private List<Finding> findings(UUID branchId) {
        return checks.stream().flatMap(c -> c.findings(branchId).stream()).toList();
    }

    /** Marking a branch as not stocking needs its shelves empty and nothing on the road: the stock would be stranded. */
    private void refuseWhileGoodsRemain(BranchResponse branch) {
        List<Finding> findings = findings(branch.id());
        if (findings.isEmpty()) {
            return;
        }
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "branch_still_has_stock",
                "This branch still has goods",
                describe(branch, findings)
                        + " Move the stock to a shop, receive or cancel the transfers and finish the stock-takes first,"
                        + " then mark it as not stocking.",
                List.of(),
                facts(findings));
    }

    /**
     * A branch with open customer orders, or stock kept aside for them, is not closed or made a Store: the orders are delivered
     * and sold there (issue #385, ADR-052). A module reports them as the finding {@code open_orders}; no reason overrides it. The
     * branch row is held FOR UPDATE by the close and the flag change, and taking or delivering an order or collecting its fee reads
     * it FOR SHARE, so no order slips in between the check and the change.
     */
    private void refuseWhileOrdersOpen(BranchResponse branch, String what) {
        refuseWhileOrdersOpen(branch, findings(branch.id()), what);
    }

    private void refuseWhileOrdersOpen(BranchResponse branch, List<Finding> findings, String what) {
        Finding orders = findings.stream()
                .filter(f -> f.kind().equals("open_orders"))
                .findFirst()
                .orElse(null);
        if (orders == null) {
            return;
        }
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "branch_has_open_orders",
                "This branch still has customer orders",
                branch.name() + " has " + orders.count()
                        + (orders.count() == 1 ? " customer order" : " customer orders")
                        + " not finished: to deliver, with the items kept aside, or with a delivery fee still owed on a"
                        + " buyer's account. Deliver, cancel or collect them before it is " + what + ".",
                List.of(),
                Map.of("open_orders", orders.count()));
    }

    /** Marking a branch as a Store needs no credit sale of it still owing: its payments are taken at the till that stops selling. */
    private void refuseWhileSalesOpen(BranchResponse branch) {
        int open = sellingChecks.stream()
                .mapToInt(c -> c.openCreditSales(branch.id()))
                .sum();
        if (open == 0) {
            return;
        }
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "branch_has_open_credit_sales",
                "This branch still has credit sales owing",
                branch.name() + " has " + open + (open == 1 ? " credit sale" : " credit sales")
                        + " that still owe money, and the payments are taken at this branch. Let the buyers pay, or settle"
                        + " them, before marking it as a store that does not sell.",
                List.of(),
                Map.of("open_credit_sales", open));
    }

    private String describe(BranchResponse branch, List<Finding> findings) {
        String currency = tenant.profile().currency();
        StringBuilder words = new StringBuilder(branch.name());
        boolean first = true;
        for (Finding f : findings) {
            words.append(first ? " " : " and ");
            first = false;
            if (f.kind().equals("stock")) {
                words.append("still holds stock worth ")
                        .append(money(f.valueMinor(), currency))
                        .append(" (")
                        .append(f.count())
                        .append(f.count() == 1 ? " item)" : " items)");
            } else if (f.kind().equals("open_orders")) {
                words.append("has ")
                        .append(f.count())
                        .append(f.count() == 1 ? " customer order" : " customer orders")
                        .append(" not finished");
            } else if (f.kind().equals("open_stocktakes")) {
                words.append("has ")
                        .append(f.count())
                        .append(f.count() == 1 ? " stock-take" : " stock-takes")
                        .append(" started and not finished");
            } else {
                words.append("has ")
                        .append(f.count())
                        .append(f.count() == 1 ? " transfer" : " transfers")
                        .append(" still on the way");
            }
        }
        return words.append('.').toString();
    }

    /** What the screen shows next to the warning, as figures, so it never has to read the words. */
    private Map<String, Object> facts(List<Finding> findings) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("currency", tenant.profile().currency());
        for (Finding f : findings) {
            if (f.kind().equals("stock")) {
                facts.put("stock_items", f.count());
                facts.put("stock_value_minor", f.valueMinor());
            } else if (f.kind().equals("open_orders")) {
                facts.put("open_orders", f.count());
            } else if (f.kind().equals("open_stocktakes")) {
                facts.put("open_stocktakes", f.count());
            } else {
                facts.put("transfers_in_flight", f.count());
                facts.put("transfers_in_flight_value_minor", f.valueMinor());
            }
        }
        return facts;
    }

    /** Thousands separated with the currency code first; shillings have no minor unit. */
    private static String money(long minor, String currency) {
        if ("UGX".equals(currency)) {
            return currency + " " + String.format(Locale.ENGLISH, "%,d", minor);
        }
        long abs = Math.abs(minor);
        return currency + " " + (minor < 0 ? "-" : "")
                + String.format(Locale.ENGLISH, "%,d.%02d", abs / 100, abs % 100);
    }

    // ---- The shop's own logo (issue #461, ADR-068) ------------------------------------------------------

    /**
     * Stores the logo as a core document of the tenant (the subject of the business logo, so the public route reads it the
     * same way) and points the branch at it; the file was checked and re-encoded before the transaction opened
     * ({@link BrandingService#uploadBranchLogo}). A branch outside the caller's {@code core.branches.manage} scope is a 404
     * and a closed branch a 409, both before anything is stored, and the status is checked again on the locked row so a close
     * that lands while the file is stored is still a 409. No If-Match: the version still goes up, so a form holding the
     * old one refetches.
     */
    @Transactional
    BranchResponse replaceLogo(UUID branchId, Documents.Prepared prepared) {
        Principal principal = CurrentPrincipal.require();
        BranchResponse branch = find(branchId)
                .filter(b -> principal.may("core.branches.manage", b.id()))
                .orElseThrow(ApiException::notFound);
        requireOpenForLogo(branch);
        // Stored first: the object write does not wait inside the branch row lock.
        UUID id = documents
                .store(prepared, TenantDocumentAccess.SUBJECT, tenant.profile().id(), branchId)
                .id();
        // Checked again on the locked row: a close that committed while the file was stored refuses here, and the
        // rollback
        // removes the stored object (DocumentService.store).
        BranchResponse locked = lockForUpdate(branchId);
        requireOpenForLogo(locked);
        return setLogo(locked, id);
    }

    private static void requireOpenForLogo(BranchResponse branch) {
        if (!"active".equals(branch.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The branch is closed. Reopen it to change its logo.");
        }
    }

    /**
     * The previous logo's document stays (documents are never deleted); only the branch's pointer is cleared. Idempotent on
     * an open branch; a closed branch's logo is left as it is (409), as for an upload.
     */
    @Transactional
    BranchResponse removeLogo(UUID branchId) {
        BranchResponse before = lockForUpdate(branchId);
        requireOpenForLogo(before);
        return before.logoUrl() == null ? before : setLogo(before, null);
    }

    private BranchResponse setLogo(BranchResponse before, UUID documentId) {
        UUID was = logoDocumentId(before.id()).orElse(null);
        jdbc.sql("""
                        UPDATE branches SET logo_document_id = ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(documentId, before.id()).update();
        Map<String, Object> wasValues = new LinkedHashMap<>();
        wasValues.put("logo_document_id", was);
        Map<String, Object> nowValues = new LinkedHashMap<>();
        nowValues.put("logo_document_id", documentId);
        audit.record(new AuditLog.Entry(
                "core.branch.logo_changed", "core.branch", before.id(), before.id(), wasValues, nowValues));
        return find(before.id()).orElseThrow();
    }

    /** The branch's logo document, when it has one; row-level security keeps every other business's branches out. */
    @Transactional(readOnly = true)
    Optional<UUID> logoDocumentId(UUID branchId) {
        return jdbc.sql("SELECT logo_document_id FROM branches WHERE id = ?")
                .param(branchId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, UUID.class)))
                .optional()
                .flatMap(o -> o);
    }

    /** As {@link #logoDocumentId} by the branch code, which never changes: what an issued document's snapshot keeps. */
    @Transactional(readOnly = true)
    Optional<BranchLogo> logoByCode(String code) {
        return jdbc.sql("SELECT id, logo_document_id FROM branches WHERE code = ? AND logo_document_id IS NOT NULL")
                .param(code)
                .query((rs, n) -> new BranchLogo(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
    }

    /** A branch with its logo document, for the path the logo is served at (issue #500). */
    record BranchLogo(UUID branchId, UUID documentId) {}

    static final String LOGO_PATH = "/api/v1/branches/%s/logo?v=%s";

    /** The versioned URL the branch response and {@code /me} give for a shop's logo, or null without one. */
    static String logoUrl(UUID branchId, UUID logoDocumentId) {
        return logoDocumentId == null ? null : LOGO_PATH.formatted(branchId, logoDocumentId);
    }

    private BranchResponse lockForUpdate(UUID branchId) {
        Principal principal = CurrentPrincipal.require();
        return jdbc.sql("SELECT " + COLUMNS + " FROM branches WHERE id = ? FOR UPDATE")
                .param(branchId)
                .query(BranchService::map)
                .optional()
                .filter(b -> principal.may("core.branches.manage", b.id()))
                .orElseThrow(ApiException::notFound);
    }

    private Optional<BranchResponse> find(UUID branchId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM branches WHERE id = ?")
                .param(branchId)
                .query(BranchService::map)
                .optional();
    }

    private static Map<String, Object> values(String name, String location, boolean stocksGoods, boolean sellsGoods) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("location", location);
        m.put("stocks_goods", stocksGoods);
        m.put("sells_goods", sellsGoods);
        return m;
    }

    private static BranchResponse map(ResultSet rs, int n) throws SQLException {
        return new BranchResponse(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("location"),
                rs.getBoolean("is_head_office"),
                rs.getString("status"),
                rs.getBoolean("stocks_goods"),
                rs.getBoolean("sells_goods"),
                instant(rs.getTimestamp("closed_at")),
                rs.getString("closed_reason"),
                null,
                null,
                logoUrl(rs.getObject("id", UUID.class), rs.getObject("logo_document_id", UUID.class)),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getInt("version"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
