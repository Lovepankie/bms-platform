/**
 * Lending: loan products (FR-PRD; chapter 6 tables {@code lending_loan_products},
 * {@code lending_loan_product_versions}, {@code lending_loan_product_fees}; chapter 7 section
 * 7.11.12). A product's terms live in immutable versions: editing creates a new version and
 * existing loans keep theirs (FR-PRD-04). {@link com.rincoltech.bms.lending.products.ScheduleCalculator}
 * is the public home of the normative schedule rules of chapter 3 section 3.4.
 */
@ApplicationModule(
        id = "lending.products",
        displayName = "Lending: Loan Products",
        allowedDependencies = {"kernel", "core.tenancy", "core.audit"})
package com.rincoltech.bms.lending.products;

import org.springframework.modulith.ApplicationModule;
