package com.rincoltech.bms.retail.advisor.internal;

import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.retail.advisor.internal.BriefCatalogue.Action;
import com.rincoltech.bms.retail.advisor.internal.BriefCatalogue.Topic;
import com.rincoltech.bms.retail.advisor.internal.PromptFacts.PromptFact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything between the shop facts and the brief that is shown (issue #422), on the rules of the lending brief (ADR-037 as
 * amended in #223, spec 5.13): the request (a reviewed system prompt and the facts as data), the check of the model's
 * structured answer, and the rendering. <b>The model writes no text</b>: it answers
 *
 * <pre>
 * {"findings": [{"fact_keys": [up to three keys], "topic": a topic code, "action": an action code,
 *                "priority": "high" | "medium" | "low"}],
 *  "watch": [fact keys]}
 * </pre>
 *
 * and the answer is accepted only when every part is exactly what the schema allows: no other field, every string a code of the
 * catalogue or a key of a fact in the prompt, the action of the topic, each fact of a family the topic may cite, a calm action
 * only at low priority beside no worse change, no duplicates, the counts within limits. Anything else is refused as a whole and
 * the facts only brief is shown. An accepted answer is written by our code from the catalogue and the facts; as a second line
 * every number in a written point must be a number of a fact it cites, counted, else it is refused too. An answer that flags
 * nothing is the facts only brief with the reason {@code empty}, never labelled as AI.
 */
final class BriefWriter {

    static final int MAX_TOKENS = 700;
    static final String CANARY = "RETAIL-BRIEF-PROMPT-V1";
    static final String RESOURCE = "/retail-advisor/system-prompt.txt";
    static final String VALIDATION = "validation";
    /** The reason stored when the model's valid answer flagged nothing: the day shows the automatic summary. */
    static final String EMPTY = "empty";

    static final int MAX_RAW_CHARS = 4000;
    static final int MAX_FINDINGS = 6;
    static final int MAX_FACT_KEYS = 3;
    static final int MAX_WATCH = 6;
    static final int MAX_CHIPS = 8;

    /** The figures an owner looks at first, in order, for the facts only brief. */
    static final List<String> KEY_FIGURES = List.of(
            "sales.yesterday",
            "sales.yesterday.change",
            "sales.last_7_days",
            "sales.last_7_days.change",
            "profit.last_7_days",
            "own.sales_yesterday",
            "own.sales_last_7_days",
            "own.sales_last_7_days.change",
            "credit.overdue",
            "stock.out",
            "stock.low",
            "orders.open",
            "controls.voided_sales");

    private static final Set<String> ROOT_FIELDS = Set.of("findings", "watch");
    private static final Set<String> FINDING_FIELDS = Set.of("fact_keys", "topic", "action", "priority");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z]+)\\}\\}");
    /** A number as our code writes one: digits with their thousands commas and a decimal point, {@code 1,250,000}. */
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    static final String SYSTEM =
            load().replace("@CANARY@", CANARY).replace("@TOPICS@", topics()).replace("@ACTIONS@", actions());

    /** One point of the brief: our sentence and the fact keys it rests on. */
    record Point(String text, List<String> facts) {}

    /** A figure the text rests on. {@code metric} is the fact's family, which the screen links to its page. */
    record Chip(String key, String label, String display, String metric) {}

    /** What is stored and shown. {@code source} is {@code model} or {@code fallback}; {@code reason} is null for a model brief. */
    record Brief(
            String source, String reason, String summary, List<Point> points, List<Chip> chips, LocalDate factsAsOf) {

        boolean model() {
            return "model".equals(source);
        }
    }

    private record Finding(List<String> keys, Topic topic, Action action, String priority) {}

    private BriefWriter() {}

    // ---- The request -------------------------------------------------------------------------------------------

    static LlmClient.Request request(PromptFacts facts) {
        return new LlmClient.Request(
                List.of(new LlmClient.Message("system", SYSTEM), new LlmClient.Message("user", content(facts))),
                MAX_TOKENS,
                true);
    }

    static String content(PromptFacts facts) {
        StringBuilder out = new StringBuilder();
        out.append("Figures stand at the end of ")
                .append(facts.asOf())
                .append(". Currency: ")
                .append(facts.currency());
        out.append(".\nFACTS (key | name | value | note)\n");
        for (PromptFact f : facts.facts()) {
            out.append(f.key()).append(" | ").append(f.label()).append(" | ").append(f.display());
            out.append(" | ")
                    .append(
                            switch (f.polarity()) {
                                case HIGHER_IS_BETTER -> "higher is better";
                                case HIGHER_IS_WORSE -> "lower is better";
                                case NEUTRAL -> "neither higher nor lower is better";
                            });
            out.append('\n');
        }
        return out.append("END FACTS\n").toString();
    }

    private static String topics() {
        StringBuilder out = new StringBuilder();
        BriefCatalogue.INSTANCE
                .topics
                .values()
                .forEach(t -> out.append("- ")
                        .append(t.code())
                        .append(": ")
                        .append(t.label())
                        .append('\n'));
        return out.toString().stripTrailing();
    }

    private static String actions() {
        StringBuilder out = new StringBuilder();
        BriefCatalogue.INSTANCE
                .actions
                .values()
                .forEach(a -> out.append("- ")
                        .append(a.code())
                        .append(", ")
                        .append(a.topic())
                        .append(": ")
                        .append(a.text().replace("{{where}}", ""))
                        .append('\n'));
        return out.toString().stripTrailing();
    }

    private static String load() {
        try (InputStream in = BriefWriter.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    // ---- The brief ---------------------------------------------------------------------------------------------

    /** The brief from what the gateway answered; null when no call was made. */
    static Brief build(PromptFacts facts, AiResult result) {
        if (result == null) {
            return fallback(facts, null);
        }
        if (!result.answered()) {
            return fallback(facts, result.reason().code());
        }
        List<Point> points = validate(result.text(), facts);
        if (points == null) {
            return fallback(facts, VALIDATION);
        }
        if (points.isEmpty()) {
            // No finding and nothing to watch: the key figures, labelled as the automatic summary, since no model chose
            // them.
            return fallback(facts, EMPTY);
        }
        return of("model", null, points, facts);
    }

    /** The facts only brief, with the reason it stands in for a model brief (null when none was asked for). */
    static Brief fallback(PromptFacts facts, String reason) {
        List<String> keys = new ArrayList<>();
        List<String> phrases = new ArrayList<>();
        Map<String, PromptFact> byKey = byKey(facts);
        for (String key : KEY_FIGURES) {
            PromptFact f = byKey.get(key);
            if (f != null) {
                keys.add(key);
                phrases.add(phrase(f));
            }
        }
        List<Point> points = phrases.isEmpty()
                ? List.of(new Point(template("nothing", Map.of()), List.of()))
                : List.of(new Point(template("fallback", Map.of("facts", String.join("; ", phrases))), keys));
        return of("fallback", reason, points, facts);
    }

    private static Brief of(String source, String reason, List<Point> points, PromptFacts facts) {
        List<Point> named = points.stream()
                .map(p -> new Point(facts.toNames(p.text()), List.copyOf(p.facts())))
                .toList();
        return new Brief(
                source,
                reason,
                template("summary", Map.of("asof", DAY.format(facts.asOf()))),
                named,
                chips(named, facts),
                facts.asOf());
    }

    private static List<Chip> chips(List<Point> points, PromptFacts facts) {
        Map<String, PromptFact> byKey = byKey(facts);
        Set<String> keys = new LinkedHashSet<>();
        points.forEach(p -> keys.addAll(p.facts()));
        List<Chip> chips = new ArrayList<>();
        for (String key : keys) {
            PromptFact f = byKey.get(key);
            if (f == null || f.isChange() || chips.size() >= MAX_CHIPS) {
                continue;
            }
            chips.add(new Chip(key, facts.toNames(f.label()), f.display(), f.family()));
        }
        return List.copyOf(chips);
    }

    /** The phrase for one fact: {@code Sales yesterday (UGX 120,000)} or a movement with better or worse, all from the fact. */
    static String phrase(PromptFact f) {
        if (!f.isChange()) {
            return f.label() + " (" + f.display() + ")";
        }
        String name = f.label().substring(0, f.label().lastIndexOf(", change on "));
        String compared = " compared with " + f.against();
        if (f.value() == 0) {
            return name + " did not change" + compared;
        }
        String how = switch (f.polarity()) {
            case HIGHER_IS_BETTER -> f.value() > 0 ? " (better)" : " (worse)";
            case HIGHER_IS_WORSE -> f.value() > 0 ? " (worse)" : " (better)";
            case NEUTRAL -> "";
        };
        return name
                + (f.value() > 0 ? " rose by " : " fell by ")
                + f.display().replaceFirst("^[+-]", "")
                + compared
                + how;
    }

    // ---- The check ---------------------------------------------------------------------------------------------

    /** The rendered points of an accepted answer, or null when it is refused. It fails closed. */
    static List<Point> validate(String raw, PromptFacts facts) {
        try {
            return check(raw, facts);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Point> check(String raw, PromptFacts facts) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_RAW_CHARS) {
            return null;
        }
        String trimmed = raw.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (JacksonException e) {
            return null;
        }
        if (root == null || !root.isObject() || !exactly(root, ROOT_FIELDS)) {
            return null;
        }
        JsonNode findingsNode = root.get("findings");
        JsonNode watchNode = root.get("watch");
        if (!findingsNode.isArray()
                || !watchNode.isArray()
                || findingsNode.size() > MAX_FINDINGS
                || watchNode.size() > MAX_WATCH) {
            return null;
        }
        Map<String, PromptFact> byKey = byKey(facts);
        BriefCatalogue catalogue = BriefCatalogue.INSTANCE;
        List<Finding> findings = new ArrayList<>();
        for (JsonNode node : findingsNode) {
            if (!node.isObject() || !exactly(node, FINDING_FIELDS)) {
                return null;
            }
            List<String> keys = strings(node.get("fact_keys"));
            JsonNode topicNode = node.get("topic");
            JsonNode actionNode = node.get("action");
            JsonNode priorityNode = node.get("priority");
            if (keys == null
                    || keys.isEmpty()
                    || keys.size() > MAX_FACT_KEYS
                    || !topicNode.isString()
                    || !actionNode.isString()
                    || !priorityNode.isString()) {
                return null;
            }
            Topic topic = catalogue.topics.get(topicNode.asString());
            Action action = catalogue.actions.get(actionNode.asString());
            String priority = priorityNode.asString();
            if (topic == null
                    || action == null
                    || !action.topic().equals(topic.code())
                    || !BriefCatalogue.PRIORITIES.contains(priority)
                    || !keysValid(keys, topic, byKey)) {
                return null;
            }
            if (action.calm()
                    && (!"low".equals(priority) || keys.stream().map(byKey::get).anyMatch(PromptFact::isWorse))) {
                return null;
            }
            findings.add(new Finding(keys, topic, action, priority));
        }
        List<String> watch = strings(watchNode);
        if (watch == null || !keysValid(watch, null, byKey)) {
            return null;
        }
        return render(findings, watch, byKey, facts, catalogue);
    }

    private static boolean exactly(JsonNode node, Set<String> fields) {
        Set<String> names = node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        return names.equals(fields);
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isString()) {
                return null;
            }
            out.add(element.asString());
        }
        return out;
    }

    /** Every key exists, none twice, and with a topic each is of a family the topic may cite. */
    private static boolean keysValid(List<String> keys, Topic topic, Map<String, PromptFact> byKey) {
        if (new LinkedHashSet<>(keys).size() != keys.size()) {
            return false;
        }
        for (String key : keys) {
            PromptFact fact = byKey.get(key);
            if (fact == null || (topic != null && !topic.facts().contains(fact.family()))) {
                return false;
            }
        }
        return true;
    }

    private static List<Point> render(
            List<Finding> findings,
            List<String> watch,
            Map<String, PromptFact> byKey,
            PromptFacts facts,
            BriefCatalogue catalogue) {
        List<Point> points = new ArrayList<>();
        List<List<PromptFact>> cited = new ArrayList<>();
        for (Finding f : findings) {
            List<PromptFact> used = f.keys().stream().map(byKey::get).toList();
            String action = fill(f.action().text(), Map.of("where", where(used)));
            points.add(new Point(
                    template(
                            "finding",
                            Map.of(
                                    "priority", Objects.requireNonNullElse(catalogue.priorities.get(f.priority()), ""),
                                    "facts",
                                            used.stream()
                                                    .map(BriefWriter::phrase)
                                                    .collect(Collectors.joining("; ")),
                                    "action", action)),
                    f.keys()));
            cited.add(used);
        }
        if (!watch.isEmpty()) {
            List<PromptFact> used = watch.stream().map(byKey::get).toList();
            points.add(new Point(
                    template(
                            "watch",
                            Map.of(
                                    "facts",
                                    used.stream().map(BriefWriter::phrase).collect(Collectors.joining("; ")))),
                    watch));
            cited.add(used);
        }
        // The second line: every number our renderer wrote is a number of a fact the point cites, counted, so a stray
        // "4" next
        // to a cited count of 4 is caught (review of #735). Each cited fact allows the numbers of its display once.
        for (int i = 0; i < points.size(); i++) {
            Map<String, Integer> allowed = new HashMap<>();
            for (PromptFact f : cited.get(i)) {
                Matcher m = NUMBER.matcher(f.display());
                while (m.find()) {
                    allowed.merge(m.group(), 1, Integer::sum);
                }
            }
            Matcher m = NUMBER.matcher(points.get(i).text());
            while (m.find()) {
                if (allowed.merge(m.group(), -1, Integer::sum) < 0) {
                    return null;
                }
            }
        }
        return points;
    }

    /** The items the cited facts are about, for the action's {@code {{where}}}: {@code " for Item A and Item B"}. */
    static String where(List<PromptFact> used) {
        List<String> aliases = used.stream()
                .map(PromptFact::alias)
                .filter(Objects::nonNull)
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

    private static Map<String, PromptFact> byKey(PromptFacts facts) {
        Map<String, PromptFact> byKey = new LinkedHashMap<>();
        facts.facts().forEach(f -> byKey.put(f.key(), f));
        return byKey;
    }

    private static String template(String name, Map<String, String> values) {
        return fill(BriefCatalogue.INSTANCE.templates.get(name), values);
    }

    /** Fills a reviewed text in one pass: a value that looks like a placeholder is text, never filled again. */
    static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
