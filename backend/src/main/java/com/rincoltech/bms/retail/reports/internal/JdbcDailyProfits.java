package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.retail.reports.DailyProfits;
import com.rincoltech.bms.retail.reports.internal.ReportsRepository.DayFigures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcDailyProfits implements DailyProfits {

    private final ReportsRepository repo;

    JdbcDailyProfits(ReportsRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public long profitMinor(UUID branchId, LocalDate date) {
        return profitByDay(branchId, date, date).getOrDefault(date, 0L);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<LocalDate, Long> profitByDay(UUID branchId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> out = new TreeMap<>();
        for (DayFigures f : repo.daily(List.of(branchId), from, to)) {
            out.merge(f.date(), f.sales() - f.costOfSales() - f.usageCost(), Long::sum);
        }
        return out;
    }
}
