/**
 * The retail vertical's registration with the core (ADR-001, ADR-020, chapter 5 section 5.4.3):
 * the module key. Its chart of accounts is seeded by {@code bms_seed_retail_chart} when the
 * platform switches the module on; permissions are seeded by migration.
 */
@ApplicationModule(
        id = "retail.manifest",
        displayName = "Retail: Manifest",
        allowedDependencies = {"kernel", "core.tenancy"})
package com.rincoltech.bms.retail.manifest;

import org.springframework.modulith.ApplicationModule;
