package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.kernel.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code statement_line_outside} (ADR-035 decision 66): a statement line that matches no order stays a finding until two people
 * agree it is money for something outside this system. The person who marks it is the maker and can never confirm it; a second
 * holder of {@code core.payments.manage} in every branch confirms it, and only then is the review recorded and the line left out
 * of the reconciliation. Until then it stays open and listed.
 */
@Component
class StatementLineOutsideAction implements ApprovalAction {

    static final String TYPE = "statement_line_outside";

    private final JdbcClient jdbc;
    private final StatementService statements;

    StatementLineOutsideAction(JdbcClient jdbc, @Lazy StatementService statements) {
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
        return StatementService.READ;
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return jdbc.sql("SELECT 0 FROM payment_statement_rows WHERE id = ?")
                .param(subjectId)
                .query(Integer.class)
                .optional();
    }

    /** The statement is the whole business's, so the checker needs the permission in every branch, as the maker did. */
    @Override
    public boolean mayDecide(Principal checker, UUID branchId, Map<String, Object> payload) {
        return checker.branchFilter(StatementService.READ, null) == null;
    }

    @Override
    public void execute(Execution execution) {
        statements.confirmOutside(execution);
    }
}
