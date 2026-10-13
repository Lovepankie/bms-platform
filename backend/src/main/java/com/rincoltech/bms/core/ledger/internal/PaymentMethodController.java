package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/ledger/payment-methods} (chapter 7, FR-GL-08): which asset account each payment
 * method posts to, for the tenant or for one branch.
 */
@RestController
@RequestMapping(path = "/api/v1/ledger/payment-methods", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "ledger")
class PaymentMethodController {

    static final String METHODS = "cash|bank|mtn_momo|airtel_money|gateway|savings_transfer";

    @Schema(name = "PaymentMethodMapping")
    record Mapping(
            String methodKey,

            @Schema(description = "Null for the tenant's mapping")
            UUID branchId,

            UUID glAccountId,
            String accountCode,
            boolean isActive,

            @Schema(description = "mapped (a row) or default (the seeded account, ADR-026)")
            String source) {}

    @Schema(name = "PaymentMethodMappingList")
    record MappingList(List<Mapping> items) {}

    @Schema(name = "SetPaymentMethodRequest")
    record SetRequest(
            @NotNull @Pattern(regexp = METHODS) String methodKey,

            @Schema(description = "Omit for the tenant's mapping")
            UUID branchId,

            @NotNull UUID glAccountId,

            @Schema(description = "Default true; false switches the method off for this scope")
            Boolean isActive) {}

    /**
     * The methods whose seeded account retail also posts to and reads (ADR-026): retail's sales,
     * purchases, cash book and cash figures use {@code cash_on_hand} and {@code bank} by key, not through
     * this table, so with the retail module on these two stay on those accounts.
     */
    static final Map<String, String> RETAIL_SHARED = Map.of("cash", "cash_on_hand", "bank", "bank");

    private final JdbcClient jdbc;
    private final Branches branches;
    private final TenantModules modules;
    private final AuditLog audit;

    PaymentMethodController(JdbcClient jdbc, Branches branches, TenantModules modules, AuditLog audit) {
        this.jdbc = jdbc;
        this.branches = branches;
        this.modules = modules;
        this.audit = audit;
    }

    static final String MANAGE = "core.payment_methods.manage";

