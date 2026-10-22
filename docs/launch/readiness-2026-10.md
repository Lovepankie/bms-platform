# Launch readiness: first production launch (October 2026)

**Issue:** #416 · **As of:** 2026-10-22 · **Scope:** `main` on that date, plus the open pull requests
named in each item · **Kind:** a checklist of what must be true before the first paying tenant

This file is the plan, not the analysis. Each line is a control that must exist, with who owns it and
where it stands today. The reasoning behind each line, and the detail that should not sit in a
repository, is kept by the owner outside git.

**Owners.** **Hillary** owns anything that needs a person, a decision, money, an account or a
signature, legal work included. **The repo** owns anything that is finished by a merged change.

**States.** **DONE** means the control exists today. **IN FLIGHT** names the pull request that
delivers it. **OPEN** means nobody is building it yet.

## Verdict

BMS is **not ready for the first paying tenant yet, but the remaining gap is operations, commercial
process and legal, not the product.** The core is sound: every tenant table carries forced row-level
security with a catalogue-driven isolation test, sessions and tokens are well designed, payments are
re-queried from the provider rather than trusted from a callback, and the SMS and payment settings
refuse sandbox values in production.

Since the first pass of this audit hardening pull requests have been opened or merged and the email track
has been finished outside the repository, which closes or moves most of the operational lines. What is
left and cannot be closed by a merge: there is still no production host and no rehearsed release,
release building and signing are not yet on the footing M2 requires, there is no privacy notice, terms, processing agreement or data protection
registration on record, no decision on hosting personal data outside Uganda, and no written process
that collects subscription money or ends a free month.

Realistic order of work: one to two weeks of engineering on the open repo lines, the host and the
rehearsal in parallel, and the legal track started now because it is the longest. Launch with **one
pilot tenant, two-step sign-in switched on for its administrators, and online collections, the AI
assistant, savings and investments switched off** until their own gates close.

## Launch checklist (the ten lines)

1. [ ] A production host exists in a region that has been decided and recorded, reached by a rehearsed
   tag release that has been rolled back once on purpose.
2. [ ] Release builds run on company-controlled runners, and a second trusted person can sign a release.
3. [ ] Rincol Tech is registered with the data protection office, and a lending tenant shows its own
   registration at onboarding.
4. [ ] A privacy notice, terms of service and a tenant processing agreement are published, linked from
   the app, and accepted with their version recorded at sign-up.
