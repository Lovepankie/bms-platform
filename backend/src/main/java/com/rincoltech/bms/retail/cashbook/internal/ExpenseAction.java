package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code retail_expense} (ADR-091): an expense paid from the till whose amount reaches the business's amount, while expenses ask
 * for approval on the approval rules (off by default). Requested with {@code retail.expense.record}; decided by an administrator
 * ({@code core.settings.manage} in the branch). The subject is the expense still to be recorded, under the id it will have; the
 * payload is the request with its branch and business date fixed, checked again on approval ({@link ExpenseService}).
 */
@Component
class ExpenseAction implements ApprovalAction {

    static final String TYPE = "retail_expense";

    private final ExpenseService expenses;

    ExpenseAction(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "retail.expense";
    }

    @Override
    public String makerPermission() {
        return CashbookSupport.EXPENSE_RECORD;
    }

    @Override
    public String checkerPermission() {
        return "core.settings.manage";
    }

    @Override
    public boolean thresholdApplies() {
        return true;
    }

    @Override
    public boolean switchable() {
        return true;
    }

    @Override
    public boolean onByDefault(long thresholdMinor) {
        return false;
    }

    @Override
    public String label() {
        return "Expenses";
    }

    /** Nothing exists until it is approved, so there is no version to compare; everything is checked again on approval. */
    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return Optional.of(0);
    }

    @Override
    public void execute(Execution e) {
        expenses.recordApproved(e.subjectId(), e.payload(), e.makerId());
    }
}