    /**
     * The caller's branch scope for the manage permission: a branch's row needs that branch in scope,
     * the tenant-wide row needs authority over every branch. A request for a scope the caller does
     * not hold is refused before anything is read.
     */
    private static void requireScope(UUID branchId) {
        Principal principal = CurrentPrincipal.require();
        boolean allowed = principal
                .scopeOf(MANAGE)
                .map(scope -> branchId == null ? scope.all() : scope.covers(branchId))
                .orElse(false);
        if (!allowed) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    branchId == null
                            ? "The tenant-wide mapping needs authority over every branch."
                            : "You do not manage payment methods at this branch.");
        }
    }

    @GetMapping
    @RequiresPermission("core.ledger.read")
    @Operation(
            summary = "Each payment method's account: the mapped rows, then the defaults still in use",
            operationId = "listPaymentMethods")
    @Transactional(readOnly = true)
    MappingList list() {
        List<Mapping> items = new ArrayList<>(jdbc.sql("""
                        SELECT p.method_key, p.branch_id, p.gl_account_id, a.code, p.is_active
                          FROM payment_methods p JOIN gl_accounts a ON a.id = p.gl_account_id
                         ORDER BY p.method_key, p.branch_id NULLS FIRST
                        """)
                .query((rs, n) -> new Mapping(
                        rs.getString(1),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getString(4),
                        rs.getBoolean(5),
                        "mapped"))
                .list());
        JdbcPaymentMethods.DEFAULT_ACCOUNTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> items.stream().noneMatch(m -> m.methodKey().equals(e.getKey()) && m.branchId() == null))
                .forEach(e -> jdbc.sql(
                                "SELECT id, code FROM gl_accounts WHERE system_key = ? AND is_postable AND is_active")
                        .param(e.getValue())
                        .query((rs, n) -> new Mapping(
                                e.getKey(), null, rs.getObject(1, UUID.class), rs.getString(2), true, "default"))
                        .optional()
                        .ifPresent(items::add));
        return new MappingList(List.copyOf(items));
    }

    /** Sets one method's account for the tenant or one branch; the account is a postable, active asset account that no module controls. */
    @PutMapping
    @RequiresPermission(MANAGE)
    @Operation(summary = "Map a payment method to an asset account (FR-GL-08)", operationId = "setPaymentMethod")
    @ApiResponse(responseCode = "403", description = "Not in the caller's branch scope (permission_denied)")
    @ApiResponse(
            responseCode = "409",
            description = "Another first save of the same method and scope won (payment_method_changed)")
    @ApiResponse(
            responseCode = "422",
            description =
                    "invalid_payment_account, unknown_branch, account_used_by_retail (cash and bank with retail on)")
    @Transactional
    MappingList set(@Valid @RequestBody SetRequest r) {
        requireScope(r.branchId());
        // Serialises with platform_set_tenant_modules (V43), which takes the same lock before it looks
        // at the mappings: a save that passed the retail check cannot interleave with retail being
        // switched on, and the other way round. The lock ends with the transaction.
        jdbc.sql(
                        "SELECT pg_advisory_xact_lock(hashtextextended('payment_methods:' || current_setting('app.tenant_id'), 0))")
                .query()
                .singleRow();
        List<FieldProblem> problems = new ArrayList<>();
        if (r.branchId() != null && branches.findActive(r.branchId()).isEmpty()) {
            problems.add(new FieldProblem("branch_id", "unknown_branch", "No such active branch."));
        }
        Optional<String[]> account = jdbc.sql("""
                        SELECT code, system_key FROM gl_accounts
                         WHERE id = ? AND is_postable AND is_active AND account_type = 'asset' AND NOT is_system_controlled
                        """)
                .param(r.glAccountId())
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
                .optional();
        Optional<String> code = account.map(a -> a[0]);
        boolean active = r.isActive() == null || r.isActive();
        if (code.isEmpty()) {
            problems.add(new FieldProblem(
                    "gl_account_id",
                    "invalid_payment_account",
                    "Must be a postable, active asset account that no module controls."));
        } else if (active
                && RETAIL_SHARED.containsKey(r.methodKey())
                && !RETAIL_SHARED.get(r.methodKey()).equals(account.get()[1])
                && modules.isEnabled("retail")) {
            problems.add(new FieldProblem(
                    "gl_account_id",
                    "account_used_by_retail",
                    "Retail posts its cash and bank to the seeded accounts, so with the retail module on "
                            + r.methodKey()
                            + " stays on "
                            + RETAIL_SHARED.get(r.methodKey())
                            + "."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        UUID user = CurrentPrincipal.require().userId();
        Optional<Mapping> before = jdbc.sql("""
                        SELECT method_key, branch_id, gl_account_id, '' AS code, is_active FROM payment_methods
                         WHERE method_key = :key AND branch_id IS NOT DISTINCT FROM CAST(:branch AS uuid) FOR UPDATE
                        """)
                .param("key", r.methodKey())
                .param("branch", r.branchId())
                .query((rs, n) -> new Mapping(
                        rs.getString(1),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        null,
                        rs.getBoolean(5),
                        "mapped"))
                .optional();
        UUID id;
        if (before.isPresent()) {
            id = jdbc.sql("""
                            UPDATE payment_methods SET gl_account_id = :account, is_active = :active, updated_by = :user,
                                   updated_at = now(), version = version + 1
                             WHERE method_key = :key AND branch_id IS NOT DISTINCT FROM CAST(:branch AS uuid)
                            RETURNING id
                            """)
                    .param("account", r.glAccountId())
                    .param("active", active)
                    .param("user", user)
                    .param("key", r.methodKey())
                    .param("branch", r.branchId())
                    .query(UUID.class)
                    .single();
        } else {
            id = UUID.randomUUID();
            try {
                jdbc.sql("""
                                INSERT INTO payment_methods (id, tenant_id, method_key, branch_id, gl_account_id, is_active, updated_by)
                                VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                                """)
                        .params(id, r.methodKey(), r.branchId(), r.glAccountId(), active, user)
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Two first saves for the same method and scope: the unique index decides, the other is told.
                throw new ApiException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "payment_method_changed",
                        "Changed at the same time",
                        "Another change to this payment method was saved just now; reload and try again.");
            }
        }
        Map<String, Object> was = new LinkedHashMap<>();
        before.ifPresent(b -> {
            was.put("gl_account_id", b.glAccountId());
            was.put("is_active", b.isActive());
        });
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("method_key", r.methodKey());
        now.put("branch_id", r.branchId());
        now.put("gl_account_id", r.glAccountId());
        now.put("account_code", code.orElseThrow());
        now.put("is_active", active);
        audit.record(new AuditLog.Entry("core.payment_method.set", "core.payment_method", id, r.branchId(), was, now));
        return list();
    }

    /**
     * Removes the mapping of one method for the tenant or one branch, so it posts to its seeded
     * account again (ADR-026). The tenant's mapping is not touched when a branch row is removed.
     */
    @DeleteMapping("/{method_key}")
    @RequiresPermission("core.payment_methods.manage")
    @Operation(
            summary = "Return a payment method to its default account for the tenant or a branch (FR-GL-08)",
            operationId = "clearPaymentMethod")
    @ApiResponse(responseCode = "403", description = "Not in the caller's branch scope (permission_denied)")
    @ApiResponse(responseCode = "404", description = "That scope has no mapping for the method (not_found)")
    @ApiResponse(responseCode = "422", description = "Not a payment method key (validation_failed)")
    @Transactional
    MappingList clear(
            @PathVariable("method_key") String methodKey,
            @RequestParam(name = "branch_id", required = false) UUID branchId) {
        requireScope(branchId);
        if (!methodKey.matches(METHODS)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("method_key", "invalid", "Not a payment method: " + methodKey + ".")));
        }
        Optional<Map<String, Object>> was = jdbc.sql("""
                        SELECT id, gl_account_id, is_active FROM payment_methods
                         WHERE method_key = :key AND branch_id IS NOT DISTINCT FROM CAST(:branch AS uuid) FOR UPDATE
                        """)
                .param("key", methodKey)
                .param("branch", branchId)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject(1, UUID.class));
                    row.put("gl_account_id", rs.getObject(2, UUID.class));
                    row.put("is_active", rs.getBoolean(3));
                    return row;
                })
                .optional();
        Map<String, Object> row = was.orElseThrow(ApiException::notFound);
        jdbc.sql("DELETE FROM payment_methods WHERE id = ?")
                .param(row.get("id"))
                .update();
        Map<String, Object> before = new LinkedHashMap<>(row);
        before.remove("id");
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("method_key", methodKey);
        now.put("branch_id", branchId);
        now.put("source", "default");
        audit.record(new AuditLog.Entry(
                "core.payment_method.cleared", "core.payment_method", (UUID) row.get("id"), branchId, before, now));
        return list();
    }
}
