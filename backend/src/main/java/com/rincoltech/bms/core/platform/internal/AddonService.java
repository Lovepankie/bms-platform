package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.platform.internal.AddonController.AddAddonPriceRequest;
import com.rincoltech.bms.core.platform.internal.AddonController.AddonDetail;
import com.rincoltech.bms.core.platform.internal.AddonController.AddonPriceResponse;
import com.rincoltech.bms.core.platform.internal.AddonController.TenantAddonResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.TenantResponse;
import com.rincoltech.bms.core.platform.internal.PriceBookController.PlanPriceResponse;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AI assistant add-on (ADR-112, issue #423): its monthly price over time ({@code addon_prices}, entered by the operator,
 * never seeded), the operator's switch per business with a reason ({@code subscriptions.ai_addon}, audited as
 * {@code platform.tenant.ai_addon_changed}), and what it adds to what a business owes. It is charged only when it is on, not
 * marked no charge and the business is not a pilot; in the free month nothing is charged at all, so it is tried free then. The
 * add-on is what lets a business use the assistant ({@code core.ai}'s allow-list reads the same mark).
 */
@Service
class AddonService {

    static final String AI_ASSISTANT = "ai_assistant";

    /** The add-on's state on one business: on, on without charge, and whether it is charged. */
    record State(boolean on, boolean noCharge) {}

    private final JdbcClient jdbc;
    private final PlatformService platform;
    private final PriceBookService priceBook;
    private final PlatformAuditLog audit;
    private final BusinessClock clock;

    AddonService(
            JdbcClient jdbc,
            PlatformService platform,
            PriceBookService priceBook,
            PlatformAuditLog audit,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.platform = platform;
        this.priceBook = priceBook;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- the price ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    AddonDetail detail() {
        LocalDate today = clock.today(BusinessClock.DEFAULT_ZONE);
        long on = jdbc.sql("SELECT platform_ai_addon_on_count()")
                .query(Long.class)
                .single();
        return new AddonDetail(
                AI_ASSISTANT, "AI assistant", priceOn("UGX", today).orElse(null), prices(), on);
    }

    /** The add-on's price in force on {@code day}, or empty when none was entered. */
    @Transactional(readOnly = true)
    Optional<AddonPriceResponse> priceOn(String currency, LocalDate day) {
        return jdbc.sql(PRICE_SELECT + """
                         WHERE ap.addon_code = ? AND ap.currency = ? AND ap.effective_from <= ?
                         ORDER BY ap.effective_from DESC, ap.created_at DESC LIMIT 1
                        """)
                .params(AI_ASSISTANT, currency, day)
                .query(AddonService::price)
                .optional();
    }

    private List<AddonPriceResponse> prices() {
        return jdbc.sql(PRICE_SELECT + " WHERE ap.addon_code = ? ORDER BY ap.effective_from DESC, ap.created_at DESC")
                .param(AI_ASSISTANT)
                .query(AddonService::price)
                .list();
    }

    /** A new price from a date, today or later; the price already in force on that date again changes nothing. */
    @Transactional
    AddonDetail addPrice(AddAddonPriceRequest request) {
        String currency = request.currency() == null ? "UGX" : request.currency();
        LocalDate today = clock.today(BusinessClock.DEFAULT_ZONE);
        LocalDate from = request.effectiveFrom() == null ? today : request.effectiveFrom();
        if (from.isBefore(today)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "effective_from", "in_the_past", "A new price starts today or later; it never changes the past.")));
        }
        Optional<AddonPriceResponse> inForce = priceOn(currency, from);
        if (inForce.isPresent() && inForce.get().monthlyMinor() == request.monthlyMinor()) {
            return detail();
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
        UUID operator = CurrentPrincipal.require().userId();
        try {
            jdbc.sql("SELECT platform_add_addon_price(?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            id,
                            AI_ASSISTANT,
                            currency,
                            request.monthlyMinor(),
                            from,
                            PriceBookService.blankToNull(request.reason()),
                            operator)
                    .query()
                    .singleRow();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(
                    List.of(new FieldProblem("currency", "invalid", "Unknown currency " + currency + ".")));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("addon_code", AI_ASSISTANT);
        data.put("price_id", id);
        data.put("currency", currency);
        data.put("monthly_minor", request.monthlyMinor());
        data.put("effective_from", from.toString());
        inForce.ifPresent(p -> data.put("before_monthly_minor", p.monthlyMinor()));
        data.put("reason", PriceBookService.blankToNull(request.reason()));
        audit.record("platform.addon.price_added", operator, null, data);
        return detail();
    }

    // ---- one business ---------------------------------------------------------------------------------------------

    State state(UUID tenantId) {
        return jdbc.sql("SELECT * FROM platform_tenant_ai_addon(?)")
                .param(tenantId)
                .query((rs, n) -> new State(rs.getBoolean("ai_addon"), rs.getBoolean("ai_addon_no_charge")))
                .optional()
                .orElse(new State(false, false));
    }

    /** True when the add-on adds to what the business owes: on, not marked no charge, and the business is not a pilot. */
    static boolean charged(State state, TenantResponse tenant) {
        return state.on() && !state.noCharge() && !tenant.pilot();
    }

    /** The add-on as the Business page shows it, priced on the business's today. */
    @Transactional(readOnly = true)
    TenantAddonResponse view(TenantResponse tenant) {
        State s = state(tenant.id());
        LocalDate today = clock.today(ZoneId.of(tenant.timezone()));
        AddonPriceResponse price = priceOn(tenant.currency(), today).orElse(null);
        return new TenantAddonResponse(
                AI_ASSISTANT, s.on(), s.noCharge(), charged(s, tenant), price == null ? null : price.monthlyMinor());
    }

    /**
     * Switches the add-on on or off for a business, on or off charge, with a reason. The same state again changes nothing and
     * writes nothing. Switching it off stops the assistant for the business at once (the gateway reads the mark on every call).
     */
    @Transactional
    TenantAddonResponse set(UUID tenantId, boolean on, boolean noCharge, String reason) {
        TenantResponse tenant = platform.tenant(tenantId);
        State before = state(tenantId);
        State after = new State(on, on && noCharge);
        if (before.equals(after)) {
            return view(tenant);
        }
        UUID operator = CurrentPrincipal.require().userId();
        // The marks before, as the function read them under the row lock: another operator's change in between is
        // audited as the before, and when it already made this state nothing is audited (review of #743).
        State locked = jdbc.sql("SELECT * FROM platform_set_ai_addon(?, ?, ?, ?)")
                .params(tenantId, on, after.noCharge(), operator)
                .query((rs, n) -> new State(rs.getBoolean("was_on"), rs.getBoolean("was_no_charge")))
                .single();
        if (locked.equals(after)) {
            return view(tenant);
        }
        before = locked;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("addon_code", AI_ASSISTANT);
        data.put("before", before.on() ? (before.noCharge() ? "on_no_charge" : "on") : "off");
        data.put("after", after.on() ? (after.noCharge() ? "on_no_charge" : "on") : "off");
        data.put("reason", reason.trim());
        audit.record("platform.tenant.ai_addon_changed", operator, tenantId, data);
        return view(tenant);
    }

    /**
     * What the business owes for a month from {@code day}: the plan's price in force then, with the add-on's monthly price
     * added when it is charged (a year adds twelve months of it: the plan's months free are the plan's alone). Empty when the
     * plan has no price; a charged add-on with no price entered adds nothing.
     */
    @Transactional(readOnly = true)
    Optional<PlanPriceResponse> chargeOn(TenantResponse tenant, LocalDate day) {
        Optional<PlanPriceResponse> plan = priceBook.priceOn(tenant.planCode(), tenant.currency(), day);
        if (plan.isEmpty() || !charged(state(tenant.id()), tenant)) {
            return plan;
        }
        long addon = priceOn(tenant.currency(), day)
                .map(AddonPriceResponse::monthlyMinor)
                .orElse(0L);
        PlanPriceResponse p = plan.get();
        return Optional.of(new PlanPriceResponse(
                p.id(),
                p.currency(),
                p.monthlyMinor() + addon,
                p.annualMonths(),
                p.annualMinor() + addon * 12,
                p.effectiveFrom(),
                p.reason(),
                p.createdByName(),
                p.createdAt()));
    }

    private static final String PRICE_SELECT = """
            SELECT ap.id, ap.currency, ap.monthly_minor, ap.effective_from, ap.reason, ap.created_at,
                   u.full_name AS created_by_name
              FROM addon_prices ap
              LEFT JOIN platform_users u ON u.id = ap.created_by_platform_user_id
            """;

    private static AddonPriceResponse price(ResultSet rs, int n) throws SQLException {
        return new AddonPriceResponse(
                rs.getObject("id", UUID.class),
                rs.getString("currency"),
                rs.getLong("monthly_minor"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getString("reason"),
                rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant());
    }
}
