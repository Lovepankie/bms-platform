package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.onboarding.internal.ApplicationsController.ActivateRequest;
import com.rincoltech.bms.core.onboarding.internal.ApplicationsController.ActivationResult;
import com.rincoltech.bms.core.onboarding.internal.ApplicationsController.ApplicationDetail;
import com.rincoltech.bms.core.onboarding.internal.ApplicationsController.ApplicationList;
import com.rincoltech.bms.core.onboarding.internal.ApplicationsController.ApplicationResponse;
import com.rincoltech.bms.core.onboarding.internal.ApplicationsController.Duplicate;
import com.rincoltech.bms.core.onboarding.internal.SignUpController.ApplicantView;
import com.rincoltech.bms.core.onboarding.internal.SignUpController.SignUpRequest;
import com.rincoltech.bms.core.platform.TenantProvisioning;
import com.rincoltech.bms.core.platform.TenantProvisioning.Created;
import com.rincoltech.bms.core.platform.TenantProvisioning.NewTenant;
import com.rincoltech.bms.core.tenancy.ModuleManifest;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.PhoneNumbers;
import com.rincoltech.bms.kernel.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applications from the public form to Activate (ADR-024, spec sections 4, 10 and 12). Every read
 * and write goes through the V23 definer functions; times come from the kernel clock. Applicant
 * links are 256 random bits, stored as SHA-256 only, valid 7 days, and reach the applicant only by
 * email. Nothing here logs an email, a phone number, a token or a message text.
 */
@Service
class ApplicationService {

