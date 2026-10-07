package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which guided tours a user has finished or chosen not to see again (issue #19, ADR-025), kept
 * in {@code users.preferences -> 'tours'} so a first-run tour follows the user across devices.
 * The tours themselves are data in the PWA; the server only remembers a tour id, the tour version
 * the user saw and how it ended. A user writes only their own row: the id is the principal's.
 */
@Service
class TourProgress {

    /** A tour id as the PWA registry names it: lower case words joined by hyphens. */
    static final String TOUR_ID = "^[a-z0-9]+(-[a-z0-9]+)*$";

    /** Enough for every tour the PWA can register; a bound so the document cannot grow unchecked. */
    static final int MAX_TOURS = 64;

    enum Outcome {
        completed,
        dismissed
    }

    @Schema(name = "TourState", description = "How the user left a guided tour, and its version then")
    record TourState(Outcome status, int version, Instant at) {}

    @Schema(name = "TourStateRequest")
    record TourStateRequest(
            @NotNull @Schema(description = "completed: finished; dismissed: chose not to see it again")
            Outcome status,

            @Min(1) @Max(1000) @Schema(description = "The tour version the user saw; a higher one shows again")
            int version) {}

    private final JdbcClient jdbc;
    private final BusinessClock clock;

    TourProgress(JdbcClient jdbc, BusinessClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Map<String, TourState> of(UUID userId) {
        Map<String, TourState> tours = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT t.key, t.value ->> 'status' AS status, (t.value ->> 'version')::int AS version,
                               (t.value ->> 'at')::timestamptz AS at
                          FROM users u, jsonb_each(coalesce(u.preferences -> 'tours', '{}'::jsonb)) t
                         WHERE u.id = ?
                         ORDER BY t.key
                        """)
                .param(userId)
                .query((rs, n) -> tours.put(
                        rs.getString("key"),
                        new TourState(
                                Outcome.valueOf(rs.getString("status")),
                                rs.getInt("version"),
                                SessionStore.instant(rs.getTimestamp("at")))))
                .list();
        return tours;
    }

    @Transactional
    void record(UUID userId, String tourId, TourStateRequest request) {
        if (tourId == null || tourId.length() > 64 || !tourId.matches(TOUR_ID)) {
            throw ApiException.rule("tour_id_invalid", "A tour id is lower case words joined by hyphens.");
        }
        int known = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE t.key <> ?)
                          FROM users u, jsonb_each(coalesce(u.preferences -> 'tours', '{}'::jsonb)) t
                         WHERE u.id = ?
                        """).params(tourId, userId).query(Integer.class).single();
        if (known >= MAX_TOURS) {
            throw ApiException.rule("too_many_tours", "No more tours can be remembered for this user.");
        }
        int updated = jdbc.sql("""
                        UPDATE users
                           SET preferences = jsonb_set(preferences, '{tours}',
                                   coalesce(preferences -> 'tours', '{}'::jsonb)
                                   || jsonb_build_object(CAST(? AS text),
                                          jsonb_build_object('status', CAST(? AS text), 'version', CAST(? AS integer),
                                                         'at', CAST(? AS text))))
                         WHERE id = ?
                        """)
                .params(
                        tourId,
                        request.status().name(),
                        request.version(),
                        clock.now().toString(),
                        userId)
                .update();
        if (updated != 1) {
            throw AuthFlow.unauthenticated();
        }
    }
}
