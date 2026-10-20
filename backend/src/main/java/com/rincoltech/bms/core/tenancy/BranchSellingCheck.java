package com.rincoltech.bms.core.tenancy;

import java.util.UUID;

/**
 * Lets a module that sells tell the tenancy module what still hangs on a branch's till before it is marked as a Store, a branch
 * that does not sell (issue #366, ADR-048). The core iterates the beans of this type and never names a vertical. Nothing here
 * changes anything; it only reports.
 */
public interface BranchSellingCheck {

    /** Credit sales made at the branch that still have a balance owing: money to be taken at a till that will stop selling. */
    int openCreditSales(UUID branchId);
}
