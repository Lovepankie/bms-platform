# Subscription billing: reminders, overdue, the suspension list and receipts

For the platform operator. What the platform does by itself about a business's subscription, what it never does, and what to do
by hand (issue #480, ADR-075). Payments are still recorded by the operator on the Business page (ADR-056).

## What happens by itself

| When (in the business's own time zone) | What | Where to see it |
|---|---|---|
| 7 days before the last day paid for, the day before, on the day, from 08:00 | An email to the Owner with the price a month and a year, and how to pay; an SMS too only if billing SMS is on | The Business page, Emails sent |
| The same three days before the last day of the free month | The free month reminders | The Business page, Emails sent |
| The first quarter hour after midnight following the last day | The subscription becomes overdue (`past_due`); the business keeps working | The Business page history: `platform.tenant.subscription_overdue`, with no operator |
| While overdue and within the grace period | Every member of the business's staff sees "overdue since ...; pay by ..." | |
| After the grace period | The business appears in Due for suspension (`/platform/billing`) | Due for suspension |
| When you record a payment | A numbered receipt (`RT-<year>-<number>`) is emailed to the Owner (or the billing contact the Owner named, ADR-090) as a PDF | The Business page, Emails sent; the Owner's Plan and billing page |

**Who gets them (ADR-090).** When the Owner named a billing contact on Plan and billing (an accountant, say), the reminders and
receipts go to that person instead of the Owner. It never changes anyone's sign-in. A business that asked to close gets no
reminder and is never made overdue (`docs/runbooks/closing-a-business.md`).

**Nothing is ever suspended by itself.** A pilot (complimentary) business, a suspended or cancelled business and one already overdue
get no reminder. A business with no Owner gets no email: give it an Owner first (Business page, the owner rescue). The
reminder of that day is still marked as done, so an Owner added later the same day gets the next one, not that one. A
business with a status change you scheduled, or whose Owner cancelled (ADR-073), gets no reminder and is never made overdue or listed.

## Set the grace period and how to pay

1. Open **Due for suspension** in the operator portal.
2. Under **Grace period and how to pay**, set the days (0 to 60; 7 unless changed) and the words on how to pay (the mobile money
   number or bank account, in plain words; up to 1000 characters). They appear in every reminder and on the Owner's page.
3. Give a reason and Save. The change is in the platform audit log as `platform.billing.settings_changed`. It applies at once to
   every overdue business: a longer grace period takes businesses off the list, a shorter one adds them.

Payment details are configuration typed here, never in the repository or a ticket.

## Work the list

1. Open **Due for suspension**. Each business shows how many days it is overdue, since when, when its grace ended and the price of
   its next month (a business whose plan has no price shows none: enter one in Plans and prices).
2. Call or write to the owner (the name, email and last sign-in are on the card).
3. If they pay: record the payment on the Business page as usual. The business becomes active, comes off the list and gets its
   receipt. A late payment's month starts the day it is recorded (the late days are not charged).
4. If you decide to suspend: open the business and use **Suspend** with a reason. It becomes read only; nothing is deleted, and a
   resume brings it back.

## A receipt

- The Owner gets it by email when the payment is recorded and can download it again from Business set-up > Plan and billing.
- The issuer details on it come from the environment: `BMS_PLATFORM_BILLING_ISSUER_NAME` (default `Rincol Tech`),
  `BMS_PLATFORM_BILLING_ISSUER_ADDRESS`, `BMS_PLATFORM_BILLING_ISSUER_CONTACT` and `BMS_PLATFORM_BILLING_ISSUER_TAX_ID`, set in the
  host's environment file, never in the repository. A receipt is rendered when it is sent or downloaded, so a change to these
  shows on old receipts too (the business and plan names on a receipt never change).
- A receipt email that failed (no SMTP, a bounced address) shows in **Messages not sent**; send it again from there once fixed.

## Switches

| Variable | Default | What |
|---|---|---|
| `BMS_PLATFORM_BILLING_JOB_ENABLED` | `true` | The quarter-hourly job (`core.platform.subscription-billing`): the reminders and the overdue move. `false` stops both; nothing is lost but the reminders of the days it was off |
| `BMS_PLATFORM_BILLING_SMS_ENABLED` | `false` | Also text the Owner. The SMS still needs the SMS channel on (`BMS_SMS_MODE`) and the business's own SMS switch on, and is counted in that business's SMS use |

## Correcting a mistake

- **A business marked overdue by mistake** (its last day was wrong): correct the last day paid for on the Business page (Dates, with
  a reason), then move the status back to active (Subscription status, with a reason). Both are audited.
- **A reminder that should not have gone** cannot be recalled. Check the dates on the Business page; the reminder rows are kept in
  `subscription_reminders` for the record.
- **A payment recorded wrongly** is corrected by moving the last day paid for by hand (ADR-056); its receipt stays as issued.
