package com.rincoltech.bms.core.notifications;

import java.util.Optional;
import java.util.UUID;

/**
 * A file an outbox email carries (ADR-074: the weekly copy of the business's workbook). The outbox row names the file in its
 * parameter {@link #PARAM} as {@code <kind>:<id>}, never the bytes; when the email is sent, the module that owns the kind reads
 * it for the row's tenant. The notifications module never names that module (chapter 5 section 5.4.3): the owner exposes a
 * bean of this type.
 */
public interface EmailAttachments {

    /** The outbox parameter that names the attached file. */
    String PARAM = "attachment";

    /** The kind of reference this source reads, the part before the colon. */
    String kind();

    /**
     * The file, read for {@code tenantId} (the tenant stored on the outbox row), or empty when it is gone (expired, deleted):
     * the email is then not sent with a missing file.
     */
    Optional<Attachment> read(UUID tenantId, String id);

    /** @param fileName what the reader's mail program shows and saves it as */
    record Attachment(String fileName, String contentType, byte[] bytes) {}
}