5. [ ] Someone is told, within minutes, when the site, a nightly job, the disk, a certificate or a
   backup is wrong (IN FLIGHT, #427).
6. [ ] A timed restore onto a fresh machine has been run and recorded, the drill runs on a timer, and
   backups cannot be deleted by the host that writes them (IN FLIGHT, #427; part is Hillary's).
7. [ ] Container logs are capped and rotated, and personal data is removed from log lines
   (IN FLIGHT, #433).
8. [ ] Sign-in is limited per address, per account and per IP, and password hashing is bounded
   (IN FLIGHT, #433).
9. [ ] Email leaves a verified sending domain with SPF, DKIM and DMARC in place, and production
   refuses to start without its mail settings (DONE outside the repo; the startup guard IN FLIGHT, #433).
10. [ ] Subscription money, support and breach response are written down and owned by a named person
    (runbooks IN FLIGHT, #427; the commercial process is Hillary's and OPEN).

---

## MUST before the first paying tenant

### M1. A production environment exists and a release to it has been rehearsed

**Required:** a provisioned production host, one chosen release model, and a rehearsal tag that has
been deployed, health-gated and deliberately rolled back.

**State:** OPEN. The production host is not provisioned. Two release models are still described at
once (the SSH push of ADR-006 and the signed-pointer pull proposed in #301, which is unapplied and
behind `main`). The proposal also needs its ADR renumbered past 057 and three gaps closed before it
could run.

**Owner:** Hillary (the model, the account, the payment), then the repo.

### M2. Release builds run on company-controlled runners, and more than one person can sign a release

**Required:** tag builds pinned to runners Rincol Tech controls, the signing key escrowed with or
duplicated for a second trusted person, and a written procedure for replacing a lost or compromised
signer.

**State:** OPEN.

**Owner:** Hillary (the second key holder and the machines), then the repo.

### M3. The hosting region is decided and the sub-processors are listed

**Required:** an ADR naming the hosting region, the backup and document store's jurisdiction and
whether provider snapshots are on, plus a published sub-processor list giving each provider's country.

**State:** OPEN. The non-functional requirement that asks for this ADR before the pilot goes live is
still an open item, the provisioning runbook leaves the region blank, and no sub-processor list exists.

**Owner:** Hillary (the legal basis and the list), then the repo (the ADR).

### M4. Rincol Tech is registered with the data protection office, and tenants show theirs

**Required:** the company registered as a processor, and as a controller of applicant and staff data;
the certificate number and expiry kept in company records outside git; and a hard step in the
onboarding runbook where a lending tenant shows its own registration.

**State:** OPEN. Nothing in the repository records a registration, and onboarding only reminds the
tenant of its own duty.

**Owner:** Hillary.

### M5. A privacy notice, terms of service and a processing agreement exist and are accepted

**Required:** a privacy notice covering Rincol Tech both as controller and as processor, terms of
service, and a processing agreement template; all three served on public routes and linked from the
footer and the sign-up page; and a required acceptance at sign-up that stores the version, a digest of
the text, the time and the address it came from.

**State:** OPEN. None of the three documents exists, there are no legal routes in the app, and sign-up
records no acceptance. The versioned acknowledgement already used by the advisor settings is the
pattern to reuse.

**Owner:** Hillary (the text and the signatures), then the repo (the pages and the storage).

### M6. Someone is told when the site, a job, the disk, a certificate or a backup is wrong

**Required:** a check of the site and one tenant sign-in page, of failed, stuck and overdue nightly
jobs, of disk use, of certificate expiry and of backup freshness, each raising an alert to a named
person on the existing operator channel.

**State:** IN FLIGHT (#427). The pull request adds a host service on a five minute timer that covers
all six checks, sends each problem once when it starts, repeats it while it lasts, says when it clears,
retries a message it could not send, and pings an external heartbeat on every run. It has its own test
suite and a runbook.

Not in it, and still Hillary's: choosing and setting up the external uptime monitor and heartbeat,
installing the timers on the hosts, and filling in who is paged out of hours. Alerts on the worker
queue, on failed messages and on the SMS balance are separate SHOULD lines below.

**Owner:** the repo for the checks, Hillary for the monitor account and the rota.

### M7. A restore has been timed and recorded, and backups cannot be deleted by the host

**Required:** a full restore onto a fresh machine, timed against the agreed recovery objective and
recorded; the drill repeating on a timer rather than by hand; and retention enforced by the bucket so
a compromised or mistaken host cannot delete the history along with the live database.

**State:** IN FLIGHT (#427). The pull request puts the drill on a weekly timer, times it, keeps a
history and uploads each result off the host; the monitor alerts when a drill fails, is stale or runs
over the objective; a backup failure is recorded for the monitor to find; and retention can be handed
to the bucket. The runbook gives the steps and a table to record them.

Not in it, and still Hillary's: the bucket retention rules and the first fresh-machine drill.

**Owner:** the repo for the scripts and timers, Hillary for the bucket rules and the first drill.

### M8. Container logs are capped and personal data is kept out of them

**Required:** a size and rotation cap on every container's logs so the disk cannot fill, and a
redaction step so personal data never reaches a log line, with a test.

**State:** IN FLIGHT (#433). The pull request caps and rotates logs in both compose files and adds a
redaction customizer on the staging and production profile that passes every string of every structured
line, including exception text and stack traces, with unit tests over the real encoder.

**Owner:** the repo.

### M9. Sign-in is limited per address, per account and per IP, and hashing is bounded

**Required:** limits on every public sign-in, second-factor, invitation and reset route; a limit per
sign-in address and per account as well as per client address; answers that do not reveal whether an
account exists; and a bound on how many password hashes run at once, with a clear refusal beyond it.

**State:** IN FLIGHT (#433). The pull request replaces the reset-only throttle with one that covers
the whole public sign-in family on all three axes, makes an unknown address answer and lock exactly as
a real one does without paying for a hash, and bounds concurrent hashing with a busy answer beyond it.
Covered by unit and integration tests.

**Owner:** the repo.

### M10. Email leaves a verified sending domain with SPF, DKIM and DMARC in place

**Required:** a chosen provider, a verified sending domain, SPF, DKIM and DMARC records published, a
sender that reads as the product, and a support address that reaches a monitored inbox.

**State:** DONE, outside the repository. The provider is Resend, the sending domain is verified, SPF,
DKIM and DMARC records are published in the DNS zone, the sender is "BMS by Rincol Tech
<no-reply@rincoltech.com>" and support@rincoltech.com forwards to a monitored inbox. The repository
already assumes exactly this shape: the mail settings take a display-name sender and the branded
email layout and footer are built.

Remaining and separate: an ADR recording the choice, and the HTTP adapter of #333, which is an
improvement rather than a launch gate because the provider is reachable over SMTP today.

**Owner:** Hillary (done), repo (the ADR).

### M11. Production refuses to start without its mail settings

**Required:** a production instance that will not come up, naming what is unset, when the mail
settings are missing, instead of quietly leaving messages pending as it does on a laptop.

**State:** IN FLIGHT (#433). The pull request adds the guard for the production environment only,
naming the unset variables and never a value, and leaves the quiet behaviour for staging and local
runs. Tested.

**Owner:** the repo.

### M12. A price book, a trial clock and a record of payments received exist

**Required:** prices held per plan, a free month that starts on its own, a payment recorded against a
business with the period it covers, and an operator screen for all three.

**State:** DONE (#413, ADR-056). Plans carry limits, a description and an order; prices live in an
append-only table with a monthly price and a yearly term of fewer than twelve months; a payment is
recorded by the operator and confirmed at once, idempotently, with a reason kept in the audit log and
guards against an amount or a price change far from the expected one. The free month starts at the
first staff sign-in, or at a payment that comes first, so paying early never burns it. Pilot businesses
carry no clock at all. The operator has populated the book with five plans, their prices and the annual
term, and three businesses are marked as pilots.

**Owner:** the repo (delivered).

### M13. A trial ends by itself, and an unpaid business is reminded and then limited

**Required:** a scheduled transition when the free month or the paid period runs out, reminders before
it, and a guarded move to a limited state that skips pilots.

**State:** OPEN. Nothing happens when the free month ends: the clock is set and shown, but no job
reads it, nothing reminds anyone and nothing changes a business's state. The only lever is an operator
changing the status by hand, with a reason. Nothing in the product charges a card or a wallet; every
payment is money received elsewhere and typed in.

**Owner:** the repo (the scheduled transitions and reminders of ADR-024 decision 6).

### M14. A written process collects, receipts and reconciles subscription money

**Required:** a named way that a tenant pays, a receipt it can keep, a weekly review of who is about
to run out, and a ledger that matches what the operator has recorded.

**State:** OPEN. Subscription payments through the gateway (#228) are not built, so until M13 lands
this is a process rather than a feature: a payment channel, a receipt template, and a standing weekly
check of the businesses whose free month or paid period is near its end. Tax treatment of the
subscription fee still needs an answer.

**Owner:** Hillary.

### M15. A breach has a written response, and a tenant can reach support

**Required:** an incident runbook that says how to contain, how to find which tenants are affected,
when and how to tell them, how to notify the regulator and where the incident log lives; and a support
channel with hours, priorities, a ticket log and a rota that a tenant can actually see.

**State:** IN FLIGHT (#427). The pull request adds the security incident runbook, a support and on-call
runbook and a monitoring runbook, with the owner, rota and timing left as placeholders for Hillary and
for legal to fill.

Not in it: the support contact is still unset in production and there is no Help link in the app, so a
tenant has nothing to look at. That is a small separate change and it must land before launch.

**Owner:** Hillary (the rota, the hours, the legal timing), the repo (the runbooks and the Help link).

### M16. The two-step sign-in policy for tenant administrators is decided

**Required:** a decision, recorded and accepted, on whether a tenant administrator must use a second
factor; the code, the migration and the security chapter all saying the same thing; and the factor
switched on for the pilot tenant at onboarding whichever way the decision goes.

**State:** OPEN. The migration makes it optional by default, the ADR that describes that choice is
still proposed and not accepted, and the security chapter's own table still says the second factor is
mandatory for a tenant administrator. Platform operators are always required to enrol, and that is
unchanged.

**Owner:** Hillary (the decision), then the repo (the ADR and the chapter).

### M17. Repository visibility is settled, and merges to main are gated

**Required:** the repository is private; every change to `main` and every release tag passes
a review gate that the platform enforces, not only a convention; and the workflow and ADR text matches
the visibility chosen.

**State:** OPEN.

**Owner:** Hillary (the plan and the gate), then the repo (the corrections).

### M18. The features with open gates are switched off at launch

**Required:** online collections, the AI assistant, and savings and investments all off for every
tenant at launch, each turned on per tenant only after its own gate closes, with the outcome recorded
in its ADR.

**State:** OPEN. Online collections still wait on the unallocated-receipts work and on a written
confirmation that is a stated gate on the first live repayment; the AI assistant has a legal review and
the provider's written terms as go-live conditions; and whether a licensed money lender may take
deposits at all has not been checked anywhere.

**Owner:** Hillary.

### M19. A deploy never leaves a device on a broken cached page

**Required:** a build artifact that is not on disk answers as missing rather than as a page, errors
are never held by the browser, and every page revalidates after a deploy.

**State:** DONE (#436, issue #434). Found on a staging tenant: after a deploy, a device
holding a page from an earlier build could render the application unstyled, and keep doing so for a
year until its site data was cleared, because the web container answered a missing build artifact with
the application shell under a one-year immutable cache header. The same path also stopped the service
worker installing, which costs offline use and notifications. The fix serves hashed artifacts on their
own, answers 404 when one is missing, marks errors as never stored, and makes every page revalidate.
Verified against the deployed proxy version with a before-and-after table, and merged.

This is a launch-class defect: it hits any user on any tenant after any deploy, and the device does not
repair itself on a reload. It had no line in the first pass of this audit.

**Owner:** the repo.

---

## SHOULD within 30 days

Each line is still a control that must exist; none of them blocks the first tenant on its own.

| # | What must be true | State | Owner |
|---|---|---|---|
| S1 | Production containers run with the same hardening as staging: no new privileges, dropped capabilities, read-only roots and a CPU share each | OPEN. #433 adds the log caps to the production compose file but not the rest of staging's block | repo |
| S2 | The deploy refuses an unknown host instead of trusting it on first use, the host has its own firewall, and the deploy account holds only the rights a deploy needs | OPEN | repo |
| S3 | A second named custodian can reach the backup and data keys, and the old signing secrets are confirmed deleted | OPEN | Hillary |
| S4 | BMS has its own DNS zone, and the certificate token is scoped to that zone alone | OPEN | Hillary, then repo |
| S5 | The edge is trusted about the client address only where that is true, and this is tested on the production host | OPEN | repo |
| S6 | Backups are encrypted in a form that detects tampering, carry a checksum, and are read back before they are uploaded | OPEN. Raised at the first spec review as a go-live item | repo |
| S7 | A dump is taken before every migration, and the recovery point objective is agreed with the tenant in writing | OPEN | repo, Hillary (the agreement) |
| S8 | Uploaded documents and photos have a second copy or a version history | OPEN | repo |
| S9 | Every request is logged with its tenant, user, route, status and duration | OPEN. Only a request id is carried, which is not enough to answer "which tenant saw errors", to measure the performance targets, or to scope an incident under M15 | repo |
| S10 | The stated performance targets are measured, not assumed | OPEN. No HTTP load test exists and the performance ADR is still proposed | repo |
| S11 | A tenant can leave with its data, a person can ask what is held about them, and retention periods are written down | OPEN. Offboarding is one sentence; the export is a later requirement; retention is an open question | repo, Hillary (the periods) |
| S12 | The tenant has a notice template to give its own members and borrowers | OPEN. Member registration collects identity and income data with no model wording anywhere | Hillary, then repo |
| S13 | Staff invitations actually reach the invited person | OPEN (#324) | repo |
| S14 | Messages that fail or stall, and an empty prepaid SMS balance, raise an alert | OPEN. #427 covers the host and the nightly jobs; this is the message queue | repo |
| S15 | The SMS sender identity is registered, delivery is reported rather than assumed, and the regulator's rules on message types are known | OPEN (#215). Registration takes weeks, so it should start now | Hillary, then repo |
| S16 | The development sign-in stub is refused by environment as well as by profile, with a test | OPEN | repo |
| S17 | The operator tables are out of reach of the tenant database role | OPEN | repo |
| S18 | The refused-password list is a real breached-password list | OPEN | repo |
| S19 | Dependencies are scanned on a schedule and the alerts are read | OPEN, but smaller than it looked. The alerts were readable this time: four are open and all four are in build-time frontend tooling, none in anything that ships or runs in production, and all have fixes. There is still no scheduled scanning configured for the backend, the images or the workflows | Hillary, then repo |
| S20 | The design documents say what the code does | OPEN. The security chapter's sign-in table still contradicts the current second-factor behaviour (M16), the ADRs for payments, the installable app, the runner arrangement, the operator screens and the push notices are still proposed although built, and the workflow header and the proxy image note describe an earlier visibility arrangement (M17) | repo, Hillary (accepting the ADRs) |
| S21 | The lending product is checked against the rules that govern it: any rate cap, a written loan agreement, and what may be taken as collateral | OPEN. None of the three has an answer anywhere in the repository, and the identity-card collateral type is on by default | Hillary, then repo |

---

## LATER

| # | What must be true | State | Owner |
|---|---|---|---|
| L1 | A failed sign-in slows down rather than locking an account outright | OPEN. Revisit once M9 has landed and the limits are measured | repo |
| L2 | A large photo cannot be made to use a lot of memory on decode | OPEN | repo |
| L3 | Each plan's limits are set rather than unlimited | OPEN. The price book exists (M12); the limits per plan are still to be filled in | Hillary |
| L4 | There is a general per-address limit at the edge, in front of everything, not only on the sign-in family | OPEN | repo |
| L5 | Recovery to a point in time, not only to last night | OPEN. When a tenant contract needs a tighter objective than a day | repo |
| L6 | National identity numbers are encrypted at field level | OPEN. Decide together with the region ADR (M3) | Hillary |
| L7 | Outgoing email carries a reply address that someone reads | OPEN. Smaller now that the support address exists (M10) | repo |
| L8 | Signing uses a managed key and a transparency log | OPEN. Revisit after M2 | repo |
| L9 | Small hardening items tidied in one pass | OPEN | repo |
| L10 | A status page and a published service level | OPEN. After M15 | Hillary |

---

## What is already true (the ground the plan stands on)

- **Tenant isolation.** Every tenant table goes through one helper that enables and forces row-level
  security with a fail-closed policy; no migration turns it off; an isolation test discovers every such
  table from the catalogue and asserts the policy, with cross-tenant read, write, delete and
  unbound-session cases beside it. The tenant comes from the host name, and a token's tenant must match
  it.
- **Database roles.** The application role cannot bypass row-level security and owns nothing, and a
  startup guard checks this. Definer functions pin their search path and revoke public execute.
- **Authentication.** argon2id passwords; short-lived access tokens held in memory only; a host-prefixed,
  http-only, strict same-site refresh cookie rotated with reuse detection; reset and invitation tokens
  of full length, carried in the URL fragment and stored hashed; permissions deny by default; platform
  operators always enrolled in a second factor.
- **Web.** A content security policy with no inline scripts, framing refused, strict transport security,
  no content sniffing and a referrer policy; a body size cap at both the edge and the application;
  generic error documents; management endpoints on an internal port; uploads typed by content, vector
  images refused and raster images re-encoded.
- **Payments.** A callback is never trusted: every change re-queries the provider with the tenant's own
  credentials and checks the reference, currency and amount. Writes are idempotent and taken under row
  locks. Tenant credentials are sealed per tenant and never returned. Sandbox and fake settings are
  refused in production.
- **Messaging.** Production refuses fake and sink SMS providers. Caps, opt-out, sending windows, and a
  delivery log that holds no phone numbers. Push keys are validated and ephemeral keys refused on a
  server.
- **Backups.** Encrypted on the host and stored off it with daily and monthly tiers; the drill script
  restores into a throwaway container with no network and checks that isolation still holds.
- **Email.** A verified sending domain with SPF, DKIM and DMARC, a branded layout built from the plain
  text template, and a support line in the footer (M10).
- **Retail, as of 22 October.** Staff scoped to a shop or store with storekeeper and purchasing roles;
  sellers who see only their own figures; item variants and photos; customer orders that hold stock
  aside and become a sale on delivery; low stock minimums, daily notices and turn-on push notifications;
  the installable app; and the operator portal with businesses, the business page and the price book.
- **No secrets committed.** The example environment file holds placeholders only, and a pattern search
  finds no keys outside tests.

## What this checklist could not see

- GitHub settings beyond visibility: variables, secrets, environment reviewers and runner groups.
- Anything on a live host: installed timers, bucket lifecycle and lock state, and whether the operator
  alert channel is configured on a production host, since there is no production host yet (M1).
- Provider accounts and their balances, and the live merchant registration for payments.
- Documents kept outside git: any registration, agreement or legal opinion.
- Load behaviour. Nothing here is measured (S10); the test suite was not run for this pass.
- Every legal statement. A qualified Ugandan advocate must confirm each one, and the questions in M3,
  M4, M5, M18 and S21 in particular. Nothing in this file is legal advice.
