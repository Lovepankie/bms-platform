package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.GoLiveDate;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.core.tenancy.ModuleSettingsGroup;
import com.rincoltech.bms.core.tenancy.PolicyChangeGuard;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.core.tenancy.TwoStepPolicy;
import com.rincoltech.bms.core.tenancy.internal.SettingsController.SettingsResponse;
import com.rincoltech.bms.core.tenancy.internal.SettingsController.UpdateSettingsRequest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.kernel.Versions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Tenant settings (FR-TEN-08, chapter 6 table {@code tenant_settings}). The row stores only the
 * keys a tenant admin has set; every read applies the chapter 6 defaults to the rest. Each change
 * is validated and audited with the before and after values of the keys that changed.
 */
@Service
class SettingsService implements TenantSettings, ModuleSettings, GoLiveDate {

    static final Map<String, Integer> DEFAULT_APPRAISAL_WEIGHTS =
            Map.of("repayment_history", 40, "affordability", 30, "collateral_cover", 20, "exposure", 10);
    private static final String MODULE_GROUPS = "module_groups";
    private static final Pattern ACTION_TYPE = Pattern.compile("^[a-z][a-z0-9_]{2,62}$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CurrentTenant tenant;
    private final AuditLog audit;
    private final Documents documents;
    private final Map<String, ModuleSettingsGroup> groups = new TreeMap<>();
    private final TenantModules modules;
    private final BusinessClock clock;
    private final PolicyChangeGuard guard;
    private final ApplicationEventPublisher events;

    SettingsService(
            JdbcClient jdbc,
            ObjectMapper mapper,
            CurrentTenant tenant,
            AuditLog audit,
            Documents documents,
            List<ModuleSettingsGroup> groupBeans,
            TenantModules modules,
            BusinessClock clock,
            @Lazy PolicyChangeGuard guard,
            ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tenant = tenant;
        this.audit = audit;
        this.documents = documents;
        groupBeans.forEach(g -> groups.put(g.moduleKey(), g));
        this.modules = modules;
        this.clock = clock;
        this.guard = guard;
    }

    private record Stored(Map<String, Object> values, int version) {}

    @Transactional(readOnly = true)
    SettingsResponse current() {
        Stored stored = load(false);
        return resolve(stored.values(), stored.version());
    }

    /**
     * @param proved the administrator's proof was checked before this transaction (it is needed when the change relaxes the
     *     two-step rule); the row is read again under its lock, and a change that relaxes it without that proof is refused
     */
    @Transactional
    SettingsResponse update(String ifMatch, UpdateSettingsRequest request, boolean proved) {
        int expected = Versions.fromIfMatch(ifMatch);
        if (request.goLiveDate() != null) {
            // ADR-071: the go-live date bounds what may be brought in with a past date, so only the Owner moves it; the
            // owner lock comes first in the transaction (ADR-062).
            guard.requireOwner("set the go-live date");
        }
        Stored stored = lock();
        if (stored.version() != expected) {
            throw Versions.conflict(stored.version());
        }
        validate(request);
        if (request.goLiveDate() != null) {
            requireEarlier(stored, request.goLiveDate());
        }
        Map<String, Object> changes = plan(stored, request);
        boolean relaxes = relaxes(stored.values(), changes);
        if (relaxes && !proved) {
            throw ApiException.rule("proof_required", "Confirm that it is you, then save again.");
        }
        SettingsResponse after = write(stored, changes);
        if (relaxes) {
            // In this transaction: the change and the warning to the administrators commit together (ADR-046 decision
            // 6).
            guard.notifyRelaxed(
                    after.requireMfaRoles().isEmpty() && !after.requireMfaAllStaff()
                            ? "two-step sign-in is now optional for everyone"
                            : "two-step sign-in is now required of fewer people, or its date was put back");
            // The same change tells the administrators with a device (ADR-050): narrowing, switching off or postponing.
            events.publishEvent(new PushNotice(
                    "core.signin_policy_weakened",
                    "settings-v" + after.version(),
                    new PushNotice.Holders(
                            "core.settings.manage",
                            null,
                            CurrentPrincipal.get().map(Principal::userId).orElse(null)),
                    Map.of("path", "/staff/setup")));
        }
        return after;
    }

    /** The keys to write for a request, the deadline included. */
    private Map<String, Object> plan(Stored stored, UpdateSettingsRequest request) {
        Map<String, Object> changes = mapper.convertValue(request, MAP);
        changes.values().removeIf(Objects::isNull);
        // The proof of the administrator is not a setting: it is never stored, audited or echoed.
        changes.remove("totp_code");
        changes.remove("password");
        if (changes.get("theme_primary") instanceof String colour) {
            // An empty string clears the colour: back to the platform look.
            changes.put("theme_primary", colour.isEmpty() ? null : colour.toUpperCase(Locale.ROOT));
        }
        if (request.goLiveDate() != null) {
            changes.put("go_live_date", request.goLiveDate().toString());
            changes.put("go_live_source", "set");
        }
        if (changes.containsKey("require_mfa_roles")) {
            changes.put(
                    "require_mfa_roles",
                    request.requireMfaRoles().stream().distinct().sorted().toList());
        }
        placeTwoStepDeadline(stored, changes);
        return changes;
    }

    /**
     * Whether this change needs the administrator's proof (ADR-046): it turns the two-step requirement off, narrows it, or puts a
     * deadline back (a later date than the one set, or a date where people were required at once). Read without a lock, for the
     * check that must come before the transaction; {@link #update} decides again on the locked row.
     */
    @Transactional(readOnly = true)
    boolean relaxesTwoStep(UpdateSettingsRequest request) {
        Stored stored = load(false);
        return relaxes(stored.values(), plan(stored, request));
    }

    private static boolean relaxes(Map<String, Object> stored, Map<String, Object> changes) {
        Map<String, Object> merged = new TreeMap<>(stored);
        changes.forEach((k, v) -> {
            if (v == null) {
                merged.remove(k);
            } else {
                merged.put(k, v);
            }
        });
        TwoStepPolicy before = policyOf(stored);
        TwoStepPolicy after = policyOf(merged);
        if (before.off()) {
            return false;
        }
        if (after.off()) {
            return true;
        }
        boolean narrower = before.everyone()
                ? !after.everyone()
                : !after.everyone() && !after.roles().containsAll(before.roles());
        if (narrower) {
            return true;
        }
        Object sent = changes.get("mfa_enforce_from");
        return sent != null
                && (before.enforceFrom() == null || Instant.parse((String) sent).isAfter(before.enforceFrom()));
    }

    /**
     * ADR-046 decision 3: the deadline moves only when a grace period is sent, and is cleared when the policy is turned off. A grace
     * of N days means people may sign in until the END of the day N days from today in the business's own time zone, so the stored
     * instant is the start of the day after (0 days clears it: at once). Narrowing, widening or any other save without a grace
     * period leaves the deadline where it is.
     */
    private void placeTwoStepDeadline(Stored stored, Map<String, Object> changes) {
        if (!changes.containsKey("require_mfa_all_staff")
                && !changes.containsKey("require_mfa_roles")
                && !changes.containsKey("mfa_grace_days")) {
            return;
        }
        Map<String, Object> merged = new TreeMap<>(stored.values());
        merged.putAll(changes);
        TwoStepPolicy after = policyOf(merged);
        if (after.off()) {
            changes.put("mfa_enforce_from", null);
            return;
        }
        if (changes.containsKey("mfa_grace_days")) {
            int days = after.graceDays() == null ? 0 : after.graceDays();
            changes.put(
                    "mfa_enforce_from", days == 0 ? null : deadlineAfter(days).toString());
        }
    }

    /** The first instant of the day after today plus {@code days}, in the business's own time zone. */
    Instant deadlineAfter(int days) {
        ZoneId zone = tenant.profile().timezone();
        return clock.today(zone).plusDays(days + 1L).atStartOfDay(zone).toInstant();
    }

    @SuppressWarnings("unchecked")
    private static TwoStepPolicy policyOf(Map<String, Object> s) {
        Object grace = s.get("mfa_grace_days");
        Object from = s.get("mfa_enforce_from");
        return new TwoStepPolicy(
                Boolean.TRUE.equals(s.get("require_mfa_all_staff")),
                Set.copyOf((List<String>) s.getOrDefault("require_mfa_roles", List.of())),
                grace == null ? null : ((Number) grace).intValue(),
                from == null ? null : Instant.parse((String) from));
    }

    /** Stores a prepared logo as a core document of this tenant and makes it the current one (FR-TEN-08). */
    @Transactional
    SettingsResponse replaceLogo(Documents.Prepared prepared) {
        // Stored first: the object write does not wait inside the settings row lock.
        UUID id = documents
                .store(prepared, TenantDocumentAccess.SUBJECT, tenant.profile().id(), null)
                .id();
        Stored stored = lock();
        return write(stored, Map.of("logo_document_id", id.toString()));
    }

    /** The previous logo's document stays (documents are never deleted); only the setting is cleared. */
    @Transactional
    SettingsResponse removeLogo() {
        Stored stored = lock();
        if (!stored.values().containsKey("logo_document_id")) {
            return resolve(stored.values(), stored.version());
        }
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("logo_document_id", null);
        return write(stored, clear);
    }

    private Stored lock() {
        jdbc.sql("""
                        INSERT INTO tenant_settings (id, tenant_id) VALUES (?, current_setting('app.tenant_id')::uuid)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """).param(UUID.randomUUID()).update();
        return load(true);
    }

    /** Applies changes (a null value clears the key), saves with a new version and audits was and now. */
    private SettingsResponse write(Stored stored, Map<String, Object> changes) {
        SettingsResponse before = resolve(stored.values(), stored.version());
        Map<String, Object> next = new TreeMap<>(stored.values());
        changes.forEach((k, v) -> {
            if (v == null) {
                next.remove(k);
            } else {
                next.put(k, v);
            }
        });
        SettingsResponse after = resolve(next, stored.version() + 1);

        jdbc.sql("UPDATE tenant_settings SET settings = CAST(? AS jsonb), updated_at = now(), version = version + 1")
                .param(mapper.writeValueAsString(next))
                .update();

        Map<String, Object> was = mapper.convertValue(before, MAP);
        Map<String, Object> now = mapper.convertValue(after, MAP);
        Map<String, Object> auditBefore = new LinkedHashMap<>();
        Map<String, Object> auditAfter = new LinkedHashMap<>();
        for (String key : changes.keySet()) {
            if (!Objects.equals(was.get(key), now.get(key))) {
                auditBefore.put(key, was.get(key));
                auditAfter.put(key, now.get(key));
            }
        }
        audit.record(new AuditLog.Entry(
                "core.settings.updated",
                "core.tenant_settings",
                tenant.profile().id(),
                null,
                auditBefore,
                auditAfter));
        announceWeakening(before, after);
        return after;
    }

    /**
     * The administrators with a device are told when a safeguard is loosened (ADR-050): the amount that needs a second person
     * raised for an action. The person who changed it is not told. The
     * names of the actions are the ones the approvals inbox shows.
     */
    private void announceWeakening(SettingsResponse before, SettingsResponse after) {
        UUID by = CurrentPrincipal.get().map(Principal::userId).orElse(null);
        List<String> raised = after.approvalThresholdsMinor().entrySet().stream()
                .filter(e -> e.getValue() > before.approvalThresholdsMinor().getOrDefault(e.getKey(), 0L))
                .map(e -> e.getKey().replace('_', ' '))
                .sorted()
                .toList();
        if (!raised.isEmpty()) {
            events.publishEvent(new PushNotice(
                    "core.approval_policy_weakened",
                    "settings-v" + after.version(),
                    new PushNotice.Holders("core.settings.manage", null, by),
                    Map.of(
                            "action_label",
                            String.join(", ", raised.subList(0, Math.min(3, raised.size()))),
                            "path",
                            "/staff/setup")));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long approvalThresholdMinor(String actionType) {
        Long threshold = current().approvalThresholdsMinor().get(actionType);
        return threshold == null ? 0 : threshold;
    }

    @Override
    @Transactional(readOnly = true)
    public TwoStepPolicy twoStepPolicy() {
        return policyOf(load(false).values());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> disabledCollateralTypes() {
        return Set.copyOf(current().disabledCollateralTypes());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allowLoansBeforeKycVerified() {
        return current().allowLoansBeforeKycVerified();
    }

    @Override
    @Transactional(readOnly = true)
    public LocalTime smsWindowStart() {
        // The SMS settings are the module group "sms" (core.notifications, ADR-036), written only by PUT /settings/sms.
        return LocalTime.parse((String) group("sms").get("window_start"));
    }

    @Override
    @Transactional(readOnly = true)
    public LocalTime smsWindowEnd() {
        return LocalTime.parse((String) group("sms").get("window_end"));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean smsIncludeBalance() {
        return Boolean.TRUE.equals(group("sms").get("include_balance"));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> appraisalWeights() {
        return Map.copyOf(current().appraisalWeights());
    }

    @Override
    @Transactional(readOnly = true)
    public int approvalValidityDays() {
        return current().approvalValidityDays();
    }

    @Override
    @Transactional(readOnly = true)
    public Integer maxActiveLoansPerMember() {
        return current().maxActiveLoansPerMember();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean reportsEnabled() {
        return current().reportsEnabled();
    }

    // ---- the go-live date (ADR-071) ------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<Live> get() {
        Map<String, Object> values = load(false).values();
        return Optional.ofNullable((String) values.get("go_live_date"))
                .map(d -> new Live(LocalDate.parse(d), (String) values.getOrDefault("go_live_source", "set")));
    }

    /**
     * ADR-071: a later go-live date would let loans disbursed after go-live come in as if from paper, so once a date is
     * stored the Owner moves it only earlier. The operator moves it later, with a reason ({@link #setByOperator}).
     */
    private void requireEarlier(Stored stored, LocalDate date) {
        Object was = stored.values().get("go_live_date");
        if (was != null && date.isAfter(LocalDate.parse((String) was))) {
            throw ApiException.rule(
                    "go_live_locked",
                    "The go-live date (" + was + ") only moves earlier. Ask the platform operator to move it later,"
                            + " with a reason.");
        }
    }

    @Override
    @Transactional
    public void adminSignedIn(UUID userId, Instant at) {
        if (load(false).values().get("go_live_date") != null) {
            return;
        }
        Stored stored = lock();
        if (stored.values().get("go_live_date") != null) {
            return;
        }
        LocalDate day = firstDay(at.atZone(tenant.profile().timezone()).toLocalDate());
        Map<String, Object> next = new TreeMap<>(stored.values());
        next.put("go_live_date", day.toString());
        next.put("go_live_source", "first_admin_sign_in");
        // The version stays: no administrator's edit of the settings is made stale by a sign-in.
        jdbc.sql("UPDATE tenant_settings SET settings = CAST(? AS jsonb), updated_at = now() WHERE tenant_id = ?")
                .params(mapper.writeValueAsString(next), tenant.profile().id())
                .update();
        audit.record(
                AuditLog.Entry.created(
                        "core.settings.go_live_defaulted",
                        "core.tenant_settings",
                        tenant.profile().id(),
                        null,
                        Map.of("go_live_date", day.toString(), "source", "first_admin_sign_in")),
                userId,
                "staff");
    }

    /**
     * The default go-live date never lands later than the real start (ADR-071 decision 5), or a loan made in the app could
     * come back as a "paper" loan: the earliest of this sign-in, the first session of anyone ever an administrator, the
     * earliest last sign-in of the staff, the day the business was created, and the first loan made in the app (created
     * or disbursed). Sessions are pruned and administrators leave, so this sign-in alone may come too late. V89 backfills
     * the same date.
     */
    private LocalDate firstDay(LocalDate signedIn) {
        return jdbc.sql("""
                        SELECT LEAST(CAST(? AS date),
                                     (SELECT (min(s.created_at) AT TIME ZONE t.timezone)::date
                                        FROM auth_sessions s
                                       WHERE s.tenant_id = t.id
                                         AND EXISTS (SELECT 1 FROM user_role_assignments a
                                                      WHERE a.user_id = s.user_id AND a.role_key = 'tenant_admin')),
                                     (SELECT (min(u.last_login_at) AT TIME ZONE t.timezone)::date
                                        FROM users u WHERE u.tenant_id = t.id AND u.kind = 'staff'),
                                     (t.created_at AT TIME ZONE t.timezone)::date,
                                     (SELECT least((min(l.created_at) AT TIME ZONE t.timezone)::date, min(l.disbursed_on))
                                        FROM lending_loans l WHERE l.tenant_id = t.id AND l.go_live_on IS NULL))
                          FROM tenants t WHERE t.id = ?
                        """)
                .params(signedIn, tenant.profile().id())
                .query(LocalDate.class)
                .single();
    }

    @Override
    @Transactional
    public void setByOperator(LocalDate date, UUID platformUserId, String reason) {
        if (date.isAfter(clock.today(tenant.profile().timezone()))) {
            throw ApiException.validation(List.of(
                    new FieldProblem("go_live_date", "go_live_in_future", "The go-live date is today or earlier.")));
        }
        Stored stored = lock();
        Object before = stored.values().get("go_live_date");
        Map<String, Object> next = new TreeMap<>(stored.values());
        next.put("go_live_date", date.toString());
        next.put("go_live_source", "set");
        jdbc.sql(
                        "UPDATE tenant_settings SET settings = CAST(? AS jsonb), updated_at = now(), version = version + 1 WHERE tenant_id = ?")
                .params(mapper.writeValueAsString(next), tenant.profile().id())
                .update();
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("go_live_date", before);
        audit.record(
                new AuditLog.Entry(
                        "core.settings.go_live_set",
                        "core.tenant_settings",
                        tenant.profile().id(),
                        null,
                        was,
                        Map.of("go_live_date", date.toString(), "reason", reason)),
                platformUserId,
                "platform");
    }

    // ---- module settings groups (ADR-033) ---------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> group(String moduleKey) {
        ModuleSettingsGroup group = groupOf(moduleKey);
        return group.normalise(storedGroup(load(false), moduleKey), Map.of());
    }

    @Override
    @Transactional
    public Map<String, Object> change(String moduleKey, Map<String, Object> proposed, int expectedVersion) {
        ModuleSettingsGroup group = groupOf(moduleKey);
        Stored stored = lock();
        if (stored.version() != expectedVersion) {
            throw Versions.conflict(stored.version());
        }
        Map<String, Object> before = group.normalise(storedGroup(stored, moduleKey), Map.of());
        Map<String, Object> after = group.normalise(before, proposed);
        if (after.equals(before)) {
            return after;
        }
        Map<String, Object> next = new TreeMap<>(stored.values());
        Map<String, Object> all = new TreeMap<>(allGroups(stored));
        all.put(moduleKey, after);
        next.put(MODULE_GROUPS, all);
        jdbc.sql("UPDATE tenant_settings SET settings = CAST(? AS jsonb), updated_at = now(), version = version + 1")
                .param(mapper.writeValueAsString(next))
                .update();
        audit.record(new AuditLog.Entry(
                group.auditAction(), "core.tenant_settings", tenant.profile().id(), null, before, after));
        return after;
    }

    @Override
    @Transactional(readOnly = true)
    public int version() {
        return load(false).version();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<String>> enabledCapabilities() {
        Stored stored = load(false);
        Map<String, List<String>> out = new TreeMap<>();
        for (ModuleSettingsGroup group : groups.values()) {
            if (modules.isEnabled(group.moduleKey())) {
                Map<String, Object> normalised = group.normalise(storedGroup(stored, group.moduleKey()), Map.of());
                out.put(group.moduleKey(), group.enabledCapabilities(normalised));
            }
        }
        return out;
    }

    private ModuleSettingsGroup groupOf(String moduleKey) {
        ModuleSettingsGroup group = groups.get(moduleKey);
        if (group == null) {
            throw new IllegalArgumentException("No settings group for module " + moduleKey);
        }
        return group;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> allGroups(Stored stored) {
        return stored.values().get(MODULE_GROUPS) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> storedGroup(Stored stored, String moduleKey) {
        return allGroups(stored).get(moduleKey) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private Stored load(boolean forUpdate) {
        return jdbc.sql("SELECT settings::text AS settings, version FROM tenant_settings"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .query((rs, n) -> new Stored(mapper.readValue(rs.getString("settings"), MAP), rs.getInt("version")))
                .optional()
                .orElse(new Stored(Map.of(), 1));
    }

    private void validate(UpdateSettingsRequest request) {
        List<FieldProblem> problems = new ArrayList<>();
        if (request.approvalThresholdsMinor() != null) {
            request.approvalThresholdsMinor().forEach((action, amount) -> {
                if (!ACTION_TYPE.matcher(action).matches() || action.equals("loan_approval")) {
                    problems.add(new FieldProblem(
                            "approval_thresholds_minor", "invalid", "Unknown action type or one without threshold."));
                } else if (amount == null || amount < 0) {
                    problems.add(new FieldProblem("approval_thresholds_minor", "invalid", "Amounts are 0 or more."));
                }
            });
        }
        if (request.appraisalWeights() != null) {
            Map<String, Integer> w = request.appraisalWeights();
            boolean keysOk = w.keySet().equals(DEFAULT_APPRAISAL_WEIGHTS.keySet());
            boolean valuesOk = w.values().stream().allMatch(v -> v != null && v >= 0);
            if (!keysOk
                    || !valuesOk
                    || w.values().stream().mapToInt(Integer::intValue).sum() != 100) {
                problems.add(new FieldProblem(
                        "appraisal_weights", "invalid", "Give all four weights, each 0 or more, summing to 100."));
            }
        }
        if (request.requireMfaRoles() != null
                && !Set.copyOf(jdbc.sql("SELECT key FROM roles WHERE kind = 'staff'")
                                .query(String.class)
                                .list())
                        .containsAll(request.requireMfaRoles())) {
            problems.add(
                    new FieldProblem("require_mfa_roles", "invalid", "Choose roles from the list of staff roles."));
        }
        if (request.goLiveDate() != null
                && request.goLiveDate().isAfter(clock.today(tenant.profile().timezone()))) {
            problems.add(
                    new FieldProblem("go_live_date", "go_live_in_future", "The go-live date is today or earlier."));
        }
        if (request.displayName() != null && request.displayName().isBlank()) {
            problems.add(new FieldProblem("display_name", "invalid", "Must not be blank."));
        }
        if (request.themePrimary() != null
                && !request.themePrimary().isEmpty()
                && BrandColour.readableText(request.themePrimary()).isEmpty()) {
            problems.add(
                    new FieldProblem(
                            "theme_primary",
                            "insufficient_contrast",
                            "Neither white nor near-black text is readable on this colour (contrast 4.5 needed). Pick a lighter or darker one."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
    }

    @SuppressWarnings("unchecked")
    private SettingsResponse resolve(Map<String, Object> s, int version) {
        Map<String, Long> thresholds = new TreeMap<>();
        ((Map<String, Object>) s.getOrDefault("approval_thresholds_minor", Map.of()))
                .forEach((k, v) -> thresholds.put(k, ((Number) v).longValue()));
        Map<String, Integer> weights = new TreeMap<>();
        ((Map<String, Object>) s.getOrDefault("appraisal_weights", DEFAULT_APPRAISAL_WEIGHTS))
                .forEach((k, v) -> weights.put(k, ((Number) v).intValue()));
        Object maxLoans = s.get("max_active_loans_per_member");
        return new SettingsResponse(
                (String) s.getOrDefault("display_name", tenant.profile().name()),
                (String) s.getOrDefault("receipt_footer", ""),
                thresholds,
                ((Number) s.getOrDefault("approval_validity_days", 14)).intValue(),
                (Boolean) s.getOrDefault("allow_loans_before_kyc_verified", false),
                maxLoans == null ? null : ((Number) maxLoans).intValue(),
                weights,
                List.copyOf((List<String>) s.getOrDefault("disabled_collateral_types", List.of())),
                (Boolean) s.getOrDefault("require_mfa_all_staff", false),
                s.get("logo_document_id") == null ? null : UUID.fromString((String) s.get("logo_document_id")),
                (String) s.get("theme_primary"),
                s.containsKey("display_name"),
                (Boolean) s.getOrDefault("setup_dismissed", false),
                (Boolean) s.getOrDefault("reports_enabled", false),
                List.copyOf((List<String>) s.getOrDefault("require_mfa_roles", List.of())),
                s.get("mfa_grace_days") == null ? null : ((Number) s.get("mfa_grace_days")).intValue(),
                s.get("mfa_enforce_from") == null ? null : Instant.parse((String) s.get("mfa_enforce_from")),
                tenant.profile().timezone().getId(),
                s.get("go_live_date") == null ? null : LocalDate.parse((String) s.get("go_live_date")),
                version);
    }
}
