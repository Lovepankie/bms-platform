package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code statement_fee} (ADR-035 decision 65): a provider fee on a statement line above the automatic rate of the line's
 * amount ({@code bms.payments.statement-fee-auto-max-bps}, 5% by default) is posted to gateway charges only when a second
 * person approves. The uploader is the maker and never decides it; the checker holds {@code core.journals.approve}, the
 * permission that approves any other journal, in the branch of the order. Approving posts the fee once, refused if the order's
 * fee was posted meanwhile by another line.
 */
@Component
class StatementFeeAction implements ApprovalAction {

    static final String TYPE = "statement_fee";
    static final String STATEMENT_ID = "statement_id";
    static final String INTENT_ID = "intent_id";
    static final String FEE = "fee_minor";

    private final JdbcClient jdbc;
    private final StatementService statements;

    StatementFeeAction(JdbcClient jdbc, @Lazy StatementService statements) {
        this.jdbc = jdbc;
        this.statements = statements;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "core.payment_statement_row";
    }

    @Override
    public String makerPermission() {
        return StatementService.READ;
    }

    @Override
    public String checkerPermission() {
        return "core.journals.approve";
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    /** A statement line never changes but for its fee entry: version 0 while it exists. */
    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return jdbc.sql("SELECT 0 FROM payment_statement_rows WHERE id = ?")
                .param(subjectId)
                .query(Integer.class)
                .optional();
    }

    @Override
    public void execute(Execution execution) {
        statements.postHeldFee(execution);
    }
}
