# Runbook: import a retail tenant's history (`import-retail`)

**Requirements:** FR-RET-12 · **Decisions:** ADR-003, ADR-004, ADR-020 (decisions 4, 8 and 9) ·
**Design:** SDD chapter 13 sections 13.13 and 13.13.1, chapter 6 sections 6.11.4 and 6.11.5 ·
**Format:** `docs/specs/retail-pilot-data-dictionary.md` sections 4 and 5

The command imports a retail tenant's history once, at cutover: catalogue, suppliers, credit buyers,
historical sales, restocks, adjustments, returns and usage, a legacy balance per branch and product,
and one opening journal per branch; and, when the export carries them, the cash book tabs (expense
lists, parties, daily savings, expenses, banking, withdrawals, advances and their payments, and the
cash balances; ADR-022 decision 18). It runs as `bms_app` under the tenant's row-level security, like
the API, never as the owner role.

```
java -jar bms-api.jar import-retail --tenant <slug> --dir <path> [--dry-run] [--first-live-date yyyy-mm-dd]
```

`--first-live-date` is the first day the shop records in the application; the cash book's opening
journals are dated the day **before** it, so the first live day opens with the carried balance and
shows no unexplained movement. It defaults to the day of the import. Agree it with the shop owners
with the cutover moment, and pass the same date on a dry run and on the real run.

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
4. Product codes may be copied as they are: the importer normalises them exactly as the catalogue
   does (a no-break space at either end or a full-width letter does not make a second code), and
   reports a code with a special space or a control character inside.
5. Mark the restock rows whose supplier text means a stock-take as `"kind": "adjustment"` (signed per
   branch) and customer returns as `"kind": "return"`. Everything else is `"purchase"`.
   Shop-to-shop moves in the source are imported the same way, as signed adjustments at each branch:
   the importer has no transfer kind, and the platform's stock transfers (FR-RET-16, #84) are for new
   data only.
6. Write `balances.jsonl` from the product master's per-branch quantity columns: one row per branch
   and product, including zeros and negatives.
7. The cash book tabs are optional and are exported only when the owner wants the history in the
   application (data dictionary section 5): `cash_parties`, `expense_categories` (the pilot's
   expense categories tab, never typed by hand), `savings`, `expenses`, `banking`, `withdrawals`,
   `advances`, `advance_payments` and `cash_balances`. The pilot's "loan" tabs are advances to the owner or
   company, not customer lending. Write `cash_balances.jsonl` at the same cutover moment as
   `balances.jsonl`: the cash on hand, the bank balance and the savings reserve of each shop. A
   party the pilot marks as the company is not mapped (the importer skips and lists it, and any
   advance to it). An export without these files imports exactly as before.

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

### The cash book files

All nine files are optional; an absent one is not listed in the report. Whatever order the
data dictionary lists them in, the command **writes them in the order that keeps the figures
consistent**: parties, expense categories, savings, expenses, withdrawals, advances, advance
payments, **banking**, then cash balances. Banking comes after savings, expenses, advances and
payments because each banked row stores the expected amount to bank of its shop and day, computed
from the imported history with the same formula as the cash book's reports (cash takings less
savings, expenses and advances paid out, plus cash repayments); the report still lists the files in
the data dictionary's order. Read these lines too:

- **Expense categories, items and parties** missing from their lists are created exactly as written
  and listed (`expenses.jsonl:3: category ... is not in expense_categories.jsonl; created as
  written`); a beneficiary not in `cash_parties.jsonl` is created as kind `other`. Give the owner
  the list to review.
- **Skipped rows**: a second savings row for one shop and day (never merged, and also when the day
  already has a live record), a payment on an unknown advance or above the remaining principal, a
  company party, an advance to a party that is not an owner, staff member or related entity, a
  future or malformed date, an unknown shop. Each is listed with its file and line.
- **Advance numbers.** Every imported advance takes the next live advance number in business date
  then source id order; the pilot's id is kept only in the import reference and the advance's note.
  A processing fee is not modelled: it is listed and appended to the note.
- **Cash opening journals.** One per shop with a row in `cash_balances.jsonl` (a row with no shop
  is the head office), dated the day before the first live day: debit cash on hand, bank and savings
  reserve, one owner advances line for each advance still outstanding after the imported payments,
  credit opening balance equity. A shop with no row gets none.

