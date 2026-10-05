package com.rincoltech.bms.lending.loans.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules-based default score of chapter 3 section 3.18.1 (pending ADR-012 for anything
 * statistical). A pure function of its inputs and the tenant's weights: each weight is a
 * component's maximum points (defaults 40, 30, 20, 10). It recommends; it never approves.
 */
final class CreditScore {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal LOW_Q = new BigDecimal("0.30");
    private static final BigDecimal HIGH_Q = new BigDecimal("0.60");
    private static final BigDecimal FULL_COVER = new BigDecimal("1.5");

    private CreditScore() {}

    /**
     * @param closedLoans the member's loans closed so far
     * @param closedOnTime of those, closed with max DPD at most 7 (DPD history arrives with the
     *     daily snapshots of increment 6; until then a closed loan counts as on time)
     * @param monthlyInstalmentMinor this loan's largest instalment converted to a monthly amount
     * @param declaredIncomeMinor declared monthly income, null when not declared
     * @param minCoverBp the product's minimum collateral cover, null when it sets none
     */
    record Inputs(
            int closedLoans,
            int closedOnTime,
            int writtenOff,
            long monthlyInstalmentMinor,
            Long declaredIncomeMinor,
            long monthlyObligationsMinor,
            long pledgedValueMinor,
            long principalMinor,
            boolean otherLoanInArrears,
            boolean linkedPartyOver30,
            int otherActiveLoans,
            boolean newMember,
            Integer minCoverBp) {}

    record Result(int score, String band, Map<String, Object> components, List<String> flags, String recommendation) {}

    static Result score(Inputs in, Map<String, Integer> weights) {
        List<String> flags = new ArrayList<>();
        Map<String, Object> components = new LinkedHashMap<>();

        int history = repaymentHistory(in, weights.get("repayment_history"));
        components.put(
                "repayment_history",
                Map.of(
                        "points", history,
                        "max", weights.get("repayment_history"),
                        "closed_loans", in.closedLoans(),
                        "closed_on_time", in.closedOnTime(),
                        "written_off", in.writtenOff()));

        int affordability;
        Map<String, Object> afford = new LinkedHashMap<>();
        if (in.declaredIncomeMinor() == null || in.declaredIncomeMinor() <= 0) {
            affordability = 0;
            flags.add("INCOME_NOT_DECLARED");
        } else {
            // Summed as BigDecimal: a long sum could overflow to a negative ratio and score as affordable.
            BigDecimal q = BigDecimal.valueOf(in.monthlyInstalmentMinor())
                    .add(BigDecimal.valueOf(in.monthlyObligationsMinor()))
                    .divide(BigDecimal.valueOf(in.declaredIncomeMinor()), MC);
            affordability = linearDown(q, LOW_Q, HIGH_Q, weights.get("affordability"));
            afford.put("ratio", q.setScale(4, RoundingMode.HALF_UP));
        }
        afford.put("points", affordability);
        afford.put("max", weights.get("affordability"));
        afford.put("monthly_instalment_minor", in.monthlyInstalmentMinor());
        afford.put("monthly_obligations_minor", in.monthlyObligationsMinor());
        afford.put("declared_income_minor", in.declaredIncomeMinor());
        components.put("affordability", afford);

        BigDecimal cover =
                BigDecimal.valueOf(in.pledgedValueMinor()).divide(BigDecimal.valueOf(in.principalMinor()), MC);
        int coverPoints = cover.compareTo(FULL_COVER) >= 0
                ? weights.get("collateral_cover")
                : round(BigDecimal.valueOf(weights.get("collateral_cover"))
                        .multiply(cover, MC)
                        .divide(FULL_COVER, MC));
        components.put(
                "collateral_cover",
                Map.of(
                        "points", coverPoints,
                        "max", weights.get("collateral_cover"),
                        "cover", cover.setScale(4, RoundingMode.HALF_UP),
                        "pledged_value_minor", in.pledgedValueMinor()));
        if (in.minCoverBp() != null
                && cover.multiply(BigDecimal.valueOf(10_000)).compareTo(BigDecimal.valueOf(in.minCoverBp())) < 0) {
            flags.add("COLLATERAL_BELOW_PRODUCT_MINIMUM");
        }

        int exposureMax = weights.get("exposure");
        BigDecimal exposure = BigDecimal.valueOf(exposureMax);
        BigDecimal half = BigDecimal.valueOf(exposureMax).divide(BigDecimal.valueOf(2), MC);
        if (in.otherLoanInArrears()) {
            exposure = exposure.subtract(half);
            flags.add("EXISTING_LOAN_IN_ARREARS");
        }
        if (in.linkedPartyOver30()) {
            exposure = exposure.subtract(half);
            flags.add("LINKED_PARTY_IN_ARREARS");
        }
        int exposurePoints = Math.max(0, round(exposure));
        components.put("exposure", Map.of("points", exposurePoints, "max", exposureMax));

        if (in.otherActiveLoans() > 0) {
            flags.add("MULTIPLE_ACTIVE_LOANS");
        }
        if (in.newMember()) {
            flags.add("NEW_MEMBER");
        }

        int score = Math.clamp(history + affordability + coverPoints + exposurePoints, 0, 100);
        String band = score >= 75 ? "A" : score >= 60 ? "B" : score >= 45 ? "C" : "D";
        String recommendation = switch (band) {
            case "A", "B" -> "approve";
            case "C" -> "review";
            default -> "decline";
        };
        return new Result(score, band, components, List.copyOf(flags), recommendation);
    }

