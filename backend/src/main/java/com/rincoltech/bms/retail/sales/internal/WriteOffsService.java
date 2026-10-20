package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOff;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOffOutcome;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOffRequest;
import com.rincoltech.bms.retail.sales.internal.WriteOffsRepository.Admin;
import com.rincoltech.bms.retail.sales.internal.WriteOffsServicer.Alone;
import com.rincoltech.bms.retail.sales.internal.WriteOffsServicer.Plan;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The route of a write-off (ADR-043, issue #340). A positive tenant threshold for {@code retail_sale_write_off} decides: a debt at or
 * above it waits for a second holder of {@code retail.credit.write_off} (maker-checker, ADR-015), a smaller one is done at once. With no
 * positive threshold the default rule applies, awaiting Hillary's confirmation: a second administrator is needed when the tenant has
 * two or more who count, and a single one may write off alone. Whenever a write-off is done without a second person the audit row
 * says so and the administrators are told, whatever the threshold is.
 */
@Service
class WriteOffsService {

    static final String PATH = "/api/v1/retail/sales/";

    /** How long an administrator who left, or was removed, still counts for the second-person rule (ADR-043). */
    static final java.time.Duration ADMIN_MEMORY = java.time.Duration.ofDays(30);

    private final WriteOffsServicer servicer;
    private final Idempotency idempotency;
    private final Approvals approvals;
    private final TenantSettings settings;
    private final WriteOffsRepository repo;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    WriteOffsService(
            WriteOffsServicer servicer,
            Idempotency idempotency,
            Approvals approvals,
            TenantSettings settings,
            WriteOffsRepository repo,
            Outbox outbox,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.outbox = outbox;
        this.tenant = tenant;
        this.clock = clock;
        this.repo = repo;
        this.servicer = servicer;
        this.idempotency = idempotency;
        this.approvals = approvals;
        this.settings = settings;
    }

    @Transactional
    Outcome<WriteOffOutcome> create(String key, UUID saleId, WriteOffRequest r) {
        return idempotency.once(
                key, "POST", PATH + saleId + "/write-off", r, WriteOffOutcome.class, () -> writeOffOrAsk(saleId, r));
    }

    private WriteOffOutcome writeOffOrAsk(UUID saleId, WriteOffRequest r) {
        if (r.reason().trim().length() < WriteOffsServicer.MIN_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "reason",
                    "invalid",
                    "Give a reason of at least " + WriteOffsServicer.MIN_REASON + " characters.")));
        }
        Plan plan = servicer.plan(saleId);
        long threshold = settings.approvalThresholdMinor(SaleWriteOffAction.TYPE);
        // The default rule (awaiting Hillary's confirmation): with no threshold set a write-off needs a second
        // administrator when
        // two or more count; with one it is allowed alone. A positive threshold replaces the default: only a debt at or
        // above it
        // needs a second person. The administrators who count are read in both cases: a write-off that goes through
        // without a
        // second person, whatever the reason, is flagged in the audit row and told to the others (ADR-043), so raising
        // the
        // threshold first cannot make a write-off silent.
        List<Admin> admins = repo.recentAdmins(clock.now().minus(ADMIN_MEMORY));
        boolean ask = threshold > 0 ? plan.unpaidMinor() >= threshold : admins.size() >= 2;
        if (ask) {
            Approvals.Outcome o = approvals.request(new ActionRequest(
                    SaleWriteOffAction.TYPE,
                    plan.sale().branchId(),
                    saleId,
                    plan.sale().version(),
                    plan.unpaidMinor(),
                    plan.sale().currency(),
                    Map.of(SaleWriteOffAction.REASON, r.reason().trim())));
            return new WriteOffOutcome(false, o.approvalId(), null);
        }
        // Done without a second person. The others who count are told; when the maker is the only one it is a notice to
        // themselves
        // and the audit row (ADR-043: the tenant record has no contact email to add).
        UUID maker = CurrentPrincipal.require().userId();
        // Removed or deactivated administrators within 30 days count for the second-person rule but cannot read an
        // email, so only
        // active ones are told; the maker is told only when no other active administrator can be.
        List<Admin> activeOthers = admins.stream()
                .filter(a -> a.active() && a.email() != null && !a.userId().equals(maker))
                .toList();
        List<Admin> told = !activeOthers.isEmpty()
                ? activeOthers
                : admins.stream().filter(a -> a.active() && a.email() != null).toList();
        String why = threshold > 0 && admins.size() >= 2 ? "below_threshold" : "only_one_active_administrator";
        WriteOff written = servicer.record(plan, r.reason(), maker, null, null, new Alone(why, told.size()));
        String business = tenant.profile().name();
        for (Admin a : told) {
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    a.email(),
                    "retail.write_off_alone",
                    Map.of("business_name", business),
                    "retail.write_off_alone:" + written.id() + ":" + a.userId()));
        }
        return new WriteOffOutcome(true, null, written);
    }
}
