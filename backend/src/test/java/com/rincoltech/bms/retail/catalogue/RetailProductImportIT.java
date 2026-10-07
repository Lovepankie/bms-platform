package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** The CSV product import for a new client (#146): dry run, apply, idempotency, permissions. Fabricated rows. */
class RetailProductImportIT extends IntegrationTest {

    static final String ADMINISTRATOR = ADMIN + ",core.settings.manage";
    static final String HEADER = "code,description,category,unit,sell price";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-imp", false, true);
        api = new RetailTestSupport(http, t);
    }

    ResponseEntity<JsonNode> run(String csv, boolean dry, String permissions) {
        return api.post("/products/import?dry_run=" + dry, Map.of("csv", csv), permissions);
    }

    long products() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_products WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void aDryRunReportsAddedSkippedAndErrorsAndChangesNothing() {
        api.product("IMP-OLD", 100, 200);
        long before = products();
        String csv = HEADER + "\n"
                + "IMP-1,Test cable,Cables,roll,\"12,000\"\n"
                + "imp-old,Test existing,Cables,roll,500\n"
                + "IMP-2,Test bulb,Lighting,piece,abc\n"
                + "IMP-1,Test cable again,Cables,roll,13000\n"
                + "\n"
                + "IMP-3,\"Test, with comma\",Lighting,piece,900\n";
        ResponseEntity<JsonNode> dry = run(csv, true, ADMINISTRATOR);
        assertThat(dry.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = dry.getBody();
        assertThat(body.get("dry_run").asBoolean()).isTrue();
        assertThat(body.get("added").asInt()).isEqualTo(2);
        assertThat(body.get("skipped").asInt()).isEqualTo(2);
        assertThat(body.get("errors").asInt()).isEqualTo(1);
        assertThat(body.get("rows_read").asInt()).isEqualTo(5);
        assertThat(body.get("rows").get(2).get("outcome").asString()).isEqualTo("error");
        assertThat(body.get("rows").get(2).get("line").asInt()).isEqualTo(4);
        assertThat(body.get("categories_created").toString()).contains("Lighting");
        assertThat(products()).isEqualTo(before);
    }

    @Test
    void anApplyWithAnErrorRowAddsNothing() {
        long before = products();
        ResponseEntity<JsonNode> r =
                run(HEADER + "\nIMP-1,Test cable,Cables,roll,100\nIMP-2,Test,Cables,roll,x\n", false, ADMINISTRATOR);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("import_has_errors");
        assertThat(products()).isEqualTo(before);
    }

    @Test
    void anApplyAddsItemsCreatesCategoriesAndUnitsAndIsIdempotentOnTheCode() {
        // A tab separated paste from a spreadsheet, with a byte order mark, a no-break space and a full-width code.
        String csv = "﻿" + HEADER.replace(',', '\t') + "\n"
                + " IMP-A \tTest cable\tCables\troll\t12000\n"
                + "ＩＭＰ-B\tTest bulb\tlighting\tPiece\t900\n";
        ResponseEntity<JsonNode> first = run(csv, false, ADMINISTRATOR);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("added").asInt()).isEqualTo(2);
        assertThat(first.getBody().get("categories_created")).hasSize(2);
        assertThat(api.get("/products?query=imp-a", ADMIN).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/products?query=IMP-B", ADMIN).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/categories", ADMIN).getBody().get("items")).hasSize(2);
        long after = products();

        ResponseEntity<JsonNode> again = run(csv.replace("12000", "99999"), false, ADMINISTRATOR);
        assertThat(again.getBody().get("added").asInt()).isZero();
        assertThat(again.getBody().get("skipped").asInt()).isEqualTo(2);
        assertThat(again.getBody().get("categories_created")).isEmpty();
        assertThat(products()).isEqualTo(after);
        assertThat(api.get("/products?query=imp-a", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(12_000);

        String audited = TestDatabase.owner()
                .sql("SELECT string_agg(cast(data AS text), ' ') FROM audit_log WHERE tenant_id = ?"
                        + " AND action IN ('retail.product.created', 'retail.product.imported')")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(audited).contains("\"added\"").doesNotContain("cost");
    }

    @Test
    void onlyAnAdministratorWithTheCataloguePermissionMayImport() {
        String csv = HEADER + "\nIMP-1,Test cable,Cables,roll,100\n";
        assertThat(run(csv, true, ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(csv, true, SALES + ",core.settings.manage").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(csv, false, ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(products()).isZero();
    }

    @Test
    void theCostColumnIsAcceptedOnlyWithProfitRead() {
        String csv = HEADER + ",cost price\nIMP-1,Test cable,Cables,roll,1000,700\n";
        String noProfit = "retail.catalogue.manage,retail.stock.read,core.settings.manage";
        ResponseEntity<JsonNode> refused = run(csv, true, noProfit);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("cost_not_allowed");
        assertThat(run(HEADER + "\nIMP-1,Test cable,Cables,roll,1000\n", false, noProfit)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(run(csv.replace("IMP-1", "IMP-2"), false, ADMINISTRATOR).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode item =
                api.get("/products?query=IMP-2", ADMIN).getBody().get("items").get(0);
        assertThat(item.get("cost_minor").asLong()).isEqualTo(700);
        JsonNode plain = api.get("/products?query=IMP-2", "retail.stock.read")
                .getBody()
                .get("items")
                .get(0);
        assertThat(plain.has("cost_minor")).isFalse();
        ResponseEntity<JsonNode> below =
                run(csv.replace("IMP-1", "IMP-3").replace("1000,700", "600,700"), true, ADMINISTRATOR);
        assertThat(below.getBody().get("errors").asInt()).isEqualTo(1);
    }

    @Test
    void aFileWithoutTheRequiredColumnsIsRefusedInPlainWords() {
        ResponseEntity<JsonNode> r = run("code,description\nIMP-1,Test\n", true, ADMINISTRATOR);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("needs a column called category");
    }

    @Test
    void anotherTenantsCodesAreNotSeen() {
        api.product("IMP-SAME", 100, 200);
        TestDatabase.Fixture other = TestDatabase.tenant("retail-imp-2", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        ResponseEntity<JsonNode> r = theirs.post(
                "/products/import?dry_run=true",
                Map.of("csv", HEADER + "\nIMP-SAME,Test,Cables,roll,100\n"),
                ADMINISTRATOR);
        assertThat(r.getBody().get("added").asInt()).isEqualTo(1);
        assertThat(r.getBody().get("skipped").asInt()).isZero();
    }
}
