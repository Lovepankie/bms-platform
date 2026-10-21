# Runbook: support and on-call

**Applies to:** production, and staging during a pilot · **Requirements:** NFR-AVL-01, NFR-DP-05 · **Decision:** ADR-058 ·
**Issue:** #425 · **Related:** `monitoring-and-alerts.md`, `security-incident.md`, `rollback.md`, `restore-from-backup.md`

Every value in angle brackets is a placeholder for Hillary to fill before launch. Until a row is filled, its owner is Hillary.

## 1. Contacts and owners

| What | Owner | Where |
|---|---|---|
| Support channels and hours (this runbook) | `<SUPPORT_OWNER, default Hillary Arinda>` | |
| On-call rota (section 4) | `<ON_CALL_OWNER, default Hillary Arinda>` | |
| Support WhatsApp number | `<SUPPORT_OWNER>` | `<+256 7XX XXX XXX, a company phone, not a personal one>` |
| Support email (a shared inbox) | `<SUPPORT_OWNER>` | `<support@...>` |
| `BMS_SUPPORT_CONTACT` in `/opt/bms/.env` (the footer of every email, ADR-054) | `<SUPPORT_OWNER>` | the same number and address |
| Ticket log (section 3) | `<SUPPORT_OWNER>` | `<shared inbox labels, or a private tracker>` |
| Telegram operator chat (alerts) | `<ON_CALL_OWNER>` | members: `<names>` |
| External uptime monitor account (`monitoring-and-alerts.md` section 1) | `<ON_CALL_OWNER>` | `<provider, account email>` |
| Cloud provider console for the VM | `<ON_CALL_OWNER>` | `<provider>`, two-step sign-in on |
| Cloudflare account (DNS, R2, bucket locks) | Hillary | two-step sign-in on |
| Pilot tenant's named contacts | `<TENANT_LIAISON>` | `<name, role, phone>` of the owner and one backup |

## 2. Support hours and what the tenant is told

- Support hours: `<SUPPORT_HOURS, proposed Monday to Saturday 08:00 to 18:00 Kampala time>`. Publish the same hours in the terms
  of service (audit item M5) and on the Help link of the app (a separate Task).
- First answer: within `<FIRST_RESPONSE, proposed 2 working hours>` in support hours; a P1 at any time (section 3).
- Outside support hours, the tenant is told to message the WhatsApp number; only a P1 wakes someone.

## 3. Taking a request

Every request gets one line in the ticket log the same day, also one solved on the phone:

```
<date> <time> <tenant> <who asked> <channel> <P1 to P4> <one line> <owner> <status> <link to issue, if any>
```

Write no national ID numbers, phone numbers, loan figures or passwords in the log or in a GitHub issue; refer to records by the
app's own reference (a receipt or loan number). Never ask a user for a password or a sign-in code.

| Priority | Meaning | Answer | Examples |
|---|---|---|---|
| P1 | Nobody at a tenant can work, or data may be exposed | At once, any hour; start `security-incident.md` if data may be exposed | Site down, sign-in broken for everyone, a cross-tenant view |
| P2 | One important task is blocked for a tenant, no workaround | Same working day | Repayments cannot be recorded, a report the cash office needs fails |
| P3 | A problem with a workaround | Within `<P3_DAYS, proposed 3>` working days | A screen error with another path, a wrong label |
| P4 | A question or a request | When planned | How do I, can you add |

A bug becomes a GitHub issue (a Bug under its Story, `PROCESS.md`) in neutral words; tell the tenant the outcome, not the issue
number.

Common requests and where the answer is:

| Request | Runbook |
|---|---|
| A staff user forgot the password | the business's admin issues a link, or the user uses Forgot password (ADR-042) |
| A tenant admin lost the authenticator | operator reset (`docs/sdd/07-api-design.md`, `/platform/tenants/{id}/users/{id}/reset-sign-in`) after checking who is asking by a call back to the number on record |
| A new branch, user or setting | the tenant's admin does it; `onboard-tenant.md` for operator steps |
| SMS not arriving | `sms-incident.md` |
| A payment not showing | `payments.md` |
| A data import | `import-retail.md`, `customer-data-cutover.md` |

Before acting for a caller on anything that changes access (a reset, a new admin, an export), confirm who they are by calling back
the tenant contact on record, never the number that called.

## 4. On call

| Week starting | Primary | Secondary |
|---|---|---|
| `<date>` | `<name>` | `<name>` |
| `<date>` | `<name>` | `<name>` |

- The primary keeps the phone loud for the operator chat and the uptime monitor's alerts, and can reach a laptop with the host
  SSH key within `<ON_CALL_REACH, proposed 30 minutes>`.
- Out of hours, the primary answers a `site` alert, an external monitor alert and any P1. `disk`, `tls`, `backup`, `drill` and
  `jobs` alerts wait until the next morning unless the message shows a backup missing for more than 48 hours or disk above
  90 percent.
- If the primary has not acknowledged a P1 in the operator chat within 15 minutes, the secondary takes it; if neither has within
  30, the incident lead (`security-incident.md`) is called.
- The person who answers says so in the chat ("on it"), then posts what they found and when it cleared.
- A handover at the start of each week: open alerts, open P1 and P2 tickets, planned work.

Until the team has two people on call, Hillary is primary and `<SECONDARY_DEFAULT>` secondary, and the pilot tenant is told that
out-of-hours answers are best effort.

## 5. When the site is down

1. Acknowledge in the operator chat.
2. `monitoring-and-alerts.md` section 3, row `site`; a bad release is `rollback.md`; a lost host is `restore-from-backup.md`.
3. If it lasts more than `<TENANT_UPDATE_MINUTES, proposed 30>` minutes, message the pilot tenant's contact: what is affected,
   that we are working on it, and when the next update comes. Repeat at that time.
4. Afterwards, one line in the ticket log and, for anything over an hour, a short review like section 5 of `security-incident.md`.
