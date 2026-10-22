package com.rincoltech.bms.core.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.testsupport.Api;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * Feedback sent from inside the app (issue #447, ADR-065): any member of staff sends an idea, a problem or a question with
 * an optional screenshot; the sender sees their own and its status, nobody else in the business does and no other business
 * does; the platform operator sees every business's newest first, filters by status, moves it forward with a reply the
 * sender then sees, and is emailed through the outbox; ten an hour per person. Fabricated data only.
 */
class FeedbackIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    /** Calls the real outbox until a test arms it to fail. */
    @MockitoSpyBean
    Outbox outbox;

    TestDatabase.Fixture a;
    TestDatabase.Fixture b;
    Api apiA;
    Api apiB;
    String senderA;
    UUID senderAId;
    String colleagueA;
    String senderB;
    Api platform;
    String operator;
    UUID operatorId;

    @BeforeEach
    void setUp() {
        TestRestTemplate plain = Api.withoutRetries(http);
        a = TestDatabase.tenant("fb-a", true);
        b = TestDatabase.tenant("fb-b", true);
        apiA = Api.tenant(plain, a.slug());
        apiB = Api.tenant(plain, b.slug());
        String emailA = Api.email("fb-cashier");
        senderAId = Api.staff(a, emailA, new Api.Role("cashier", a.headOffice()));
        senderA = apiA.signIn(senderAId, emailA, Api.PASSWORD, null).accessToken();
        String emailColleague = Api.email("fb-admin");
        UUID colleague = Api.staff(a, emailColleague, new Api.Role("tenant_admin", null));
        colleagueA = apiA.signIn(colleague, emailColleague, Api.PASSWORD, null).accessToken();
        String emailB = Api.email("fb-other");
        UUID otherB = Api.staff(b, emailB, new Api.Role("tenant_admin", null));
        senderB = apiB.signIn(otherB, emailB, Api.PASSWORD, null).accessToken();
        platform = Api.platform(plain);
        String operatorEmail = Api.email("fb-operator");
        operatorId = Api.platformUser(operatorEmail);
        operator =
                platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null).accessToken();
    }

    static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    static ResponseEntity<JsonNode> send(Api api, String token, String kind, String message, byte[] screenshot) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("kind", kind);
        form.add("message", message);
        form.add("page_path", "/staff/retail/sale?draft=secret#top");
        if (screenshot != null) {
            form.add("screenshot", new ByteArrayResource(screenshot) {
                @Override
                public String getFilename() {
                    return "screen.png";
                }
            });
        }
        return api.call(
                HttpMethod.POST,
                "/api/v1/feedback",
                form,
                token,
                Map.of(HttpHeaders.CONTENT_TYPE, "multipart/form-data", HttpHeaders.USER_AGENT, "TestBrowser/1.0"));
    }

    static List<String> ids(ResponseEntity<JsonNode> list) {
        assertThat(list.getStatusCode()).as("%s", list.getBody()).isEqualTo(HttpStatus.OK);
        List<String> out = new ArrayList<>();
        list.getBody().get("items").forEach(i -> out.add(i.get("id").asString()));
        return out;
    }

    @Test
    void anyMemberOfStaffSendsAndSeesOnlyTheirOwn() throws IOException {
        ResponseEntity<JsonNode> sent =
                send(apiA, senderA, "problem", "  The sale screen froze after I added a third item.  ", png(400, 800));
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode item = sent.getBody();
        String id = item.get("id").asString();
        assertThat(item.get("kind").asString()).isEqualTo("problem");
        assertThat(item.get("message").asString()).isEqualTo("The sale screen froze after I added a third item.");
        // The path only: the query and the fragment are dropped.
        assertThat(item.get("page_path").asString()).isEqualTo("/staff/retail/sale");
        assertThat(item.get("app_version").asString()).isNotBlank();
        assertThat(item.get("has_screenshot").asBoolean()).isTrue();
        assertThat(item.get("status").asString()).isEqualTo("new");
        assertThat(TestDatabase.owner()
                        .sql("SELECT tenant_id, sender_user_id, user_agent FROM feedback_items WHERE id = ?::uuid")
                        .param(id)
                        .query((rs, n) ->
                                List.of(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                        .single())
                .containsExactly(a.tenantId(), senderAId, "TestBrowser/1.0");

        // The sender lists it and reads the screenshot; an administrator of the same business sees neither.
        assertThat(ids(apiA.get("/api/v1/feedback", senderA))).contains(id);
        ResponseEntity<byte[]> own = image(apiA, "/api/v1/feedback/" + id + "/screenshot", senderA);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(ids(apiA.get("/api/v1/feedback", colleagueA))).doesNotContain(id);
        assertThat(image(apiA, "/api/v1/feedback/" + id + "/screenshot", colleagueA)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The operator is emailed through the outbox; the sender's business and words are in it.
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM notification_outbox
                                 WHERE template_key = 'feedback.new' AND channel = 'email'
                                   AND idempotency_key = 'feedback.new:' || ? || ':email:' || ?
                                """)
                        .params(id, operatorId.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // Audited in the business, without the message.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE action = 'core.feedback.sent' AND entity_id = ?::uuid")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void anotherBusinessNeverSeesIt() throws IOException, SQLException {
        String id = send(apiA, senderA, "idea", "Show the till total in larger figures.", png(200, 200))
                .getBody()
                .get("id")
                .asString();
        assertThat(ids(apiB.get("/api/v1/feedback", senderB))).doesNotContain(id);
        assertThat(image(apiB, "/api/v1/feedback/" + id + "/screenshot", senderB)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // Row level security: bound to business B, the application role sees none of A's feedback, and the operator's
        // functions refuse a transaction bound to a business.
        assertThat(asTenant(
                        b.tenantId(), "SELECT count(*) FROM feedback_items WHERE tenant_id = '" + a.tenantId() + "'"))
                .isEqualTo("0");
        assertThat(asTenant(b.tenantId(), "SELECT count(*) FROM platform_feedback_list(NULL, NULL, NULL, 10, 0)"))
                .isEqualTo("refused");
        assertThat(asTenant(b.tenantId(), "SELECT platform_feedback_tenant('" + id + "')"))
                .isEqualTo("refused");
        assertThat(asTenant(
                        b.tenantId(),
                        "SELECT platform_feedback_set_status('" + id + "', 'done', NULL, '" + operatorId + "', now())"))
                .isEqualTo("refused");
        assertThat(asTenant(b.tenantId(), "SELECT platform_feedback_set_linked_issue('" + id + "', 7)"))
                .isEqualTo("refused");
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM feedback_items WHERE id = ?::uuid")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("new");
    }

    @Test
    void aPlatformTokenCannotSendOrList() {
        assertThat(send(apiA, operator, "idea", "Test message from an operator", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(apiA.get("/api/v1/feedback", operator).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM feedback_items WHERE tenant_id = ?")
                        .param(a.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void anOutboxFailureNeverBlocksTheSend() {
        // The spy sits behind the transactional proxy; arm the spy itself.
        Outbox spy = AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new IllegalStateException("test outbox down")).when(spy).enqueue(any());
        ResponseEntity<JsonNode> sent = send(apiA, senderA, "problem", "Test problem while the outbox is down", null);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = sent.getBody().get("id").asString();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM feedback_items WHERE id = ?::uuid")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM notification_outbox WHERE idempotency_key LIKE 'feedback.new:' || ? || ':%'")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(ids(apiA.get("/api/v1/feedback", senderA))).contains(id);
    }

    @Test
    void aReplyAloneKeepsTheStatusDateAndBlankRepliesClear() {
        String id = send(apiA, senderA, "question", "Test question about the reply", null)
                .getBody()
                .get("id")
                .asString();
        String url = "/api/v1/platform/feedback/" + id + "/status";
        assertThat(platform.call(HttpMethod.PUT, url, Map.of("status", "seen"), operator)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Instant changed = statusChangedAt(id);
        assertThat(changed).isNotNull();

        ResponseEntity<JsonNode> replied =
                platform.call(HttpMethod.PUT, url, Map.of("status", "seen", "reply_note", "Test reply"), operator);
        assertThat(replied.getBody().get("reply_note").asString()).isEqualTo("Test reply");
        assertThat(statusChangedAt(id)).isEqualTo(changed);

        // Only white space (tabs and line breaks too) clears the reply.
        ResponseEntity<JsonNode> cleared =
                platform.call(HttpMethod.PUT, url, Map.of("status", "seen", "reply_note", " \t\r\n "), operator);
        assertThat(cleared.getStatusCode()).as("%s", cleared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("reply_note").isNull()).isTrue();
        assertThat(statusChangedAt(id)).isEqualTo(changed);
    }

    static Instant statusChangedAt(String id) {
        return TestDatabase.owner()
                .sql("SELECT status_changed_at FROM feedback_items WHERE id = ?::uuid")
                .param(id)
                .query((rs, n) ->
                        rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                .single();
    }

    @Test
    void theOperatorLinksAnIssueAndListsOnlyWhatIsNew() {
        String first = send(apiA, senderA, "problem", "Test problem to link", null)
                .getBody()
                .get("id")
                .asString();
        Instant firstAt = Instant.parse(find(platform.get("/api/v1/platform/feedback?limit=200", operator), first)
                .get("created_at")
                .asString());
        String second = send(apiA, colleagueA, "idea", "Test idea after the first", null)
                .getBody()
                .get("id")
                .asString();

        // since: only what was sent after it.
        assertThat(ids(platform.get("/api/v1/platform/feedback?limit=200&since=" + firstAt, operator)))
                .contains(second)
                .doesNotContain(first);

        String url = "/api/v1/platform/feedback/" + first + "/linked-issue";
        ResponseEntity<JsonNode> linked =
                platform.call(HttpMethod.PUT, url, Map.of("linked_issue_number", 4521), operator);
        assertThat(linked.getStatusCode()).as("%s", linked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(linked.getBody().get("linked_issue_number").asInt()).isEqualTo(4521);
        assertThat(find(platform.get("/api/v1/platform/feedback?limit=200", operator), first)
                        .get("linked_issue_number")
                        .asInt())
                .isEqualTo(4521);
        assertThat(platform.call(HttpMethod.PUT, url, Map.of("linked_issue_number", 0), operator)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(platform.call(HttpMethod.PUT, url, Map.of("linked_issue_number", 7), senderA)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> clear = new HashMap<>();
        clear.put("linked_issue_number", null);
        ResponseEntity<JsonNode> cleared = platform.call(HttpMethod.PUT, url, clear, operator);
        assertThat(cleared.getStatusCode()).as("%s", cleared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("linked_issue_number").isNull()).isTrue();
        assertThat(platform.call(
                                HttpMethod.PUT,
                                "/api/v1/platform/feedback/" + UUID.randomUUID() + "/linked-issue",
                                Map.of("linked_issue_number", 7),
                                operator)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Audited like the status change: set, then cleared.
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM platform_audit_log
                                 WHERE action = 'platform.feedback.issue_linked' AND platform_user_id = ? AND tenant_id = ?
                                """)
                        .params(operatorId, a.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    /** One query as bms_app with the tenant bound as the transaction manager binds it; "refused" when the database says no. */
    static String asTenant(UUID tenantId, String sql) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, tenantId.toString());
                bind.executeQuery().close();
            }
            try (PreparedStatement q = c.prepareStatement(sql);
                    ResultSet rs = q.executeQuery()) {
                rs.next();
                return rs.getString(1);
            } catch (SQLException e) {
                return "refused";
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void theOperatorListsEveryBusinessMovesItForwardAndReplies() throws IOException {
        String first = send(apiA, senderA, "question", "How do I close the till on a holiday?", null)
                .getBody()
                .get("id")
                .asString();
        String second = send(apiB, senderB, "idea", "A dark receipt option would help.", png(300, 300))
                .getBody()
                .get("id")
                .asString();

        // Tenant staff and anonymous callers are refused.
        for (String token : new String[] {null, senderA}) {
            assertThat(platform.get("/api/v1/platform/feedback", token).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // Newest first, across businesses, with the business and the sender's name.
        ResponseEntity<JsonNode> all = platform.get("/api/v1/platform/feedback?limit=200", operator);
        List<String> order = ids(all);
        assertThat(order).contains(first, second);
        assertThat(order.indexOf(second)).isLessThan(order.indexOf(first));
        JsonNode row = find(all, first);
        assertThat(row.get("tenant_slug").asString()).isEqualTo(a.slug());
        assertThat(row.get("sender_name").asString()).isNotBlank();
        assertThat(row.get("has_screenshot").asBoolean()).isFalse();

        // The screenshot of business B, read by the operator with B bound.
        ResponseEntity<byte[]> shot = image(platform, "/api/v1/platform/feedback/" + second + "/screenshot", operator);
        assertThat(shot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(image(platform, "/api/v1/platform/feedback/" + first + "/screenshot", operator)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Seen, with a reply the sender then sees.
        ResponseEntity<JsonNode> seen = platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/feedback/" + first + "/status",
                Map.of("status", "seen", "reply_note", "Thank you, a guide is coming this week."),
                operator);
        assertThat(seen.getStatusCode()).as("%s", seen.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(seen.getBody().get("status").asString()).isEqualTo("seen");
        JsonNode mine = find(apiA.get("/api/v1/feedback", senderA), first);
        assertThat(mine.get("status").asString()).isEqualTo("seen");
        assertThat(mine.get("reply_note").asString()).isEqualTo("Thank you, a guide is coming this week.");

        // Filter by status.
        assertThat(ids(platform.get("/api/v1/platform/feedback?status=seen&limit=200", operator)))
                .contains(first)
                .doesNotContain(second);
        assertThat(ids(platform.get("/api/v1/platform/feedback?status=new&limit=200", operator)))
                .contains(second)
                .doesNotContain(first);

        // Done keeps the reply; a move back is refused in plain words.
        ResponseEntity<JsonNode> done = platform.call(
                HttpMethod.PUT, "/api/v1/platform/feedback/" + first + "/status", Map.of("status", "done"), operator);
        assertThat(done.getBody().get("status").asString()).isEqualTo("done");
        assertThat(done.getBody().get("reply_note").asString()).isEqualTo("Thank you, a guide is coming this week.");
        ResponseEntity<JsonNode> back = platform.call(
                HttpMethod.PUT, "/api/v1/platform/feedback/" + first + "/status", Map.of("status", "seen"), operator);
        assertThat(back.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(back.getBody().get("code").asString()).isEqualTo("feedback_status_backwards");

        // Tenant staff cannot change a status.
        assertThat(platform.call(
                                HttpMethod.PUT,
                                "/api/v1/platform/feedback/" + second + "/status",
                                Map.of("status", "done"),
                                senderB)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Audited in the platform log with the operator and the business.
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM platform_audit_log
                                 WHERE action = 'platform.feedback.status_changed' AND platform_user_id = ? AND tenant_id = ?
                                """)
                        .params(operatorId, a.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void tenAnHourPerPerson() {
        for (int i = 1; i <= 10; i++) {
            assertThat(send(apiA, senderA, "idea", "Test idea number " + i, null)
                            .getStatusCode())
                    .as("item %d", i)
                    .isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<JsonNode> refused = send(apiA, senderA, "idea", "One too many", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("feedback_rate_limited");
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        // Another person of the same business is not limited by it.
        assertThat(send(apiA, colleagueA, "idea", "My first idea", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void refusesWhatItCannotKeep() throws IOException {
        assertThat(send(apiA, senderA, "complaint", "Test message", null).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(send(apiA, senderA, "idea", "   ", null).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(send(apiA, senderA, "idea", "x".repeat(2001), null).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> gif =
                send(apiA, senderA, "idea", "Test message", "GIF89a fabricated".getBytes(StandardCharsets.US_ASCII));
        assertThat(gif.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(gif.getBody().get("code").asString()).isEqualTo("screenshot_not_an_image");
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png(10, 10), 0, big, 0, 8);
        ResponseEntity<JsonNode> tooBig = send(apiA, senderA, "idea", "Test message", big);
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooBig.getBody().get("code").asString()).isEqualTo("screenshot_too_large");
        // Nothing was kept from any refused send.
        assertThat(ids(apiA.get("/api/v1/feedback", senderA))).isEmpty();
    }

    static JsonNode find(ResponseEntity<JsonNode> list, String id) {
        for (JsonNode i : list.getBody().get("items")) {
            if (i.get("id").asString().equals(id)) {
                return i;
            }
        }
        throw new AssertionError("no item " + id + " in " + list.getBody());
    }

    ResponseEntity<byte[]> image(Api api, String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (api == apiA) {
            h.add("X-Tenant", a.slug());
        } else if (api == apiB) {
            h.add("X-Tenant", b.slug());
        }
        h.setBearerAuth(token);
        return Api.withoutRetries(http)
                .exchange(path, HttpMethod.GET, new org.springframework.http.HttpEntity<>(h), byte[].class);
    }
}
