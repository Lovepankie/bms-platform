package com.rincoltech.bms.core.approvals.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.approvals.Approvals.Rule;
import com.rincoltech.bms.core.approvals.internal.ApprovalRulesController.RuleChange;
import com.rincoltech.bms.core.approvals.internal.ApprovalRulesController.RuleView;
import com.rincoltech.bms.core.approvals.internal.ApprovalRulesController.RulesView;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.ApprovalRuleStore;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.kernel.Versions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approval rules of the bound business (ADR-091): one row per registered action of a module the business uses, its switch
 * and its amount. A change is validated against the registry (a lending action cannot be switched off), stored, audited with the
 * old and new rule of each action that changed, and told to the other administrators when it loosens a rule.
 */
@Service
class ApprovalRulesService {

    private final ApprovalService approvals;
    private final ApprovalRuleStore store;
    private final TenantModules modules;
    private final CurrentTenant tenant;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;

    ApprovalRulesService(
            ApprovalService approvals,
            ApprovalRuleStore store,
            TenantModules modules,
            CurrentTenant tenant,
            AuditLog audit,
            ApplicationEventPublisher events) {
        this.approvals = approvals;
        this.store = store;
        this.modules = modules;
        this.tenant = tenant;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    RulesView list() {
        return new RulesView(
                listed().stream().map(this::view).toList(), tenant.profile().currency(), store.version());
    }

    @Transactional
    RulesView update(String ifMatch, List<RuleChange> changes) {
        int expected = Versions.fromIfMatch(ifMatch);
        Map<String, ApprovalAction> listed = new LinkedHashMap<>();
        listed().forEach(a -> listed.put(a.actionType(), a));
        List<FieldProblem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RuleChange c : changes) {
            ApprovalAction action = listed.get(c.actionType());
            if (action == null || !seen.add(c.actionType())) {
                problems.add(new FieldProblem(
                        "rules", "invalid", "Each action once, from the list of this business's actions."));
            } else if (c.thresholdMinor() != null && !action.thresholdApplies()) {
                problems.add(new FieldProblem("rules", "invalid", action.label() + " has no amount."));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        for (RuleChange c : changes) {
            if (!c.on() && !listed.get(c.actionType()).switchable()) {
                throw ApiException.rule(
                        "approval_always_on",
                        listed.get(c.actionType()).label()
                                + " always needs a second person. It cannot be switched off.");
            }
        }
        Map<String, Boolean> switches = new TreeMap<>();
        Map<String, Long> amounts = new TreeMap<>();
        Map<String, Object> before = new TreeMap<>();
        Map<String, Object> after = new TreeMap<>();
        List<String> loosened = new ArrayList<>();
        for (RuleChange c : changes) {
            ApprovalAction action = listed.get(c.actionType());
            Rule was = approvals.rule(action);
            long amount = c.thresholdMinor() == null ? was.thresholdMinor() : c.thresholdMinor();
            Rule now = new Rule(c.on(), amount);
            if (now.equals(was)) {
                continue;
            }
            // The switch is stored once it is saved, so a later change of the amount never flips a default.
            if (action.switchable()) {
                switches.put(c.actionType(), c.on());
            }
            if (action.thresholdApplies() && amount != was.thresholdMinor()) {
                amounts.put(c.actionType(), amount);
            }
            before.put(c.actionType(), words(action, was));
            after.put(c.actionType(), words(action, now));
            if (was.on() && (!now.on() || now.thresholdMinor() > was.thresholdMinor())) {
                loosened.add(action.label());
            }
        }
        if (before.isEmpty()) {
            return list();
        }
        store.saveApprovalRules(switches, amounts, expected);
        audit.record(new AuditLog.Entry(
                "core.settings.approval_rules_changed",
                "core.tenant_settings",
                tenant.profile().id(),
                null,
                before,
                after));
        if (!loosened.isEmpty()) {
            // The other administrators with a device hear that a safeguard was loosened (ADR-050); the person who
            // changed it
            // does not.
            events.publishEvent(new PushNotice(
                    "core.approval_policy_weakened",
                    "approval-rules-v" + (expected + 1),
                    new PushNotice.Holders(
                            "core.settings.manage",
                            null,
                            CurrentPrincipal.get().map(Principal::userId).orElse(null)),
                    Map.of(
                            "action_label",
                            String.join(", ", loosened.subList(0, Math.min(3, loosened.size()))),
                            "path",
                            "/staff/setup/approvals")));
        }
        return list();
    }

    /** The actions of the modules the business uses, as the screen lists them: lending first, then retail, in label order. */
    private List<ApprovalAction> listed() {
        return approvals.actions().stream()
                .filter(ApprovalAction::listed)
                .filter(a -> modules.isEnabled(moduleOf(a)))
                .filter(ApprovalAction::offered)
                .sorted(Comparator.comparing(ApprovalRulesService::moduleOf).thenComparing(ApprovalAction::label))
                .toList();
    }

    private RuleView view(ApprovalAction action) {
        Rule rule = approvals.rule(action);
        return new RuleView(
                action.actionType(),
                moduleOf(action),
                action.label(),
                action.thresholdApplies(),
                action.switchable(),
                rule.on(),
                action.thresholdApplies() ? rule.thresholdMinor() : null);
    }

    private static Map<String, Object> words(ApprovalAction action, Rule rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("on", rule.on());
        if (action.thresholdApplies()) {
            m.put("threshold_minor", rule.thresholdMinor());
        }
        return m;
    }

    /** {@code lending.loan} belongs to {@code lending}: the module is the first part of the subject type. */
    static String moduleOf(ApprovalAction action) {
        String subject = action.subjectType();
        int dot = subject.indexOf('.');
        return dot < 0 ? subject : subject.substring(0, dot);
    }
}
