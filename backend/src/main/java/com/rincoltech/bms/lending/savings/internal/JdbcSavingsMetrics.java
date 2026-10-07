package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.lending.savings.SavingsMetrics;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.BalanceRow;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.MovementRow;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link SavingsMetrics} from the savings rows, by value date, under row-level security. */
@Service
class JdbcSavingsMetrics implements SavingsMetrics {

    private final SavingsRepository repo;
    private final CurrentTenant currentTenant;

    JdbcSavingsMetrics(SavingsRepository repo, CurrentTenant currentTenant) {
        this.repo = repo;
        this.currentTenant = currentTenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<Metric> metrics(LocalDate from, LocalDate to, List<UUID> branchIds) {
        String c = currentTenant.profile().currency();
        long balances = repo.balancesAsAt(to, branchIds, null).stream()
                .mapToLong(BalanceRow::balanceMinor)
                .sum();
        List<MovementRow> rows = repo.movements(from, to, branchIds, null);
        long deposits = rows.stream().mapToLong(MovementRow::depositsMinor).sum();
        long withdrawals =
                rows.stream().mapToLong(MovementRow::withdrawalsMinor).sum();
        long interest = rows.stream().mapToLong(MovementRow::interestMinor).sum();
        long reversalsIn =
                rows.stream().mapToLong(MovementRow::reversalsInMinor).sum();
        long reversalsOut =
                rows.stream().mapToLong(MovementRow::reversalsOutMinor).sum();
        return List.of(
                new Metric(
                        "savings.balances",
                        "Savings held",
                        "money",
                        balances,
                        c,
                        "Sum of every savings account's balance at the end of the last day of the range."),
                new Metric(
                        "savings.inflows",
                        "Deposits",
                        "money",
                        deposits - reversalsOut,
                        c,
                        "Deposits dated in the range, less reversals of deposits dated in the range."),
                new Metric(
                        "savings.outflows",
                        "Withdrawals",
                        "money",
                        withdrawals - reversalsIn,
                        c,
                        "Withdrawals dated in the range, less reversals of withdrawals and fees dated in the range."),
                new Metric(
                        "savings.interest_paid",
                        "Interest paid",
                        "money",
                        interest,
                        c,
                        "Interest credited to savings accounts with a value date in the range."),
                new Metric(
                        "savings.dormant_accounts",
                        "Dormant accounts",
                        "count",
                        repo.dormantAccounts(branchIds),
                        null,
                        "Accounts dormant now: no member deposit or withdrawal for the product's dormancy days."));
    }
}
