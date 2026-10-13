package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.PayableRow;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.Payables;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsRepository.Open;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supplier balance and ageing report (purchasing capability; ADR-033, issue #198; trade-capabilities.md section 8):
 * per supplier, what is owed on invoices that stand and credit restocks, by days past the due date as of today in the tenant's
 * business time zone (the buckets of the customer ageing), and the goods received and not yet invoiced beside it. Read only,
 * in the caller's branch scope: an invoice counts for its branch, a restock for the branches its stock went into and
 * only when all of them are in scope, a receipt for the branch it went into.
 */
@Service
class PayablesService {

    private final PaymentsRepository repo;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    PayablesService(
            PaymentsRepository repo, RetailCapabilities capabilities, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
    }

    /** A supplier's running sums while the report is built: not due, 1-30, 31-60, 61-90, over 90, unbilled. */
    private static final class Sums {
        final UUID id;
        final String name;
        final long[] v = new long[6];

        Sums(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        long owed() {
            return v[0] + v[1] + v[2] + v[3] + v[4];
        }

        PayableRow row() {
            return new PayableRow(id, name, v[0], v[1], v[2], v[3], v[4], owed(), v[5]);
        }
    }

    @Transactional(readOnly = true)
    Payables report(List<UUID> branchIds, UUID supplierId) {
        capabilities.require(RetailCapabilities.PURCHASING);
        List<UUID> filter = CurrentPrincipal.require().branchFilter(OrdersService.PAYABLE, branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        Map<UUID, Sums> bySupplier = new LinkedHashMap<>();
        Sums all = new Sums(null, "All suppliers");
        Set<UUID> allowed = filter == null ? null : new HashSet<>(filter);
        if (allowed == null || !allowed.isEmpty()) {
            for (Open o : repo.open(supplierId)) {
                if (allowed != null && (o.branchIds().isEmpty() || !allowed.containsAll(o.branchIds()))) {
                    continue;
                }
                int days = PaymentsService.daysPastDue(o.dueDate(), today);
                int bucket = days <= 0 ? 0 : days <= 30 ? 1 : days <= 60 ? 2 : days <= 90 ? 3 : 4;
                bySupplier.computeIfAbsent(o.supplierId(), k -> new Sums(k, o.supplierName())).v[bucket] +=
                        o.openMinor();
                all.v[bucket] += o.openMinor();
            }
            for (PaymentsRepository.Unbilled u : repo.unbilledBySupplier(filter)) {
                if ((supplierId != null && !supplierId.equals(u.supplierId())) || u.amountMinor() == 0) {
                    continue;
                }
                bySupplier.computeIfAbsent(u.supplierId(), k -> new Sums(k, u.supplierName())).v[5] += u.amountMinor();
                all.v[5] += u.amountMinor();
            }
        }
        List<PayableRow> rows = bySupplier.values().stream()
                .sorted(Comparator.<Sums>comparingLong(Sums::owed)
                        .reversed()
                        .thenComparing(s -> s.name.toLowerCase())
                        .thenComparing(s -> s.id))
                .map(Sums::row)
                .toList();
        return new Payables(today, tenant.profile().currency(), all.row(), rows);
    }
}
