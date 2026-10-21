# Runbook: security incident and personal data breach

**Applies to:** production (staging holds fabricated data only, but use the same steps for practice) · **Requirements:** NFR-DP-05
· **Decisions:** ADR-003 (a cross-tenant leak is a breach), ADR-058 · **Issue:** #425 · **Related:** `support-and-on-call.md`,
`restore-from-backup.md`, `rollback.md`, `sms-incident.md`

Nothing here is legal advice. Every step that touches a notice, a deadline or a regulator is for **legal** (a qualified Ugandan
advocate) to confirm before launch, and the confirmed answers are written into section 7.

## Roles (fill in before launch)

| Role | Who | Backup | How to reach them out of hours |
|---|---|---|---|
| Incident lead: decides, owns the log and every outside message | `<INCIDENT_LEAD, default Hillary Arinda>` | `<INCIDENT_LEAD_BACKUP>` | `<phone or chat>` |
| Technical lead: contains, investigates, restores | `<TECH_LEAD>` | `<TECH_LEAD_BACKUP>` | `<phone or chat>` |
| Legal: notice timing, wording, the PDPO | `<LEGAL_CONTACT, advocate and firm>` | none | `<phone, email>` |
| Tenant contact: speaks to the affected tenant | `<TENANT_LIAISON>` | the incident lead | `<phone or chat>` |

One person may hold two roles; the incident lead is never also the only person investigating.

## 1. What counts

Start this runbook, and tell the incident lead at once, for any of:

- one tenant's data seen by another tenant's user, a member, or the public (ADR-003: always a breach);
- a leaked secret: the host `.env`, `BMS_DATA_KEY`, `BMS_TOKEN_SIGNING_JWK`, `BACKUP_ENCRYPTION_KEY`, the R2, Cloudflare, Pesapal,
  SMS, SMTP or Telegram credentials, the cosign key, a GitHub token, an operator's password or authenticator;
- someone else in a host, a platform operator account, a tenant admin account, the Cloudflare or GitHub account;
- personal data sent to the wrong person (an SMS or email to the wrong number or address, an export shared by mistake);
- backups deleted, encrypted or downloaded by someone who should not have;
- a report from a tenant, a member, a researcher or the press that any of the above happened.

When in doubt, start. Closing an incident that was nothing costs an hour.

## 2. First hour: contain

1. **Open the incident log** (section 6) and write the time you learned of it, from whom, and what you know. Every action from now
   on gets a line with its time (UTC) and who did it.
2. **Keep evidence before changing anything:**
   ```bash
   cd /opt/bms && mkdir -p state/incident-$(date -u +%Y%m%d) && cd state/incident-$(date -u +%Y%m%d)
   docker compose --project-name bms -f /opt/bms/compose.yml logs --since 72h --timestamps > containers.log 2>&1
   cp /opt/bms/state/history.log /opt/bms/state/*.log . 2>/dev/null
   journalctl --since -72h > journal.log; last -F > logins.txt
   ```
   Copy the folder off the host to the incident lead's encrypted storage, never into a chat, an issue or this repository.
3. **Cut the access that is in use.** Pick what fits; do not wait for the full picture:
   - a staff or member account: deactivate it in the app or end its sessions as `bms_owner`:
     `UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'admin' WHERE user_id = '<id>' AND revoked_at IS NULL;`
   - every session of every user: rotate `BMS_TOKEN_SIGNING_JWK` (the `keys` command, `provision-host.md` section 4), restart the
     API, and revoke every open session with the same statement without the `user_id` filter (and on `platform_sessions`
     for the operator accounts, by `platform_user_id`);
   - a leaked provider secret: rotate it at the provider first, then in `/opt/bms/.env`, restart (`payments.md`, `sms-setup.md`,
     `ai-assistant.md` each have the rotation steps); rotating `BMS_DATA_KEY` follows `payments.md` section 4a;
   - a host you no longer trust: take it off the network at the provider (firewall or power off), restore onto a fresh VM
     (`restore-from-backup.md`), and rotate every secret it held;
   - a code path that leaks across tenants: stop serving it (roll back with `rollback.md`, or stop the API and say so) before
     fixing it.
4. **Tell the incident lead and, once something is contained, post one line in the operator chat:** "Security incident open,
   `<short neutral title>`, lead `<name>`". No personal data, tenant names or secrets in the chat.

## 3. Same day: find who is affected

Work as `bms_owner` on the live database or, if the data may have been changed, on a restore of a backup taken before the
incident (`restore-from-backup.md`, "existing host" method into `bms_restored`). Record every query and its counts in the log;
copy no personal data out of the database.

- **Which tenants:** the tenants whose rows the path touched, from the code path and the request log (request ids are on every
  log line), and from `audit_log` (`tenant_id`, `actor_user_id`, `actor_kind`, `action`, `entity_type`, `entity_id`, `ip`,
  `request_id`, `created_at`) and `platform_audit_log` (`platform_user_id`, `action`, `tenant_id`, `ip`, `occurred_at`).
