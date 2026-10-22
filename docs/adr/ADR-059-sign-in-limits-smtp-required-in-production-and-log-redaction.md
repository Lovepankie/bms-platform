# ADR-059: Sign-in limits that count failures, a database lock for every address, bounded password hashing outside transactions, SMTP required in production, and redacted, capped logs

## Status

Proposed (issue #426; launch readiness audit #416, items M8, M9 and M10; revised after the review of PR #433;
renumbered at merge if needed, because #427 proposes pending ADR-058 at the same time). Builds on ADR-014 (argon2id,
the five-failure lock of FR-IAM-05), ADR-042 (the forgotten password, whose per-address reset bucket this widens into
one throttle for the whole sign-in family), ADR-024 (the outbox senders that are off when unset) and ADR-036 (the SMS
startup guard this copies for email). **It amends two things, named here:** ADR-042 decision 7 (a completed password
reset now lifts the failed-attempt lock; it no longer leaves it alone), and the limit table of chapter 7 section 7.10,
whose "staff sign-in per login identifier, 10 per 15 minutes" was never built and is replaced by the database lock
(five failures in a row, 15 minutes) for every address, real or not. The lock itself, its length and its words are
unchanged. The contract is in chapter 7 sections 7.7, 7.10 and 7.11, the controls in chapter 8 sections 8.2 and 8.11,
the settings in chapter 9, the table in chapter 6.

## Context

