package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * An entry kept on the phone while it had no network and sent later (issue #812, ADR-067 decision 13): an expense, savings,
 * banking, a withdrawal, Buy stock, Move stock, usage or damage. It is dated by when it was made, judged as an offline sale
 * is (ADR-067 decisions 5 and 6), with the lead's decisions on the review of #839:
 *
 * <ul>
 *   <li>{@code online_as_of} (the server's time when the phone was last online) is required, and an {@code occurred_at}
 *       more than {@link #CLOCK_BEHIND_SKEW} before it (a phone clock behind) is refused ({@code offline_clock_behind});
 *   <li>a time ahead of the server is now;
 *   <li>a time older than {@link #MAX_AGE}, the 7 days an offline sale's key is answered for (decision 5), is the owner's
 *       to keep: it needs {@link #BACKDATE} at every branch the entry touches (the backdate permission of issue #811, pending
 *       ADR-120) and is otherwise refused ({@code offline_entry_too_old});
 *   <li>a closed accounting period stays refused by the ledger ({@code period_closed}), as for an offline sale: retail has no
 *       daily close.
 * </ul>
 *
 * A date sent with the entry is not used: the person did not choose it, the phone did, when the entry was made.
 */
@Schema(
        name = "RetailOfflineEntry",
        description =
                "Sent only by an entry kept on the phone while offline (ADR-067 decision 13): it is dated by when it"
                        + " was made, and a date field sent with it is not used. Made more than 2 minutes before"
                        + " online_as_of is refused (offline_clock_behind); made more than 7 days ago needs"
                        + " retail.entry.backdate at every branch it touches, else it is refused (offline_entry_too_old)")
public record OfflineEntry(
        @NotNull
        @Schema(description = "When the entry was made on the phone", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant occurredAt,

        // Review of #839: a phone never online since this app was installed cannot say it; the refusal tells its maker
        // what to do in plain words, as the other offline refusals do.
        @NotNull(
                message = "The phone had not been online with this app before this entry was made, so its day is not"
                        + " known. Fix it on the phone to save it as an entry of today.")
        @Schema(
                description = "The server's time when the phone was last online",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant onlineAsOf) {

    /** How far before {@code online_as_of} an entry may say it was made (as an offline sale's prices_as_of, decision 6). */
    public static final Duration CLOCK_BEHIND_SKEW = Duration.ofMinutes(2);

    /** The oldest an entry kept offline may be without the owner's permission: the 7 days of ADR-067 decision 5. */
    public static final Duration MAX_AGE = Duration.ofDays(7);

    /** Date an entry earlier than the app would (issue #811, ADR-120); the owner's. */
    public static final String BACKDATE = EntryDates.BACKDATE;

    /** When the entry counts as made; refused for a phone clock behind, or when too old for the caller. */
    public Instant judged(Instant now, Collection<UUID> branches) {
        // A phone clock ahead of the server cannot say it was online in the future: now at the latest.
        Instant asOf = onlineAsOf.isAfter(now) ? now : onlineAsOf;
        if (occurredAt.isBefore(asOf.minus(CLOCK_BEHIND_SKEW))) {
            throw refused(
                    "offline_clock_behind",
                    "The phone's clock was behind when this entry was made, so its day is not known. Fix it on the"
                            + " phone to save it as an entry of today.");
        }
        Instant at = occurredAt.isAfter(now) ? now : occurredAt;
        if (at.isBefore(now.minus(MAX_AGE))) {
            Principal principal = CurrentPrincipal.require();
            if (branches.isEmpty() || !branches.stream().allMatch(b -> principal.may(BACKDATE, b))) {
                throw refused(
                        "offline_entry_too_old",
                        "This entry was kept on the phone for more than 7 days. Only the owner can save it on its own"
                                + " day. Fix it on the phone to save it as an entry of today.");
            }
        }
        return at;
    }

    /** The business day of an entry: the day it was made when it was kept offline, otherwise the date it asks for. */
    public static LocalDate day(
            OfflineEntry offline, LocalDate requested, Instant now, ZoneId zone, Collection<UUID> branches) {
        return offline == null ? requested : LocalDate.ofInstant(offline.judged(now, branches), zone);
    }

    /**
     * The offline block a request is dated by (the lead's decision on the third review of #839): none when the request names
     * an earlier day the owner chose online with its reason (ADR-120), which the server judges as it was sent online, the
     * backdate permission included; otherwise the block as sent. The phone sends the block with every entry it kept.
     */
    public static OfflineEntry unlessChosen(OfflineEntry offline, String dateReason) {
        return dateReason != null && !dateReason.isBlank() ? null : offline;
    }

    private static ApiException refused(String code, String words) {
        return ApiException.validation(List.of(new FieldProblem("offline.occurred_at", code, words)));
    }
}
