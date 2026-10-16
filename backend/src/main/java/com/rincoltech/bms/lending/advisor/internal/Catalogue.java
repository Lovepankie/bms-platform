package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reviewed catalogue the advisor's output is rendered from (ADR-037 decision 5 as amended in issue #223, spec 5.13):
 * the topics the model may choose, the actions it may recommend (each with a stable code, the topic it belongs to and its
 * English text) and the templates our code fills. The model chooses among these by code and writes no text of its own.
 *
 * <p>The file is {@code advisor/catalogue.json}. It is checked when it loads, and a defect fails the application at start
 * rather than at the first answer: no digit anywhere in a template or an action text (a figure is written only by our code
 * from a fact), only the placeholders our code fills ({@code {{where}}} in an action; {@code {{priority}}}, {{@code {{facts}}}},
 * {@code {{action}}}, {@code {{asof}}} and {@code {{range}}} in a template), every action under a topic that exists, and
 * every topic bound to the fact families it may reference.
 */
final class Catalogue {

    static final String RESOURCE = "/advisor/catalogue.json";
    /** The priorities the model may choose, as codes. */
    static final Set<String> PRIORITIES = Set.of("high", "medium", "low");

    record Topic(String code, String label, Set<String> facts) {}

    /** A calm action says no action is needed: it is allowed only at low priority and when no cited change is worse. */
    record Action(String code, String topic, String text, boolean calm) {}

