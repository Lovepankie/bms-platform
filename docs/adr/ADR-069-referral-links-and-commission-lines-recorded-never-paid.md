# ADR-069: Referral links, and commission lines the platform records but never pays

## Status

Accepted (issue #464, FR-REF-01 to FR-REF-08). Builds on ADR-003 (row-level security), ADR-016 (the platform reads and changes
tenants through SECURITY DEFINER functions), ADR-024 (self-onboarding; its decision 5 on commission rules, which this record
narrows to referrals) and ADR-056 (manual subscription payments; its decision 8 deferred commission lines, which this record
builds). Migration `V89`: `V81` to `V88` are claimed by open pull requests (lending `V81` to `V84`, payments `V85` and `V86`,
offline sales `V87`, shop logos `V88`), and the file is renamed at merge if they land after it. The two numbers before this
record are claimed by open pull requests: pending ADR-067 (offline sales) and pending ADR-068 (shop logos).

## Context

The platform owner (22 Oct 2026) wants businesses that bring other businesses to Rincol Tech BMS to earn a commission for each
customer they refer, and the platform must know who referred whom so the commission can be worked out and paid. The amounts and
the rates are commercial terms that change by deal and over time: ADR-024 decision 3 keeps every price out of the repository, and
the same must hold for commissions. Money for commissions moves outside the app (mobile money or a bank transfer by the BMS team),
exactly as subscription payments are received outside it today (ADR-056). A referrer must never see another business's private
data, and a business must not be able to refer itself.

## Decision

1. **Every business has one referral code and a link.** A trigger on `tenants` gives each new business a code when it is created
   and `V89` backfills every business that exists: up to five letters of the slug and two characters from an alphabet without 0,
   1, I and O (for example `DODOS7K`), four characters after ten taken tries, eight random characters after twenty or for a slug
   with fewer than two letters. Codes are unique, stored in upper case, matched without case, and never change
   (`referral_codes`, append-only). The link is the platform sign-up with the code: `{platform origin}/sign-up?ref=CODE`.
2. **The sign-up takes the code; Activate makes the referral.** The sign-up page reads `?ref=` into an optional "Referred by"
   field the applicant can change or clear. The server resolves the code before anything is created: a code no business has is
   422 `referral_code_unknown` on the `referral_code` field, and nothing is stored. This says nothing about the email, so the
   same-answer rule of FR-ONB-03 still holds. The code and its business are kept on the application; Activate turns them into the
   new business's referral (`tenant_referrals`, source `sign_up`) in the activation's transaction, so the referral survives the
   application being closed and purged. A repeated Activate makes no second referral.
3. **One referrer per business, never itself, and a correction never rewrites history.** A partial unique index allows one
   current referral per referred business, and a CHECK refuses a row where a business refers itself (the service refuses it
   first with 422 `referral_self`). The platform operator sets, corrects or clears the referrer of a business with a reason
   (`PUT /platform/tenants/{id}/referrer`, audited `platform.referral.referrer_changed` with before and after); a correction ends
   the current referral and starts a new one, so commission lines already worked out stay with the referrer who earned them. The
   same referrer again changes nothing.
4. **The commission rule is configuration the operator types; nothing is built in.** A rule is one of: `percent_of_payment`
   (basis points of each subscription payment, for the first N paid months of the referred business, N empty for no end, with an
   optional cap on the referral's total), `fixed_first_payment` (a fixed amount once, on the referred business's first payment)
   and `fixed_per_month` (a fixed amount per paid month for the first N paid months). The operator sets a platform default rule
   and may override it on any one referral at any time, each with a reason, audited. Rules are append-only: a change is a new rule
   and the pointer moves (`referral_settings.default_rule_id`, `tenant_referrals.rule_id`). The default starts empty: until the
   operator sets one, nothing is worked out. No rate or amount appears in code, migrations, fixtures or docs.
5. **A payment the operator records adds the commission line from the rule in force at that moment.** In the same transaction as
   `POST /platform/tenants/{id}/payments` (and behind its Idempotency-Key, so a repeat adds nothing), the definer function
   `platform_referral_commission_for_payment` finds the paying business's current referral and the rule in force (its override,
   else the default), counts the paid months already used (decision 9), and adds one
   line (referral, payment, amount, the months counted, and a snapshot of the rule and the payment) when the rule gives an amount.
   A percent is worked out in whole minor units rounded half up (amounts are positive, so 10% of 333 is 33 and of 335 is 34;
   rounding down would short the referrer on every payment), pro rata on the months still inside N; a rule with an amount
   applies only to payments in its currency. A change of rule applies to payments recorded from then on; lines already worked out
   never change. The outcome (`added`, `no_referral`, `no_rule`, `not_first_payment`, `months_used`, `currency_differs`,
   `cap_reached`, `zero`) is never an error: the payment is recorded whatever it says.
