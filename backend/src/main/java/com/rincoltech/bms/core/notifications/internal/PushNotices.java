package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PushNotice;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a {@link PushNotice} published by a module into outbox rows (ADR-050). It runs synchronously in the publisher's
 * transaction, so a notice exists exactly when the event that caused it commits and a rolled-back event notifies nobody
 * (FR-NTF-01). Everything it does runs inside a savepoint ({@link #on}): a failure of any kind rolls back to the savepoint, is
 * logged by class only, and the business event goes on, because a notification is never worth a failed sale or approval.
 *
 * <p>Nothing is queued while push is off (no VAPID keys), and nobody without a subscribed device gets a row, so a business
 * that does not use push writes nothing. One row per person: the key is {@code push:<template>:<event>:<user>}, so a retried
 * cause never notifies twice.
 */
@Component
class PushNotices {

    /** A notice that cannot be delivered within this time is no longer news; the outbox expires the row. */
    static final Duration LIFETIME = Duration.ofHours(12);

    /**
     * Notices about something already on its way to a branch: staff of a closed branch still receive it (a transfer may be
     * received at a closed branch), so their branch need not be active. Every other notice needs an active branch.
     */
    private static final Set<String> ON_THE_ROAD = Set.of("retail.transfer_dispatched");

    private static final Logger log = LoggerFactory.getLogger(PushNotices.class);

    private final Outbox outbox;
    private final JdbcClient jdbc;
    private final VapidKeys keys;
    private final BusinessClock clock;
    private final NotificationSwitches switches;
    private final TransactionTemplate savepoint;

    PushNotices(
            Outbox outbox,
            JdbcClient jdbc,
            VapidKeys keys,
            BusinessClock clock,
            NotificationSwitches switches,
            PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.keys = keys;
        this.clock = clock;
        this.switches = switches;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @EventListener
    void on(PushNotice notice) {
        if (!keys.available()) {
            return;
        }
        try {
            savepoint.executeWithoutResult(tx -> write(notice));
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError error) {
                throw error;
            }
            // The event that called is a business event; its notice is not worth it. No name, no value.
            log.warn(
                    "push notice '{}' failed and was skipped: {}",
                    notice.template(),
                    e.getClass().getSimpleName());
        }
    }

    private void write(PushNotice notice) {
        // Issue #412: a business can switch each of the everyday notices off; the safeguard notices of ADR-050 have no
        // switch.
        if (!switches.allows(notice.template())) {
            return;
        }
        List<UUID> people = recipients(notice.template(), notice.audience());
        if (people.isEmpty()) {
            return;
        }
        Map<String, String> params = withNames(notice.params());
        var expires = clock.now().plus(LIFETIME);
        for (UUID person : people) {
            outbox.enqueue(new Outbox.Message(
                    Outbox.PUSH,
                    PushOutboxChannel.USER_PREFIX + person,
                    notice.template(),
                    params,
                    "push:" + notice.template() + ":" + notice.eventRef() + ":" + person,
                    null,
                    expires));
        }
    }

    private List<UUID> recipients(String template, PushNotice.Audience audience) {
        return switch (audience) {
            case PushNotice.Holders h -> holders(h, ON_THE_ROAD.contains(template));
            case PushNotice.BranchStaff b -> branchStaff(b);
            case PushNotice.AnyOf any -> {
                Set<UUID> out = new LinkedHashSet<>();
                for (PushNotice.Audience part : any.parts()) {
                    out.addAll(recipients(template, part));
                }
                yield new ArrayList<>(out);
            }
            case PushNotice.Users u -> {
                List<UUID> out = new ArrayList<>();
                for (UUID id : u.userIds()) {
                    if (activeWithDevice(id)) {
                        out.add(id);
                    }
                }
                yield out;
            }
        };
    }

    private boolean activeWithDevice(UUID userId) {
        return jdbc.sql("""
                        SELECT count(*) FROM users u
                         WHERE u.id = ? AND u.kind = 'staff' AND u.status = 'active'
                           AND EXISTS (SELECT 1 FROM push_subscriptions s
                                        WHERE s.tenant_id = u.tenant_id AND s.user_id = u.id)
                        """).param(userId).query(Integer.class).single() > 0;
    }

    private List<UUID> holders(PushNotice.Holders h, boolean closedBranchOk) {
        return jdbc
                .sql("""
                        SELECT DISTINCT u.id
                          FROM users u
                          JOIN user_role_assignments a
                            ON a.tenant_id = u.tenant_id AND a.user_id = u.id AND a.revoked_at IS NULL
                          JOIN role_permissions rp ON rp.role_key = a.role_key AND rp.permission_key = ?
                         WHERE u.kind = 'staff' AND u.status = 'active'
                           AND (a.branch_id IS NULL
                                OR (CAST(? AS uuid) IS NOT NULL AND a.branch_id = CAST(? AS uuid)
                                    AND (? OR EXISTS (SELECT 1 FROM branches b WHERE b.id = a.branch_id AND b.status = 'active'))))
                           AND (CAST(? AS uuid) IS NULL OR u.id <> CAST(? AS uuid))
                           AND EXISTS (SELECT 1 FROM push_subscriptions s
                                        WHERE s.tenant_id = u.tenant_id AND s.user_id = u.id)
                        """)
                .params(h.permission(), h.branchId(), h.branchId(), closedBranchOk, h.exceptUserId(), h.exceptUserId())
                .query(UUID.class)
                .list()
                .stream()
                .filter(id -> h.alsoHeld().stream().allMatch(p -> holds(id, p, h.branchId())))
                .toList();
    }

    /** The user holds the permission through a live role in the branch, or with no branch (everywhere). */
    private boolean holds(UUID userId, String permission, UUID branchId) {
        return jdbc.sql("""
                                SELECT count(*) FROM user_role_assignments a
                                  JOIN role_permissions rp ON rp.role_key = a.role_key AND rp.permission_key = ?
                                 WHERE a.user_id = ? AND a.revoked_at IS NULL
                                   AND (a.branch_id IS NULL OR a.branch_id = CAST(? AS uuid))
                                """)
                        .params(permission, userId, branchId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Holders of the permission through a role given for this branch itself, at an active branch (issue #412). */
    private List<UUID> branchStaff(PushNotice.BranchStaff b) {
        return jdbc.sql("""
                        SELECT DISTINCT u.id
                          FROM users u
                          JOIN user_role_assignments a
                            ON a.tenant_id = u.tenant_id AND a.user_id = u.id AND a.revoked_at IS NULL
                          JOIN role_permissions rp ON rp.role_key = a.role_key AND rp.permission_key = ?
                          JOIN branches b ON b.tenant_id = a.tenant_id AND b.id = a.branch_id AND b.status = 'active'
                         WHERE u.kind = 'staff' AND u.status = 'active'
                           AND a.branch_id = ?
                           AND (CAST(? AS uuid) IS NULL OR u.id <> CAST(? AS uuid))
                           AND EXISTS (SELECT 1 FROM push_subscriptions s
                                        WHERE s.tenant_id = u.tenant_id AND s.user_id = u.id)
                        """)
                .params(b.permission(), b.branchId(), b.exceptUserId(), b.exceptUserId())
                .query(UUID.class)
                .list();
    }

    /**
     * A parameter named {@code ..._branch_id} or {@code branch_id} is turned into its branch's name under the same name with
     * {@code _id} replaced by {@code _name}, so the modules pass ids and the templates read names.
     */
    private Map<String, String> withNames(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>(params);
        params.forEach((key, value) -> {
            if (key.endsWith("branch_id")) {
                String name = jdbc.sql("SELECT name FROM branches WHERE id = CAST(? AS uuid)")
                        .param(value)
                        .query(String.class)
                        .optional()
                        .orElse("your branch");
                out.put(key.substring(0, key.length() - 2) + "name", name);
            }
        });
        return out;
    }
}
