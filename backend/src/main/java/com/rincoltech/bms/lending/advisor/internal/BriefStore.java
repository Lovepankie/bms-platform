package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AdvisorAnswer.Point;
import com.rincoltech.bms.lending.advisor.internal.BriefBuilder.Brief;
import com.rincoltech.bms.lending.advisor.internal.BriefBuilder.Chip;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored daily brief (table {@code lending_ai_briefs}, V55): one row per tenant and business date over all branches,
 * inserted once. It holds the text our renderer made, never anything the model wrote.
 */
@Repository
class BriefStore {

    /** A stored brief and when it was made. */
    record Stored(Brief brief, Instant generatedAt) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    BriefStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    boolean exists(LocalDate date) {
        return jdbc.sql("SELECT count(*) FROM lending_ai_briefs WHERE brief_date = ?")
                        .param(Date.valueOf(date))
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Transactional(readOnly = true)
    Optional<Stored> find(LocalDate date) {
        return jdbc.sql("""
                        SELECT source, reason, summary, points, chips, facts_as_of, generated_at
                          FROM lending_ai_briefs WHERE brief_date = ?
                        """)
                .param(Date.valueOf(date))
                .query((rs, n) -> new Stored(
                        new Brief(
                                rs.getString("source"),
                                rs.getString("reason"),
                                rs.getString("summary"),
                                JSON.readValue(rs.getString("points"), new TypeReference<List<Point>>() {}),
                                JSON.readValue(rs.getString("chips"), new TypeReference<List<Chip>>() {}),
                                rs.getDate("facts_as_of").toLocalDate()),
                        rs.getTimestamp("generated_at").toInstant()))
                .optional();
    }

    /** Stores the day once; returns false when a brief for the date exists already (a rerun changes nothing). */
    @Transactional
    boolean insertOnce(LocalDate date, Brief brief, Instant generatedAt, String model, UUID callId) {
        return jdbc.sql("""
                        INSERT INTO lending_ai_briefs
                            (tenant_id, brief_date, source, reason, summary, points, chips, facts_as_of,
                             generated_at, model, call_id)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, brief_date) DO NOTHING
                        """)
                        .param(Date.valueOf(date))
                        .param(brief.source())
                        .param(brief.reason())
                        .param(brief.summary())
                        .param(JSON.writeValueAsString(brief.points()))
                        .param(JSON.writeValueAsString(brief.chips()))
                        .param(Date.valueOf(brief.factsAsOf()))
                        .param(Timestamp.from(generatedAt))
                        .param(model)
                        .param(callId)
                        .update()
                == 1;
    }
}