    /** An action text that tells the owner to do nothing; such an action must be marked calm in the file. */
    static final Pattern CALM_WORDING =
            Pattern.compile("(?i)\\b(?:no action|nothing to do|keep (?:this )?under watch|keep watching)\\b");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z]+)\\}\\}");
    private static final Set<String> ACTION_PLACEHOLDERS = Set.of("where");
    private static final Map<String, Set<String>> TEMPLATE_PLACEHOLDERS = Map.of(
            "summary", Set.of("asof", "range"),
            "finding", Set.of("priority", "facts", "action"),
            "watch", Set.of("facts"),
            "fallback", Set.of("facts"),
            "nothing", Set.of());

    private final Map<String, Topic> topics;
    private final Map<String, Action> actions;
    private final Map<String, String> templates;
    private final Map<String, String> priorityLabels;

    private Catalogue(
            Map<String, Topic> topics,
            Map<String, Action> actions,
            Map<String, String> templates,
            Map<String, String> priorityLabels) {
        this.topics = topics;
        this.actions = actions;
        this.templates = templates;
        this.priorityLabels = priorityLabels;
    }

    /** The catalogue of this build. */
    static final Catalogue INSTANCE = load();

    Map<String, Topic> topics() {
        return topics;
    }

    Map<String, Action> actions() {
        return actions;
    }

    String template(String name) {
        return templates.get(name);
    }

    String priorityLabel(String priority) {
        return priorityLabels.get(priority);
    }

    /**
     * The family a fact belongs to, for binding it to a topic: a change is its figure's family, and a branch or product fact
     * is {@code branch.} or {@code product.} whatever its alias ({@code branch_1.par30} is {@code branch.par30}).
     */
    static String base(PromptFact f) {
        String key = f.isChange() ? f.deltaOf() : f.key();
        return key.replaceFirst("^(branch|product)_[a-z0-9]+\\.", "$1.");
    }

    private static Catalogue load() {
        try (InputStream in = Catalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("the advisor catalogue cannot be read: " + e.getMessage(), e);
        }
    }

    /** Parses and checks a catalogue file; a duplicate key, topic code or action code is a defect, never a last-one-wins. */
    static Catalogue parse(String json) {
        try {
            JsonNode root = JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build()
                    .readTree(json);
            Map<String, String> templates = new LinkedHashMap<>();
            root.get("templates")
                    .properties()
                    .forEach(e -> templates.put(e.getKey(), e.getValue().asString()));
            Map<String, String> priorities = new LinkedHashMap<>();
            root.get("priorities")
                    .properties()
                    .forEach(e -> priorities.put(e.getKey(), e.getValue().asString()));
            Map<String, Topic> topics = new LinkedHashMap<>();
            for (JsonNode t : root.get("topics")) {
                Set<String> facts = new LinkedHashSet<>();
                t.get("facts").forEach(f -> facts.add(f.asString()));
                String code = t.get("code").asString();
                require(!topics.containsKey(code), "duplicate topic code " + code);
                topics.put(code, new Topic(code, t.get("label").asString(), Collections.unmodifiableSet(facts)));
            }
            Map<String, Action> actions = new LinkedHashMap<>();
            for (JsonNode a : root.get("actions")) {
                String code = a.get("code").asString();
                require(!actions.containsKey(code), "duplicate action code " + code);
                JsonNode calm = a.get("calm");
                actions.put(
                        code,
                        new Action(
                                code,
                                a.get("topic").asString(),
                                a.get("text").asString(),
                                calm != null && calm.asBoolean()));
            }
            check(templates, priorities, topics, actions);
            // Unmodifiable views that keep the order of the file: the prompt lists topics and actions in that order.
            return new Catalogue(
                    Collections.unmodifiableMap(topics),
                    Collections.unmodifiableMap(actions),
                    Collections.unmodifiableMap(templates),
                    Collections.unmodifiableMap(priorities));
        } catch (RuntimeException e) {
            throw new IllegalStateException("the advisor catalogue is not valid: " + e.getMessage(), e);
        }
    }

    private static void check(
            Map<String, String> templates,
            Map<String, String> priorities,
            Map<String, Topic> topics,
            Map<String, Action> actions) {
        require(priorities.keySet().equals(PRIORITIES), "priorities must be exactly high, medium and low");
        require(
                templates.keySet().equals(TEMPLATE_PLACEHOLDERS.keySet()),
                "templates must be exactly " + TEMPLATE_PLACEHOLDERS.keySet());
        templates.forEach((name, text) -> checkText("template " + name, text, TEMPLATE_PLACEHOLDERS.get(name)));
        priorities.forEach((code, label) -> checkText("priority " + code, label, Set.of()));
        require(topics.size() >= 6, "at least six topics");
        require(actions.size() >= 12 && actions.size() <= 20, "twelve to twenty actions");
        topics.values().forEach(t -> {
            require(t.code().matches("[a-z_]{3,40}"), "topic code " + t.code());
            require(!t.facts().isEmpty(), "topic " + t.code() + " has no facts");
            checkText("topic " + t.code(), t.label(), Set.of());
        });
        actions.values().forEach(a -> {
            require(a.code().matches("[A-Z_]{3,50}"), "action code " + a.code());
            require(topics.containsKey(a.topic()), "action " + a.code() + " has an unknown topic");
            checkText("action " + a.code(), a.text(), ACTION_PLACEHOLDERS);
            require(
                    a.calm() == CALM_WORDING.matcher(a.text()).find(),
                    "action " + a.code() + " says no action is needed if and only if it is marked calm");
        });
        topics.keySet()
                .forEach(t -> require(
                        actions.values().stream().anyMatch(a -> a.topic().equals(t)), "topic " + t + " has no action"));
    }

    /** No digit, no placeholder but the allowed ones, no braces left over, no markup. */
    static void checkText(String what, String text, Set<String> allowedPlaceholders) {
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            require(allowedPlaceholders.contains(m.group(1)), what + " uses the placeholder {{" + m.group(1) + "}}");
        }
        String rest = m.reset().replaceAll(" ");
        require(!rest.matches("(?s).*[\\p{Nd}\\p{No}\\p{Nl}].*"), what + " holds a digit");
        require(!rest.contains("{") && !rest.contains("}"), what + " holds a stray brace");
        require(!rest.matches("(?s).*[<>\\[\\]@].*"), what + " holds markup");
        require(text.equals(text.strip()) && !text.isBlank(), what + " is blank or padded");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }

    /** The topic codes, in catalogue order, for the prompt. */
    List<String> topicCodes() {
        return List.copyOf(topics.keySet());
    }
}
