package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code retail_sale_void} (ADR-091): voiding a sale whose total reaches the business's amount, while sale voids ask for
 * approval on the approval rules (off by default). Requested with {@code retail.sale.void}; decided by an administrator
 * ({@code core.settings.manage} in the sale's branch), so one seller never approves another's void. {@link SalesService}
 * decides when to ask. The subject is the sale, whose version moves with every payment, return and void, so a request goes
 * stale when anything happened to the sale meanwhile (FR-APR-08); the void is checked again and runs in the maker's name.
 */
@Component
class SaleVoidAction implements ApprovalAction {

    static final String TYPE = "retail_sale_void";
    static final String REASON = "reason";

    private final SalesService sales;
    private final SalesRepository repo;

    SaleVoidAction(SalesService sales, SalesRepository repo) {
        this.sales = sales;
        this.repo = repo;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return SalesService.SALE;
    }

    @Override
    public String makerPermission() {
        return "retail.sale.void";
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
        return "Sale voids";
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.find(subjectId, false).map(h -> h.sale().version());
    }

    @Override
    public void execute(Execution e) {
        sales.voidApproved(e.subjectId(), (String) e.payload().get(REASON), e.makerId());
    }
}
