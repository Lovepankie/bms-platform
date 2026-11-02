/**
 * Lending: the formal reports of increment 8 (extras slice D1, issue #214; ADR-038; chapter 14 sections 14.3 and
 * 14.10). The lending registration with the report framework of {@code core.reporting}: each report is a
 * {@link com.rincoltech.bms.core.reporting.ReportDefinition} bean that reads its figures through the public
 * {@link com.rincoltech.bms.lending.insights.InsightsFigures} port, so there is one implementation of PAR and
 * the report equals the insights page. Today: {@code lending.par} and {@code lending.portfolio_outstanding} (D1, D2),
 * {@code lending.expected_vs_collected}, {@code lending.arrears_list}, {@code lending.disbursements} and
 * {@code lending.officer_performance} (D3, issue #222), and {@code lending.collateral_register} and
 * {@code lending.regulatory_summary} (D4, issue #225), which read through the public
 * {@link com.rincoltech.bms.lending.insights.RegisterFigures} port.
 */
@ApplicationModule(
        id = "lending.reports",
        displayName = "Lending: Reports",
        allowedDependencies = {"kernel", "core.reporting", "lending.insights"})
package com.rincoltech.bms.lending.reports;

import org.springframework.modulith.ApplicationModule;
