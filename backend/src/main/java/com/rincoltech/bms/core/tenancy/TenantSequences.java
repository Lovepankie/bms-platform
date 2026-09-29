package com.rincoltech.bms.core.tenancy;

/**
 * Gap-free per-tenant numbers (FR-DOC-04; chapter 6 table {@code tenant_sequences}). Must be
 * called inside the business transaction: a rolled back transaction gives its number back.
 */
public interface TenantSequences {

    long next(String sequenceKey);
}
