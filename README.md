# bms-platform

**BMS Platform** is a multi-tenant SaaS business management system for small and medium
enterprises in Uganda and East Africa. It is a platform core (tenancy, branches, users
and roles, audit, maker-checker approvals, a double-entry general ledger, SMS and email,
PDF documents, reporting, spreadsheet import, mobile money intake) plus vertical modules a
tenant switches on. The first vertical is **lending** for microfinance institutions and
money lenders; retail follows later.

Staff use a web app; members use an installable PWA, SMS, and later USSD; payments come
in through mobile money.

## Quickstart

Developers and coding agents start with **[AGENTS.md](AGENTS.md)**.

Local environment: `make dev` starts PostgreSQL 16, Redis 7, the API, the worker and the
web app with fabricated seed data. The Makefile arrives with the application scaffold,
whose backend framework is pending ADR-010; until then the specification in `docs/` is the
deliverable.

View the architecture model locally:

```bash
docker run --rm -it -p 8080:8080 -v "$(pwd)/docs:/usr/local/structurizr" structurizr/lite
```

Validate it before pushing (the version is pinned on purpose; the `latest` tag is a no-op
stub that passes anything):

```bash
docker run --rm -v "$PWD/docs:/work" -w /work structurizr/cli:2024.09.19 \
  validate -workspace workspace.dsl
```

Build the browsable site (all views, the SDD chapters and the ADRs):

```bash
mkdir -p docs/build/site && chmod -R 777 docs/build
docker run --rm -v "$PWD/docs:/var/model" -w /var/model \
  ghcr.io/avisi-cloud/structurizr-site-generatr:latest generate-site -w workspace.dsl
open docs/build/site/index.html
```

CI does the same on every change to `docs/workspace.dsl`, `docs/sdd/**` or `docs/adr/**`
and uploads the site as a build artifact.

## Documentation map

| You want | Go to |
|---|---|
| How to work in this repository | [AGENTS.md](AGENTS.md), [PROCESS.md](PROCESS.md) |
| What is built first, and the open questions for the pilot tenant | [docs/specs/lending-mvp-scope.md](docs/specs/lending-mvp-scope.md) |
| Why a decision was made | [docs/adr/](docs/adr/) |
| The full design | [docs/sdd/](docs/sdd/README.md) |
| Requirements with acceptance criteria | [docs/sdd/03-functional-requirements.md](docs/sdd/03-functional-requirements.md) |
| Tables, RLS, ledger, posting rules | [docs/sdd/06-database-design.md](docs/sdd/06-database-design.md) |
| Endpoints and API conventions | [docs/sdd/07-api-design.md](docs/sdd/07-api-design.md) |
| Roles, permissions, maker-checker | [docs/sdd/08-security-design.md](docs/sdd/08-security-design.md) |
| Infrastructure and CI/CD | `docs/sdd/09-infrastructure-design.md`, `docs/sdd/10-cicd-pipeline.md`, `docs/runbooks/` |
| Importing the pilot spreadsheet | [docs/sdd/13-data-migration-and-import.md](docs/sdd/13-data-migration-and-import.md), [docs/specs/pilot-data-dictionary.md](docs/specs/pilot-data-dictionary.md) |
| Report definitions and formulas | [docs/sdd/14-reporting.md](docs/sdd/14-reporting.md) |
| Test strategy | [docs/sdd/15-test-strategy.md](docs/sdd/15-test-strategy.md) |
| C4 model | [docs/workspace.dsl](docs/workspace.dsl) |

## Delivery flow

Short-lived branch, pull request with CI, merge to `main` deploys to **staging**
automatically; a version tag `vX.Y.Z` on a commit already green on staging promotes the
same images to **production** after the dev lead's approval. Details in
[PROCESS.md](PROCESS.md) section 7.

## House style and content boundary

No em dashes or en dashes anywhere (CI enforces). The repository is technical only: no
client or borrower names, no real figures, no commercial terms. See [AGENTS.md](AGENTS.md).

## Related repositories

| Repository | Relationship |
|---|---|
| `ERP_BMS` | A separate offline, single-device app for very small shops. Not a dependency and not a predecessor (ADR-007). |
