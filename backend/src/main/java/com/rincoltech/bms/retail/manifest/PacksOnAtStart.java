package com.rincoltech.bms.retail.manifest;

import com.rincoltech.bms.core.tenancy.ModuleSetUp;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #630 (ADR-033 amendment, owner decision D64): a business that gets the retail module, when it is created with it or
 * when retail is switched on later, starts with packs ({@code units}) on, so a shop that buys by the carton finds them on the
 * item form. Only packs: every other switch stays off by default. The owner can switch packs off in Retail settings; a later
 * switch-on of retail turns them on again. Existing businesses keep the switch they have.
 */
@Component
class PacksOnAtStart implements ModuleSetUp {

    private final ModuleSettings settings;

    PacksOnAtStart(ModuleSettings settings) {
        this.settings = settings;
    }

    @Override
    public String moduleKey() {
        return RetailManifest.MODULE_KEY;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void switchedOn() {
        settings.change(RetailManifest.MODULE_KEY, Map.of("units", true), settings.version());
    }
}
