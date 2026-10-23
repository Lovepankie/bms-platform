package com.rincoltech.bms.retail.sales.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #500: the screen's receipt is read from the receipt PDF's model without a cast that can throw, so an odd value
 * (a line number stored as a long, a missing map, a list of something else) prints blank instead of failing the request.
 */
class ReceiptPrintTest {

    final DocumentModels models = new DocumentModels(null);

    @Test
    void anEmptyModelPrintsBlankAndThrowsNothing() {
        DocumentsApi.ReceiptPrint p = models.printed(Map.of());
        assertThat(p.title()).isEmpty();
        assertThat(p.lines()).isEmpty();
        assertThat(p.returns()).isEmpty();
        assertThat(p.net()).isNull();
        assertThat(p.status()).isNull();
        assertThat(p.logoUrl()).isNull();
    }

    @Test
    void oddValuesAreReadSafely() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("no", 3L);
        line.put("description", "Test Soap 500g");
        line.put("quantity", "5");
        line.put("pack", null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lh", "not a map");
        m.put("branch", null);
        m.put("lines", List.of(line, "not a line"));
        m.put("returns", Map.of("lines", "not a list", "net", "5,000"));
        DocumentsApi.ReceiptPrint p = models.printed(m);
        assertThat(p.lines()).hasSize(2);
        assertThat(p.lines().get(0).no()).isEqualTo(3);
        assertThat(p.lines().get(0).quantity()).isEqualTo("5");
        assertThat(p.lines().get(0).pack()).isNull();
        assertThat(p.lines().get(1).no()).isEqualTo(2);
        assertThat(p.returns()).isEmpty();
        assertThat(p.net()).isEqualTo("5,000");
        assertThat(p.businessName()).isEmpty();
        assertThat(p.logoUrl()).isNull();
    }

    @Test
    void theLogoIsTheOneTheLetterheadOfTheModelPrints() {
        Map<String, Object> lh = new LinkedHashMap<>();
        lh.put("logo", "data:image/png;base64,AAAA");
        lh.put("logoUrl", "/api/v1/branding/logo?v=1");
        assertThat(models.printed(Map.of("lh", lh)).logoUrl()).isEqualTo("/api/v1/branding/logo?v=1");
    }

    @Test
    void theLineUnderTheDescriptionIsTheCodeAndThePriceList() {
        assertThat(DocumentModels.detail("TST-01", null)).isEqualTo("TST-01");
        assertThat(DocumentModels.detail("TST-01", "Wholesale")).isEqualTo("TST-01  |  Wholesale");
    }

    @Test
    void aQuantityWithoutAUnitHasNoTrailingSpace() {
        assertThat(DocumentModels.quantity("5.000", null)).isEqualTo("5");
        assertThat(DocumentModels.quantity("2.500", "carton")).isEqualTo("2.5 carton");
    }
}
