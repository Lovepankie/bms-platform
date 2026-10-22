package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Barcodes on products (ADR-070, issue #345; FR-RET-68): add, unique per business among active items, the lookup a
 * scan makes, isolation between businesses, made label codes and the import columns. Fabricated codes and rows.
 */
class RetailBarcodesIT extends IntegrationTest {

    static final String EAN = "4006381333931";
    static final String UPC = "036000291452";
    static final String ADMINISTRATOR = ADMIN + ",core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-bc", false, true);
        api = new RetailTestSupport(http, t);
    }

    ResponseEntity<JsonNode> add(UUID product, String code) {
        return api.post("/products/" + product + "/barcodes", Map.of("code", code), ADMIN);
    }

    ResponseEntity<JsonNode> lookup(String code) {
        return api.get("/products/by-barcode/" + code, SALES);
    }

    @Test
    void frRet68AnItemTakesSeveralBarcodesAndAScanFindsItByAnyOfThem() {
        UUID item = api.product("BC-1", 100, 200);
        assertThat(add(item, EAN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> second = add(item, "TEST-BC-0001");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("format").asString()).isEqualTo("code128");

        ResponseEntity<JsonNode> found = lookup(EAN);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody().get("product").get("id").asString()).isEqualTo(item.toString());
        assertThat(found.getBody().get("product").get("barcodes")).hasSize(2);
        assertThat(lookup("TEST-BC-0001").getBody().get("barcode").get("code").asString())
                .isEqualTo("TEST-BC-0001");

        JsonNode listed =
                api.get("/products?query=BC-1", ADMIN).getBody().get("items").get(0);
        assertThat(listed.get("barcodes").get(0).get("code").asString()).isEqualTo(EAN);
        assertThat(api.get("/products/" + item, ADMIN).getBody().get("barcodes"))
                .hasSize(2);
    }

    void switchUnits(boolean on) {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", on),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** An item with a carton of 12 switched on, and the carton's ETag. */
    record Carton(UUID item, UUID unit, String etag) {}

    Carton carton(String code) {
        switchUnits(true);
        UUID item = api.product(code, 100, 200);
        UUID unit = api.unit("carton-" + code.toLowerCase());
        ResponseEntity<JsonNode> pack =
                api.post("/products/" + item + "/units", Map.of("unit_id", unit, "factor", "12"), ADMIN);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);
        return new Carton(item, unit, pack.getHeaders().getETag());
    }

    ResponseEntity<JsonNode> addOnPack(Carton c, String code) {
        return api.post("/products/" + c.item() + "/barcodes", Map.of("code", code, "unit_id", c.unit()), ADMIN);
    }

    @Test
    void frRet68APackBarcodeFindsThePackAndStopsAnsweringOnceThePackIsSwitchedOff() {
        Carton c = carton("BC-PK1");
        assertThat(add(c.item(), EAN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> onPack = addOnPack(c, "TEST-BC-CARTON");
        assertThat(onPack.getStatusCode()).as("%s", onPack.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(onPack.getBody().get("unit_id").asString())
                .isEqualTo(c.unit().toString());

        ResponseEntity<JsonNode> found = lookup("TEST-BC-CARTON");
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody().get("unit_id").asString()).isEqualTo(c.unit().toString());
        assertThat(lookup(EAN).getBody().has("unit_id")).isFalse();

        ResponseEntity<JsonNode> off = api.call(
                HttpMethod.PATCH,
                "/products/" + c.item() + "/units/" + c.unit(),
                Map.of("active", false),
                ADMIN,
                "*",
                Map.of("If-Match", c.etag()));
        assertThat(off.getStatusCode()).as("%s", off.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> again = lookup("TEST-BC-CARTON");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(again.getBody().get("code").asString()).isEqualTo("pack_inactive");
        assertThat(again.getBody().get("detail").asString()).contains("switched off");
        // The item's own barcode still scans.
        assertThat(lookup(EAN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void frRet68APackBarcodeDoesNotAnswerWhileTheUnitsCapabilityIsOff() {
        Carton c = carton("BC-PK2");
        assertThat(addOnPack(c, "TEST-BC-CARTON2").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        switchUnits(false);
        ResponseEntity<JsonNode> r = lookup("TEST-BC-CARTON2");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code").asString()).isEqualTo("pack_inactive");
        assertThat(r.getBody().get("detail").asString()).contains("pack sizes are switched off");
    }

    @Test
    void frRet68TheItemAndItsPackCannotShareACode() {
        Carton c = carton("BC-PK3");
        assertThat(add(c.item(), EAN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> same = addOnPack(c, EAN);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(same.getBody().get("code").asString()).isEqualTo("duplicate_barcode");
        assertThat(addOnPack(c, "TEST-BC-CARTON3").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add(c.item(), "TEST-BC-CARTON3").getBody().get("code").asString())
                .isEqualTo("duplicate_barcode");
    }

    @Test
    void frRet68AWrongCheckDigitIsRefusedInPlainWords() {
        UUID item = api.product("BC-2", 100, 200);
        ResponseEntity<JsonNode> r = add(item, "4006381333932");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("invalid_barcode").contains("last digit");
    }

    @Test
    void frRet68ABarcodeIsUniqueAmongActiveItemsAndAUpcMatchesItsEan13Form() {
        UUID first = api.product("BC-3", 100, 200);
        UUID other = api.product("BC-4", 100, 200);
        assertThat(add(first, UPC).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add(first, UPC).getBody().get("code").asString()).isEqualTo("duplicate_barcode");
        ResponseEntity<JsonNode> taken = add(other, "0" + UPC);
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody().get("code").asString()).isEqualTo("barcode_taken");
        // A scanner that reports the UPC-A as thirteen digits finds the same item.
        assertThat(lookup("0" + UPC).getBody().get("product").get("id").asString())
                .isEqualTo(first.toString());

        // Switched off, the item no longer answers a scan and gives the code up to an active item.
        String etag = api.get("/products/" + first, ADMIN).getHeaders().getETag();
        api.call(HttpMethod.PATCH, "/products/" + first, Map.of("active", false), ADMIN, "*", Map.of("If-Match", etag));
        assertThat(lookup(UPC).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(add(other, UPC).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lookup(UPC).getBody().get("product").get("id").asString()).isEqualTo(other.toString());
        assertThat(api.get("/products/" + first + "/barcodes", ADMIN).getBody().get("items"))
                .isEmpty();
    }

    @Test
    void frRet68AnUnknownCodeAnswersBarcodeNotFound() {
        ResponseEntity<JsonNode> r = lookup("NO-SUCH-CODE");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code").asString()).isEqualTo("barcode_not_found");
    }

    @Test
    void frRet68TheLookupNeverCrossesBusinessesAndEachBusinessMayUseTheSameCode() {
        TestDatabase.Fixture other = TestDatabase.tenant("retail-bc-b", false, true);
        RetailTestSupport b = new RetailTestSupport(http, other);
        UUID theirs = b.product("BC-B", 100, 200);
        assertThat(b.post("/products/" + theirs + "/barcodes", Map.of("code", EAN), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(lookup(EAN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        UUID ours = api.product("BC-A", 100, 200);
        assertThat(add(ours, EAN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lookup(EAN).getBody().get("product").get("id").asString()).isEqualTo(ours.toString());
        assertThat(b.get("/products/by-barcode/" + EAN, SALES)
                        .getBody()
                        .get("product")
                        .get("id")
                        .asString())
                .isEqualTo(theirs.toString());
    }

    @Test
    void frRet68TheLookupCarriesTheBranchBalanceWhenAsked() {
        UUID item = api.product("BC-5", 100, 200);
        add(item, EAN);
        api.stockUp(t.headOffice(), item, "7");
        JsonNode p = api.get("/products/by-barcode/" + EAN + "?branch_id=" + t.headOffice(), SALES)
                .getBody()
                .get("product");
        assertThat(p.get("qty").asString()).isEqualTo("7.000");
    }

    @Test
    void frRet68OnlyTheCataloguePermissionChangesBarcodesAndARemovedCodeNoLongerScans() {
        UUID item = api.product("BC-6", 100, 200);
        assertThat(api.post("/products/" + item + "/barcodes", Map.of("code", EAN), SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String id = add(item, EAN).getBody().get("id").asString();
        assertThat(api.call(HttpMethod.DELETE, "/products/" + item + "/barcodes/" + id, null, SALES, "*", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.call(HttpMethod.DELETE, "/products/" + item + "/barcodes/" + id, null, ADMIN, "*", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(lookup(EAN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        long audited = TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action IN"
                        + " ('retail.barcode.added', 'retail.barcode.removed')")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(audited).isEqualTo(2);
    }

    @Test
    void frRet68LabelCodesAreMadeOncePerItemAndScan() {
        UUID one = api.product("BC-7", 100, 200);
        UUID two = api.product("BC-8", 100, 200);
        ResponseEntity<JsonNode> made =
                api.post("/products/barcodes/generate", Map.of("product_ids", List.of(one, two)), ADMIN);
        assertThat(made.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(made.getBody().get("items")).hasSize(2);
        String code = made.getBody().get("items").get(0).get("code").asString();
        assertThat(code).matches("IN\\d{8}");
        assertThat(made.getBody().get("items").get(0).get("generated").asBoolean())
                .isTrue();
        JsonNode again = api.post("/products/barcodes/generate", Map.of("product_ids", List.of(one, two)), ADMIN)
                .getBody();
        assertThat(again.get("items").get(0).get("code").asString()).isEqualTo(code);
        assertThat(lookup(code).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.post("/products/barcodes/generate", Map.of("product_ids", List.of(one)), SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void frRet68TheImportFileCarriesBarcodesAndRefusesABadOrRepeatedOne() {
        String header = "code,description,category,unit,sell price,barcode\n";
        ResponseEntity<JsonNode> dry = api.post(
                "/products/import?dry_run=true",
                Map.of(
                        "csv",
                        header
                                + "IMP-BC1,Test soap,Soap,bar,1000," + EAN + " TEST-SOAP\n"
                                + "IMP-BC2,Test oil,Oil,bottle,2000,4006381333932\n"
                                + "IMP-BC3,Test salt,Salt,packet,500," + EAN + "\n"),
                ADMINISTRATOR);
        assertThat(dry.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = dry.getBody().get("rows");
        assertThat(rows.get(0).get("outcome").asString()).isEqualTo("added");
        assertThat(rows.get(1).get("outcome").asString()).isEqualTo("error");
        assertThat(rows.get(1).get("message").asString()).contains("last digit");
        assertThat(rows.get(2).get("message").asString()).contains("repeated in the file");

        ResponseEntity<JsonNode> applied = api.post(
                "/products/import?dry_run=false",
                Map.of("csv", header + "IMP-BC1,Test soap,Soap,bar,1000," + EAN + "|TEST-SOAP\n"),
                ADMINISTRATOR);
        assertThat(applied.getStatusCode()).as("%s", applied.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lookup("TEST-SOAP").getBody().get("product").get("code").asString())
                .isEqualTo("IMP-BC1");
        assertThat(lookup(EAN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
