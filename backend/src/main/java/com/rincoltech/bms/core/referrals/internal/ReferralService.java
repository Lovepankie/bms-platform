package com.rincoltech.bms.core.referrals.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.referrals.Referrals;
import com.rincoltech.bms.core.referrals.internal.ReferralsController.MyReferral;
import com.rincoltech.bms.core.referrals.internal.ReferralsController.MyReferrals;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Referrals and commissions (issue #464, ADR-069) through the SECURITY DEFINER functions of V89. The business's own view reads
 * inside the bound tenant; the operator's reads and writes run with no tenant bound, except the commission of a payment, which
 * runs in the paying business's transaction. Every operator write is audited in {@code platform_audit_log} with the reason.
 */
@Service
class ReferralService implements Referrals {

    static final Set<String> KINDS = Set.of("percent_of_payment", "fixed_first_payment", "fixed_per_month");
    static final Set<String> STATUSES = Set.of("applied", "active", "paying", "ended");
    static final Set<String> COMMISSION_FILTERS = Set.of("due", "paid");
    static final Set<String> PAID_METHODS = Set.of("mtn_momo", "airtel_money", "bank_transfer", "cash", "other");
    static final int EXPORT_LIMIT = 10_000;

    /** A code as people type it: letters and digits, any case, spaces at the ends ignored. */
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9]{4,10}$");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Map<String, Object>>> TOTALS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final PlatformAuditLog audit;
    private final PlatformHost hosts;
    private final BusinessClock clock;
    private final Idempotency idempotency;
    private final TransactionTemplate transactions;

    ReferralService(
            JdbcClient jdbc,
            PlatformAuditLog audit,
            PlatformHost hosts,
            BusinessClock clock,
            Idempotency idempotency,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.hosts = hosts;
        this.clock = clock;
        this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ---- The business's own view -------------------------------------------------------------

    /** The bound business's code, its link to the public sign-up, and the businesses it referred. */
    @Transactional(readOnly = true)
    MyReferrals mine() {
        String code = jdbc.sql("SELECT code FROM referral_codes")
                .query(String.class)
                .optional()
                .orElseThrow(ApiException::notFound);
        List<MyReferral> items = jdbc.sql("SELECT * FROM tenant_referrals_mine()")
                .query((rs, n) -> new MyReferral(
                        rs.getString("referred_name"),
                        rs.getTimestamp("joined_at").toInstant(),
                        rs.getString("status"),
                        totals(rs.getString("totals"))))
                .list();
        return new MyReferrals(
                code,
                link(code),
                items,
                sum(items.stream().flatMap(i -> i.totals().stream()).toList()));
    }

    String link(String code) {
        return hosts.platformOrigin() + "/sign-up?ref=" + code;
    }

    // ---- Sign-up and Activate --------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Code resolve(String code) {
        String typed = code == null ? "" : code.strip();
        UUID tenant = CODE.matcher(typed).matches()
                ? jdbc.sql("SELECT referral_code_tenant(?)")
                        .param(typed)
                        .query(UUID.class)
                        .optional()
                        .orElse(null)
                : null;
        if (tenant == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "referral_code_unknown",
                    "Referral code not found",
                    "No business has that referral code. Check it with the person who referred you, or leave it empty.",
                    List.of(new FieldProblem(
                            "referral_code",
                            "unknown",
                            "No business has that referral code. Check it, or leave it empty.")));
        }
        return new Code(typed.toUpperCase(Locale.ROOT), tenant);
    }

    @Override
    public void refuseSelf(Code code, String email, String phoneE164) {
        boolean self = Boolean.TRUE.equals(jdbc.sql("SELECT referral_is_self(?, ?, ?)")
                .params(code.tenantId(), email, phoneE164)
                .query(Boolean.class)
                .single());
        if (self) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "referral_self",
                    "Your own referral code",
                    "This code belongs to a business you run. A business is not referred by its own people; leave the"
                            + " code empty.",
                    List.of(new FieldProblem(
                            "referral_code", "self", "This code belongs to a business you run. Leave it empty.")));
        }
    }

    @Override
    public void attachToApplication(UUID applicationId, Code code) {
        jdbc.sql("SELECT onboarding_application_set_referrer(?, ?, ?)")
                .params(applicationId, code.tenantId(), code.code())
                .query()
                .listOfRows();
    }

    @Override
    public Optional<ApplicationReferrer> applicationReferrer(UUID applicationId) {
        return jdbc.sql("SELECT * FROM onboarding_application_referrer(?)")
                .param(applicationId)
                .query((rs, n) -> new ApplicationReferrer(
                        rs.getString("code"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_slug"),
                        rs.getString("tenant_name")))
                .optional();
    }

    @Override
    public Optional<UUID> recordFromApplication(UUID applicationId, UUID tenantId, UUID operatorId) {
        record Row(UUID referral, UUID referrer, String outcome) {}
        Row row = jdbc.sql("SELECT * FROM platform_referral_from_application(?, ?, ?)")
                .params(applicationId, tenantId, operatorId)
                .query((rs, n) -> new Row(
                        rs.getObject("referral_id", UUID.class),
                        rs.getObject("referrer_tenant_id", UUID.class),
                        rs.getString("outcome")))
                .single();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("application_id", applicationId);
        if ("recorded".equals(row.outcome())) {
            data.put("referral_id", row.referral());
            data.put("source", "sign_up");
            audit.record("platform.referral.recorded", operatorId, tenantId, data);
        } else if ("self".equals(row.outcome())) {
            // ADR-069 decision 10: the applicant is the Owner or an administrator of the referrer; checked again at
            // Activate, since the referrer's people may have changed since the sign-up.
            data.put("referrer_tenant_id", row.referrer());
            data.put("refused", "referral_self");
            audit.record("platform.referral.refused_self", operatorId, tenantId, data);
        }
        return Optional.ofNullable(row.referral());
    }

    // ---- The payment the operator records ----------------------------------------------------

    @Override
    public Commission commissionForPayment(UUID paymentId, UUID operatorId) {
        record Row(Commission commission, UUID referrer) {}
        Row row = jdbc.sql("SELECT * FROM platform_referral_commission_for_payment(?, ?)")
                .params(paymentId, operatorId)
                .query((rs, n) -> new Row(
                        new Commission(
                                rs.getString("outcome"),
                                rs.getObject("line_id", UUID.class),
                                rs.getObject("referral_id", UUID.class),
                                rs.getObject("amount_minor", Long.class),
                                rs.getString("currency")),
                        rs.getObject("referrer_tenant_id", UUID.class)))
                .single();
        Commission commission = row.commission();
        if (commission.lineId() != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("line_id", commission.lineId());
            data.put("referral_id", commission.referralId());
            data.put("kind", "payment");
            data.put("payment_id", paymentId);
            data.put("amount_minor", commission.amountMinor());
            data.put("currency", commission.currency());
            audit.record("platform.referral.commission_added", operatorId, row.referrer(), data);
        }
        return commission;
    }

    // ---- The operator: reads ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<Referral> list(Filter filter, int limit, int offset) {
        check(filter);
        return jdbc.sql("SELECT * FROM platform_referrals_list(?, NULL, ?, ?, NULL, ?, ?)")
                .params(filter.referrerTenantId(), filter.status(), filter.commission(), limit, offset)
                .query(ReferralService::referral)
                .list();
    }

    @Override
    @Transactional
    public String exportCsv(Filter filter, UUID operatorId) {
        List<Referral> rows = list(filter, EXPORT_LIMIT, 0);
        StringBuilder csv = new StringBuilder(
                "referral_id,application_id,referrer_slug,referrer_name,code,referred_slug,referred_name,status,source,"
                        + "joined_at,started_at,ended_at,rule_override,currency,earned_minor,paid_minor,due_minor\r\n");
        for (Referral r : rows) {
            List<Total> totals = r.totals().isEmpty() ? List.of(new Total(null, 0, 0, 0)) : r.totals();
            for (Total t : totals) {
                csv.append(String.join(
                                ",",
                                cell(r.referralId()),
                                cell(r.applicationId()),
                                cell(r.referrerSlug()),
                                cell(r.referrerName()),
                                cell(r.code()),
                                cell(r.referredSlug()),
                                cell(r.referredName()),
                                cell(r.status()),
                                cell(r.source()),
                                cell(r.joinedAt()),
                                cell(r.startedAt()),
                                cell(r.endedAt()),
                                cell(r.ruleOverride()),
                                cell(t.currency()),
                                cell(t.earnedMinor()),
                                cell(t.paidMinor()),
                                cell(t.dueMinor())))
                        .append("\r\n");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows.size());
        data.put("referrer_tenant_id", filter.referrerTenantId());
        data.put("status", filter.status());
        data.put("commission", filter.commission());
        audit.record("platform.referral.exported", operatorId, null, data);
        return csv.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public ReferralDetail detail(UUID referralId) {
        Referral referral = referral(referralId).orElseThrow(ApiException::notFound);
        Optional<Rule> override = jdbc.sql(
                        "SELECT rule_id FROM platform_referrals_list(NULL, NULL, NULL, NULL, ?, 1, 0)")
                .param(referralId)
                .query(UUID.class)
                .optional()
                .flatMap(id -> rule(id, false));
        Rule inForce = null;
        String source = "none";
        if (referral.endedAt() == null) {
            if (override.isPresent()) {
                inForce = override.get();
                source = "override";
            } else {
                inForce = defaultRule().orElse(null);
                source = inForce == null ? "none" : "default";
            }
        }
        List<Line> lines = jdbc.sql("SELECT * FROM platform_referral_lines(?)")
                .param(referralId)
                .query(ReferralService::line)
                .list();
        return new ReferralDetail(referral, inForce, source, lines);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Rule> defaultRule() {
        return rule(null, true);
    }

    // ---- The operator: writes ---------------------------------------------------------------

    @Override
    @Transactional
    public Optional<Referral> setReferrer(UUID tenantId, String referrerCode, String reason, UUID operatorId) {
        UUID referrer = null;
        if (referrerCode != null && !referrerCode.isBlank()) {
            referrer = resolve(referrerCode).tenantId();
            if (referrer.equals(tenantId)) {
                throw ApiException.rule(
                        "referral_self", "A business cannot refer itself. Choose another business's code.");
            }
        }
        Boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_tenant_overview(?))")
                .param(tenantId)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(exists)) {
            throw ApiException.notFound();
        }
        record Change(UUID ended, UUID previous, UUID started) {}
        Optional<Change> change = jdbc.sql("SELECT * FROM platform_referral_set_referrer(?, ?, ?, ?)")
                .params(tenantId, referrer, operatorId, Timestamp.from(clock.now()))
                .query((rs, n) -> new Change(
                        rs.getObject("ended_referral_id", UUID.class),
                        rs.getObject("previous_referrer", UUID.class),
                        rs.getObject("started_referral_id", UUID.class)))
                .optional();
        if (change.isPresent()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(
                    "before",
                    change.get().previous() == null
                            ? null
                            : change.get().previous().toString());
            data.put("after", referrer == null ? null : referrer.toString());
            data.put("ended_referral_id", change.get().ended());
            data.put("started_referral_id", change.get().started());
            data.put("reason", reason.trim());
            audit.record("platform.referral.referrer_changed", operatorId, tenantId, data);
        }
        return current(tenantId);
    }

    @Override
    @Transactional
    public Optional<Rule> setDefaultRule(RuleInput input, String reason, UUID operatorId) {
        UUID ruleId = input == null ? null : addRule(input, operatorId);
        UUID previous = jdbc.sql("SELECT platform_referral_set_default_rule(?, ?, ?)")
                .params(ruleId, operatorId, Timestamp.from(clock.now()))
                .query(UUID.class)
                .optional()
                .orElse(null);
        Optional<Rule> after = defaultRule();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", previous == null ? null : previous.toString());
        data.put("after", ruleId == null ? null : ruleId.toString());
        data.put("rule", after.map(ReferralService::describe).orElse(null));
        data.put("reason", reason.trim());
        audit.record("platform.referral.default_rule_changed", operatorId, null, data);
        return after;
    }

    @Override
    @Transactional
    public ReferralDetail setRule(UUID referralId, RuleInput input, String reason, UUID operatorId) {
        Referral referral = referral(referralId).orElseThrow(ApiException::notFound);
        UUID ruleId = input == null ? null : addRule(input, operatorId);
        String result = jdbc.sql("SELECT platform_referral_set_rule(?, ?)")
                .params(referralId, ruleId)
                .query(String.class)
                .single();
        if ("not_found".equals(result)) {
            throw ApiException.notFound();
        }
        if ("ended".equals(result)) {
            throw ApiException.rule(
                    "referral_ended",
                    "This referral ended when the business was given another referrer, so it earns nothing more.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("referral_id", referralId);
        data.put("before", result.isEmpty() ? null : result);
        data.put("after", ruleId == null ? null : ruleId.toString());
        data.put(
                "rule",
                input == null
                        ? null
                        : rule(ruleId, false).map(ReferralService::describe).orElse(null));
        data.put("reason", reason.trim());
        audit.record("platform.referral.rule_changed", operatorId, referral.referredTenantId(), data);
        return detail(referralId);
    }

    /** What the Idempotency-Key protocol hashes, and what it keeps for a replay. */
    record ManualLine(long amountMinor, String currency, String reason) {}

    record AddedLine(UUID lineId) {}

    /**
     * Not {@code @Transactional}: the line is written in a transaction bound to the referrer, so its Idempotency-Key row is that
     * business's (as a payment's is the paying business's); the reads around it run with no tenant bound.
     */
    @Override
    public LineAdded addLine(
            UUID referralId, String idempotencyKey, long amountMinor, String currency, String reason, UUID operatorId) {
        if (amountMinor < 1 || amountMinor > 1_000_000_000L) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "amount_minor", "out_of_range", "The amount is from 1 to 1,000,000,000 minor units.")));
        }
        String cur = transactions.execute(status -> currency(currency, "currency"));
        UUID referrer = transactions
                .execute(status -> referral(referralId))
                .orElseThrow(ApiException::notFound)
                .referrerTenantId();
        String path = "/api/v1/platform/referrals/" + referralId + "/commissions";
        ManualLine request = new ManualLine(amountMinor, cur, reason.trim());
        Idempotency.Outcome<AddedLine> outcome = TenantContext.callAs(
                referrer,
                () -> transactions.execute(status ->
                        idempotency.once(idempotencyKey, "POST", path, request, AddedLine.class, () -> {
                            UUID id = UUID.randomUUID();
                            jdbc.sql("SELECT platform_referral_add_manual_line(?, ?, ?, ?, ?, ?)")
                                    .params(id, referralId, amountMinor, cur, request.reason(), operatorId)
                                    .query()
                                    .singleRow();
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("line_id", id);
                            data.put("referral_id", referralId);
                            data.put("kind", "manual");
                            data.put("amount_minor", amountMinor);
                            data.put("currency", cur);
                            data.put("referrer_tenant_id", referrer);
                            data.put("reason", request.reason());
                            audit.record("platform.referral.commission_added", operatorId, referrer, data);
                            return new AddedLine(id);
                        })));
        return new LineAdded(transactions.execute(status -> detail(referralId)), outcome.replayed());
    }

    @Override
    @Transactional
    public ReferralDetail reverseLine(UUID lineId, String reason, UUID operatorId) {
        UUID id = UUID.randomUUID();
        String result = jdbc.sql("SELECT platform_referral_reverse_line(?, ?, ?, ?)")
                .params(id, lineId, reason.trim(), operatorId)
                .query(String.class)
                .single();
        switch (result) {
            case "not_found" -> throw ApiException.notFound();
            case "is_reversal" ->
                throw ApiException.rule(
                        "commission_line_is_reversal", "A reversal is not reversed. Add a new line instead.");
            case "already_reversed" ->
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "commission_line_already_reversed",
                        "Already reversed",
                        "This line was already reversed.");
            default -> {}
        }
        UUID referralId = UUID.fromString(result);
        ReferralDetail detail = detail(referralId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("line_id", id);
        data.put("reverses_line_id", lineId);
        data.put("referral_id", referralId);
        data.put("reason", reason.trim());
        audit.record(
                "platform.referral.commission_reversed",
                operatorId,
                detail.referral().referrerTenantId(),
                data);
        return detail;
    }

    @Override
    @Transactional
    public ReferralDetail markPaid(UUID lineId, Payout payout, UUID operatorId) {
        if (payout.method() == null || !PAID_METHODS.contains(payout.method())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "method", "invalid", "Choose mtn_momo, airtel_money, bank_transfer, cash or other.")));
        }
        LocalDate today = clock.today(BusinessClock.DEFAULT_ZONE);
        if (payout.paidOn() == null
                || payout.paidOn().isAfter(today)
                || payout.paidOn().isBefore(EARLIEST)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "paid_on", "out_of_range", "Give the day it was paid: from 1 Jan 2026 to today.")));
        }
        String result = jdbc.sql("SELECT platform_referral_mark_paid(?, ?, ?, ?, ?, ?, ?)")
                .params(
                        lineId,
                        payout.paidOn(),
                        payout.method(),
                        blankToNull(payout.reference()),
                        blankToNull(payout.note()),
                        operatorId,
                        Timestamp.from(clock.now()))
                .query(String.class)
                .single();
        if ("not_found".equals(result)) {
            throw ApiException.notFound();
        }
        if ("already_paid".equals(result)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "commission_already_paid",
                    "Already paid",
                    "This line is already marked paid. A mistake is corrected with a reversal.");
        }
        if ("reversed".equals(result)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "commission_line_reversed",
                    "Reversed",
                    "This line was reversed, so nothing is owed on it. It cannot be marked paid.");
        }
        UUID referralId = UUID.fromString(result);
        ReferralDetail detail = detail(referralId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("line_id", lineId);
        data.put("referral_id", referralId);
        data.put("paid_on", payout.paidOn().toString());
        data.put("method", payout.method());
        data.put("reason", payout.reason().trim());
        audit.record(
                "platform.referral.commission_paid",
                operatorId,
                detail.referral().referrerTenantId(),
                data);
        return detail;
    }

    /** No date in this product is older. */
    static final LocalDate EARLIEST = LocalDate.of(2026, 1, 1);

    // ---- Helpers ---------------------------------------------------------------------------

    private Optional<Referral> referral(UUID referralId) {
        return jdbc.sql("SELECT * FROM platform_referrals_list(NULL, NULL, NULL, NULL, ?, 1, 0)")
                .param(referralId)
                .query(ReferralService::referral)
                .optional();
    }

    /** The current referral of a business, or empty. */
    private Optional<Referral> current(UUID tenantId) {
        return jdbc
                .sql("SELECT * FROM platform_referrals_list(NULL, ?, NULL, NULL, NULL, 10000, 0)")
                .param(tenantId)
                .query(ReferralService::referral)
                .list()
                .stream()
                .filter(r -> r.endedAt() == null)
                .findFirst();
    }

    private Optional<Rule> rule(UUID id, boolean byDefault) {
        return jdbc.sql("SELECT * FROM platform_referral_rule(?, ?)")
                .params(id, byDefault)
                .query((rs, n) -> new Rule(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getObject("percent_bp", Integer.class),
                        rs.getObject("amount_minor", Long.class),
                        rs.getString("currency"),
                        rs.getObject("months", Integer.class),
                        rs.getObject("cap_minor", Long.class),
                        rs.getString("created_by_name"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /** Checks the rule in plain words (the table's CHECKs hold it too) and stores it. */
    private UUID addRule(RuleInput input, UUID operatorId) {
        List<FieldProblem> problems = ruleProblems(input);
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        String currency = input.currency() == null ? null : currency(input.currency(), "currency");
        UUID id = UUID.randomUUID();
        jdbc.sql("SELECT platform_referral_add_rule(?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        id,
                        input.kind(),
                        input.percentBp(),
                        input.amountMinor(),
                        currency,
                        input.months(),
                        input.capMinor(),
                        operatorId)
                .query()
                .listOfRows();
        return id;
    }

    static List<FieldProblem> ruleProblems(RuleInput r) {
        List<FieldProblem> problems = new ArrayList<>();
        if (r.kind() == null || !KINDS.contains(r.kind())) {
            problems.add(new FieldProblem(
                    "kind", "invalid", "Choose percent_of_payment, fixed_first_payment or fixed_per_month."));
            return problems;
        }
        boolean percent = r.kind().equals("percent_of_payment");
        if (percent) {
            if (r.percentBp() == null || r.percentBp() < 1 || r.percentBp() > 10_000) {
                problems.add(new FieldProblem(
                        "percent_bp",
                        "out_of_range",
                        "Give the percent in basis points, from 1 (0.01%) to 10000 (100%)."));
            }
            if (r.amountMinor() != null) {
                problems.add(new FieldProblem("amount_minor", "not_allowed", "A percent rule has no fixed amount."));
            }
            if (r.capMinor() != null && (r.capMinor() < 1 || r.capMinor() > 1_000_000_000L)) {
                problems.add(new FieldProblem(
                        "cap_minor", "out_of_range", "The cap is from 1 to 1,000,000,000 minor units, or empty."));
            }
            if (r.capMinor() != null && r.currency() == null) {
                problems.add(new FieldProblem("currency", "required", "Give the currency of the cap."));
            }
        } else {
            if (r.amountMinor() == null || r.amountMinor() < 1 || r.amountMinor() > 50_000_000L) {
                problems.add(new FieldProblem(
                        "amount_minor", "out_of_range", "Give the amount, from 1 to 50,000,000 minor units."));
            }
            if (r.currency() == null) {
                problems.add(new FieldProblem("currency", "required", "Give the currency of the amount."));
            }
            if (r.percentBp() != null) {
                problems.add(new FieldProblem("percent_bp", "not_allowed", "A fixed amount rule has no percent."));
            }
            if (r.capMinor() != null) {
                problems.add(new FieldProblem("cap_minor", "not_allowed", "Only a percent rule has a cap."));
            }
        }
        if (r.kind().equals("fixed_first_payment")) {
            if (r.months() != null) {
                problems.add(new FieldProblem(
                        "months", "not_allowed", "A first payment rule pays once; it has no number of months."));
            }
        } else if (r.months() != null && (r.months() < 1 || r.months() > 120)) {
            problems.add(new FieldProblem(
                    "months", "out_of_range", "The number of months is from 1 to 120, or empty for no end."));
        }
        return problems;
    }

    private String currency(String raw, String field) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        boolean known = code.matches("^[A-Z]{3}$")
                && Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM currencies WHERE code = ?)")
                        .param(code)
                        .query(Boolean.class)
                        .single());
        if (!known) {
            throw ApiException.validation(List.of(new FieldProblem(field, "unknown", "Choose UGX, KES, TZS or USD.")));
        }
        return code;
    }

    private static void check(Filter filter) {
        if (filter.status() != null && !STATUSES.contains(filter.status())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is applied, active, paying or ended.")));
        }
        if (filter.commission() != null && !COMMISSION_FILTERS.contains(filter.commission())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("commission", "invalid", "commission is due or paid.")));
        }
    }

    private static Map<String, Object> describe(Rule rule) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rule.id().toString());
        out.put("kind", rule.kind());
        out.put("percent_bp", rule.percentBp());
        out.put("amount_minor", rule.amountMinor());
        out.put("currency", rule.currency());
        out.put("months", rule.months());
        out.put("cap_minor", rule.capMinor());
        return out;
    }

    /** The totals of several rows, one per currency. */
    static List<Total> sum(List<Total> totals) {
        Map<String, long[]> byCurrency = new TreeMap<>();
        for (Total t : totals) {
            long[] sums = byCurrency.computeIfAbsent(t.currency(), c -> new long[3]);
            sums[0] += t.earnedMinor();
            sums[1] += t.paidMinor();
            sums[2] += t.dueMinor();
        }
        return byCurrency.entrySet().stream()
                .map(e -> new Total(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }

    static List<Total> totals(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, TOTALS).stream()
                .map(t -> new Total(
                        (String) t.get("currency"),
                        ((Number) t.get("earned_minor")).longValue(),
                        ((Number) t.get("paid_minor")).longValue(),
                        ((Number) t.get("due_minor")).longValue()))
                .toList();
    }

    private static Referral referral(ResultSet rs, int n) throws SQLException {
        return new Referral(
                rs.getObject("referral_id", UUID.class),
                rs.getObject("application_id", UUID.class),
                rs.getObject("referrer_tenant_id", UUID.class),
                rs.getString("referrer_slug"),
                rs.getString("referrer_name"),
                rs.getString("code"),
                rs.getObject("referred_tenant_id", UUID.class),
                rs.getString("referred_slug"),
                rs.getString("referred_name"),
                rs.getString("status"),
                rs.getString("source"),
                instant(rs.getTimestamp("joined_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("ended_at")),
                rs.getObject("rule_id", UUID.class) != null,
                totals(rs.getString("totals")));
    }

    private static Line line(ResultSet rs, int n) throws SQLException {
        String snapshot = rs.getString("rule_snapshot");
        return new Line(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("reverses_line_id", UUID.class),
                rs.getObject("reversed_by_line_id", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getObject("months_counted", Integer.class),
                snapshot == null ? null : JSON.readValue(snapshot, OBJECT),
                rs.getString("note"),
                rs.getObject("paid_on", LocalDate.class),
                rs.getString("paid_method"),
                rs.getString("paid_reference"),
                rs.getString("paid_note"),
                instant(rs.getTimestamp("paid_at")),
                rs.getString("paid_by_name"),
                rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * RFC 4180 quoting; a text starting with a formula character (= + - @, a tab or a carriage return) is neutralised for
     * spreadsheet safety (as the audit export). A number is written as it is, so a negative amount stays a number.
     */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.contains("'")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
