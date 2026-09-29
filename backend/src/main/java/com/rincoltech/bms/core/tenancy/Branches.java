package com.rincoltech.bms.core.tenancy;

import java.util.Optional;
import java.util.UUID;

/** Branch lookups for the bound tenant (FR-BR-01). Row-level security scopes every call. */
public interface Branches {

    Optional<Branch> findActive(UUID branchId);

    record Branch(UUID id, String code, String name, boolean headOffice) {}
}
