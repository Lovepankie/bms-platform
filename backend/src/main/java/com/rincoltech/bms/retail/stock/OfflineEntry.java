package com.rincoltech.bms.retail.stock;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * An entry kept on the phone while it had no network and sent later (issue #812, ADR-067 decision 13): an expense, savings,
 * banking, a withdrawal, Buy stock, Move stock, usage or damage. It is dated by when it was made, judged as an offline sale
 * is (ADR-067 decision 6): a time ahead of the server is now (no clash is kept for an entry); before
 * {@code online_as_of} (the server's time when the phone was last online) by more than {@link #CLOCK_BEHIND_SKEW}, the
 * phone clock was behind and the entry is dated when it reached the server. A date sent with the entry is not used: the
 * person did not choose it (issue #811, pending ADR-120), the phone did, when the entry was made.
 */
@Schema(
        name = "RetailOfflineEntry",
        description =
                "Sent only by an entry kept on the phone while offline (ADR-067 decision 13): it is dated by when it"
                        + " was made, and a date field sent with it is not used")
public record OfflineEntry(
        @NotNull
        @Schema(description = "When the entry was made on the phone", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant occurredAt,

        @Schema(description = "The server's time when the phone was last online, when it knows it")
        Instant onlineAsOf) {

    /** How far before {@code online_as_of} an entry may say it was made and still be dated as it says. */
    public static final Duration CLOCK_BEHIND_SKEW = Duration.ofMinutes(2);

    /** When the entry counts as made: as it says, or now when the phone clock was ahead or behind. */
    public Instant judged(Instant now) {
        // A phone clock ahead of the server cannot say it was online in the future: now at the latest.
        Instant asOf = onlineAsOf == null ? null : onlineAsOf.isAfter(now) ? now : onlineAsOf;
        if (asOf != null && occurredAt.isBefore(asOf.minus(CLOCK_BEHIND_SKEW))) {
            return now;
        }
        return occurredAt.isAfter(now) ? now : occurredAt;
    }

    /** The business day of an entry: the day it was made when it was kept offline, otherwise the date it asks for. */
    public static LocalDate day(OfflineEntry offline, LocalDate requested, Instant now, ZoneId zone) {
        return offline == null ? requested : LocalDate.ofInstant(offline.judged(now), zone);
    }
}
