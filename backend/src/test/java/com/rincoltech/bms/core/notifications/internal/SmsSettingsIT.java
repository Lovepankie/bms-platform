package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The tenant SMS settings through the API (issue #218, ADR-036, spec 4.8): the switch with its acknowledgement, the
 * sender id chosen from the operator's allow-list, the window, the caps that can be lowered but not raised, the
 * version check on every change, who may do what, the audit trail, and that switching on never releases a backlog.
 * Fabricated tenants, people and sender names; no number is sent anywhere.
 */
class SmsSettingsIT extends LoanFixtures {

    static final String SETTINGS = "/api/v1/settings/sms";

    @Autowired
    Outbox outbox;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID admin = UUID.randomUUID();

    /** A fabricated sender id of this test alone: an id belongs to one tenant at a time, across the shared database. */
    final String brand = "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);

    Api platform;
    Session operator;

    @BeforeEach
    void operator() {
        platform = Api.platform(http);
        String email = Api.email("sms-operator");
        operator = platform.signIn(Api.platformUser(email), email, Api.PASSWORD, null);
        // The operator has switched SMS on for this tenant, daily cap 300 (the ceiling its administrator works under).
        SmsTenants.operator(t.tenantId(), true, 300);
    }

    ResponseEntity<JsonNode> read() {
        return send(HttpMethod.GET, SETTINGS, as(admin, adminPerms, "*", null), null);
    }

    ResponseEntity<JsonNode> change(Map<String, Object> body, String etag) {
        return send(HttpMethod.PUT, SETTINGS, as(admin, adminPerms, "*", etag), body);
    }

    /** Reads, then changes under the version just read. */
    ResponseEntity<JsonNode> change(Map<String, Object> body) {
        return change(body, read().getHeaders().getETag());
    }

    Map<String, Object> switchOn() {
        Map<String, Object> on = new LinkedHashMap<>();
        on.put("enabled", true);
        on.put(
                "acknowledged_text_version",
                read().getBody().get("acknowledgement").get("text_version").asString());
        return on;
    }

    String platformPath(String senderId) {
        return "/api/v1/platform/tenants/" + t.tenantId() + "/sms/sender-ids/" + senderId;
    }

    ResponseEntity<JsonNode> approve(UUID tenant, String senderId) {
        return platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/tenants/" + tenant + "/sms/sender-ids/" + senderId,
                null,
                operator.accessToken());
    }

    List<String> codes(ResponseEntity<JsonNode> r) {
        List<String> codes = new java.util.ArrayList<>();
        r.getBody()
                .get("errors")
                .forEach(e -> codes.add(
                        e.get("field").asString() + ":" + e.get("code").asString()));
        return codes;
    }

    long audits(String action) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?")
                .params(t.tenantId(), action)
                .query(Long.class)
                .single();
    }

    // ---- Defaults and the switch -----------------------------------------------------------------

    @Test
    void aTenantStartsWithSmsOffTheStandardWindowAndNoCapsOfItsOwn() {
        ResponseEntity<JsonNode> r = read();
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode s = r.getBody();
        assertThat(s.get("enabled").asBoolean()).isFalse();
        assertThat(s.get("operator_enabled").asBoolean()).isTrue();
        assertThat(s.get("window_start").asString()).isEqualTo("08:00");
        assertThat(s.get("window_end").asString()).isEqualTo("20:00");
        assertThat(s.get("window_zone").asString()).isEqualTo("Africa/Kampala");
        assertThat(s.get("daily_cap").isNull()).isTrue();
        assertThat(s.get("monthly_cap").isNull()).isTrue();
        // The ceiling is the operator's cap for this tenant, and a month is 31 of those days at most.
        assertThat(s.get("daily_ceiling").asInt()).isEqualTo(300);
        assertThat(s.get("monthly_ceiling").asLong()).isEqualTo(300L * 31);
        assertThat(s.get("effective_daily_cap").asInt()).isEqualTo(300);
        assertThat(s.get("used_today").asLong()).isZero();
        assertThat(s.get("include_balance").asBoolean()).isFalse();
        assertThat(s.get("sender_id").isNull()).isTrue();
        assertThat(s.get("sender_status").asString()).isEqualTo("none");
        assertThat(s.get("approved_sender_ids")).isEmpty();
        assertThat(s.get("acknowledgement").get("text").asString()).contains("costs money");
        assertThat(s.get("acknowledged_by").isNull()).isTrue();
        assertThat(r.getHeaders().getETag()).isEqualTo("\"" + s.get("version").asInt() + "\"");
    }

    @Test
    void switchingOnNeedsTheAcknowledgementOfTheTextThatWasShownAndRecordsWhoAndWhen() {
        // Without it, and with another version of the text: refused, and nothing changes.
        ResponseEntity<JsonNode> bare = change(Map.of("enabled", true));
        assertThat(bare.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bare.getBody().get("code").asString()).isEqualTo("acknowledgement_required");
        ResponseEntity<JsonNode> old = change(Map.of("enabled", true, "acknowledged_text_version", "sms-0"));
        assertThat(old.getBody().get("code").asString()).isEqualTo("acknowledgement_required");
        assertThat(read().getBody().get("enabled").asBoolean()).isFalse();
        // The acknowledgement is not taken without a switch-on.
        ResponseEntity<JsonNode> loose = change(Map.of("acknowledged_text_version", "sms-1"));
        assertThat(loose.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> on = change(switchOn());
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("enabled").asBoolean()).isTrue();
        assertThat(on.getBody().get("acknowledged_text_version").asString()).isEqualTo("sms-1");
        assertThat(on.getBody().get("acknowledged_by").asString()).isEqualTo(admin.toString());
        assertThat(on.getBody().get("acknowledged_at").isNull()).isFalse();

        // The audit row carries before and after.
        Map<String, Object> audit = TestDatabase.owner()
                .sql(
                        "SELECT data::text AS data FROM audit_log WHERE tenant_id = ? AND action = 'core.sms.settings.changed'")
                .param(t.tenantId())
                .query()
                .singleRow();
        assertThat((String) audit.get("data")).contains("\"enabled\": false").contains("\"enabled\": true");

        // Switching off keeps the record of who acknowledged, and a second switch-on needs a new acknowledgement.
        ResponseEntity<JsonNode> off = change(Map.of("enabled", false));
        assertThat(off.getBody().get("enabled").asBoolean()).isFalse();
        assertThat(off.getBody().get("acknowledged_by").asString()).isEqualTo(admin.toString());
        assertThat(change(Map.of("enabled", true)).getBody().get("code").asString())
                .isEqualTo("acknowledgement_required");
        assertThat(audits("core.sms.settings.changed")).isEqualTo(2);
    }

    @Test
    void aChangeAgainstANoOpWritesNothingAndKeepsTheVersion() {
        ResponseEntity<JsonNode> on = change(switchOn());
        int version = on.getBody().get("version").asInt();
        ResponseEntity<JsonNode> same =
                change(Map.of("include_balance", false), on.getHeaders().getETag());
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(same.getBody().get("version").asInt()).isEqualTo(version);
        assertThat(audits("core.sms.settings.changed")).isEqualTo(1);
    }

    // ---- If-Match and races ----------------------------------------------------------------------

    @Test
    void everyChangeNeedsTheVersionItWasReadAt() {
        ResponseEntity<JsonNode> none =
                send(HttpMethod.PUT, SETTINGS, as(admin, adminPerms, "*", null), Map.of("include_balance", true));
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        String etag = read().getHeaders().getETag();
        assertThat(change(Map.of("include_balance", true), etag).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // The same, now stale: refused with the current version named, and nothing is overwritten.
        ResponseEntity<JsonNode> stale = change(Map.of("window_start", "09:00"), etag);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("version_conflict");
        JsonNode now = read().getBody();
        assertThat(now.get("window_start").asString()).isEqualTo("08:00");
        assertThat(now.get("include_balance").asBoolean()).isTrue();
    }

    @Test
    void twoAdministratorsSavingAtOnceUnderTheSameVersionHaveOneWinner() throws Exception {
        String etag = read().getHeaders().getETag();
        List<org.springframework.http.HttpStatusCode> statuses = Api.race(
                2, () -> send(HttpMethod.PUT, SETTINGS, as(admin, adminPerms, "*", etag), Map.of("daily_cap", 10)));
        assertThat(Api.count(statuses, HttpStatus.OK)).isEqualTo(1);
        assertThat(Api.count(statuses, HttpStatus.CONFLICT)).isEqualTo(1);
        assertThat(read().getBody().get("daily_cap").asInt()).isEqualTo(10);
        assertThat(audits("core.sms.settings.changed")).isEqualTo(1);
    }

    @Test
    void twoAdministratorsSwitchingOnAtOnceGiveOneAcknowledgementAndOneAudit() throws Exception {
        Map<String, Object> on = switchOn();
        String etag = read().getHeaders().getETag();
        List<org.springframework.http.HttpStatusCode> statuses =
                Api.race(3, () -> send(HttpMethod.PUT, SETTINGS, as(admin, adminPerms, "*", etag), on));
        assertThat(Api.count(statuses, HttpStatus.OK)).isEqualTo(1);
        assertThat(Api.count(statuses, HttpStatus.CONFLICT)).isEqualTo(2);
        assertThat(audits("core.sms.settings.changed")).isEqualTo(1);
    }

    // ---- The sender id ---------------------------------------------------------------------------

    @Test
    void aTenantAdminCannotChooseASenderIdTheOperatorDidNotApprove() {
        ResponseEntity<JsonNode> free = change(Map.of("sender_id", "FreeChoice"));
        assertThat(free.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(codes(free)).containsExactly("sender_id:not_approved");
        assertThat(read().getBody().get("sender_id").isNull()).isTrue();
    }

    @Test
    void aSenderIdIsValidatedBeforeItIsLookedUp() {
        // Four fabricated kinds of wrong: too short, too long, a space, only digits.
        for (String bad : List.of("AB", "TooLongSender", "My Shop", "256700000")) {
            ResponseEntity<JsonNode> r = send(
                    HttpMethod.PUT,
                    SETTINGS,
                    as(admin, adminPerms, "*", read().getHeaders().getETag()),
                    Map.of("sender_id", bad));
            assertThat(r.getStatusCode()).as(bad).isIn(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.BAD_REQUEST);
        }
        // Digits only passes the shape check of the request but not the rule: no letter, no name.
        ResponseEntity<JsonNode> digits = change(Map.of("sender_id", "256700000"));
        assertThat(codes(digits)).containsExactly("sender_id:invalid");
        // The operator cannot approve one either.
        assertThat(approve(t.tenantId(), "12345").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(approve(t.tenantId(), "AB").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void anApprovedSenderIdCanBeChosenAndIsStoredAsApprovedAndCleared() {
        assertThat(approve(t.tenantId(), brand).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read().getBody().get("approved_sender_ids")).hasSize(1);

        // Spelling follows the approval, not what was typed.
        ResponseEntity<JsonNode> chosen = change(Map.of("sender_id", brand.toLowerCase()));
        assertThat(chosen.getStatusCode()).as("%s", chosen.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(chosen.getBody().get("sender_id").asString()).isEqualTo(brand);
        assertThat(chosen.getBody().get("sender_status").asString()).isEqualTo("approved");

        ResponseEntity<JsonNode> cleared = change(Map.of("sender_id", ""));
        assertThat(cleared.getBody().get("sender_id").isNull()).isTrue();
        assertThat(cleared.getBody().get("sender_status").asString()).isEqualTo("none");
    }

    @Test
    void aSenderIdBelongsToOneBusinessAtATimeAndAnotherCannotUseIt() {
        assertThat(approve(t.tenantId(), brand).getStatusCode()).isEqualTo(HttpStatus.OK);
        TestDatabase.Fixture other = TestDatabase.tenant("sms-other", true);
        ResponseEntity<JsonNode> taken = approve(other.tenantId(), brand.toUpperCase());
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody().get("code").asString()).isEqualTo("sender_id_taken");
        // The other business cannot choose it either, whatever it types.
        ResponseEntity<JsonNode> theirs =
                send(HttpMethod.PUT, SETTINGS, otherAdmin(other, null), Map.of("sender_id", brand));
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        ResponseEntity<JsonNode> theirsRead = send(HttpMethod.GET, SETTINGS, otherAdmin(other, null), null);
        ResponseEntity<JsonNode> theirsTry = send(
                HttpMethod.PUT,
                SETTINGS,
                otherAdmin(other, theirsRead.getHeaders().getETag()),
                Map.of("sender_id", brand));
        assertThat(theirsTry.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(codes(theirsTry)).containsExactly("sender_id:not_approved");
        // After the first business gives it up, the other can have it.
        assertThat(platform.call(HttpMethod.DELETE, platformPath(brand), null, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(approve(other.tenantId(), brand).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withdrawingASenderIdIsShownAtOnceAndNeedsNoEditOfTheSettings() {
        assertThat(approve(t.tenantId(), brand).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(change(Map.of("sender_id", brand)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(platform.call(HttpMethod.DELETE, platformPath(brand), null, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode view = read().getBody();
        assertThat(view.get("sender_id").asString()).isEqualTo(brand);
        assertThat(view.get("sender_status").asString()).isEqualTo("withdrawn");
        assertThat(view.get("approved_sender_ids")).isEmpty();
        // Withdrawing what is not approved is a 404, and the operator's actions are audited.
        assertThat(platform.call(HttpMethod.DELETE, platformPath(brand), null, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE tenant_id = ? AND action LIKE 'platform.sms.sender_id_%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void theTenantCannotReachTheOperatorsSenderIdRoutes() {
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.GET)) {
            String path = "/api/v1/platform/tenants/" + t.tenantId() + "/sms/sender-ids"
                    + (method == HttpMethod.GET ? "" : "/GrabbedName");
            ResponseEntity<JsonNode> r = send(method, path, as(admin, adminPerms, "*", null), null);
            assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", method).isFalse();
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM platform_sms_sender_ids WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    org.springframework.http.HttpHeaders otherAdmin(TestDatabase.Fixture other, String etag) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.add("X-Tenant", other.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", adminPerms);
        h.add("X-Dev-Branch-Ids", "*");
        if (etag != null) {
            h.add(org.springframework.http.HttpHeaders.IF_MATCH, etag);
        }
        return h;
    }

    // ---- The window ------------------------------------------------------------------------------

    @Test
    void theWindowIsOnTheKampalaClockAndValidated() {
        ResponseEntity<JsonNode> ok = change(Map.of("window_start", "07:30", "window_end", "18:45"));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("window_start").asString()).isEqualTo("07:30");
        assertThat(ok.getBody().get("window_end").asString()).isEqualTo("18:45");

        Map<String, List<String>> bad = new LinkedHashMap<>();
        bad.put("end before start", List.of("20:00", "08:00"));
        bad.put("equal", List.of("10:00", "10:00"));
        bad.put("under an hour", List.of("10:00", "10:30"));
        bad.put("before the earliest", List.of("05:00", "12:00"));
        bad.put("after the latest", List.of("12:00", "22:00"));
        bad.put("not a time", List.of("8am", "20:00"));
        bad.put("hours out of range", List.of("08:00", "25:00"));
        for (var e : bad.entrySet()) {
            ResponseEntity<JsonNode> r = change(Map.of(
                    "window_start",
                    e.getValue().get(0),
                    "window_end",
                    e.getValue().get(1)));
            assertThat(r.getStatusCode()).as(e.getKey()).isIn(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.BAD_REQUEST);
        }
        // One side alone is checked against the other as it stands.
        ResponseEntity<JsonNode> late = change(Map.of("window_start", "18:30"));
        assertThat(codes(late)).containsExactly("window_end:window_too_short");
        assertThat(read().getBody().get("window_start").asString()).isEqualTo("07:30");
        // The limits themselves are allowed.
        assertThat(change(Map.of("window_start", "06:00", "window_end", "21:00"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---- Caps ------------------------------------------------------------------------------------

    @Test
    void theTenantCanLowerItsCapsButNeverRaiseThemAboveTheCeiling() {
        ResponseEntity<JsonNode> lowered = change(Map.of("daily_cap", 120, "monthly_cap", 2000));
        assertThat(lowered.getStatusCode()).as("%s", lowered.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lowered.getBody().get("daily_cap").asInt()).isEqualTo(120);
        assertThat(lowered.getBody().get("effective_daily_cap").asInt()).isEqualTo(120);
        assertThat(lowered.getBody().get("effective_monthly_cap").asLong()).isEqualTo(2000);

        // At the ceiling is allowed; one above is not; the ceiling is the operator's cap and 31 days of it.
        assertThat(change(Map.of("daily_cap", 300)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> above = change(Map.of("daily_cap", 301));
        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(codes(above)).containsExactly("daily_cap:above_ceiling");
        ResponseEntity<JsonNode> aboveMonth = change(Map.of("monthly_cap", 300 * 31 + 1));
        assertThat(codes(aboveMonth)).containsExactly("monthly_cap:above_ceiling");
        assertThat(read().getBody().get("daily_cap").asInt()).isEqualTo(300);
        // Not a number, a fraction, negative: refused, not rounded or defaulted.
        for (Object badValue : List.of(-1, "ten", 2.5)) {
            ResponseEntity<JsonNode> r = change(Map.of("daily_cap", badValue));
            assertThat(r.getStatusCode())
                    .as("%s", badValue)
                    .isIn(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void aCapOfZeroMeansStopAndIsNeverReadAsNoCap() {
        ResponseEntity<JsonNode> zero = change(Map.of("daily_cap", 0, "monthly_cap", 0));
        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode view = read().getBody();
        // Stored as 0 and shown as 0: not null (none of its own), not the ceiling.
        assertThat(view.get("daily_cap").isNull()).isFalse();
        assertThat(view.get("daily_cap").asInt()).isZero();
        assertThat(view.get("monthly_cap").asInt()).isZero();
        assertThat(view.get("effective_daily_cap").asInt()).isZero();
        assertThat(view.get("effective_monthly_cap").asLong()).isZero();
    }

    @Test
    void whenTheOperatorLowersItsCapTheEffectiveCapFallsAtOnceAndAHigherOneIsRefused() {
        assertThat(change(Map.of("daily_cap", 250)).getStatusCode()).isEqualTo(HttpStatus.OK);
        SmsTenants.operator(t.tenantId(), true, 100);
        JsonNode view = read().getBody();
        assertThat(view.get("daily_cap").asInt()).isEqualTo(250);
        assertThat(view.get("daily_ceiling").asInt()).isEqualTo(100);
        assertThat(view.get("effective_daily_cap").asInt()).isEqualTo(100);
        assertThat(codes(change(Map.of("daily_cap", 101)))).containsExactly("daily_cap:above_ceiling");
        assertThat(change(Map.of("daily_cap", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- Who may do what -------------------------------------------------------------------------

    @Test
    void onlyTheTenantAdminChangesThemAndOthersWithSettingsReadCanLook() {
        UUID manager = UUID.randomUUID();
        UUID officerUser = UUID.randomUUID();
        // A branch manager may read the settings (core.settings.read) and may not change them.
        assertThat(send(HttpMethod.GET, SETTINGS, as(manager, managerPerms, "*", null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String etag = read().getHeaders().getETag();
        assertThat(send(HttpMethod.PUT, SETTINGS, as(manager, managerPerms, "*", etag), switchOn())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // A loan officer holds neither.
        assertThat(send(HttpMethod.GET, SETTINGS, as(officerUser, officerPerms, "*", null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.PUT, SETTINGS, as(officerUser, officerPerms, "*", etag), Map.of("daily_cap", 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(read().getBody().get("enabled").asBoolean()).isFalse();
        assertThat(read().getBody().get("daily_cap").isNull()).isTrue();
    }

    @Test
    void anotherBusinessSeesItsOwnSettingsNeverThisOnes() {
        assertThat(approve(t.tenantId(), brand).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(change(Map.of("sender_id", brand, "daily_cap", 7, "window_start", "09:15"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(change(switchOn()).getStatusCode()).isEqualTo(HttpStatus.OK);
        TestDatabase.Fixture other = TestDatabase.tenant("sms-other", true);
        JsonNode theirs =
                send(HttpMethod.GET, SETTINGS, otherAdmin(other, null), null).getBody();
        assertThat(theirs.get("enabled").asBoolean()).isFalse();
        assertThat(theirs.get("sender_id").isNull()).isTrue();
        assertThat(theirs.get("daily_cap").isNull()).isTrue();
        assertThat(theirs.get("window_start").asString()).isEqualTo("08:00");
        assertThat(theirs.get("approved_sender_ids")).isEmpty();
        assertThat(theirs.get("operator_enabled").asBoolean()).isFalse();
    }

    // ---- No backlog ------------------------------------------------------------------------------

    boolean queue(String key) {
        return Boolean.TRUE.equals(TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(tx -> outbox.enqueue(new Outbox.Message(
                                Outbox.SMS,
                                "+256700000771",
                                "savings.deposit",
                                Map.of(
                                        "tenant_name", "Test Lender",
                                        "currency", "UGX",
                                        "amount", "5,000",
                                        "account_no", "SV000001",
                                        "receipt_no", "RC-1"),
                                key)))));
    }

    String status(String key) {
        return TestDatabase.owner()
                .sql("SELECT status || ':' || coalesce(last_error, '') || ':' || params::text FROM notification_outbox"
                        + " WHERE idempotency_key = ?")
                .param(key)
                .query(String.class)
                .single();
    }

    @Test
    void switchingOnNeverReleasesWhatWasQueuedBeforeAndWhileOffNothingIsQueued() {
        // Off: the operator's switch is on, the tenant's is not, so nothing is written.
        String whileOff = "test.sms.backlog:" + UUID.randomUUID();
        assertThat(queue(whileOff)).isFalse();

        assertThat(change(switchOn()).getStatusCode()).isEqualTo(HttpStatus.OK);
        String queued = "test.sms.backlog:" + UUID.randomUUID();
        assertThat(queue(queued)).isTrue();
        assertThat(status(queued)).startsWith("pending::").contains("SV000001");

        // Switched off, then on again before anything was sent: the old row must not go out.
        assertThat(change(Map.of("enabled", false)).getStatusCode()).isEqualTo(HttpStatus.OK);
        String whileOff2 = "test.sms.backlog:" + UUID.randomUUID();
        assertThat(queue(whileOff2)).isFalse();
        assertThat(status(queued)).startsWith("pending");
        assertThat(change(switchOn()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(status(queued)).isEqualTo("failed:backlog_dropped:{}");
        // And a message queued after the switch-on is a new message, not part of the backlog.
        String fresh = "test.sms.backlog:" + UUID.randomUUID();
        assertThat(queue(fresh)).isTrue();
        assertThat(status(fresh)).startsWith("pending::");
    }

    @Test
    void theBacklogOfOneBusinessIsNotDroppedBySwitchingAnotherOn() {
        assertThat(change(switchOn()).getStatusCode()).isEqualTo(HttpStatus.OK);
        String mine = "test.sms.backlog:" + UUID.randomUUID();
        assertThat(queue(mine)).isTrue();

        TestDatabase.Fixture other = TestDatabase.tenant("sms-other", true);
        SmsTenants.operator(other.tenantId(), true, 100);
        ResponseEntity<JsonNode> theirRead = send(HttpMethod.GET, SETTINGS, otherAdmin(other, null), null);
        Map<String, Object> on = new LinkedHashMap<>();
        on.put("enabled", true);
        on.put(
                "acknowledged_text_version",
                theirRead.getBody().get("acknowledgement").get("text_version").asString());
        assertThat(send(
                                HttpMethod.PUT,
                                SETTINGS,
                                otherAdmin(other, theirRead.getHeaders().getETag()),
                                on)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(status(mine)).startsWith("pending::");
    }

    // ---- Legacy keys -----------------------------------------------------------------------------

    /** The block between the markers of the migration, run on its own against fabricated rows. */
    @Test
    void theLegacyWindowAndBalanceKeysAreCopiedIntoTheGroupOnceAndNothingElseIsTouched() throws IOException {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V52__sms_tenant_settings.sql"));
        int from = migration.indexOf("-- legacy-copy:begin") + "-- legacy-copy:begin".length();
        String copy = migration.substring(from, migration.indexOf("-- legacy-copy:end"));
        assertThat(copy).contains("UPDATE tenant_settings");

        TestDatabase.Fixture legacy = TestDatabase.tenant("sms-legacy", true);
        TestDatabase.Fixture grouped = TestDatabase.tenant("sms-grouped", true);
        TestDatabase.Fixture plain = TestDatabase.tenant("sms-plain", true);
        TestDatabase.Fixture partial = TestDatabase.tenant("sms-partial", true);
        arrange(
                legacy,
                "{\"sms_window_start\": \"09:30\", \"sms_window_end\": \"17:00\", \"sms_include_balance\": true,"
                        + " \"sms_sender_name\": \"OldName\", \"display_name\": \"Test Legacy\"}");
        arrange(
                grouped,
                "{\"sms_window_start\": \"09:30\", \"module_groups\": {\"sms\": {\"window_start\": \"10:00\"}}}");
        arrange(plain, "{\"display_name\": \"Test Plain\"}");
        arrange(
                partial,
                "{\"sms_include_balance\": false, \"module_groups\": {\"retail.capabilities\": {\"units\": true}}}");

        // JUSTIFICATION-A3: review findings 3 and 8: the clamp cases and the run scoped to this test's own tenants.
        // Wide, short, late and malformed legacy windows: clamped or left out, never copied as they are.
        TestDatabase.Fixture wide = TestDatabase.tenant("sms-wide", true);
        TestDatabase.Fixture tooShort = TestDatabase.tenant("sms-short", true);
        TestDatabase.Fixture lateOnly = TestDatabase.tenant("sms-late", true);
        TestDatabase.Fixture malformed = TestDatabase.tenant("sms-bad", true);
        arrange(wide, "{\"sms_window_start\": \"05:00\", \"sms_window_end\": \"23:00\"}");
        arrange(tooShort, "{\"sms_window_start\": \"20:00\", \"sms_window_end\": \"20:30\"}");
        arrange(lateOnly, "{\"sms_window_start\": \"21:30\", \"sms_window_end\": \"22:00\"}");
        arrange(malformed, "{\"sms_window_start\": \"8am\", \"sms_window_end\": \"23:59\"}");

        // Only this test's own tenants are touched: the shared test database holds other tests' rows.
        String own = java.util.stream.Stream.of(legacy, grouped, plain, partial, wide, tooShort, lateOnly, malformed)
                .map(f -> "'" + f.tenantId() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        String scoped = copy.replace("WHERE ts.id = w.id", "WHERE ts.id = w.id AND ts.tenant_id IN (" + own + ")");
        assertThat(scoped).isNotEqualTo(copy);
        TestDatabase.owner().sql(scoped).update();
        // Run again: the second run changes nothing.
        TestDatabase.owner().sql(scoped).update();

        JsonNode wideGroup = settingsOf(wide).get("module_groups").get("sms");
        assertThat(wideGroup.get("window_start").asString()).isEqualTo("06:00");
        assertThat(wideGroup.get("window_end").asString()).isEqualTo("21:00");
        // Not a window once clamped (under an hour, or beginning after the latest end): nothing copied, the default
        // applies.
        for (TestDatabase.Fixture notAWindow : List.of(tooShort, lateOnly)) {
            assertThat(settingsOf(notAWindow).get("module_groups").get("sms").has("window_start"))
                    .isFalse();
            assertThat(settingsOf(notAWindow).get("module_groups").get("sms").has("window_end"))
                    .isFalse();
        }

        // A side that is not a time is the default for that side: 08:00 to the end clamped to 21:00.
        JsonNode badGroup = settingsOf(malformed).get("module_groups").get("sms");
        assertThat(badGroup.get("window_start").asString()).isEqualTo("08:00");
        assertThat(badGroup.get("window_end").asString()).isEqualTo("21:00");

        JsonNode l = settingsOf(legacy);
        assertThat(l.get("module_groups").get("sms").get("window_start").asString())
                .isEqualTo("09:30");
        assertThat(l.get("module_groups").get("sms").get("window_end").asString())
                .isEqualTo("17:00");
        assertThat(l.get("module_groups").get("sms").get("include_balance").asBoolean())
                .isTrue();
        // The sender name was never approved, so it is not carried over; the other keys are as they were.
        assertThat(l.get("module_groups").get("sms").has("sender_id")).isFalse();
        assertThat(l.get("module_groups").get("sms").has("enabled")).isFalse();
        assertThat(l.get("display_name").asString()).isEqualTo("Test Legacy");
        assertThat(l.get("sms_window_start").asString()).isEqualTo("09:30");

        JsonNode g = settingsOf(grouped);
        assertThat(g.get("module_groups").get("sms").get("window_start").asString())
                .isEqualTo("10:00");
        assertThat(g.get("module_groups").get("sms").has("window_end")).isFalse();

        assertThat(settingsOf(plain).has("module_groups")).isFalse();

        JsonNode p = settingsOf(partial);
        assertThat(p.get("module_groups").get("sms").get("include_balance").asBoolean())
                .isFalse();
        assertThat(p.get("module_groups")
                        .get("retail.capabilities")
                        .get("units")
                        .asBoolean())
                .isTrue();
    }

    void arrange(TestDatabase.Fixture tenant, String json) {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (gen_random_uuid(), ?, CAST(? AS jsonb))"
                                + " ON CONFLICT (tenant_id) DO UPDATE SET settings = excluded.settings")
                .params(tenant.tenantId(), json)
                .update();
    }

    JsonNode settingsOf(TestDatabase.Fixture tenant) {
        String json = TestDatabase.owner()
                .sql("SELECT settings::text FROM tenant_settings WHERE tenant_id = ?")
                .param(tenant.tenantId())
                .query(String.class)
                .single();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
    }
}
