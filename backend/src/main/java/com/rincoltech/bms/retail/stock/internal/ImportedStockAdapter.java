package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.catalogue.ImportedStock;
import com.rincoltech.bms.retail.stock.EntryDates;
import com.rincoltech.bms.retail.stock.OfflineEntry;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stock's side of Import items (issue #870, ADR-101 amendment of 6 Nov 2026): the branch is checked as Quick add checks it
 * ({@code retail.stocktake.commit} there, a place that keeps goods), a list kept on the phone is dated as every entry kept
 * offline is (ADR-067 decision 13), and each quantity goes in through Quick add's own opening stock.
 */
@Component
class ImportedStockAdapter implements ImportedStock {

    static final String ENTITY = "retail.product_import";

    private final QuickAddService quickAdd;
    private final RetailBranchContext branches;
    private final EntryDates entryDates;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    ImportedStockAdapter(
            QuickAddService quickAdd,
            RetailBranchContext branches,
            EntryDates entryDates,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.quickAdd = quickAdd;
        this.branches = branches;
        this.entryDates = entryDates;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Override
    public Opening check(UUID branchId, Instant occurredAt, Instant onlineAsOf) {
        UUID branch = branches.resolveStocking(QuickAddService.COUNT, branchId);
        OfflineEntry offline = occurredAt == null ? null : new OfflineEntry(occurredAt, onlineAsOf);
        LocalDate day = OfflineEntry.day(
                offline, entryDates.today(), clock.now(), tenant.profile().timezone(), List.of(branch));
        return new Opening(branch, day);
    }

    @Override
    public void add(Opening at, UUID productId, BigDecimal qty, UUID importId) {
        quickAdd.addOpening(at.branchId(), productId, qty, at.day(), importId);
    }

    @Override
    public void recordedOffline(Opening at, Instant occurredAt, Instant onlineAsOf, UUID importId) {
        if (occurredAt != null) {
            entryDates.auditOffline(
                    new OfflineEntry(occurredAt, onlineAsOf), at.day(), ENTITY, importId, at.branchId());
        }
    }
}
