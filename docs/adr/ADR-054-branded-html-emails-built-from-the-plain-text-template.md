# ADR-054: Every email is branded HTML and plain text, built from one template

## Status

Accepted (owner request, 21 Oct 2026, issue #396). Builds on ADR-024 (the notification outbox and its SMTP sender), ADR-034 (the
tenant letterhead), ADR-042 (the staff password reset emails) and ADR-046 (two-step sign-in is optional by default). Does not
supersede any of them. No migration. The requirement is FR-NTF-10 in chapter 3; the email adapter is chapter 12 section 12.6.

## Context

Every BMS email (the applicant's confirmation, needs-info and rejection, the activation of a new business, the operator alert,
the staff password reset and its notices, the payment-account and write-off alerts, the two-step warning and the daily brief) was
sent as plain text only, with no logo or banner. The owner found that it does not look professional next to the emails people get
from their bank or their mobile money provider. Two wording slips had also crept in: the activation email told every new
administrator to "turn on two-step sign-in", which since ADR-046 is optional unless the business requires it, and the sign-off read
"Rincoltech" rather than "Rincol Tech".

An HTML email has its own hazards: mail apps differ (Outlook draws with a word processor engine, Gmail strips style blocks, phone
apps reflow and some invert colours in dark mode), and any value a user typed (a contact name, a business name) that reached the
markup unescaped would let an applicant put their own links and markup into a message sent from the platform's address.

## Decision

1. **Multipart, the plain text unchanged in role.** The SMTP sender sends every email as `multipart/alternative`: the template's
   plain text first, then an HTML part. The outbox contract is unchanged: a template still produces the subject and the text, the
   rows still carry the template key and its values, and the HTML part is rendered at send time from the same template and the same
   values (`OutboxTemplates.emailContent`, `EmailLayout`). A delivery without a template gets its text as escaped HTML paragraphs,
   so no email is ever plain text only.
2. **One layout.** Table-based, every style inline, at most 600 px wide, no external stylesheet, script or font, a light card that
   declares `color-scheme: light only`, the logo on a white band so a transparent logo stays readable when a mail app inverts colours.
   The subject is the title. A paragraph of the template that is only the main link placeholder becomes one big button with a label
   the template names, and the raw link is shown again below it for copy and paste; the other link values become plain anchors. The
   footer says "BMS by Rincol Tech Solutions Ltd", why the reader got this email (a sentence per template) and, when
   `BMS_SUPPORT_CONTACT` is set, how to reach support. The text part ends with the same footer after the standard signature
   delimiter (`-- ` with its trailing space).
3. **Whose logo.** The header is the Rincol Tech logo, by absolute URL on the platform host
   (`https://<BMS_PLATFORM_HOST>/brand/rincoltech-logo.png`, the PWA's own file), never attached. A row of a tenant (the emails of
   its own staff) whose logo is set shows that logo instead, by absolute URL on the tenant's public logo route versioned by the
   logo's document id (`/api/v1/branding/logo?v=<id>`, as the branding route gives it, so a replaced logo is never served from a
   stale cache), with its alt text the business's name, its width and height read from the image and fitted into 200 by 64 px, the
   button in the tenant's readable brand colour, and "Powered by BMS from Rincol Tech" in the footer. A colour that is not
   `#RRGGBB` never reaches a style: the platform's is used. The brand is read in one short transaction of the row's tenant
   (`TenantJobs.callForTenant`, `Letterheads`); any failure there (a suspended tenant, storage down, an image that cannot be read)
   falls back to the platform's look and never stops the send.
4. **Escaping and trusted links.** Every value is HTML-escaped (`&`, `<`, `>`, quotes), in text and in attributes. Only a URL in
   a link parameter (`link`, `links`, `sign_in_url`, `portal_url`) whose scheme is the platform's link scheme (`https` on staging
   and production) and whose host is the platform host or exactly a tenant host under the pattern (`PlatformHost.servesPlatform`,
   `servesTenant`) becomes an anchor or the button, cut before trailing sentence punctuation; any other URL, a business name that
   looks like a link included, is printed as escaped text. A subject is put on one line before it is set, so a line break in a
   business's name can never start a header of its own.
5. **Wording.** A prose paragraph of a template is one line (a trailing backslash joins the source lines), so both parts wrap to the
   reader's screen. The activation email says to set the password; the variant `onboarding.activation_two_step`, which also asks to
   turn on two-step sign-in, is chosen only when the new administrator is required to (a role that requires it or the tenant's policy,
   carried from `TenantAdmins.Invitation.twoStepRequired`). Every email is signed "Rincol Tech".
6. **Support contact from configuration.** `bms.notifications.support-contact` (`BMS_SUPPORT_CONTACT`) is free text shown as typed,
   empty by default (the line is then left out); no phone number or address is in the code.

## Consequences

- Better: every email carries the brand and one obvious action, the text part is still there for clients that want it, and a new
  template gets the HTML part with no extra work; `EmailLayoutTest` fails if a template has no reason line, if a main link has no
  button, or if the links of the two parts differ.
- Better: a user's input can never become markup in an email from the platform.
- Worse: the logo images load from the platform and tenant hosts, so a host behind an access gate (staging) shows the alt text
  instead, and some mail apps hide remote images until the reader allows them; the email still reads correctly without them.
- Worse: a tenant email reads the letterhead (and on a cold cache the logo bytes from storage, to size it) before it sends.
- Watch: a template added by another branch must give a reason line and, if it has a main link, a button label; a value that is a
  list of links (`links`) is linked line by line.
- Not done here: staff invitations still go through the recording notifier and show their link to the inviting administrator; when
  they are sent by email they will use this layout through the outbox.
