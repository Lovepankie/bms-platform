package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;

/**
 * Migration V38 (ADR-033, issue #193): the replaced status check, entry-pair check, void-column
 * checks and update guard of {@code retail_transfers}, tested in the database as the application
 * role, so the rules hold whatever code writes. The guard allows exactly four transitions
 * (in_transit to received, in_transit to cancelled, completed to voided, received to voided) and
 * refuses every other change; the check constraints refuse the states that cannot exist. Rows
 * are arranged as the owner with a zero cost so no journal entry is needed. Fabricated data.
 */
class RetailTransferGuardIT extends IntegrationTest {

    static final String GUARD = "42501";
    static final String CHECK = "23514";
    static final List<String> STATUSES = List.of("completed", "in_transit", "received", "cancelled", "voided");
    /** The only transitions a transfer makes (spec section 10). */
    static final List<String> ALLOWED =
            List.of("in_transit>received", "in_transit>cancelled", "completed>voided", "received>voided");

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-guard", false, true);
        product = new RetailTestSupport(http, t).product("GUARD-1", 100, 150);
    }

    /** A transfer of cost zero (no entries) in the given status, arranged as the owner. */
    UUID transfer(String status) {
        UUID id = UUID.randomUUID();
        boolean voidColumns = status.equals("cancelled") || status.equals("voided");
        boolean receivedColumns = status.equals("received");
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_transfers (id, tenant_id, from_branch_id, to_branch_id, transfer_date, currency,
                            cost_total_minor, status, created_by, voided_at, voided_by, void_reason, received_at, received_by)
                        VALUES (?, ?, ?, ?, DATE '2026-01-15', 'UGX', 0, ?, ?,
                            CASE WHEN ? THEN now() END, CASE WHEN ? THEN gen_random_uuid() END,
                            CASE WHEN ? THEN 'Test reason' END,
                            CASE WHEN ? THEN now() END, CASE WHEN ? THEN gen_random_uuid() END)
                        """)
                .params(
                        id,
                        t.tenantId(),
                        t.headOffice(),
                        t.secondBranch(),
                        status,
                        UUID.randomUUID(),
                        voidColumns,
                        voidColumns,
                        voidColumns,
                        receivedColumns,
                        receivedColumns)
                .update();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_transfer_lines (id, tenant_id, transfer_id, line_no, product_id, qty,
                            unit_cost_minor, line_cost_minor)
                        VALUES (?, ?, ?, 1, ?, 5, 0, 0)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), id, product)
                .update();
        return id;
    }

    /** Runs one statement as bms_app with the tenant bound; returns the SQLSTATE of a refusal, null when it ran. */
    String asApp(String sql, Object... params) {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, t.tenantId().toString());
                bind.execute();
            }
            try (PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    s.setObject(i + 1, params[i]);
                }
                s.execute();
            }
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    String status(UUID id) {
        return TestDatabase.owner()
                .sql("SELECT status FROM retail_transfers WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    /**
     * The UPDATE that moves a transfer to a status, setting the columns that status needs. A receipt first records what
     * arrived on the lines, as the receipt does and V116 requires; that write is itself refused unless the transfer is in
     * transit, and the move is tried either way.
     */
    String moveTo(UUID id, String target) {
        return switch (target) {
            case "received" -> {
                asApp("UPDATE retail_transfer_lines SET received_qty = qty WHERE transfer_id = ?", id);
                yield asApp(
                        "UPDATE retail_transfers SET status = 'received', received_at = now(), received_by = ? WHERE id = ?",
                        UUID.randomUUID(),
                        id);
            }
            case "cancelled", "voided" ->
                asApp(
                        "UPDATE retail_transfers SET status = '" + target
                                + "', voided_at = now(), voided_by = ?, void_reason = 'Test reason' WHERE id = ?",
                        UUID.randomUUID(),
                        id);
            default -> asApp("UPDATE retail_transfers SET status = '" + target + "' WHERE id = ?", id);
        };
    }

    /** The guard allows exactly the four transitions and refuses every other change of status. */
    @Test
    void theGuardAllowsExactlyTheFourTransitions() {
        for (String from : STATUSES) {
            for (String to : STATUSES) {
                if (from.equals(to)) {
                    continue;
                }
                UUID id = transfer(from);
                String refused = moveTo(id, to);
                if (ALLOWED.contains(from + ">" + to)) {
                    assertThat(refused).as("%s to %s is allowed", from, to).isNull();
                    assertThat(status(id)).isEqualTo(to);
                } else {
                    assertThat(refused)
                            .as("%s to %s is refused by the guard", from, to)
                            .isEqualTo(GUARD);
                    assertThat(status(id)).isEqualTo(from);
                }
            }
        }
    }

    /** Every transition happens once: a transfer that has left a state never changes again. */
    @Test
    void aFinishedTransferNeverChangesAgain() {
        UUID received = transfer("in_transit");
        assertThat(moveTo(received, "received")).isNull();
        assertThat(asApp("UPDATE retail_transfers SET received_at = now() WHERE id = ?", received))
                .as("a second receipt")
                .isEqualTo(GUARD);
        assertThat(moveTo(received, "voided")).isNull();
        assertThat(asApp("UPDATE retail_transfers SET void_reason = 'Other' WHERE id = ?", received))
                .as("the void reason is written once")
                .isEqualTo(GUARD);
        UUID cancelled = transfer("in_transit");
        assertThat(moveTo(cancelled, "cancelled")).isNull();
        assertThat(asApp("UPDATE retail_transfers SET void_reason = 'Other' WHERE id = ?", cancelled))
                .isEqualTo(GUARD);
    }

    /** The transfer itself never changes: its branches, date, note, cost and creator are fixed. */
    @Test
    void theTransferItselfNeverChanges() {
        for (String column : List.of(
                "note = 'Changed'",
                "cost_total_minor = 5",
                "transfer_date = DATE '2026-02-01'",
                "from_branch_id = to_branch_id",
                "created_by = gen_random_uuid()")) {
            UUID id = transfer("in_transit");
            assertThat(asApp("UPDATE retail_transfers SET " + column + " WHERE id = ?", id))
                    .as(column)
                    .isEqualTo(GUARD);
        }
        UUID id = transfer("in_transit");
        assertThat(asApp(
                        "UPDATE retail_transfers SET status = 'received', received_at = now(), received_by = ?,"
                                + " note = 'Changed' WHERE id = ?",
                        UUID.randomUUID(),
                        id))
                .as("a receipt changes nothing else")
                .isEqualTo(GUARD);
        assertThat(asApp(
                        "UPDATE retail_transfers SET status = 'received', received_at = now(), received_by = ?,"
                                + " voided_at = now(), voided_by = ?, void_reason = 'x' WHERE id = ?",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        id))
                .as("a receipt sets the receipt columns only")
                .isEqualTo(GUARD);
        assertThat(status(id)).isEqualTo("in_transit");
        assertThat(asApp("UPDATE retail_transfers SET note = note WHERE id = ?", id))
                .as("a row left unchanged is not a change")
                .isNull();
    }

    /** The check constraints refuse states that cannot exist. */
    @Test
    void theChecksRefuseImpossibleStates() {
        String insert = """
                INSERT INTO retail_transfers (id, tenant_id, from_branch_id, to_branch_id, transfer_date, currency,
                    cost_total_minor, status, created_by%s)
                VALUES (gen_random_uuid(), ?, ?, ?, DATE '2026-01-15', 'UGX', %d, '%s', gen_random_uuid()%s)
                """;
        record Case(String what, String columns, long cost, String status, String values) {}
        List<Case> cases = List.of(
                new Case("an unknown status", "", 0, "bogus", ""),
                new Case("cancelled without a cancel time", "", 0, "cancelled", ""),
                new Case(
                        "voided with a blank void reason",
                        ", voided_at, voided_by, void_reason",
                        0,
                        "voided",
                        ", now(), gen_random_uuid(), ' '"),
                new Case("received without a receipt time", "", 0, "received", ""),
                new Case(
                        "completed with a receipt time",
                        ", received_at, received_by",
                        0,
                        "completed",
                        ", now(), gen_random_uuid()"),
                new Case("a receipt time without a receiver", ", received_at", 0, "received", ", now()"),
                new Case("in transit with a cost and no source entry", "", 100, "in_transit", ""),
                new Case(
                        "received with a cost and no source entry",
                        ", received_at, received_by",
                        100,
                        "received",
                        ", now(), gen_random_uuid()"));
        for (Case c : cases) {
            String sql = insert.formatted(c.columns(), c.cost(), c.status(), c.values());
            String state = null;
            try {
                TestDatabase.owner()
                        .sql(sql)
                        .params(t.tenantId(), t.headOffice(), t.secondBranch())
                        .update();
            } catch (org.springframework.dao.DataAccessException e) {
                state = ((SQLException) rootSql(e)).getSQLState();
            }
            assertThat(state).as(c.what()).isEqualTo(CHECK);
        }
        // The same inserts with the facts in order are accepted: a zero-cost dispatch, a receipt, a cancellation.
        assertThat(transfer("in_transit")).isNotNull();
        assertThat(transfer("received")).isNotNull();
        assertThat(transfer("cancelled")).isNotNull();
    }

    private static Throwable rootSql(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** A line records what arrived once, and only while its transfer is in transit; nothing else changes. */
    @Test
    void aLineRecordsWhatArrivedOnceAndOnlyWhileInTransit() {
        UUID open = transfer("in_transit");
        assertThat(asApp("UPDATE retail_transfer_lines SET received_qty = 3 WHERE transfer_id = ?", open))
                .isNull();
        assertThat(asApp("UPDATE retail_transfer_lines SET received_qty = 4 WHERE transfer_id = ?", open))
                .as("once")
                .isEqualTo(GUARD);
        assertThat(asApp("UPDATE retail_transfer_lines SET qty = 9 WHERE transfer_id = ?", open))
                .as("the dispatched quantity is fixed")
                .isEqualTo(GUARD);
        assertThat(asApp("DELETE FROM retail_transfer_lines WHERE transfer_id = ?", open))
                .as("append-only for delete")
                .isEqualTo(GUARD);
        UUID done = transfer("completed");
        assertThat(asApp("UPDATE retail_transfer_lines SET received_qty = 5 WHERE transfer_id = ?", done))
                .as("a completed transfer's lines are final")
                .isEqualTo(GUARD);
        UUID over = transfer("in_transit");
        assertThat(asApp("UPDATE retail_transfer_lines SET received_qty = 6 WHERE transfer_id = ?", over))
                .as("more than was dispatched")
                .isEqualTo(CHECK);
        UUID blank = transfer("in_transit");
        assertThat(asApp("UPDATE retail_transfer_lines SET received_qty = NULL WHERE transfer_id = ?", blank))
                .as("clearing a count")
                .isEqualTo(GUARD);
    }

    /** Row-level security covers the new columns: another tenant's transfer cannot be moved. */
    @Test
    void anotherTenantCannotMoveATransfer() {
        UUID id = transfer("in_transit");
        TestDatabase.Fixture other = TestDatabase.tenant("retail-guard-other", false, true);
        String state;
        int rows = 0;
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, other.tenantId().toString());
                bind.execute();
            }
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE retail_transfers SET status = 'cancelled', voided_at = now(), voided_by = ?, void_reason = 'x' WHERE id = ?")) {
                s.setObject(1, UUID.randomUUID());
                s.setObject(2, id);
                rows = s.executeUpdate();
            }
            state = null;
        } catch (SQLException e) {
            state = e.getSQLState();
        }
        assertThat(state).isNull();
        assertThat(rows).isZero();
        assertThat(status(id)).isEqualTo("in_transit");
    }
}
