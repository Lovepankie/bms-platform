package com.rincoltech.bms.core.payments.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Matches statement lines to the tenant's own payment intents (spec 3.9): the order tracking id first, which is unique per
 * order; the confirmation code only for a line that has no tracking id, and only when it names exactly one order, because a
 * provider's short code is not unique (#312). Reads only, in the caller's transaction with the tenant bound, so another
 * tenant's order can never be named. One lookup per batch of lines, never one per line.
 */
@Component
class StatementMatcher {

    private static final int BATCH = 500;

    /** What a line asks to be matched on. */
    record Line(String orderTrackingId, String confirmationCode, long amountMinor, String currency) {}

    /** The facts of an intent a match needs. */
    record IntentFacts(
            UUID id,
            UUID branchId,
            String status,
            String merchantReference,
            String providerReference,
            String confirmationCode,
            Long paidAmountMinor,
            String paidCurrency,
            Instant paidAt) {

        boolean paidHere() {
            return paidAt != null && paidAmountMinor != null;
        }
    }

    /** The outcome for one line; {@code intent} is null for {@code unmatched} and {@code ambiguous}. */
    record Match(String status, IntentFacts intent) {}

    private final JdbcClient jdbc;

    StatementMatcher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Match> match(List<Line> lines) {
        List<String> tracking = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (Line l : lines) {
            if (l.orderTrackingId() != null) {
                tracking.add(l.orderTrackingId().toLowerCase(Locale.ROOT));
            } else if (l.confirmationCode() != null) {
                codes.add(l.confirmationCode().toUpperCase(Locale.ROOT));
            }
        }
        Map<String, IntentFacts> byTracking = new HashMap<>();
        for (IntentFacts f : query("lower(provider_reference)", tracking)) {
            byTracking.put(f.providerReference().toLowerCase(Locale.ROOT), f);
        }
        Map<String, List<IntentFacts>> byCode = new HashMap<>();
        for (IntentFacts f : query("upper(confirmation_code)", codes)) {
            byCode.computeIfAbsent(f.confirmationCode().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(f);
        }
        List<Match> out = new ArrayList<>();
        for (Line l : lines) {
            IntentFacts intent = null;
            if (l.orderTrackingId() != null) {
                intent = byTracking.get(l.orderTrackingId().toLowerCase(Locale.ROOT));
            } else {
                List<IntentFacts> named =
                        byCode.getOrDefault(l.confirmationCode().toUpperCase(Locale.ROOT), List.of());
                if (named.size() > 1) {
                    out.add(new Match("ambiguous", null));
                    continue;
                }
                intent = named.isEmpty() ? null : named.getFirst();
            }
            out.add(intent == null ? new Match("unmatched", null) : new Match(judge(l, intent), intent));
        }
        return out;
    }

    private static String judge(Line l, IntentFacts intent) {
        if (!intent.paidHere()) {
            return "not_paid_here";
        }
        if (!l.currency().equals(intent.paidCurrency())) {
            return "currency_mismatch";
        }
        return l.amountMinor() == intent.paidAmountMinor() ? "matched" : "amount_mismatch";
    }

    private List<IntentFacts> query(String expression, Collection<String> keys) {
        List<IntentFacts> found = new ArrayList<>();
        List<String> all = new ArrayList<>(new java.util.LinkedHashSet<>(keys));
        for (int from = 0; from < all.size(); from += BATCH) {
            List<String> batch = all.subList(from, Math.min(all.size(), from + BATCH));
            found.addAll(jdbc.sql("""
                            SELECT id, branch_id, status, merchant_reference, provider_reference, confirmation_code,
                                   paid_amount_minor, paid_currency, paid_at
                              FROM payment_intents
                             WHERE provider = 'pesapal' AND scope = 'tenant' AND %s IN (:keys)
                            """.formatted(expression))
                    .param("keys", batch)
                    .query((rs, n) -> {
                        Timestamp paid = rs.getTimestamp("paid_at");
                        return new IntentFacts(
                                rs.getObject("id", UUID.class),
                                rs.getObject("branch_id", UUID.class),
                                rs.getString("status"),
                                rs.getString("merchant_reference"),
                                rs.getString("provider_reference"),
                                rs.getString("confirmation_code"),
                                (Long) rs.getObject("paid_amount_minor"),
                                rs.getString("paid_currency"),
                                paid == null ? null : paid.toInstant());
                    })
                    .list());
        }
        return found;
    }
}
