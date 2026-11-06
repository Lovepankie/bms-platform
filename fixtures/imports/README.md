# Import mapping fixtures (fabricated)

Fabricated mappings for the canonical template contract of customer data onboarding (issue #129,
spec `docs/specs/customer-data-onboarding.md` section 4.2, ADR-027 decision 3). Every header name,
label, file name and value here is made up; none comes from a customer's sheet. Real mappings are
never committed: they live in the business's `import_mappings` rows on production.

They map onto `fixture_shop` version 1, a template registered only on the test classpath
(`backend/src/test/java/com/rincoltech/bms/core/imports/internal/FixtureShopTemplate.java`):

| File | What it is | What the validator says |
|---|---|---|
| `fixture_shop_v1.mapping.json` | Three sources (a CSV item list, two tabs of one workbook) using every part of a mapping: columns mapped and ignored with a reason, a fixed and an automatic header row, value, enum and unit maps, the date convention, zone and currency, the row rules each entity set offers, and both `source_ref` rules | Valid; against `fixture_shop_v1.item_list.headers.json` it adds one `COLUMN_UNMAPPED` for the column the mapping does not name |
| `fixture_shop_v1.invalid.mapping.json` | One source that feeds a field twice, leaves the required `name` without a column, maps values of a field no column feeds and forms `source_ref` from a column it does not keep | `FIELD_MAPPED_TWICE`, `REQUIRED_FIELD_UNMAPPED`, `MAP_FIELD_UNMAPPED`, `SOURCE_REF_COLUMN_UNKNOWN` |
| `fixture_shop_v1.item_list.headers.json` | The header row of a fabricated item list, with one column the mapping does not name | |

`MappingValidatorTest` and `ImportMappingsIT` read these files.
