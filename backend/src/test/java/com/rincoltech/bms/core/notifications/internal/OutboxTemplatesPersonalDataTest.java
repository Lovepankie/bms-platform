package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Issue #887 (review of #886): an outbox row lives for 30 days after it is sent and the purge of an import run never reaches
 * it, so no import or approval message may carry a text a person typed on the run (a checker's decision note, a signer's
 * name, a review note). Every template of every channel whose key starts with {@code imports.} or names an approval takes
 * only the parameters below; a template that adds any other parameter fails here until it is shown to hold no typed text
 * and added with its reason. ImportPurgeIT's outbox scan relies on this.
 */
class OutboxTemplatesPersonalDataTest {

    /**
     * The parameters an import or approval message may take, each never typed on a run: the run's id, the business's slug
     * and name, the Owner's account name (from {@code users}, the recipient, never a text of the run), a day, the
     * application's fixed reason, and an approval action's label (from its code).
     */
    static final Set<String> ALLOWED =
            Set.of("run", "business", "business_name", "owner_name", "day", "reason", "action_label");

    @Test
    void noImportOrApprovalTemplateTakesATypedNoteOrName() {
        TreeMap<String, Set<String>> found = new TreeMap<>();
        for (String channel : List.of("email", "telegram", "sms", "push")) {
            for (String key : OutboxTemplates.keys(channel)) {
                if (key.contains("import") || key.contains("approv")) {
                    found.put(channel + ":" + key, OutboxTemplates.placeholders(channel, key));
                }
            }
        }
        // The whole list (review of #907): a new import or approval message fails here until it is added and checked.
        assertThat(found.keySet())
                .as("the import and approval messages")
                .containsExactlyInAnyOrder(
                        "email:imports.purged_without_warning",
                        "telegram:imports.purged_without_warning",
                        "telegram:imports.purge_warning",
                        "push:core.approval_policy_weakened",
                        "push:core.approval_waiting");
        found.forEach((template, names) -> {
            Set<String> extra = new TreeSet<>(names);
            extra.removeAll(ALLOWED);
            assertThat(extra)
                    .as("parameters of %s outside the allowed list", template)
                    .isEmpty();
        });
    }
}
