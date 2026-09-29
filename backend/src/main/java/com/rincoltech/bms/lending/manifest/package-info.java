/**
 * The lending vertical's registration with the core (ADR-001, chapter 5 section 5.4.3): the
 * module key today; permissions, default chart of accounts entries, posting rules, report
 * definitions, import templates and scheduled jobs as they are built.
 */
@ApplicationModule(
        id = "lending.manifest",
        displayName = "Lending: Manifest",
        allowedDependencies = {"kernel", "core.tenancy"})
package com.rincoltech.bms.lending.manifest;

import org.springframework.modulith.ApplicationModule;
