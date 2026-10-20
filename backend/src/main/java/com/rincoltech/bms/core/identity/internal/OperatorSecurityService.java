package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.OperatorSecurity;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import org.springframework.stereotype.Component;

/** {@link OperatorSecurity} over the platform operators' accounts and the same single-use step-up as a tenant admin's. */
@Component
class OperatorSecurityService implements OperatorSecurity {

    private final AuthFlow flow;
    private final PlatformAccounts accounts;

    OperatorSecurityService(AuthFlow flow, PlatformAccounts accounts) {
        this.flow = flow;
        this.accounts = accounts;
    }

    @Override
    public void requireTotp(String code) {
        flow.confirmTotp(accounts, CurrentPrincipal.require().userId(), code);
    }
}
