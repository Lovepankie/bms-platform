package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The app sets the date of every entry (issue #811, ADR-120), as a sale already does: an expense, savings, banking, a
 * withdrawal, an advance and its repayment, Buy stock and Move stock are dated today on the business clock when no date is
 * sent. A date before today is taken only from a holder of {@link #BACKDATE} at every branch the entry touches (403
 * {@code date_not_today} otherwise), with a reason of {@link #MIN_REASON} to {@link #MAX_REASON} characters (422 on
 * {@code date_reason}), and is audited as {@code retail.entry.backdated}. A future date stays each service's own 422, so
 * this class never reads it. Offline sales are not entries of this kind: they carry the time they were made (ADR-067).
 */
@Component
public class EntryDates {

    /** Date an entry earlier than today (the tenant admin's; ADR-120). */
    public static final String BACKDATE = "retail.entry.backdate";

    public static final String REASON_FIELD = "date_reason";
    public static final int MIN_REASON = 3;
    public static final int MAX_REASON = 300;

    /** An entry dated before today by a holder of {@link #BACKDATE}, with the reason they gave. */
    public record Backdate(LocalDate date, LocalDate today, String reason) {}

    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    EntryDates(CurrentTenant tenant, BusinessClock clock, AuditLog audit) {
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    public LocalDate today() {
        return clock.today(tenant.profile().timezone());
    }

    /**
     * The gate on a requested date: {@code null} when the entry is dated today (nothing sent, today sent, or a future date,
     * which the caller refuses itself), otherwise the checked backdate. The reason is read only for a date before today.
     */
    public Backdate check(LocalDate requested, String field, String reason, Collection<UUID> branches) {
        LocalDate today = today();
        if (requested == null || !requested.isBefore(today)) {
            return null;
        }
        Principal principal = CurrentPrincipal.require();
        if (branches.isEmpty() || !branches.stream().allMatch(b -> principal.may(BACKDATE, b))) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "date_not_today",
                    "Date not allowed",
                    "The app dates this entry today. Only the owner can choose an earlier date.",
                    List.of(new FieldProblem(field, "date_not_today", "Only the owner can choose an earlier date.")));
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_REASON || trimmed.length() > MAX_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    REASON_FIELD, "reason_required", "Say why this entry has an earlier date (3 to 300 characters).")));
        }
        return new Backdate(requested, today, trimmed);
    }

    /** As {@link #check(LocalDate, String, String, Collection)} for an entry of one branch. */
    public Backdate check(LocalDate requested, String field, String reason, UUID branch) {
        return check(requested, field, reason, List.of(branch));
    }

    /**
     * An entry approved later (ADR-091): the maker's backdate was checked when they asked, so it is kept without asking the
     * checker for the permission again; {@code null} when the maker gave no reason (the entry was dated on its day).
     */
    public Backdate approved(LocalDate date, String reason) {
        String trimmed = reason == null ? null : reason.trim();
        if (date == null || trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        return new Backdate(date, today(), trimmed);
    }

    /**
     * The audit row of an entry kept on the phone offline and dated by the phone (issue #812, ADR-067 decision 13), in the
     * transaction that wrote it: {@code retail.entry.recorded_offline} with the day, the time the phone said it was made and
     * when it was last online, so an owner can tell a phone-dated entry from one dated by the server. Nothing when not offline.
     */
    public void auditOffline(OfflineEntry offline, LocalDate day, String entityType, UUID entityId, UUID branchId) {
        if (offline == null) {
            return;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("entry", entityType);
        after.put("date", day.toString());
        after.put("occurred_at", offline.occurredAt().toString());
        after.put("online_as_of", offline.onlineAsOf().toString());
        after.put("entered_on", today().toString());
        audit.record(AuditLog.Entry.created("retail.entry.recorded_offline", entityType, entityId, branchId, after));
    }

    /** The audit row of a backdated entry, in the transaction that wrote it; nothing for an entry dated today. */
    public void audit(Backdate backdate, String entityType, UUID entityId, UUID branchId) {
        if (backdate == null) {
            return;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("entry", entityType);
        after.put("date", backdate.date().toString());
        after.put("entered_on", backdate.today().toString());
        after.put("reason", backdate.reason());
        audit.record(AuditLog.Entry.created("retail.entry.backdated", entityType, entityId, branchId, after));
    }
}
