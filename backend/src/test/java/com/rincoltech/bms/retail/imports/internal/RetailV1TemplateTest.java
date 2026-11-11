package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.RowFinding;
import com.rincoltech.bms.kernel.CompactKeys;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code retail_v1} without a database (issue #135): its field list is the normalised export's (data dictionary section 4,
 * the command's own {@link RetailImporter#FIELDS}), its codes are pinned in {@code fixtures/imports/issue-codes.txt}, its
 * rules find what the retail codes of spec section 3.4.1 say, its forecast follows the negative-stock rule, and the
 * export's mapping names every field of every set. Fabricated values only.
 */
class RetailV1TemplateTest {

    @Test
    void theFieldsAreTheNormalisedExportsAndTheSetsAreInTheCommandsOrder() {
        List<String> files = RetailV1Template.ENTITY_SETS.stream()
                .map(s -> s.key() + ".jsonl")
                .toList();
        assertThat(files).isEqualTo(RetailImporter.FILES);
        for (EntitySet set : RetailV1Template.ENTITY_SETS) {
            Set<String> fields = set.fields().stream().map(Field::name).collect(Collectors.toSet());
            assertThat(fields).as(set.key()).isEqualTo(RetailImporter.FIELDS.get(set.key() + ".jsonl"));
        }
    }

    @Test
    void theRetailCodesArePinned() throws IOException {
        Map<String, String> pinned = new TreeMap<>();
        for (String line : Files.readAllLines(Path.of("../fixtures/imports/issue-codes.txt"), StandardCharsets.UTF_8)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && "retail_v1".equals(parts[0])) {
                pinned.put(parts[1], parts[2] + (parts.length > 3 ? " " + parts[3] : ""));
            }
        }
        Map<String, String> current = new TreeMap<>();
        for (IssueCode c : template().issueCodes()) {
            current.put(c.code(), c.severity().key() + (c.retired() ? " retired" : ""));
        }
        assertThat(current).isEqualTo(pinned);
    }

    @Test
    void theRowRulesFindTheRetailCodes() {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("code", "TP-001");
        product.put("cost_minor", 800L);
        product.put("sell_minor", 1300L);
        assertThat(RetailV1Template.productFindings(product, code -> Optional.empty()))
                .extracting(RowFinding::code)
                .containsExactly("NAME_MISSING");
        product.put("description", "Test cable item 01");
        assertThat(RetailV1Template.productFindings(product, code -> Optional.of(new long[] {800, 1300})))
                .isEmpty();
        assertThat(RetailV1Template.productFindings(product, code -> Optional.of(new long[] {700, 1300})))
                .extracting(RowFinding::code)
                .containsExactly("PRICE_DIFFERS_EXISTING");

        assertThat(RetailV1Template.balanceFindings(Map.of("branch", "JJA", "qty", new BigDecimal("-3"))))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.code()).isEqualTo("NEGATIVE_BALANCE");
                    assertThat(f.params()).containsEntry("branch", "JJA");
                });
        assertThat(RetailV1Template.balanceFindings(Map.of("branch", "JJA", "qty", BigDecimal.ONE)))
                .isEmpty();
        assertThat(RetailV1Template.saleFindings(Map.of("payment_method", "credit")))
                .extracting(RowFinding::code)
                .containsExactly("CREDIT_SALE_UNPAID");
        assertThat(RetailV1Template.saleFindings(Map.of("payment_method", "cash")))
                .isEmpty();
    }

    @Test
    void theForecastJournalsOnlyWhatIsAboveZeroAndListsTheRestForTheFirstStockTake() {
        ImportForecast f = new RetailV1Template.StockForecast(Map.of("TS-009", 9_000L));
        f.batch(1);
        f.row("products", Map.of("code", "TS-001", "cost_minor", 700L));
        f.row("products", Map.of("code", "ts-001", "cost_minor", 999L));
        f.batch(2);
        f.row("balances", Map.of("branch", "KLA", "product_code", "TS-001", "qty", new BigDecimal("12.5")));
        f.row("balances", Map.of("branch", "KLA", "product_code", "TS-009", "qty", new BigDecimal("-3")));

        ImportForecast.Balances b = f.balances();

        // The first products row's cost counts; an item the business holds keeps its own (issue #73).
        assertThat(b.journals()).containsExactly(new ImportForecast.Journal("KLA", "inventory", 8_750, 8_750));
        assertThat(b.stock()).containsExactly(new ImportForecast.Stock("KLA", new BigDecimal("9.5"), 8_750 - 27_000));
        assertThat(f.productStock())
                .extracting(ImportForecast.ProductStock::product, ImportForecast.ProductStock::valueMinor)
                .containsExactly(tuple("TS-001", new BigDecimal("8750")), tuple("TS-009", new BigDecimal("-27000")));
    }

    /**
     * One opening journal per branch, ever (Lead's rule on the review of 4af7a39): a branch another run already opened is
     * predicted none, as the loader posts none there; a closed branch none either; a branch is opened once in a run.
     */
    @Test
    void theForecastPredictsOneOpeningJournalPerBranchEver() {
        ImportForecast f = new RetailV1Template.StockForecast(
                Map.of(), Set.of(RetailV1Template.compare("KLA")), Set.of(RetailV1Template.compare("JJA")), Map.of());
        f.batch(1);
        f.row("products", Map.of("code", "TS-001", "cost_minor", 700L));
        f.batch(2);
        f.row("balances", Map.of("branch", "KLA", "product_code", "TS-001", "qty", new BigDecimal("12.5")));
        f.row("balances", Map.of("branch", "ENT", "product_code", "TS-001", "qty", new BigDecimal("2")));
        f.row("balances", Map.of("branch", "JJA", "product_code", "TS-001", "qty", new BigDecimal("1")));
        f.batch(3);
        f.row("balances", Map.of("branch", "ENT", "product_code", "TS-002", "qty", new BigDecimal("5")));

        // KLA is already opened; ENT is opened by batch 2 alone (2 x 700), so batch 3 adds no second journal; JJA is
        // closed.
        assertThat(f.balances().journals())
                .containsExactly(
                        new ImportForecast.Journal(RetailV1Template.compare("ENT"), "inventory", 1_400, 1_400));
    }

    // JUSTIFICATION-A3: the Lead's steer for round 4 asks the shared netting function to be shown; a test of its own.
    /** The one rule the loader posts and the forecast predicts (Lead's steer on the review of 51cfb70). */
    @Test
    void theOpeningOfABranchValuesItsBalanceRowsAndJournalsOnlyThoseAboveZero() {
        Map<String, BigDecimal> rows = new LinkedHashMap<>();
        rows.put("TS-001", new BigDecimal("12.5"));
        rows.put("TS-009", new BigDecimal("-3"));
        Map<String, Long> cost = Map.of("TS-001", 700L, "TS-009", 9_000L);

        RetailV1Template.BranchOpening o = RetailV1Template.branchOpening(rows, cost::get);

        assertThat(o.qty()).isEqualByComparingTo("9.5");
        assertThat(o.valueMinor()).isEqualTo(8_750 - 27_000);
        assertThat(o.aboveZeroMinor()).isEqualTo(8_750);
        assertThat(o.journal(false)).as("the first journal of the branch").isEqualTo(8_750);
        assertThat(o.journal(true)).as("a branch already opened takes none").isZero();

        // An item whose legacy balance an earlier import wrote stands at the books' balance (review of bf87e45).
        assertThat(RetailV1Template.openingQuantities(rows, Map.of("TS-001", BigDecimal.ZERO)))
                .containsEntry("TS-001", BigDecimal.ZERO)
                .containsEntry("TS-009", new BigDecimal("-3"));
    }

    // JUSTIFICATION-A3: issue #876 asks a test of should-fix 1 and 2; these are three tests of the forecast.
    /**
     * Issue #876, should-fix 1 and 2: TS-001's legacy balance an earlier import wrote at HQ, which is not opened, and the
     * books hold 0. This run restocks 3, sells 1 (the same sale twice, written once) and sends a balance of 5. The loader
     * reads the books after the history, 2, so the opening values 2 and the run puts 2 on hand.
     */
    @Test
    void theForecastValuesALegacyHeldItemAfterThisRunsHistoryAsTheLoaderReadsIt() {
        Map<String, Map<String, RetailV1Template.Held>> books =
                Map.of("HQ", Map.of("TS-001", new RetailV1Template.Held(BigDecimal.ZERO, true)));
        ImportForecast f = new RetailV1Template.StockForecast(Map.of("TS-001", 100L), Set.of(), Set.of(), books);
        f.batch(1);
        f.row(
                "purchases",
                Map.of("source_ref", "P-2", "product_code", "TS-001", "kind", "purchase", "qty_by_branch", "HQ=3"));
        f.batch(2);
        Map<String, Object> sale =
                Map.of("source_ref", "S-1", "branch", "HQ", "product_code", "TS-001", "qty", BigDecimal.ONE);
        f.row("sales", sale);
        f.row("sales", sale);
        f.batch(3);
        f.row("balances", Map.of("branch", "HQ", "product_code", "TS-001", "qty", new BigDecimal("5")));

        assertThat(f.balances().journals()).containsExactly(new ImportForecast.Journal("HQ", "inventory", 200, 200));
        assertThat(f.productStock()).singleElement().satisfies(p -> {
            assertThat(p.product()).isEqualTo("TS-001");
            assertThat(p.qty()).as("this run's history, not the file's 5").isEqualByComparingTo("2");
        });
    }

    /**
     * Issue #876: at a branch already opened the loader leaves an item the books hold as they hold it, so the run puts
     * nothing on hand for it; a new item there takes its legacy balance. A sale other imports hold moves nothing.
     */
    @Test
    void theForecastsStockPerItemIsWhatTheRunPutsOnHand() {
        CompactKeys earlier = new CompactKeys();
        earlier.putIfAbsent("sales|S-OLD", 0L);
        ImportForecast f = new RetailV1Template.StockForecast(
                Map.of("TS-002", 200L, "TS-003", 300L),
                Set.of("HQ"),
                Set.of(),
                Map.of("HQ", Map.of("TS-002", new RetailV1Template.Held(new BigDecimal("2"), false))),
                earlier);
        f.batch(1);
        f.row("sales", Map.of("source_ref", "S-OLD", "branch", "HQ", "product_code", "TS-002", "qty", BigDecimal.ONE));
        f.batch(2);
        f.row("balances", Map.of("branch", "HQ", "product_code", "TS-002", "qty", BigDecimal.ONE));
        f.row("balances", Map.of("branch", "HQ", "product_code", "TS-003", "qty", new BigDecimal("3")));

        assertThat(f.balances().journals()).as("HQ is opened").isEmpty();
        assertThat(f.productStock())
                .extracting(ImportForecast.ProductStock::product, p -> p.qty().stripTrailingZeros())
                .containsExactly(tuple("TS-002", BigDecimal.ZERO), tuple("TS-003", new BigDecimal("3")));
    }

    /**
     * Review of db86c31 on #908: at a closed branch the loader writes the legacy balance and posts no journal, so the
     * forecast predicts no journal there and still counts the stock the run puts on hand, before and after balances().
     */
    @Test
    void aClosedBranchTakesNoJournalButItsStockIsCounted() {
        ImportForecast f =
                new RetailV1Template.StockForecast(Map.of("TS-001", 100L), Set.of(), Set.of("KLA"), Map.of());
        f.batch(1);
        f.row("balances", Map.of("branch", "KLA", "product_code", "TS-001", "qty", new BigDecimal("4")));
        f.batch(2);

        assertThat(f.productStock())
                .extracting(ImportForecast.ProductStock::branch, p -> p.qty().stripTrailingZeros())
                .containsExactly(tuple("KLA", new BigDecimal("4")));
        assertThat(f.balances().journals()).isEmpty();
        assertThat(f.balances().stock()).containsExactly(new ImportForecast.Stock("KLA", new BigDecimal("4"), 400));
    }

    /** The stock a history row moves, read as the loader reads it; none for a row the loader refuses (issue #876). */
    @Test
    void aHistoryRowMovesTheStockTheLoaderWrites() {
        assertThat(RetailV1Template.historyMoves("purchases", restock("adjustment", "hq=-2; KLA=0; ENT=1.5")))
                .containsExactly(
                        new RetailV1Template.Move("HQ", "ts-001", new BigDecimal("-2.000")),
                        new RetailV1Template.Move("ENT", "ts-001", new BigDecimal("1.500")));
        assertThatThrownBy(() -> RetailV1Template.historyMoves("purchases", restock("return", "HQ=-2")))
                .isInstanceOf(ExportFile.RowProblem.class)
                .hasMessage("a return quantity must be positive, branch HQ");
        assertThatThrownBy(() -> RetailV1Template.historyMoves("purchases", restock("purchase", "HQ=1; hq=2")))
                .hasMessage("branch hq appears twice in qty_by_branch");
        assertThatThrownBy(() -> RetailV1Template.historyMoves("purchases", restock("purchase", "HQ=0")))
                .hasMessage("no quantity in qty_by_branch");
        assertThat(RetailV1Template.historyMoves(
                        "usage", row(Map.of("branch", "HQ", "product_code", "TS-001", "qty", BigDecimal.TEN))))
                .containsExactly(new RetailV1Template.Move("HQ", "TS-001", new BigDecimal("-10.000")));
        assertThatThrownBy(() -> RetailV1Template.historyMoves(
                        "sales", row(Map.of("branch", "HQ", "product_code", "TS-001", "qty", BigDecimal.ZERO))))
                .hasMessage("qty must be positive");
        Set<String> refs = new java.util.HashSet<>();
        RetailV1Template.refOnce(restock("purchase", "HQ=1"), "P-1", refs);
        assertThatThrownBy(() -> RetailV1Template.refOnce(restock("purchase", "HQ=1"), "P-1", refs))
                .hasMessage("duplicate source_ref P-1");
    }

    /** A restock row of the fabricated item ts-001 as the validation types it, read as the loader reads it. */
    private static ExportFile.Row restock(String kind, String qtyByBranch) {
        return row(Map.of("source_ref", "P-1", "product_code", "ts-001", "kind", kind, "qty_by_branch", qtyByBranch));
    }

    private static ExportFile.Row row(Map<String, Object> values) {
        return ExportFile.staged("rows.jsonl", List.of(Map.entry(1, values)))
                .rows
                .getFirst();
    }

    @Test
    void theExportsMappingNamesEveryFieldAndKnowsTheHistoryByItsReference() {
        Map<String, Object> mapping = RetailV1Template.exportMapping(ZoneId.of("Africa/Kampala"));
        assertThat(mapping).containsEntry("template", "retail").containsEntry("template_version", 1);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> sources = (Map<String, Map<String, Object>>) mapping.get("sources");
        assertThat(sources.keySet()).containsExactlyElementsOf(RetailV1Template.SETS);
        assertThat(sources.get("sales").get("source_ref")).isEqualTo(Map.of("rule", "column", "column", "source_ref"));
        assertThat(sources.get("balances").get("source_ref")).isEqualTo(Map.of("rule", "row_hash"));
        assertThat(RetailImporter.historyKey("sales", "SAL-00011")).isEqualTo("sales.jsonl|-|ref:SAL-00011");
    }

    @Test
    void aSheetsQuantityPerBranchReadsAsTheExportsObject() {
        assertThat(ExportFile.branchQuantities("KLA=4; ENT=-2.5").toString())
                .isEqualTo(ExportFile.branchQuantities("{\"KLA\":\"4\",\"ENT\":\"-2.5\"}")
                        .toString());
        assertThat(ExportFile.branchQuantities("{KLA=4, ENT=-2.5}").toString())
                .as("a JSON Lines object as the staged row holds it")
                .isEqualTo("{\"KLA\":\"4\",\"ENT\":\"-2.5\"}");
        assertThat(ExportFile.branchQuantities("not a list").isString()).isTrue();
    }

    private static RetailV1Template template() {
        return new RetailV1Template(null, null, null, null, null);
    }
}
