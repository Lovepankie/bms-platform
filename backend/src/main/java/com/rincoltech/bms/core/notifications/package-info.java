/**
 * Notifications (FR-NTF, chapter 12 section 12.6). Today: the outgoing message port
 * {@link com.rincoltech.bms.core.notifications.Notifier} and a fake adapter that records messages
 * instead of sending them. No email or SMS provider is wired yet (pending ADR-013 for SMS);
 * templates, the outbox and the send log arrive with increment 6.
 */
@ApplicationModule(
        id = "core.notifications",
        displayName = "Core: Notifications",
        allowedDependencies = {"kernel"})
package com.rincoltech.bms.core.notifications;

import org.springframework.modulith.ApplicationModule;
