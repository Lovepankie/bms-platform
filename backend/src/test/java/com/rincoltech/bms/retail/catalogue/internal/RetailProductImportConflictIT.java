package com.rincoltech.bms.retail.catalogue.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * An import whose rows clash with a row added after the check read the catalogue is a 409
 * {@code import_conflict}, and nothing from the file is added (#146). Fabricated rows.
 */
class RetailProductImportConflictIT extends IntegrationTest {

    static final String ADMINISTRATOR = ADMIN + ",core.settings.manage";

    @Autowired
    TestRestTemplate http;

    @MockitoSpyBean
    CatalogueRepository repo;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-impc", false, true);
        api = new RetailTestSupport(http, t);
    }

    long count(String table) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void aCodeAddedAfterTheReadIsA409AndNothingIsAdded() {
        api.product("IMP-CLASH", 100, 200);
        long products = count("retail_products");
        long categories = count("retail_categories");
        // The import reads the codes before the clashing row is committed: it sees none.
        doReturn(Set.of()).when(repo).productCodes();

        ResponseEntity<JsonNode> r = api.post(
                "/products/import?dry_run=false",
                Map.of(
                        "csv",
                        "code,description,category,unit,sell price\n"
                                + "IMP-NEW,Test new,Test New Category,roll,100\n"
                                + "IMP-CLASH,Test clash,Test New Category,roll,100\n"),
                ADMINISTRATOR);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("import_conflict");
        assertThat(count("retail_products")).isEqualTo(products);
        assertThat(count("retail_categories")).isEqualTo(categories);
    }

    /**
     * Issue #662: a pack unit switched off after the check read the units is a change made meanwhile, so the apply is a
     * 409 {@code import_conflict}, as for a code or a name, not a 422 {@code inactive_unit}, and nothing is added.
     */
    @Test
    void aPackUnitSwitchedOffAfterTheReadIsA409AndNothingIsAdded() {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> units = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", true),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(units.getStatusCode()).as("%s", units.getBody()).isEqualTo(HttpStatus.OK);
        UUID carton = api.unit("Test carton");
        TestDatabase.owner()
                .sql("UPDATE retail_units SET active = false WHERE tenant_id = ? AND id = ?")
                .params(t.tenantId(), carton)
                .update();
        long products = count("retail_products");
        // The import reads the switched off units before the unit is switched off: it sees none.
        doReturn(Set.of()).when(repo).inactiveUnitNames();

        ResponseEntity<JsonNode> r = api.post(
                "/products/import?dry_run=false",
                Map.of(
                        "csv",
                        "code,description,category,unit,sell price,pack unit,pack factor\n"
                                + "IMP-PACK,Test packed item,Test New Category,piece,100,test carton,12\n"),
                ADMINISTRATOR);

        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("import_conflict");
        assertThat(r.getBody().get("detail").asString()).contains("switched off the unit test carton");
        assertThat(count("retail_products")).isEqualTo(products);
    }
}
