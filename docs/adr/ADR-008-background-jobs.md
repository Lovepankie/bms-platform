# ADR-008: Background jobs and scheduling on PostgreSQL with db-scheduler; no Redis

## Status

Accepted (2026-09-29). Replaces the earlier assumption of Redis 7 with RQ or arq, which was
tied to the Python plan that ADR-010 did not adopt. ADR-002 names "one worker" beside the API;
under this record the worker is a role inside the application (below), not a separate container.

## Context

The platform needs background work in four shapes: side effects after commit (SMS, email, PDF
rendering, report builds) drained from the transactional outbox (chapter 5 section 5.4.4);
nightly jobs per tenant (arrears and penalties, savings interest, investment returns,
reconciliation); short-period polls (pending payment intents every two minutes, chapter 12);
and housekeeping (purging expired idempotency keys). Every job must run exactly once per
schedule even if more than one instance is running, and every job that touches tenant data
must bind the tenant exactly as a request does.

The earlier plan put a queue in Redis. That adds a stateful service to back up, monitor, size
and secure on a 4 GB host, and gives two sources of truth for "what still has to happen": the
outbox table and the Redis queue. The outbox already lives in PostgreSQL, in the same
transaction as the event.

Redis also carried three other duties in the draft chapters: rate limits, a 60 second
permission cache, and a set of revoked session ids. None needs a shared store while the API
runs as one instance per environment.

Options considered: Redis with a queue library; JobRunr (PostgreSQL-backed, with a dashboard;
some features are in its paid edition); db-scheduler (PostgreSQL-backed, one table, cluster
safe through row locking, recurring and one-time tasks, a Spring Boot starter, no paid tier);
Spring's `@Scheduled` alone (no persistence, runs on every instance).

## Decision

- **db-scheduler** is the job runner. Its state is one table, `scheduled_tasks`, created by a
  Flyway migration (V1). A task is a Spring bean in the module that owns it; the starter
  registers every task bean. Row locking in `scheduled_tasks` guarantees one execution per
  schedule across instances.
- **The worker is a role, not a container.** Polling is on in the API process by default
  (`BMS_SCHEDULER_ENABLED=true`). If job load ever competes with requests, a second container
  of the same image with the web port unpublished takes the role and the API instance sets
  `BMS_SCHEDULER_ENABLED=false`. No code changes either way. "The worker" in the SDD chapters
  means this role.
- **Per-tenant work** goes through `core.jobs.TenantJobs.forEachActiveTenant`: it lists tenants
  with `app_list_active_tenants()`, then for each one binds the tenant and opens one
  transaction, so row-level security applies exactly as for a request. One tenant's failure is
  logged and the others still run; the run then fails so db-scheduler records and retries it.
  Jobs are idempotent for their business date (chapter 5 section 5.4.4).
- **The outbox stays in PostgreSQL** (`outbox_events`); an outbox dispatcher task drains it
  after commit, retrying with backoff, when the notifications module lands.
- **No Redis anywhere.** The duties the drafts gave it move as follows:
  - rate limits (chapter 7 section 7.10): in-process token buckets per API instance, keyed by
    tenant and principal; a PostgreSQL-backed limiter replaces them if a second API instance
    is ever added;
  - permission cache (chapter 7 section 7.4.1): an in-process cache with a 60 second expiry,
    keyed by tenant and user, cleared on role changes;
  - revoked sessions (FR-IAM-08): read from `auth_sessions` on each request (an indexed lookup
    by primary key), so revocation takes effect at once;
  - USSD session state (chapter 11): a small table with an expiry, purged by a job.
- The first task, `core.idempotency-key-purge`, runs nightly at 02:30 East Africa Time and
  deletes expired idempotency keys per tenant.

## Consequences

**Better:**

- One stateful service (PostgreSQL) to run, back up and restore; a restore brings back the
  pending jobs with the data they belong to.
- A job and the business rows it touches can commit in one transaction.
- The 4 GB host keeps the memory Redis would have used.
- Jobs bind tenants through the same transaction manager and policies as requests.

**Worse:**

- Polling adds a small, constant query load (every 10 seconds).
- In-process rate limits and caches are per instance: correct for one API instance, and they
  must move to PostgreSQL before a second instance is added.
- db-scheduler has no built-in dashboard; job health comes from `scheduled_tasks` and the logs.

**Watch for:**

- A job that forgets `TenantJobs` and queries tenant tables directly: it fails on its first
  query (no tenant bound), which is safe and loud.
- A long job inside one transaction. Batch per tenant, at most 500 accounts per transaction
  (chapter 5 section 5.4.4).
- Adding a second API instance without first moving rate limits and caches off the process.

## Related ADRs

- ADR-002: the worker named there is the role described here.
- ADR-003: jobs bind tenants the same way requests do.
- ADR-010: db-scheduler runs inside the Spring Boot application.
