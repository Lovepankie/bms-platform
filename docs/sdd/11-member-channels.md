# 11. Member Channels

**Status:** Draft · **Owner:** Hillary

## 11.1 Purpose

How a tenant's members reach the platform: the member area of the PWA, SMS, USSD
(phase 2) and mobile money payments. Staff use the staff area only; field officers use it
on Android phones, which is covered by the same PWA rules (ADR-009, chapter 4 section
4.8).

| Channel | Phase | Reach | Member can |
|---|---|---|---|
| PWA member area | P2 (after the staff MVP) | Smartphone with data | See balances, schedules, statements; apply; invest; pay |
| SMS (outbound) | MVP | Every phone | Receive receipts, reminders, arrears notices, one-time codes |
| USSD | Later (phase 2 of channels) | Every phone, no data | Check balance and next due amount, mini statement, pay |
| Mobile money | P2 | Every MTN or Airtel wallet | Pay loan instalments, deposit, fund investments |

## 11.2 PWA member area

### 11.2.1 Access

- The member opens `https://<tenant slug>.<base domain>/` and chooses "Member". The
  tenant's branding (display name, logo) comes from tenant settings.
- First use: portal activation (FR-IAM-09): phone number, SMS one-time code, set a 5
  digit PIN. Staff trigger an invitation SMS with a link from the member record, or the
  member starts activation directly; either way the phone must belong to a member record
  of that tenant.
- Later: phone plus PIN. Refresh sessions last up to 30 days idle, so a member signs in
  rarely.
- Installation: the browser offers "Add to Home screen"; the app shows an install hint on
  Android after the second visit.

### 11.2.2 Screens

| Screen | Content | Requirement |
|---|---|---|
| Home | Next payment due (amount, date), total loan balance, savings balance, active investments, any arrears in red, "Pay now" | FR-MSS-01, FR-MSS-02 |
| Loans | List of loans; each opens to balance, DPD, schedule with paid and outstanding per instalment, transactions, download statement | FR-MSS-01 |
| Apply | Choose product, amount, term, purpose; shows an indicative schedule from the product preview; submits a draft to the officer | FR-MSS-03 |
| Savings | Accounts, balances, transactions | FR-MSS-02 |
| Invest | Products with terms and rates, application, active investments with maturity and return, maturity instruction | FR-MSS-03, FR-INV-05 |
| Pay | Choose what to pay (loan, savings, investment), amount (defaults to the next due amount), wallet phone; shows progress and the outcome | FR-MSS-04 |
| Documents | Receipts and statements | FR-MSS-05 |
| Profile | Name, phone, member number, next of kin (read only), change PIN, sign out | |

### 11.2.3 Offline behaviour

Chapter 4 section 4.8 is normative. In short: the shell loads offline; Home, Loans,
Savings and Investments show the last synced data with a "last updated" time; Apply
drafts are kept locally; Pay and any other money action are disabled offline with a
message.

### 11.2.4 Performance

The member area is its own lazily loaded route tree, under 250 KB of compressed
JavaScript at first load (NFR-PERF-06). Lists page at 20 items. Images are avoided except
the tenant logo.

## 11.3 SMS

### 11.3.1 Events and default templates

Templates are per tenant and editable (FR-NTF-03). Defaults, all fabricated wording, kept
within one 160 character GSM-7 segment where possible:

| Event key | When | Default text |
|---|---|---|
| `loan.disbursed` | Disbursement executed | `{tenant_name}: Loan {loan_no} of {currency} {amount} disbursed on {date}. First payment {next_amount} due {next_due_date}.` |
| `loan.repayment_received` | Repayment recorded | `{tenant_name}: Received {currency} {amount} for loan {loan_no}. Receipt {receipt_no}. Balance {balance}.` |
| `loan.due_in_3_days` | 3 days before a due date, inside the sending window | `{tenant_name}: Reminder, {currency} {amount} for loan {loan_no} is due on {due_date}.` |
| `loan.due_today` | On the due date | `{tenant_name}: {currency} {amount} for loan {loan_no} is due today.` |
| `loan.overdue_1`, `loan.overdue_7`, `loan.overdue_30` | At 1, 7 and 30 DPD | `{tenant_name}: Loan {loan_no} is {dpd} days overdue. Amount in arrears {currency} {arrears}. Please pay to avoid penalties.` |
| `loan.closed` | Loan closed | `{tenant_name}: Loan {loan_no} is fully paid. Thank you.` |
| `savings.deposit`, `savings.withdrawal` | Transaction recorded | `{tenant_name}: {txn_type} of {currency} {amount} on account {account_no}. Balance {balance}.` |
| `investment.funded`, `investment.maturing`, `investment.paid_out` | Lifecycle | Similar pattern |
| `auth.member_otp` | Activation or PIN reset | `{code} is your {tenant_name} code. It expires in 10 minutes. Do not share it.` |

### 11.3.2 Rules

- Reminders and arrears messages only inside the tenant's sending window (FR-NTF-04);
  receipts and codes immediately.
- One message per event per schedule item (FR-NTF-06).
- Messages go to the member's primary phone; never to next of kin in the MVP (contacting
  next of kin about a debt raises privacy questions the tenant must decide; listed as an
  open question).
- Sender ID: the tenant's approved sender name if registered with the aggregator, else the
  aggregator's shared sender.
- Cost: segments are counted per tenant (FR-NTF-07).

## 11.4 USSD (later)

Depends on the aggregator decision (pending ADR-013) and a USSD code assignment. Design
intent, so the API and data model do not block it:

```
*<code>#
1. Loan balance        -> balance, next due amount and date
2. Mini statement      -> last 3 transactions
3. Pay loan            -> amount (default next due) -> confirm -> mobile money prompt
4. Savings balance
0. Exit
```

- The member is identified by the calling number (the aggregator passes it) and confirms
  with their PIN before any balance is shown.
- Tenant identification: one USSD code per tenant, or a shared code with a tenant choice
  menu; decided with the aggregator.
- Sessions are stateless on the server apart from a short-lived session record keyed by
  the aggregator's session id (expires after 3 minutes; a small PostgreSQL table purged by a
  job, ADR-008).
- USSD responses must return within the aggregator's time limit (typically a few seconds);
  the payment step only creates the payment intent and returns.

## 11.5 Payments from the member's side

1. The member chooses what to pay and the amount, and confirms the wallet phone (default:
   their own).
2. The platform creates a payment intent (FR-PAY-01) and asks the gateway to prompt the
   phone.
3. The member approves the prompt on their phone with their mobile money PIN (the platform
   never sees it).
4. The app polls the intent every 3 seconds for up to 2 minutes and shows `succeeded`,
   `failed` or "still waiting, we will SMS you".
5. On success the repayment or deposit is booked (FR-PAY-03) and the receipt SMS is sent.

Gateway specifics are in chapter 12; the choice of gateway is pending ADR-011.

## 11.6 Staff in the field

Loan officers register members, capture applications and log collection visits from a
phone. The staff area is responsive to 360 px, forms save drafts offline
(NFR-OFF-03), and the document capture step uses the phone camera for ID photos.
Money-moving actions stay online-only (NFR-OFF-04).
