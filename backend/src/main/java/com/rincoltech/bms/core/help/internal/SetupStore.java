package com.rincoltech.bms.core.help.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The setup helper's draft log (migration V112, ADR-102): one row per draft, written before the work under the business's
 * advisory lock, so drafts sent at once are counted one after the other, then finished with what happened. Counts, names
 * and closed words only: never the list, the file or the draft. Each method runs in a short transaction of its own, so no
 * connection is held while a provider is asked.
 */
@Component
class SetupStore {

    /** Why a draft could not start: the person's hour, or the business's day. */
    enum Limit {
        PERSON,
        BUSINESS
    }

    /** A started draft, or the limit that stopped it. */
    record Start(UUID id, Limit limit) {}

    /** What a draft came to, for its row. */
    record Finish(
            String outcome,
            String provider,
            String model,
            List<String> failures,
            int rows,
            int inputTokens,
            int outputTokens,
            long costMicro,
            long latencyMs) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate writes;

    SetupStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.writes = new TransactionTemplate(transactions);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Writes the row of a draft when the person has asked for fewer than {@code personLimit} since {@code hourFrom} and the
     * business has sent fewer than {@code businessLimit} to the providers since {@code dayFrom}. Past the person's limit
     * nothing is written; past the business's the row is written as {@code daily_cap}, so the log shows it.
     */
    Start start(
            String kind,
            String source,
            UUID userId,
            Instant now,
            Instant hourFrom,
            Instant dayFrom,
            long personLimit,
            long businessLimit,
            String currency) {
        return writes.execute(tx -> {
            jdbc.sql(
                            "SELECT pg_advisory_xact_lock(hashtextextended('help_setup:' || current_setting('app.tenant_id'), 0))")
                    .query()
                    .listOfRows();
            long mine = jdbc.sql("SELECT count(*) FROM help_setup_drafts WHERE user_id = ? AND asked_at >= ?")
                    .params(userId, Timestamp.from(hourFrom))
                    .query(Long.class)
                    .single();
            if (mine >= personLimit) {
                return new Start(null, Limit.PERSON);
            }
            long sent = jdbc.sql("""
                            SELECT count(*) FROM help_setup_drafts
                             WHERE asked_at >= ? AND outcome IN ('started', 'drafted', 'all_failed')
                            """)
                    .param(Timestamp.from(dayFrom))
                    .query(Long.class)
                    .single();
            boolean capped = sent >= businessLimit;
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO help_setup_drafts (id, tenant_id, user_id, asked_at, kind, source, outcome,
                                                           cost_currency)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(id, userId, Timestamp.from(now), kind, source, capped ? "daily_cap" : "started", currency)
                    .update();
            return new Start(id, capped ? Limit.BUSINESS : null);
        });
    }

    void finish(UUID id, Finish f) {
        writes.executeWithoutResult(tx -> jdbc.sql("""
                        UPDATE help_setup_drafts
                           SET outcome = ?, provider = ?, model = ?, failures = ?, rows_drafted = ?, input_tokens = ?,
                               output_tokens = ?, cost_micro = ?, latency_ms = ?
                         WHERE id = ? AND outcome = 'started'
                        """)
                .params(
                        f.outcome(),
                        f.provider(),
                        f.model(),
                        f.failures().toArray(String[]::new),
                        f.rows(),
                        f.inputTokens(),
                        f.outputTokens(),
                        f.costMicro(),
                        (int) Math.min(Integer.MAX_VALUE, f.latencyMs()),
                        id)
                .update());
    }
}
