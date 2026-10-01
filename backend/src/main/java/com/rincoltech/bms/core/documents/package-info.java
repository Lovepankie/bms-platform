/**
 * Core: documents (FR-DOC-02, FR-DOC-03; chapter 12 section 12.5). Stores uploaded and generated
 * files in object storage under {@code tenants/<tenant_id>/}, records them in {@code documents},
 * and issues short-lived signed download URLs after the owning module says the caller may read
 * the subject ({@link com.rincoltech.bms.core.documents.DocumentAccess}, a registry in the sense
 * of chapter 5 section 5.4.3: the core never names a vertical).
 */
@ApplicationModule(
        id = "core.documents",
        displayName = "Core: Documents",
        allowedDependencies = {"kernel", "core.audit"})
package com.rincoltech.bms.core.documents;

import org.springframework.modulith.ApplicationModule;
