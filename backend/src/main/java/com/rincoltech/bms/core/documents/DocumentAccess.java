package com.rincoltech.bms.core.documents;

import com.rincoltech.bms.kernel.Principal;
import java.util.UUID;

/**
 * Registered by the module that owns a subject type (chapter 5 section 5.4.3): may this principal
 * read that subject, and so its documents (FR-DOC-03)? A document whose subject type has no
 * registration is not readable by anyone.
 */
public interface DocumentAccess {

    /** For example {@code lending.member}. */
    String subjectType();

    boolean canRead(Principal principal, UUID subjectId);
}
