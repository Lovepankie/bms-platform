package com.rincoltech.bms.core.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Branch lookups for the bound tenant (FR-BR-01). Row-level security scopes every call. */
public interface Branches {

    Optional<Branch> findActive(UUID branchId);

    /** Every branch of the tenant, active and inactive, head office first. */
    List<Branch> all();

    record Branch(UUID id, String code, String name, boolean headOffice, boolean active) {}
}