6. **Lines are recorded and marked paid, never paid by the app and never deleted.** The operator can add a line with an amount
   worked out by hand (kind `manual`, with a reason, behind the Idempotency-Key protocol: the key row is the referrer's, as a
   payment's is the paying business's, so a double submit adds one line), reverse a line once with a reason (kind `reversal`, the exact opposite
   amount; a reversal is not reversed), and mark a line paid once (date, method `mtn_momo`, `airtel_money`, `bank_transfer`,
   `cash` or `other`, reference, note, reason). A trigger refuses any delete and any update other than setting the paid columns
   once. Every write is audited in `platform_audit_log` with the operator and the reason. These line writes take a reason and
   no authenticator code, as recording a subscription payment does: they record money moved elsewhere. Setting or correcting
   the referrer, the default rule and a referral's rule decide who is owed money on every later payment, so those three
   routes also take a current code from the operator's own authenticator (`totp_code`, the step-up of ADR-042, used once and
   checked before the change).
7. **Who sees what.** Referrals and lines are rows of the **referrer** (`tenant_id` is the referrer) under forced row-level
   security, with SELECT only for the application role. The owner and the administrators of a business (`core.referrals.read`,
   tenant admin only, so the Owner of ADR-062 holds it) see `GET /referrals`: their code, their link, and for each business they
   referred its name, when it joined, its status (`applied`, `active`, `paying`, or `ended` for a referral moved to another
   referrer that carries lines) and the commission earned, paid and due per currency; nothing else of the referred business. An
   application counts as `applied` only once its email is confirmed. The operator lists referrals across the platform with
   filters (referrer, status, commission due or paid) and exports them as CSV (at most 10,000 rows, audited), through definer
   functions that refuse a tenant-bound transaction. The PWA shows the page as Referrals under Business set-up
   (`/staff/setup/referrals`), with Copy and Share on WhatsApp buttons and its page tour.
8. **A new core module.** `core.referrals` (allowed dependencies `kernel`, `core.tenancy`, `core.audit`, `core.operations` for the
   Idempotency-Key) owns the tables and the
   public port `Referrals`; `core.onboarding` uses it at the sign-up and Activate, `core.platform` at the payment and for the
   operator routes, as the feedback inbox does (ADR-065).

The review of the pull request (NEEDS_CHANGES, 22 Oct 2026) added the decisions below; the platform owner chose the defaults.

9. **The months used count only payments made under this referral, in the rule's currency, one payment at a time.** The first
    N paid months of a rule count every other confirmed payment of the referred business recorded on or after the day this
    referral started (`tenant_referrals.started_at`); payments recorded before the referral existed, under an earlier referrer
    or none, do not use N up, and neither does a payment that earned nothing because its currency differed from the rule's
    (outcome `currency_differs`, kept per payment in `referral_payment_outcomes`, so a later rule in that currency still starts
    at its first month). The
    count does not compare creation times: recording a payment locks the business's subscription row first (`V75`), so two
    payments recorded at the same moment are counted one after the other and never both as the first. The cap stays per
    referral row.
10. **A business is not referred by its own people.** At the sign-up, after the code resolves, the applicant's email (compared as
    the sign-up compares addresses, `onboarding_email_key`) and phone (E.164) are compared with the Owner and the administrators
    (not deactivated) of the business the code belongs to (`referral_is_self`); a match is 422 `referral_self` on the
    `referral_code` field and nothing is created. Activate compares them again, since the referrer's people may have changed:
    on a match no referral is stored and the refusal is audited `platform.referral.refused_self` with the referrer.
11. **A reversed line is not paid.** An unpaid line that was reversed nets to nothing with its reversal, so marking it paid is
    refused with 409 `commission_line_reversed`; a line paid before it was reversed stays paid, and the reversal records what
    the referrer owes back.

## Consequences

**Better:** every business can bring others with one link; the platform knows who referred whom from the first form; commission
terms change by deal and over time without a deploy, and each line keeps the rule it used; the record of what was earned and
paid is auditable end to end and cannot be edited away; a referrer sees only its own referrals.

**Worse:** the referrer can infer a referred business's subscription amount from a percent commission line; the operator list and
the CSV read across every business through definer functions, outside RLS by design; codes are not secret, so anyone can type a valid code at the sign-up
(the operator still verifies every application before Activate).

**Watch for:** the operator portal has no screen for referrals yet (the API is complete); when it comes, it has a page tour like
every operator page. Gaming by self-dealing through a second business run by someone who is not the Owner or an administrator of the referrer is caught only by the operator's review
of the application. If agents (ADR-024 decision 5) arrive, their commission lines can reuse the rule kinds and the line table with
an agent in place of a referring business.