The launch readiness audit of 21 October 2026 (#416) found three gaps in the application itself that must close before
a real tenant uses production:

- **M9.** `/staff/login` and `/staff/mfa/verify` had no throttle; only the two reset routes had one. A stranger who
  knows a login name could spray passwords across every tenant's staff, and the lock itself told them which names
  exist: five wrong passwords for a real account answered 423 `account_locked`, for an unknown one 401 for ever.
  argon2id takes 64 MiB a hash with nothing bounding how many run at once, and virtual threads are on, so about
  sixteen sign-ins at once, even for accounts that do not exist, could fill the 1.5 GB container's heap. Production
  DNS is not proxied through Cloudflare, so no edge rule helps.
- **M10.** Password resets, invitations and activation links are email only, and a sender whose values are unset is
  quietly off: production could start with no email and nobody would know until a lender could not get in.
- **M8.** NFR-SEC-08 and chapter 8 promise a log redaction filter with a unit test. None existed. A PostgreSQL duplicate
  key message prints the refused value, which can be a phone number, and the API logs unexpected exceptions with their
  text. Production `compose.yml` had no `logging:` block, so container logs could fill the 40 GB disk.

The first version of this decision was reviewed on PR #433 and changed in these ways, for these reasons:

- The lock of an address that matches no account lived in a 10,000-key in-memory LRU. Any restart (staging restarts on
  every green build) or a spray of about 10,000 made-up addresses emptied it, and then one more try told a real account
  (423) from none (401). The same eviction reset drained per-IP buckets.
- Anyone could keep a real owner locked out for good (five wrong passwords every 15 minutes), a 30-per-15-minutes
  address bucket that counted right passwords too let two requests a minute turn the owner away with 429 even with the
  right password, and nothing in the application lifted a lock.
- Each sign-in held its database connection while it waited up to two seconds for a hashing slot, so a sign-in flood
  took the whole pool (ten connections) from every tenant.
- Users reach the service over MTN and Airtel mobile data, where carrier-grade NAT puts many subscribers behind one
  public IPv4. Sixty requests per IP in 10 minutes, successes included, with two to three requests for a sign-in with
  two steps, let about twenty people on one carrier address use the budget up, and let anyone on that carrier drain it
  for everyone else with sixty requests. Pilots are live, so a limit that locks out real users is worse than none.
- The redaction filter missed secret keys inside longer names, leaked most of a quoted password with spaces, missed
  URL-encoded and spaced Uganda numbers and lower-case NINs, hid large UGX amounts and epoch times as phone numbers,
  and its email pattern took quadratic time on a long run of letters and dots.

The client IP itself is right in production and is not changed here: DNS-only records, Caddy distrusts public senders,
Tomcat's RemoteIpValve takes the right-most untrusted `X-Forwarded-For`; staging uses Cloudflare's appended visitor IP.

## Decision

1. **The lock is the limit, in the database, for every address.** The five-failure lock of FR-IAM-05 (five wrong
   passwords in a row lock for 15 minutes; a failure while locked keeps the lock; the count starts again after it) is
   what stops a guesser. An account keeps it in `users` (`platform_users` for operators). Every other address typed,
   the email in lower case or the phone in E.164 within one tenant and one kind of account, keeps it in
   `sign_in_address_failures` (migration V78), keyed by the SHA-256 of that address, with the same single-statement
   count and lock. So the lock of an address of nobody survives a restart and cannot be evicted, and after five
   failures it answers the same 423 `account_locked` with the same words for the same time as an account. Both paths
   read the address's row and the account before the hash, then lock the row (the account's, or the address's) and
   read its lock again **before looking at the result of the hash**: once a lock is in force every answer is 423,
   right password or wrong, so a burst of guesses that all passed the first look before the lock cannot single out the
   right one (delta review of #433). Then one counter row and the same audit rows are written; neither lock pays for a
   hash.
2. **In memory, only noise, and only failures per IP.** `AuthThrottle` keeps token buckets, per instance and lost on
   restart:
   - **Failed requests per client IP** (an IPv6 /64, an IPv4 address) on staff and operator sign-in, the second factor
     (verify, enrol, confirm), invitation acceptance and the operator's first password:
     `BMS_AUTH_SIGN_IN_FAILURES_PER_IP` (property `bms.auth.sign-in-failures-per-ip`) in 10 minutes, **300** by
     default. A token is taken before the work, so a burst sent at once cannot all pass a nearly empty bucket together,
     and given back unless the work failed: a success costs nothing, and neither does a 423 lock, a 429 or a 503; a wrong
     password, code or token or a weak password does. While the bucket is empty the IP gets 429 before any work. The reasoning: under carrier NAT
     one public IPv4 is hundreds of subscribers, so counting their successes punishes them for each other's sign-ins;
     their typing mistakes are what the budget must absorb, and 300 failures in 10 minutes (one every two seconds) is
     far above what one office or one carrier address of honest users makes, while the database lock still allows only
     five guesses per address per 15 minutes from all IPs together. The per-IP budget only bounds how many addresses
     one client can try and how many hashes it can make us run.
   - Reset requests: 20 an hour per client IP, 10 an hour per sign-in address from any IP (the database sends at most 3
     links of them); link uses: 30 in 10 minutes per client IP. These count every request, as before.
   - Second-factor attempts: 30 in 15 minutes per account (the fifth wrong code in a row still locks it).
   There is no in-memory budget per sign-in address any more: the database lock is it, and it never refuses a right
   password before the fifth failure. Each limit keeps at most 50,000 keys and **never evicts a bucket that remembers
   anything**: when full it drops only buckets that have refilled completely (at most one sweep a second); when every
   kept bucket still holds state, a new key is let through uncounted (logged at most once a minute) rather than
   forgetting one or refusing every newcomer. Every 429 carries `Retry-After`, the time until the bucket holds one
   token again.
3. **A lock can be lifted, three ways, all audited.** A completed password reset (the link from the owner's own
   mailbox) lifts the lock and starts the count again (`core.auth.unlocked`, by `password_reset`; amends ADR-042
   decision 7). A tenant admin lifts the lock of a staff user of the tenant, any role, with a reason (`POST /users/{user_id}/unlock`,
   `core.users.manage`; the reason is kept in the audit row). The platform operator lifts the lock of a tenant's staff user with a reason
   (`POST /platform/tenants/{tenant_id}/users/{user_id}/unlock`, `platform.tenants.manage`, both audit logs). None
   changes a password, a second factor or a session, so none needs a step-up. Nothing lifts an operator's lock: a
   tenant admin cannot reach an operator and the operator route only reaches a tenant's users; an operator's lock
   lapses after 15 minutes.
4. **Hashing is bounded and never inside a transaction.** At most `bms.auth.argon2-max-concurrent` argon2id hashes run
   at once (4 by default: 256 MiB at the production cost), behind a fair semaphore. A caller that waits two seconds
   without a slot gets 503 `sign_in_busy` with a `Retry-After` of two to five seconds, spread so the callers turned away
   do not all return in the same second. The dummy hash for an unknown account takes a slot like any other. No caller
   holds a database connection while it waits for a slot or hashes: sign-in and the step-up password read in one short
   transaction, hash, and record in a second; invitation acceptance, the password reset and the operator's first
   password check their token in a read-only transaction, hash, then check it again under a row lock and write.
   `Passwords` refuses to hash inside a transaction, so a new caller that forgets fails its first test.
5. **Production refuses to start without email.** With `BMS_ENVIRONMENT=production` the email channel's constructor
   checks `BMS_SMTP_HOST`, `BMS_SMTP_USER`, `BMS_SMTP_PASSWORD` and `BMS_MAIL_FROM` (which must parse as an address)
   and stops the start with a message that names the unset variables and never a value, as the SMS and Pesapal guards
   do. All three read `BMS_ENVIRONMENT` the same way (trimmed, lower case, exactly `production`), so one value turns
   every guard on or none. `deploy/compose.yml`, which runs production only, passes `${BMS_ENVIRONMENT:-production}`:
   an unset value is production there and the guards stay on (a `:?` would also stop the CI step that parses the file
   with placeholder values). Staging, a laptop and an unset environment elsewhere keep the quiet off. The provider and
   SPF, DKIM and DMARC are a separate decision (pending, Hillary).
6. **Server logs are redacted in the formatter.** The `server` profile (staging and production) names
   `com.rincoltech.bms.kernel.LogRedaction` as `logging.structured.json.customizer`, so every string of every ECS JSON
   line (the message, the exception text and stack trace, each MDC value) passes through it:
   - the value after a secret key, alone or ending a longer name (`token`, `resetToken`, `invitation_token`,
     `"newPassword"`, `client_secret`, `BMS_SMTP_PASSWORD`, `x-api-key`, `pin`, `otp` and the like), a quoted value up
     to its closing quote; with no word boundary, so a harmless key ending in one (`spin=`, `shipping_pin=`) loses its
     value too, on purpose; the credential after `Authorization:`, `Bearer` or `Basic`; signed tokens;
   - email addresses; NINs in any letter case; Uganda mobile and landline numbers with spaces or dashes, `+`, a
     URL-encoded `%2B` or a leading 0, and any other standalone run of 9 to 15 digits, **except** a run labelled as an
     amount, time, count or id (`amount=`, `_minor=`, `balance=`, `id=`, `ts=` and the like), an epoch time in
     seconds or milliseconds, or a compact date and time, which a lending ledger needs;
   - the value after a name key (`name=`, `full_name=`, `customerName=`, a record's `Customer[name=...]`).
   Every pattern is linear: bounded or possessive runs anchored at the start of a run, a cheap pre-check per pattern,
   and the quoted value found by a scan rather than a repeated group, so a long logged URL or token costs one read and
   cannot overflow the regex stack. The dev and test profiles log plain text of fabricated data and are not filtered.
7. **Container logs are capped and rotated** with Docker's `local` driver on every service: five files of 20 MB in
   production (`deploy/compose.yml`), three of 10 MB locally (`docker-compose.yml`); staging keeps its two of 5 MB.
   PostgreSQL runs with `log_error_verbosity=terse` in all three files, so its own log never prints the `DETAIL` of a
   refused duplicate (the key value, a phone number) into what the driver keeps.

## Consequences

**Better:**
- After five failures an address of nobody answers exactly as an account, and that holds across restarts and floods;
  password spraying is bounded to five guesses per address per 15 minutes from all IPs together.
- Honest users behind one carrier address do not use up each other's budget, and a right password is never refused by
  an address budget.
- A locked-out owner gets back in by resetting the password or by asking an admin or the operator.
- A sign-in flood slows sign-in with a clear 503 and leaves the database pool to every other tenant.
- Production cannot go live with email off; the redaction NFR-SEC-08 promised exists, with tests that run Spring Boot's
  own ECS encoder; the disk cannot fill with container logs.

**Worse:**
- **The lock is a denial of service by design.** Anyone who knows or guesses a sign-in name (operator emails are
  guessable) can keep that account locked by sending five wrong passwords every 15 minutes. We accept it because the
  alternative, no lock, lets the same stranger guess the password. The owner's way out is a reset (needs their
  mailbox), an admin of the business, or the operator; an operator locked this way waits out the 15 minutes and is
  locked again by a persistent attacker, which a Caddy rate limit or an allow-list on the platform host would end.
- Lifting the lock at a reset gives whoever reads the owner's mailbox more guesses at the second factor: up to three
  resets an hour, five codes after each, on top of the five every 15 minutes the lock allows anyway (35 an hour
  instead of 20, against a code that changes every 30 seconds), and every reset changes the owner's password, ends
  their sessions and emails them.
- Not every difference is hidden. An address of nobody forgets its count after seven days without a failure (the
  nightly purge keeps the table small); an account keeps its count until a right password. A prober who spreads five
  failures over more than a week can tell them apart, one address a week. The database work of the two paths is the
  same but not timed to the microsecond.
- An attacker on the same carrier address can still drain that address's failure budget for everyone behind it, at one
  failure every two seconds; the budget is a setting.
- A drained per-IP bucket and the reset buckets are still per instance and lost on restart; only the locks survive.
  When 50,000 kept buckets of one limit all still hold state, new keys go uncounted until some refill.
- Redaction is by pattern: it also hides some harmless numbers (an unlabelled 9 to 15 digit id, a `name=` that is not
  a person's) and cannot recognise a name written into free text with no key, so code must still not log one.

**Watch for:**
- 429 and 503 counts on the sign-in routes, `core.auth.account_locked` and `core.auth.unlocked` in the audit log, and
  the warning that a throttle is full; raise `BMS_AUTH_SIGN_IN_FAILURES_PER_IP` or the hashing bound if real users
  meet them.
- **The hashing slots can be kept busy.** About 50 client IPs or IPv6 /64s (each within its 300 failures in 10
  minutes) sending wrong passwords as fast as they can keep all four hashing slots busy, and real users then get 503
  `sign_in_busy` until it stops. This is inherent to a bounded hash and accepted: the alternative is a heap that fills.
  Watch the rate of 503 `sign_in_busy` and of `core.auth.sign_in_failed` per minute and the number of distinct client
  addresses among them; the answers are a Caddy rate limit, proxying production through Cloudflare, or more slots.
- The size of `sign_in_address_failures` (a spray of made-up addresses adds one row each until the purge).
- A Caddy rate limit (or proxying production through Cloudflare and trusting `Cf-Connecting-Ip`) is still the
  stronger first line and is not part of this decision.
- The member sign-in routes (FR-IAM-09, P2) are not built; when they are, they use the same database lock and the
  same per-IP failure budget.
