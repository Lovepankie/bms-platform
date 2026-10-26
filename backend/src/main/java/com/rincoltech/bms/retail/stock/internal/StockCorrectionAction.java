package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.stock.internal.StockApi.CorrectionRequest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code retail_stock_correction} (ADR-091): correcting the quantity of an item at a branch when the value of the difference at
 * the branch's average cost reaches the business's amount, while stock corrections ask for approval on the approval rules (off
 * by default). Requested with {@code retail.stocktake.commit}; decided by an administrator ({@code core.settings.manage} in the
 * branch). The subject is the item, so one correction of an item waits at a time; the payload carries the quantity the maker
 * saw, and the correction runs on approval only while the quantity is still that one ({@link StockCorrectionService}).
 */
@Component
class StockCorrectionAction implements ApprovalAction {

    static final String TYPE = "retail_stock_correction";
    /** The id the correction gets when it is made, fixed when it is asked for. */
    static final String CORRECTION_ID = "correction_id";

    private final StockCorrectionService corrections;
    private final RetailCatalogue catalogue;

    StockCorrectionAction(StockCorrectionService corrections, RetailCatalogue catalogue) {
        this.corrections = corrections;
        this.catalogue = catalogue;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return StockCorrectionService.SOURCE;
    }

    @Override
    public String makerPermission() {
        return StockCorrectionService.PERMISSION;
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
        return "Stock corrections";
    }

    /** The item has no version a correction moves; the quantity the maker saw is checked again on approval instead. */
    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return catalogue.find(subjectId).map(p -> 0);
    }

    @Override
    public void execute(Execution e) {
        Map<String, Object> p = e.payload();
        CorrectionRequest r = new CorrectionRequest(
                UUID.fromString((String) p.get("branch_id")),
                UUID.fromString((String) p.get("product_id")),
                new BigDecimal((String) p.get("new_qty")),
                new BigDecimal((String) p.get("expected_qty")),
                (String) p.get("reason_code"),
                (String) p.get("note"));
        corrections.correctApproved(UUID.fromString((String) p.get(CORRECTION_ID)), e.branchId(), r, e.makerId());
    }
}