- **Which accounts acted:** `auth_sessions` (`user_id`, `ip`, `user_agent`, `created_at`, `revoked_at`) for the window.
- **Which people:** count the members, borrowers or staff whose records the path exposed, by tenant, and which kinds of data
  (name, phone, national ID number, loan figures, savings balances, photos and documents).
- **Exports and reports:** `audit_log` actions for audit and report exports in the window.
- **Messages:** the outbox and SMS delivery log for messages sent to a wrong address or number.

Write the result as: tenants affected, people affected per tenant (a count), kinds of data, the window (first and last time), and
whether the data was seen, copied, changed or deleted.

## 4. Tell the tenant, and help it tell the regulator

Under the Data Protection and Privacy Act 2019, the tenant is the data controller for its members and borrowers and Rincol Tech is
its processor; for the staff and operator accounts it holds itself, Rincol Tech is the controller. Section 23 of the Act asks the
controller to notify the Personal Data Protection Office (PDPO) of a breach. **Legal confirms the deadline and wording before
launch (section 7).**

1. **Tell each affected tenant within `<TENANT_NOTICE_HOURS, legal to confirm, proposed 24>` hours of confirming the breach**, by
   phone to its named contact, then in writing (email) the same day. Use the template below. The tenant decides whether and how it
   tells its members; offer the facts it needs.
2. **Where Rincol Tech is the controller** (its own staff, operators, or a breach of the platform itself), the incident lead sends
   the PDPO notice drafted with legal.
3. **Keep each sent message** (who, when, how, what) in the incident log.

Tenant notice (email, plain words, no blame, no guess presented as fact):

```
Subject: Security incident affecting <business name> on BMS

On <date> we found that <what happened, one sentence>. It affected <kinds of data> of about <count> of your
<members / borrowers / staff> between <first time> and <last time>. <Seen / copied / changed / deleted: what we know>.

What we have done: <contained how, when>.
What we are still checking: <open questions>, and we will update you by <time>.
What you may need to do: as the controller of your members' data you may need to notify the Personal Data Protection
Office and the people affected. We will give you any facts you need for that.

Your contact for this incident: <name, phone, email>.
Rincol Tech
```

PDPO notice draft (for legal to complete and send, or to hand to the tenant as a starting point): the controller and processor
names and contacts; the nature of the breach; the categories and approximate numbers of people and records; the likely
consequences; the measures taken and proposed; the time of discovery and of the notice; the contact for further information.

## 5. Recover and close

- Fix the cause with a normal pull request (a Bug under its Story, `PROCESS.md`), with a test that fails before the fix. A
  cross-tenant fix adds the case to the isolation test catalogue. Describe the fix in neutral words: no tenant names, no
  personal data, no secrets in the issue, the commit or the pull request.
- Rotate every secret that may have been seen, even if nothing shows it was used.
- Restore data that was changed or deleted from the last good backup, tenant by tenant, with the tenant's agreement.
- Within `<REVIEW_DAYS, proposed 5>` working days, hold a review: timeline, cause, what detected it, what slowed us, actions with
  owners and issues. Keep the review with the incident log; record only the action items, in neutral words, in GitHub.
- Close the incident when the cause is fixed, every notice is sent, and the review is held. Post one line in the operator chat.

## 6. The incident log

The log is kept **outside this repository** (it may hold personal data and tenant names), in `<INCIDENT_LOG_LOCATION, for
example a restricted shared folder>`, one file per incident named `<YYYY-MM-DD>-<short title>`. Every incident is logged, also a
suspected breach that turned out to be nothing (the Act's accountability duty is easier to show with a complete log).

```
Incident: <YYYY-MM-DD short title>        Lead: <name>        Status: open / contained / closed
Detected: <time UTC>, by <who or what>, reported to the lead at <time>
Kind: cross-tenant / leaked secret / account takeover / wrong recipient / backups / other
Timeline (UTC):
  <time> <who> <what was done or learned>
Affected: tenants <n>; people <count per tenant>; data <kinds>; window <from> to <to>; seen / copied / changed / deleted
Notices: tenant <who, when, how>; PDPO <who, when, reference>; people <by the tenant, when>
Cause:
Fix: <issue and pull request numbers>
Secrets rotated: <which, when>
Review: <date, attendees, actions with owners>
```

## 7. Answers legal must give before launch

| Question | Answer (legal) | Date confirmed |
|---|---|---|
| The PDPO notice deadline and channel under section 23 and the regulations | `<to fill>` | |
| The hours within which Rincol Tech, as processor, must tell a tenant (to put in the processing agreement) | `<to fill>` | |
| When the people affected must be told, and by whom | `<to fill>` | |
| Whether a breach of a lender's data must also be reported to its regulator (UMRA for Tier 4) | `<to fill>` | |
| Rincol Tech's PDPO registration number (audit item M4) | `<to fill>` | |

Practise this runbook on staging once before launch with a made-up scenario and fabricated data, and record the practice in the
incident log location as `practice`.
