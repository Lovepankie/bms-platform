package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AdvisorAnswer.Point;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.advisor.internal.Catalogue.Action;
import com.rincoltech.bms.lending.advisor.internal.Catalogue.Topic;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Context;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Result;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only control over a model's answer (ADR-037 decision 5 as amended in issue #223, spec 5.13): <b>the model writes no
 * text</b>. Four rounds of independent review found wording that got past every word list, pattern and sentence rule applied
 * to free prose, so there is no prose to check. The model answers in a fixed JSON shape,
 *
 * <pre>
 * {"findings": [{"fact_keys": [up to three keys of the facts it was given],
 *                "topic": one of the catalogue's topics,
 *                "action": one of the catalogue's action codes,
 *                "priority": "high" | "medium" | "low"}],
 *  "watch": [fact keys]}
 * </pre>
 *
 * and this class accepts it only when every part is exactly what the schema allows: no field beyond those, every string equal
 * to an enum value or to a key of a fact in the prompt, the action belonging to the topic, each fact of a family the topic may
 * reference, no fact that is hidden ({@code fewer than 5}) or outside the prompt (and so outside the caller's scope), no
 * duplicates, and the counts within their limits. Anything else is refused as a whole, with a closed code and nothing the
 * model wrote, and the caller shows the deterministic {@link Fallback}.
 *
 * <p>An accepted answer is rendered by our code: the reviewed templates and action texts of {@link Catalogue}, and the phrases
 * of {@link Phrases} for the facts (value, name, movement, period and better or worse all from the fact). As a second line of
 * defence every number of the rendered text is re-derived from the facts it came from, so a defect in a template or in the
 * substitution cannot reach a screen.
 *
 * <p>The rules can each be switched off in a test ({@link Rules}) so that the golden evaluation can prove every rule is the
 * one doing its work.
 */
final class StructuredValidator {

    /** Test switches: each rule off in turn must make the golden evaluation fail. Never changed outside tests. */
    static final class Rules {
        static volatile boolean strictFields = true;
        static volatile boolean enumTopic = true;
        static volatile boolean enumAction = true;
        static volatile boolean enumPriority = true;
        static volatile boolean actionBelongsToTopic = true;
        static volatile boolean factExists = true;
        static volatile boolean factNotHidden = true;
        static volatile boolean factFitsTopic = true;
        static volatile boolean noDuplicates = true;
        static volatile boolean limits = true;
        static volatile boolean calmAction = true;
        static volatile boolean secondLine = true;

        private Rules() {}

        static void reset() {
            strictFields = true;
            enumTopic = true;
            enumAction = true;
            enumPriority = true;
            actionBelongsToTopic = true;
            factExists = true;
            factNotHidden = true;
            factFitsTopic = true;
            noDuplicates = true;
            limits = true;
            calmAction = true;
            secondLine = true;
            Phrases.corrupt = false;
        }
    }

    static final int MAX_RAW_CHARS = 4000;
    static final int MAX_FINDINGS = 6;
    static final int MAX_FACT_KEYS = 3;
    static final int MAX_WATCH = 6;

    private static final Set<String> ROOT_FIELDS = Set.of("findings", "watch");
    private static final Set<String> FINDING_FIELDS = Set.of("fact_keys", "topic", "action", "priority");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private record Finding(List<String> keys, Topic topic, Action action, String priority) {}

    private StructuredValidator() {}

    /** Validates and renders one raw answer. It fails closed: an exception is {@code validator_error}. */
    static Result validate(String raw, Context ctx) {
        try {
            return validateAnswer(raw, ctx);
        } catch (RuntimeException e) {
            return new Result(false, List.of(new Violation("validator_error", "answer", null)), null);
        }
    }

    private static Result fail(String code, String where) {
        return new Result(false, List.of(new Violation(code, where, null)), null);
    }

    private static Result validateAnswer(String raw, Context ctx) {
        if (raw == null || raw.isBlank()) {
            return fail("not_json", "answer");
        }
        if (raw.length() > MAX_RAW_CHARS) {
            return fail("too_long", "answer");
        }
        String trimmed = raw.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return fail("not_json", "answer");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (JacksonException e) {
            return fail("not_json", "answer");
        }
        List<Violation> bad = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return fail("schema", "answer");
        }
        fields(root, ROOT_FIELDS, "answer", bad);
        JsonNode findingsNode = root.get("findings");
        JsonNode watchNode = root.get("watch");
        if (findingsNode == null || !findingsNode.isArray() || watchNode == null || !watchNode.isArray()) {
            bad.add(new Violation("schema", "answer", null));
            return new Result(false, bad, null);
        }
        if (Rules.limits && findingsNode.size() > MAX_FINDINGS) {
            bad.add(new Violation("too_many", "findings", null));
        }
        if (Rules.limits && watchNode.size() > MAX_WATCH) {
            bad.add(new Violation("too_many", "watch", null));
        }

        Map<String, PromptFact> byKey = new LinkedHashMap<>();
        ctx.facts().facts().forEach(f -> byKey.put(f.key(), f));
        Catalogue catalogue = Catalogue.INSTANCE;

        List<Finding> findings = new ArrayList<>();
        int n = 0;
        for (JsonNode node : findingsNode) {
            n++;
            String where = "finding " + n;
            if (!node.isObject()) {
                bad.add(new Violation("schema", where, null));
                continue;
            }
            fields(node, FINDING_FIELDS, where, bad);
            JsonNode keysNode = node.get("fact_keys");
            JsonNode topicNode = node.get("topic");
            JsonNode actionNode = node.get("action");
            JsonNode priorityNode = node.get("priority");
            if (keysNode == null
                    || !keysNode.isArray()
                    || !isString(topicNode)
                    || !isString(actionNode)
                    || !isString(priorityNode)) {
                bad.add(new Violation("schema", where, null));
                continue;
            }
            List<String> keys = strings(keysNode, where, bad);
            if (keys == null) {
                continue;
            }
            if (Rules.limits && (keys.isEmpty() || keys.size() > MAX_FACT_KEYS)) {
                bad.add(new Violation("too_many", where, null));
            }
            Topic topic = catalogue.topics().get(topicNode.asString());
            if (topic == null && Rules.enumTopic) {
                bad.add(new Violation("unknown_topic", where, null));
            }
            Action action = catalogue.actions().get(actionNode.asString());
            if (action == null && Rules.enumAction) {
                bad.add(new Violation("unknown_action", where, null));
            }
            if (action != null && topic != null && !action.topic().equals(topic.code()) && Rules.actionBelongsToTopic) {
                bad.add(new Violation("action_topic_mismatch", where, null));
            }
            String priority = priorityNode.asString();
            if (!Catalogue.PRIORITIES.contains(priority) && Rules.enumPriority) {
                bad.add(new Violation("bad_priority", where, null));
            }
            checkKeys(keys, topic, byKey, where, true, bad);
            if (action != null && action.calm() && Rules.calmAction && !calmAllowed(priority, keys, byKey)) {
                bad.add(new Violation("calm_action_mismatch", where, null));
            }
            if (topic != null && action != null) {
                findings.add(new Finding(keys, topic, action, priority));
            }
        }

        List<String> watch = strings(watchNode, "watch", bad);
        if (watch != null) {
            checkKeys(watch, null, byKey, "watch", false, bad);
        }
        if (!bad.isEmpty()) {
            return new Result(false, bad, null);
        }
        return render(findings, watch, byKey, ctx, catalogue);
    }

    /**
     * A calm action ("no action is needed now") contradicts a high or medium priority and a cited change that is worse: it is
     * allowed only at low priority and when no change among the cited facts is worse.
     */
    private static boolean calmAllowed(String priority, List<String> keys, Map<String, PromptFact> byKey) {
        if (!"low".equals(priority)) {
            return false;
        }
        return keys.stream().map(byKey::get).filter(Objects::nonNull).noneMatch(StructuredValidator::isWorse);
    }

    /** A change fact whose movement is the bad one for its metric. */
    static boolean isWorse(PromptFact f) {
        if (!f.isChange() || f.value() == null || f.value() == 0) {
            return false;
        }
        return switch (f.polarity()) {
            case HIGHER_IS_BETTER -> f.value() < 0;
            case HIGHER_IS_WORSE -> f.value() > 0;
            case NEUTRAL -> false;
        };
    }

    private static boolean isString(JsonNode node) {
        return node != null && node.isString();
    }

    /** The strings of an array, or null (with a violation) when any element is not a string. */
    private static List<String> strings(JsonNode array, String where, List<Violation> bad) {
        List<String> out = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isString()) {
                bad.add(new Violation("schema", where, null));
                return null;
            }
            out.add(element.asString());
        }
        return out;
    }

    /** The object has exactly the allowed fields: an extra one is refused, a missing one is a schema violation. */
    private static void fields(JsonNode node, Set<String> allowed, String where, List<Violation> bad) {
        Set<String> names = node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        if (Rules.strictFields && !allowed.containsAll(names)) {
            bad.add(new Violation("unknown_field", where, null));
        }
        if (!names.containsAll(allowed)) {
            bad.add(new Violation("schema", where, null));
        }
    }

    private static void checkKeys(
            List<String> keys,
            Topic topic,
            Map<String, PromptFact> byKey,
            String where,
            boolean bindToTopic,
            List<Violation> bad) {
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            if (!seen.add(key) && Rules.noDuplicates) {
                bad.add(new Violation("duplicate_fact", where, null));
            }
            PromptFact fact = byKey.get(key);
            if (fact == null) {
                if (Rules.factExists) {
                    bad.add(new Violation("unknown_fact", where, null));
                }
                continue;
            }
            if (fact.suppressed() && Rules.factNotHidden) {
                bad.add(new Violation("hidden_fact", where, null));
            }
            if (bindToTopic && topic != null && !topic.facts().contains(Catalogue.base(fact)) && Rules.factFitsTopic) {
                bad.add(new Violation("fact_topic_mismatch", where, null));
            }
        }
    }

    // ---- The rendering ------------------------------------------------------------------------------------------

    private static Result render(
            List<Finding> findings,
            List<String> watch,
            Map<String, PromptFact> byKey,
            Context ctx,
            Catalogue catalogue) {
        AliasedFacts facts = ctx.facts();
        String summary = Templates.fill(
                catalogue.template("summary"),
                Map.of(
                        "asof",
                        Phrases.date(facts.asOf()),
                        "range",
                        Phrases.date(facts.from()) + " to " + Phrases.date(facts.to())));
        List<Point> points = new ArrayList<>();
        List<List<PromptFact>> cited = new ArrayList<>();
        for (Finding f : findings) {
            List<PromptFact> used =
                    f.keys().stream().map(byKey::get).filter(x -> x != null).toList();
            String action = Templates.fill(f.action().text(), Map.of("where", where(used)));
            String text = Templates.fill(
                    catalogue.template("finding"),
                    Map.of(
                            "priority", Objects.requireNonNullElse(catalogue.priorityLabel(f.priority()), ""),
                            "facts", used.stream().map(Phrases::fact).collect(Collectors.joining("; ")),
                            "action", action));
            points.add(new Point(text, f.keys()));
            cited.add(used);
        }
        if (!watch.isEmpty()) {
            List<PromptFact> used =
                    watch.stream().map(byKey::get).filter(x -> x != null).toList();
            points.add(new Point(
                    Templates.fill(
                            catalogue.template("watch"),
                            Map.of("facts", used.stream().map(Phrases::fact).collect(Collectors.joining("; ")))),
                    watch));
            cited.add(used);
        }
        if (points.isEmpty()) {
            // No finding and nothing to watch: the person sees the key figures, as for a refused answer.
            for (Point p : Fallback.render(facts).points()) {
                points.add(p);
                cited.add(p.facts().stream()
                        .map(byKey::get)
                        .filter(Objects::nonNull)
                        .toList());
            }
        }
        AdvisorAnswer rendered = new AdvisorAnswer(summary, points);
        if (Rules.secondLine) {
            List<Violation> bad = new ArrayList<>();
            bad.addAll(NumericValidator.derivable(summary, "summary", List.of(), ctx).stream()
                    .map(v -> new Violation("render_check_failed", v.where(), v.code()))
                    .toList());
            for (int i = 0; i < points.size(); i++) {
                String where = "point " + (i + 1);
                for (Violation v : NumericValidator.derivable(points.get(i).text(), where, cited.get(i), ctx)) {
                    bad.add(new Violation("render_check_failed", where, v.code()));
                }
                if (NumericValidator.looksPersonal(Text.normalise(points.get(i).text()))) {
                    bad.add(new Violation("render_check_failed", where, "pii_shape"));
                }
            }
            if (!bad.isEmpty()) {
                return new Result(false, bad, null);
            }
        }
        return new Result(true, List.of(), rendered);
    }

    /** The groups the cited facts are about, for the action's {@code {{where}}}: {@code " for Branch 1 and Product A"}. */
    static String where(List<PromptFact> used) {
        List<String> aliases = used.stream()
                .map(PromptFact::alias)
                .filter(a -> a != null)
                .distinct()
                .toList();
        if (aliases.isEmpty()) {
            return "";
        }
        if (aliases.size() == 1) {
            return " for " + aliases.get(0);
        }
        return " for " + String.join(", ", aliases.subList(0, aliases.size() - 1)) + " and "
                + aliases.get(aliases.size() - 1);
    }
}
