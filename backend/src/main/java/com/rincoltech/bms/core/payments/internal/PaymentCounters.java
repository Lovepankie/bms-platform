package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Date;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The day's counts the operator's alerts read (spec 9.2): notifications received, unknown references, anomalies in a status
 * answer, reversals, handler refusals. A word of ours per outcome and a number per Kampala day, in a platform table no
 * tenant can read ({@code app_count_payment_callback}); never an identifier of a payer or an order.
 *
 * <p>Each count commits on its own, so that it survives the rollback of the transaction that noticed the event.
 */
@Component
class PaymentCounters {

    private final JdbcClient jdbc;
    private final BusinessClock clock;

    PaymentCounters(JdbcClient jdbc, BusinessClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void count(String outcome) {
        LocalDate day = clock.today(BusinessClock.DEFAULT_ZONE);
        jdbc.sql("SELECT app_count_payment_callback(?, ?)")
                .params(Date.valueOf(day), outcome)
                .query((rs, n) -> 0)
                .list();
    }
}
