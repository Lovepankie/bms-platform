package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.Principal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * This module's registration as the reader of {@code core.tenant} documents (the tenant logo,
 * FR-TEN-08): any staff user who may read settings may read them. The public logo route does not
 * go through here; it serves one document by design.
 */
@Component
class TenantDocumentAccess implements DocumentAccess {

    static final String SUBJECT = "core.tenant";

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public boolean canRead(Principal principal, UUID subjectId) {
        return "staff".equals(principal.kind()) && principal.hasPermission("core.settings.read");
    }
}
