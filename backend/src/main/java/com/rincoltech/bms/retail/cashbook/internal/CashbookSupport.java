package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.VoidRequest;
import com.rincoltech.bms.retail.stock.ClosedTills;
import com.rincoltech.bms.retail.stock.EntryDates;
import com.rincoltech.bms.retail.stock.OfflineEntry;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
class CashbookSupport implements ClosedTills {

    static final String READ = "retail.cashbook.read";
    static final String SAVINGS_RECORD = "retail.savings.record";
    static final String SAVINGS_OVERWRITE = "retail.savings.overwrite";
    /** ADR-115, issue #799: with the owner's savings switch on, a caller who may sell at a branch may record its savings. */
    static final String SALE_CREATE = "retail.sale.create";

    static final String BANKING_RECORD = "retail.banking.record";
    static final String EXPENSE_RECORD = "retail.expense.record";
    static final String EXPENSE_MANAGE = "retail.expense.manage";
    static final String WITHDRAWAL_RECORD = "retail.withdrawal.record";
    static final String ADVANCE_CREATE = "retail.advance.create";
    static final String ADVANCE_REPAY = "retail.advance.repay";
    static final String VOID = "retail.cashbook.void";
    static final String PROFIT_READ = "retail.profit.read";
    /** ADR-055 (the owner's decision of 21 October 2026): closing the till and doing the banking, given per person. */
    static final String TILL = "retail.till.close";

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int DEFAULT_REPORT_DAYS = 30;
    static final int MAX_REPORT_DAYS = 366;

    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final JdbcClient jdbc;
    private final EntryDates entryDates;
    private final byte[] tokenKey;

