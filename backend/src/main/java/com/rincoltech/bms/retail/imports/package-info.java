/**
 * Retail: the pilot import (FR-RET-12; ADR-020 decision 9; chapter 13 section 13.8; chapter 6 table
 * {@code retail_import_refs}). The one-off {@code import-retail} command reads a normalised export
 * directory of JSON Lines files and writes it through the retail modules' own history ports, as
 * {@code bms_app} under the tenant's row-level security, one transaction per file. History posts no
 * journals; one opening journal per branch records the inventory at current cost. Source references
 * make a re-run of the same export add nothing.
 */
@ApplicationModule(
        id = "retail.imports",
        displayName = "Retail: Imports",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
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
