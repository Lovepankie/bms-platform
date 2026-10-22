package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import org.springframework.http.HttpStatus;

/**
 * The statements, the reconciliation run and the review of a statement line are the business's as a whole: a statement names
 * orders of every branch, fees of every branch are posted by it, and the run reads every order. They need the permission in
 * every branch; a branch-scoped holder is refused rather than shown or allowed a part.
 */
final class PaymentsScope {

    private PaymentsScope() {}

    static Principal requireEverywhere(String permission) {
        Principal principal = CurrentPrincipal.require();
        if (principal.branchFilter(permission, null) != null) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "This is done for the whole business, so it needs the permission in every branch.");
        }
        return principal;
    }
}
