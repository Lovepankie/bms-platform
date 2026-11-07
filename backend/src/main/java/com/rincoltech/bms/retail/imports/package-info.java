/**
 * Retail: the pilot import (FR-RET-12; ADR-020 decision 9; chapter 13 sections 13.13 and 13.14.1; chapter 6 section
 * 6.11.4) and the canonical import template {@code retail_v1} (issue #135, ADR-027 decision 1). The template's loader is
 * the code of the {@code import-retail} command, which reads a normalised export directory of JSON Lines files and writes
 * it through the retail modules' own history ports, as {@code bms_app} under the tenant's row-level security, each file a
 * batch of a run of {@code core.imports} in its own transaction. History posts no journals; one opening journal per branch
 * records the inventory at current cost, through the framework. Source references in {@code import_refs} make a re-run of
 * the same export add nothing.
 */
@ApplicationModule(
        id = "retail.imports",
        displayName = "Retail: Imports",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.imports",
            "core.jobs",
            "core.ledger",
            "retail.catalogue",
            "retail.stock",
            "retail.sales",
            "retail.purchasing",
            "retail.cashbook"
        })
package com.rincoltech.bms.retail.imports;

import org.springframework.modulith.ApplicationModule;
