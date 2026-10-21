package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.Principal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * FR-DOC-03 for item photos (issue #384, ADR-053): whoever may read the catalogue ({@code retail.stock.read}, the permission of
 * the photo route itself, held by every seller and stock clerk) may open a photo of an item of their own tenant. Row-level
 * security already keeps another tenant's document out of reach, so the owning module's rule is the permission alone.
 */
@Component
class PhotoAccess implements DocumentAccess {

    static final String READ = "retail.stock.read";

    @Override
    public String subjectType() {
        return PhotoService.SUBJECT;
    }

    @Override
    public boolean canRead(Principal principal, UUID subjectId) {
        return principal.hasPermission(READ);
    }

    /** The same rule for the small copy of a photo, a document of its own whose subject is the full photo. */
    @Component
    static class Small implements DocumentAccess {

        @Override
        public String subjectType() {
            return PhotoService.SMALL_SUBJECT;
        }

        @Override
        public boolean canRead(Principal principal, UUID subjectId) {
            return principal.hasPermission(READ);
        }
    }
}
