package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Staff of a business? Find your sign-in page" on the platform home page (issue #383). The asker types a work email; when it
 * is an active staff user's in one or more active businesses, an email goes to that address, and only that address, with the
 * sign-in page of each business. The caller always gets the same answer: nothing the page returns, and no timing, says whether
 * the address matched. The lookup crosses tenants through the definer function {@code app_staff_sign_in_pages} (V72, ADR-003,
 * ADR-016) and returns only each business's public slug and name. The address is limited per client address (the controller)
 * and per mailbox (here, counted in the database from the outbox rows, so it holds across instances); a request over a bound
 * sends nothing and is audited like any other.
 */
@Service
class FindSignInService {

    private static final Logger log = LoggerFactory.getLogger(FindSignInService.class);

    static final String TEMPLATE = "identity.find_sign_in";
    /** Emails one mailbox can be sent in an hour; further requests are answered and ignored. */
    static final int MAX_EMAILS_PER_MAILBOX_PER_HOUR = 3;

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration LINK_LIFETIME = Duration.ofDays(1);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Outbox outbox;
    private final PlatformAuditLog audit;
    private final PlatformHost hosts;
    private final BusinessClock clock;

    FindSignInService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Outbox outbox,
            PlatformAuditLog audit,
            PlatformHost hosts,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
        this.audit = audit;
        this.hosts = hosts;
        this.clock = clock;
    }

    private record Page(String slug, String name) {}

    /** Returns nothing, on purpose: the caller must not be able to tell what happened. */
    void request(String typed) {
        String email = typed.trim().toLowerCase(Locale.ROOT);
        transactions.executeWithoutResult(status -> {
            String mailbox = jdbc.sql("SELECT onboarding_email_key(?)")
                    .param(email)
                    .query(String.class)
                    .single();
            String throttle = "find-sign-in:" + mailbox;
            // One request per mailbox at a time, so the count below is exact under concurrency.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))")
                    .param(throttle)
                    .query()
                    .listOfRows();
            Instant now = clock.now();
            if (outbox.countSince(throttle, null, now.minus(WINDOW), now) >= MAX_EMAILS_PER_MAILBOX_PER_HOUR) {
                record("rate_limited", email, 0);
                return;
            }
            List<Page> pages = jdbc.sql("SELECT slug, name FROM app_staff_sign_in_pages(?)")
                    .param(email)
                    .query((rs, n) -> new Page(rs.getString("slug"), rs.getString("name")))
                    .list();
            if (pages.isEmpty()) {
                record("no_match", email, 0);
                return;
            }
            StringBuilder links = new StringBuilder();
            for (Page page : pages) {
                if (!links.isEmpty()) {
                    links.append("\n\n");
                }
                links.append(line(page.name()))
                        .append("\n")
                        .append(hosts.tenantOrigin(page.slug()))
                        .append("/sign-in");
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("links", links.toString());
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    email,
                    TEMPLATE,
                    params,
                    TEMPLATE + ":" + UUID.randomUUID(),
                    throttle,
                    now.plus(LINK_LIFETIME)));
            record("sent", email, pages.size());
        });
    }

    /**
     * Audits a request that failed inside {@link #request} (its own transaction rolled back), in a new transaction. A failure
     * here too is only logged: the caller answers the same either way.
     */
    void recordFailure(String typed) {
        String email = typed.trim().toLowerCase(Locale.ROOT);
        try {
            transactions.executeWithoutResult(status -> record("failed", email, 0));
        } catch (RuntimeException e) {
            log.error("Could not audit a failed find-sign-in request", e);
        }
    }

    private void record(String result, String email, int businesses) {
        audit.record(
                "platform.find_sign_in.requested",
                null,
                null,
                Map.of("result", result, "email", email, "businesses", businesses));
    }

    /** A business name on one line: a name is typed by the business, so a line break must not shape the email. */
    private static String line(String name) {
        return name.replaceAll("[\\r\\n\\u2028\\u2029]+", " ").trim();
    }
}
