package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * "Staff of a business? Find your sign-in page" (issue #383): the public platform route that emails a staff user the sign-in
 * page of each business where their address is an active staff user. Fabricated businesses and addresses only; every call is
 * made from its own documentation address (RFC 5737) so the per-address limit of one test never touches another.
 */
class FindSignInIT extends IntegrationTest {

    static final String PATH = "/api/v1/platform/find-sign-in";
    static final String TEMPLATE = "identity.find_sign_in";
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);

    @Autowired
    TestRestTemplate http;

    Api platform;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
    }

    static String freshAddress() {
        int n = ADDRESS.getAndIncrement();
        return "203.0." + (n / 250) + "." + (n % 250 + 1);
    }

    ResponseEntity<JsonNode> ask(String email) {
        return ask(email, freshAddress());
    }

    ResponseEntity<JsonNode> ask(String email, String from) {
        return platform.call(HttpMethod.POST, PATH, Map.of("email", email), null, Map.of("X-Forwarded-For", from));
    }

    static long sent(String email) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM notification_outbox WHERE template_key = ? AND recipient = ?")
                .params(TEMPLATE, email)
                .query(Long.class)
                .single();
    }

    static String links(String email) {
        return TestDatabase.owner()
                .sql("""
                        SELECT params->>'links' FROM notification_outbox WHERE template_key = ? AND recipient = ?
                         ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .params(TEMPLATE, email)
                .query(String.class)
                .single();
    }

    /** The audit row keeps the address masked (FR-AUD-05), so it is found by its masked form. */
    static long audited(String result, String email) {
        return TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM platform_audit_log
                         WHERE action = 'platform.find_sign_in.requested' AND data->>'result' = ? AND data->>'email' = ?
                        """)
                .params(result, Masking.lastFour(email))
                .query(Long.class)
                .single();
    }

    @Test
    void anActiveStaffUserIsEmailedTheirBusinessPageAndNobodyElse() {
        TestDatabase.Fixture shop = TestDatabase.tenant("findshop", false);
        String email = Api.email("cashier");
        Api.staff(shop, email, new Role("cashier", shop.headOffice()));

        ResponseEntity<JsonNode> answer = ask(email);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(sent(email)).isEqualTo(1);
        String links = links(email);
        assertThat(links).contains("//" + shop.slug() + "-bms-staging.rincoltech.test/sign-in");
        assertThat(audited("sent", email)).isEqualTo(1);
    }

    @Test
    void theAddressIsMatchedWithoutRegardToCase() {
        TestDatabase.Fixture shop = TestDatabase.tenant("findcase", false);
        String email = Api.email("mixed");
        Api.staff(shop, email, new Role("cashier", shop.headOffice()));

        ask(email.toUpperCase());

        assertThat(sent(email)).isEqualTo(1);
    }

    @Test
    void anUnknownAddressGetsTheSameAnswerAndNothingIsSent() {
        TestDatabase.Fixture shop = TestDatabase.tenant("findknown", false);
        String known = Api.email("known");
        Api.staff(shop, known, new Role("cashier", shop.headOffice()));
        String stranger = Api.email("stranger");

        ResponseEntity<JsonNode> yes = ask(known);
        ResponseEntity<JsonNode> no = ask(stranger);

        assertThat(no.getStatusCode()).isEqualTo(yes.getStatusCode());
        assertThat(no.getBody()).isEqualTo(yes.getBody());
        assertThat(sent(stranger)).isZero();
        assertThat(audited("no_match", stranger)).isEqualTo(1);
    }

    @Test
    void aDeactivatedOrInvitedUserAndASuspendedBusinessGetNothing() {
        TestDatabase.Fixture shop = TestDatabase.tenant("findgone", false);
        String left = Api.email("left");
        String invited = Api.email("invited");
        String suspended = Api.email("suspended");
        Api.staff(shop, left, new Role("cashier", shop.headOffice()));
        Api.staff(shop, invited, new Role("cashier", shop.headOffice()));
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE tenant_id = ? AND email = ?")
                .params(shop.tenantId(), left)
                .update();
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'invited' WHERE tenant_id = ? AND email = ?")
                .params(shop.tenantId(), invited)
                .update();
        TestDatabase.Fixture closed = TestDatabase.tenant("findclosed", false);
        Api.staff(closed, suspended, new Role("cashier", closed.headOffice()));
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .params(closed.tenantId())
                .update();

        for (String email : new String[] {left, invited, suspended}) {
            assertThat(ask(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(sent(email)).isZero();
        }
    }

    @Test
    void aMemberWithTheSameEmailIsNotStaff() {
        TestDatabase.Fixture shop = TestDatabase.tenant("findmember", false);
        String email = Api.email("member");
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status)"
                        + " VALUES (gen_random_uuid(), ?, 'member', 'Test Member 01', ?, 'active')")
                .params(shop.tenantId(), email)
                .update();

        ask(email);

        assertThat(sent(email)).isZero();
    }

    @Test
    void anAddressInTwoBusinessesGetsOneEmailWithBothPagesAndNoOtherBusinessIsNamed() {
        TestDatabase.Fixture one = TestDatabase.tenant("findone", false);
        TestDatabase.Fixture two = TestDatabase.tenant("findtwo", false);
        TestDatabase.Fixture other = TestDatabase.tenant("findother", false);
        String email = Api.email("both");
        Api.staff(one, email, new Role("cashier", one.headOffice()));
        Api.staff(two, email, new Role("cashier", two.headOffice()));
        Api.staff(other, Api.email("someone-else"), new Role("cashier", other.headOffice()));

        ask(email);

        assertThat(sent(email)).isEqualTo(1);
        String links = links(email);
        assertThat(links).contains(one.slug() + "-bms-staging").contains(two.slug() + "-bms-staging");
        assertThat(links).doesNotContain(other.slug());
    }

    @Test
    void aMailboxGetsAtMostThreeEmailsAnHourAndTheAnswerDoesNotChange() {
        TestDatabase.Fixture shop = TestDatabase.tenant("findmailbox", false);
        String email = Api.email("busy");
        Api.staff(shop, email, new Role("cashier", shop.headOffice()));

        ResponseEntity<JsonNode> first = ask(email);
        ask(email);
        ask(email);
        ResponseEntity<JsonNode> over = ask(email);

        assertThat(over.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(over.getBody()).isEqualTo(first.getBody());
        assertThat(sent(email)).isEqualTo(FindSignInService.MAX_EMAILS_PER_MAILBOX_PER_HOUR);
        assertThat(audited("rate_limited", email)).isEqualTo(1);
    }

    /** Emails sent to any spelling of one mailbox, grouped as the limit groups them ({@code onboarding_email_key}). */
    static long sentToMailbox(String email) {
        return TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM notification_outbox
                         WHERE template_key = ? AND onboarding_email_key(recipient) = onboarding_email_key(?)
                        """)
                .params(TEMPLATE, email)
                .query(Long.class)
                .single();
    }

    @Test
    void spellingsOfOneMailboxShareItsLimit() {
        // Both spellings are staff somewhere, so every request that reaches the lookup would send if the limit allowed
        // it.
        String local = "variant-" + UUID.randomUUID().toString().substring(0, 8);
        // A domain no other test uses, so the masked address of the audit row (last four characters) is this test's.
        String plain = local + "@example.net";
        String tagged = local + "+shop@example.net";
        TestDatabase.Fixture one = TestDatabase.tenant("findvariant1", false);
        TestDatabase.Fixture two = TestDatabase.tenant("findvariant2", false);
        Api.staff(one, plain, new Role("cashier", one.headOffice()));
        Api.staff(two, tagged, new Role("cashier", two.headOffice()));

        assertThat(ask(plain.toUpperCase(Locale.ROOT)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // The page trims what is typed; the route refuses surrounding spaces as invalid, so they send nothing.
        assertThat(ask("  " + plain + " ").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ask(tagged).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ask(plain).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ask("Variant" + tagged.substring("variant".length())).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        assertThat(sentToMailbox(plain)).isEqualTo(FindSignInService.MAX_EMAILS_PER_MAILBOX_PER_HOUR);
        assertThat(audited("rate_limited", tagged)).isEqualTo(1);
    }

    @Test
    void oneClientAddressIsLimitedAndTheNextRequestIsRefusedBeforeAnyLookup() {
        String from = freshAddress();
        int allowed = SignUpRateLimiter.FIND_PER_IP.capacity();
        for (int i = 0; i < allowed; i++) {
            assertThat(ask(Api.email("flood" + i), from).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        ResponseEntity<JsonNode> refused = ask(Api.email("flood-over"), from);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ask(Api.email("other-address")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void aBadlyShapedAddressIsRefusedAsInvalid() {
        assertThat(ask("not-an-email").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aTenantHostDoesNotServeTheRoute() {
        TestDatabase.Fixture shop = TestDatabase.tenant("findtenanthost", false);
        ResponseEntity<JsonNode> answer = platform.onHost(shop.slug() + "-bms-staging.rincoltech.test")
                .call(
                        HttpMethod.POST,
                        PATH,
                        Map.of("email", Api.email("x")),
                        null,
                        Map.of("X-Forwarded-For", freshAddress()));

        assertThat(answer.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
