package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.core.tenancy.internal.SettingsController.SettingsResponse;
import com.rincoltech.bms.core.tenancy.internal.SettingsController.UpdateSettingsRequest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.Versions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
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
class SettingsService implements TenantSettings {

    static final Map<String, Integer> DEFAULT_APPRAISAL_WEIGHTS =
            Map.of("repayment_history", 40, "affordability", 30, "collateral_cover", 20, "exposure", 10);
    private static final Pattern ACTION_TYPE = Pattern.compile("^[a-z][a-z0-9_]{2,62}$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CurrentTenant tenant;
    private final AuditLog audit;

    SettingsService(JdbcClient jdbc, ObjectMapper mapper, CurrentTenant tenant, AuditLog audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tenant = tenant;
        this.audit = audit;
    }

    private record Stored(Map<String, Object> values, int version) {}

    @Transactional(readOnly = true)
    SettingsResponse current() {
        Stored stored = load(false);
        return resolve(stored.values(), stored.version());
    }

    @Transactional
    SettingsResponse update(String ifMatch, UpdateSettingsRequest request) {
        int expected = Versions.fromIfMatch(ifMatch);
        jdbc.sql("""
                        INSERT INTO tenant_settings (id, tenant_id) VALUES (?, current_setting('app.tenant_id')::uuid)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """).param(UUID.randomUUID()).update();
        Stored stored = load(true);
        if (stored.version() != expected) {
            throw Versions.conflict(stored.version());
        }
        validate(request);
        SettingsResponse before = resolve(stored.values(), stored.version());

        Map<String, Object> changes = mapper.convertValue(request, MAP);
        changes.values().removeIf(Objects::isNull);
        Map<String, Object> next = new TreeMap<>(stored.values());
        next.putAll(changes);
        SettingsResponse after = resolve(next, stored.version() + 1);
        if (after.smsWindowStart().compareTo(after.smsWindowEnd()) >= 0) {
            throw ApiException.validation(
                    List.of(new FieldProblem("sms_window_end", "invalid", "Must be later than sms_window_start.")));
        }

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
        return after;
    }

    @Override
    @Transactional(readOnly = true)
    public long approvalThresholdMinor(String actionType) {
        Long threshold = current().approvalThresholdsMinor().get(actionType);
        return threshold == null ? 0 : threshold;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean requireMfaAllStaff() {
        return current().requireMfaAllStaff();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> disabledCollateralTypes() {
        return Set.copyOf(current().disabledCollateralTypes());
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
        if (request.displayName() != null && request.displayName().isBlank()) {
            problems.add(new FieldProblem("display_name", "invalid", "Must not be blank."));
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
                (String) s.get("sms_sender_name"),
                (String) s.getOrDefault("sms_window_start", "08:00"),
                (String) s.getOrDefault("sms_window_end", "20:00"),
                thresholds,
                ((Number) s.getOrDefault("approval_validity_days", 14)).intValue(),
                (Boolean) s.getOrDefault("allow_loans_before_kyc_verified", false),
                maxLoans == null ? null : ((Number) maxLoans).intValue(),
                weights,
                List.copyOf((List<String>) s.getOrDefault("disabled_collateral_types", List.of())),
                (Boolean) s.getOrDefault("require_mfa_all_staff", false),
                version);
    }
}
