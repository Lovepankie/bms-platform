package com.rincoltech.bms.core.feedback.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.ImagePolicy;
import com.rincoltech.bms.core.documents.Documents.Prepared;
import com.rincoltech.bms.core.feedback.FeedbackInbox;
import com.rincoltech.bms.core.feedback.internal.FeedbackApi.FeedbackItem;
import com.rincoltech.bms.core.feedback.internal.FeedbackApi.FeedbackList;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * In-app feedback (issue #447, ADR-065): the sender's send and own list inside their tenant, and the operator's inbox
 * across businesses through the definer functions of V80. Nothing here takes a tenant: the sender's is the one the request
 * host resolved, and the operator's screenshot read is bound by {@code core.platform}.
 */
@Service
class FeedbackService implements FeedbackInbox {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    static final Set<String> KINDS = Set.of("idea", "problem", "question");
    static final Set<String> STATUSES = Set.of("new", "seen", "done");
    static final int MESSAGE_MAX = 2000;
    static final int SCREENSHOT_MAX_BYTES = 2 * 1024 * 1024;

    /** PNG or JPEG, 2 MB in, re-encoded without metadata; a tall phone screenshot is scaled to 2400 px on its long edge. */
    static final ImagePolicy SCREENSHOT = new ImagePolicy(SCREENSHOT_MAX_BYTES, 1, 2400, 16_000_000L);

    /** At most this many items per person in any hour. */
    static final int PER_HOUR = 10;

    static final Duration WINDOW = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final Documents documents;
    private final Outbox outbox;
    private final AuditLog audit;
    private final PlatformAuditLog platformAudit;
    private final BusinessClock clock;
    private final CurrentTenant tenant;
    private final PlatformHost hosts;
    private final TransactionTemplate transactions;
    private final TransactionTemplate savepoint;
    private final String appVersion;

    FeedbackService(
            JdbcClient jdbc,
            Documents documents,
            Outbox outbox,
            AuditLog audit,
            PlatformAuditLog platformAudit,
            BusinessClock clock,
            CurrentTenant tenant,
            PlatformHost hosts,
            PlatformTransactionManager transactionManager,
            ObjectProvider<BuildProperties> build,
            ObjectProvider<GitProperties> git,
            @Value("${bms.git-sha:}") String imageGitSha) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.outbox = outbox;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.clock = clock;
        this.tenant = tenant;
        this.hosts = hosts;
        this.transactions = new TransactionTemplate(transactionManager);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.appVersion = appVersion(build.getIfAvailable(), git.getIfAvailable(), imageGitSha);
    }

    /** What {@code GET /version} reports, in one string: the version and the git SHA, as the image or the build carries it. */
    static String appVersion(BuildProperties build, GitProperties git, String imageGitSha) {
        String version = build == null ? "unknown" : build.getVersion();
        String sha = !imageGitSha.isBlank() ? imageGitSha : git != null ? git.getShortCommitId() : "unknown";
        String both = version + " (" + (sha.length() > 12 ? sha.substring(0, 12) : sha) + ")";
        return both.length() > 100 ? both.substring(0, 100) : both;
    }

    // ---- The sender ---------------------------------------------------------------------------

    record Send(String kind, String message, String pagePath, String userAgent, byte[] screenshot) {}

    /**
     * The screenshot is checked and re-encoded before the transaction opens, so no connection waits on it. A sender over the
     * hourly limit is refused first, by a plain count, so a burst of sends never costs an image decode each; the locked
     * count in the transaction is the one that holds.
     */
    FeedbackItem send(Send request) {
        validate(request);
        UUID sender = CurrentPrincipal.require().userId();
        transactions.executeWithoutResult(tx -> limit(sender, clock.now(), false));
        Prepared screenshot = request.screenshot() == null || request.screenshot().length == 0
                ? null
                : documents.prepareImage(screenshotBytes(request.screenshot()), SCREENSHOT);
        return transactions.execute(tx -> store(request, screenshot));
    }

    private FeedbackItem store(Send request, Prepared screenshot) {
        UUID sender = CurrentPrincipal.require().userId();
        Instant now = clock.now();
        limit(sender, now, true);
        UUID id = UUID.randomUUID();
        UUID documentId = screenshot == null
                ? null
                : documents
                        .store(screenshot, FeedbackDocumentAccess.SUBJECT, id, null)
                        .id();
        jdbc.sql("""
                        INSERT INTO feedback_items (id, tenant_id, sender_user_id, kind, message, page_path, app_version,
                                                    user_agent, screenshot_document_id, created_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        sender,
                        request.kind(),
                        request.message().strip(),
                        pagePath(request.pagePath()),
                        appVersion,
                        cut(request.userAgent(), 400),
                        documentId,
                        Timestamp.from(now))
                .update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("kind", request.kind());
        after.put("has_screenshot", documentId != null);
        audit.record(AuditLog.Entry.created("core.feedback.sent", "core.feedback", id, null, after));
        tellOperators(id, request.kind(), request.message().strip(), pagePath(request.pagePath()), sender);
        return ownItem(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    FeedbackList mine() {
        return new FeedbackList(
                jdbc.sql(SELECT_OWN + " WHERE sender_user_id = ? ORDER BY created_at DESC, id LIMIT 100")
                        .param(CurrentPrincipal.require().userId())
                        .query(FeedbackService::item)
                        .list());
    }

    /** The bytes of the caller's own screenshot; 404 for anyone else's (the document access says so) or none. */
    Documents.Image ownScreenshot(UUID id) {
        UUID documentId = transactions.execute(tx -> ownItem(id).isPresent() ? screenshotId(id) : null);
        if (documentId == null) {
            throw ApiException.notFound();
        }
        return documents.readImage(FeedbackDocumentAccess.SUBJECT, documentId);
    }

    private Optional<FeedbackItem> ownItem(UUID id) {
        return jdbc.sql(SELECT_OWN + " WHERE id = ? AND sender_user_id = ?")
                .params(id, CurrentPrincipal.require().userId())
                .query(FeedbackService::item)
                .optional();
    }

    private UUID screenshotId(UUID id) {
        return jdbc.sql("SELECT screenshot_document_id FROM feedback_items WHERE id = ?")
                .param(id)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private static final String SELECT_OWN = """
            SELECT id, kind, message, page_path, app_version, screenshot_document_id IS NOT NULL AS has_screenshot,
                   status, reply_note, status_changed_at, created_at
              FROM feedback_items""";

    private static FeedbackItem item(ResultSet rs, int n) throws SQLException {
        return new FeedbackItem(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("message"),
                rs.getString("page_path"),
                rs.getString("app_version"),
                rs.getBoolean("has_screenshot"),
                rs.getString("status"),
                rs.getString("reply_note"),
                instant(rs.getTimestamp("status_changed_at")),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Ten items per person in any hour, counted in the database under a per-person lock, so two sends at once cannot both
     * take the tenth place. The 429 names the wait until the oldest of the ten leaves the hour. Without the lock it is the
     * cheap early check before the screenshot is decoded.
     */
    private void limit(UUID sender, Instant now, boolean locked) {
        if (locked) {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('core.feedback:' || ?, 0))")
                    .param(sender.toString())
                    .query((rs, n) -> 1)
                    .single();
        }
        List<Instant> recent = jdbc.sql("""
                        SELECT created_at FROM feedback_items
                         WHERE sender_user_id = ? AND created_at > ?
                         ORDER BY created_at DESC LIMIT ?
                        """)
                .params(sender, Timestamp.from(now.minus(WINDOW)), PER_HOUR)
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .list();
        if (recent.size() >= PER_HOUR) {
            Duration wait = Duration.between(now, recent.getLast().plus(WINDOW));
            throw new ApiException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "feedback_rate_limited",
                            "Too much feedback at once",
                            "You have sent " + PER_HOUR + " messages in the last hour. Please wait a little and send"
                                    + " this one later.")
                    .withRetryAfter(wait.isNegative() ? Duration.ofSeconds(1) : wait);
        }
    }

    /**
     * One email per active platform operator (the addresses the onboarding alert uses), in a savepoint: a failure here is
     * logged without the message and the feedback is kept. The outbox sends later, so a mail server that is down never
     * delays or refuses the send either.
     */
    private void tellOperators(UUID id, String kind, String message, String pagePath, UUID sender) {
        try {
            savepoint.executeWithoutResult(tx -> {
                record Operator(UUID id, String email) {}
                List<Operator> operators = jdbc.sql("SELECT id, email FROM platform_users WHERE is_active ORDER BY id")
                        .query((rs, n) -> new Operator(rs.getObject("id", UUID.class), rs.getString("email")))
                        .list();
                if (operators.isEmpty()) {
                    return;
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("business_name", tenant.profile().name());
                params.put(
                        "sender_name",
                        jdbc.sql("SELECT full_name FROM users WHERE id = ?")
                                .param(sender)
                                .query(String.class)
                                .single());
                params.put("kind", kind);
                params.put("page", pagePath == null ? "not known" : pagePath);
                params.put("message", message);
                params.put("portal_url", hosts.platformOrigin() + "/platform/feedback");
                for (Operator operator : operators) {
                    outbox.enqueue(new Outbox.Message(
                            Outbox.EMAIL,
                            operator.email(),
                            "feedback.new",
                            params,
                            "feedback.new:" + id + ":email:" + operator.id()));
                }
            });
        } catch (RuntimeException e) {
            log.warn(
                    "the operator email for new feedback was not queued: {}",
                    e.getClass().getSimpleName());
        }
    }

    private static void validate(Send request) {
        List<FieldProblem> problems = new java.util.ArrayList<>();
        if (request.kind() == null || !KINDS.contains(request.kind())) {
            problems.add(new FieldProblem("kind", "invalid", "Choose an idea, a problem or a question."));
        }
        if (request.message() == null || request.message().isBlank()) {
            problems.add(new FieldProblem("message", "required", "Write your message."));
        } else if (request.message().strip().length() > MESSAGE_MAX) {
            problems.add(new FieldProblem(
                    "message", "too_long", "The message can be at most " + MESSAGE_MAX + " characters."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
    }

    /** PNG or JPEG by content, at most 2 MB, in plain words before the documents module sees it. */
    static byte[] screenshotBytes(byte[] raw) {
        if (raw.length > SCREENSHOT_MAX_BYTES) {
            throw ApiException.rule(
                    "screenshot_too_large", "The screenshot is larger than 2 MB. Send a smaller picture.");
        }
        boolean jpeg = raw.length > 3 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8 && (raw[2] & 0xFF) == 0xFF;
        boolean png = raw.length > 8 && (raw[0] & 0xFF) == 0x89 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G';
        if (!jpeg && !png) {
            throw ApiException.rule("screenshot_not_an_image", "The screenshot must be a PNG or JPEG picture.");
        }
        return raw;
    }

    /** The path only: no scheme or host, no query (it can carry a search or a token), no fragment, 300 characters at most. */
    static String pagePath(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.strip();
        int cut = path.length();
        for (char c : new char[] {'?', '#'}) {
            int at = path.indexOf(c);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        path = path.substring(0, cut);
        if (!path.startsWith("/") || path.startsWith("//")) {
            return null;
        }
        return cut(path, 300);
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    // ---- The operator (FeedbackInbox) -----------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<Item> list(String status, Instant since, int limit, int offset) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is new, seen or done.")));
        }
        return jdbc.sql("SELECT * FROM platform_feedback_list(?, NULL, ?, ?, ?)")
                .params(status, since == null ? null : Timestamp.from(since), limit, offset)
                .query(FeedbackService::operatorItem)
                .list();
    }

    @Override
    @Transactional
    public Item setStatus(UUID id, String status, String replyNote, UUID operatorId) {
        if (status == null || !STATUSES.contains(status)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is new, seen or done.")));
        }
        if (replyNote != null && replyNote.strip().length() > MESSAGE_MAX) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "reply_note", "too_long", "The reply can be at most " + MESSAGE_MAX + " characters.")));
        }
        Item before = byId(id).orElseThrow(ApiException::notFound);
        String result = jdbc.sql("SELECT platform_feedback_set_status(?, ?, ?, ?, ?)")
                .params(id, status, replyNote, operatorId, Timestamp.from(clock.now()))
                .query(String.class)
                .single();
        if ("not_found".equals(result)) {
            throw ApiException.notFound();
        }
        if ("backwards".equals(result)) {
            throw ApiException.rule(
                    "feedback_status_backwards",
                    "This feedback is already " + before.status() + "; it can only move forward (new, seen, done).");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("feedback_id", id);
        data.put("before", before.status());
        data.put("after", status);
        data.put("reply_changed", replyNote != null);
        platformAudit.record("platform.feedback.status_changed", operatorId, before.tenantId(), data);
        return byId(id).orElseThrow();
    }

    @Override
    @Transactional
    public Item setLinkedIssue(UUID id, Integer issueNumber, UUID operatorId) {
        if (issueNumber != null && issueNumber < 1) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "linked_issue_number", "invalid", "The issue number is a whole number above zero.")));
        }
        Item before = byId(id).orElseThrow(ApiException::notFound);
        String result = jdbc.sql("SELECT platform_feedback_set_linked_issue(?, ?)")
                .params(id, issueNumber)
                .query(String.class)
                .single();
        if ("not_found".equals(result)) {
            throw ApiException.notFound();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("feedback_id", id);
        data.put("before", before.linkedIssueNumber());
        data.put("after", issueNumber);
        platformAudit.record("platform.feedback.issue_linked", operatorId, before.tenantId(), data);
        return byId(id).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> tenantOf(UUID id) {
        return Optional.ofNullable(jdbc.sql("SELECT platform_feedback_tenant(?)")
                .param(id)
                .query(UUID.class)
                .single());
    }

    @Override
    public Screenshot screenshot(UUID id) {
        UUID documentId = transactions.execute(tx -> screenshotId(id));
        if (documentId == null) {
            throw ApiException.notFound();
        }
        Documents.Image image = documents.readImage(FeedbackDocumentAccess.SUBJECT, documentId);
        return new Screenshot(image.bytes(), image.contentType(), image.sha256());
    }

    /** One item through the list function, the operator's only read. */
    private Optional<Item> byId(UUID id) {
        return jdbc.sql("SELECT * FROM platform_feedback_list(NULL, ?, NULL, 1, 0)")
                .param(id)
                .query(FeedbackService::operatorItem)
                .optional();
    }

    private static Item operatorItem(ResultSet rs, int n) throws SQLException {
        return new Item(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_slug"),
                rs.getString("tenant_name"),
                rs.getString("sender_name"),
                rs.getString("kind"),
                rs.getString("message"),
                rs.getString("page_path"),
                rs.getString("app_version"),
                rs.getString("user_agent"),
                rs.getBoolean("has_screenshot"),
                rs.getString("status"),
                rs.getString("reply_note"),
                instant(rs.getTimestamp("status_changed_at")),
                rs.getObject("linked_issue_number", Integer.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
