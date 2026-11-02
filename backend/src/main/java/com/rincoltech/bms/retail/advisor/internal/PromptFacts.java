package com.rincoltech.bms.retail.advisor.internal;

import com.rincoltech.bms.retail.reports.RetailBriefFacts.Fact;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.FactSet;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.ItemName;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.Kind;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.Polarity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shop facts as a model sees them (issue #422): a flat list in which every item figure speaks of {@code Item A} and every
 * change is a fact of its own that a point can cite, so the model quotes a movement and never computes one. No item name is in
 * a key or a label; the names stay in {@link #names}, which maps the rendered text back for the person who asked and is never
 * put in a prompt or a log.
 */
record PromptFacts(LocalDate asOf, String currency, List<PromptFact> facts, Map<String, String> names) {

    /**
     * One line of the prompt.
     *
     * @param key {@code sales.yesterday}, {@code item_a.sales_week}, or a change: {@code sales.yesterday.change}
     * @param family the key without the alias and the change suffix ({@code item.sales_week}): what a topic binds to
     * @param alias {@code Item A}, or null for a figure of the whole business
     * @param deltaOf the key of the figure this is the change of, or null for a figure
     * @param against for a change, what it compares with in words
     */
    record PromptFact(
            String key,
            String family,
            String label,
            String alias,
            Kind kind,
            Polarity polarity,
            long value,
            String display,
            String deltaOf,
            String against) {

        boolean isChange() {
            return deltaOf != null;
        }

        /** A change whose movement is the bad one for its figure. */
        boolean isWorse() {
            if (!isChange() || value == 0) {
                return false;
            }
            return switch (polarity) {
                case HIGHER_IS_BETTER -> value < 0;
                case HIGHER_IS_WORSE -> value > 0;
                case NEUTRAL -> false;
            };
        }
    }

    /** At most this many items are named: Item A to Item Z. */
    static final int MAX_ITEMS = 26;

    static PromptFacts of(FactSet set) {
        Map<String, String> aliasOf = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (ItemName n : set.names()) {
            if (!aliasOf.containsKey(n.item()) && aliasOf.size() < MAX_ITEMS) {
                String alias = "Item " + (char) ('A' + aliasOf.size());
                aliasOf.put(n.item(), alias);
                names.put(alias, n.name());
            }
        }
        List<PromptFact> out = new ArrayList<>();
        for (Fact f : set.facts()) {
            String alias = f.item() == null ? null : aliasOf.get(f.item());
            if (f.item() != null && alias == null) {
                // An item the fact set does not name cannot be aliased: it is left out, never sent under its id.
                continue;
            }
            String key = alias == null
                    ? f.key()
                    : alias.toLowerCase().replace(' ', '_')
                            + f.key().substring(f.key().indexOf('.'));
            String label = alias == null ? f.label() : alias + ": " + f.label();
            out.add(new PromptFact(
                    key, f.key(), label, alias, f.kind(), f.polarity(), f.value(), f.display(), null, null));
            if (f.change() != null) {
                out.add(new PromptFact(
                        key + ".change",
                        f.key(),
                        label + ", change on " + f.change().against(),
                        alias,
                        f.kind(),
                        f.polarity(),
                        f.change().change(),
                        f.change().display(),
                        key,
                        f.change().against()));
            }
        }
        return new PromptFacts(set.asOf(), set.currency(), List.copyOf(out), Map.copyOf(names));
    }

    private static final Pattern ALIAS = Pattern.compile("\\bItem [A-Z]\\b");

    /** The text with every alias replaced by the item's name in one pass (a name that reads like an alias stays as it is). */
    String toNames(String text) {
        Matcher m = ALIAS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(names.getOrDefault(m.group(), m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    @Override
    public String toString() {
        return "PromptFacts[asOf=" + asOf + ", facts=" + facts.size() + ", items=" + names.size() + "]";
    }
}
