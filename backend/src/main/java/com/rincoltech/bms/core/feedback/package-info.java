/**
 * Core: feedback sent from inside the app (issue #447, ADR-065). Any signed-in staff user sends an idea, a problem or a
 * question with an optional screenshot; the tenant, the sender, the page they came from, the running version and the
 * browser are recorded with it in {@code feedback_items} (forced RLS); the sender sees their own items, their status and
 * the operator's reply. Each new item queues an email to every active platform operator through the outbox, in a
 * savepoint, so a failure there never stops the send. At most ten items per person per hour. Public API:
 * {@link com.rincoltech.bms.core.feedback.FeedbackInbox}, the operator's list, status change and screenshot read, which
 * {@code core.platform} serves on the platform host through the SECURITY DEFINER functions of migration V80 (ADR-016).
 */
@ApplicationModule(
        id = "core.feedback",
        displayName = "Core: Feedback",
        allowedDependencies = {"kernel", "core.tenancy", "core.audit", "core.documents", "core.notifications"})
package com.rincoltech.bms.core.feedback;

import org.springframework.modulith.ApplicationModule;