## 5. Real run

**Take a backup first, and verify it.** The imported tables are append-only and the run commits per
file, so nothing a real run writes can be undone by editing rows. Immediately before the real run,
on the host, dump the database as `bms_owner` (it has BYPASSRLS, so every tenant is in the dump) and
check the dump reads back:

```bash
cd /opt/bms
C=(docker compose --project-name bms -f compose.yml)
"${C[@]}" exec -T postgres pg_dump --username bms_owner --dbname bms --format custom --compress 6 \
  > backups/pre-import-<slug>.dump
chmod 600 backups/pre-import-<slug>.dump
"${C[@]}" exec -T postgres pg_restore --list < backups/pre-import-<slug>.dump | grep -c 'TABLE DATA'
sha256sum backups/pre-import-<slug>.dump | tee backups/pre-import-<slug>.sha256
```

The `--list` must exit 0 and count the table data entries (well over a hundred); a truncated or empty
dump fails here. Note the time of the dump in the cutover record. Do not start the real run without
a verified dump.

Then repeat step 3 without `--dry-run`, saving the report:

```bash
docker compose --project-name bms -f compose.yml run --rm --no-deps \
  -v /opt/bms/import/<slug>:/import:ro \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1' \
  api import-retail --tenant <slug> --dir /import | tee /opt/bms/import/<slug>-import.txt
```

Each file is one transaction. Then, signed in as the tenant admin, open the valuation and the daily
profit for a day you know from the source, and compare them with the source.

**Dates.** Every imported movement carries a business date (SDD chapter 6 section 6.11.4): a sale,
restock, adjustment, return or usage row the date of its source row in the tenant's zone, and the
legacy balances the import date, which is also the date of the opening journals. So:

- The valuation without `as_of`, or `as_of` the import date or later, equals the source's current
  quantities at current cost; the revaluation difference is zero except where a branch has
  negative balances (they are valued but not in the opening journal).
- The valuation `as_of` a date before the import counts the imported history only, by business
  date, and the inventory account is still zero then. It is not the shop's stock on that day: the
  source never recorded its opening stock, which the legacy balance supplies on the import date. A
  branch can even show a negative value there. Use it to check the history, not as a past
  valuation.
- The daily profit of any day counts the imported sales by sale date and the usage by its date,
  exactly like live ones.

## 6. Re-run safely

The import is idempotent for the data it imports:

- History rows are keyed by `source_ref` in `retail_import_refs`; a reference already imported is
  counted as `existing` and skipped.
- Catalogue, suppliers, credit buyers and branches are matched by code or name.
- **An existing product's prices are never changed by a re-run** (issue #73). A product that
  already exists keeps its cost and sell price and gets no price history row, so a price edited in
  the app between two runs stays as it is. When the file's prices differ from the catalogue's, the
  report says so (`product <code> existed with other prices; its prices were left unchanged`); the
  line is information only. Only a new product takes the file's prices.
- A balance the imported history already gives (a zero legacy difference) is written as nothing
  and counted as `existing`, on the first run and on every re-run.
- The legacy balance is written once per branch and product. On a re-run, a balance that no longer
  equals the source is reported (`legacy balance already written ... correct it with a stock-take`),
  never silently changed.
- The opening journal is posted once per branch (`already posted, not posted again`), and so is
  the cash opening journal of each shop (`retail.cash_opening:<branch>`).
- Cash book rows are keyed by `source_ref` per file (`savings`, `expenses`, `banking`,
  `withdrawals`, `advances`, `advance_payments`); a re-run takes no advance number and adds no row.
  A banked row keeps the expected amount it was computed with; a savings, expense or advance added
  to an earlier day afterwards does not change it.

