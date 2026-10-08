# Runbook: fill a staging lending tenant with fabricated data (`seed-lending`)

**Requirements:** increment 5 demo (issue #108), increment 10 investments (issue #152) ·
**Decision:** ADR-026 decision 4, ADR-031 · **Design:** SDD chapter 5 section 5.4 (`lending.seed`),
chapter 7 sections 7.11.13 and 7.11.16

The command gives an empty lending tenant on **staging** a small, fully fabricated loan book, so the
pilot tenant's staff can try the loan screens with fake data before their own register is imported
(increment 7):

- 15 members (`Test Borrower 01` to `15`, phones `+256700000001` to `15`, KYC verified);
- 4 loan products (`FAB-BULLET`, `FAB-MONTHLY` with a 2% deducted fee, `FAB-DECLINE` with an upfront
  fee, `FAB-WEEKLY` with an added fee and the early settlement rebate), all rates invented;
- 12 applications in every state before the money: draft, submitted, appraised, approved, rejected
  and cancelled;
- 6 loans disbursed 10 to 100 days back with 11 repayments between them: one paid off with a
  credit, some partly paid, some overdue, one untouched;
- 3 savings products (`FAB-SAVE` on daily balance, posted monthly, with a minimum balance and a
  withdrawal fee; `FAB-TARGET` on the lowest monthly balance, posted quarterly, with a minimum
  opening balance and one withdrawal a month; `FAB-PLAIN` with no interest) and 11 savings accounts
  (one member holds two) with twelve months of monthly deposits and quarterly withdrawals. The
  nightly end of day runs day by day as the movements go in, so the accounts carry their end-of-day
  balances, monthly and quarterly interest, and one account has gone dormant (#151, ADR-032).
- 3 investment products (`FAB-FD` fixed deposit paid at maturity with a reduced rate on early
  withdrawal, `FAB-FD-MONTHLY` paying its return monthly, `FAB-RECURRING` compounding and renewing
  itself), all rates invented;
- 10 investments placed over the last twelve months and replayed day by day through the nightly
  job's own work: one paid out at maturity, one recurring deposit renewed four times, a monthly
  income deposit whose ten returns were collected, one matured and waiting for the member, one
  maturing within the week, one that rolls over today, and one opened today and not yet funded.

Every disbursement, repayment, deposit and withdrawal goes through the same code as staff
(`LoanServicing`, `SavingsServicing`), and every investment funding, accrual, maturity, rollover and
payout through `InvestmentServicing`, so each posts its journal, receipt or voucher number and
audit row; the trial balance balances, loans receivable equals the loans' principal outstanding,
member savings equals the savings accounts' balances and both investment liabilities equal the
investments' balances. The seed queues no SMS.

```
java -jar bms-api.jar seed-lending --tenant <slug>
```

### The insights demo year (`--insights-demo`, #153)

```
java -jar bms-api.jar seed-lending --tenant <slug> --insights-demo [--scale <1-50>]
```

After the base seed, in the same transaction, the tenant also gets twelve months of fabricated
history so the Insights page shows believable trends: two more fabricated branches (`FABN`, `FABE`),
nine fabricated staff users that cannot sign in (deactivated, no credentials, phones in the
`+2567000000NN` range), about 90 members joining through the year (`Demo Borrower 0001` and on), and
about 230 applications growing month by month, a few rejected or cancelled, each disbursed loan
serviced through `LoanServicing`: most borrowers pay on time, some pay late or in part, some stop and
are written off about four months later (some then partly recovered), and a few settle early with an
overpayment credit. Then the year's daily snapshots are written, a month per transaction. `--scale N` multiplies the members
and applications by N (20 for the performance measurement of `docs/specs/lending-insights-metrics.md`;
expect several minutes). The history is deterministic for a given scale and date. Every name, number
and amount is invented.

Exit status 0 means the tenant was seeded; 1 means it was refused and **nothing was written**; 2 is
a usage error.

## Safety rules (enforced by the command)

- **Never production.** The command exits 1 when `BMS_ENVIRONMENT` is `production`. It is a named
  command of the API image and never runs at startup; the image's default command is the web
  application.
- **Empty tenants only.** It refuses a tenant that holds any member, loan product, savings product,
  investment product, loan or journal entry, so it can never mix fabricated rows with real ones.
- **Once.** It records the audit action `lending.seed.fabricated` (the marker) and refuses any
  tenant that carries it.
- **One transaction.** A failure part way leaves the tenant exactly as it was. The insights demo
  year (`--insights-demo`) is the exception: it runs after the base seed has committed and commits
  month by month, because one transaction for the whole year slows down as it goes (each numbering
  sequence row is updated tens of thousands of times and the old row versions cannot be cleaned up
  until the transaction ends). A failure part way leaves a partial year; seed a fresh tenant.
- It runs as `bms_app` under the tenant's row-level security, like the API, and needs the lending
  module switched on for the tenant (`docs/runbooks/onboard-tenant.md`).

## Run on staging

On the staging host, from `/opt/bms` (the host installs `compose.pi-staging.yml` as `compose.yml`):

```bash
cd /opt/bms
docker compose --project-name bms -f compose.yml run --rm --no-deps \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1' \
  api seed-lending --tenant <slug>
```

The stack is capped at 900 MB (ADR-018): run it when nothing else heavy is running. The command
prints one line, for example
`seed-lending: tenant <slug>: 15 members, 4 products, 12 applications, 6 disbursed loans, 11 repayments, 11 savings accounts, <n> savings movements, 3 investment products, 10 investments (all fabricated)`.

## Run locally

`make seed-lending` seeds the fabricated `demo` tenant of `make dev` (override with
`SEED_SLUG=<slug>`); add `SEED_ARGS=--insights-demo` for the insights year. A second run is refused by the marker; `make clean` and `make dev` start over.

## Removing the fabricated data

There is no delete: journals, transactions and allocations are append-only (ADR-004). A tenant that
was seeded keeps its fabricated book. When the pilot moves to real data, it does so on production
(or on a new staging tenant), never on top of a seeded tenant.
