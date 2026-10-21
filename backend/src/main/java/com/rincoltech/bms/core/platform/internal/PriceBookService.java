package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.platform.internal.PriceBookController.AddPriceRequest;
import com.rincoltech.bms.core.platform.internal.PriceBookController.PlanDetail;
import com.rincoltech.bms.core.platform.internal.PriceBookController.PlanPriceResponse;
import com.rincoltech.bms.core.platform.internal.PriceBookController.PlanResponse;
import com.rincoltech.bms.core.platform.internal.PriceBookController.SavePlanRequest;
import com.rincoltech.bms.core.tenancy.ModuleManifest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The price book (ADR-056): plans with their limits and their prices over time. Plans change through
 * {@code platform_save_plan} and prices are added through {@code platform_add_plan_price} (ADR-016); a price row is never
 * changed, so the price on any past day can be read back. Every change is written to {@code platform_audit_log} with the
 * operator; a request that would change nothing writes nothing.
 */
@Service
class PriceBookService {

    static final String PLAN_CODE = "^[a-z0-9_-]{1,40}$";

    /** The cap on a monthly price and on a payment, in minor units: UGX 50 million, far above any plan (a typing slip). */
    static final long MAX_MINOR = 50_000_000L;

    private final JdbcClient jdbc;
    private final PlatformAuditLog audit;
    private final BusinessClock clock;
    private final Set<String> moduleKeys;