A re-run of the same export prints the same report with every count under `existing` and writes no
data row; a dry run writes no row either (both checked end to end on a throwaway database for issue #71,
with the fabricated `fixtures/retail/import-sample/`). A committed re-run still writes **one audit
row per file** (`retail.import.file_imported`, ten per run plus one per cash book file present, with that file's counts), so the audit log
records every run; a dry run's audit rows are rolled back with the rest.

So after a failure (`result: FAILED: <file>: <reason>`, exit status 1), fix the cause and run the
same command again: the files that committed are skipped row by row and the run continues where it
stopped. To add rows the first export missed, add them to the export with new `source_ref` values
and re-run. To change something already imported, do not re-import: correct it in the application
(a stock-take for quantities, a price edit for prices).

## If the real run was wrong

Know these before you decide anything:

- **The run commits per file.** A file that fails rolls back alone; the files before it stay
  committed. A run that finished committed everything it reported as `written`.
- **The tables it writes are append-only** (`retail_stock_movements`, `retail_sale_lines`,
  `retail_price_history`, `retail_import_refs`, `journal_entries` and `journal_lines`, among others;
  SDD chapter 6 section 6.11). `bms_app` cannot update or delete them, and they must **never** be
  edited ad hoc as `bms_owner` either: a hand-deleted movement or journal line breaks the balances,
  the ledger and the audit trail in ways no report shows.

Then:

1. **Stop.** Do not re-run the import to "fix" it: a re-run adds what is missing and changes
   nothing already imported (section 6). Tell the dev lead; the decision to restore is theirs.
2. **Compare against the backup in a scratch database.** Restore the pre-import dump next to the
   live database, without touching it, and compare the tenant's rows as `bms_owner`:

   ```bash
   cd /opt/bms
   C=(docker compose --project-name bms -f compose.yml)
   sha256sum -c backups/pre-import-<slug>.sha256
   "${C[@]}" exec -T postgres psql -U postgres -d postgres -c "CREATE DATABASE bms_preimport OWNER bms_owner"
   "${C[@]}" exec -T postgres pg_restore -U bms_owner -d bms_preimport --exit-on-error \
     < backups/pre-import-<slug>.dump
   ```

   Count the tenant's rows of the tables in the report (`retail_products`, `retail_stock_movements`,
   `journal_entries`, ...) in `bms` and in `bms_preimport`, and check whether any **other** tenant,
   or this tenant's staff, wrote anything after the dump (`audit_log` rows newer than the dump time,
   outside `retail.import.*`).
3. **Reverse with the documented owner procedure.** If nothing but the import wrote after the dump,
   the reversal is the "existing host" procedure of `docs/runbooks/restore-from-backup.md` section 3,
   run by the operator as `bms_owner` on the dev lead's decision, with `bms_preimport` as the restored
   database (skip its steps 1 and 2): stop the API, swap the names, keep the damaged copy, then
   section 4 to verify and start. Fix the export, dry-run again (step 3) and repeat the real run with
   a fresh backup.
4. **If anything else wrote after the dump** (another tenant, or this tenant started trading), a
   whole-database restore would lose those writes: do not restore. Correct the data in the
   application instead (a stock-take for quantities, a price edit for prices) and record what was
   wrong and what was corrected in the cutover record. An opening journal is corrected only by a
   reversing journal through the ledger; agree it with the accountant and the dev lead.
5. Drop the scratch database once the decision is recorded:
   `"${C[@]}" exec -T postgres psql -U postgres -d postgres -c "DROP DATABASE bms_preimport"`.

## After

- Keep both reports with the cutover record (outside the repository).
- Delete the export from the host: `rm -r /opt/bms/import/<slug>`.
- Once the import is signed off, delete the pre-import dump too (it holds real data):
  `rm /opt/bms/backups/pre-import-<slug>.*`. The nightly encrypted backup covers the database from
  then on.
- Cash book: open the daily cash summary of a shop for the first live day. Its opening must equal the
  `cash_balances` figure for that shop and `other_movements_minor` must be zero for a live day with
  no manual journal. The imported days are listed apart in the banking report and are not in the
  unbanked running total, which starts at the first live day with nothing carried.
- Plan the first stock-take for every branch with negative balances; its adjustments replace the
  legacy balances (ADR-020 decision 9).
- From the cutover on, move stock between shops with **Move stock** (stock transfers, FR-RET-16), not
  with a pair of adjustments. Imported history is not rewritten: the shop-to-shop moves already
  imported stay adjustments in both branches' history, and no transfer is created for them (ADR-020
  amendment of 2026-10-06).
