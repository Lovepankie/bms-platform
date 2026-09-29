package com.rincoltech.bms.core.tenancy;

import java.time.ZoneId;
import java.util.UUID;

/** Profile of the bound tenant: its currency and timezone are defaults for new records. */
public interface CurrentTenant {

    Profile profile();

    record Profile(UUID id, String slug, String name, String currency, ZoneId timezone) {}
}
