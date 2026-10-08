package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.insights.InsightsSnapshots;
import java.sql.Date;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly snapshot (FR-ARR-02), written here until the arrears job of increment 6 (#109)
 * takes the table over. Each run fills every business date after the last one written, up to
 * yesterday, starting from the first disbursement, at most {@link #MAX_DAYS_PER_RUN} days a run,
 * and always rewrites yesterday so a late repayment dated yesterday is reflected.
 */
@Service
class SnapshotService implements InsightsSnapshots {

    static final int MAX_DAYS_PER_RUN = 400;

    private final Positions positions;
    private final JdbcClient jdbc;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    SnapshotService(Positions positions, JdbcClient jdbc, CurrentTenant tenant, BusinessClock clock) {
        this.positions = positions;
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long backfill(LocalDate from, LocalDate to) {
        long rows = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            rows += positions.writeSnapshot(d);
        }
        return rows;
    }

    /** The nightly run for the bound tenant; returns the number of dates written. */
    @Transactional(propagation = Propagation.MANDATORY)
    int nightly() {
        LocalDate yesterday = clock.today(tenant.profile().timezone()).minusDays(1);
        Date last = jdbc.sql("SELECT max(business_date) FROM lending_loan_daily_snapshots")
                .query(Date.class)
                .single();
        LocalDate start;
        if (last != null) {
            start = last.toLocalDate().isBefore(yesterday) ? last.toLocalDate().plusDays(1) : yesterday;
        } else {
            Date first = jdbc.sql("SELECT min(disbursed_on) FROM lending_loans")
                    .query(Date.class)
                    .single();
            if (first == null) {
                return 0;
            }
            start = first.toLocalDate();
        }
        if (start.isAfter(yesterday)) {
            return 0;
        }
        LocalDate end = start.plusDays(MAX_DAYS_PER_RUN - 1L).isBefore(yesterday)
                ? start.plusDays(MAX_DAYS_PER_RUN - 1L)
                : yesterday;
        backfill(start, end);
        return (int) (end.toEpochDay() - start.toEpochDay() + 1);
    }
}
