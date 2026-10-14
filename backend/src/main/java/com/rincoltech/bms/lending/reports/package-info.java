/**
 * Lending: the formal reports of increment 8 (extras slice D1, issue #214; ADR-038; chapter 14 sections 14.3 and
 * 14.10). The lending registration with the report framework of {@code core.reporting}: each report is a
 * {@link com.rincoltech.bms.core.reporting.ReportDefinition} bean that reads its figures through the public
 * {@link com.rincoltech.bms.lending.insights.InsightsFigures} port, so there is one implementation of PAR and
 * the report equals the insights page. Today: {@code lending.par}. The collections, disbursements, officer
 * performance, collateral register and regulator summary arrive with slices D3 and D4.
 */
@ApplicationModule(
        id = "lending.reports",
        displayName = "Lending: Reports",
        allowedDependencies = {"kernel", "core.reporting", "lending.insights"})
package com.rincoltech.bms.lending.reports;

import org.springframework.modulith.ApplicationModule;
