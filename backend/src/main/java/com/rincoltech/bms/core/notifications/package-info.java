/**
 * Notifications (FR-NTF, chapter 12 section 12.6). The outgoing message port
 * {@link com.rincoltech.bms.core.notifications.Notifier} with a fake adapter that records messages,
 * and the outbox {@link com.rincoltech.bms.core.notifications.Outbox} (ADR-024, spec section 11):
 * rows written in the caller's transaction and delivered by a db-scheduler job (ADR-008) through
 * an SMTP email sender (implicit TLS) and a Telegram Bot API sender, each switched on only when its
 * environment variables are set. No SMS provider is wired yet (pending ADR-013); the WhatsApp relay
 * is build step 3 of the spec.
 */
@ApplicationModule(
        id = "core.notifications",
        displayName = "Core: Notifications",
        allowedDependencies = {"kernel", "core.audit"})
package com.rincoltech.bms.core.notifications;

import org.springframework.modulith.ApplicationModule;
