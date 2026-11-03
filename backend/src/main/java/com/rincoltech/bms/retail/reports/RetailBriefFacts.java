package com.rincoltech.bms.retail.reports;

import java.time.LocalDate;
import java.util.List;

/**
 * The public read port through which the retail AI brief (issue #422, ADR-037 as amended) reaches the shop figures. It answers
 * with a typed list of facts, each with its display form, whether a higher value is good news, and where it applies a change
 * against a stated comparison already computed, so a model never needs arithmetic.
 *
 * <p>It is built from the queries the shop reports already run (the dashboard, credit control, sales analysis, stock and
 * Controls), so a figure agrees with the page its chip opens. {@link #forOwner} reads every branch, every seller, cost and
 * profit, for the daily job; {@link #forCaller} keeps to what the signed-in caller may see (ADR-055): money only with
 * {@code retail.sale.all_sellers} over every branch it covers, cost and profit only with {@code retail.profit.read} there too,
 * stock counts with {@code retail.stock.read}, Controls counts with {@code retail.controls.read}, open orders with
 * {@code retail.order.manage}, and for a seller who sees only their own sales, their own sales alone. A Storekeeper therefore
 * gets stock counts and never a money figure.
 *
 * <p>What it never returns: a buyer's or a staff member's name, a phone, a sale number or any free text. An item appears as an
 * {@link Fact#item} reference; its name is in {@link FactSet#names()} only so that the brief can map {@code Item A} back for
 * the person who asked, and must never be put in a prompt or a log.
 */
public interface RetailBriefFacts {

    /** Every branch and every figure, read as of the business date {@code date}: the job's facts. Reads no principal. */
    FactSet forOwner(LocalDate date);

    /** What the signed-in caller may see, as of {@code date}. */
    FactSet forCaller(LocalDate date);

    /** The unit of a value: integer minor units of the currency, or a count. */
    enum Kind {
        MONEY,
        COUNT
    }

    /** Whether a higher value is good news ({@code HIGHER_IS_BETTER}) or bad news ({@code HIGHER_IS_WORSE}). */
    enum Polarity {
        HIGHER_IS_BETTER,
        HIGHER_IS_WORSE,
        NEUTRAL
    }

    /**
     * A change against an earlier figure, in the fact's own units. {@code display} carries its sign, for example
     * {@code +UGX 12,000}; {@code against} says what it is compared with in words, for example {@code the usual day}.
     */
    record Change(long change, String display, String against) {}

    /**
     * One fact.
     *
     * @param key a stable key, for example {@code sales.yesterday}; for an item {@code item.sales_week} with {@link #item}
     * @param display the form to put in a prompt, for example {@code UGX 1,250,000} or {@code 12}
     * @param change null when there is no comparison or it cannot be known
     * @param item the product the figure is about, by id; null for a figure of the whole business
     */
    record Fact(
            String key,
            String label,
            Kind kind,
            Polarity polarity,
            long value,
            String display,
            Change change,
            String item) {}

    /** The real name of an item, for mapping an alias back. Never for a prompt or a log. */
    record ItemName(String item, String name) {

        @Override
        public String toString() {
            return "ItemName[" + item + "]";
        }
    }

    /**
     * The facts of one view. {@code money} is true when money figures were allowed; {@code names} are kept out of
     * {@link #toString} so that a log line cannot carry them.
     */
    record FactSet(LocalDate asOf, String currency, boolean money, List<Fact> facts, List<ItemName> names) {

        public FactSet {
            facts = List.copyOf(facts);
            names = List.copyOf(names);
        }

        @Override
        public String toString() {
            return "FactSet[asOf=" + asOf + ", facts=" + facts.size() + ", items=" + names.size() + "]";
        }
    }
}