    /** No prior loans: half the maximum. Otherwise the on-time share, minus a quarter of the maximum per write-off. */
    private static int repaymentHistory(Inputs in, int max) {
        if (in.closedLoans() == 0 && in.writtenOff() == 0) {
            return round(BigDecimal.valueOf(max).divide(BigDecimal.valueOf(2), MC));
        }
        BigDecimal base = in.closedLoans() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(max)
                        .multiply(BigDecimal.valueOf(in.closedOnTime()), MC)
                        .divide(BigDecimal.valueOf(in.closedLoans()), MC);
        BigDecimal penalty = BigDecimal.valueOf(max)
                .multiply(BigDecimal.valueOf(in.writtenOff()), MC)
                .divide(BigDecimal.valueOf(4), MC);
        return Math.max(0, round(base.subtract(penalty)));
    }

    /** Full points at or below {@code low}, none at or above {@code high}, linear between. */
    private static int linearDown(BigDecimal x, BigDecimal low, BigDecimal high, int max) {
        if (x.compareTo(low) <= 0) {
            return max;
        }
        if (x.compareTo(high) >= 0) {
            return 0;
        }
        return round(BigDecimal.valueOf(max).multiply(high.subtract(x), MC).divide(high.subtract(low), MC));
    }

    private static int round(BigDecimal x) {
        return x.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /**
     * Chapter 3 section 3.18.1: an instalment as a monthly amount. Daily x 30, weekly x 52 / 12,
     * fortnightly x 26 / 12, monthly x 1; a bullet is the total due over the term in months
     * (at least 1).
     */
    static long monthly(long largestInstalmentMinor, String pattern, String frequency, String termUnit, int termCount) {
        BigDecimal amount = BigDecimal.valueOf(largestInstalmentMinor);
        if ("bullet".equals(pattern)) {
            BigDecimal months = switch (termUnit) {
                case "day" -> BigDecimal.valueOf(termCount).divide(BigDecimal.valueOf(30), MC);
                case "week" -> BigDecimal.valueOf(termCount * 7L).divide(BigDecimal.valueOf(30), MC);
                default -> BigDecimal.valueOf(termCount);
            };
            return round(amount.divide(months.max(BigDecimal.ONE), MC));
        }
        BigDecimal factor = switch (frequency) {
            case "daily" -> BigDecimal.valueOf(30);
            case "weekly" -> BigDecimal.valueOf(52).divide(BigDecimal.valueOf(12), MC);
            case "fortnightly" -> BigDecimal.valueOf(26).divide(BigDecimal.valueOf(12), MC);
            default -> BigDecimal.ONE;
        };
        return round(amount.multiply(factor, MC));
    }
}
