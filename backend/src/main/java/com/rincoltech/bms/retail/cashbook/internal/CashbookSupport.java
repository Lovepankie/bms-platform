package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.VoidRequest;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What every cash book service shares: the permission names, the business date rules, the profit
 * gate of ADR-022 decision 13, the tenant settings of the cash book and the savings suggestion
 * token. No permission is checked here except the profit gate's question.
 */
@Component
class CashbookSupport {

    static final String READ = "retail.cashbook.read";
    static final String SAVINGS_RECORD = "retail.savings.record";
    static final String SAVINGS_OVERWRITE = "retail.savings.overwrite";
    static final String BANKING_RECORD = "retail.banking.record";
    static final String EXPENSE_RECORD = "retail.expense.record";
    static final String EXPENSE_MANAGE = "retail.expense.manage";
    static final String WITHDRAWAL_RECORD = "retail.withdrawal.record";
    static final String ADVANCE_CREATE = "retail.advance.create";
    static final String ADVANCE_REPAY = "retail.advance.repay";
    static final String VOID = "retail.cashbook.void";
    static final String PROFIT_READ = "retail.profit.read";

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int DEFAULT_REPORT_DAYS = 30;
    static final int MAX_REPORT_DAYS = 366;

    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final JdbcClient jdbc;
    private final byte[] tokenKey;

    CashbookSupport(
            CurrentTenant tenant,
            BusinessClock clock,
            JdbcClient jdbc,
            @Value("${bms.auth.data-key:}") String dataKey) {
        this.tenant = tenant;
        this.clock = clock;
        this.jdbc = jdbc;
        byte[] key;
        if (dataKey == null || dataKey.isBlank()) {
            // The dev and test profiles may leave the data key blank; a token then lives until a restart.
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else {
            key = ("retail.savings.suggestion:" + dataKey).getBytes(StandardCharsets.UTF_8);
        }
        this.tokenKey = key;
    }

    LocalDate today() {
        return clock.today(tenant.profile().timezone());
    }

    Instant now() {
        return clock.now();
    }

    String currency() {
        return tenant.profile().currency();
    }

    /** The business date of a record: today when absent, never in the future (422 field problem). */
    LocalDate businessDate(LocalDate requested, String field) {
        LocalDate today = today();
        LocalDate date = requested == null ? today : requested;
        if (date.isAfter(today)) {
            throw ApiException.validation(
                    List.of(new FieldProblem(field, "future_date", "A date cannot be in the future.")));
        }
        return date;
    }

    /** The first and last day of a report: the last 30 days by default, at most 366. */
    LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_REPORT_DAYS - 1L) : from;
        if (start.isAfter(end)) {
            throw ApiException.validation(List.of(new FieldProblem("from", "invalid", "from is after to.")));
        }
        if (start.plusDays(MAX_REPORT_DAYS).isBefore(end)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("from", "range_too_long", "Ask for at most 366 days at a time.")));
        }
        return new LocalDate[] {start, end};
    }

    int limit(Integer requested) {
        return requested == null ? DEFAULT_LIMIT : Math.clamp(requested, 1, MAX_LIMIT);
    }

    /** True when the caller holds {@code retail.profit.read} in the branch (ADR-017). */
    static boolean mayProfit(UUID branchId) {
        return CurrentPrincipal.require().may(PROFIT_READ, branchId);
    }

    static Principal principal() {
        return CurrentPrincipal.require();
    }

    static ApiException notFound() {
        return ApiException.notFound();
    }

    static ApiException alreadyVoided() {
        return new ApiException(
                HttpStatus.CONFLICT, "cash_record_voided", "Record voided", "This record has been voided already.");
    }

    static String reason(VoidRequest r) {
        return r.reason().trim();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---------------------------------------------------------------------------------- settings

    /** {@code retail.cashbook.savings_rate_bp} from the tenant's settings; 5000 (half) when unset. */
    long savingsRateBp() {
        return setting("retail.cashbook.savings_rate_bp", 5000);
    }

    /** {@code retail.cashbook.tolerance_minor}; 0 when unset. */
    long toleranceMinor() {
        return setting("retail.cashbook.tolerance_minor", 0);
    }

    private long setting(String key, long fallback) {
        String value = jdbc.sql("SELECT settings ->> ? FROM tenant_settings")
                .param(key)
                .query(String.class)
                .optional()
                .orElse(null);
        if (value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------------------- the token

    /**
     * An opaque keyed hash of the tenant, branch, date and suggested amount (ADR-022 decision 11):
     * it reveals nothing about the amount, so every caller may hold it.
     */
    String suggestionToken(UUID branchId, LocalDate date, long suggestedMinor) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            String input = tenant.profile().id() + "|" + branchId + "|" + date + "|" + suggestedMinor;
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
