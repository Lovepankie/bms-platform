package com.rincoltech.bms.core.imports;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What a template predicts its commit will post (issue #132, spec section 3.6, FR-IMP-06): the SPI behind the preview's
 * predicted balances. The preview makes one from {@link ImportTemplate#forecast()} per preview and gives it, in commit order
 * (the template's entity sets, then each batch's order, then line), the typed values of every row the commit would write: a
 * row of a parsed batch that is not excluded, not blank and has no open blocking issue. Values are typed as the validation
 * types them: text, {@code Long} for money, {@code BigDecimal} for qty, {@code LocalDate}, {@code Instant},
 * {@code Boolean}. A template computes them with the same rules its loader posts by, so the preview's figures equal what
 * the commit produces, and its golden test proves it.
 */
public interface ImportForecast {

    /**
     * A batch begins: the rows that follow, until the next call, are that batch's (issue #133). The commit posts an opening
     * journal per batch, branch and kind, so a template whose batches each hold a kind predicts one journal per batch.
     * Nothing by default.
     */
    default void batch(int seq) {}

    /** One row the commit would write, of one entity set. */
    void row(String entitySet, Map<String, Object> values);

    /** The predicted balances once every row is seen. */
    Balances balances();

    /**
     * The stock once every row is seen, per branch and product (issue #134): the reconciliation's quantity check, the
     * balances checksum of spec section 3.9, compares it product by product with the stock ledger. The product is the
     * template's own code for it, never a personal value. None by default, for a template that keeps no stock.
     */
    default List<ProductStock> productStock() {
        return List.of();
    }

    /**
     * Stock of one product at one branch: the quantity and its value at cost in minor units (issue #134), an exact decimal,
     * since a fractional quantity at a whole unit cost need not be whole: never rounded.
     */
    record ProductStock(String branch, String product, BigDecimal qty, BigDecimal valueMinor) {}

    /** A forecast that predicts nothing: the default of a template that posts no opening balance. */
    ImportForecast NONE = new ImportForecast() {
        @Override
        public void row(String entitySet, Map<String, Object> values) {}

        @Override
        public Balances balances() {
            return Balances.NONE;
        }
    };

    /**
     * The predicted balances of spec section 3.6, each per branch code: stock quantity and value, receivables, loan principal
     * outstanding, loans by arrears bucket, and each opening journal with its debit and credit totals in minor units.
     */
    record Balances(
            List<Stock> stock,
            List<Amount> receivables,
            List<Amount> loanPrincipal,
            List<ArrearsBucket> arrears,
            List<Journal> journals) {

        public static final Balances NONE = new Balances(List.of(), List.of(), List.of(), List.of(), List.of());

        public Balances {
            stock = List.copyOf(stock);
            receivables = List.copyOf(receivables);
            loanPrincipal = List.copyOf(loanPrincipal);
            arrears = List.copyOf(arrears);
            journals = List.copyOf(journals);
        }
    }

    /** Stock of one branch: the quantity on hand and its value at cost, in minor units. */
    record Stock(String branch, BigDecimal qty, long valueMinor) {}

    /** An amount of one branch, in minor units. */
    record Amount(String branch, long amountMinor) {}

    /** The loans of one branch in one days-past-due bucket (for example {@code 1-30}) and their principal outstanding. */
    record ArrearsBucket(String branch, String bucket, int loans, long principalMinor) {}

    /** One opening journal the commit would post: the branch, the kind and its debit and credit totals. */
    record Journal(String branch, String kind, long debitMinor, long creditMinor) {}
}
