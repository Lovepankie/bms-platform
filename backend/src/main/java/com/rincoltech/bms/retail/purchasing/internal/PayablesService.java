package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.PayableRow;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.Payables;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.Statement;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.StatementLine;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsRepository.Open;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supplier balance and ageing report (ADR-033, issue #198; trade-capabilities.md section 8; open without the purchasing
 * capability since issue #557, ADR-084, as What I owe suppliers under the Money tab):
 * per supplier, what is owed on invoices that stand and credit restocks, by days past the due date as of today in the tenant's
 * business time zone (the buckets of the customer ageing), and the goods received and not yet invoiced beside it. Read only,
 * in the caller's branch scope: an invoice counts for its branch, a restock for the branches its stock went into and
 * only when all of them are in scope, a receipt for the branch it went into.
 */
@Service
class PayablesService {

    static final int STATEMENT_DEFAULT_DAYS = 90;
    static final int STATEMENT_MAX_DAYS = 731;
    static final int STATEMENT_MAX_LINES = 2000;

    private final PaymentsRepository repo;
    private final PurchasingRepository suppliers;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    PayablesService(
            PaymentsRepository repo, PurchasingRepository suppliers, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.suppliers = suppliers;
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

    /**
     * A supplier's statement (issue #557): what was owed at the start of {@code from}, each purchase on credit, invoice,
     * credit note, payment, void and reversal up to the end of {@code to} with the balance after it, and what is owed at
     * the end. In a limited branch scope an item counts only when all its branches are in scope (as on the payables
     * report), and a payment by what it paid on those items, so the closing balance agrees with what is still to pay.
     */
    @Transactional(readOnly = true)
    Statement statement(UUID supplierId, LocalDate fromParam, LocalDate toParam) {
        var supplier = suppliers.supplier(supplierId).orElseThrow(ApiException::notFound);
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate to = toParam == null ? today : toParam;
        LocalDate from = fromParam == null ? to.minusDays(STATEMENT_DEFAULT_DAYS - 1L) : fromParam;
        if (from.isAfter(to) || from.plusDays(STATEMENT_MAX_DAYS).isBefore(to)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "from",
                    "invalid",
                    "from must be on or before to, at most " + STATEMENT_MAX_DAYS + " days apart.")));
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter(OrdersService.PAYABLE, null);
        Set<UUID> allowed = filter == null ? null : new HashSet<>(filter);
        Map<UUID, Long> paidInScope = new HashMap<>();
        if (allowed != null) {
            for (PaymentsRepository.Paid p : repo.paidBySupplier(supplierId)) {
                if (!p.itemBranchIds().isEmpty() && allowed.containsAll(p.itemBranchIds())) {
                    paidInScope.merge(p.paymentId(), p.amount(), Math::addExact);
                }
            }
        }
        long opening = 0;
        long running = 0;
        List<StatementLine> lines = new ArrayList<>();
        for (PaymentsRepository.Event e : repo.statementEvents(supplierId, to)) {
            long amount = e.amount();
            if (allowed != null) {
                if (e.kind().startsWith("payment")) {
                    long part = paidInScope.getOrDefault(e.sourceId(), 0L);
                    amount = e.amount() < 0 ? -part : part;
                } else if (e.branchIds().isEmpty() || !allowed.containsAll(e.branchIds())) {
                    amount = 0;
                }
                if (amount == 0) {
                    continue;
                }
            }
            if (e.day().isBefore(from)) {
                opening = Math.addExact(opening, amount);
                running = opening;
                continue;
            }
            if (lines.size() == STATEMENT_MAX_LINES) {
                throw ApiException.rule(
                        "statement_too_long", "Choose a shorter range: more than " + STATEMENT_MAX_LINES + " lines.");
            }
            running = Math.addExact(running, amount);
            lines.add(new StatementLine(
                    e.day(),
                    e.kind(),
                    e.number(),
                    e.reference(),
                    e.sourceId(),
                    e.method(),
                    Math.max(amount, 0),
                    Math.max(-amount, 0),
                    running));
        }
        return new Statement(
                supplierId,
                supplier.name(),
                tenant.profile().currency(),
                from,
                to,
                opening,
                List.copyOf(lines),
                running);
    }
}
