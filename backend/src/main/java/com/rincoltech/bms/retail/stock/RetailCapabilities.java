package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailPricing;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Reads the tenant's retail capabilities (ADR-033, docs/specs/trade-capabilities.md section 1).
 * The settings group itself is supplied by {@code retail.manifest} and stored by the core; this is
 * the one place the rest of retail asks whether a capability is on. Off is the default and means
 * behaviour exactly as before the capability existed.
 */
@Component
public class RetailCapabilities {

    public static final String BRANCH_ROLES = "branch_roles";
    public static final String UNITS = RetailCatalogue.UNITS_CAPABILITY;
    public static final String PRICE_LISTS = RetailPricing.PRICE_LISTS_CAPABILITY;
    public static final String CUSTOMER_ACCOUNTS = "customer_accounts";
    public static final String TRANSFER_RECEIPT = "transfer_receipt";
    public static final String DOCUMENTS = "documents";
    public static final String RETURNS = "returns";
    public static final String PURCHASING = "purchasing";
    /** The module key of the settings group. */
    public static final String MODULE = "retail";

    /** Credit sales count when they are made: the behaviour before ADR-043 and the default. */
    public static final String ON_SALE = "on_sale";

    /** Credit sales count as sales, cash and profit only when the buyer pays (ADR-043). */
    public static final String ON_PAYMENT = "on_payment";

    public static final String CREDIT_RECOGNITION = "credit_sale_recognition";

    private final ModuleSettings settings;
    private final CurrentTenant tenant;

    RetailCapabilities(ModuleSettings settings, CurrentTenant tenant) {
        this.settings = settings;
        this.tenant = tenant;
    }

    public boolean enabled(String capability) {
        return Boolean.TRUE.equals(settings.group(MODULE).get(capability));
    }

    /**
     * As {@link #enabled}, read under a shared lock on the settings row until the caller's
     * transaction ends, so the capability cannot be switched off before what the caller writes on
     * the strength of it has committed (issue #239). Needs a read-write transaction.
     */
    public boolean enabledHeld(String capability) {
        return Boolean.TRUE.equals(settings.groupHeld(MODULE).get(capability));
    }

    /** The tenant's credit sale recognition, {@link #ON_SALE} unless the owner chose {@link #ON_PAYMENT} (ADR-043). */
    public String creditRecognition() {
        // on_payment with no start day is no rule at all (the group never stores that, and a hand-edited row is read as
        // on_sale).
        Map<String, Object> g = settings.group(MODULE);
        return ON_PAYMENT.equals(g.get(CREDIT_RECOGNITION))
                        && g.get("credit_recognition_effective_from") instanceof String
                ? ON_PAYMENT
                : ON_SALE;
    }

    /**
     * The credit sales counted when the buyer pays (ADR-043): those whose counting day (the later of the date and the entry day) is on or after the first business day of the rule. Empty for a tenant that
     * never chose it, so every report reads as it always did.
     */
    public CreditPeriods onPaymentPeriods() {
        Map<String, Object> g = settings.group(MODULE);
        if (ON_PAYMENT.equals(g.get(CREDIT_RECOGNITION))
                && g.get("credit_recognition_effective_from") instanceof String d) {
            return new CreditPeriods(LocalDate.parse(d), tenant.profile().timezone());
        }
        return CreditPeriods.NONE;
    }

    /** The rule in force on {@code day}: {@link #ON_PAYMENT} once its first day has come, else {@link #ON_SALE}. */
    public String creditRecognitionOn(LocalDate day) {
        return onPaymentPeriods().contains(day) ? ON_PAYMENT : ON_SALE;
    }

    /** The day the current credit rule took effect, when it was ever changed (ADR-043). */
    public LocalDate creditRecognitionEffectiveFrom() {
        return settings.group(MODULE).get("credit_recognition_effective_from") instanceof String d
                ? LocalDate.parse(d)
                : null;
    }

    /** True when credit sales are reported as received money only (ADR-043). */
    public boolean recognisesOnPayment() {
        return ON_PAYMENT.equals(creditRecognition());
    }

    /** Months of stock at the recent pace above which an item is a slow mover (issue #414); 4 unless the business chose. */
    public int slowMoverMonths() {
        return settings.group(MODULE).get("slow_mover_months") instanceof Number n ? n.intValue() : 4;
    }

    /** Units sold in 90 days below which an item is a slow mover (issue #414); 0, the default, means the rule is off. */
    public int slowMoverMinSold() {
        return settings.group(MODULE).get("slow_mover_min_sold") instanceof Number n ? n.intValue() : 0;
    }

    /** 404 {@code capability_not_enabled} for a route of a capability that is off. */
    public void require(String capability) {
        if (!enabled(capability)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "capability_not_enabled",
                    "Capability not enabled",
                    "This capability is not enabled for the tenant.");
        }
    }

    /** The whole group with defaults applied, for the settings route. */
    public Map<String, Object> group() {
        return settings.group(MODULE);
    }

    /** Applies a partial change to the group; validated by the group and audited by the core. */
    public Map<String, Object> change(Map<String, Object> proposed, int expectedVersion) {
        return settings.change(MODULE, proposed, expectedVersion);
    }

    /** The settings version, the ETag of the group. */
    public int version() {
        return settings.version();
    }
}
