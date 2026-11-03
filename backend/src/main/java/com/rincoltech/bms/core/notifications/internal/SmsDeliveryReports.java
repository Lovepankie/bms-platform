package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.internal.SmsProvider.DeliveryReport;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Date;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies SMS delivery reports to the delivery log (issue #215, ADR-036 decision 7, spec section 4.7) and counts what came
 * of each. A report names no business, so it is applied in a platform transaction through the definer function
 * {@code sms_delivery_report_apply} (V130), which finds the row by the provider's message id and moves its status forward
 * only. The counters are a word of ours per outcome and a number per Kampala day ({@code sms_report_counters}), never an id
 * or a number; each count commits on its own, so a refused or failed report is still counted.
 */
@Component
class SmsDeliveryReports {

    private final JdbcClient jdbc;
    private final BusinessClock clock;

    SmsDeliveryReports(JdbcClient jdbc, BusinessClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Applies one report and answers the outcome: {@code moved}, {@code repeated}, {@code not_forward},
     * {@code unknown_id} or {@code ambiguous_id} (V130 explains each). An interim {@code sent} report never moves a row.
     */
    @Transactional
    public String apply(String provider, DeliveryReport report) {
        return jdbc.sql("SELECT sms_delivery_report_apply(?, ?, ?, ?, ?)")
                .params(provider, report.messageId(), report.status(), report.reason(), Timestamp.from(clock.now()))
                .query(String.class)
                .single();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void count(String outcome) {
        jdbc.sql("SELECT app_count_sms_report(?, ?)")
                .params(Date.valueOf(clock.today(BusinessClock.DEFAULT_ZONE)), outcome)
                .query((rs, n) -> 0)
                .list();
    }
}