    static final Duration LINK_TTL = Duration.ofDays(7);
    static final Duration UNVERIFIED_EXPIRY = Duration.ofDays(14);
    static final Duration CLOSED_RETENTION = Duration.ofDays(90);
    static final int LIST_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n]]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final char[] REFERENCE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final String DEFAULT_PLAN = "starter";
    static final String VERIFY_TEMPLATE = "onboarding.verify_email";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Outbox outbox;
    private final PlatformAuditLog audit;
    private final PlatformHost hosts;
    private final TenantProvisioning provisioning;
    private final BusinessClock clock;
    private final SignUpRateLimiter limiter;
    private final OnboardingProperties caps;
    private final Set<String> moduleKeys;

    ApplicationService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Outbox outbox,
            PlatformAuditLog audit,
            PlatformHost hosts,
            TenantProvisioning provisioning,
            BusinessClock clock,
            SignUpRateLimiter limiter,
            OnboardingProperties caps,
            List<ModuleManifest> manifests) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
        this.audit = audit;
        this.hosts = hosts;
        this.provisioning = provisioning;
        this.clock = clock;
        this.limiter = limiter;
        this.caps = caps;
        this.moduleKeys = manifests.stream().map(ModuleManifest::key).collect(Collectors.toUnmodifiableSet());
    }

    // ---- Public: the applicant ------------------------------------------------------------

    /**
     * FR-ONB-01, FR-ONB-02, FR-ONB-03. Creates the application and emails a link to confirm the
     * address; when the mailbox already has an open application, emails a fresh link to that one's
     * stored address instead. Either way, and also when a bound is reached or the honeypot is
     * filled, the caller gets the same answer, so the endpoint does not reveal which emails are
     * known. The bounds that must hold under a flood are counted in the database inside one
     * serialised transaction (review B2): the global caps per hour, and confirmation emails per
     * mailbox per 24 hours; a request over a bound creates, rotates and sends nothing.
     */
    void submit(SignUpRequest request) {
        limiter.acquire(SignUpRateLimiter.CREATE_PER_IP, RequestContext.clientIp());
        if (request.website() != null && !request.website().isBlank()) {
            log.info("sign-up dropped: hidden field filled");
            return;
        }
        String email = request.contactEmail().trim().toLowerCase(Locale.ROOT);
        String phone = phone(request.country(), request.contactPhone());
        List<String> modules = checkModules(request.modules());
        String businessName = line(request.businessName());
        String contactName = line(request.contactName());
        if (businessName.length() < 2 || contactName.length() < 2) {
            throw ApiException.validation(
                    List.of(new FieldProblem("business_name", "required", "Give the business and contact names.")));
        }
        String token = token();
        String hash = sha256(token);
        Instant now = clock.now();
        transactions.executeWithoutResult(status -> {
            // One sign-up at a time, so the counts below are exact under concurrency.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('core.onboarding.sign-up'))")
                    .query()
                    .listOfRows();
            if (capReached(now)) {
                return;
            }
            String mailbox = jdbc.sql("SELECT onboarding_email_key(?)")
                    .param(email)
                    .query(String.class)
                    .single();
            String throttle = "onboarding.verify:" + mailbox;
            if (outbox.countSince(throttle, null, now.minus(Duration.ofHours(24)), now)
                    >= caps.maxVerificationEmailsPerMailbox()) {
                log.info("sign-up answered without an email: mailbox bound reached");
                return;
            }
            UUID id = UUID.randomUUID();
            String reference = reference();
            boolean created = Boolean.TRUE.equals(
                    jdbc.sql("SELECT onboarding_application_create(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                            .params(
                                    id,
                                    reference,
                                    businessName,
                                    contactName,
                                    email,
                                    phone,
                                    request.country(),
                                    modules.toArray(String[]::new),
                                    request.term(),
                                    request.wayIn(),
                                    blankToNull(request.agentCode()),
                                    blankToNull(text(request.message())),
                                    hash,
                                    Timestamp.from(now.plus(LINK_TTL)),
                                    Timestamp.from(now))
                            .query(Boolean.class)
                            .single());
            // A repeat goes to the address just submitted, which the key treats as the same mailbox,
            // never to a variant stored earlier: a variant that bounces cannot squat the mailbox
            // (round 2 review item 2).
            String recipient = email;
            if (created) {
                audit.record("platform.application.submitted", null, null, Map.of("application_id", id));
            } else {
                Optional<String> open = jdbc.sql("SELECT reference FROM onboarding_application_relink(?, ?, ?, ?)")
                        .params(email, hash, Timestamp.from(now.plus(LINK_TTL)), Timestamp.from(now))
                        .query(String.class)
                        .optional();
                if (open.isEmpty()) {
                    return;
                }
                reference = open.get();
            }
            // No text the applicant typed: the address is not confirmed yet (review B1).
            Map<String, String> params = new LinkedHashMap<>();
            params.put("reference", reference);
            params.put("link", hosts.platformOrigin() + "/sign-up/verify#token=" + token);
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    recipient,
                    VERIFY_TEMPLATE,
                    params,
                    "onboarding.link:" + hash,
                    throttle,
                    now.plus(LINK_TTL)));
        });
    }

    /**
     * The global caps of {@link OnboardingProperties}. When one is reached the first request of the
     * hour writes an audit row and a Telegram alert to the operator (one per hour, by idempotency
     * key); every request of that hour is answered but creates and sends nothing.
     */
    private boolean capReached(Instant now) {
        Instant hourAgo = now.minus(Duration.ofHours(1));
        int applications = jdbc.sql("SELECT onboarding_applications_created_since(?, ?)")
                .params(Timestamp.from(hourAgo), Timestamp.from(now))
                .query(Integer.class)
                .single();
        int emails = outbox.countSince(null, VERIFY_TEMPLATE, hourAgo, now);
        if (applications < caps.maxApplicationsPerHour() && emails < caps.maxVerificationEmailsPerHour()) {
            return false;
        }
        String hour =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00", Locale.ROOT).format(now.atOffset(ZoneOffset.UTC));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("hour", hour);
        params.put("applications", Integer.toString(applications));
        params.put("emails", Integer.toString(emails));
        boolean first = outbox.enqueue(new Outbox.Message(
                Outbox.TELEGRAM,
                Outbox.OPERATOR_CHAT,
                "onboarding.cap_reached",
                params,
                "onboarding.cap_reached:" + hour));
        if (first) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hour", hour);
            data.put("applications_last_hour", applications);
            data.put("verification_emails_last_hour", emails);
            data.put("max_applications_per_hour", caps.maxApplicationsPerHour());
            data.put("max_verification_emails_per_hour", caps.maxVerificationEmailsPerHour());
            audit.record("platform.sign_up.cap_reached", null, null, data);
            log.warn("sign-up cap reached for the hour {}: new sign-ups send nothing until it passes", hour);
        }
        return true;
    }

    /** FR-ONB-02, FR-ONB-04: the applicant page; with {@code verify} the email is confirmed. */
    ApplicantView view(String token, boolean verify) {
        String hash = checkLink(token);
        return transactions.execute(status -> {
            Link link = byLink(hash, verify).orElseThrow(ApplicationService::linkInvalid);
            if (link.newlyVerified()) {
                audit.record("platform.application.email_verified", null, null, Map.of("application_id", link.id()));
                alertOperators(link);
            }
            return link.view();
        });
    }

    /** FR-ONB-04: the answer to a needs_info note puts the application back in the queue. */
    ApplicantView reply(String token, String reply) {
        String hash = checkLink(token);
        String answer = text(reply);
        if (answer == null || answer.isBlank()) {
            throw ApiException.validation(List.of(new FieldProblem("reply", "required", "Write your answer.")));
        }
        return transactions.execute(status -> {
            Optional<UUID> id = jdbc.sql("SELECT onboarding_application_reply(?, ?, ?)")
                    .params(hash, answer, Timestamp.from(clock.now()))
                    .query(UUID.class)
                    .optional()
                    .filter(v -> v != null);
            if (id.isEmpty()) {
                byLink(hash, false).orElseThrow(ApplicationService::linkInvalid);
                throw state("Only an application waiting for more information can be answered.");
            }
            audit.record("platform.application.replied", null, null, Map.of("application_id", id.get()));
            return byLink(hash, false)
                    .orElseThrow(ApplicationService::linkInvalid)
                    .view();
        });
    }

    // ---- Operator portal ------------------------------------------------------------------

    /** FR-ONB-05: the queue, newest first, with the count per status. */
    ApplicationList list(List<String> statuses) {
        return transactions.execute(status -> {
            List<ApplicationResponse> items = jdbc.sql("SELECT * FROM onboarding_applications_list(?, NULL, ?)")
                    .params(statuses == null || statuses.isEmpty() ? null : statuses.toArray(String[]::new), LIST_LIMIT)
                    .query(ApplicationService::application)
                    .list();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String s : List.of("submitted", "needs_info", "verified", "activated", "rejected", "expired")) {
                counts.put(s, 0L);
            }
            jdbc.sql("SELECT status, total FROM onboarding_application_counts()")
                    .query((rs, n) -> counts.put(rs.getString("status"), rs.getLong("total")))
                    .list();
            return new ApplicationList(items, counts);
        });
    }

    /** FR-ONB-05: one application, the possible repeats (spec section 4.2 item 4) and a slug. */
    ApplicationDetail detail(UUID id) {
        return transactions.execute(status -> {
            ApplicationResponse application = find(id).orElseThrow(ApiException::notFound);
            List<Duplicate> duplicates = jdbc.sql("SELECT * FROM onboarding_application_duplicates(?)")
                    .param(id)
                    .query((rs, n) -> new Duplicate(
                            rs.getString("kind"),
                            rs.getObject("id", UUID.class),
                            rs.getString("reference"),
                            rs.getString("name"),
                            rs.getString("status"),
                            strings(rs.getArray("matched_on"))))
                    .list();
            String slug = "activated".equals(application.status()) ? null : suggestSlug(application.businessName());
            return new ApplicationDetail(application, duplicates, slug);
        });
    }

    /** FR-ONB-06: Verify, Needs info (with a note the applicant sees) or Reject (with a reason). */
    ApplicationDetail decide(UUID id, String to, String note) {
        UUID operator = CurrentPrincipal.require().userId();
        List<String> from = switch (to) {
            case "verified" -> List.of("submitted", "needs_info");
            case "needs_info" -> List.of("submitted", "verified");
            case "rejected" -> List.of("submitted", "needs_info", "verified");
            default -> throw new IllegalArgumentException(to);
        };
        String cleanNote = to.equals("verified") ? null : text(note);
        if (!to.equals("verified") && (cleanNote == null || cleanNote.isBlank())) {
            throw ApiException.validation(List.of(new FieldProblem("note", "required", "Write the note.")));
        }
        boolean tellApplicant = !to.equals("verified");
        String token = tellApplicant ? token() : null;
        String hash = token == null ? null : sha256(token);
        Instant now = clock.now();
        transactions.executeWithoutResult(status -> {
            ApplicationResponse before = find(id).orElseThrow(ApiException::notFound);
            String previous = jdbc.sql("SELECT onboarding_application_decide(?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            id,
                            from.toArray(String[]::new),
                            to,
                            operator,
                            cleanNote,
                            hash,
                            hash == null ? null : Timestamp.from(now.plus(LINK_TTL)),
                            Timestamp.from(now))
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (previous == null) {
                throw state("The application is " + before.status().replace('_', ' ') + " and cannot be moved to "
                        + to.replace('_', ' ') + ".");
            }
            if (tellApplicant) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("contact_name", before.contactName());
                params.put("business_name", before.businessName());
                params.put("note", cleanNote);
                params.put("link", hosts.platformOrigin() + "/sign-up/verify#token=" + token);
                outbox.enqueue(new Outbox.Message(
                        Outbox.EMAIL,
                        before.contactEmail(),
                        to.equals("rejected") ? "onboarding.rejected" : "onboarding.needs_info",
                        params,
                        "onboarding.link:" + hash,
                        null,
                        now.plus(LINK_TTL)));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("application_id", id);
            data.put("reference", before.reference());
            data.put("before", previous);
            data.put("after", to);
            audit.record("platform.application." + to, operator, null, data);
        });
        return detail(id);
    }

    /**
     * FR-ONB-07 (spec sections 4.1 and 4.2, build step 1): creates the tenant through the platform
     * tenant function with the applicant as its invited first admin, marks the application
     * activated, writes {@code platform_audit_log} and queues the activation email, all in one
     * transaction. A second call finds the application activated and does nothing.
     */
    ActivationResult activate(UUID id, ActivateRequest request) {
        UUID operator = CurrentPrincipal.require().userId();
        List<String> modules = checkModules(request.modules());
        String note = text(request.paymentNote());
        if ("paid".equals(request.wayIn()) && (note == null || note.isBlank())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "payment_note", "required", "Note the payment received (method, reference and date).")));
        }
        ApplicationResponse application = transactions.execute(status -> find(id).orElseThrow(ApiException::notFound));
        if ("activated".equals(application.status())) {
            return repeat(id);
        }
        if (!"verified".equals(application.status())) {
            throw notVerified();
        }
        String slug = request.slug().trim().toLowerCase(Locale.ROOT);
        AtomicReference<Instant> activatedAt = new AtomicReference<>();
        Optional<Created> created = provisioning.create(
                new NewTenant(
                        application.businessName(),
                        slug,
                        request.planCode() == null ? DEFAULT_PLAN : request.planCode(),
                        null,
                        null,
                        modules,
                        "HQ",
                        "Head Office",
                        application.contactName(),
                        application.contactEmail(),
                        application.contactPhone(),
                        "paid".equals(request.wayIn()) ? "active" : "trial"),
                new TenantProvisioning.Steps() {
                    @Override
                    public boolean before() {
                        Lock lock = jdbc.sql("SELECT * FROM onboarding_application_lock(?)")
                                .param(id)
                                .query((rs, n) -> new Lock(rs.getString("status")))
                                .optional()
                                .orElseThrow(ApiException::notFound);
                        if ("activated".equals(lock.status())) {
                            return false;
                        }
                        if (!"verified".equals(lock.status())) {
                            throw notVerified();
                        }
                        return true;
                    }

                    @Override
                    public void after(Created tenant) {
                        Instant now = clock.now();
                        activatedAt.set(now);
                        jdbc.sql("SELECT onboarding_application_activated(?, ?, ?, ?, ?, ?, ?)")
                                .params(
                                        id,
                                        tenant.tenantId(),
                                        operator,
                                        request.wayIn(),
                                        request.term(),
                                        note,
                                        Timestamp.from(now))
                                .query()
                                .singleRow();
                        Map<String, String> params = new LinkedHashMap<>();
                        params.put("contact_name", application.contactName());
                        params.put("business_name", application.businessName());
                        params.put("link", tenant.invitationUrl());
                        params.put(
                                "expires_at",
                                DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH)
                                        .format(tenant.invitationExpiresAt().atOffset(ZoneOffset.UTC)));
                        params.put("sign_in_url", hosts.tenantOrigin(tenant.slug()) + "/sign-in");
                        outbox.enqueue(new Outbox.Message(
                                Outbox.EMAIL,
                                application.contactEmail(),
                                "onboarding.activation",
                                params,
                                "onboarding.activation:" + id,
                                null,
                                tenant.invitationExpiresAt()));
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("application_id", id);
                        data.put("reference", application.reference());
                        data.put("slug", tenant.slug());
                        data.put("modules", modules);
                        data.put("way_in", request.wayIn());
                        data.put("term", request.term());
                        data.put("payment_note_recorded", note != null && !note.isBlank());
                        data.put("contact_email", application.contactEmail());
                        audit.record("platform.application.activated", operator, tenant.tenantId(), data);
                    }
                });
        if (created.isEmpty()) {
            return repeat(id);
        }
        Created tenant = created.get();
        ApplicationResponse after = transactions.execute(status -> find(id).orElseThrow());
        return new ActivationResult(
                after, true, tenant.tenantId(), tenant.slug(), tenant.invitationUrl(), tenant.invitationExpiresAt());
    }

    // ---- Housekeeping (spec sections 4 and 12) ---------------------------------------------

    /** Expires applications unverified after 14 days and deletes closed ones after 90 days. */
    void housekeeping() {
        Instant now = clock.now();
        transactions.executeWithoutResult(status -> {
            int expired = jdbc.sql("SELECT onboarding_applications_expire(?, ?)")
                    .params(Timestamp.from(now.minus(UNVERIFIED_EXPIRY)), Timestamp.from(now))
                    .query(Integer.class)
                    .single();
            int deleted = jdbc.sql("SELECT onboarding_applications_purge(?)")
                    .param(Timestamp.from(now.minus(CLOSED_RETENTION)))
                    .query(Integer.class)
                    .single();
            log.info("onboarding housekeeping: expired={} deleted={}", expired, deleted);
        });
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private ActivationResult repeat(UUID id) {
        ApplicationResponse current = transactions.execute(status -> find(id).orElseThrow(ApiException::notFound));
        return new ActivationResult(
                current, false, current.activatedTenantId(), current.activatedTenantSlug(), null, null);
    }

    private void alertOperators(Link link) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("reference", link.view().reference());
        params.put("business_name", link.view().businessName());
        params.put("modules", String.join(", ", link.view().modules()));
        params.put("portal_url", hosts.platformOrigin() + "/platform/applications/" + link.id());
        record Operator(UUID id, String email) {}
        List<Operator> operators = jdbc.sql("SELECT id, email FROM platform_users WHERE is_active ORDER BY id")
                .query((rs, n) -> new Operator(rs.getObject("id", UUID.class), rs.getString("email")))
                .list();
        for (Operator operator : operators) {
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    operator.email(),
                    "onboarding.operator_alert",
                    params,
                    "onboarding.operator_alert:" + link.id() + ":email:" + operator.id()));
        }
        outbox.enqueue(new Outbox.Message(
                Outbox.TELEGRAM,
                Outbox.OPERATOR_CHAT,
                "onboarding.operator_alert",
                params,
                "onboarding.operator_alert:" + link.id() + ":telegram"));
    }

    private Optional<ApplicationResponse> find(UUID id) {
        return jdbc.sql("SELECT * FROM onboarding_applications_list(NULL, ?, 1)")
                .param(id)
                .query(ApplicationService::application)
                .optional();
    }

    private Optional<Link> byLink(String hash, boolean verify) {
        return jdbc.sql("SELECT * FROM onboarding_application_by_link(?, ?, ?)")
                .params(hash, Timestamp.from(clock.now()), verify)
                .query((rs, n) -> new Link(
                        rs.getObject("id", UUID.class),
                        rs.getString("link_token_hash"),
                        rs.getBoolean("newly_verified"),
                        new ApplicantView(
                                rs.getString("reference"),
                                rs.getString("status"),
                                rs.getString("business_name"),
                                strings(rs.getArray("modules")),
                                rs.getString("term"),
                                rs.getString("way_in"),
                                rs.getString("operator_note"),
                                rs.getString("reject_reason"),
                                rs.getBoolean("email_verified"),
                                rs.getTimestamp("created_at").toInstant())))
                .optional()
                // The row was found by its hash; the digests are compared again in constant time
                // so no code path depends on a byte-by-byte comparison of secret material.
                .filter(link -> MessageDigest.isEqual(
                        link.hash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Rate limit, then shape: a malformed token gets the same answer as an unknown one. */
    private String checkLink(String token) {
        limiter.acquire(SignUpRateLimiter.LINK_PER_IP, RequestContext.clientIp());
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw linkInvalid();
        }
        return sha256(token);
    }

    /**
     * A free slug from the business name (FR-TEN-02): lower case letters, digits and hyphens, at
     * most 40 characters, with a number appended when taken; null when none of the first ten is free.
     */
    String suggestSlug(String businessName) {
        String base = Normalizer.normalize(businessName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.length() > 40) {
            base = base.substring(0, 40).replaceAll("-+$", "");
        }
        if (base.length() < 3) {
            base = (base + "-shop").replaceAll("^-+", "");
        }
        for (int i = 1; i <= 10; i++) {
            String candidate = i == 1 ? base : base + "-" + i;
            if (provisioning.slugAvailable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> checkModules(List<String> modules) {
        List<String> unique = modules.stream().distinct().sorted().toList();
        for (String module : unique) {
            if (!moduleKeys.contains(module)) {
                throw ApiException.validation(
                        List.of(new FieldProblem("modules", "invalid", "Unknown module " + module + ".")));
            }
        }
        return unique;
    }

    /** Uganda numbers in any local form; other countries in international form. */
    static String phone(String country, String raw) {
        Optional<String> phone = "UG".equals(country)
                ? PhoneNumbers.normaliseUganda(raw)
                : Optional.ofNullable(raw)
                        .map(r -> r.replaceAll("[\\s()-]", ""))
                        .filter(r -> E164.matcher(r).matches());
        return phone.orElseThrow(() -> ApiException.validation(List.of(new FieldProblem(
                "contact_phone",
                "invalid_phone",
                "UG".equals(country)
                        ? "Enter a Uganda mobile number, for example 0700 000 000."
                        : "Enter the number in international form, for example +254 700 000 000."))));
    }

    /** One line: NFC, no control characters, single spaces, trimmed. */
    static String line(String value) {
        if (value == null) {
            return "";
        }
        String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
        return SPACES.matcher(CONTROL.matcher(nfc).replaceAll(""))
                .replaceAll(" ")
                .trim();
    }

    /** Free text: NFC, no control characters except line breaks, trimmed. */
    static String text(String value) {
        if (value == null) {
            return null;
        }
        String nfc = Normalizer.normalize(value.replace("\r\n", "\n"), Normalizer.Form.NFC);
        return CONTROL.matcher(nfc).replaceAll("").trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 256 random bits, base64url (43 characters). */
    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code A-} and 8 characters without 0, 1, I or O, read out on the phone. */
    private static String reference() {
        StringBuilder reference = new StringBuilder("A-");
        for (int i = 0; i < 8; i++) {
            reference.append(REFERENCE_ALPHABET[RANDOM.nextInt(REFERENCE_ALPHABET.length)]);
        }
        return reference.toString();
    }

    private static ApiException linkInvalid() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "link_invalid",
                "Link not valid",
                "This link is not valid or has expired. Apply again with the same email to get a new one.");
    }

    private static ApiException notVerified() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "application_not_verified",
                "Not verified",
                "Verify the application before activating it.");
    }

    private static ApiException state(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "application_state", "Not allowed now", detail);
    }

    private static ApplicationResponse application(ResultSet rs, int n) throws SQLException {
        return new ApplicationResponse(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getString("status"),
                rs.getString("business_name"),
                rs.getString("contact_name"),
                rs.getString("contact_email"),
                rs.getString("contact_phone_e164"),
                rs.getString("country"),
                strings(rs.getArray("modules")),
                rs.getString("term"),
                rs.getString("way_in"),
                rs.getString("agent_code"),
                rs.getString("message"),
                instant(rs, "email_verified_at"),
                rs.getString("operator_note"),
                rs.getString("applicant_reply"),
                rs.getString("reject_reason"),
                rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"),
                rs.getObject("activated_by", UUID.class),
                instant(rs, "activated_at"),
                rs.getObject("activated_tenant", UUID.class),
                rs.getString("activated_tenant_slug"),
                rs.getString("activation_way"),
                rs.getString("activation_term"),
                rs.getString("activation_note"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }

    private record Link(UUID id, String hash, boolean newlyVerified, ApplicantView view) {}

    private record Lock(String status) {}
}
