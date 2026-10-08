package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.insights.InsightsPanel;
import com.rincoltech.bms.lending.investments.InvestmentMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The investments panel (FR-INS-09, #152): balances, inflows, outflows and returns from
 * {@link InvestmentMetrics}, then the maturity ladder from today as one money figure per bucket
 * (principal plus the return still owed). Investments carry no responsible officer, so a request
 * narrowed to one officer shows no investment figures.
 */
@Component
@Order(2)
class InvestmentsPanel implements InsightsPanel {

    private static final Map<String, String> LADDER = Map.of(
            "overdue", "Matured, not yet paid",
            "0_7", "Maturing in 7 days",
            "8_30", "Maturing in 8 to 30 days",
            "31_90", "Maturing in 31 to 90 days");

    private final InvestmentMetrics investments;
    private final JdbcClient jdbc;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    InvestmentsPanel(InvestmentMetrics investments, JdbcClient jdbc, CurrentTenant tenant, BusinessClock clock) {
        this.investments = investments;
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Override
    public String key() {
        return InvestmentMetrics.KEY;
    }

    @Override
    public String title() {
        return "Investments";
    }

    @Override
    public boolean shown() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending_investments)")
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<Metric> metrics(Filter filter) {
        if (filter.officerId() != null) {
            return List.of();
        }
        String currency = tenant.profile().currency();
        List<Metric> out = new ArrayList<>();
        investments.metrics(filter.from(), filter.to(), filter.branchIds()).stream()
                .map(m -> new Metric(m.key(), m.label(), m.kind(), m.value(), m.currency(), m.definition()))
                .forEach(out::add);
        investments
                .maturityLadder(clock.today(tenant.profile().timezone()), filter.branchIds())
                .forEach(b -> out.add(new Metric(
                        InvestmentMetrics.KEY + ".maturing_" + b.bucket(),
                        LADDER.get(b.bucket()),
                        "money",
                        b.totalMinor(),
                        currency,
                        "Principal held plus the return still owed on the investments in this bucket of the"
                                + " maturity ladder, counted from today")));
        return out;
    }
}
