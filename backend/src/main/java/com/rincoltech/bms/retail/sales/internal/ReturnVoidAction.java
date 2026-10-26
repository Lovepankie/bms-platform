package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code retail_sale_return_void} (chapter 8 section 8.4; issue #201): voiding a customer return that needed a second
 * administrator to be made, or that is worth the tenant's {@code retail_sale_return} threshold by itself. It has no threshold
 * of its own (it reads that one), so it is always checked once requested. The subject is the return, whose version moves
 * only with its void; everything is checked again at execution (the goods must still be in stock).
 */
@Component
class ReturnVoidAction implements ApprovalAction {

    static final String TYPE = "retail_sale_return_void";
    static final String REASON = "reason";

    private final ReturnsServicer servicer;
    private final ReturnsRepository repo;

    ReturnVoidAction(ReturnsServicer servicer, ReturnsRepository repo) {
        this.servicer = servicer;
        this.repo = repo;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "retail.sale_return";
    }

    @Override
    public String makerPermission() {
        return ReturnsServicer.PERMISSION;
    }

    @Override
    public String checkerPermission() {
        return ReturnsServicer.PERMISSION;
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    /** ADR-091 decision 6: the void of a return follows the returns rule, so the stale policy of decision 11 covers it. */
    @Override
    public boolean governedByRules() {
        return true;
    }

    /** ADR-091: the void of a return follows the returns rule, so it is not a row of its own. */
    @Override
    public boolean listed() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.find(subjectId, false).map(ReturnsRepository.Row::version);
    }

    @Override
    public void execute(Execution e) {
        servicer.voidReturn(e.subjectId(), (String) e.payload().get(REASON));
    }
}
