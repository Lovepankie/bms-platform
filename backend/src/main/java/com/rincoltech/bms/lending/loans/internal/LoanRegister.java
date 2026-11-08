package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.members.MemberImports;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of chapter 13 for one row of a loan register (issue #136, sections 13.5 to 13.9), as pure functions: the
 * normalisers of 13.6 (phone, NIN, dates, duration, rate, amounts, the categorical fields, collateral), the row classes of
 * 13.5, the member key and the match against a business's members of 13.9, and the loan a row gives (13.10 steps 4 to 6).
 * No database and no clock: the validation's rules ({@link LoanRegisterRules}), the forecast and the loader all call these
 * same functions, so what the review shows, what the preview predicts and what the commit writes always agree.
 */
final class LoanRegister {

    private LoanRegister() {}

    // ---------------------------------------------------------------------------------------------
    // The fields of the entity set "loans", one per column of the register (13.8), and two of the batch.
    // ---------------------------------------------------------------------------------------------

    static final String DISBURSED_ON = "disbursed_on";
    static final String CUSTOMER_NAME = "customer_name";
    static final String LOCATION = "location";
    static final String CUSTOMER_PHONE = "customer_phone";
    static final String CUSTOMER_NIN = "customer_nin";
    static final String MARITAL_STATUS = "marital_status";
    static final String KIN_NAME = "kin_name";
    static final String KIN_PHONE = "kin_phone";
    static final String KIN_NIN = "kin_nin";
    static final String KIN_RELATIONSHIP = "kin_relationship";
    static final String KIN_LOCATION = "kin_location";
    static final String OCCUPATION = "occupation";
    static final String OTHER_INCOME = "other_income";
    static final String PURPOSE = "purpose";
    static final String PRINCIPAL = "principal";
    static final String RATE = "rate";
    static final String DURATION = "duration";
    static final String DUE_ON = "due_on";
    static final String RETURN_AMOUNT = "return_amount";
    static final String COLLATERAL = "collateral";
    static final String PAYMENT = "payment";
    static final String PAID_ON = "paid_on";
    static final String OUTSTANDING = "outstanding";
    static final String STATUS = "status";
    static final String REMARKS = "remarks";
    static final String BRANCH = "branch";
    static final String PRODUCT = "product";

    /** The amount columns of 13.5: a sub-header holds none, a placeholder holds only zeros. */
    static final List<String> AMOUNTS = List.of(PRINCIPAL, RETURN_AMOUNT, PAYMENT, OUTSTANDING);

    /** The fields of the batch rather than of a cell (the mapping's constants): not counted as a row's cells. */
    static final Set<String> BATCH_FIELDS = Set.of(BRANCH, PRODUCT);

    /** The earliest date a loan of a register can carry (13.6.3). */
    static final LocalDate EARLIEST = LocalDate.of(2015, 1, 1);

    /** How far after the business date a date may still be (13.6.3). */
    static final int LATEST_DAYS = 400;

    // ---------------------------------------------------------------------------------------------
    // 13.6.1 Phone numbers.
    // ---------------------------------------------------------------------------------------------

    private static final Pattern TWO_NUMBERS = Pattern.compile("\\s*(?:/|,|\\bor\\b)\\s*", Pattern.CASE_INSENSITIVE);

    /**
     * A phone cell read: the E.164 number (null when it cannot be read), the second number of a cell with two, whether it
     * held two, whether the text was not already the E.164 form, and whether it is a mobile number.
     */
    record Phone(String e164, String alternate, boolean multiple, boolean changed, boolean mobile) {}

    /** 13.6.1, or null for an empty cell. */
    static Phone phone(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.strip();
        String[] parts = TWO_NUMBERS.split(s, 2);
        boolean multiple = parts.length == 2 && !parts[1].isBlank();
        String first = national(parts[0]);
        String second = multiple ? national(parts[1]) : null;
        if (first == null) {
            return new Phone(null, null, multiple, true, false);
        }
        String e164 = "+256" + first;
        return new Phone(
                e164, second == null ? null : "+256" + second, multiple, !s.equals(e164), first.startsWith("7"));
    }

    /** The nine digits of a Uganda number, or null (13.6.1 steps 1, 3 and 4). */
    private static String national(String text) {
        String s = text.strip();
        if (s.matches("^\\d+\\.0+$")) {
            s = s.substring(0, s.indexOf('.'));
        }
        String digits = s.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("256")) {
            return digits.substring(3);
        }
        if (digits.length() == 10 && digits.startsWith("0")) {
            return digits.substring(1);
        }
        return digits.length() == 9 ? digits : null;
    }

    // ---------------------------------------------------------------------------------------------
    // 13.6.2 National ID.
    // ---------------------------------------------------------------------------------------------

    private static final Pattern NIN = Pattern.compile("^C[MF][A-Z0-9]{12}$");

    /** A NIN cell read: the valid NIN, or the text kept as another ID number when it is not one; null when empty. */
    record Nin(String valid, String other) {}

    static Nin nin(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.toUpperCase(Locale.ROOT).replaceAll("\\s", "");
        return NIN.matcher(s).matches() ? new Nin(s, null) : new Nin(null, cut(text.strip(), 40));
    }

    // ---------------------------------------------------------------------------------------------
    // 13.6.4 Duration.
    // ---------------------------------------------------------------------------------------------

    private static final List<String> WORDS =
            List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve");

    private static final Pattern DURATION_TEXT = Pattern.compile(
            "^(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s*"
                    + "(days?|d|weeks?|wks?|w|months?|mon|mths?|mnth|m)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DURATION_PLAIN = Pattern.compile("^\\d+ [A-Za-z]+$");

    /** A term: its count and unit ({@code day}, {@code week}, {@code month}), and whether the text was tidied. */
    record Duration(int count, String unit, boolean changed) {}

    /** 13.6.4, or null when the text is empty or not a whole count of days, weeks or months. */
    static Duration duration(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.strip();
        Matcher m = DURATION_TEXT.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String n = m.group(1).toLowerCase(Locale.ROOT);
        int count;
        try {
            count = WORDS.contains(n) ? WORDS.indexOf(n) + 1 : Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return null;
        }
        if (count <= 0 || count > 1000) {
            return null;
        }
        String u = m.group(2).toLowerCase(Locale.ROOT);
        String unit = u.startsWith("d") ? "day" : u.startsWith("w") ? "week" : "month";
        return new Duration(count, unit, !DURATION_PLAIN.matcher(s).matches());
    }

    /** The date a term after {@code d} ends, by chapter 3 R-TERM: a month clamps to the month's end. */
    static LocalDate plus(LocalDate d, Duration du) {
        return switch (du.unit()) {
            case "day" -> d.plusDays(du.count());
            case "week" -> d.plusWeeks(du.count());
            default -> d.plusMonths(du.count());
        };
    }

    // ---------------------------------------------------------------------------------------------
    // 13.6.6 Amounts and 13.6.5 the rate.
    // ---------------------------------------------------------------------------------------------

    private static final Pattern CURRENCY = Pattern.compile("^(UGX|SHS\\.?)", Pattern.CASE_INSENSITIVE);

    /**
     * An amount cell read: the whole shillings used (rounded half up from a fraction), the code of its problem
     * ({@code NEGATIVE_AMOUNT}, {@code AMOUNT_NOT_INTEGER}, {@code AMOUNT_UNPARSEABLE}) or null, and for a fraction the
     * rounded value as the suggestion.
     */
    record Amount(Long minor, String problem, String suggestion) {}

    /** 13.6.6, or null for an empty cell. */
    static Amount amount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = CURRENCY.matcher(text.strip().replaceAll("[\\s,]", "")).replaceFirst("");
        BigDecimal d;
        try {
            d = new BigDecimal(s);
        } catch (NumberFormatException e) {
            return new Amount(null, "AMOUNT_UNPARSEABLE", null);
        }
        if (d.signum() < 0) {
            return new Amount(null, "NEGATIVE_AMOUNT", null);
        }
        BigDecimal whole = d.setScale(0, RoundingMode.HALF_UP);
        long minor;
        try {
            minor = whole.longValueExact();
        } catch (ArithmeticException e) {
            return new Amount(null, "AMOUNT_UNPARSEABLE", null);
        }
        if (d.stripTrailingZeros().scale() > 0) {
            return new Amount(minor, "AMOUNT_NOT_INTEGER", whole.toPlainString());
        }
        return new Amount(minor, null, null);
    }

    /**
     * A rate cell read (13.6.5): the basis points for the whole term used, the code of its finding
     * ({@code RATE_TEXT_PARSED}, {@code RATE_ZERO}, {@code RATE_PERCENT_FORM}, {@code RATE_OUT_OF_RANGE},
     * {@code RATE_MISSING}) or null. A rate in percent form is used as a percentage, as its suggestion reads it.
     */
    record Rate(Integer bp, String code, String suggestion) {}

    /** 13.6.5, with the implied rate of the amounts for a suggestion (null when they imply none). */
    static Rate rate(String text, Long principal, Long returnAmount) {
        String implied = impliedText(principal, returnAmount);
        if (text == null || text.isBlank()) {
            return new Rate(null, "RATE_MISSING", implied);
        }
        String s = text.strip().replaceAll("\\s", "");
        boolean percent = s.endsWith("%");
        BigDecimal v;
        try {
            v = new BigDecimal(percent ? s.substring(0, s.length() - 1) : s);
        } catch (NumberFormatException e) {
            return new Rate(null, "RATE_MISSING", implied);
        }
        if (v.signum() < 0) {
            return new Rate(null, "RATE_OUT_OF_RANGE", implied);
        }
        if (percent) {
            return v.compareTo(BigDecimal.valueOf(1000)) > 0
                    ? new Rate(null, "RATE_OUT_OF_RANGE", implied)
                    : new Rate(bp(v.movePointLeft(2)), "RATE_TEXT_PARSED", null);
        }
        if (v.signum() == 0) {
            return new Rate(0, "RATE_ZERO", null);
        }
        if (v.compareTo(BigDecimal.ONE) < 0) {
            return new Rate(bp(v), null, null);
        }
        if (v.compareTo(BigDecimal.valueOf(100)) <= 0) {
            BigDecimal fraction = v.movePointLeft(2);
            return new Rate(
                    bp(fraction),
                    "RATE_PERCENT_FORM",
                    fraction.stripTrailingZeros().toPlainString());
        }
        return new Rate(null, "RATE_OUT_OF_RANGE", implied);
    }

    private static int bp(BigDecimal fraction) {
        return fraction.movePointRight(4).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** The implied rate {@code R / P - 1} to the nearest basis point, as the fraction a cell holds, when it is in (0, 1). */
    static String impliedText(Long principal, Long returnAmount) {
        Integer bp = impliedBp(principal, returnAmount);
        return bp == null || bp <= 0 || bp >= 10_000
                ? null
                : BigDecimal.valueOf(bp).movePointLeft(4).stripTrailingZeros().toPlainString();
    }

    static Integer impliedBp(Long principal, Long returnAmount) {
        if (principal == null || returnAmount == null || principal <= 0) {
            return null;
        }
        return BigDecimal.valueOf(returnAmount - principal)
                .multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(principal), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /** {@code round(P x (1 + r))}, half up, with the rate in basis points. */
    static long withRate(long principal, int bp) {
        return BigDecimal.valueOf(principal)
                .multiply(BigDecimal.valueOf(10_000L + bp))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    // ---------------------------------------------------------------------------------------------
    // 13.6.7 Categorical fields and 13.6.8 collateral.
    // ---------------------------------------------------------------------------------------------

    /** The marital status a text maps to; {@code unknown} for an empty one, null when the rules do not know it. */
    static String marital(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "single", "s" -> "single";
            case "married", "m" -> "married";
            case "divorced" -> "divorced";
            case "widow", "widowed", "widower" -> "widowed";
            case "separated" -> "separated";
            default -> null;
        };
    }

    /** The relationship a text maps to; null when the rules do not know it (it is then {@code other} with its text). */
    static String relationship(String text) {
        if (text == null || text.isBlank()) {
            return "other";
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "wife", "husband", "spouse" -> "spouse";
            case "mother", "father", "mum", "dad", "parent" -> "parent";
            case "son", "daughter", "child" -> "child";
            case "brother", "sister", "sibling" -> "sibling";
            case "uncle",
                    "aunt",
                    "aunty",
                    "cousin",
                    "nephew",
                    "niece",
                    "in-law",
                    "in law",
                    "grandmother",
                    "grandfather",
                    "relative" -> "relative";
            case "friend" -> "friend";
            case "boss", "employer" -> "employer";
            default -> null;
        };
    }

    /** {@code open} or {@code settled} for a status text, null when the rules do not know it. */
    static String status(String text) {
        if (text == null || text.isBlank()) {
            return "open";
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "active", "running", "ongoing", "pending" -> "open";
            case "cleared", "paid", "completed", "finished", "closed" -> "settled";
            default -> null;
        };
    }

    private static final Pattern VEHICLE =
            Pattern.compile("^(car|motorcycle|bike|vehicle)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** A collateral item a cell gives: its type, description and reference, and whether the pattern was recognised. */
    record Collateral(String type, String description, String reference, boolean recognised) {}

    /** 13.6.8, or null for an empty cell. {@code nin} is the borrower's, the reference of a national ID pledged. */
    static Collateral collateral(String text, String nin) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.strip().replaceAll("\\s+", " ");
        String key = s.toLowerCase(Locale.ROOT);
        switch (key) {
            case "land", "land title", "title", "plot" -> {
                return new Collateral("land_title", s, null, true);
            }
            case "id", "national id", "nid" -> {
                return new Collateral("national_id", s, nin, true);
            }
            case "car log", "log book", "logbook", "log" -> {
                return new Collateral("vehicle_logbook", s, null, true);
            }
            default -> {
                Matcher m = VEHICLE.matcher(s);
                if (m.matches()) {
                    String plate = m.group(2).replaceAll("\\s", "").toUpperCase(Locale.ROOT);
                    return new Collateral("vehicle", s, plate, true);
                }
                return new Collateral("other", s, null, false);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 13.6.3 Dates.
    // ---------------------------------------------------------------------------------------------

    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ]00:00(?::00)?)?$");
    private static final Pattern SHORT = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})$");
    private static final Pattern DAY_MONTH_YEAR =
            Pattern.compile("^(\\d{1,2})(?:st|nd|rd|th)?[\\s-]+([A-Za-z]+)\\.?[\\s,-]+(\\d{4})$");
    private static final Pattern MONTH_DAY_YEAR =
            Pattern.compile("^([A-Za-z]+)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})$");

    /** One date a cell may mean: the date and how it was read ({@code as_is}, {@code swapped}, {@code text_*}). */
    record Candidate(LocalDate date, String kind) {

        boolean text() {
            return kind.startsWith("text_");
        }
    }

    /** The candidates of a cell (13.6.3), before the window; empty when it is not a date. */
    static List<Candidate> candidates(String text) {
        List<Candidate> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String s = text.strip();
        Matcher iso = ISO.matcher(s);
        if (iso.matches()) {
            int y = Integer.parseInt(iso.group(1));
            int m = Integer.parseInt(iso.group(2));
            int d = Integer.parseInt(iso.group(3));
            add(out, y, m, d, "as_is");
            if (!out.isEmpty() && d <= 12 && m <= 12 && d != m) {
                add(out, y, d, m, "swapped");
            }
            return out;
        }
        Matcher sh = SHORT.matcher(s);
        if (sh.matches()) {
            int a = Integer.parseInt(sh.group(1));
            int b = Integer.parseInt(sh.group(2));
            int y = Integer.parseInt(sh.group(3)) + (sh.group(3).length() == 2 ? 2000 : 0);
            add(out, y, a, b, "text_mdy");
            if (a != b) {
                add(out, y, b, a, "text_dmy");
            }
            return out;
        }
        Matcher dmy = DAY_MONTH_YEAR.matcher(s);
        if (dmy.matches()) {
            Integer month = monthOf(dmy.group(2));
            if (month != null) {
                add(out, Integer.parseInt(dmy.group(3)), month, Integer.parseInt(dmy.group(1)), "text_named");
            }
            return out;
        }
        Matcher mdy = MONTH_DAY_YEAR.matcher(s);
        if (mdy.matches()) {
            Integer month = monthOf(mdy.group(1));
            if (month != null) {
                add(out, Integer.parseInt(mdy.group(3)), month, Integer.parseInt(mdy.group(2)), "text_named");
            }
        }
        return out;
    }

    private static void add(List<Candidate> out, int y, int m, int d, String kind) {
        try {
            out.add(new Candidate(LocalDate.of(y, m, d), kind));
        } catch (DateTimeException e) {
            // Not a date: no candidate.
        }
    }

    private static final List<String> MONTHS = List.of(
            "january",
            "february",
            "march",
            "april",
            "may",
            "june",
            "july",
            "august",
            "september",
            "october",
            "november",
            "december");

    /** The month of a name or its three-letter abbreviation (case-insensitive), or null. */
    static Integer monthOf(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < MONTHS.size(); i++) {
            String full = MONTHS.get(i);
            if (w.equals(full) || (w.length() == 3 && full.startsWith(w)) || ("sept".equals(w) && i == 8)) {
                return i + 1;
            }
        }
        return null;
    }

    private static final Pattern MONTH_LABEL = Pattern.compile("^([A-Za-z]+)\\.?(?:[\\s,-]+(\\d{4}|\\d{2}))?$");

    /** A sub-header's month label (13.5): the month and the year it names, or null for no year; null when not a label. */
    record MonthLabel(int month, Integer year) {}

    static MonthLabel monthLabel(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MONTH_LABEL.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        Integer month = monthOf(m.group(1));
        if (month == null) {
            return null;
        }
        Integer year = m.group(2) == null
                ? null
                : Integer.parseInt(m.group(2)) + (m.group(2).length() == 2 ? 2000 : 0);
        return new MonthLabel(month, year);
    }

    /** The candidates inside the window from 2015 to the business date plus 400 days (no upper end without one). */
    static List<Candidate> inWindow(List<Candidate> all, LocalDate businessDate) {
        LocalDate latest = businessDate == null ? null : businessDate.plusDays(LATEST_DAYS);
        return all.stream()
                .filter(c -> !c.date().isBefore(EARLIEST)
                        && (latest == null || !c.date().isAfter(latest)))
                .toList();
    }

    /** A finding of the rules: a code, its field (null for the row), the other line it names, and its suggestion. */
    record Note(String code, String field, Integer other, String suggestion) {

        static Note of(String code, String field) {
            return new Note(code, field, null, null);
        }
    }

    /** The disbursement and due dates resolved together (13.6.3), with what the resolution found. */
    record Dates(LocalDate disbursedOn, LocalDate dueOn, List<Note> notes) {}

    /**
     * 13.6.3 for DATE and EXPECTED DATE OF RETURN: the consistent pair under the duration, the sub-header's month to choose
     * between several, or the finding that says why there is none. {@code context} is the month of the nearest sub-header
     * above the row, or null.
     */
    static Dates dates(String dateText, String dueText, Duration du, YearMonth context, LocalDate businessDate) {
        List<Note> notes = new ArrayList<>();
        List<Candidate> dAll = candidates(dateText);
        List<Candidate> eAll = candidates(dueText);
        List<Candidate> d = inWindow(dAll, businessDate);
        List<Candidate> e = inWindow(eAll, businessDate);
        boolean dateEmpty = dateText == null || dateText.isBlank();
        boolean dueEmpty = dueText == null || dueText.isBlank();
        if (dateEmpty || dAll.isEmpty()) {
            notes.add(Note.of("DATE_UNPARSEABLE", DISBURSED_ON));
        } else if (d.isEmpty()) {
            notes.add(Note.of("DATE_OUT_OF_RANGE", DISBURSED_ON));
        }
        if (!dueEmpty && eAll.isEmpty()) {
            notes.add(Note.of("DATE_UNPARSEABLE", DUE_ON));
        } else if (!dueEmpty && e.isEmpty()) {
            notes.add(Note.of("DATE_OUT_OF_RANGE", DUE_ON));
        }
        if (!notes.isEmpty() || du == null) {
            return new Dates(null, null, notes);
        }
        if (dueEmpty) {
            // Nothing to check against: one reading is used and verified by a person; the due date is the term's end.
            if (d.size() != 1) {
                notes.add(Note.of("DATE_AMBIGUOUS", DISBURSED_ON));
                return new Dates(null, null, notes);
            }
            LocalDate due = plus(d.getFirst().date(), du);
            textParsed(notes, d.getFirst(), DISBURSED_ON);
            notes.add(new Note("DATE_UNVERIFIED", DUE_ON, null, due.toString()));
            return new Dates(d.getFirst().date(), due, notes);
        }
        record Pair(Candidate d, Candidate e, boolean thirty) {}
        List<Pair> pairs = new ArrayList<>();
        for (Candidate dc : d) {
            for (Candidate ec : e) {
                if (ec.date().equals(plus(dc.date(), du))) {
                    pairs.add(new Pair(dc, ec, false));
                } else if ("month".equals(du.unit())
                        && ec.date().equals(dc.date().plusDays(30L * du.count()))) {
                    pairs.add(new Pair(dc, ec, true));
                }
            }
        }
        if (pairs.isEmpty()) {
            notes.add(Note.of("DATE_INCONSISTENT", DISBURSED_ON));
            return new Dates(null, null, notes);
        }
        Pair chosen;
        boolean byContext = false;
        if (pairs.size() == 1) {
            chosen = pairs.getFirst();
        } else {
            List<Pair> inMonth = context == null
                    ? List.of()
                    : pairs.stream()
                            .filter(p -> YearMonth.from(p.d().date()).equals(context))
                            .toList();
            if (inMonth.size() != 1) {
                notes.add(Note.of("DATE_AMBIGUOUS", DISBURSED_ON));
                return new Dates(null, null, notes);
            }
            chosen = inMonth.getFirst();
            byContext = true;
        }
        Candidate dc = chosen.d();
        Candidate ec = chosen.e();
        // A pair chosen by the sub-header is a repair a person confirms, unless both dates were read as written.
        boolean confirmD = "swapped".equals(dc.kind()) || (byContext && !"as_is".equals(dc.kind()));
        boolean confirmE = "swapped".equals(ec.kind()) || (byContext && !"as_is".equals(ec.kind()));
        if (confirmD) {
            notes.add(
                    new Note("DATE_SWAP_REPAIRED", DISBURSED_ON, null, dc.date().toString()));
        } else {
            textParsed(notes, dc, DISBURSED_ON);
        }
        if (confirmE) {
            notes.add(new Note("DATE_SWAP_REPAIRED", DUE_ON, null, ec.date().toString()));
        } else {
            textParsed(notes, ec, DUE_ON);
        }
        if (chosen.thirty()) {
            notes.add(Note.of("DATE_MONTH_AS_30_DAYS", DUE_ON));
        }
        return new Dates(dc.date(), ec.date(), notes);
    }

    private static void textParsed(List<Note> notes, Candidate c, String field) {
        if (c.text()) {
            notes.add(Note.of("DATE_TEXT_PARSED", field));
        }
    }

    /**
     * DATE OF CURRENT PAY (13.6.3): the candidates on or after the disbursement and on or before the business date. One is
     * used; two give {@code PAY_DATE_AMBIGUOUS} with the one read the way the row's DATE was read; none gives
     * {@code PAY_DATE_OUT_OF_RANGE} and the first reading is kept for a person to check. The date used, or null for none.
     */
    record PayDate(LocalDate paidOn, Note note) {}

    static PayDate payDate(String text, LocalDate disbursedOn, String dateKind, LocalDate businessDate) {
        List<Candidate> all = candidates(text);
        if (all.isEmpty()) {
            return new PayDate(null, Note.of("DATE_UNPARSEABLE", PAID_ON));
        }
        List<Candidate> kept = all.stream()
                .filter(c -> disbursedOn == null || !c.date().isBefore(disbursedOn))
                .filter(c -> businessDate == null || !c.date().isAfter(businessDate))
                .toList();
        if (kept.size() == 1) {
            return new PayDate(kept.getFirst().date(), null);
        }
        if (kept.isEmpty()) {
            return new PayDate(all.getFirst().date(), Note.of("PAY_DATE_OUT_OF_RANGE", PAID_ON));
        }
        Candidate same =
                kept.stream().filter(c -> c.kind().equals(dateKind)).findFirst().orElse(kept.getFirst());
        return new PayDate(
                same.date(),
                new Note("PAY_DATE_AMBIGUOUS", PAID_ON, null, same.date().toString()));
    }

    /** The way a resolved disbursement date was read from its cell, for the pay date's convention. */
    static String kindOf(String dateText, LocalDate resolved) {
        if (resolved == null) {
            return "as_is";
        }
        return candidates(dateText).stream()
                .filter(c -> c.date().equals(resolved))
                .map(Candidate::kind)
                .findFirst()
                .orElse("as_is");
    }

    /** A stored date: the ISO text the rules replaced the cell with, else its first reading. */
    static LocalDate stored(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.toString().strip());
        } catch (DateTimeParseException e) {
            List<Candidate> c = candidates(value.toString());
            return c.isEmpty() ? null : c.getFirst().date();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 13.5 Row classes.
    // ---------------------------------------------------------------------------------------------

    /** The class of a row that is not a record: {@code SUBHEADER_ROW}, {@code PLACEHOLDER_ROW}, {@code AMOUNTS_ONLY_ROW}. */
    static String classify(Map<String, String> texts, Map<String, Object> rowRules) {
        List<String> filled = new ArrayList<>();
        texts.forEach((field, text) -> {
            if (!BATCH_FIELDS.contains(field) && text != null && !text.isBlank()) {
                filled.add(field);
            }
        });
        if (filled.isEmpty()) {
            return null;
        }
        if (Boolean.TRUE.equals(rowRules.get("subheader_month"))
                && filled.size() <= 2
                && filled.stream().noneMatch(AMOUNTS::contains)
                && filled.stream().anyMatch(f -> monthLabel(texts.get(f)) != null)) {
            return "SUBHEADER_ROW";
        }
        List<Pattern> placeholders = placeholders(rowRules.get("placeholder_rows"));
        String name = texts.get(CUSTOMER_NAME);
        if (!placeholders.isEmpty()
                && (blank(name) || matches(placeholders, name))
                && emptyZeroOrPlaceholder(texts.get(CUSTOMER_PHONE), placeholders)
                && emptyZeroOrPlaceholder(texts.get(CUSTOMER_NIN), placeholders)
                && AMOUNTS.stream().allMatch(a -> emptyOrZero(texts.get(a)))) {
            return "PLACEHOLDER_ROW";
        }
        if (blank(name)
                && blank(texts.get(CUSTOMER_PHONE))
                && blank(texts.get(CUSTOMER_NIN))
                && AMOUNTS.stream().anyMatch(a -> !emptyOrZero(texts.get(a)))) {
            return "AMOUNTS_ONLY_ROW";
        }
        return null;
    }

    /** The month of a sub-header row: the first of its cells that is a month label. */
    static MonthLabel subheaderMonth(Map<String, String> texts) {
        for (Map.Entry<String, String> e : texts.entrySet()) {
            if (!BATCH_FIELDS.contains(e.getKey()) && monthLabel(e.getValue()) != null) {
                return monthLabel(e.getValue());
            }
        }
        return null;
    }

    /** The mapping's placeholder patterns, each a whole-cell regular expression ignoring case, or a text when not one. */
    static List<Pattern> placeholders(Object rule) {
        List<Pattern> out = new ArrayList<>();
        if (rule instanceof List<?> list) {
            for (Object o : list) {
                String p = String.valueOf(o);
                try {
                    out.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
                } catch (java.util.regex.PatternSyntaxException e) {
                    out.add(Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE));
                }
            }
        }
        return out;
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        String s = text.strip();
        return patterns.stream().anyMatch(p -> p.matcher(s).matches());
    }

    private static boolean emptyZeroOrPlaceholder(String text, List<Pattern> patterns) {
        return emptyOrZero(text) || matches(patterns, text);
    }

    private static boolean emptyOrZero(String text) {
        if (blank(text)) {
            return true;
        }
        Amount a = amount(text);
        return a != null && a.minor() != null && a.minor() == 0;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---------------------------------------------------------------------------------------------
    // A row as the forecast and the loader read it, from its stored values.
    // ---------------------------------------------------------------------------------------------

    /** Everything a written row gives (13.8), read from its stored values by the rules above. */
    record Row(
            String branch,
            String product,
            String name,
            String location,
            String phone,
            String altPhone,
            String nin,
            String otherId,
            String marital,
            String occupation,
            String otherIncome,
            String kinName,
            String kinPhone,
            String kinNin,
            String kinRelationship,
            String kinRelationshipText,
            String kinLocation,
            String purpose,
            long principal,
            int rateBp,
            int termCount,
            String termUnit,
            LocalDate disbursedOn,
            LocalDate dueOn,
            long returnAmount,
            long payment,
            LocalDate paidOn,
            Collateral collateral,
            String remarks) {

        long interest() {
            return Math.max(0, returnAmount - principal);
        }

        String idType() {
            return nin != null ? "nin" : otherId != null ? "other" : "none";
        }
    }

    /** The row of a stored row, or throws {@link IllegalArgumentException} for one the commit cannot write. */
    static Row row(Map<String, Object> v) {
        String name = text(v, CUSTOMER_NAME);
        Phone phone = phone(text(v, CUSTOMER_PHONE));
        Duration du = duration(text(v, DURATION));
        LocalDate disbursed = stored(v.get(DISBURSED_ON));
        LocalDate due = stored(v.get(DUE_ON));
        Amount p = amount(text(v, PRINCIPAL));
        if (name == null
                || phone == null
                || phone.e164() == null
                || du == null
                || disbursed == null
                || due == null
                || p == null
                || p.minor() == null
                || p.minor() <= 0) {
            throw new IllegalArgumentException("a row without a borrower, phone, term, dates or principal");
        }
        Amount r = amount(text(v, RETURN_AMOUNT));
        Rate rate = rate(text(v, RATE), p.minor(), r == null ? null : r.minor());
        if (rate.bp() == null) {
            throw new IllegalArgumentException("a row without a rate");
        }
        long ret = r != null && r.minor() != null ? r.minor() : withRate(p.minor(), rate.bp());
        Amount pay = amount(text(v, PAYMENT));
        long paid = pay == null || pay.minor() == null ? 0 : pay.minor();
        LocalDate paidOn = paid > 0 ? stored(v.get(PAID_ON)) : null;
        if (paid > 0 && paidOn == null) {
            throw new IllegalArgumentException("a payment without its date");
        }
        Nin nin = nin(text(v, CUSTOMER_NIN));
        String altE164 = phone.alternate();
        String kinName = text(v, KIN_NAME);
        Phone kinPhone = kinName == null ? null : phone(text(v, KIN_PHONE));
        Nin kinNin = kinName == null ? null : nin(text(v, KIN_NIN));
        String relText = text(v, KIN_RELATIONSHIP);
        String rel = relationship(relText);
        String marital = marital(text(v, MARITAL_STATUS));
        return new Row(
                text(v, BRANCH),
                text(v, PRODUCT),
                cut(name, 200),
                cut(text(v, LOCATION), 200),
                phone.e164(),
                altE164 == null || altE164.equals(phone.e164()) ? null : altE164,
                nin == null ? null : nin.valid(),
                nin == null ? null : nin.other(),
                marital == null ? "unknown" : marital,
                cut(text(v, OCCUPATION), 120),
                cut(text(v, OTHER_INCOME), 200),
                cut(kinName, 200),
                kinPhone == null ? null : kinPhone.e164(),
                kinNin == null ? null : kinNin.valid(),
                rel == null ? "other" : rel,
                rel == null ? cut(relText, 60) : null,
                cut(text(v, KIN_LOCATION), 200),
                cut(text(v, PURPOSE), 300),
                p.minor(),
                rate.bp(),
                du.count(),
                du.unit(),
                disbursed,
                due,
                ret,
                paid,
                paidOn,
                collateral(text(v, COLLATERAL), nin == null ? null : nin.valid()),
                text(v, REMARKS));
    }

    static String text(Map<String, ?> v, String field) {
        Object o = v.get(field);
        if (o == null) {
            return null;
        }
        String s = o.toString().strip();
        return s.isEmpty() ? null : s;
    }

    static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }

    // ---------------------------------------------------------------------------------------------
    // The loan a row gives (13.10 steps 4 to 6): one bullet item, the historic payment by R-ALLOC.
    // ---------------------------------------------------------------------------------------------

    /** The schedule of a row: one item due on the expected date of return, principal and interest (13.10 step 4). */
    static List<ScheduleCalculator.Item> schedule(Row r) {
        return List.of(new ScheduleCalculator.Item(1, r.dueOn(), r.principal(), r.interest(), 0));
    }

    /** The row's one historic payment, if any. */
    static List<BringIn.Line> lines(Row r) {
        return r.payment() > 0 ? List.of(new BringIn.Line(1, r.paidOn(), r.payment(), null, null)) : List.of();
    }

    /**
     * The loan the row leaves as at the cutover day (13.10 steps 5 and 6): the payment applied by the product's allocation
     * order (interest first by default), the excess held as a credit, and the position aged to {@code asAt}. The one
     * function the forecast predicts by and the loader writes by.
     */
    static BringIn.Plan plan(Row r, List<String> allocationOrder, LocalDate asAt) {
        return BringIn.apply(BringIn.items(schedule(r)), lines(r), "flat", false, allocationOrder, asAt);
    }

    /** The credit a payment above the amount to be returned leaves (13.10 step 5). */
    static long credit(BringIn.Plan plan) {
        return plan.paid().stream().mapToLong(p -> p.applied().excessMinor()).sum();
    }

    /** The days-past-due bucket of a loan, as the reconciliation names it. */
    static String bucket(int daysPastDue) {
        if (daysPastDue <= 0) {
            return "current";
        }
        return daysPastDue <= 30 ? "1-30" : daysPastDue <= 60 ? "31-60" : daysPastDue <= 90 ? "61-90" : "over-90";
    }

    // ---------------------------------------------------------------------------------------------
    // 13.9 The member a row is.
    // ---------------------------------------------------------------------------------------------

    /** 13.9: {@code nin:<NIN>}, else {@code phone:<E.164>}, else the row's own key (never merged). */
    static String memberKey(String nin, String phone, String rowKey) {
        if (nin != null) {
            return "nin:" + nin;
        }
        return phone != null ? "phone:" + phone : "row:" + rowKey;
    }

    /** How a borrower stands against the members already held: one of them, one whose phone it shares but who differs, none. */
    enum MatchKind {
        MATCHED,
        CONFLICT,
        NONE
    }

    record Match(MatchKind kind, MemberImports.Held member) {}

    /**
     * A borrower against the members held (13.9: NIN first, then phone). The member with the NIN is the borrower. Else, when
     * exactly one member holds the phone, it is the borrower unless they differ: both have NINs and they differ, or one has
     * none and the names differ (ignoring case and spaces); then it is a conflict. Two or more members on one phone are a
     * conflict too. Order does not matter, so the validation and the loader decide alike.
     */
    static Match match(String nin, String name, Optional<MemberImports.Held> byNin, List<MemberImports.Held> byPhone) {
        if (byNin.isPresent()) {
            return new Match(MatchKind.MATCHED, byNin.get());
        }
        if (byPhone.isEmpty()) {
            return new Match(MatchKind.NONE, null);
        }
        if (byPhone.size() > 1) {
            return new Match(MatchKind.CONFLICT, null);
        }
        MemberImports.Held held = byPhone.getFirst();
        boolean differ = nin != null && held.nationalId() != null
                ? !nin.equals(held.nationalId())
                : !sameName(name, held.fullName());
        return differ ? new Match(MatchKind.CONFLICT, null) : new Match(MatchKind.MATCHED, held);
    }

    static boolean sameName(String a, String b) {
        return a != null && b != null && compare(a).equals(compare(b));
    }

    private static String compare(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
