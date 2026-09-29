# ADR-010: Backend language and framework: Java 25, Spring Boot 4.1, Spring Modulith 2.1, Flyway

## Status

Accepted (2026-09-29). Replaces the earlier plan of Python 3.12 with FastAPI, SQLAlchemy and
Alembic, which was never accepted as an ADR. ADR-002 and ADR-003 stand unchanged; this record
chooses how they are implemented.

## Context

ADR-002 fixed the shape of the backend (a modular monolith with enforced module boundaries,
one transaction per request) and ADR-003 fixed tenant isolation (shared schema, forced
row-level security, `app.tenant_id` bound per transaction). Both were written to hold for
either candidate stack. The product plan had assumed Python with FastAPI. The owner reopened
the choice before any backend code was written, for three reasons.

1. **It is a money-bearing system.** Every financial event must post a balanced journal in
   the same transaction as the event (ADR-004), and maker-checker, idempotency keys and
   append-only tables all depend on transaction boundaries being exact. The team has already
   moved another payment-bearing platform from FastAPI to Spring Boot for the same reasons:
   transactional strength, robustness, security, maturity for systems with many external
   integrations, and Spring Modulith as tested enforcement of a modular monolith. The same
   weighting applies here with more force: this platform holds members' loans, savings and
   investments.
2. **The team already builds this kind of system in Spring Boot.** Its developers build Spring
   Boot multi-tenant platforms on PostgreSQL with row-level security, Flyway migrations and
   Testcontainers-based isolation tests today. The failure modes they have met are design
   inputs here rather than incidents to rediscover: a tenant setting that evaporates when a
   query runs outside a transaction; isolation tests that silently ran as a superuser and so
   never exercised a policy; a write path that picked a tenant arbitrarily when a phone number
   matched members of several tenants; an over-exposed actuator.
3. **Module boundaries need a real enforcement mechanism.** Spring Modulith verifies the
   module graph from the compiled code in an ordinary unit test; the Python equivalent would
   have been a custom import checker.

Options considered: Python 3.12 with FastAPI (the earlier plan); Java 25 with Spring Boot and
Spring Modulith; Kotlin on the same stack. Kotlin was rejected only because nobody on the team
writes it daily. Within Spring Boot, the 3.5 line was rejected because its open-source support
ended on 2026-06-30; the 4.1 line is supported to 2027-07-31.

## Decision

- **Language and runtime:** Java 25 (LTS), Eclipse Temurin in development, CI and images.
- **Framework:** Spring Boot 4.1.x (Spring Framework 7, Jackson 3), Spring MVC on virtual
  threads, Bean Validation, `springdoc-openapi` 3.1 for the OpenAPI 3.1 document (chapter 7
  section 7.3). Starters are Spring Boot 4's modular ones (`spring-boot-starter-webmvc`,
  `-jdbc`, `-actuator`, `-validation`); `spring-boot-flyway` is deliberately absent, so the
  application can never migrate on start.
- **Build:** Maven, one module (`backend/pom.xml`, groupId `com.rincoltech.bms`).
- **Modules:** one deployable application with Spring Modulith 2.1 application modules by
  package, not Maven modules. Each module is a package with a `package-info.java` carrying
  `@ApplicationModule(id = ..., allowedDependencies = ...)`: `kernel` (open, depends on
  nothing), `core.tenancy`, `core.identity`, `core.audit`, `core.ledger`, `core.jobs`,
  `core.operations`, `lending.manifest`, `lending.members`, and further `core.*` and
  `lending.*` modules as they are built. A module's public API is the types in its base
  package; its `internal` subpackage is closed. `ModularityTest` runs
  `ApplicationModules.verify()` and an explicit "core never depends on a vertical" rule.
  Maven multi-module was considered and rejected: in the team's experience a multi-module
  Spring Boot backend was flattened back to one module because the Maven boundaries added
  build ceremony without adding enforcement that Spring Modulith does not already give.
