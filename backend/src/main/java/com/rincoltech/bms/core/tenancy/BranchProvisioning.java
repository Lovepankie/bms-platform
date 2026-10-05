package com.rincoltech.bms.core.tenancy;

import java.util.UUID;

/**
 * Creates a branch of the bound tenant outside a request, for a one-off command such as the retail
 * import (FR-BR-01, FR-RET-12). The same rules as the branch API apply: the plan's branch limit,
 * a unique code of 2 to 10 capital letters or digits, and an audit row.
 */
public interface BranchProvisioning {

    /** @return the new branch's id */
    UUID create(String code, String name);
}
