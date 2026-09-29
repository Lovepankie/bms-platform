package com.rincoltech.bms.testsupport;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only maker endpoint for {@link TestApprovalAction}, the way a module's own endpoint calls
 * {@link Approvals#request}. Declares a money-moving permission so the denial audit of FR-AUD-03
 * can be tested. Hidden from the contract document.
 */
@Hidden
@RestController
public class TestActionController {

    private final Approvals approvals;
    private final TestApprovalAction action;

    TestActionController(Approvals approvals, TestApprovalAction action) {
        this.approvals = approvals;
        this.action = action;
    }

    public record TestActionRequest(UUID subjectId, UUID branchId, Long amountMinor, String note) {}

    @PostMapping("/api/v1/test-actions")
    @RequiresPermission("lending.disbursements.request")
    @Transactional
    public Approvals.Outcome request(@RequestBody TestActionRequest request) {
        action.versions.putIfAbsent(request.subjectId(), 1);
        return approvals.request(new Approvals.ActionRequest(
                TestApprovalAction.TYPE,
                request.branchId(),
                request.subjectId(),
                action.versions.get(request.subjectId()),
                request.amountMinor(),
                request.amountMinor() == null ? null : "UGX",
                Map.of("note", request.note() == null ? "" : request.note())));
    }
}
