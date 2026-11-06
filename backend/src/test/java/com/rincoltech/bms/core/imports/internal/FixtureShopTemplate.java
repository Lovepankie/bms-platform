package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportTemplate;
import java.math.BigDecimal;
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

    /** An item the business already holds, so a sale may name it without an items row in the run. */
    static final String EXISTING_ITEM = "FAB-900";

    static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    /** What a test does between the validation's two passes, where the engine asks for the business's keys; or null. */
    static volatile Runnable BETWEEN_PASSES;

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
                                Field.required("code", FieldType.text(20))
                                        .normalisedBy(Normaliser.UPPER)
                                        .asKey(),
                                Field.required("name", FieldType.text(120)).normalisedBy(Normaliser.COLLAPSE_SPACES),
                                Field.optional("unit", FieldType.text(20)),
                                Field.optional("price", FieldType.money()).range(BigDecimal.ZERO, MAX_PRICE),
                                Field.optional("kind", FieldType.enumOf("stock", "service")),
                                Field.optional("active", FieldType.bool())),
                        Set.of(RowRule.PLACEHOLDER_ROWS)),
                new EntitySet(
                        "customers",
                        List.of(
                                Field.required("ref_no", FieldType.text(20)).asKey(),
                                Field.required("name", FieldType.text(120))
                                        .normalisedBy(Normaliser.COLLAPSE_SPACES)
                                        .personalData(),
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

    /**
     * Its own codes (issue #130): {@code FIXTURE_ITEM_UNNAMED} is retired, kept and marked, since the required flag of
     * {@code name} reports an empty name as {@code REQUIRED_MISSING}; {@code FIXTURE_SALE_QTY_NOT_POSITIVE} is the cross-field
     * rule of a sale. JUSTIFICATION-A3: the fixture template exercises the new contract of #130.
     */
    @Override
    public List<IssueCode> issueCodes() {
        return List.of(
                new IssueCode("FIXTURE_ITEM_UNNAMED", Severity.BLOCKING, "The item on line {line} has no name.", true),
                new IssueCode(
                        "FIXTURE_SALE_QTY_NOT_POSITIVE",
                        Severity.BLOCKING,
                        "{field} on {file} line {line} must be above zero for a sale."));
    }

    /** A sale sells something: its quantity is above zero; an adjustment may take stock off. */
    @Override
    public List<RowCheck> rowChecks(String entitySet) {
        if (!"sales".equals(entitySet)) {
            return List.of();
        }
        return List.of(values ->
                "sale".equals(values.get("kind")) && values.get("qty") instanceof BigDecimal qty && qty.signum() <= 0
                        ? List.of(new RowFinding("FIXTURE_SALE_QTY_NOT_POSITIVE", "qty", Map.of()))
                        : List.of());
    }

    /** The business already holds the item {@code FAB-900} (in compare form), as a vertical's own table would answer. */
    @Override
    public Set<String> existingKeys(String entitySet, Set<String> keys) {
        Runnable between = BETWEEN_PASSES;
        if (between != null) {
            between.run();
        }
        return "items".equals(entitySet) && keys.contains(EXISTING_ITEM) ? Set.of(EXISTING_ITEM) : Set.of();
    }

    @Override
    public BatchWriter loader(String entitySet) {
        // The commit of #133 runs it; this fixture writes nothing.
        return (runId, batchId, seq, checkpoint) -> Map.of("entity_set", entitySet, "written", 0);
    }
}
