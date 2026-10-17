package com.rincoltech.bms.lending.advisor.internal;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds every number an answer states (spec 5.6), in whatever form: digits in any script, thousands separators
 * ({@code 1,200,000}, {@code 1 200 000}, {@code 1.200.000}), decimals, signs, compact forms ({@code 1.2m},
 * {@code 1.2 million}, {@code 500k}), percentages, currency codes before or after, number words
 * ({@code twenty-five}, {@code one hundred and twenty}, {@code two million}), fractions ({@code half},
 * {@code two thirds}) and multipliers ({@code double}). It runs on {@link Text#normalise normalised} text.
 *
 * <p>What is not a number the model stated, and is set aside before the search: the dates the answer is allowed to name
 * (any form), the aliases of the groups, the names of the cited facts (which contain {@code PAR 30} and so on), the
 * names {@code PAR 1} to {@code PAR 90}, list markers ({@code 1.}, {@code (2)}) and ordinals ({@code 3rd}). A number
 * glued to letters ({@code Q3}) is a code, not a figure. An alias that does not exist ({@code Branch 7} when there are
 * three) is reported, not ignored.
 *
 * <p>A reading that is ambiguous gives more than one value ({@code 1.200} is 1.2 or 1200), and the token is accepted if
 * any reading matches a fact; a reading that cannot be told is never guessed in the model's favour beyond that.
 */
final class NumberExtractor {

    enum Sign {
        NONE,
        PLUS,
        MINUS
    }

    enum Unit {
        NONE,
        MONEY,
        PERCENT,
        BASIS_POINTS,
        DAYS,
        HOURS,
        /** A number followed by something counted: {@code 12 loans}. */
        COUNT
    }

    /**
     * One stated number.
     *
     * @param values the readings, as written (scale is the number of decimals the writer gave)
     * @param magnitude 1, or the factor of {@code k}, {@code million} and so on
     * @param currency {@code UGX}, {@code KES}, {@code TZS}, {@code USD}, {@code SHILLING} (any shilling) or null
     * @param fraction true for {@code half}, {@code two thirds}: the value is a ratio
     * @param multiplier true for {@code double}, {@code twice}: the value is a factor
     * @param yearLike a four-digit whole number that stands as the year of an allowed date ({@code in 2026})
     * @param start where it begins in the normalised text (every set-aside step keeps the length of the text)
     * @param end where it ends
     */
    record Token(
            String text,
            List<BigDecimal> values,
            Sign sign,
            Unit unit,
            String currency,
            BigDecimal magnitude,
            boolean fraction,
            boolean multiplier,
            boolean yearLike,
            int start,
            int end) {

        boolean compact() {
            return magnitude.compareTo(BigDecimal.ONE) != 0;
        }
    }

    record Extraction(List<Token> tokens, List<String> unknownAliases) {}

    private static final String MONTH =
            "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    private static final Pattern ISO = Pattern.compile("(?<![\\d.,-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern DAY_FIRST =
            Pattern.compile("(?<![\\d.,])(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})(?!\\d)");
    private static final Pattern DAY_MONTH = Pattern.compile(
            "(?<![\\d.,])(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH + "\\b\\.?(?:,?\\s+(\\d{4})(?!\\d))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?<!\\p{L})" + MONTH + "\\b\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?!\\d)(?:,?\\s+(\\d{4})(?!\\d))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_YEAR =
            Pattern.compile("(?<!\\p{L})" + MONTH + "\\b\\.?,?\\s+(\\d{4})(?!\\d)", Pattern.CASE_INSENSITIVE);

    private static final Pattern UNKNOWN_ALIAS =
            Pattern.compile("(?<![\\p{L}\\p{N}])(?:(?i:branch)\\s+\\d+|(?i:product)\\s+[A-Z]{1,2})(?![\\p{L}\\p{N}])");
    private static final Pattern PAR_NAME = Pattern.compile("(?i)(?<![\\p{L}\\d])par[\\s-]?(?:1|30|60|90)(?![\\d])");
    private static final Pattern LIST_MARK = Pattern.compile("(?m)^[ \\t]*\\(?\\d{1,2}[.)][ \\t]+");
    private static final Pattern ORDINAL = Pattern.compile("(?i)(?<![\\d.,])\\d+(?:st|nd|rd|th)(?![\\p{L}\\d])");

    /** A word that makes the four digits after it a year: {@code in 2026}, {@code since 2026}. */
    private static final Pattern YEAR_WORD =
            Pattern.compile("(?i)(?<![\\p{L}])(?:in|during|since|of|year|fy|from|until|by|for|to)\\s+$");

    private static final Pattern COUNT_NOUN = Pattern.compile(
            "(?i)\\s+(?:[a-z-]+\\s+)?(?:loans?|members?|borrowers?|customers?|clients?|accounts?|applications?|"
                    + "disbursements?|repayments?|payments?|branch(?:es)?|products?)(?![\\p{L}])");

    private static final Pattern CORE = Pattern.compile("(?<![\\d.,])(?:"
            + "\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?"
            + "|\\d{1,3}(?:\\.\\d{3}){2,}(?:,\\d+)?"
            + "|\\d{1,3}(?: \\d{3})+(?:\\.\\d+)?"
            + "|\\d+(?:[.,]\\d+)?"
            + "|\\.\\d+"
            + ")(?!\\d)");

    private static final Pattern CURRENCY_BEFORE =
            Pattern.compile("(?i)(?:(?<![\\p{L}])(ugx|ush|shs?|kes|tzs|usd)\\.?\\s?|([$\\u20AC\\u00A3])\\s?)$");
    private static final Pattern MAGNITUDE_ATTACHED = Pattern.compile("(?i)(bn|mn|[kmb])(?![\\p{L}\\d])");
    private static final Pattern MAGNITUDE_WORD =
            Pattern.compile("(?i)\\s(thousand|million|billion|trillion)s?(?![\\p{L}])");
    private static final Pattern PERCENT_UNIT =
            Pattern.compile("(?i)\\s?(?:%|(?:percentage\\s+points?|per\\s?cent|percent|points?|pp|pts)(?![\\p{L}]))");
    private static final Pattern BP_UNIT = Pattern.compile("(?i)\\s?(?:basis\\s+points?|bps?)(?![\\p{L}])");
    private static final Pattern DAYS_UNIT = Pattern.compile("(?i)\\s?days?(?![\\p{L}])");
    private static final Pattern HOURS_UNIT = Pattern.compile("(?i)\\s?(?:hours?|hrs?)(?![\\p{L}])");
    private static final Pattern CURRENCY_AFTER =
            Pattern.compile("(?i)\\s?(ugx|ush|shillings?|shs?|kes|tzs|usd|dollars?)(?![\\p{L}])");

    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("zero", 0),
            Map.entry("one", 1),
            Map.entry("two", 2),
            Map.entry("three", 3),
            Map.entry("four", 4),
            Map.entry("five", 5),
            Map.entry("six", 6),
            Map.entry("seven", 7),
            Map.entry("eight", 8),
            Map.entry("nine", 9),
            Map.entry("ten", 10),
            Map.entry("eleven", 11),
            Map.entry("twelve", 12),
            Map.entry("thirteen", 13),
            Map.entry("fourteen", 14),
            Map.entry("fifteen", 15),
            Map.entry("sixteen", 16),
            Map.entry("seventeen", 17),
            Map.entry("eighteen", 18),
            Map.entry("nineteen", 19),
            Map.entry("twenty", 20),
            Map.entry("thirty", 30),
            Map.entry("forty", 40),
            Map.entry("fifty", 50),
            Map.entry("sixty", 60),
            Map.entry("seventy", 70),
            Map.entry("eighty", 80),
            Map.entry("ninety", 90));
    private static final String WORD_ALTERNATIVES =
            String.join("|", WORDS.keySet()) + "|hundred|thousand|million|billion|dozen";
    private static final String DIGIT_WORDS = "zero|one|two|three|four|five|six|seven|eight|nine";
    private static final Pattern WORD_RUN = Pattern.compile("(?i)(?<![\\p{L}])(?<main>(?:" + WORD_ALTERNATIVES
            + ")(?:(?:[-\\s]+|\\s+and\\s+)(?:" + WORD_ALTERNATIVES + "))*)(?<point>\\s+point(?:\\s+(?:" + DIGIT_WORDS
            + "))+)?(?![\\p{L}])");
    private static final Pattern SLASH_FRACTION =
            Pattern.compile("(?<![\\d/.,])(\\d{1,3})\\s?/\\s?(\\d{1,3})(?![\\d/])");
    private static final Pattern ROMAN = Pattern.compile(
            "(?<![\\p{L}\\d])(?=[IVXLCDM]{2,}(?![\\p{L}\\d]))M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})(?![\\p{L}\\d])");
    private static final Pattern FRACTION =
            Pattern.compile("(?i)(?<![\\p{L}])(?:(a|one|two|three|four|five|six|seven|eight|nine|ten)[-\\s]+)?"
                    + "(halves|half|thirds|third|quarters|quarter|fifths|fifth)(?![\\p{L}])");
    private static final Pattern MULTIPLIER =
            Pattern.compile("(?i)(?<![\\p{L}])(double[sd]?|doubling|triple[sd]?|tripling|twice|thrice)(?![\\p{L}])");
    private static final Pattern COUNTED_NOUN = Pattern.compile(
            "(?is)\\s+(?:[a-z]+\\s+)?(?:loans?|members?|borrowers?|branch(?:es)?|products?|days?|weeks?|months?|"
                    + "customers?|clients?|accounts?|payments?|repayments?|applications?|disbursements?|groups?)\\b.*");

    private NumberExtractor() {}

    /** The text with every match of the pattern replaced by spaces of the same length, so offsets stay valid. */
    static String blank(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start()).append(" ".repeat(m.end() - m.start()));
            last = m.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    /**
     * @param normalised the text, already {@link Text#normalise normalised}
     * @param dates the dates the answer may name
     * @param aliases the aliases of this fact set
     * @param labels the names of the facts the text cites, which may carry digits
     */
    static Extraction extract(String normalised, Set<LocalDate> dates, Aliases aliases, Collection<String> labels) {
        String s = stripDates(normalised, dates);
        if (aliases.pattern() != null) {
            s = blank(aliases.pattern(), s);
        }
        List<String> unknown = new ArrayList<>();
        Matcher u = UNKNOWN_ALIAS.matcher(s);
        while (u.find()) {
            unknown.add(u.group().replaceAll("\\s+", " "));
        }
        s = blank(UNKNOWN_ALIAS, s);
        // Only a name that carries a digit can hide a figure ("PAR 30"); the others stay visible to the unit and noun
        // checks.
        List<String> byLength = labels.stream()
                .filter(label -> label.chars().anyMatch(Character::isDigit))
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String label : byLength) {
            s = blank(
                    Pattern.compile(
                            "(?<![\\p{L}\\d])" + Pattern.quote(label).replace(" ", "\\E\\s+\\Q") + "(?![\\p{L}\\d])",
                            Pattern.CASE_INSENSITIVE),
                    s);
        }
        s = blank(PAR_NAME, s);
        s = blank(LIST_MARK, s);
        s = blank(ORDINAL, s);

        Set<Integer> years = new java.util.HashSet<>();
        dates.forEach(d -> years.add(d.getYear()));
        List<Token> tokens = new ArrayList<>();
        char[] masked = s.toCharArray();
        slashFractions(s, tokens, masked);
        digits(new String(masked), years, tokens, masked);
        String rest = new String(masked);
        romans(rest, tokens, masked);
        rest = new String(masked);
        fractions(rest, tokens, masked);
        rest = new String(masked);
        multipliers(rest, tokens, masked);
        rest = new String(masked);
        words(rest, tokens);
        return new Extraction(tokens, unknown);
    }

    // ---- Dates ------------------------------------------------------------------------------

    static String stripDates(String text, Set<LocalDate> allowed) {
        String s = text;
        s = replaceDates(s, ISO, m -> full(allowed, m.group(1), m.group(2), m.group(3)));
        s = replaceDates(s, DAY_FIRST, m -> full(allowed, m.group(3), m.group(2), m.group(1)));
        s = replaceDates(s, DAY_MONTH, m -> named(allowed, m.group(1), m.group(2), m.group(3)));
        s = replaceDates(s, MONTH_DAY, m -> named(allowed, m.group(2), m.group(1), m.group(3)));
        s = replaceDates(s, MONTH_YEAR, m -> monthYear(allowed, m.group(1), m.group(2)));
        return s;
    }

    private interface DateTest {
        boolean ok(Matcher m);
    }

    private static String replaceDates(String s, Pattern p, DateTest test) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            boolean ok;
            try {
                ok = test.ok(m);
            } catch (DateTimeException | NumberFormatException e) {
                ok = false;
            }
            m.appendReplacement(
                    out,
                    ok
                            ? Matcher.quoteReplacement(" ".repeat(m.group().length()))
                            : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean full(Set<LocalDate> allowed, String y, String mo, String d) {
        return allowed.contains(LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d)));
    }

    private static int month(String name) {
        String n = name.toLowerCase(Locale.ROOT).substring(0, 3);
        return List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
                        .indexOf(n)
                + 1;
    }

    private static boolean named(Set<LocalDate> allowed, String day, String monthName, String year) {
        int d = Integer.parseInt(day);
        int mo = month(monthName);
        return allowed.stream()
                .anyMatch(a -> a.getDayOfMonth() == d
                        && a.getMonthValue() == mo
                        && (year == null || a.getYear() == Integer.parseInt(year)));
    }

    private static boolean monthYear(Set<LocalDate> allowed, String monthName, String year) {
        int mo = month(monthName);
        int y = Integer.parseInt(year);
        return allowed.stream().anyMatch(a -> a.getMonthValue() == mo && a.getYear() == y);
    }

    // ---- Digits -----------------------------------------------------------------------------

    private static void digits(String s, Set<Integer> years, List<Token> tokens, char[] masked) {
        Matcher m = CORE.matcher(s);
        int from = 0;
        while (m.find(from)) {
            from = m.end();
            int start = m.start();
            int end = m.end();
            boolean letterBefore = start > 0 && Character.isLetter(s.charAt(start - 1));
            Sign sign = Sign.NONE;
            String currency = null;
            int tokenStart = start;
            // A sign directly before the number, then a currency before that; or a currency, then a sign.
            int cursor = start;
            if (cursor > 0 && isSign(s.charAt(cursor - 1)) && signStands(s, cursor - 1)) {
                sign = s.charAt(cursor - 1) == '-' ? Sign.MINUS : Sign.PLUS;
                cursor--;
                tokenStart = cursor;
            }
            Matcher c = CURRENCY_BEFORE.matcher(s.substring(Math.max(0, cursor - 8), cursor));
            if (c.find()) {
                currency = currencyOf(c.group(1) != null ? c.group(1) : c.group(2));
                cursor = cursor - (c.end() - c.start());
                tokenStart = cursor;
                letterBefore = false;
                if (sign == Sign.NONE && cursor > 0 && isSign(s.charAt(cursor - 1)) && signStands(s, cursor - 1)) {
                    sign = s.charAt(cursor - 1) == '-' ? Sign.MINUS : Sign.PLUS;
                    tokenStart = cursor - 1;
                }
            }
            if (letterBefore) {
                continue; // Q3, B2B, a code
            }
            List<BigDecimal> values = readings(m.group());
            Suffix suffix = suffix(s, end);
            if (suffix.currency != null) {
                currency = suffix.currency;
            }
            Unit unit = suffix.unit;
            if (currency != null && (unit == Unit.NONE || unit == Unit.COUNT)) {
                unit = Unit.MONEY;
            }
            boolean yearLike = suffix.magnitude.compareTo(BigDecimal.ONE) == 0
                    && unit == Unit.NONE
                    && m.group().matches("\\d{4}")
                    && years.contains(Integer.parseInt(m.group()))
                    && YEAR_WORD
                            .matcher(s.substring(Math.max(0, start - 12), start))
                            .find()
                    && !COUNT_NOUN.matcher(s.substring(end)).lookingAt();
            tokens.add(new Token(
                    s.substring(tokenStart, suffix.end).strip(),
                    values,
                    sign,
                    unit,
                    currency,
                    suffix.magnitude,
                    false,
                    false,
                    yearLike,
                    tokenStart,
                    suffix.end));
            for (int i = tokenStart; i < suffix.end; i++) {
                masked[i] = ' ';
            }
            from = Math.max(from, suffix.end);
        }
    }

    private static boolean isSign(char ch) {
        return ch == '+' || ch == '-';
    }

    /** A sign belongs to the number only at the start of a phrase: not after a letter or digit, as in {@code 5-10}. */
    private static boolean signStands(String s, int at) {
        if (at == 0) {
            return true;
        }
        char before = s.charAt(at - 1);
        return !(Character.isLetterOrDigit(before) || before == ')' || before == '%');
    }

    private static String currencyOf(String raw) {
        String c = raw.toLowerCase(Locale.ROOT).replace(".", "");
        return switch (c) {
            case "ugx", "ush", "sh", "shs" -> "UGX";
            case "kes" -> "KES";
            case "tzs" -> "TZS";
            case "usd", "$", "dollar", "dollars" -> "USD";
            case "shilling", "shillings" -> "SHILLING";
            default -> "OTHER";
        };
    }

    private record Suffix(int end, BigDecimal magnitude, Unit unit, String currency) {}

    private static Suffix suffix(String s, int end) {
        int i = end;
        BigDecimal magnitude = BigDecimal.ONE;
        Matcher m = MAGNITUDE_ATTACHED.matcher(s).region(i, s.length());
        if (m.lookingAt()) {
            magnitude = magnitudeOf(m.group(1));
            i = m.end();
        } else {
            m = MAGNITUDE_WORD.matcher(s).region(i, s.length());
            if (m.lookingAt()) {
                magnitude = magnitudeOf(m.group(1));
                i = m.end();
            }
        }
        for (Object[] unit : new Object[][] {
            {PERCENT_UNIT, Unit.PERCENT}, {BP_UNIT, Unit.BASIS_POINTS}, {DAYS_UNIT, Unit.DAYS}, {HOURS_UNIT, Unit.HOURS}
        }) {
            Matcher u = ((Pattern) unit[0]).matcher(s).region(i, s.length());
            if (u.lookingAt()) {
                return new Suffix(u.end(), magnitude, (Unit) unit[1], null);
            }
        }
        Matcher c = CURRENCY_AFTER.matcher(s).region(i, s.length());
        if (c.lookingAt()) {
            return new Suffix(c.end(), magnitude, Unit.MONEY, currencyOf(c.group(1)));
        }
        // Followed by something counted: a count, which only a count fact can account for. Not consumed.
        if (COUNT_NOUN.matcher(s).region(i, s.length()).lookingAt()) {
            return new Suffix(i, magnitude, Unit.COUNT, null);
        }
        return new Suffix(i, magnitude, Unit.NONE, null);
    }

    private static BigDecimal magnitudeOf(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "k", "thousand" -> BigDecimal.valueOf(1_000);
            case "m", "mn", "million" -> BigDecimal.valueOf(1_000_000);
            case "b", "bn", "billion" -> BigDecimal.valueOf(1_000_000_000L);
            case "trillion" -> BigDecimal.valueOf(1_000_000_000_000L);
            default -> BigDecimal.ONE;
        };
    }

    /** The readings of a written number; more than one when the separators cannot say which they are. */
    static List<BigDecimal> readings(String raw) {
        if (raw.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
            return List.of(new BigDecimal(raw.replace(",", "")));
        }
        if (raw.matches("\\d{1,3}(\\.\\d{3}){2,}(,\\d+)?")) {
            return List.of(new BigDecimal(raw.replace(".", "").replace(',', '.')));
        }
        if (raw.matches("\\d{1,3}( \\d{3})+(\\.\\d+)?")) {
            return List.of(new BigDecimal(raw.replace(" ", "")));
        }
        if (raw.matches("\\d{1,3}\\.\\d{3}")) {
            return List.of(new BigDecimal(raw), new BigDecimal(raw.replace(".", "")));
        }
        if (raw.matches("\\d+,\\d+")) {
            return List.of(new BigDecimal(raw.replace(',', '.')));
        }
        return List.of(new BigDecimal(raw.startsWith(".") ? "0" + raw : raw));
    }

    // ---- Words ------------------------------------------------------------------------------

    private static void fractions(String s, List<Token> tokens, char[] masked) {
        Matcher m = FRACTION.matcher(s);
        while (m.find()) {
            String numeratorWord = m.group(1) == null ? null : m.group(1).toLowerCase(Locale.ROOT);
            String denominatorWord = m.group(2).toLowerCase(Locale.ROOT);
            boolean plural = denominatorWord.endsWith("s");
            int denominator = switch (denominatorWord.replaceAll("s$", "").replace("halve", "half")) {
                case "half" -> 2;
                case "third" -> 3;
                case "quarter" -> 4;
                case "fifth" -> 5;
                default -> 0;
            };
            int numerator;
            if (numeratorWord == null) {
                if (denominator != 2 || plural) {
                    continue; // "this quarter", "the third": a period or an ordinal, not a fraction
                }
                numerator = 1;
            } else if (numeratorWord.equals("a")) {
                numerator = 1;
            } else {
                numerator = WORDS.get(numeratorWord);
            }
            BigDecimal ratio = BigDecimal.valueOf(numerator)
                    .divide(BigDecimal.valueOf(denominator), 6, java.math.RoundingMode.HALF_UP);
            tokens.add(new Token(
                    m.group(),
                    List.of(ratio),
                    Sign.NONE,
                    Unit.NONE,
                    null,
                    BigDecimal.ONE,
                    true,
                    false,
                    false,
                    m.start(),
                    m.end()));
            mask(masked, m.start(), m.end());
        }
    }

    private static void multipliers(String s, List<Token> tokens, char[] masked) {
        Matcher m = MULTIPLIER.matcher(s);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            int factor = w.startsWith("tripl") || w.equals("thrice") ? 3 : 2;
            tokens.add(new Token(
                    m.group(),
                    List.of(BigDecimal.valueOf(factor)),
                    Sign.NONE,
                    Unit.NONE,
                    null,
                    BigDecimal.ONE,
                    false,
                    true,
                    false,
                    m.start(),
                    m.end()));
            mask(masked, m.start(), m.end());
        }
    }

    private static void words(String s, List<Token> tokens) {
        Matcher m = WORD_RUN.matcher(s);
        int consumed = 0;
        while (m.find()) {
            if (m.start() < consumed) {
                continue; // the "million" of "one point two five million" is part of that number
            }
            String run = m.group("main");
            String point = m.group("point");
            if (point == null
                    && run.equalsIgnoreCase("one")
                    && !COUNTED_NOUN.matcher(s.substring(m.end())).matches()) {
                continue; // "no one", "one of them": not a count
            }
            List<BigDecimal> numbers = new ArrayList<>();
            for (long n : numbersOf(run)) {
                numbers.add(BigDecimal.valueOf(n));
            }
            if (point != null) {
                // "twelve point five": the digits after the point are read one by one.
                StringBuilder digits = new StringBuilder();
                for (String w : point.strip().split("\\s+")) {
                    if (!w.equalsIgnoreCase("point")) {
                        digits.append(WORDS.get(w.toLowerCase(Locale.ROOT)));
                    }
                }
                int last = numbers.size() - 1;
                numbers.set(last, new BigDecimal(numbers.get(last).toPlainString() + "." + digits));
            }
            String currency = null;
            Matcher c = CURRENCY_BEFORE.matcher(s.substring(Math.max(0, m.start() - 8), m.start()));
            if (c.find()) {
                currency = currencyOf(c.group(1) != null ? c.group(1) : c.group(2));
            }
            Suffix suffix = suffix(s, m.end());
            for (int i = 0; i < numbers.size(); i++) {
                boolean last = i == numbers.size() - 1;
                String cur = last && suffix.currency != null ? suffix.currency : (i == 0 ? currency : null);
                Unit unit = !last
                        ? Unit.NONE
                        : cur != null && (suffix.unit == Unit.NONE || suffix.unit == Unit.COUNT)
                                ? Unit.MONEY
                                : suffix.unit;
                tokens.add(new Token(
                        m.group(),
                        List.of(numbers.get(i)),
                        Sign.NONE,
                        unit,
                        cur,
                        last ? suffix.magnitude : BigDecimal.ONE,
                        false,
                        false,
                        false,
                        m.start(),
                        last ? suffix.end : m.end()));
            }
            consumed = Math.max(consumed, suffix.end);
        }
    }

    /** {@code 1/2}, {@code 3/4}, and {@code 24/7} (which is a ratio no fact has): a share. */
    private static void slashFractions(String s, List<Token> tokens, char[] masked) {
        Matcher m = SLASH_FRACTION.matcher(s);
        while (m.find()) {
            int denominator = Integer.parseInt(m.group(2));
            if (denominator == 0) {
                continue;
            }
            BigDecimal ratio = BigDecimal.valueOf(Integer.parseInt(m.group(1)))
                    .divide(BigDecimal.valueOf(denominator), 6, java.math.RoundingMode.HALF_UP);
            tokens.add(new Token(
                    m.group(),
                    List.of(ratio),
                    Sign.NONE,
                    Unit.NONE,
                    null,
                    BigDecimal.ONE,
                    true,
                    false,
                    false,
                    m.start(),
                    m.end()));
            mask(masked, m.start(), m.end());
        }
    }

    /** {@code XII percent}: a roman numeral of two letters or more, in capitals, is a number like any other. */
    private static void romans(String s, List<Token> tokens, char[] masked) {
        Matcher m = ROMAN.matcher(s);
        while (m.find()) {
            if (m.group().isEmpty()) {
                continue;
            }
            Suffix suffix = suffix(s, m.end());
            tokens.add(new Token(
                    m.group(),
                    List.of(BigDecimal.valueOf(romanValue(m.group()))),
                    Sign.NONE,
                    suffix.unit,
                    suffix.currency,
                    suffix.magnitude,
                    false,
                    false,
                    false,
                    m.start(),
                    suffix.end));
            mask(masked, m.start(), suffix.end);
        }
    }

    private static int romanValue(String roman) {
        int total = 0;
        int previous = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int v = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                default -> 1000;
            };
            total += v < previous ? -v : v;
            previous = Math.max(previous, v);
        }
        return total;
    }

    private static final Set<String> MAGNITUDE_WORDS = Set.of("hundred", "thousand", "million", "billion");

    /**
     * The numbers a run of number words says, by the grammar of English numbers: {@code twenty five} is 25 but
     * {@code nineteen and ninety} is 19 and 90, and {@code nine ten} is 9 and 10. {@code and} joins only after a
     * hundred, a thousand, a million.
     */
    static List<Long> numbersOf(String run) {
        List<Long> out = new ArrayList<>();
        long total = 0;
        long current = 0;
        boolean open = false;
        String previous = null;
        boolean andPending = false;
        for (String w : run.toLowerCase(Locale.ROOT).split("[-\\s]+")) {
            if (w.equals("and")) {
                if (previous != null && MAGNITUDE_WORDS.contains(previous)) {
                    andPending = true;
                } else {
                    // "and" between two numbers: they are two numbers.
                    out.add(total + current);
                    total = 0;
                    current = 0;
                    open = false;
                    previous = null;
                }
                continue;
            }
            if (open && !continues(previous, w) && !andPending) {
                out.add(total + current);
                total = 0;
                current = 0;
            }
            andPending = false;
            open = true;
            switch (w) {
                case "hundred" -> current = (current == 0 ? 1 : current) * 100;
                case "dozen" -> current = (current == 0 ? 1 : current) * 12;
                case "thousand" -> {
                    total += (current == 0 ? 1 : current) * 1_000L;
                    current = 0;
                }
                case "million" -> {
                    total += (current == 0 ? 1 : current) * 1_000_000L;
                    current = 0;
                }
                case "billion" -> {
                    total += (current == 0 ? 1 : current) * 1_000_000_000L;
                    current = 0;
                }
                default -> current += WORDS.get(w);
            }
            previous = w;
        }
        if (open) {
            out.add(total + current);
        }
        return out;
    }

    /** Whether {@code next} can carry on the number whose last word is {@code previous}. */
    private static boolean continues(String previous, String next) {
        if (previous == null) {
            return true;
        }
        if (MAGNITUDE_WORDS.contains(previous)) {
            return true;
        }
        Integer p = WORDS.get(previous);
        Integer n = WORDS.get(next);
        if (next.equals("dozen")) {
            return p != null && p >= 1 && p <= 19; // three dozen
        }
        if (p != null && p >= 20 && p % 10 == 0) {
            return (n != null && n >= 1 && n <= 9) || MAGNITUDE_WORDS.contains(next); // twenty five, fifty thousand
        }
        if (previous.equals("dozen")) {
            return false;
        }
        // ones and teens carry on only into a magnitude: five hundred, thirteen thousand
        return MAGNITUDE_WORDS.contains(next);
    }

    private static void mask(char[] masked, int from, int to) {
        for (int i = from; i < to; i++) {
            masked[i] = ' ';
        }
    }
}
