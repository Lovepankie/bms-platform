/**
 * Notifications (FR-NTF, chapter 12 section 12.6). The outgoing message port
 * {@link com.rincoltech.bms.core.notifications.Notifier} with a fake adapter that records messages,
 * and the outbox {@link com.rincoltech.bms.core.notifications.Outbox} (ADR-024, spec section 11):
 * rows written in the caller's transaction and delivered by a db-scheduler job (ADR-008) through
 * an SMTP email sender (implicit TLS) and a Telegram Bot API sender, each switched on only when its
 * environment variables are set, and the SMS channel (ADR-036): a provider port with an Africa's
 * Talking adapter, a non-sending sink that is the only provider allowed outside production, the
 * tenant link and category on the outbox row, a delivery and cost log, the opt-out, the sending window,
 * per tenant and platform caps, and a startup guard that refuses any configuration that could text a
 * real phone from staging, and Web Push (ADR-050): installed-app notifications to a signed-in person's devices, with VAPID keys
 * from the environment, a per user per device subscription table and a push channel on the same outbox. USSD stays pending ADR-013; the WhatsApp relay is build step 3 of the spec.
 */
@ApplicationModule(
        id = "core.notifications",
        displayName = "Core: Notifications",
        allowedDependencies = {"kernel", "core.audit", "core.tenancy", "core.jobs"})
package com.rincoltech.bms.core.notifications;

import org.springframework.modulith.ApplicationModule;
