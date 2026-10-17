package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The SMS opt-out list through the API (FR-NTF-14, ADR-036 decision 8, spec 4.9): adding a number, pasting a list,
 * lifting a row, the searched and paged list, the member screen's control, who may do what, the audit trail with only
 * a masked number in it, and that one business never sees or lifts another's rows. Fabricated numbers throughout.
 */
class SmsOptOutIT extends LoanFixtures {

    static final String OPT_OUTS = "/api/v1/sms/opt-outs";

    /** What lifting needs: a reason, kept in the audit row. */
    static final Map<String, String> LIFT = Map.of("reason", "Checked with the person");

    final UUID admin = UUID.randomUUID();

    ResponseEntity<JsonNode> add(String phone, String scope) {
        return send(
                HttpMethod.POST, OPT_OUTS, as(admin, adminPerms, "*", null), Map.of("phone", phone, "scope", scope));
    }

    ResponseEntity<JsonNode> list(String query) {
        return send(HttpMethod.GET, OPT_OUTS + query, as(admin, adminPerms, "*", null), null);
    }

    ResponseEntity<JsonNode> paste(String numbers, String scope, Boolean dryRun) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("numbers", numbers);
        body.put("scope", scope);
        if (dryRun != null) {
            body.put("dry_run", dryRun);
        }
        return send(HttpMethod.POST, OPT_OUTS + "/import", as(admin, adminPerms, "*", null), body);
    }

    long active() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM sms_opt_outs WHERE tenant_id = ? AND removed_at IS NULL")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    long audits(String action) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?")
                .params(t.tenantId(), action)
                .query(Long.class)
                .single();
    }

    List<String> masked(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("recipient_masked").asString()));
        return out;
    }

    // ---- One number --------------------------------------------------------------------------------

    @Test
    void aNumberIsNormalisedAndTheSameNumberTypedAnotherWayIsTheSameRow() {
        ResponseEntity<JsonNode> first = add("0700000601", "reminders");
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("kind").asString()).isEqualTo("number");
        assertThat(first.getBody().get("scope").asString()).isEqualTo("reminders");
        assertThat(first.getBody().get("source").asString()).isEqualTo("number");
        assertThat(first.getBody().get("recipient_masked").asString()).isEqualTo("+256*****0601");
        assertThat(first.getBody().get("member_id").isNull()).isTrue();
        String id = first.getBody().get("id").asString();

        // Typed as +256 700 000 601 and as 256700000601: the same number, so the same row, and the scope is set as
        // asked.
        ResponseEntity<JsonNode> again = add("+256 700 000 601", "reminders");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id").asString()).isEqualTo(id);
        ResponseEntity<JsonNode> wider = add("256700000601", "all");
        assertThat(wider.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wider.getBody().get("id").asString()).isEqualTo(id);
        assertThat(wider.getBody().get("scope").asString()).isEqualTo("all");
        assertThat(active()).isEqualTo(1);

        // The audit rows carry the masked number and never the number.
        assertThat(audits("core.sms.opt_out.added")).isEqualTo(1);
        assertThat(audits("core.sms.opt_out.changed")).isEqualTo(1);
        String data = TestDatabase.owner()
                .sql(
                        "SELECT string_agg(data::text, ' ') FROM audit_log WHERE tenant_id = ? AND action LIKE 'core.sms.opt_out.%'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(data).contains("*********0601").doesNotContain("700000601").doesNotContain("0700000601");
        // The table holds the keyed hash, not the number.
        assertThat(TestDatabase.owner()
                        .sql("SELECT row_to_json(o)::text FROM sms_opt_outs o WHERE id = ?::uuid")
                        .param(id)
                        .query(String.class)
                        .single())
                .doesNotContain("700000601");
    }

    // JUSTIFICATION-A3: a new test for a concurrency case (review lesson: races) that the existing tests do not reach.
    /** Two administrators (or one double click) adding the same number at once: one row, one "added" audit, no error. */
    @Test
    void theSameNumberAddedAtOnceMakesOneRowAndNoError() throws Exception {
        List<org.springframework.http.HttpStatusCode> statuses =
                com.rincoltech.bms.testsupport.Api.race(4, () -> add("0700000611", "all"));
        assertThat(com.rincoltech.bms.testsupport.Api.count(statuses, HttpStatus.CREATED))
                .isEqualTo(1);
        assertThat(com.rincoltech.bms.testsupport.Api.count(statuses, HttpStatus.OK))
                .isEqualTo(3);
        assertThat(active()).isEqualTo(1);
        assertThat(audits("core.sms.opt_out.added")).isEqualTo(1);

        // And a member opted out from two screens at once is one row too.
        String member = member("Test Member Race", "+256700001211", t.headOffice(), false);
        List<org.springframework.http.HttpStatusCode> both = com.rincoltech.bms.testsupport.Api.race(
                3,
                () -> send(
                        HttpMethod.PUT,
                        memberPath(member),
                        as(officer, officerPerms, "*", null),
                        Map.of("scope", "all")));
        assertThat(com.rincoltech.bms.testsupport.Api.count(both, HttpStatus.OK))
                .isEqualTo(3);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM sms_opt_outs WHERE member_id = ?::uuid AND removed_at IS NULL")
                        .param(member)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // Review finding 7: adding and lifting the same number at once never ends in a 500 (a row lifted between the
        // insert finding it and the read is tried again), whichever way the requests interleave.
        String contested = add("0700000612", "all").getBody().get("id").asString();
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
        List<org.springframework.http.HttpStatusCode> mixed = com.rincoltech.bms.testsupport.Api.race(8, () -> {
            if (turn.getAndIncrement() % 2 == 0) {
                return add("0700000612", "all");
            }
            return send(HttpMethod.DELETE, OPT_OUTS + "/" + contested, as(admin, adminPerms, "*", null), LIFT);
        });
        assertThat(mixed).noneMatch(s -> s.is5xxServerError());
        assertThat(mixed).allMatch(s -> s.value() == 200 || s.value() == 201 || s.value() == 204 || s.value() == 404);
    }

    @Test
    void aNumberWeCannotReadWithoutGuessingIsRefusedAndNothingIsStored() {
        // Too short, a bare 9 digit number, a bare 10 digit number that is not 07 (a foreign number without its
        // country code), another country's, digits left over, letters.
        for (String bad : List.of(
                "0700 000", "700000601", "2125550101", "+15555550100", "07000006011", "call me", "07000000a1")) {
            ResponseEntity<JsonNode> r = add(bad, "all");
            assertThat(r.getStatusCode()).as(bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody().get("errors").get(0).get("code").asString())
                    .as(bad)
                    .isEqualTo("invalid_phone");
        }
        assertThat(add("0700000602", "everything").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(active()).isZero();
    }

    // ---- The list ----------------------------------------------------------------------------------

    @Test
    void theListIsPagedNewestFirstAndFilteredAndSearchedByTheDigitsOfAMaskedNumber() throws Exception {
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<JsonNode> added = add("070000070" + i, i % 2 == 0 ? "all" : "reminders");
            assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            // The order does not depend on how fast the requests were: each row gets a fixed time, a minute apart.
            TestDatabase.owner()
                    .sql("UPDATE sms_opt_outs SET created_at = ? WHERE id = ?::uuid")
                    .params(
                            java.sql.Timestamp.from(java.time.Instant.parse("2031-03-10T07:00:00Z")
                                    .plusSeconds(60L * i)),
                            added.getBody().get("id").asString())
                    .update();
        }
        // Newest first, paged by cursor: every row once, none twice.
        List<String> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<JsonNode> page = list("?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            all.addAll(masked(page.getBody()));
            cursor = page.getBody().get("next_cursor").isNull()
                    ? null
                    : page.getBody().get("next_cursor").asString();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(pages).isEqualTo(3);
        assertThat(all)
                .containsExactly("+256*****0705", "+256*****0704", "+256*****0703", "+256*****0702", "+256*****0701");

        assertThat(masked(list("?scope=all").getBody())).containsExactly("+256*****0704", "+256*****0702");
        assertThat(masked(list("?kind=member").getBody())).isEmpty();
        assertThat(masked(list("?kind=number&scope=reminders").getBody()))
                .containsExactly("+256*****0705", "+256*****0703", "+256*****0701");
        // Search by the last digits, as typed on a phone: only that row matches.
        assertThat(masked(list("?q=0703").getBody())).containsExactly("+256*****0703");
        assertThat(masked(list("?q=700000704").getBody())).isEmpty();
        // A search that matches every row pages like the list, and its cursor cannot continue another list: restart it.
        ResponseEntity<JsonNode> searched = list("?q=2560&limit=1");
        assertThat(masked(searched.getBody())).hasSize(1);
        assertThat(searched.getBody().get("next_cursor").isNull()).isFalse();
        String searchCursor = searched.getBody().get("next_cursor").asString();
        ResponseEntity<JsonNode> secondOfSearch = list("?q=2560&limit=1&cursor=" + searchCursor);
        assertThat(secondOfSearch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(masked(secondOfSearch.getBody())).hasSize(1).doesNotContainAnyElementsOf(masked(searched.getBody()));
        assertThat(list("?cursor=" + searchCursor).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(list("?scope=nonsense").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(list("?cursor=not-a-cursor").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- A pasted list -----------------------------------------------------------------------------

    @Test
    void aPastedListIsReadAsADryRunFirstThenAppliedAndRepeatingItChangesNothing() {
        String pasted = "0700000801\n+256 700 000 802; 256700000803,\r\n0700000801\nnot a number\n\n2125550101";
        ResponseEntity<JsonNode> dry = paste(pasted, "reminders", null);
        assertThat(dry.getStatusCode()).as("%s", dry.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(dry.getBody().get("dry_run").asBoolean()).isTrue();
        assertThat(dry.getBody().get("rows_read").asInt()).isEqualTo(6);
        assertThat(dry.getBody().get("added").asInt()).isEqualTo(3);
        assertThat(dry.getBody().get("invalid").asInt()).isEqualTo(2);
        assertThat(dry.getBody().get("unchanged").asInt()).isEqualTo(1);
        assertThat(active()).as("a dry run writes nothing").isZero();

        ResponseEntity<JsonNode> applied = paste(pasted, "reminders", false);
        assertThat(applied.getBody().get("dry_run").asBoolean()).isFalse();
        assertThat(applied.getBody().get("added").asInt()).isEqualTo(3);
        assertThat(active()).isEqualTo(3);
        // Each line is reported by its position and outcome, never by the number.
        List<String> outcomes = new ArrayList<>();
        applied.getBody()
                .get("rows")
                .forEach(r -> outcomes.add(
                        r.get("line").asInt() + ":" + r.get("outcome").asString()));
        assertThat(outcomes)
                .containsExactly(
                        "1:added", "2:added", "3:added", "4:duplicate_in_list", "5:invalid_phone", "6:invalid_phone");
        assertThat(applied.getBody().toString()).doesNotContain("700000801").doesNotContain("2125550101");
        assertThat(audits("core.sms.opt_out.added")).isEqualTo(3);
        assertThat(audits("core.sms.opt_out.imported")).isEqualTo(1);

        // The same paste again: nothing added, nothing changed, nothing audited.
        ResponseEntity<JsonNode> repeat = paste(pasted, "reminders", false);
        assertThat(repeat.getBody().get("added").asInt()).isZero();
        assertThat(repeat.getBody().get("changed").asInt()).isZero();
        assertThat(active()).isEqualTo(3);
        assertThat(audits("core.sms.opt_out.imported")).isEqualTo(1);
    }

    @Test
    void aPastedListNeverWeakensABlockButCanStrengthenIt() {
        add("0700000811", "all");
        add("0700000812", "reminders");
        ResponseEntity<JsonNode> weaker = paste("0700000811", "reminders", false);
        assertThat(weaker.getBody().get("unchanged").asInt()).isEqualTo(1);
        ResponseEntity<JsonNode> stronger = paste("0700000812", "all", false);
        assertThat(stronger.getBody().get("changed").asInt()).isEqualTo(1);
        Map<String, String> scopes = new LinkedHashMap<>();
        list("?limit=10")
                .getBody()
                .get("items")
                .forEach(i -> scopes.put(
                        i.get("recipient_masked").asString(), i.get("scope").asString()));
        assertThat(scopes).containsEntry("+256*****0811", "all").containsEntry("+256*****0812", "all");
    }

    @Test
    void aPastedListIsBounded() {
        assertThat(paste("   ", "all", false).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        String tooMany = java.util.stream.IntStream.range(0, 2001)
                .mapToObj(i -> String.format("07%08d", i))
                .collect(Collectors.joining("\n"));
        ResponseEntity<JsonNode> r = paste(tooMany, "all", false);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("errors").get(0).get("code").asString()).isEqualTo("too_many_rows");
        assertThat(active()).isZero();
        String justEnough = java.util.stream.IntStream.range(0, 2000)
                .mapToObj(i -> String.format("07%08d", i))
                .collect(Collectors.joining("\n"));
        ResponseEntity<JsonNode> dry = paste(justEnough, "all", true);
        assertThat(dry.getBody().get("rows_read").asInt()).isEqualTo(2000);
        assertThat(dry.getBody().get("added").asInt()).isEqualTo(2000);
    }

    // ---- Lifting -----------------------------------------------------------------------------------

    @Test
    void liftingARowRemovesItFromTheListKeepsTheHistoryAndIsAudited() {
        String id = add("0700000901", "all").getBody().get("id").asString();
        ResponseEntity<JsonNode> removed =
                send(HttpMethod.DELETE, OPT_OUTS + "/" + id, as(admin, adminPerms, "*", null), LIFT);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(masked(list("").getBody())).isEmpty();
        assertThat(audits("core.sms.opt_out.removed")).isEqualTo(1);
        // The row stays, marked removed, with who lifted it.
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT removed_at IS NOT NULL AS removed, removed_by FROM sms_opt_outs WHERE id = ?::uuid")
                .param(id)
                .query()
                .singleRow();
        assertThat(row.get("removed")).isEqualTo(true);
        assertThat(row.get("removed_by")).isEqualTo(admin);
        // Lifting twice, or something that never existed, is 404.
        assertThat(send(HttpMethod.DELETE, OPT_OUTS + "/" + id, as(admin, adminPerms, "*", null), LIFT)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, OPT_OUTS + "/" + UUID.randomUUID(), as(admin, adminPerms, "*", null), LIFT)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // Adding it again makes a new row.
        assertThat(add("0700000901", "reminders").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(active()).isEqualTo(1);
    }

    // ---- Who may, and other businesses -------------------------------------------------------------

    @Test
    void onlyTheTenantAdminManagesTheListAndNotificationReadersCanSeeIt() {
        UUID manager = UUID.randomUUID();
        UUID officerUser = UUID.randomUUID();
        UUID auditor = UUID.randomUUID();
        String auditorPerms = permissionsOf("auditor");
        String id = add("0700001001", "all").getBody().get("id").asString();
        Map<String, Object> body = Map.of("phone", "0700001002", "scope", "all");
        for (var who : List.of(
                Map.entry(manager, managerPerms),
                Map.entry(auditor, auditorPerms),
                Map.entry(officerUser, officerPerms))) {
            HttpHeaders h = as(who.getKey(), who.getValue(), "*", null);
            assertThat(send(HttpMethod.POST, OPT_OUTS, h, body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(send(HttpMethod.POST, OPT_OUTS + "/import", h, Map.of("numbers", "0700001003", "scope", "all"))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(send(HttpMethod.DELETE, OPT_OUTS + "/" + id, h, LIFT).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(send(HttpMethod.GET, OPT_OUTS, as(manager, managerPerms, "*", null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.GET, OPT_OUTS, as(auditor, auditorPerms, "*", null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.GET, OPT_OUTS, as(officerUser, officerPerms, "*", null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(active()).isEqualTo(1);
    }

    HttpHeaders otherAdmin(TestDatabase.Fixture other) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", other.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", adminPerms);
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }

    @Test
    void anotherBusinessSeesNoneOfTheRowsAndCannotLiftThem() {
        String id = add("0700001101", "all").getBody().get("id").asString();
        TestDatabase.Fixture other = TestDatabase.tenant("sms-optout-other", true);
        JsonNode theirs = http.exchange(
                        OPT_OUTS, HttpMethod.GET, new HttpEntity<>(null, otherAdmin(other)), JsonNode.class)
                .getBody();
        assertThat(theirs.get("items")).isEmpty();
        ResponseEntity<JsonNode> lift = http.exchange(
                OPT_OUTS + "/" + id, HttpMethod.DELETE, new HttpEntity<>(LIFT, otherAdmin(other)), JsonNode.class);
        assertThat(lift.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(active()).as("our row is still active").isEqualTo(1);
        // The same number on their list is their own row.
        ResponseEntity<JsonNode> same = http.exchange(
                OPT_OUTS,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("phone", "0700001101", "scope", "all"), otherAdmin(other)),
                JsonNode.class);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(same.getBody().get("id").asString()).isNotEqualTo(id);
        assertThat(active()).isEqualTo(1);
    }

    // ---- The member screen's control ---------------------------------------------------------------

    static String memberPath(String member) {
        return "/api/v1/lending/members/" + member + "/sms-opt-out";
    }

    @Test
    void staffOptAMemberOutAndBackInAndEveryChangeIsAudited() {
        String member = member("Test Member One", "+256700001201", t.headOffice(), false);
        HttpHeaders asOfficer = as(officer, officerPerms, "*", null);
        JsonNode before =
                send(HttpMethod.GET, memberPath(member), asOfficer, null).getBody();
        assertThat(before.get("opted_out").asBoolean()).isFalse();
        assertThat(before.get("scope").isNull()).isTrue();

        ResponseEntity<JsonNode> out =
                send(HttpMethod.PUT, memberPath(member), asOfficer, Map.of("scope", "reminders"));
        assertThat(out.getStatusCode()).as("%s", out.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(out.getBody().get("opted_out").asBoolean()).isTrue();
        assertThat(out.getBody().get("scope").asString()).isEqualTo("reminders");
        assertThat(out.getBody().get("recipient_masked").asString()).isEqualTo("+256*****1201");

        // The same again changes nothing and audits nothing; a wider scope changes the one row.
        assertThat(send(HttpMethod.PUT, memberPath(member), asOfficer, Map.of("scope", "reminders"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(audits("core.sms.opt_out.added")).isEqualTo(1);
        ResponseEntity<JsonNode> wider = send(HttpMethod.PUT, memberPath(member), asOfficer, Map.of("scope", "all"));
        assertThat(wider.getBody().get("scope").asString()).isEqualTo("all");
        assertThat(audits("core.sms.opt_out.changed")).isEqualTo(1);
        assertThat(active()).isEqualTo(1);

        // The administrator's list shows it as a member row, with no number and no name.
        JsonNode listed = list("").getBody().get("items").get(0);
        assertThat(listed.get("kind").asString()).isEqualTo("member");
        assertThat(listed.get("member_id").asString()).isEqualTo(member);
        assertThat(listed.get("source").asString()).isEqualTo("staff");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT recipient_hash IS NULL FROM sms_opt_outs WHERE member_id = ?::uuid AND removed_at IS NULL")
                        .param(member)
                        .query(Boolean.class)
                        .single())
                .as("a member row follows the member to any number")
                .isTrue();

        // Lifting is the tenant administrator's, with a reason: here the administrator does it.
        HttpHeaders asAdmin = as(admin, adminPerms, "*", null);
        ResponseEntity<JsonNode> in = send(HttpMethod.DELETE, memberPath(member), asAdmin, LIFT);
        assertThat(in.getBody().get("opted_out").asBoolean()).isFalse();
        assertThat(audits("core.sms.opt_out.removed")).isEqualTo(1);
        assertThat(active()).isZero();
        // Lifting what is not there is not an error, and writes nothing more.
        assertThat(send(HttpMethod.DELETE, memberPath(member), asAdmin, LIFT).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(audits("core.sms.opt_out.removed")).isEqualTo(1);
        assertThat(send(HttpMethod.PUT, memberPath(member), asOfficer, Map.of("scope", "nonsense"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // JUSTIFICATION-A3: a new test for review finding 2 (who may lift an opt-out); no existing test covers a refusal.
    /** The opt-out protects the member from being pestered; one click by a field officer must not undo it. */
    @Test
    void aLoanOfficerCannotLiftOrNarrowAnOptOutAndTheAdministratorNeedsAReasonThatIsAudited() {
        String member = member("Test Member Three", "+256700001401", t.headOffice(), false);
        HttpHeaders asOfficer = as(officer, officerPerms, "*", null);
        assertThat(send(HttpMethod.PUT, memberPath(member), asOfficer, Map.of("scope", "all"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // The officer cannot lift it (403 on the route), nor narrow it from every message to reminders only.
        assertThat(send(HttpMethod.DELETE, memberPath(member), asOfficer, LIFT).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> narrow =
                send(HttpMethod.PUT, memberPath(member), asOfficer, Map.of("scope", "reminders"));
        assertThat(narrow.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(narrow.getBody().get("code").asString()).isEqualTo("permission_denied");
        assertThat(send(HttpMethod.GET, memberPath(member), asOfficer, null)
                        .getBody()
                        .get("scope")
                        .asString())
                .isEqualTo("all");
        assertThat(audits("core.sms.opt_out.removed")).isZero();
        assertThat(audits("core.sms.opt_out.changed")).isZero();
        // Making it wider, the other way, is allowed for the officer: reminders to all.
        String other = member("Test Member Four", "+256700001402", t.headOffice(), false);
        send(HttpMethod.PUT, memberPath(other), asOfficer, Map.of("scope", "reminders"));
        assertThat(send(HttpMethod.PUT, memberPath(other), asOfficer, Map.of("scope", "all"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // The administrator must say why: a blank, a too short one and a missing one are refused and nothing is lifted.
        HttpHeaders asAdmin = as(admin, adminPerms, "*", null);
        assertThat(send(HttpMethod.DELETE, memberPath(member), asAdmin, Map.of("reason", "  "))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(send(HttpMethod.DELETE, memberPath(member), asAdmin, Map.of("reason", "ok"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(send(HttpMethod.DELETE, memberPath(member), asAdmin, Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(active()).isEqualTo(2);

        // With a reason the administrator lifts it, and the reason is in the audit row.
        assertThat(send(HttpMethod.DELETE, memberPath(member), asAdmin, Map.of("reason", "The member asked in person"))
                        .getBody()
                        .get("opted_out")
                        .asBoolean())
                .isFalse();
        String data = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.sms.opt_out.removed'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(data).contains("The member asked in person");
        // Narrowing is a partial lift: the administrator needs a reason for it too, and nothing changes without one.
        assertThat(send(HttpMethod.PUT, memberPath(other), asAdmin, Map.of("scope", "reminders"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(send(HttpMethod.PUT, memberPath(other), asAdmin, Map.of("scope", "reminders", "reason", "ok"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(send(HttpMethod.GET, memberPath(other), asAdmin, null)
                        .getBody()
                        .get("scope")
                        .asString())
                .isEqualTo("all");
        assertThat(send(
                                HttpMethod.PUT,
                                memberPath(other),
                                asAdmin,
                                Map.of("scope", "reminders", "reason", "The member asked for reminders only"))
                        .getBody()
                        .get("scope")
                        .asString())
                .isEqualTo("reminders");
        String changed = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.sms.opt_out.changed'"
                        + " ORDER BY created_at DESC LIMIT 1")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(changed).contains("The member asked for reminders only");
        // Widening needs no reason.
        assertThat(send(HttpMethod.PUT, memberPath(other), asAdmin, Map.of("scope", "all"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // A number row narrowed through the list needs a reason as well; the same number set again as it is does not.
        ResponseEntity<JsonNode> narrowNumber = add("0700001404", "all");
        assertThat(narrowNumber.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add("0700001404", "reminders").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(add("0700001404", "all").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(
                                HttpMethod.POST,
                                OPT_OUTS,
                                asAdmin,
                                Map.of(
                                        "phone",
                                        "0700001404",
                                        "scope",
                                        "reminders",
                                        "reason",
                                        "Number now belongs to a shop"))
                        .getBody()
                        .get("scope")
                        .asString())
                .isEqualTo("reminders");

        // The list's own lift (a number row) follows the same rule: a reason, and the administrator only.
        String numberRow = add("0700001403", "all").getBody().get("id").asString();
        assertThat(send(HttpMethod.DELETE, OPT_OUTS + "/" + numberRow, asAdmin, Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(send(HttpMethod.DELETE, OPT_OUTS + "/" + numberRow, asAdmin, Map.of("reason", "Wrong number typed"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void theMemberControlFollowsTheMembersBranchScopeAndPermissions() {
        String member = member("Test Member Two", "+256700001301", t.headOffice(), false);
        // An officer whose scope is the second branch only cannot see or change a head office member: 404, as for any
        // member route.
        HttpHeaders elsewhere =
                as(UUID.randomUUID(), officerPerms, t.secondBranch().toString(), null);
        assertThat(send(HttpMethod.GET, memberPath(member), elsewhere, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PUT, memberPath(member), elsewhere, Map.of("scope", "all"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // Lifting is the administrator's; an administrator of the second branch only does not see this member either.
        HttpHeaders adminElsewhere =
                as(UUID.randomUUID(), adminPerms, t.secondBranch().toString(), null);
        assertThat(send(HttpMethod.DELETE, memberPath(member), adminElsewhere, LIFT)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // A cashier may look at a member (lending.members.read) but not change the opt-out (lending.members.update).
        HttpHeaders cashier = as(UUID.randomUUID(), permissionsOf("cashier"), "*", null);
        assertThat(send(HttpMethod.GET, memberPath(member), cashier, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.PUT, memberPath(member), cashier, Map.of("scope", "all"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.DELETE, memberPath(member), cashier, LIFT).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // A member that does not exist, or belongs to another business, is not found.
        assertThat(send(
                                HttpMethod.PUT,
                                memberPath(UUID.randomUUID().toString()),
                                as(officer, officerPerms, "*", null),
                                Map.of("scope", "all"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        TestDatabase.Fixture other = TestDatabase.tenant("sms-optout-member", true);
        HttpHeaders theirs = otherAdmin(other);
        assertThat(http.exchange(
                                memberPath(member),
                                HttpMethod.PUT,
                                new HttpEntity<>(Map.of("scope", "all"), theirs),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(active()).isZero();
    }
}
