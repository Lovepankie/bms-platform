package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code collateral_release} (chapter 8 section 8.4; FR-COL-04): requested with
 * {@code lending.collateral.release_request}, decided with {@code lending.collateral.release_approve},
 * never below a threshold. On approval the item becomes {@code released} and the timeline records
 * who collected it. Depends only on the repository, so the approvals registry has no cycle.
 */
@Component
class CollateralReleaseAction implements ApprovalAction {

    static final String TYPE = "collateral_release";

    private final CollateralRepository repo;
    private final AuditLog audit;
    private final BusinessClock clock;

    CollateralReleaseAction(CollateralRepository repo, AuditLog audit, BusinessClock clock) {
        this.repo = repo;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return CollateralService.SUBJECT;
    }

    @Override
    public String makerPermission() {
        return "lending.collateral.release_request";
    }

    @Override
    public String checkerPermission() {
        return "lending.collateral.release_approve";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.find(subjectId).map(CollateralResponse::version);
    }

    /** Checked again here: the item may have been seized between request and approval. */
    @Override
    public void execute(Execution e) {
        CollateralResponse c = repo.lock(e.subjectId()).orElseThrow();
        if (!CollateralService.RELEASABLE.contains(c.custodyStatus())) {
            throw new IllegalStateException("An item that is " + c.custodyStatus() + " cannot be released.");
        }
        repo.update(CollateralService.withCustody(c, "released", null));
        String collectedBy = (String) e.payload().get("collected_by");
        String note = (String) e.payload().get("note");
        repo.insertEvent(
                c.id(),
                CollateralService.event(
                        "released",
                        c.custodyStatus(),
                        "released",
                        null,
                        collectedBy,
                        note,
                        clock.now(),
                        e.approvalId()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("custody_status", "released");
        after.put("collected_by", collectedBy);
        after.put("approval_request_id", e.approvalId());
        audit.record(new AuditLog.Entry(
                "lending.collateral.released",
                CollateralService.SUBJECT,
                c.id(),
                c.branchId(),
                Map.of("custody_status", c.custodyStatus()),
                after));
    }
}
