package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * SMS delivery reports over HTTP (issue #215, ADR-036 decision 7, spec section 4.7), with the recorded fabricated bodies
 * of {@code src/test/resources/africastalking}: a delivered report moves a sent row once and a replay changes nothing; a
 * late interim report never moves a delivered row back, and neither does a contradicting final one; an interim report
 * first and the final one after it lands; a failure keeps its reason; an unknown id is ignored; a wrong token is
 * rejected and counted; a report moves only the row of its own message id, in whichever business it is, and a tenant
 * session cannot see another business's row; the recipient's number is never logged. The path token is the test
 * profile's fabricated one; every id and number is fabricated.
 */
@ExtendWith(OutputCaptureExtension.class)
class SmsDeliveryReportIT extends IntegrationTest {

    static final String TOKEN = "test-only-fabricated-sms-callback-token-0123456789";
    static final String PATH = "/api/v1/channels/sms/africastalking/delivery-reports/";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture a;
    TestDatabase.Fixture b;

    @BeforeEach
    void tenants() {
        a = TestDatabase.tenant("smsdra", true);
        b = TestDatabase.tenant("smsdrb", true);
    }

    static String newId() {
        return "ATXid_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** One delivery row of a business, as the channel writes it after the provider took the message. */
    static UUID sentRow(TestDatabase.Fixture t, String messageId) {
        return row(t, messageId, "sent");
    }

    static UUID row(TestDatabase.Fixture t, String messageId, String status) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO sms_deliveries (id, tenant_id, created_at, updated_at, outbox_id, template_key, category,
                                                    recipient_masked, recipient_hash, provider, provider_message_id,
                                                    segments, provider_segments, cost_minor, currency, cost_raw, status)
                        VALUES (?, ?, TIMESTAMPTZ '2026-10-05 07:00:00Z', TIMESTAMPTZ '2026-10-05 07:00:00Z', ?,
                                'savings.deposit', 'transactional', '+256*****0101', repeat('a', 64), 'africastalking', ?,
                                1, 1, 30, 'UGX', 'UGX 30.0000', ?)
                        """)
                .params(id, t.tenantId(), UUID.randomUUID(), messageId, status)
                .update();
        return id;
    }

    static Map<String, Object> state(UUID rowId) {
        return TestDatabase.owner()
                .sql("SELECT status, failure_reason, updated_at FROM sms_deliveries WHERE id = ?")
                .param(rowId)
                .query()
                .singleRow();
    }

    static String status(UUID rowId) {
        return (String) state(rowId).get("status");
    }

    /** Every hit of an outcome so far, over every day, so a test reads its own increments. */
    static long hits(String outcome) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(hits), 0) FROM sms_report_counters WHERE outcome = ?")
                .param(outcome)
                .query(Long.class)
                .single();
    }

    ResponseEntity<String> post(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return http.postForEntity(PATH + token, new HttpEntity<>(body, headers), String.class);
    }

    ResponseEntity<String> report(String name, String messageId) throws Exception {
        return post(TOKEN, recorded(name, messageId));
    }

    /** A recorded body with its placeholder id replaced. */
    static String recorded(String name, String messageId) throws Exception {
        try (var in =
                SmsDeliveryReportIT.class.getResourceAsStream("/africastalking/delivery-report-" + name + ".txt")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .strip()
                    .replace("MESSAGE_ID", messageId);
        }
    }

    @Test
    void aDeliveredReportMovesSentToDeliveredOnceAndAReplayChangesNothing() throws Exception {
        String id = newId();
        UUID row = sentRow(a, id);
        long moved = hits("moved");
        long repeated = hits("repeated");
        Timestamp written = (Timestamp) state(row).get("updated_at");

        assertThat(report("success", id).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> first = state(row);
        assertThat(first.get("status")).isEqualTo("delivered");
        assertThat(first.get("failure_reason")).isNull();
        assertThat((Timestamp) first.get("updated_at")).isAfter(written);

        // The provider retries what it thinks we missed: the same report again is acknowledged and changes nothing.
        assertThat(report("success", id).getStatusCode().value()).isEqualTo(200);
        assertThat(report("success", id).getStatusCode().value()).isEqualTo(200);
        assertThat(state(row)).isEqualTo(first);
        assertThat(hits("moved") - moved).isEqualTo(1);
        assertThat(hits("repeated") - repeated).isEqualTo(2);
    }

    @Test
    void aLateSentReportNeverMovesDeliveredBackAndNeitherDoesAContradictingFailure() throws Exception {
        String id = newId();
        UUID row = sentRow(a, id);
        long interim = hits("interim");
        long notForward = hits("not_forward");

        assertThat(report("success", id).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> delivered = state(row);
        // Out of order: the interim reports the provider sent first arrive after the final one.
        assertThat(report("sent", id).getStatusCode().value()).isEqualTo(200);
        assertThat(report("buffered", id).getStatusCode().value()).isEqualTo(200);
        // A final report that contradicts the first final one is not a forward move either.
        assertThat(report("failed", id).getStatusCode().value()).isEqualTo(200);

        assertThat(state(row)).isEqualTo(delivered);
        assertThat(hits("interim") - interim).isEqualTo(2);
        assertThat(hits("not_forward") - notForward).isEqualTo(1);
    }

    @Test
    void anInterimReportLeavesTheRowSentAndTheFinalOneAfterItLands() throws Exception {
        String id = newId();
        UUID row = sentRow(a, id);
        Map<String, Object> written = state(row);
        long interim = hits("interim");

        assertThat(report("buffered", id).getStatusCode().value()).isEqualTo(200);
        assertThat(hits("interim") - interim).isEqualTo(1);
        // Not touched at all: an interim report writes nothing to the row.
        assertThat(state(row)).isEqualTo(written);
        assertThat(report("sent", id).getStatusCode().value()).isEqualTo(200);
        assertThat(report("success", id).getStatusCode().value()).isEqualTo(200);
        assertThat(status(row)).isEqualTo("delivered");
    }

    @Test
    void aFailureIsRecordedWithItsReasonAndALaterSuccessDoesNotUndoIt() throws Exception {
        String failedId = newId();
        UUID failed = sentRow(a, failedId);
        String rejectedId = newId();
        UUID rejected = sentRow(a, rejectedId);

        assertThat(report("failed", failedId).getStatusCode().value()).isEqualTo(200);
        assertThat(report("rejected", rejectedId).getStatusCode().value()).isEqualTo(200);
        assertThat(state(failed))
                .containsEntry("status", "undelivered")
                .containsEntry("failure_reason", "DeliveryFailure");
        assertThat(state(rejected))
                .containsEntry("status", "undelivered")
                .containsEntry("failure_reason", "UserInBlackList");

        assertThat(report("success", failedId).getStatusCode().value()).isEqualTo(200);
        assertThat(status(failed)).isEqualTo("undelivered");
    }

    @Test
    void aRowThatWasNeverSentIsNotMovedByAReport() throws Exception {
        // A row the channel marked unknown or failed keeps that status: a report moves only sent rows (forward only).
        String id = newId();
        UUID unknown = row(a, id, "unknown");
        assertThat(report("success", id).getStatusCode().value()).isEqualTo(200);
        assertThat(status(unknown)).isEqualTo("unknown");
    }

    @Test
    void anUnknownIdIsAcknowledgedIgnoredAndCounted() throws Exception {
        String known = newId();
        UUID row = sentRow(a, known);
        long unknown = hits("unknown_id");

        assertThat(report("success", newId()).getStatusCode().value()).isEqualTo(200);

        assertThat(status(row)).isEqualTo("sent");
        assertThat(hits("unknown_id") - unknown).isEqualTo(1);
    }

    @Test
    void aReportWithAWrongTokenIsRejectedAndCountedAndChangesNothing() throws Exception {
        String id = newId();
        UUID row = sentRow(a, id);
        long bad = hits("bad_token");

        ResponseEntity<String> wrong =
                post("test-only-a-wrong-token-of-the-right-length-000000", recorded("success", id));
        assertThat(wrong.getStatusCode().value()).isEqualTo(404);
        assertThat(post("short", recorded("success", id)).getStatusCode().value())
                .isEqualTo(404);

        assertThat(status(row)).isEqualTo("sent");
        assertThat(hits("bad_token") - bad).isEqualTo(2);
    }

    @Test
    void aBodyThatIsNotAReportIsRefusedAndCounted() {
        long malformed = hits("malformed");
        assertThat(post(TOKEN, "status=Success&phoneNumber=%2B256700000106")
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(hits("malformed") - malformed).isEqualTo(1);
        // A status word this build does not know is acknowledged, so the provider does not retry, and counted.
        long unknownStatus = hits("unknown_status");
        assertThat(post(TOKEN, "id=" + newId() + "&status=Expired")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(hits("unknown_status") - unknownStatus).isEqualTo(1);
    }

    @Test
    void aReportMovesOnlyItsOwnBusinessesRowAndATenantSessionSeesNoOtherBusinessesRow() throws Exception {
        String idA = newId();
        String idB = newId();
        UUID rowA = sentRow(a, idA);
        UUID rowB = sentRow(b, idB);

        assertThat(report("success", idA).getStatusCode().value()).isEqualTo(200);
        assertThat(status(rowA)).isEqualTo("delivered");
        assertThat(status(rowB)).isEqualTo("sent");
        assertThat(report("failed", idB).getStatusCode().value()).isEqualTo(200);
        assertThat(status(rowB)).isEqualTo("undelivered");
        assertThat(status(rowA)).isEqualTo("delivered");

        // Row-level security: bound to B, A's row is not there, by id or by message id.
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, b.tenantId().toString());
                bind.execute();
            }
            try (PreparedStatement ps =
                    c.prepareStatement("SELECT count(*) FROM sms_deliveries WHERE id = ? OR provider_message_id = ?")) {
                ps.setObject(1, rowA);
                ps.setString(2, idA);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).isZero();
                }
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * Issue #808: a chunked body has no length, so the 16 KB check could not see its size; it is refused with 411 and counted,
     * and the row it names does not move. JUSTIFICATION-A3: a new case over real HTTP, which only Tomcat can prove.
     */
    @Test
    void aChunkedBodyIsRefusedBeforeItIsRead() throws Exception {
        String id = newId();
        UUID row = sentRow(a, id);
        long noLength = hits("no_length");
        byte[] body = recorded("success", id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(http.getRootUri() + PATH + TOKEN))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                // A stream of unknown length: the client sends it chunked, with no Content-Length.
                .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(body)))
                .build();
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            int status = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode();
            assertThat(status).isEqualTo(411);
        }
        assertThat(hits("no_length") - noLength).isEqualTo(1);
        assertThat(status(row)).isEqualTo("sent");
    }

    /**
     * V137 (issue #808): the nightly purge answers the delivery rows it deleted, never the counter rows it cleared with them.
     * Dated in 1999, before anything another test writes, so the count is this test's own. JUSTIFICATION-A3: a new case.
     */
    @Test
    void thePurgeAnswersDeliveryRowsOnlyAndStillClearsTheOldCounters() {
        UUID old = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO sms_deliveries (id, tenant_id, created_at, updated_at, outbox_id, template_key, category,
                                                    recipient_masked, recipient_hash, provider, provider_message_id,
                                                    segments, provider_segments, cost_minor, currency, cost_raw, status)
                        VALUES (?, ?, TIMESTAMPTZ '1999-06-01 07:00:00Z', TIMESTAMPTZ '1999-06-01 07:00:00Z', ?,
                                'savings.deposit', 'transactional', '+256*****0101', repeat('a', 64), 'africastalking', ?,
                                1, 1, 30, 'UGX', 'UGX 30.0000', 'delivered')
                        """)
                .params(old, a.tenantId(), UUID.randomUUID(), newId())
                .update();
        for (String outcome : java.util.List.of("moved", "repeated", "unknown_id")) {
            TestDatabase.owner()
                    .sql("INSERT INTO sms_report_counters (day, outcome, hits) VALUES (DATE '1999-06-01', ?, 3)")
                    .param(outcome)
                    .update();
        }

        int purged = TestDatabase.owner()
                .sql("SELECT sms_deliveries_purge(TIMESTAMPTZ '2000-01-01 00:00:00Z')")
                .query(Integer.class)
                .single();

        assertThat(purged).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM sms_report_counters WHERE day < DATE '2000-01-01'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theRecipientsNumberIsNeverLogged(CapturedOutput output) throws Exception {
        String id = newId();
        sentRow(a, id);
        report("failed", id);
        report("success", newId());
        assertThat(output.getAll()).doesNotContain("256700000102").doesNotContain("256700000101");
    }
}
