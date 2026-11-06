package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code import_commit} (chapter 8 section 8.4, ADR-015, issue #133): a staff user with {@code core.imports.manage} requests
 * the commit of a ready import run, and a different user with {@code core.imports.approve_commit} approves it. No threshold:
 * every commit waits for a second person. The approval executes no batch (a batch commits in its own transaction, never in
 * the approval's): it marks the run approved at its version, and the commit then starts only while the run is still at that
 * version. The run's version is the subject's, so a run changed while the request waits makes it stale.
 */
@Component
class ImportCommitAction implements ApprovalAction {

    static final String TYPE = "import_commit";
    static final String SUBJECT = "core.import_run";

    private final ImportRunService runs;

    /** The service is lazy: it requests approvals, and the approvals hold every action, this one included. */
    ImportCommitAction(@Lazy ImportRunService runs) {
        this.runs = runs;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public String makerPermission() {
        return "core.imports.manage";
    }

    @Override
    public String checkerPermission() {
        return "core.imports.approve_commit";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public String label() {
        return "Import a business's data";
    }

    /** Not a row of the business's approval rules (ADR-091): an import always waits for a second person. */
    @Override
    public boolean listed() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return runs.version(subjectId);
    }

    @Override
    public void execute(Execution e) {
        runs.commitApproved(e.subjectId(), e.approvalId(), e.makerId(), e.checkerId());
    }
}
