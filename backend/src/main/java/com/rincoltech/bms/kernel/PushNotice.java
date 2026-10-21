package com.rincoltech.bms.kernel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A staff push notification a module wants sent for something that just happened (ADR-050). It is an application event,
 * published in the transaction of the cause and handled synchronously by {@code core.notifications}, so no module needs to
 * depend on the notifications module (the tenancy and approvals modules could not: notifications depends on them). The
 * handler writes one outbox row per person who has a device and sends later; a business event never waits for, or fails
 * because of, a push. With push switched off, or nobody with a device, the event is dropped.
 *
 * <p>The text is not here: the {@code template} key names a push rendering in the notifications module, filled from
 * {@code params}. A {@code path} parameter (an app route that starts with one slash) is where a tap opens. No parameter may
 * hold a name, a phone number, an identity number or an amount: a notification is shown on a lock screen.
 *
 * @param template the push template key, for example {@code retail.sale_voided}
 * @param eventRef what the event is about, so a retried or replayed cause never notifies twice: the id of the sale, the
 *     transfer, the approval; unique per template
 * @param audience who is told
 * @param params the template values
 */
public record PushNotice(String template, String eventRef, Audience audience, Map<String, String> params) {

    public PushNotice {
        if (template == null || template.isBlank() || eventRef == null || eventRef.isBlank() || audience == null) {
            throw new IllegalArgumentException("a push notice needs a template, an event reference and an audience");
        }
        params = Map.copyOf(params);
    }

    /** Who is told. */
    public sealed interface Audience permits Users, Holders, BranchStaff, AnyOf {}

    /** These people, when they have a device. */
    public record Users(Set<UUID> userIds) implements Audience {

        public Users {
            userIds = Set.copyOf(userIds);
        }
    }

    /**
     * Every active staff user who holds the permission through a role, in the branch when one is given (a role with no
     * branch holds it everywhere), without the user who caused the event when one is given.
     */
    public record Holders(String permission, UUID branchId, UUID exceptUserId) implements Audience {}

    /**
     * The staff of one branch: every active staff user who holds the permission through a role given for that branch
     * itself. A role with no branch (an administrator's) does not count here, so a notice about one shop's daily work
     * reaches the people who work there and not every administrator (issue #412).
     */
    public record BranchStaff(String permission, UUID branchId, UUID exceptUserId) implements Audience {

        public BranchStaff {
            if (permission == null || branchId == null) {
                throw new IllegalArgumentException("the staff of a branch need a permission and a branch");
            }
        }
    }

    /** Everyone in any of these audiences, each person once (issue #412). */
    public record AnyOf(List<Audience> parts) implements Audience {

        public AnyOf {
            parts = List.copyOf(parts);
        }
    }
}
