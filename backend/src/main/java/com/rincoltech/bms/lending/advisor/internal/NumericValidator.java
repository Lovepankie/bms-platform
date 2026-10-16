package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Sign;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Token;
import com.rincoltech.bms.lending.advisor.internal.NumberExtractor.Unit;
import com.rincoltech.bms.lending.insights.FactDisplay;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The second line of defence over the text our own renderer wrote (ADR-037 decision 5 as amended in issue #223): the model
 * writes no text at all, so there is no prose to validate, but every number in what the renderer produced is still
 * re-derived from the facts it came from, so that a defect in a template or in the substitution cannot put a wrong figure
 * on a screen. A number is derivable when it equals a cited fact's display or plain form in any written form, or is that
 * figure correctly rounded to the precision shown (compact amounts and rates only, never a count, by no more than five
 * percent); units must agree and the sign of a change must agree with the fact. A hidden fact has no number to state.
 *
 * <p>It also carries the shape screen for personal data ({@link #looksPersonal}) that the renderer's output is run through.
 * Violations carry a closed code and at most a number as written, never a sentence.
 */
final class NumericValidator {

    /** The most a rounded compact figure may be off, as a share of the fact. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,255}\\.[A-Za-z]{2,24}");
    private static final String SEP = "[\\s\\p{Z}./_\\-()]";
    private static final Pattern UGANDA_MOBILE =
            Pattern.compile("(?<!\\d)(?:\\+?256|0)" + SEP + "*7(?:" + SEP + "*\\d){8}(?!\\d)");
    private static final Pattern INTERNATIONAL = Pattern.compile("(?<!\\d)\\+\\d(?:" + SEP + "*\\d){9,13}(?!\\d)");
    private static final Pattern NIN = Pattern.compile("(?<![A-Za-z0-9])[Cc][MFmf][A-Za-z0-9]{12}(?![A-Za-z0-9])");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Pattern LOSS_WORD =
            Pattern.compile("(?i)\\b(?:loss|losses|deficit|negative|minus|shortfall|below zero)\\b");

    private NumericValidator() {}

    /** What the validator needs to know about the prompt the answer belongs to. */
    record Context(AliasedFacts facts) {

        static Context of(AliasedFacts facts) {
            return new Context(facts);
        }
    }

    /** {@code code} is closed; {@code where} says which part of the answer; {@code token} is a number or null, never a sentence. */
    record Violation(String code, String where, String token) {}

    /** The verdict. {@code answer} is the rendered brief, null unless valid. */
    record Result(boolean valid, List<Violation> violations, AdvisorAnswer answer) {

        boolean has(String code) {
            return violations.stream().anyMatch(v -> v.code().equals(code));
        }
    }

    /** Every number of a text against the cited facts. */
    static List<Violation> derivable(String text, String where, List<PromptFact> cited, Context ctx) {
        List<Violation> out = new ArrayList<>();
        String n = Text.normalise(text);
        Set<String> names = new LinkedHashSet<>();
        ctx.facts().facts().forEach(f -> {
            names.add(f.label());
            names.add(f.metric());
        });
        NumberExtractor.Extraction found =
                NumberExtractor.extract(n, ctx.facts().dates(), ctx.facts().aliases(), names);
        boolean loss = LOSS_WORD.matcher(n).find();
        for (Token t : found.tokens()) {
            if (t.yearLike()) {
                continue;
            }
            String code = verdict(t, cited, ctx.facts().currency(), loss);
            if (code != null) {
                out.add(new Violation(code, where, tokenText(t)));
            }
        }
        return out;
    }

    /** True when the normalised text holds an email address, a phone number, an ID number shape or a UUID. */
    static boolean looksPersonal(String normalised) {
        // Every pattern is bounded, so there is no length guard: a long text must never skip the screen.
        return EMAIL.matcher(normalised).find()
                || UGANDA_MOBILE.matcher(normalised).find()
                || INTERNATIONAL.matcher(normalised).find()
                || NIN.matcher(normalised).find()
                || UUID_SHAPE.matcher(normalised).find();
    }

    /** A number, as written, for a violation: digits and symbols only, never a sentence. */
    private static String tokenText(Token t) {
        String s = t.text();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** Null when some cited fact accounts for the token; else the most specific code. */
    private static String verdict(Token t, List<PromptFact> cited, String currency, boolean lossWord) {
        boolean sign = false;
        boolean unit = false;
        for (PromptFact f : cited) {
            switch (match(t, f, currency, lossWord)) {
                case OK -> {
                    return null;
                }
                case SIGN -> sign = true;
                case UNIT -> unit = true;
                default -> {}
            }
        }
        return sign ? "sign_mismatch" : unit ? "unit_mismatch" : "invented_number";
    }

    enum Match {
        OK,
        UNIT,
        SIGN,
        NO
    }

    static Match match(Token t, PromptFact f, String currency, boolean lossWord) {
        if (f.suppressed()) {
            // A hidden figure has no number to state: "exactly 5" is a number nobody gave.
            return Match.NO;
        }
        if (f.value() == null) {
            return Match.NO;
        }
        if (!valueMatches(t, f, currency)) {
            return Match.NO;
        }
        if (!unitFits(t, f, currency)) {
            return Match.UNIT;
        }
        return signFits(t, f, lossWord) ? Match.OK : Match.SIGN;
    }

    private static boolean anyReading(Token t, java.util.function.Predicate<BigDecimal> test) {
        for (BigDecimal v : t.values()) {
            if (test.test(v.multiply(t.magnitude()))) {
                return true;
            }
        }
        return false;
    }

    /** The fact's figure in the units a person writes: whole currency units, a count, percent, days, hours. */
    private static BigDecimal target(PromptFact f, String currency) {
        BigDecimal v = BigDecimal.valueOf(Math.abs(f.value()));
        return switch (f.kind()) {
            case MONEY -> v.movePointLeft(FactDisplay.exponent(currency));
            case BASIS_POINTS -> v.movePointLeft(2);
            default -> v;
        };
    }

    private static boolean valueMatches(Token t, PromptFact f, String currency) {
        BigDecimal target = target(f, currency);
        if (t.fraction()) {
            // "half", "two thirds": a share, against a rate that is not a change.
            if (f.kind() != PortfolioFacts.Kind.BASIS_POINTS || f.isChange()) {
                return false;
            }
            BigDecimal share = t.values().get(0).multiply(BigDecimal.valueOf(100));
            return share.signum() > 0 && target.subtract(share).abs().compareTo(share.multiply(TOLERANCE)) <= 0;
        }
        if (t.unit() == Unit.BASIS_POINTS) {
            // "1,250 bp": basis points, exactly.
            return f.kind() == PortfolioFacts.Kind.BASIS_POINTS
                    && anyReading(t, v -> v.compareTo(BigDecimal.valueOf(Math.abs(f.value()))) == 0);
        }
        for (BigDecimal reading : t.values()) {
            BigDecimal stated = reading.multiply(t.magnitude());
            if (stated.compareTo(target) == 0) {
                return true;
            }
            boolean mayRound = !t.multiplier() && (t.compact() || f.kind() == PortfolioFacts.Kind.BASIS_POINTS);
            if (mayRound && roundsTo(reading, t.magnitude(), target, f.kind() == PortfolioFacts.Kind.BASIS_POINTS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code target} correctly rounded to the precision the writer shows (half up, or half down on a tie) is
     * what they wrote, and the figure is within {@link #TOLERANCE} of the fact. The one exception to the tolerance is a
     * rate to one decimal, which is the display form itself and so is always right (0.25 percent is shown as 0.3).
     * Zero is never a rounding.
     */
    private static boolean roundsTo(BigDecimal reading, BigDecimal magnitude, BigDecimal target, boolean rate) {
        if (target.signum() == 0 || reading.scale() < 0) {
            return false;
        }
        BigDecimal scaled = target.divide(magnitude, 24, RoundingMode.HALF_UP);
        boolean correct = reading.compareTo(scaled.setScale(reading.scale(), RoundingMode.HALF_UP)) == 0
                || reading.compareTo(scaled.setScale(reading.scale(), RoundingMode.HALF_DOWN)) == 0;
        if (!correct) {
            return false;
        }
        if (rate && reading.scale() == 1) {
            return true;
        }
        BigDecimal gap = reading.multiply(magnitude).subtract(target).abs();
        return gap.compareTo(target.multiply(TOLERANCE)) <= 0;
    }

    private static boolean unitFits(Token t, PromptFact f, String currency) {
        return switch (t.unit()) {
            case NONE -> true;
            case MONEY -> f.kind() == PortfolioFacts.Kind.MONEY && currencyFits(t.currency(), currency);
            case PERCENT, BASIS_POINTS -> f.kind() == PortfolioFacts.Kind.BASIS_POINTS;
            case COUNT -> f.kind() == PortfolioFacts.Kind.COUNT;
            case DAYS -> f.kind() == PortfolioFacts.Kind.DAYS;
            case HOURS -> f.kind() == PortfolioFacts.Kind.HOURS;
        };
    }

    private static boolean currencyFits(String written, String currency) {
        if (written == null) {
            return true;
        }
        if (written.equals("SHILLING")) {
            return Set.of("UGX", "KES", "TZS").contains(currency);
        }
        return written.equals(currency);
    }

    private static boolean signFits(Token t, PromptFact f, boolean lossWord) {
        long v = f.value();
        if (f.isChange()) {
            return v == 0 || t.sign() == Sign.NONE || (t.sign() == Sign.PLUS) == (v > 0);
        }
        if (t.sign() == Sign.PLUS) {
            return false; // a plus sign claims a movement; a figure has none
        }
        if (v < 0) {
            return t.sign() == Sign.MINUS || lossWord;
        }
        return t.sign() != Sign.MINUS;
    }
}