    PriceBookService(JdbcClient jdbc, PlatformAuditLog audit, BusinessClock clock, List<ModuleManifest> manifests) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.moduleKeys = manifests.stream().map(ModuleManifest::key).collect(Collectors.toUnmodifiableSet());
    }

    record Saved(PlanDetail detail, boolean created) {}

    private record PlanRow(
            UUID id,
            String code,
            String name,
            String description,
            Integer maxBranches,
            Integer maxStaffUsers,
            Integer maxActiveMembers,
            List<String> allowedModules,
            boolean active,
            int sortOrder,
            long tenantCount) {}

    LocalDate today() {
        return clock.today(BusinessClock.DEFAULT_ZONE);
    }

    @Transactional(readOnly = true)
    List<PlanResponse> plans(boolean includeInactive) {
        LocalDate today = today();
        return rows(includeInactive ? null : true, null).stream()
                .map(p -> response(p, today))
                .toList();
    }

    @Transactional(readOnly = true)
    PlanDetail detail(String code) {
        PlanRow row = row(code).orElseThrow(ApiException::notFound);
        return new PlanDetail(response(row, today()), prices(row.id(), null));
    }

    /** The price in force on {@code day} for a plan and currency, or empty when none was entered. */
    @Transactional(readOnly = true)
    Optional<PlanPriceResponse> priceOn(String planCode, String currency, LocalDate day) {
        return jdbc.sql(PRICE_SELECT + """
                         WHERE p.code = ? AND pp.currency = ? AND pp.effective_from <= ?
                         ORDER BY pp.effective_from DESC, pp.created_at DESC LIMIT 1
                        """)
                .params(planCode, currency, day)
                .query(PriceBookService::price)
                .optional();
    }

    @Transactional
    Saved save(String code, SavePlanRequest request) {
        if (code == null || !code.matches(PLAN_CODE)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "plan_code", "invalid", "1 to 40 lower case letters, digits, hyphens or underscores.")));
        }
        List<String> modules =
                request.allowedModules().stream().distinct().sorted().toList();
        for (String module : modules) {
            if (!moduleKeys.contains(module)) {
                throw ApiException.validation(
                        List.of(new FieldProblem("allowed_modules", "invalid", "Unknown module " + module + ".")));
            }
        }
        String description = blankToNull(request.description());
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        Optional<PlanRow> before = row(code);
        PlanRow wanted = new PlanRow(
                before.map(PlanRow::id).orElse(null),
                code,
                request.name().trim(),
                description,
                request.maxBranches(),
                request.maxStaffUsers(),
                request.maxActiveMembers(),
                modules,
                request.active(),
                sortOrder,
                before.map(PlanRow::tenantCount).orElse(0L));
        if (before.isPresent() && before.get().equals(wanted)) {
            return new Saved(detail(code), false);
        }
        Map<String, Long> over = before.map(b -> overLimit(code, b, wanted)).orElse(Map.of());
        if (!over.isEmpty() && !Boolean.TRUE.equals(request.confirmBelowUse())) {
            throw ApiException.rule("plan_limit_below_use", belowUseMessage(over));
        }
        boolean created;
        try {
            created = Boolean.TRUE.equals(jdbc.sql("SELECT platform_save_plan(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            code,
                            wanted.name(),
                            description,
                            wanted.maxBranches(),
                            wanted.maxStaffUsers(),
                            wanted.maxActiveMembers(),
                            modules.toArray(String[]::new),
                            wanted.active(),
                            sortOrder,
                            CurrentPrincipal.require().userId())
                    .query(Boolean.class)
                    .single());
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("plan_module_in_use")) {
                throw ApiException.rule(
                        "plan_module_in_use",
                        "A business on this plan uses a module you took out. Move that business to another plan first,"
                                + " or keep the module in this plan.");
            }
            throw e;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan_code", code);
        before.ifPresent(b -> data.put("before", snapshot(b)));
        data.put("after", snapshot(wanted));
        if (!over.isEmpty()) {
            data.put("businesses_over_limit", over);
        }
        data.put("reason", blankToNull(request.reason()));
        audit.record(
                created ? "platform.plan.created" : "platform.plan.changed",
                CurrentPrincipal.require().userId(),
                null,
                data);
        return new Saved(detail(code), created);
    }

    @Transactional
    PlanDetail addPrice(String code, AddPriceRequest request) {
        PlanRow plan = row(code).orElseThrow(ApiException::notFound);
        String currency = request.currency() == null ? "UGX" : request.currency();
        LocalDate today = today();
        LocalDate from = request.effectiveFrom() == null ? today : request.effectiveFrom();
        if (from.isBefore(today)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "effective_from", "in_the_past", "A new price starts today or later; it never changes the past.")));
        }
        Optional<PlanPriceResponse> inForce = priceOn(code, currency, from);
        if (inForce.isPresent()
                && inForce.get().monthlyMinor() == request.monthlyMinor()
                && inForce.get().annualMonths() == request.annualMonths()) {
            return detail(code);
        }
        if (inForce.isPresent()
                && !Boolean.TRUE.equals(request.confirmLargeChange())
                && Math.abs(request.monthlyMinor() - inForce.get().monthlyMinor()) * 4
                        > inForce.get().monthlyMinor()) {
            throw ApiException.rule(
                    "price_change_unusual",
                    "The price in force is "
                            + BusinessService.money(currency, inForce.get().monthlyMinor())
                            + " a month, and the new price is "
                            + BusinessService.money(currency, request.monthlyMinor())
                            + ". That is more than a quarter away. Check it, then confirm it.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("SELECT platform_add_plan_price(?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            id,
                            code,
                            currency,
                            request.monthlyMinor(),
                            request.annualMonths(),
                            from,
                            blankToNull(request.reason()),
                            CurrentPrincipal.require().userId())
                    .query()
                    .singleRow();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(
                    List.of(new FieldProblem("currency", "invalid", "Unknown currency " + currency + ".")));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan_code", code);
        data.put("price_id", id);
        data.put("currency", currency);
        data.put("monthly_minor", request.monthlyMinor());
        data.put("annual_months", request.annualMonths());
        data.put("effective_from", from.toString());
        inForce.ifPresent(p -> data.put("before_monthly_minor", p.monthlyMinor()));
        data.put("reason", blankToNull(request.reason()));
        audit.record("platform.plan.price_added", CurrentPrincipal.require().userId(), null, data);
        return new PlanDetail(response(plan, today), prices(plan.id(), null));
    }

    /**
     * The businesses on the plan that would be over a limit this change lowers, by limit (branches, staff, members); empty
     * when no limit goes down or nobody is over. A limit that stays or rises is not checked.
     */
    private Map<String, Long> overLimit(String code, PlanRow before, PlanRow wanted) {
        Integer branches = lowered(before.maxBranches(), wanted.maxBranches());
        Integer staff = lowered(before.maxStaffUsers(), wanted.maxStaffUsers());
        Integer members = lowered(before.maxActiveMembers(), wanted.maxActiveMembers());
        if (branches == null && staff == null && members == null) {
            return Map.of();
        }
        return jdbc.sql("SELECT * FROM platform_plan_tenants_over(?, ?, ?, ?)")
                .params(code, branches, staff, members)
                .query((rs, n) -> {
                    Map<String, Long> out = new LinkedHashMap<>();
                    for (String key : List.of("branches", "staff", "members")) {
                        long count = rs.getLong("over_" + key);
                        if (count > 0) {
                            out.put(key, count);
                        }
                    }
                    return out;
                })
                .single();
    }

    private static Integer lowered(Integer before, Integer after) {
        return after != null && (before == null || after < before) ? after : null;
    }

    private static String belowUseMessage(Map<String, Long> over) {
        Map<String, String> what =
                Map.of("branches", "open shops and stores", "staff", "staff", "members", "active lending members");
        String parts = over.entrySet().stream()
                .map(e -> e.getValue() + (e.getValue() == 1 ? " business has" : " businesses have") + " more "
                        + what.get(e.getKey()))
                .collect(Collectors.joining(", "));
        return "On this plan, " + parts + " than the new limit allows. They keep working but are over their plan until"
                + " they are moved. Check it, then confirm it.";
    }

    // ---- reads ---------------------------------------------------------------------------------

    private static final String PLAN_SELECT = """
            WITH c AS (SELECT o.plan_code, count(*) AS n FROM platform_tenant_overview() o GROUP BY o.plan_code)
            SELECT p.id, p.code, p.name, p.description, p.max_branches, p.max_staff_users, p.max_active_members,
                   p.allowed_modules, p.is_active, p.sort_order, coalesce(c.n, 0) AS tenant_count
              FROM plans p
              LEFT JOIN c ON c.plan_code = p.code
            """;

    private static final String PRICE_SELECT = """
            SELECT pp.id, pp.currency, pp.monthly_minor, pp.annual_months, pp.effective_from, pp.reason, pp.created_at,
                   u.full_name AS created_by_name
              FROM plan_prices pp
              JOIN plans p ON p.id = pp.plan_id
              LEFT JOIN platform_users u ON u.id = pp.created_by_platform_user_id
            """;

    private List<PlanRow> rows(Boolean active, String code) {
        String where = code != null ? " WHERE p.code = ?" : (active != null ? " WHERE p.is_active" : "");
        var query = jdbc.sql(PLAN_SELECT + where + " ORDER BY p.sort_order, p.name, p.code");
        if (code != null) {
            query = query.param(code);
        }
        return query.query(PriceBookService::plan).list();
    }

    private Optional<PlanRow> row(String code) {
        if (code == null || !code.matches(PLAN_CODE)) {
            return Optional.empty();
        }
        return rows(null, code).stream().findFirst();
    }

    private List<PlanPriceResponse> prices(UUID planId, String currency) {
        return jdbc.sql(PRICE_SELECT
                        + " WHERE pp.plan_id = ? AND (?::text IS NULL OR pp.currency = ?)"
                        + " ORDER BY pp.effective_from DESC, pp.created_at DESC")
                .params(planId, currency, currency)
                .query(PriceBookService::price)
                .list();
    }

    private PlanResponse response(PlanRow p, LocalDate today) {
        PlanPriceResponse current = priceOn(p.code(), "UGX", today).orElse(null);
        PlanPriceResponse next = jdbc.sql(PRICE_SELECT + """
                         WHERE pp.plan_id = ? AND pp.currency = 'UGX' AND pp.effective_from > ?
                         ORDER BY pp.effective_from, pp.created_at DESC LIMIT 1
                        """)
                .params(p.id(), today)
                .query(PriceBookService::price)
                .optional()
                .orElse(null);
        return new PlanResponse(
                p.code(),
                p.name(),
                p.description(),
                p.maxBranches(),
                p.maxStaffUsers(),
                p.maxActiveMembers(),
                p.allowedModules(),
                p.active(),
                p.sortOrder(),
                current,
                next,
                p.tenantCount());
    }

    private static Map<String, Object> snapshot(PlanRow p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("max_branches", p.maxBranches());
        m.put("max_staff_users", p.maxStaffUsers());
        m.put("max_active_members", p.maxActiveMembers());
        m.put("allowed_modules", p.allowedModules());
        m.put("active", p.active());
        m.put("sort_order", p.sortOrder());
        return m;
    }

    private static PlanRow plan(ResultSet rs, int n) throws SQLException {
        return new PlanRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("max_branches", Integer.class),
                rs.getObject("max_staff_users", Integer.class),
                rs.getObject("max_active_members", Integer.class),
                strings(rs.getArray("allowed_modules")),
                rs.getBoolean("is_active"),
                rs.getInt("sort_order"),
                rs.getLong("tenant_count"));
    }

    static PlanPriceResponse price(ResultSet rs, int n) throws SQLException {
        long monthly = rs.getLong("monthly_minor");
        int months = rs.getInt("annual_months");
        return new PlanPriceResponse(
                rs.getObject("id", UUID.class),
                rs.getString("currency"),
                monthly,
                months,
                monthly * months,
                rs.getObject("effective_from", LocalDate.class),
                rs.getString("reason"),
                rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((String[]) array.getArray()).sorted().toList();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
