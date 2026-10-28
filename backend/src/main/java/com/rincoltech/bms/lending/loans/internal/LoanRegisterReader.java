package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.help.DraftJson;
import com.rincoltech.bms.core.help.SetupDrafts;
import com.rincoltech.bms.kernel.PhoneNumbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a model's answer into draft loans of the existing loans import (issue #626, ADR-102). Deterministic, so the same
 * answer always gives the same loans: a date is kept only as year-month-day, an amount becomes minor units, a phone is
 * written in E.164 when it is a Uganda number, a register number repeated is kept once, and a loan without its own number
 * gets {@code <prefix>-01}, {@code -02} and so on. When the register states a monthly rate and a term but no total to
 * repay, the total is worked out as flat interest (the amount lent, plus the rate times the months) and marked so, for the
 * owner to check. Nothing here decides what is recorded: the loans fill the import's check, which keeps its own rules.
 */
final class LoanRegisterReader implements SetupDrafts.Reader<List<LoanRegisterReader.Loan>> {

    static final int MAX_LOANS = 300;
    static final int MAX_PAYMENTS = 120;

    record Payment(LocalDate date, Long amountMinor) {}

    /** One drafted loan. A value the register does not give clearly is null, for the owner to fill in. */
    record Loan(
            String ref,
            String memberName,
            String memberPhone,
            Long principalMinor,
            LocalDate disbursementDate,
            Integer termMonths,
            Long agreedTotalMinor,
            String ratePercent,
            boolean totalWorkedOut,
            List<Payment> payments) {}

    private final ObjectMapper mapper;
    private final int exponent;
    private final String refPrefix;

    LoanRegisterReader(ObjectMapper mapper, int exponent, String refPrefix) {
        this.mapper = mapper;
        this.exponent = exponent;
        this.refPrefix = refPrefix;
    }

    @Override
    public Optional<List<Loan>> read(String answer) {
        JsonNode list = DraftJson.array(mapper, answer, "loans");
        if (list == null) {
            return Optional.empty();
        }
        List<Loan> loans = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        int made = 0;
        for (JsonNode item : list) {
            if (loans.size() >= MAX_LOANS) {
                break;
            }
            String name = DraftJson.text(item, "member_name", 200);
            Long principal = minor(DraftJson.number(item, "principal"));
            if (name == null && principal == null) {
                continue;
            }
            String ref = DraftJson.text(item, "ref", 40);
            if (ref == null || !refs.add(ref.toLowerCase(Locale.ROOT))) {
                do {
                    made++;
                    ref = "%s-%02d".formatted(refPrefix, made);
                } while (!refs.add(ref.toLowerCase(Locale.ROOT)));
            }
            String phone = DraftJson.text(item, "member_phone", 30);
            if (phone != null) {
                phone = PhoneNumbers.normaliseUganda(phone).orElse(phone);
            }
            BigDecimal term = DraftJson.number(item, "term_months");
            Integer months = term != null
                            && term.stripTrailingZeros().scale() <= 0
                            && term.compareTo(BigDecimal.ONE) >= 0
                            && term.compareTo(BigDecimal.valueOf(600)) <= 0
                    ? term.intValue()
                    : null;
            BigDecimal rate = DraftJson.number(item, "rate_percent_per_month");
            if (rate != null && (rate.signum() <= 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0)) {
                rate = null;
            }
            Long total = minor(DraftJson.number(item, "total_to_repay"));
            boolean workedOut = false;
            if (total == null && principal != null && rate != null && months != null) {
                total = BigDecimal.valueOf(principal)
                        .multiply(BigDecimal.ONE.add(
                                rate.multiply(BigDecimal.valueOf(months)).movePointLeft(2)))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
                workedOut = true;
            }
            List<Payment> payments = new ArrayList<>();
            JsonNode paid = item.path("payments");
            if (paid.isArray()) {
                for (JsonNode p : paid) {
                    if (payments.size() >= MAX_PAYMENTS) {
                        break;
                    }
                    Long amount = minor(DraftJson.number(p, "amount"));
                    LocalDate date = date(DraftJson.text(p, "date", 10));
                    if (amount != null || date != null) {
                        payments.add(new Payment(date, amount));
                    }
                }
            }
            loans.add(new Loan(
                    ref,
                    name,
                    phone,
                    principal,
                    date(DraftJson.text(item, "date", 10)),
                    months,
                    total,
                    rate == null ? null : rate.stripTrailingZeros().toPlainString(),
                    workedOut,
                    List.copyOf(payments)));
        }
        return Optional.of(List.copyOf(loans));
    }

    @Override
    public int rows(List<Loan> draft) {
        return draft.size();
    }

    private Long minor(BigDecimal major) {
        if (major == null || major.signum() <= 0) {
            return null;
        }
        long minor = major.setScale(exponent, RoundingMode.HALF_UP)
                .movePointRight(exponent)
                .longValue();
        return minor <= 0 || minor > LoanApi.MAX_MONEY_MINOR ? null : minor;
    }

    /** Year-month-day only, as the import reads it; anything else is left for the owner. */
    static LocalDate date(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