- **Data access:** plain SQL through Spring's `JdbcClient` and `@Transactional`; no JPA. SQL
  keeps row-level security, composite keys, `SELECT ... FOR UPDATE` and the ledger's
  statements visible in review, and removes the object-relational mapping that ADR-002 rule 6
  forbids from crossing module boundaries.
- **JSON:** Jackson 3 with the global snake_case naming strategy. swagger-core, which builds
  the OpenAPI schemas, still uses Jackson 2; it gets its own snake_case mapper, and that is the
  only Jackson 2 code in the application.
- **Tenant binding:** a `JdbcTransactionManager` subclass binds
  `set_config('app.tenant_id', <tenant>, true)` in `prepareTransactionalConnection`, which
  runs at the start of every Spring-managed transaction on the connection that transaction
  uses. The tenant comes only from the request host (or, in the dev and test profiles, the
  `X-Tenant` header), resolved once per request by `app_resolve_tenant`; services never take a
  tenant id as a parameter, and inserts take `tenant_id` from
  `current_setting('app.tenant_id')`. A write path therefore cannot choose a tenant, and the
  database refuses any other value (ADR-003).
- **Migrations:** Flyway SQL migrations in `backend/src/main/resources/db/migration`, run as
  `bms_owner` by the image's `migrate` command in a one-shot container before the application
  switches (ADR-006). The application never migrates on start; it connects as `bms_app` and
  refuses to start as an owner, superuser or `BYPASSRLS` role.
- **Tests:** JUnit 6; unit and architecture tests (`*Test`) in Surefire; integration tests
  (`*IT`) in Failsafe against PostgreSQL 16 in Testcontainers 2, initialised with the same role
  script and the same migrator as the servers, and connected as `bms_app`.
- **Formatting:** Spotless with palantir-java-format, checked in `mvn verify`.

## Consequences

**Better:**

- Transaction boundaries, the tenant binding and the ledger's balance rule sit in one
  framework mechanism that every module shares, and each is proven by an integration test
  against real PostgreSQL.
- Module boundaries are verified from the compiled code on every build, with the allowed
  dependencies kept as data in each module's `package-info.java`.
- The team reuses what it already knows from building Spring Boot multi-tenant platforms,
  including the specific failure modes above.
- Compile-time types catch a class of error that would surface only at request time in
  Python.
- The platform starts on a supported framework line with more than ten months of open-source
  support ahead of it.

**Worse:**

- A heavier runtime than a Python API: the API container needs about 1.5 GB on a 4 GB host,
  and cold start is several seconds (the deploy health gate allows for it).
- Plain SQL is more code than an ORM for simple CRUD, and the SQL is only checked when the
  integration tests run.
- Two Jackson generations are on the classpath until swagger-core moves to Jackson 3.
- The Python ecosystem for data work is not in process. Nothing in the lending MVP needs it;
  analytics, if ever needed, would be a separate component.

**Watch for:**

- Support dates. Spring Boot 3.5 open-source support ended on 2026-06-30. Spring Boot 4.1
  open-source support runs to 2027-07-31, and 4.2 is due on 2026-11-30. The next upgrade
  (4.2 or later) is its own change with its own ADR, run through the full `mvn verify`, and
  should land well before 2027-07-31. Spring Modulith, springdoc-openapi and db-scheduler's
  Spring Boot 4 starter move with it.
- Code that queries outside a transaction. It fails loudly (the tenant is only bound inside a
  transaction), which is the intent, but the fix is always `@Transactional`, never binding the
  tenant by hand.
- Tests that connect as a superuser. They pass with every policy dropped. Integration tests
  connect as `bms_app`; fixtures that need to bypass row-level security use `bms_owner`
  explicitly.
- A module growing a `public` class in its base package that nobody else needs. The base
  package is the module's API.

## Related ADRs

- ADR-002 is the module structure this implements; Spring Modulith is its enforcement.
- ADR-003 is the isolation model; the transaction manager and the startup role guard are its
  implementation.
- ADR-004 is the ledger whose balance rule is checked in Java and again by a deferred trigger.
- ADR-006 runs the `migrate` command before switching containers.
- ADR-008 runs background jobs in this same application on PostgreSQL.
