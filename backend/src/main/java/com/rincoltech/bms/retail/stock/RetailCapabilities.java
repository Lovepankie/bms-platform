package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
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
    public static final String TRANSFER_RECEIPT = "transfer_receipt";
    private static final String MODULE = "retail";

    private final ModuleSettings settings;

    RetailCapabilities(ModuleSettings settings) {
        this.settings = settings;
    }

    public boolean enabled(String capability) {
        return Boolean.TRUE.equals(settings.group(MODULE).get(capability));
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