    CashbookSupport(
            CurrentTenant tenant,
            BusinessClock clock,
            JdbcClient jdbc,
            EntryDates entryDates,
            @Value("${bms.auth.data-key:}") String dataKey) {
        this.tenant = tenant;
        this.clock = clock;
        this.jdbc = jdbc;
        this.entryDates = entryDates;
        byte[] key;
        if (dataKey == null || dataKey.isBlank()) {
            // The dev and test profiles may leave the data key blank; a token then lives until a restart.
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else {
            key = purposeKey(dataKey);
        }
        this.tokenKey = key;
    }

    /** HMAC-SHA256 of a fixed purpose label under the data key: the data key itself never keys the token. */
    private static byte[] purposeKey(String dataKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(dataKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("bms.retail.cashbook.savings-suggestion.v1".getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    LocalDate today() {
        return clock.today(tenant.profile().timezone());
    }

    /** The business date of an instant in the tenant's zone, so a void and its reversal share one clock reading. */
    LocalDate dayOf(Instant at) {
        return at.atZone(tenant.profile().timezone()).toLocalDate();
    }

    Instant now() {
        return clock.now();
    }

    ZoneId zone() {
        return tenant.profile().timezone();
    }

    String currency() {
        return tenant.profile().currency();
    }

    /**
     * The date of a new entry that may have been kept on the phone offline (ADR-067 decision 13): offline, the day it was
     * made, judged as an offline sale is, with no backdate of ADR-120 to check, since the person did not choose it (past 7
     * days {@link OfflineEntry} asks for the owner's backdate permission itself); otherwise
     * {@link #entryDate(LocalDate, String, String, UUID)}.
     */
    Dated entryDate(LocalDate requested, String field, String reason, UUID branch, OfflineEntry offline) {
        if (offline == null) {
            return entryDate(requested, field, reason, branch);
        }
        return new Dated(offlineDay(offline, requested, field, branch), null, offline);
    }

    /**
     * The day of a cash book entry kept on the phone offline (ADR-067 decision 13): the day it was made, judged as an offline
     * sale is, and never a closed day for anyone but the owner (review of #839). Retail has no till-close record: "Can close
     * the till and do the banking" closes a day by banking it, so a day before today on which the branch's cash was banked
     * (an active banking record) is closed, and an entry landing on it needs {@code retail.entry.backdate} at the branch.
     */
    LocalDate offlineDay(OfflineEntry offline, LocalDate requested, String field, UUID branch) {
        LocalDate day = businessDate(OfflineEntry.day(offline, requested, now(), zone(), List.of(branch)), field);
        requireOpen(offline, day, today(), List.of(branch));
        return day;
    }

    /** The branch's cash of that day was banked: an active banking record on the day. */
    @Override
    public boolean closed(UUID branch, LocalDate day) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_cash_bankings
                                        WHERE branch_id = ? AND business_date = ? AND voided_at IS NULL)
                        """).params(branch, day).query(Boolean.class).single();
    }

    /**
     * When a record happened: for one kept offline, the time it was made, judged (a time sent with it is not used: the phone
     * dated it, review of #839); otherwise {@code at} as sent, or now.
     */
    Instant entryTime(Instant at, OfflineEntry offline, UUID branch) {
        if (offline != null) {
            return offline.judged(now(), List.of(branch));
        }
        return at == null ? now() : at;
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

    /**
     * The date of an entry and, when it is before today, the owner's checked backdate (ADR-120); else none. {@code offline}:
     * the entry was kept on the phone and dated by it (ADR-067 decision 13), audited as such.
     */
    record Dated(LocalDate date, EntryDates.Backdate backdate, OfflineEntry offline) {
        Dated(LocalDate date, EntryDates.Backdate backdate) {
            this(date, backdate, null);
        }
    }

    /**
     * The business date of a new entry (ADR-120): today when absent, never in the future, and before today only from a
     * holder of {@code retail.entry.backdate} at the branch, with a reason.
     */
    Dated entryDate(LocalDate requested, String field, String reason, UUID branch) {
        LocalDate date = businessDate(requested, field);
        return new Dated(date, entryDates.check(date, field, reason, branch));
    }

    /**
     * ADR-120: the time of a banking or a withdrawal is part of its date. A time on an earlier day is gated like the date,
     * and audited when the business date itself is today (review of #831), so either earlier day leaves its audit row.
     */
    Dated withTime(Dated dated, Instant at, String field, String reason, UUID branch) {
        EntryDates.Backdate time = entryDates.check(dayOf(at), field, reason, branch);
        return dated.backdate() != null || time == null ? dated : new Dated(dated.date(), time);
    }

    /** The {@code retail.entry.backdated} audit row of an entry dated before today; nothing otherwise. */
    void auditBackdate(Dated dated, String entityType, UUID id, UUID branch) {
        entryDates.audit(dated.backdate(), entityType, id, branch);
        entryDates.auditOffline(dated.offline(), dated.date(), entityType, id, branch);
    }

    EntryDates entryDates() {
        return entryDates;
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

    /**
     * ADR-055: the till's figures (the daily cash summary, the expected banking, the savings suggestion) and closing the till
     * (savings and banking recorded) are for the people the owner gave "Can close the till and do the banking" (the role
     * {@code retail_till}) and the administrators; a seller is refused with 403. The branch is checked by the route's own
     * permission, which the same role carries for the same branches.
     */
    static void requireTill() {
        if (!CurrentPrincipal.require().hasPermission(TILL)) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Closing the till and the banking are for the people the owner chose.");
        }
    }

    static ApiException notFound() {
        return ApiException.notFound();
    }

    static ApiException alreadyVoided() {
        return new ApiException(
                HttpStatus.CONFLICT, "cash_record_voided", "Record voided", "This record has been voided already.");
    }

    static ApiException historicalRecord() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "historical_record",
                "Imported record",
                "Imported records cannot be voided; correct them with a new record.");
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

    /**
     * Issue #768, ADR-115: {@code salespeople_see_savings_amount}, set by the Owner in Business set-up, and since when it is on.
     * When on, a caller without {@code retail.profit.read} sees the suggested savings amount and may change it, never the
     * profit, the expenses or the cap; since issue #827 every record of any month shows its amount to such a caller who may record
     * savings or sell at its branch ({@code SavingsService.amountShown}); {@code since} only records when it was turned on.
     */
    record SalespeopleSavings(boolean on, Instant since) {
        static final SalespeopleSavings OFF = new SalespeopleSavings(false, null);

        /**
         * Issue #799: the sales staff routes answer only while the setting is on; off, they are refused as if the caller held
         * nothing more than the right to sell, which is what the till's routes already say to a seller.
         */
        void requireOnForSalesStaff() {
            if (!on) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "permission_denied",
                        "Permission denied",
                        "You do not have permission to perform this action.");
            }
        }
    }

    /** Read once per request, so one answer never mixes two states of the setting. */
    SalespeopleSavings salespeopleSavings() {
        return jdbc.sql(
                        "SELECT settings ->> 'salespeople_see_savings_amount', settings ->> 'salespeople_see_savings_amount_since'"
                                + " FROM tenant_settings")
                .query((rs, n) -> "true".equals(rs.getString(1))
                        ? new SalespeopleSavings(true, rs.getString(2) == null ? null : Instant.parse(rs.getString(2)))
                        : SalespeopleSavings.OFF)
                .optional()
                .orElse(SalespeopleSavings.OFF);
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

    /** Constant-time comparison of a presented token with the one for the current suggestion. */
    boolean tokenMatches(UUID branchId, LocalDate date, long suggestedMinor, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                suggestionToken(branchId, date, suggestedMinor).getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
