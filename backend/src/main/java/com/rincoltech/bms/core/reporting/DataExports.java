package com.rincoltech.bms.core.reporting;

import java.util.UUID;

/**
 * Asking for the business's full "Download my data" workbook from outside the reporting module (ADR-074): the Owner's request
 * to close the business makes one for the Owner (issue #571, ADR-090). In the caller's transaction, bound to the business.
 */
public interface DataExports {

    /**
     * Queues the full workbook (personal columns included) for {@code requester}, who alone may fetch it, as the Download my
     * data card does; an identical one of theirs still queued or running is returned instead. Returns the run's id.
     */
    UUID requestFullCopy(UUID requester);
}
