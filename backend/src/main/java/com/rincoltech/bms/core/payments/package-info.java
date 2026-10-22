/**
 * Payment collections through a hosted-order gateway (ADR-035, FR-PAY-01 to FR-PAY-03, FR-PAY-08,
 * docs/specs/client-extras.md section 3, issue #213). The {@link com.rincoltech.bms.core.payments.PaymentGateway}
 * port with one Pesapal v3 adapter and an in-process fake; the payment intents (a tenant table with a state machine
 * the database guards); the public notification endpoints, which read two identifiers from a request and trust
 * nothing else; the checker that asks the provider for the verified status and is the only thing that moves an
 * intent to paid; the poller that does the same for intents whose notification never came; and the purpose handler
 * registry ({@link com.rincoltech.bms.core.payments.PurposeHandler}) through which the owning module books the money
 * (loan repayments and savings deposits arrive in slices #216 and #219). Off unless the host environment configures
 * a gateway and a callback address, the tenant connects and tests its own merchant account ({@code Receive payments}:
 * credentials sealed with the application data key, one account per tenant, never the platform's) and the tenant switches
 * collections on. Money owed to a tenant lands only in that tenant's own account (ADR-035 amendment of 2026-10-18, issue #233).
 * Depends on no vertical.
 */
@ApplicationModule(
        id = "core.payments",
        displayName = "Core: Payments",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.jobs",
            "core.ledger",
            "core.approvals",
            "core.operations",
            "core.identity",
            "core.notifications"
        })
package com.rincoltech.bms.core.payments;

import org.springframework.modulith.ApplicationModule;
