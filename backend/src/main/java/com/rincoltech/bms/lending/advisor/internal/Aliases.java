package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Group;
import com.rincoltech.bms.lending.insights.PortfolioFacts.GroupName;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The alias mapper (spec 5.4): branches become {@code Branch 1}, {@code Branch 2}, products become
 * {@code Product A}, {@code Product B}, assigned for one fact set in the order of the groups' ids (so the same groups
 * get the same aliases on every run, and the order of a name or of a size tells nothing), and mapped back to names
 * only in the text shown to the person who asked, by whole word, longest alias first, in one pass (a name that
 * contains another alias is not mapped again). There is no officer alias: an officer is a named staff member and the
 * officer dimension is not in the first release.
 *
 * <p>The names are held here only for the mapping back. {@link #toString} shows counts, so that a log line cannot carry
 * a name, and nothing in this class puts a name anywhere but the return value of {@link #toNames}.
 */
final class Aliases {

    private final Map<Group, String> aliasOf;
    private final Map<String, String> nameOf;
    private final Pattern known;
    private final List<String> labels;

    private Aliases(Map<Group, String> aliasOf, Map<String, String> nameOf) {
        this.aliasOf = aliasOf;
        this.nameOf = nameOf;
        this.labels = aliasOf.values().stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
        this.known = labels.isEmpty()
                ? null
                : Pattern.compile("(?<![\\p{L}\\p{N}])(?:"
                        + labels.stream().map(Aliases::regex).collect(Collectors.joining("|")) + ")(?![\\p{L}\\p{N}])");
    }

    static Aliases none() {
        return new Aliases(Map.of(), Map.of());
    }

    /** Assigns aliases to every group of the fact set. */
    static Aliases of(FactSet facts) {
        Map<Group, String> aliasOf = new LinkedHashMap<>();
        Map<String, String> nameOf = new LinkedHashMap<>();
        for (Dimension dimension : Dimension.values()) {
            List<GroupName> groups = facts.names().stream()
                    .filter(g -> g.group().dimension() == dimension)
                    .sorted(Comparator.comparing(g -> g.group().id()))
                    .toList();
            int i = 0;
            for (GroupName g : groups) {
                if (aliasOf.containsKey(g.group())) {
                    continue;
                }
                i++;
                String alias = dimension == Dimension.BRANCH ? "Branch " + i : "Product " + letters(i);
                aliasOf.put(g.group(), alias);
                nameOf.put(alias, g.name());
            }
        }
        return new Aliases(aliasOf, nameOf);
    }

    /** 1 is A, 26 is Z, 27 is AA. */
    static String letters(int n) {
        StringBuilder s = new StringBuilder();
        int rest = n;
        while (rest > 0) {
            rest--;
            s.insert(0, (char) ('A' + rest % 26));
            rest /= 26;
        }
        return s.toString();
    }

    /** The alias of a group, or null when the group is not in this fact set. */
    String aliasOf(Group group) {
        return aliasOf.get(group);
    }

    /** The aliases, longest first. */
    List<String> labels() {
        return labels;
    }

    /** The real names, for a check that none of them reaches a prompt or comes back in an answer. */
    List<String> names() {
        return new ArrayList<>(nameOf.values());
    }

    /** Matches any known alias as a whole word; null when there are none. */
    Pattern pattern() {
        return known;
    }

    /**
     * The text with every alias replaced by the group's name, for the person who asked. The word {@code Branch} or
     * {@code Product} may be in any case; the number or letter is exact, so {@code Branch 1} never matches inside
     * {@code Branch 10} and a sentence's "a product A customer" is not read as an alias unless it is spelled so.
     */
    String toNames(String text) {
        String clean = Text.display(text);
        if (known == null) {
            return clean;
        }
        Matcher m = known.matcher(clean);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = nameOf.get(canonical(m.group()));
            m.appendReplacement(out, Matcher.quoteReplacement(name == null ? m.group() : neutral(name)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A real name as it may be written into a sentence: a name is the tenant's own text and may hold markup, a line break or
     * markdown, so the characters that could start any of them are removed (angle brackets, backticks, asterisks, tildes,
     * pipes, square brackets, backslashes, underscores; an ampersand becomes "and" and a double quote a single one) and
     * every run of white space or control characters becomes one space.
     */
    static String neutral(String name) {
        String s = name.replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ")
                .replaceAll("[<>`*~|\\[\\]\\\\_]", "")
                .replace("&", " and ")
                .replace('"', '\'')
                .replaceAll(" {2,}", " ")
                .strip();
        return s.isEmpty() ? "(unnamed)" : s;
    }

    /** {@code BRANCH   1} as {@code Branch 1}: the word capitalised, spaces collapsed. */
    private static String canonical(String found) {
        String one = found.replaceAll("[\\s\\p{Z}]+", " ");
        int space = one.indexOf(' ');
        return Character.toUpperCase(one.charAt(0)) + one.substring(1, space).toLowerCase() + one.substring(space);
    }

    /** The word part in any case, the rest exact, spaces flexible. */
    private static String regex(String alias) {
        int space = alias.indexOf(' ');
        return "(?i:" + Pattern.quote(alias.substring(0, space)) + ")[\\s\\p{Z}]+"
                + Pattern.quote(alias.substring(space + 1));
    }

    @Override
    public String toString() {
        return "Aliases[" + aliasOf.size() + "]";
    }
}
