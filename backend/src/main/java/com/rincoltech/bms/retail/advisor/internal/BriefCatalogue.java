package com.rincoltech.bms.retail.advisor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reviewed catalogue the retail brief is written from ({@code retail-advisor/catalogue.json}, issue #422): the topics the
 * model may choose, the actions it may recommend (a stable code, its topic and its English text) and the templates our code
 * fills. It is checked when it loads, the rules of the lending catalogue (ADR-037 as amended in #223): no digit in any text, only
 * the placeholders our code fills, every action under a topic that exists, an action that says no action is needed marked calm
 * and the other way round, and every topic bound to the fact families it may cite. A defect fails the application at start.
 */
final class BriefCatalogue {

    static final String RESOURCE = "/retail-advisor/catalogue.json";
    static final Set<String> PRIORITIES = Set.of("high", "medium", "low");

    record Topic(String code, String label, Set<String> facts) {}

    record Action(String code, String topic, String text, boolean calm) {}

    static final Pattern CALM_WORDING =
            Pattern.compile("(?i)\\b(?:no action|nothing to do|keep (?:this )?under watch|keep watching)\\b");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z]+)\\}\\}");
    private static final Map<String, Set<String>> TEMPLATE_PLACEHOLDERS = Map.of(
            "summary", Set.of("asof"),
            "finding", Set.of("priority", "facts", "action"),
            "watch", Set.of("facts"),
            "fallback", Set.of("facts"),
            "nothing", Set.of());

    final Map<String, Topic> topics;
    final Map<String, Action> actions;
    final Map<String, String> templates;
    final Map<String, String> priorities;

    private BriefCatalogue(
            Map<String, Topic> topics,
            Map<String, Action> actions,
            Map<String, String> templates,
            Map<String, String> priorities) {
        this.topics = topics;
        this.actions = actions;
        this.templates = templates;
        this.priorities = priorities;
    }

    static final BriefCatalogue INSTANCE = load();

    private static BriefCatalogue load() {
        try (InputStream in = BriefCatalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("the retail brief catalogue cannot be read: " + e.getMessage(), e);
        }
    }

    /** Parses and checks a catalogue file; a duplicate key or code is a defect, never a last-one-wins. */
    static BriefCatalogue parse(String json) {
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
            return new BriefCatalogue(
                    Collections.unmodifiableMap(topics),
                    Collections.unmodifiableMap(actions),
                    Collections.unmodifiableMap(templates),
                    Collections.unmodifiableMap(priorities));
        } catch (RuntimeException e) {
            throw new IllegalStateException("the retail brief catalogue is not valid: " + e.getMessage(), e);
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
        require(!topics.isEmpty() && !actions.isEmpty(), "topics and actions");
        topics.values().forEach(t -> {
            require(t.code().matches("[a-z_]{3,40}"), "topic code " + t.code());
            require(!t.facts().isEmpty(), "topic " + t.code() + " has no facts");
            checkText("topic " + t.code(), t.label(), Set.of());
        });
        actions.values().forEach(a -> {
            require(a.code().matches("[A-Z_]{3,50}"), "action code " + a.code());
            require(topics.containsKey(a.topic()), "action " + a.code() + " has an unknown topic");
            checkText("action " + a.code(), a.text(), Set.of("where"));
            require(
                    a.calm() == CALM_WORDING.matcher(a.text()).find(),
                    "action " + a.code() + " says no action is needed if and only if it is marked calm");
        });
        topics.keySet()
                .forEach(t -> require(
                        actions.values().stream().anyMatch(a -> a.topic().equals(t)), "topic " + t + " has no action"));
    }

    /** No digit, no placeholder but the allowed ones, no stray brace, no markup, no dash. */
    static void checkText(String what, String text, Set<String> allowedPlaceholders) {
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            require(allowedPlaceholders.contains(m.group(1)), what + " uses the placeholder {{" + m.group(1) + "}}");
        }
        String rest = m.reset().replaceAll(" ");
        require(!rest.matches("(?s).*[\\p{Nd}\\p{No}\\p{Nl}].*"), what + " holds a digit");
        require(!rest.contains("{") && !rest.contains("}"), what + " holds a stray brace");
        require(!rest.matches("(?s).*[<>\\[\\]@\\u2013\\u2014].*"), what + " holds markup or a dash");
        require(text.equals(text.strip()) && !text.isBlank(), what + " is blank or padded");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }
}
