package com.rincoltech.bms.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #852 (ADR-026 decision 5): an Idempotency-Key answers only the person who stored it. The assertions every
 * module's test makes: another person's request with the key is refused with 422 {@code idempotency_key_other_user},
 * is not a replay and carries no id of the stored answer that the caller did not already send; the person who stored it
 * still gets the replay; and the business keeps one key row, the first person's.
 */
public final class KeyOwnership {

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private KeyOwnership() {}

    /**
     * Another person's request with a stored key. {@code sent} is what that person sent (the path and the body): an id
     * in it, such as the branch or the loan, was theirs already and is not a leak.
     */
    public static void refused(ResponseEntity<JsonNode> refused, ResponseEntity<JsonNode> stored, Object... sent) {
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("idempotency_key_other_user");
        assertThat(refused.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        Set<String> known = ids(java.util.Arrays.deepToString(sent));
        Set<String> leaked = ids(String.valueOf(refused.getBody()));
        leaked.retainAll(ids(String.valueOf(stored.getBody())));
        leaked.removeAll(known);
        assertThat(leaked).as("ids of the stored answer in the refusal").isEmpty();
    }

    /** The person who stored the key sends it again: the stored answer, marked as a replay. */
    public static void replayed(ResponseEntity<JsonNode> again, ResponseEntity<JsonNode> first) {
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(first.getStatusCode());
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(ids(String.valueOf(again.getBody()))).containsAll(ids(String.valueOf(first.getBody())));
    }

    /** The people who hold this key in the business: one row each. */
    public static Set<UUID> holders(UUID tenantId, String key) {
        return new LinkedHashSet<>(TestDatabase.owner()
                .sql("SELECT principal_id FROM idempotency_keys WHERE tenant_id = ? AND key = ?")
                .params(tenantId, key)
                .query(UUID.class)
                .list());
    }

    private static Set<String> ids(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = UUID_TEXT.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }
}
