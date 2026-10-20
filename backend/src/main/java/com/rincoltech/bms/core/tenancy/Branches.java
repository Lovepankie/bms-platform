package com.rincoltech.bms.core.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Branch lookups for the bound tenant (FR-BR-01). Row-level security scopes every call. */
public interface Branches {

    Optional<Branch> findActive(UUID branchId);

    /**
     * The branch whatever its status, read {@code FOR SHARE} in the caller's write transaction (issue #329, ADR-041): a close or a
     * not-stocking change holds the row {@code FOR UPDATE}, so a write that checks the branch here queues behind it and sees the
     * result, and an admin change waits for a write already past its check. Needs a transaction that is not read only.
     */
    Optional<Branch> lockBranch(UUID branchId);

    /** Every branch of the tenant, active and inactive, head office first. */
    List<Branch> all();

    /**
     * @param active false for a closed branch (status {@code inactive}, ADR-041)
     * @param stocksGoods false for a branch that keeps no goods, such as a head office that only keeps accounts: the
     *     retail module refuses sales, restocks, transfers and stock adjustments there
     * @param sellsGoods false for a Store, a branch that holds stock and takes transfers and purchases but does not sell: the
     *     retail module refuses a sale, a payment taken and a refund paid there with 422 {@code branch_does_not_sell} (issue
     *     #366, ADR-048)
     */
    record Branch(
            UUID id,
            String code,
            String name,
            boolean headOffice,
            boolean active,
            boolean stocksGoods,
            boolean sellsGoods) {}
}
