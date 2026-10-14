package com.rincoltech.bms.retail.stock;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * A rule another retail module attaches to moving stock out of a branch by a transfer (ADR-033, issue #199): purchasing
 * refuses to move out the units of a product that belong to a goods received note held as received and not costed, while
 * the tenant requires costing before stock moves on. Stock cannot depend on purchasing, so the rule is supplied here as a
 * port. Runs in the transfer's transaction, before anything is written, and throws an {@code ApiException} to refuse.
 */
public interface TransferOutGuard {

    /**
     * @param fromBranchId the branch the stock leaves
     * @param quantities the base units of each product on the transfer
     */
    void check(UUID fromBranchId, Map<UUID, BigDecimal> quantities);
}
