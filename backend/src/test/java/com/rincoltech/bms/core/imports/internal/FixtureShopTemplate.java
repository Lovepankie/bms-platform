package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A fabricated canonical template, registered only on the test classpath (as {@code TestApprovalAction} is), the way a
 * vertical registers its own: a bean that implements {@link ImportTemplate}, found by the registry without the core naming
 * it (issue #129). Its three entity sets use every field kind of spec section 3.2, and its mapping fixtures are in
 * {@code fixtures/imports/}. No real shop, header or value: every name here is made up.
 */
@Component
class FixtureShopTemplate implements ImportTemplate {

    static final String KEY = "fixture_shop";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public List<EntitySet> entitySets() {
        return List.of(
                new EntitySet(
                        "items",
                        List.of(
                                Field.required("code", FieldType.text(20)).normalisedBy(Normaliser.UPPER),
                                Field.required("name", FieldType.text(120)).normalisedBy(Normaliser.COLLAPSE_SPACES),
                                Field.optional("unit", FieldType.text(20)),
                                Field.optional("price", FieldType.money()),
                                Field.optional("kind", FieldType.enumOf("stock", "service")),
                                Field.optional("active", FieldType.bool())),
                        Set.of(RowRule.PLACEHOLDER_ROWS)),
                new EntitySet(
                        "customers",
                        List.of(
                                Field.required("ref_no", FieldType.text(20)),
                                Field.required("name", FieldType.text(120)).normalisedBy(Normaliser.COLLAPSE_SPACES),
                                Field.optional("phone", FieldType.phone()),
                                Field.optional("nin", FieldType.nin()),
                                Field.optional("joined_on", FieldType.date()))),
                new EntitySet(
                        "sales",
                        List.of(
                                Field.required("item", FieldType.ref("items")),
                                Field.optional("customer", FieldType.ref("customers")),
                                Field.required("sold_at", FieldType.datetime()),
                                Field.required("qty", FieldType.qty()),
                                Field.required("amount", FieldType.money()),
                                Field.required("kind", FieldType.enumOf("sale", "adjustment"))),
                        Set.of(RowRule.SUBHEADER_MONTH, RowRule.TOTALS_ROW)));
    }

    @Override
    public List<IssueCode> issueCodes() {
        return List.of(
                new IssueCode("FIXTURE_ITEM_UNNAMED", Severity.BLOCKING, "The item on line {line} has no name."));
    }

    @Override
    public BatchWriter loader(String entitySet) {
        // The commit of #133 runs it; this fixture writes nothing.
        return (runId, batchId, seq, checkpoint) -> Map.of("entity_set", entitySet, "written", 0);
    }
}
