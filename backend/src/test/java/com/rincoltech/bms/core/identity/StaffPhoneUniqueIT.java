package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * One invited or active staff user per phone in a business (issue #323, ADR-085, FR-IAM-01): the V98 index refuses the
 * second of two invitations sent at once with 409 {@code duplicate_phone}, and the migration stops, changing nothing, when a
 * business already has a shared phone. All users and phones are fabricated.
 */
class StaffPhoneUniqueIT extends IntegrationTest {

    static final int PARALLEL = 6;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    Session adminSession;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("phone", true);
        Api.requireTwoStepForAdmins(t);
        api = Api.tenant(http, t.slug());
        String adminEmail = Api.email("admin");
        UUID admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
    }

    Map<String, Object> invite(String phone) {
        Map<String, Object> role = new java.util.HashMap<>();
        role.put("role_key", "cashier");
        role.put("branch_id", t.headOffice());
        return Map.of("full_name", "Test Staff Invitee", "phone", phone, "roles", List.of(role));
    }

    long staffWithPhone(String e164) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM users WHERE tenant_id = ? AND kind = 'staff' AND phone_e164 = ?")
                .params(t.tenantId(), e164)
                .query(Long.class)
                .single();
    }

    /** Issue #323: the same phone invited N times at once, in two spellings, makes one staff user; the rest get 409. */
    @Test
    void concurrentInvitationsWithOnePhoneMakeOneStaffUser() throws Exception {
        Queue<JsonNode> refusals = new ConcurrentLinkedQueue<>();
        AtomicInteger task = new AtomicInteger();
        List<HttpStatusCode> statuses = Api.race(PARALLEL, () -> {
            String spelling = task.getAndIncrement() % 2 == 0 ? "0700000041" : "+256 700 000041";
            ResponseEntity<JsonNode> r = api.post("/api/v1/users", invite(spelling), adminSession.accessToken());
            if (r.getStatusCode().value() == 409) {
                refusals.add(r.getBody());
            }
            return r;
        });

        assertThat(Api.count(statuses, HttpStatus.CREATED))
                .as("statuses %s", statuses)
                .isEqualTo(1);
        assertThat(Api.count(statuses, HttpStatus.CONFLICT))
                .as("statuses %s", statuses)
                .isEqualTo(PARALLEL - 1);
        assertThat(refusals).allSatisfy(b -> {
            assertThat(b.get("code").asString()).isEqualTo("duplicate_phone");
            assertThat(b.get("detail").asString()).isEqualTo("A staff user has this phone.");
        });
        assertThat(staffWithPhone("+256700000041")).isEqualTo(1);
    }

    /**
     * Issue #323: the race the application check cannot see. Another transaction has inserted the phone but not committed,
     * so the invitation passes the check, waits on the index, and once the other commits is refused with the same 409.
     */
    @Test
    void anInvitationThatPassedTheCheckIsRefusedByTheIndex() throws Exception {
        try (Connection other = TestDatabase.ownerDataSource().getConnection()) {
            other.setAutoCommit(false);
            try (var insert =
                    other.prepareStatement("INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status)"
                            + " VALUES (?, ?, 'staff', 'Test Staff Other Tab', '+256700000042', 'invited')")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, t.tenantId());
                insert.executeUpdate();
            }
            String xid;
            try (Statement s = other.createStatement();
                    var rs = s.executeQuery("SELECT pg_current_xact_id()::text")) {
                rs.next();
                xid = rs.getString(1);
            }
            CompletableFuture<ResponseEntity<JsonNode>> invited = CompletableFuture.supplyAsync(
                    () -> api.post("/api/v1/users", invite("0700000042"), adminSession.accessToken()));
            awaitAnInsertWaitingOn(xid);
            other.commit();

            ResponseEntity<JsonNode> r = invited.get(30, TimeUnit.SECONDS);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("code").asString()).isEqualTo("duplicate_phone");
        }
        assertThat(staffWithPhone("+256700000042")).isEqualTo(1);
    }

    /** A deactivated user keeps the phone out of the index, and the application still refuses it (unchanged). */
    @Test
    void aPhoneOfADeactivatedUserIsStillRefusedByTheApplication() {
        insertStaff(t.tenantId(), "+256700000043", "deactivated");

        ResponseEntity<JsonNode> r = api.post("/api/v1/users", invite("0700000043"), adminSession.accessToken());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("duplicate_phone");
    }

    /**
     * Issue #323: V98 run again on a database that already has two invited or active staff users with one phone stops with a
     * message naming the business and how many phones are shared, never the phone, and changes nothing. A deactivated user
     * sharing the phone does not stop it. Run in one transaction that is rolled back, so the shared database is untouched.
     */
    @Test
    void theMigrationStopsOnExistingDuplicatesAndNamesTheBusinessNotThePhone() throws Exception {
        String migration = new ClassPathResource("db/migration/V98__users_staff_phone_unique.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        UUID first = insertStaff(t.tenantId(), "+256700000044", "active");
        UUID second;
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '5s'");
                s.execute("DROP INDEX users_staff_phone");
                second = UUID.randomUUID();
                s.execute("INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status) VALUES ('" + second
                        + "', '" + t.tenantId()
                        + "', 'staff', 'Test Staff Deactivated', '+256700000044', 'deactivated')");
                s.execute("SAVEPOINT only_deactivated");
                s.execute(migration);
                s.execute("ROLLBACK TO SAVEPOINT only_deactivated");
                s.execute("UPDATE users SET status = 'invited' WHERE id = '" + second + "'");

                assertThatThrownBy(() -> s.execute(migration))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining(
                                "V98: 1 phone number(s) are each held by more than one invited or active staff user")
                        .hasMessageContaining(t.tenantId().toString())
                        .hasMessageContaining("Nothing was changed")
                        .hasMessageNotContaining("700000044")
                        .satisfies(e ->
                                assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
            } finally {
                c.rollback();
            }
        }
        assertThat(staffWithPhone("+256700000044")).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM users WHERE id = ?")
                        .param(first)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM pg_indexes WHERE indexname = 'users_staff_phone'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    UUID insertStaff(UUID tenantId, String e164, String status) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status)"
                        + " VALUES (?, ?, 'staff', 'Test Staff Existing', ?, ?)")
                .params(id, tenantId, e164, status)
                .update();
        return id;
    }

    /** Waits until another backend waits for the transaction {@code xid} to end, as an insert on a unique key does. */
    static void awaitAnInsertWaitingOn(String xid) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            long waiting = TestDatabase.owner()
                    .sql("SELECT count(*) FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted"
                            + " AND transactionid::text = ?")
                    .param(xid)
                    .query(Long.class)
                    .single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the invitation never reached the users index");
    }
}
