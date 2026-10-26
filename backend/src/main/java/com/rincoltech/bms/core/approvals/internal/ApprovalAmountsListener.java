package com.rincoltech.bms.core.approvals.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.approvals.Approvals.Rule;
import com.rincoltech.bms.core.tenancy.ApprovalAmountsChanged;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.PushNotice;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * ADR-091 decision 8 for {@code PATCH /settings}: an amount change that loosens a rule is told to the other administrators with
 * a device, as the approval rules route tells it. Loosened means the rule asked and now asks from a higher amount, or no longer
 * asks at all because an amount lowered to 0 switches the action off by its default (returns, purchase orders). An amount of an
 * action no module registers is told when it is raised, as before.
 */
@Component
class ApprovalAmountsListener {

    private final ApprovalService approvals;
    private final TenantSettings settings;
    private final ApplicationEventPublisher events;

    ApprovalAmountsListener(ApprovalService approvals, TenantSettings settings, ApplicationEventPublisher events) {
        this.approvals = approvals;
        this.settings = settings;
        this.events = events;
    }

    @EventListener
    void on(ApprovalAmountsChanged change) {
        Map<String, Boolean> switches = settings.approvalSwitches();
        Set<String> keys = new HashSet<>(change.beforeMinor().keySet());
        keys.addAll(change.afterMinor().keySet());
        List<String> loosened = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (ApprovalAction action : approvals.actions()) {
            String type = action.actionType();
            known.add(type);
            if (!keys.contains(type) || !action.thresholdApplies()) {
                continue;
            }
            Rule was = ApprovalService.ruleAt(action, change.beforeMinor().getOrDefault(type, 0L), switches.get(type));
            Rule now = ApprovalService.ruleAt(action, change.afterMinor().getOrDefault(type, 0L), switches.get(type));
            if (was.on() && (!now.on() || now.thresholdMinor() > was.thresholdMinor())) {
                loosened.add(action.label());
            }
        }
        for (String type : new TreeSet<>(keys)) {
            if (!known.contains(type)
                    && change.afterMinor().getOrDefault(type, 0L)
                            > change.beforeMinor().getOrDefault(type, 0L)) {
                loosened.add(type.replace('_', ' '));
            }
        }
        if (loosened.isEmpty()) {
            return;
        }
        loosened.sort(null);
        events.publishEvent(new PushNotice(
                "core.approval_policy_weakened",
                "settings-v" + change.version(),
                new PushNotice.Holders("core.settings.manage", null, change.by()),
                Map.of(
                        "action_label",
                        String.join(", ", loosened.subList(0, Math.min(3, loosened.size()))),
                        "path",
                        "/staff/setup/approvals")));
    }
}
