package com.rincoltech.bms.lending.insights;

import java.time.LocalDate;

/**
 * Writes the per-loan daily snapshots (chapter 6 {@code lending_loan_daily_snapshots}, FR-ARR-02)
 * for callers outside the nightly job: the fabricated seed, which backdates a year of loans and
 * then fills the year's snapshots. Runs in the caller's transaction, with the tenant bound.
 */
public interface InsightsSnapshots {

    /** Writes every business date from {@code from} to {@code to} inclusive; returns the rows written. */
    long backfill(LocalDate from, LocalDate to);
}
