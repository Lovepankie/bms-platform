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

The validation engine (issue #130) adds:

| File | What it is |
|---|---|
| `golden/fab-item-list.csv`, `golden/fab-buyers.csv`, `golden/fab-till-roll.csv` | The golden run: three fabricated files for the sources `item_list`, `buyer_book` (a title line, then the header on line 2) and `till_roll`, with rows that produce every code the engine reports. Line 8 of `fab-buyers.csv` holds a tab inside its quoted code, on purpose (`CODE_CONTROL_CHAR`). The names, phones and NINs are made up and listed in `GoldenRun.PERSONAL`, which the tests search the messages and logs for |
| `golden/expected-issues.txt` | The golden report: one line per issue (file, line, field, code, severity and message, separated by a vertical bar), in the engine's order. `ImportCatalogueTest` and `ImportValidationIT` compare the run with it exactly |
| `issue-codes.txt` | Every issue code ever registered, with its catalogue and severity, and `retired` once retired. A code is never removed from this file |

The retail template (issue #135) adds:

| File | What it is |
|---|---|
| `retail_v1.shop_book.mapping.json` | A mapping of a fabricated shop book in another shape than the export (one CSV per tab under `fixtures/retail/import-shapes/sheet/`, headers of its own, day-first dates, sale and entry types mapped by `enum_maps`, a derived column ignored, a quantity per shop as `BRANCH=qty` pairs) onto the production template `retail_v1`. `RetailV1TemplateIT` imports it through the framework and compares the books with the command's import of the same data as a JSON Lines export (`fixtures/retail/import-shapes/export/`). The totals line of the product master is left out in the review |
| `issue-codes.txt`, lines `retail_v1` | The codes of `retail_v1`, pinned by `RetailV1TemplateTest` |

The lending template (issue #136) adds:

| File | What it is |
|---|---|
| `lending_loans_v1.pilot_register.mapping.json` | The pilot register as the first mapping of `lending_loans_v1`: its 25 columns onto the template's fields, the row rules `subheader_month` and `placeholder_rows` (the patterns of SDD chapter 13 section 13.5), the loan product as the source's `defaults`, and a second source `register_br2` for a register of the second branch (`branch` as a default too). `LendingLoansV1TemplateTest` and `LendingLoansV1TemplateIT` read it over `fixtures/pilot_loan_register_sample.csv` |
| `lending/fab-register-branch-two.csv` | A fabricated register of the second branch (three loans: one overdue, one current with a part payment, one repaid), for the golden test at two branches |
| `lending/fab-register-overpaid.csv` | Two fabricated loans at the head office: one repaid with 40,000 more than it owed (the member credit journal), one still owing |
| `lending/fab-register-edge-cases.csv` | Fabricated rows, each producing the codes of the catalogue the pilot sample does not (with `BRANCH` and `PRODUCT` columns) |
| `issue-codes.txt`, lines `lending_loans_v1` | The codes of `lending_loans_v1`, pinned by `LendingLoansV1TemplateTest` |

The error-proofing suite (issue #137) adds:

| File | What it is |
|---|---|
| `retail/fab-edge-cases/` | A fabricated export in the shape of `fixtures/retail/import-sample/`, each row of products, sales and balances made to produce one issue code: every code of `retail_v1`'s own and the shared row codes. Line 8 of `products.jsonl` holds a bell character (U+0007) in its code, on purpose (`CODE_CONTROL_CHAR`) |
| `retail/fab-edge-cases.expected-codes.txt` | Its golden report: file, line and code, one per line. `RetailV1TemplateIT` runs the edge cases through the framework with the export's mapping and checks every line, then commits them as the dry run predicts |

The property tests (`RetailImportPropertiesIT`), the fuzzed files (`MalformedFilesTest`, `ImportMalformedFilesIT`) and the big
file test (`ImportBigFileIT`) generate their fabricated files in the test and keep none here.
