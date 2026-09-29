/**
 * Maker-checker approvals (FR-APR-01 to FR-APR-08, chapter 8 section 8.4, ADR-015). A module
 * that owns an action needing a second person registers an
 * {@link com.rincoltech.bms.core.approvals.ApprovalAction} bean and calls
 * {@link com.rincoltech.bms.core.approvals.Approvals#request} from its own endpoint; this module
 * stores the pending request with a snapshot of the payload, shows it to the permitted checkers
 * in their branches, and executes exactly that payload, once, in the transaction of the approval.
 * The core never names a vertical's action: action types come from the registered beans.
 */
@ApplicationModule(
        id = "core.approvals",
        displayName = "Core: Approvals",
        allowedDependencies = {"kernel", "core.tenancy", "core.audit", "core.jobs"})
package com.rincoltech.bms.core.approvals;

import org.springframework.modulith.ApplicationModule;
