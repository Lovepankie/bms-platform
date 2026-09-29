package com.rincoltech.bms.testsupport;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * The test action of the increment 1 acceptance scenario: a fabricated action type registered only
 * in tests, requested by a cashier ({@code lending.disbursements.request}) and decided by a branch
 * manager ({@code lending.disbursements.authorise}), like a disbursement but with no effect other
 * than being recorded here. Subjects are fabricated ids with a version this class holds.
 */
@Component
public class TestApprovalAction implements ApprovalAction {

    public static final String TYPE = "test_action";

    public final List<Execution> executions = new CopyOnWriteArrayList<>();
    public final Map<UUID, Integer> versions = new ConcurrentHashMap<>();
    public final Set<UUID> failing = ConcurrentHashMap.newKeySet();
    public final Map<UUID, Set<UUID>> conflicts = new ConcurrentHashMap<>();

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "test.subject";
    }

    @Override
    public String makerPermission() {
        return "lending.disbursements.request";
    }

    @Override
    public String checkerPermission() {
        return "lending.disbursements.authorise";
    }

    @Override
    public boolean thresholdApplies() {
        return true;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return Optional.ofNullable(versions.get(subjectId));
    }

    @Override
    public Set<UUID> conflictedDeciders(UUID subjectId, Map<String, Object> payload) {
        return conflicts.getOrDefault(subjectId, Set.of());
    }

    @Override
    public void execute(Execution execution) {
        if (failing.contains(execution.subjectId())) {
            throw new IllegalStateException("the fabricated subject refuses to execute");
        }
        executions.add(execution);
    }

    public long executionsOf(UUID subjectId) {
        return executions.stream().filter(e -> e.subjectId().equals(subjectId)).count();
    }
}
