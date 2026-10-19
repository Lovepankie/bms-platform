# Runbook: connect a lender's Pesapal account

Slice A7 (issue #233, ADR-035 amendment of 2026-10-18, spec `docs/specs/client-extras.md` section 3.11). Money owed to a lender
(loan repayments, savings deposits) is paid into **the lender's own** Pesapal merchant account, never into the platform's. This
runbook is for the lender's administrator and for the operator helping them. The operator never needs, sees or holds the lender's
consumer key or secret, and nobody pastes either into a chat, an issue, an email or the repository.

## What the lender needs first

* An active **Pesapal merchant account in the lender's own name** (Pesapal's business sign-up, with the lender's own registration
  documents and settlement bank or mobile money account). The money Pesapal collects is paid out to the lender by Pesapal, not by us.
* The **consumer key** and **consumer secret** of that account (in the Pesapal merchant dashboard, under the API keys). The sandbox
  and the live account have different pairs: a staging system only ever accepts sandbox ones.
* The **business administrator's** sign-in (the screen needs the permission `core.payment_account.manage`, held by the tenant admin only, not by managers) and the authenticator app the administrator enrolled for two-step sign-in: saving and removing the account ask for a current 6-digit code. Another administrator is emailed whenever the account changes.

## Steps for the lender's administrator

1. Open **Receive payments** in the staff menu. It says Not connected.
2. Paste the **Consumer key** and the **Consumer secret** exactly as Pesapal shows them, with no spaces, type the **code from your authenticator app**, and press **Save the key and
   secret**. A key that is the platform's, or that another business is already connected with, is refused ("already connected elsewhere"): use your own Pesapal account. Nothing is sent to Pesapal yet. The page then shows the last four characters of the key; the secret is never shown again.
3. Press **Test connection**. We ask Pesapal for access with the key and secret and tell it the web address on which to report
   payments for this account. This is what turns the status to **Connected and working**; it takes up to about 20 seconds.
4. If the status says **Not working**, the reason is in plain words: Pesapal did not accept the key and secret (paste them again,
   check you copied the live or sandbox pair that matches this system), or it accepted them but refused the notification address (test
   again in a few minutes). Other businesses are not affected.
5. If it says Pesapal could not be reached, nothing was changed; try again shortly.
6. Switch collections on in Settings, payments (needs the daily ceiling and the `gateway` payment method mapped to a ledger account).

To change the secret (Pesapal asked you to rotate it), save the same consumer key with the new secret and test again; this is allowed
while payments are being confirmed. To use a **different** Pesapal account, or to remove the account (the code is asked again), wait until no payment is
still being checked (409 `payments_in_flight` otherwise). Testing is allowed once every half minute.

## Steps for the operator

1. Before the lender starts: the host must have a payment provider and the callback origin set (`BMS_PESAPAL_CALLBACK_BASE_URL`; on
   staging the sandbox only). Otherwise Test connection answers `gateway_not_configured` and the screen says to ask the operator.
2. The platform console shows the lender's status and the time of the last test (`GET /api/v1/platform/tenants/{tenant_id}/payment-account`):
   `not_connected`, `verified` or `failed`, and for a failure a closed reason (`invalid_credentials`, `registration_refused`). That is all
   the operator can see.
3. If the lender is stuck on `failed` with `registration_refused`, check the callback host is reachable from the internet and the
   proxy forwards `/api/v1/payments/callbacks/*` (runbook `payments.md` section 3), then ask the lender to test again.
4. If a lender's merchant account is compromised and orders are still being checked, the operator can release the saved account
   (`POST /api/v1/platform/tenants/{tenant_id}/payment-account/release`, audited); the waiting orders are skipped, not closed, until the lender connects a new account.
5. Never switch collections on for a lender on its behalf, and never put the platform's pair in a lender's place. A lender with no
   verified account cannot start a payment, by design.

## Where the credentials are and who can read them

Table `payment_gateway_accounts`: the key and secret are sealed with the application data key; the notification token's SHA-256 is
all that is stored of it. The API returns only the status, the time, a closed failure word and the key's last four characters. Rotating the
data key is `payments.md` section 4a. Losing `BMS_DATA_KEY` makes the stored credentials unreadable; the lenders enter them again.

## Before the first live repayment

KTA's confirmation of the own-account model (National Payment Systems Act 2020) is still to be asked. Do not switch collections on for a
lender against its **live** Pesapal account until it is on file; the sandbox is fine.
