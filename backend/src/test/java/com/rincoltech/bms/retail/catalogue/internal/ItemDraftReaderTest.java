package com.rincoltech.bms.retail.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.retail.catalogue.internal.ItemDraftReader.Row;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The setup helper's reader of a model's answer into item import rows (issue #626, ADR-102), on fixture answers: the shop's
 * own spelling of a category and a unit, prices in a stock book's ways, a pack kept only with another unit and a size of
 * at least 1, the same name and unit kept once, the buying price dropped for a caller who may not see it, and an answer
 * that is not a list of items read as no answer. Fabricated data only.
 */
class ItemDraftReaderTest {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();

    static String fixture(String name) throws IOException {
        try (var in = ItemDraftReaderTest.class.getResourceAsStream("/setup-drafts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static ItemDraftReader reader(boolean withCost) {
        return new ItemDraftReader(
                MAPPER, List.of("Test Groceries"), List.of("Piece", "Litre", "Kg", "Carton"), 0, withCost);
    }

    @Test
    void aStockBookAnswerBecomesImportRows() throws IOException {
        List<Row> rows = reader(true).read(fixture("retail-stock-book.json")).orElseThrow();
        assertThat(rows)
                .containsExactly(
                        new Row("Test Sugar 1kg", "Test Groceries", "Piece", 5500L, 4800L, "Carton", "10"),
                        new Row("Test Cooking Oil 2L", "Test Groceries", "Litre", 12000L, null, null, null),
                        new Row("Test Nails 3 inch", "General", "Kg", null, 7000L, null, null),
                        new Row("Test Soap Bar", "Test Cleaning", "Piece", null, null, null, null),
                        new Row("Test Rice", "Test Groceries", "Kg", 4200L, null, "Bag", "50"));
        assertThat(reader(true).rows(rows)).isEqualTo(5);
    }

    @Test
    void theBuyingPriceIsDroppedForACallerWhoMayNotSeeIt() throws IOException {
        assertThat(reader(false).read(fixture("retail-stock-book.json")).orElseThrow())
                .extracting(Row::buyMinor)
                .containsOnlyNulls();
    }

    @Test
    void aFencedAnswerOrABareListIsRead() throws IOException {
        assertThat(reader(true).read(fixture("retail-fenced.txt")).orElseThrow())
                .containsExactly(new Row("Test Matches", "Test Groceries", "Box", 200L, null, null, null));
    }

    @Test
    void anAnswerThatIsNotAListOfItemsIsNoAnswer() {
        assertThat(reader(true).read("Here are your items: sugar, salt")).isEmpty();
        assertThat(reader(true).read("{\"answer\": \"sugar\"}")).isEmpty();
        assertThat(reader(true).read("{\"items\": []}")).contains(List.of());
    }

    @Test
    void decimalsFollowTheCurrency() {
        ItemDraftReader kes = new ItemDraftReader(MAPPER, List.of(), List.of(), 2, true);
        assertThat(kes.read(
                                "{\"items\": [{\"name\": \"Test Tea\", \"unit\": \"Packet\", \"sell_price\": \"120.505\"}]}")
                        .orElseThrow())
                .containsExactly(new Row("Test Tea", "General", "Packet", 12051L, null, null, null));
    }

    /** While the shop's pack capability is off no pack is kept, since the import would refuse the row. */
    @Test
    void noPackIsKeptWhileThePackCapabilityIsOff() throws IOException {
        ItemDraftReader off = new ItemDraftReader(
                MAPPER, List.of("Test Groceries"), List.of("Piece", "Litre", "Kg", "Carton"), 0, true, false);
        assertThat(off.read(fixture("retail-stock-book.json")).orElseThrow())
                .extracting(Row::packUnit)
                .containsOnlyNulls();
    }

    /** The reply shape the instructions ask for is one JSON object, with or without the buying price and the pack. */
    @Test
    void theAskedForReplyIsJsonInEveryForm() {
        for (boolean cost : List.of(true, false)) {
            for (boolean packs : List.of(true, false)) {
                String text = ItemDraftService.instructions(cost, packs);
                JsonNode shape = MAPPER.readTree(text.substring(text.indexOf("{\"items\"")));
                JsonNode item = shape.get("items").get(0);
                assertThat(item.has("buy_price")).isEqualTo(cost);
                assertThat(item.has("pack_unit")).isEqualTo(packs);
                assertThat(text.contains("bigger pack")).isEqualTo(packs);
            }
        }
    }
}
