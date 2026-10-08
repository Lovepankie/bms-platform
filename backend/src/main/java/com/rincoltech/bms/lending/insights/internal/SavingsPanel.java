package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.lending.insights.InsightsPanel;
import com.rincoltech.bms.lending.savings.SavingsMetrics;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The savings panel (FR-INS-09, #151): balances, deposits, withdrawals, interest paid and dormant
 * accounts, mapped one to one from {@link SavingsMetrics}. Savings accounts carry no responsible
 * officer, so a request narrowed to one officer shows no savings figures.
 */
@Component
@Order(1)
class SavingsPanel implements InsightsPanel {

    private final SavingsMetrics savings;
    private final JdbcClient jdbc;

    SavingsPanel(SavingsMetrics savings, JdbcClient jdbc) {
        this.savings = savings;
        this.jdbc = jdbc;
    }

    @Override
    public String key() {
        return SavingsMetrics.KEY;
    }

    @Override
    public String title() {
        return "Savings";
    }

    @Override
    public boolean shown() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending_savings_accounts)")
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<Metric> metrics(Filter filter) {
        if (filter.officerId() != null) {
            return List.of();
        }
        return savings.metrics(filter.from(), filter.to(), filter.branchIds()).stream()
                .map(m -> new Metric(m.key(), m.label(), m.kind(), m.value(), m.currency(), m.definition()))
                .toList();
    }
}
