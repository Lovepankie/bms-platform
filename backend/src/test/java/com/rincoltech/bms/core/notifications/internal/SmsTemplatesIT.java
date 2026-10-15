package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import com.rincoltech.bms.testsupport.Api;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * A tenant's own SMS wording through the API (FR-NTF-03, ADR-036 decision 9, spec 4.10): the list with defaults and
 * placeholders, saving under {@code If-Match} on the override's version, every refusal with its code, the preview with
 * encoding, segments and the cost estimate from the tenant's own history, escaping, the audit trail, who may do what,
 * and that one business never sees another's wording. Fabricated names, no number is sent.
 */
class SmsTemplatesIT extends LoanFixtures {

    static final String TEMPLATES = "/api/v1/notification-templates";
    static final String DEPOSIT = TEMPLATES + "/savings.deposit/sms";

    final UUID admin = UUID.randomUUID();

    ResponseEntity<JsonNode> list() {
        return send(HttpMethod.GET, TEMPLATES, as(admin, adminPerms, "*", null), null);
    }

    JsonNode deposit() {
        for (JsonNode item : list().getBody().get("items")) {
            if (item.get("event_key").asString().equals("savings.deposit")) {
                return item;
            }
        }
        throw new AssertionError("no savings.deposit template");
    }

    ResponseEntity<JsonNode> save(String text, String version) {
        return send(HttpMethod.PUT, DEPOSIT, as(admin, adminPerms, "*", version), Map.of("text", text));
    }

    ResponseEntity<JsonNode> revert(String version) {
        return send(HttpMethod.DELETE, DEPOSIT, as(admin, adminPerms, "*", version), null);
    }

