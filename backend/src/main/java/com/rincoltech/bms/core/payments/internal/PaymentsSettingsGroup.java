package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.ledger.PaymentMethods;
import com.rincoltech.bms.core.tenancy.ModuleSettingsGroup;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tenant settings group {@code payments} (chapter 6 section 6.5, ADR-035 decision 11): the switch
 * {@code collections_enabled} (off by default) and a daily ceiling on what the tenant may ask the provider to collect
 * (absent means none; a placeholder for the commercial limits that are still to be decided). Stored in the tenant
 * settings row, versioned with it and audited with before and after. Every write path goes through {@link #normalise}, so
 * the rules hold whoever calls: switching on is refused with 409 when
 *
 * <ul>
 *   <li>{@code merchant_account_not_verified}: this tenant has not connected and tested its own merchant account
 *       ({@code Receive payments}, issue #233). Money owed to a tenant lands only in that tenant's own account, so there is
 *       nothing to switch on until it exists; the platform's account is never a substitute;
 *   <li>{@code gateway_method_unmapped}: the {@code gateway} payment method has no active ledger account yet (FR-GL-08);
 *   <li>{@code gateway_not_configured}: the host has no payment provider or callback address, so no account can be tested;
 *   <li>{@code daily_ceiling_required}: no daily ceiling is set (a bound on what one day can ask for; it cannot be
 *       removed while collections are on).
 * </ul>
 *
 * Switching off is never refused. A ceiling lower than today's use is allowed; it simply refuses the next intent.
 */
@Component
class PaymentsSettingsGroup implements ModuleSettingsGroup {

    static final String KEY = "payments";
    static final String ENABLED = "collections_enabled";
    static final String CEILING = "daily_ceiling_minor";
    private static final Set<String> KEYS = Set.of(ENABLED, CEILING);

    private final PesapalSettings pesapal;
    private final VerifiedAccounts merchantAccounts;
    private final PaymentMethods methods;
    private final TransactionTemplate isolated;

    PaymentsSettingsGroup(
            PesapalSettings pesapal,
            VerifiedAccounts merchantAccounts,
            PaymentMethods methods,
            PlatformTransactionManager transactions) {
        this.pesapal = pesapal;
        this.merchantAccounts = merchantAccounts;
        this.methods = methods;
        this.isolated = new TransactionTemplate(transactions);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolated.setReadOnly(true);
    }

    @Override
    public String moduleKey() {
        return KEY;
    }

    @Override
    public String auditAction() {
        return "core.payments.settings.changed";
    }

    /** True when this tenant has connected and tested its own merchant account (read in the caller's transaction). */
    boolean merchantAccountVerified() {
        return merchantAccounts.verified();
    }

    /** True when the host can serve a lender's own account at all (a provider and a callback address). */
    boolean gatewayAvailable() {
        return pesapal.tenantAccountsEnabled();
    }

    /**
     * True when the {@code gateway} payment method posts to an active ledger account. Asked in a transaction of its own:
     * the ledger refuses an unmapped method with an exception, which would otherwise mark the caller's transaction
     * rollback-only.
     */
    boolean gatewayMethodMapped() {
        try {
            isolated.executeWithoutResult(status -> methods.accountRef("gateway", null));
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> normalise(Map<String, Object> current, Map<String, Object> proposed) {
        List<FieldProblem> problems = new ArrayList<>();
        proposed.keySet().stream()
                .filter(k -> !KEYS.contains(k))
                .forEach(k -> problems.add(new FieldProblem(k, "unknown", "Not a payments setting.")));
        boolean wasOn = Boolean.TRUE.equals(current.get(ENABLED));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ENABLED, wasOn);
        if (current.get(CEILING) instanceof Number n) {
            out.put(CEILING, n.longValue());
        }
        if (proposed.containsKey(ENABLED) && !(proposed.get(ENABLED) instanceof Boolean)) {
            problems.add(new FieldProblem(ENABLED, "invalid", "Use true or false."));
        }
        if (proposed.containsKey(CEILING) && proposed.get(CEILING) != null) {
            Object v = proposed.get(CEILING);
            if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < 1) {
                problems.add(new FieldProblem(CEILING, "invalid", "Use a whole number of 1 or more, or clear it."));
            } else {
                out.put(CEILING, n.longValue());
            }
        } else if (proposed.containsKey(CEILING)) {
            out.remove(CEILING);
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        boolean switchingOn = Boolean.TRUE.equals(proposed.get(ENABLED)) && !wasOn;
        if (switchingOn) {
            if (!gatewayAvailable()) {
                throw conflict("gateway_not_configured", "The payment provider is not configured on this host.");
            }
            if (!merchantAccountVerified()) {
                throw conflict(
                        "merchant_account_not_verified",
                        "Connect and test your own payment account (Receive payments) before switching collections on.");
            }
            if (!gatewayMethodMapped()) {
                throw conflict(
                        "gateway_method_unmapped",
                        "Map the gateway payment method to a ledger account before switching collections on.");
            }
        }
        // The END state decides: collections on with no daily limit is never stored, whichever way it came about
        // (switching on, or clearing the limit of a tenant that is already on, alone or in the same request).
        boolean endsOn = proposed.containsKey(ENABLED) ? Boolean.TRUE.equals(proposed.get(ENABLED)) : wasOn;
        if (endsOn && !out.containsKey(CEILING)) {
            throw conflict(
                    "daily_ceiling_required",
                    "Collections need a daily limit: it bounds what one day can ask for, and it cannot be removed while they are on.");
        }
        if (proposed.containsKey(ENABLED)) {
            out.put(ENABLED, proposed.get(ENABLED));
        }
        return out;
    }

    @Override
    public List<String> enabledCapabilities(Map<String, Object> normalised) {
        return List.of();
    }

    private static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "Collections cannot be switched on", detail);
    }
}
