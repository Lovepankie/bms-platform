/**
 * General ledger (ADR-004, chapter 6 section 6.6). {@link com.rincoltech.bms.core.ledger.LedgerPosting}
 * is the single posting operation ({@code post_entry}); there is no other way to write a journal.
 */
@ApplicationModule(
        id = "core.ledger",
        displayName = "Core: General Ledger",
        allowedDependencies = {"kernel", "core.tenancy", "core.identity"})
package com.rincoltech.bms.core.ledger;

import org.springframework.modulith.ApplicationModule;
