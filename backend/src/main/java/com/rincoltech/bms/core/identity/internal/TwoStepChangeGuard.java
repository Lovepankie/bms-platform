package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.AdminSecurity;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PolicyChangeGuard;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PolicyChangeGuard} over the staff accounts and the outbox (issue #348, ADR-046 decision 6). */
@Component
class TwoStepChangeGuard implements PolicyChangeGuard {

    static final String TEMPLATE = "identity.two_step_policy_weakened";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'");

    private final AdminSecurityService security;
    private final PasswordResets resets;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final Owners owners;

    TwoStepChangeGuard(
            AdminSecurityService security,
            PasswordResets resets,
            Outbox outbox,
            CurrentTenant tenant,
            BusinessClock clock,
            Owners owners) {
        this.owners = owners;
        this.security = security;
        this.resets = resets;
        this.outbox = outbox;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Override
    public void requireProof(String totpCode, String password) {
        if (resets.actorHasSecondFactor()) {
            if (totpCode == null || totpCode.isBlank()) {
                throw ApiException.rule("totp_required", "Enter the current code from your authenticator app.");
            }
            security.requireTotp(totpCode);
        } else {
            if (password == null || password.isEmpty()) {
                throw ApiException.rule("password_required", "Enter your own password to confirm.");
            }
            security.requirePassword(password);
        }
    }

    @Override
    public void requireOwner(String what) {
        owners.requireOwnerStrictly(CurrentPrincipal.require(), what);
    }

    @Override
    @Transactional
    public void notifyRelaxed(String change) {
        Instant now = clock.now();
        String name = security.actorName();
        for (AdminSecurity.Holder holder : security.holders("core.settings.manage")) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("business_name", tenant.profile().name());
            params.put("change", change);
            params.put("who", name == null || name.isBlank() ? "an administrator" : name);
            params.put("when", WHEN.format(now.atZone(ZoneOffset.UTC)));
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    holder.email(),
                    TEMPLATE,
                    params,
                    TEMPLATE + ":" + now.toEpochMilli() + ":" + holder.userId(),
                    null,
                    null));
        }
    }
}