    ResponseEntity<JsonNode> preview(Object text) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (text != null) {
            body.put("text", text);
        }
        return send(HttpMethod.POST, DEPOSIT + "/preview", as(admin, adminPerms, "*", null), body);
    }

    String firstErrorCode(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        return r.getBody().get("errors").get(0).get("code").asString();
    }

    long audits(String action) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?")
                .params(t.tenantId(), action)
                .query(Long.class)
                .single();
    }

    // ---- The list and the defaults -----------------------------------------------------------------

    @Test
    void theListHasTheEventsTheirDefaultsPlaceholdersAndLimits() {
        ResponseEntity<JsonNode> r = list();
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> keys = new java.util.ArrayList<>();
        r.getBody().get("items").forEach(i -> keys.add(i.get("event_key").asString()));
        assertThat(keys).containsExactly("savings.deposit", "savings.withdrawal");

        JsonNode dep = deposit();
        assertThat(dep.get("channel").asString()).isEqualTo("sms");
        assertThat(dep.get("default_text").asString()).startsWith("{tenant_name}: Deposit of");
        assertThat(dep.get("override").isNull()).isTrue();
        List<String> names = new java.util.ArrayList<>();
        dep.get("placeholders").forEach(p -> names.add(p.get("name").asString()));
        assertThat(names).containsExactly("tenant_name", "currency", "amount", "account_no", "receipt_no");
        assertThat(names).doesNotContain("balance");
        assertThat(dep.get("max_chars").asInt()).isEqualTo(320);
        assertThat(dep.get("max_worst_case_segments").asInt()).isEqualTo(4);
        // The sample text is the default filled with fabricated data and this business's own name.
        assertThat(dep.get("sample_text").asString())
                .startsWith("Test Tenant " + t.slug() + ": Deposit of UGX 50,000 on account SV000123");
        JsonNode m = dep.get("measure");
        assertThat(m.get("encoding").asString()).isEqualTo("gsm7");
        assertThat(m.get("segments").asInt()).isEqualTo(1);
        assertThat(m.get("worst_case_segments").asInt()).isBetween(1, 4);
        // No history of cost, so no estimate: never a made-up price.
        assertThat(m.get("estimated_cost_minor").isNull()).isTrue();
    }

    // ---- Saving ------------------------------------------------------------------------------------

    @Test
    void savingNeedsTheVersionOfTheOverrideZeroWhileThereIsNoneAndEveryChangeIsAudited() {
        assertThat(save("Paid {amount} on {account_no}", null).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        ResponseEntity<JsonNode> first = save("Paid {amount} on {account_no}", "0");
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("override").get("text").asString()).isEqualTo("Paid {amount} on {account_no}");
        assertThat(first.getBody().get("override").get("version").asInt()).isEqualTo(1);
        assertThat(first.getBody().get("sample_text").asString()).isEqualTo("Paid 50,000 on SV000123");
        assertThat(deposit().get("override").get("version").asInt()).isEqualTo(1);

        // The same version again is stale; the current one is accepted and bumps.
        ResponseEntity<JsonNode> stale = save("Other {amount} {account_no}", "0");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("version_conflict");
        assertThat(deposit().get("override").get("text").asString()).isEqualTo("Paid {amount} on {account_no}");
        ResponseEntity<JsonNode> second = save("Received {amount} on {account_no}", "1");
        assertThat(second.getBody().get("override").get("version").asInt()).isEqualTo(2);

        // Saving what is already saved writes nothing new.
        ResponseEntity<JsonNode> same = save("Received {amount} on {account_no}", "2");
        assertThat(same.getBody().get("override").get("version").asInt()).isEqualTo(2);
        assertThat(audits("core.sms.template.changed")).isEqualTo(2);

        // Line endings are normalised and the edges trimmed.
        ResponseEntity<JsonNode> crlf = save("  Received {amount}\r\non {account_no}  ", "2");
        assertThat(crlf.getBody().get("override").get("text").asString())
                .isEqualTo("Received {amount}\non {account_no}");

        // Reverting needs the version too, goes back to the default and is audited with what it said.
        assertThat(revert("1").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> reverted = revert("3");
        assertThat(reverted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reverted.getBody().get("override").isNull()).isTrue();
        assertThat(audits("core.sms.template.reverted")).isEqualTo(1);
        Map<String, Object> row = TestDatabase.owner()
                .sql(
                        "SELECT data::text AS data FROM audit_log WHERE tenant_id = ? AND action = 'core.sms.template.reverted'")
                .param(t.tenantId())
                .query()
                .singleRow();
        assertThat((String) row.get("data")).contains("Received {amount}");
        // And a new override starts again at version 1 (zero while there is none).
        assertThat(save("Fresh {amount} {account_no}", "0")
                        .getBody()
                        .get("override")
                        .get("version")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void twoAdministratorsCreatingTheFirstOverrideAtOnceHaveOneWinner() throws Exception {
        List<HttpStatusCode> statuses = Api.race(
                2,
                () -> send(
                        HttpMethod.PUT,
                        DEPOSIT,
                        as(admin, adminPerms, "*", "0"),
                        Map.of("text", "Paid {amount} on {account_no}")));
        assertThat(Api.count(statuses, HttpStatus.OK)).isEqualTo(1);
        assertThat(Api.count(statuses, HttpStatus.CONFLICT)).isEqualTo(1);
        assertThat(deposit().get("override").get("version").asInt()).isEqualTo(1);
        assertThat(audits("core.sms.template.changed")).isEqualTo(1);
    }

    // ---- Refusals ----------------------------------------------------------------------------------

    @Test
    void everyRefusalHasItsOwnCodeAndSavesNothing() {
        // Acceptance (issue #218): saving {unknown} fails with unknown_placeholder.
        assertThat(firstErrorCode(save("Paid {unknown} on {account_no} {amount}", "0")))
                .isEqualTo("unknown_placeholder");
        assertThat(firstErrorCode(save("Paid {amount} on {account_no}. Balance {balance}", "0")))
                .isEqualTo("unknown_placeholder");
        assertThat(firstErrorCode(save("Paid {amount on {account_no}", "0"))).isEqualTo("malformed_placeholder");
        assertThat(firstErrorCode(save("Paid {{amount}} {account_no}", "0"))).isEqualTo("malformed_placeholder");
        assertThat(firstErrorCode(save("Thank you", "0"))).isEqualTo("missing_required_placeholder");
        assertThat(firstErrorCode(save("Paid {amount} {account_no} " + "x".repeat(320), "0")))
                .isEqualTo("too_long");
        assertThat(firstErrorCode(save("Paid {amount} {account_no}‮", "0"))).isEqualTo("invalid_character");
        assertThat(firstErrorCode(save("a".repeat(250) + " {amount} {account_no}", "0")))
                .isEqualTo("too_many_segments");
        ResponseEntity<JsonNode> blank = save("   ", "0");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(deposit().get("override").isNull()).isTrue();
        assertThat(audits("core.sms.template.changed")).isZero();
        // The message names the placeholder, and the error is on the field.
        ResponseEntity<JsonNode> r = save("Paid {nonsense} {amount} {account_no}", "0");
        assertThat(r.getBody().get("errors").get(0).get("field").asString()).isEqualTo("text");
        assertThat(r.getBody().get("errors").get(0).get("message").asString()).contains("{nonsense}");
    }

    @Test
    void anUnknownEventOrChannelIsNotFound() {
        assertThat(send(
                                HttpMethod.PUT,
                                TEMPLATES + "/savings.nothing/sms",
                                as(admin, adminPerms, "*", "0"),
                                Map.of("text", "x"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(
                                HttpMethod.PUT,
                                TEMPLATES + "/savings.deposit/email",
                                as(admin, adminPerms, "*", "0"),
                                Map.of("text", "x {amount}"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(
                                HttpMethod.POST,
                                TEMPLATES + "/savings.nothing/sms/preview",
                                as(admin, adminPerms, "*", null),
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(
                                HttpMethod.DELETE,
                                TEMPLATES + "/savings.deposit/whatsapp",
                                as(admin, adminPerms, "*", "0"),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- The preview -------------------------------------------------------------------------------

    @Test
    void thePreviewShowsExactlyWhatWouldBeSentAndHowManySegments() {
        ResponseEntity<JsonNode> r = preview("Paid {currency} {amount} into {account_no}. Ref {receipt_no}");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("text").asString()).isEqualTo("Paid UGX 50,000 into SV000123. Ref RC-0042");
        assertThat(r.getBody().get("measure").get("encoding").asString()).isEqualTo("gsm7");
        assertThat(r.getBody().get("measure").get("units").asInt())
                .isEqualTo("Paid UGX 50,000 into SV000123. Ref RC-0042".length());
        // Nothing was saved by previewing.
        assertThat(deposit().get("override").isNull()).isTrue();
        // With no text: the saved wording, else the default.
        assertThat(preview(null).getBody().get("text").asString()).contains(": Deposit of UGX 50,000");
        save("Saved {amount} {account_no}", "0");
        assertThat(preview(null).getBody().get("text").asString()).isEqualTo("Saved 50,000 SV000123");
        // The preview refuses what a save would refuse.
        assertThat(firstErrorCode(preview("Paid {unknown} {amount} {account_no}")))
                .isEqualTo("unknown_placeholder");
    }

    @Test
    void aBusinessNameWithMarkupQuotesOrAnotherScriptIsShownAsPlainTextAndSwitchesTheEncoding() {
        for (String name : List.of("A & B <Sacco> \"Q\" 'x'", "مشروع الأمل", "幸福銀行", "{amount} $1")) {
            TestDatabase.owner()
                    .sql("UPDATE tenants SET name = ? WHERE id = ?")
                    .params(name, t.tenantId())
                    .update();
            ResponseEntity<JsonNode> r = preview("{tenant_name}: paid {amount} on {account_no}");
            assertThat(r.getStatusCode()).as(name).isEqualTo(HttpStatus.OK);
            // Plain text, as sent: not HTML escaped, not read for placeholders again.
            assertThat(r.getBody().get("text").asString()).isEqualTo(name + ": paid 50,000 on SV000123");
            boolean latin = name.chars().allMatch(c -> c < 0x250);
            assertThat(r.getBody().get("measure").get("encoding").asString()).isEqualTo(latin ? "gsm7" : "ucs2");
        }
        // The raw response carries the characters, JSON-escaped only as JSON requires.
        ResponseEntity<String> raw = http.exchange(
                DEPOSIT + "/preview",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("text", "{tenant_name} {amount} {account_no}"), as(admin, adminPerms, "*", null)),
                String.class);
        assertThat(raw.getBody()).doesNotContain("&amp;").doesNotContain("&lt;").doesNotContain("\\u003c");
    }

    // JUSTIFICATION-A3: review finding 4 reaches the preview; no existing test has a business name over the limit.
    @Test
    void aBusinessNameTooLongForTheWordingIsNeverCutSoThePreviewShowsTheDefaultWordsAndSaysWhy() {
        String longName = "A".repeat(101);
        TestDatabase.owner()
                .sql("UPDATE tenants SET name = ? WHERE id = ?")
                .params(longName, t.tenantId())
                .update();
        assertThat(save("Paid {amount} on {account_no} to {tenant_name}", "0").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> r = preview(null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // What would be sent: the default words with the whole name, and the reason the saved words are not used.
        assertThat(r.getBody().get("text").asString()).startsWith(longName + ": Deposit of UGX 50,000");
        assertThat(r.getBody().get("note").asString()).contains("longer than the place the wording has for it");
        assertThat(deposit().get("note").asString()).contains("default words are sent");
        // A name that fits is used as it is, and there is no note.
        TestDatabase.owner()
                .sql("UPDATE tenants SET name = ? WHERE id = ?")
                .params("A".repeat(100), t.tenantId())
                .update();
        ResponseEntity<JsonNode> fits = preview(null);
        assertThat(fits.getBody().get("text").asString()).isEqualTo("Paid 50,000 on SV000123 to " + "A".repeat(100));
        assertThat(fits.getBody().get("note").isNull()).isTrue();
    }

    @Test
    void theCostEstimateIsFromTheTenantsOwnSentMessagesOverTheLastThirtyDaysAndNeverInvented() {
        // 4 sent segments for 128 minor units: 32 a segment.
        insertDelivery("sent", 1, 32, "UGX", "30 minutes");
        insertDelivery("delivered", 1, 32, "UGX", "2 days");
        insertDelivery("sent", 2, 64, "UGX", "29 days");
        // Outside the 30 days, failed, unpriced and another business's messages do not count.
        insertDelivery("sent", 1, 900, "UGX", "31 days");
        insertDelivery("failed", 1, 900, "UGX", "1 hour");
        TestDatabase.Fixture other = TestDatabase.tenant("sms-cost", true);
        insertDeliveryFor(other, "sent", 1, 5000, "UGX", "1 hour");

        JsonNode m = preview("Paid {amount} on {account_no}").getBody().get("measure");
        assertThat(m.get("segments").asInt()).isEqualTo(1);
        assertThat(m.get("estimated_cost_minor").asLong()).isEqualTo(32);
        assertThat(m.get("currency").asString()).isEqualTo("UGX");
        assertThat(deposit().get("measure").get("estimated_cost_minor").asLong())
                .isEqualTo(32);

        // Two currencies cannot be averaged: no estimate rather than a wrong one.
        insertDelivery("sent", 1, 1, "KES", "1 hour");
        assertThat(preview("Paid {amount} on {account_no}")
                        .getBody()
                        .get("measure")
                        .get("estimated_cost_minor")
                        .isNull())
                .isTrue();
    }

    void insertDelivery(String status, int segments, long cost, String currency, String age) {
        insertDeliveryFor(t, status, segments, cost, currency, age);
    }

    void insertDeliveryFor(
            TestDatabase.Fixture tenant, String status, int segments, long cost, String currency, String age) {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO sms_deliveries (id, tenant_id, created_at, outbox_id, template_key, category, recipient_masked,"
                                + " recipient_hash, provider, segments, cost_minor, currency, status)"
                                + " VALUES (gen_random_uuid(), ?, now() - CAST(? AS interval), gen_random_uuid(), 'savings.deposit',"
                                + " 'transactional', '+256*****0001', repeat('a', 64), 'fake', ?, ?, ?, ?)")
                .params(tenant.tenantId(), age, segments, cost, currency, status)
                .update();
    }

    // ---- Who may do what, and other businesses -----------------------------------------------------

    @Test
    void onlyTheTenantAdminChangesWordingAndNotificationReadersCanSeeIt() {
        UUID manager = UUID.randomUUID();
        UUID officerUser = UUID.randomUUID();
        UUID auditor = UUID.randomUUID();
        String auditorPerms = permissionsOf("auditor");
        // The branch manager and the auditor read the list; the loan officer does not.
        assertThat(send(HttpMethod.GET, TEMPLATES, as(manager, managerPerms, "*", null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.GET, TEMPLATES, as(auditor, auditorPerms, "*", null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.GET, TEMPLATES, as(officerUser, officerPerms, "*", null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> body = Map.of("text", "Paid {amount} on {account_no}");
        for (var who : List.of(
                Map.entry(manager, managerPerms),
                Map.entry(auditor, auditorPerms),
                Map.entry(officerUser, officerPerms))) {
            HttpHeaders h = as(who.getKey(), who.getValue(), "*", "0");
            assertThat(send(HttpMethod.PUT, DEPOSIT, h, body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(send(HttpMethod.DELETE, DEPOSIT, h, null).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(send(HttpMethod.POST, DEPOSIT + "/preview", h, body).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(send(
                                    HttpMethod.POST,
                                    "/api/v1/sms/test-message",
                                    h,
                                    Map.of("event_key", "savings.deposit", "to", "0700000601"))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(deposit().get("override").isNull()).isTrue();
    }

    @Test
    void anotherBusinessNeverSeesOrTouchesThisBusinessesWording() {
        assertThat(save("Our words {amount} {account_no}", "0").getStatusCode()).isEqualTo(HttpStatus.OK);
        TestDatabase.Fixture other = TestDatabase.tenant("sms-words", true);
        HttpHeaders theirs = otherHeaders(other, null);
        JsonNode theirList = http.exchange(
                        TEMPLATES,
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(null, theirs),
                        JsonNode.class)
                .getBody();
        theirList
                .get("items")
                .forEach(i -> assertThat(i.get("override").isNull()).isTrue());

        // They cannot revert it: they have none, so version 1 is a conflict and 0 changes nothing; ours stays.
        ResponseEntity<JsonNode> revert = http.exchange(
                DEPOSIT,
                HttpMethod.DELETE,
                new org.springframework.http.HttpEntity<>(null, otherHeaders(other, "1")),
                JsonNode.class);
        assertThat(revert.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> noop = http.exchange(
                DEPOSIT,
                HttpMethod.DELETE,
                new org.springframework.http.HttpEntity<>(null, otherHeaders(other, "0")),
                JsonNode.class);
        assertThat(noop.getBody().get("override").isNull()).isTrue();
        // Their own save is their own: version 0 is free for them while ours is at 1.
        ResponseEntity<JsonNode> theirSave = http.exchange(
                DEPOSIT,
                HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("text", "Their words {amount} {account_no}"), otherHeaders(other, "0")),
                JsonNode.class);
        assertThat(theirSave.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deposit().get("override").get("text").asString()).isEqualTo("Our words {amount} {account_no}");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM sms_template_overrides WHERE event_key = 'savings.deposit' AND tenant_id IN (?, ?)")
                        .params(t.tenantId(), other.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    HttpHeaders otherHeaders(TestDatabase.Fixture other, String etag) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", other.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", adminPerms);
        h.add("X-Dev-Branch-Ids", "*");
        if (etag != null) {
            h.add(HttpHeaders.IF_MATCH, etag);
        }
        return h;
    }
}
