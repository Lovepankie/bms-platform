# ADR-065: Feedback from inside the app, stored per business and answered by the platform operator

## Status

Accepted (issue #447, FR-FBK-01 to FR-FBK-05). Builds on ADR-003 (row-level security), ADR-016 (the platform reads and changes
tenants through SECURITY DEFINER functions), ADR-024 (the platform outbox and its operator alert), ADR-049 (every route has a page
tour) and ADR-053 (an image a module shows is a `core.documents` upload served from the same origin). Migration `V80`, the next
number after `V79` on main (the open pull requests that had claimed `V80` to `V85` take the numbers after it). ADR-063 is on main and the number before this one,
pending ADR-064, is claimed by an open pull request.

## Context

The platform owner wants ideas and problems straight from the owners and the staff of each business, without a WhatsApp thread,
and wants to tell them what became of each. Today a person who hits a problem has no way to say so from where they are, and the
operator learns of it late, without the page, the version or the device that would let us reproduce it. The answer must not open
a way for one business to read another's words, must not let the sender's request fail because a mail server is down, and must
not become a way to flood the operator.

## Decision

1. **A new core module, `core.feedback`, and one tenant table.** `feedback_items` (V80) holds the kind (`idea`, `problem`,
   `question`), the message (1 to 2000 characters), the page path the sender came from (path only: the PWA remembers the last staff
   page and the server drops any query or fragment, which can carry a search or a token), the running version (version and git
   SHA, taken by the server from its own build, as `/version` reports it, so a client cannot misreport it), the browser's user agent
   (cut to 400 characters), an optional screenshot, the status and the operator's reply. Forced row-level security like every
   tenant table; the application role has `SELECT` and `INSERT` only, so nothing inside a business can change a status or a reply.
2. **Any member of staff can send, and sees only their own.** The staff routes are `@AuthenticatedEndpoint(kind = "staff")`, the
   same declaration as `/me`: feedback is not a business capability to grant, so no matrix permission is added. `GET /feedback`
   lists the caller's own items (filtered on the sender; RLS keeps every other business out); an administrator does not read
   colleagues' feedback, which keeps a complaint about a colleague or the business private to the person who sent it and the operator.
3. **The screenshot is a core document.** PNG or JPEG by content, at most 2 MB (checked in plain words before the documents
   module sees it), re-encoded without metadata through `Documents.prepareImage` before the transaction opens, stored under the
   tenant's prefix with subject type `core.feedback`, and served from the same origin. Its `DocumentAccess` lets the sender and a
   platform operator who may read tenants read it, nobody else, and hides a refusal as a 404.
4. **The operator reads across businesses only through definer functions.** `platform_feedback_list` (newest first, optionally
   one status or one item, with the business and the sender's name and nothing else of the sender), `platform_feedback_tenant` and
   `platform_feedback_set_status` each refuse a transaction bound to a tenant, so a tenant request can never call them. The
   operator routes live in `core.platform` (`/api/v1/platform/feedback`, `platform.tenants.read` to list and read a screenshot,
   `platform.tenants.manage` to change a status) and use the public port `FeedbackInbox`; the screenshot is read with the item's
   business bound through `TenantContext.callAs`, the console's one way to act inside a tenant, so the documents module still
   applies that tenant's RLS.
5. **Status moves forward only: new, seen, done.** The function refuses a move back (422 `feedback_status_backwards`); the same
   status again keeps it and may change the reply (null keeps it, empty clears it). Every change writes a `platform_audit_log`
   row with the operator, the business, before and after. The sender sees the status and the reply on their own Feedback page.
6. **The operator is emailed through the outbox, never in the way of the send.** Each new item queues one `feedback.new` email per
   active platform operator, the same addresses the onboarding operator alert uses (`platform_users`, there is no other operator
   address setting), in a savepoint inside the send's transaction: a failure there is logged without the message and the feedback
   is kept, and the outbox delivers later, so a mail server that is down never delays or refuses a send.
7. **Ten items per person per hour.** Counted in the database under a per-person advisory lock, so two sends at once cannot both
   take the tenth place; the eleventh answers 429 `feedback_rate_limited` in plain words with `Retry-After` until the oldest of the
   ten leaves the hour.
8. **Where it is in the app.** Feedback is a tab of the staff menu for everyone and an entry of the Help sheet (the staff menu has
   no More menu; on a phone the tabs scroll and Help is always at the top). The operator portal has a Feedback tab. Each page has its
   tour (`page-feedback`, `page-operator-feedback`), and the three welcome tours have a Feedback step.

## Consequences

**Better:** a problem arrives with the page, the version and the browser, so it can be reproduced; the sender is told what became
of it; no business can read another's feedback, by RLS and by functions that refuse a tenant-bound transaction; the send never
depends on email.

**Worse:** the operator email carries the sender's own words and name to every operator mailbox; the operator list reads up to
200 items a page across every business through a definer function, outside RLS by design; staff can attach a picture that shows
customer data on their screen, which the operator then holds.

**Watch for:** feedback that should be a support call (an outage, a lost sale) still needs the support line in
`docs/runbooks/support-and-on-call.md`; if the operator team grows, a per-operator assignment or a dedicated feedback address may
be wanted, which would be a new setting; if volume grows, the list needs a cursor instead of an offset; a retention period for
feedback and its screenshots is not decided yet.
