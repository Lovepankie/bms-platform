# Runbook: import a retail tenant's history (`import-retail`)

**Requirements:** FR-RET-12 · **Decisions:** ADR-003, ADR-004, ADR-020 (decisions 4, 8 and 9) ·
**Design:** SDD chapter 13 section 13.13, chapter 6 section 6.11.4 · **Format:**
`docs/specs/retail-pilot-data-dictionary.md` section 4

The command imports a retail tenant's history once, at cutover: catalogue, suppliers, credit buyers,
historical sales, restocks, adjustments, returns and usage, a legacy balance per branch and product,
and one opening journal per branch. It runs as `bms_app` under the tenant's row-level security, like
the API, never as the owner role.

```
java -jar bms-api.jar import-retail --tenant <slug> --dir <path> [--dry-run]
```

Exit status 0 means every file was imported (anomalies are reported, not fatal); 1 means a file
failed, or the tenant or directory was refused; 2 is a usage error.

**Real data never enters the repository.** The export lives only on the host, readable by the
operator, and is deleted when the import is signed off. Staging gets fabricated data only; the real
export goes to production.

## Before

- The tenant exists with the retail module switched on (`docs/runbooks/onboard-tenant.md`), so its
  retail chart of accounts is seeded. The import refuses a tenant without retail, or a suspended one.
- The plan allows as many branches as the export lists.
- Trading on the platform has not started for the tenant: the legacy balance assumes the only
  movements are the imported ones. The report flags any branch and product that has others.
- Agree a cutover moment with the shop owners. The source's current quantities (`balances.jsonl`) must
  be read at that moment, after the last sale in the source.

## 1. Export from the source

From the pilot spreadsheet, build the directory of JSON Lines files described in the data
dictionary section 4. For each tab:

1. Read **formatted** values, not raw cell values, so a date-only cell keeps the date the sheet
   shows (data dictionary section 3).
2. Write money as integer minor units (UGX has no decimals) and quantities as decimal strings with at
   most three places. A blank quantity is zero; drop the source's before and after quantity columns
   and its derived total columns.
3. Give every sales, restock and usage row a `source_ref` built from the tab, the row number and a
   short hash of its content, for example `sales:1234:9f2c41ab`. Keep the same rule for every
   export so a re-run recognises rows already imported.
4. Mark the restock rows whose supplier text means a stock-take as `"kind": "adjustment"` (signed per
   branch) and customer returns as `"kind": "return"`. Everything else is `"purchase"`.
5. Write `balances.jsonl` from the product master's per-branch quantity columns: one row per branch
   and product, including zeros and negatives.
6. Do not export the cash book tabs (expenses, banking, withdrawals, advances, daily savings) or the
   tabs of internal advances; they are out of the first release (pending ADR-022).

Check the files are UTF-8 and every line is one JSON object:

```bash
cd export && for f in *.jsonl; do python3 -c "import json,sys; [json.loads(l) for l in open(sys.argv[1], encoding='utf-8') if l.strip()]" "$f" && echo "$f ok"; done
```

## 2. Put the export on the host

Copy the directory to the host over SSH, readable by the operator only, outside any git checkout:

```bash
scp -r export/ <host>:/opt/bms/import/<slug>/
ssh <host> 'chmod -R go-rwx /opt/bms/import/<slug>'
```

## 3. Dry run

On the host, from `/opt/bms` (the staging host installs `compose.pi-staging.yml` as `compose.yml`).
`docker compose run` starts a second, short-lived API container with the API's environment, so it
connects as `bms_app` with the same settings; the export is mounted read only. The command starts no
web server and no scheduler.

```bash
cd /opt/bms
docker compose --project-name bms -f compose.yml run --rm --no-deps \
  -v /opt/bms/import/<slug>:/import:ro \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1' \
  api import-retail --tenant <slug> --dir /import --dry-run | tee /opt/bms/import/<slug>-dry-run.txt
```

On the staging host the stack is capped at 900 MB (ADR-018): run the import when nothing else heavy
is running. Locally (`make dev`) the same works with `docker compose run --rm --no-deps -v
"$PWD/fixtures/retail/import-sample:/import:ro" api import-retail --tenant demo --dir /import
--dry-run` after switching retail on for the `demo` tenant.

The dry run does everything a real run does inside one transaction that is rolled back, and prints
the same report. Nothing is written.

## 4. Read the report

```
file                   read  written existing  skipped
products.jsonl           21       20        0        1
sales.jsonl             221      220        0        1
...
opening journals (debit inventory, credit opening balance equity):
  ENT        debit         4568150  credit         4568150  posted JE00000001
...
negative source balances, excluded from the opening journal, for the first stock-take (1):
  JJA TP-007 -3.000
credit sales imported unpaid (the source keeps no payments): 20, total 554300 minor units; ...
anomalies (2):
  products.jsonl:6: duplicate product code tp-001 (same as line 1 ignoring case and spaces); row skipped
  sales.jsonl:221: unknown product code TP-404; row skipped
balances checksum: rows=60 total_qty=2210.500 source_sha256=... derived_sha256=... match=yes
```

- **read, written, existing, skipped.** `existing` is what the tenant already had or an earlier
  run imported. Every skipped row is an anomaly line with its file and line number.
- **Anomalies.** Fix the export for each skipped row that matters (an unknown product code usually
  means the product master is missing a product) and run the dry run again. Rows reported but not
  skipped (a category or supplier not in its file, a credit buyer not in the list) are informational.
- **Opening journals.** One per branch: the positive source quantities at each product's current cost.
  The accountant checks the totals against the shop's own valuation.
- **Negative balances** are the stock that was sold but never recorded as bought (ADR-020
  decision 4). They are imported as they are and must be counted at the first stock-take.
- **Credit sales imported unpaid.** The source keeps no payments, so their receivable is not
  journalled. Agree with the accountant how outstanding credit is opened before staff record
  payments against imported credit sales.
- **Checksum.** `match=yes` means every balance after the import equals the source quantity exactly.
  `match=NO` stops the cutover: investigate before the real run.

## 5. Real run

Repeat step 3 without `--dry-run`, saving the report:

```bash
docker compose --project-name bms -f compose.yml run --rm --no-deps \
  -v /opt/bms/import/<slug>:/import:ro \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1' \
  api import-retail --tenant <slug> --dir /import | tee /opt/bms/import/<slug>-import.txt
```

Each file is one transaction. Then, signed in as the tenant admin, open the valuation and the daily
profit for a day you know from the source, and compare them with the source.

## 6. Re-run safely

The import is idempotent:

- History rows are keyed by `source_ref` in `retail_import_refs`; a reference already imported is
  counted as `existing` and skipped.
- Catalogue, suppliers, credit buyers and branches are matched by code or name.
- The legacy balance is written once per branch and product. On a re-run, a balance that no longer
  equals the source is reported (`legacy balance already written ... correct it with a stock-take`),
  never silently changed.
- The opening journal is posted once per branch (`already posted, not posted again`).

So after a failure (`result: FAILED: <file>: <reason>`, exit status 1), fix the cause and run the
same command again: the files that committed are skipped row by row and the run continues where it
stopped. To add rows the first export missed, add them to the export with new `source_ref` values
and re-run. To change something already imported, do not re-import: correct it in the application
(a stock-take for quantities, a price edit for prices).

## After

- Keep both reports with the cutover record (outside the repository).
- Delete the export from the host: `rm -r /opt/bms/import/<slug>`.
- Plan the first stock-take for every branch with negative balances; its adjustments replace the
  legacy balances (ADR-020 decision 9).
