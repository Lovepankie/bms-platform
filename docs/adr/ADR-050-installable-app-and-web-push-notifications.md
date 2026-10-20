# ADR-050: BMS installs as an app, and tells a signed-in person on their own devices when something needs them, by Web Push

## Status

Proposed (owner request, 20 Oct 2026). Builds on ADR-009 (the PWA with vite-plugin-pwa), ADR-024 (the notification outbox),
ADR-036 (a channel of the outbox that answers sent, deferred, unknown or refused), ADR-003 (row-level security), ADR-014 (staff
sessions) and ADR-015 (maker-checker). Does not supersede any of them. The contract is in chapter 7 section 7.11.7, the tables in
chapter 6 section 6.5 (`push_subscriptions`) and the threat model in chapter 8 section 8.2b. The operations note is
`docs/runbooks/push-notifications.md`.

## Context

The staff app is a PWA (ADR-009) with a service worker, but it was not installable in any way a person would recognise: the manifest
named it "BMS Platform", had one SVG icon and started at `/`, there was no install offer, and an iPhone gave no hint that "Add to
Home Screen" exists. People who run a shop or a loan book keep the phone in their pocket; an app on the home screen that opens
without browser bars is how they use everything else.

The second gap is being told. Things happen in a business that the owner would want to know within a minute and not the next time
they open the app: a sale was voided, a debt was written off by one administrator with nobody to approve it, an approval limit was
raised so that a second person is no longer asked, stock was sent to their branch, an approval is waiting for their decision. Email
is slow and already used for links; SMS costs money and is for members (ADR-036). A browser push notification is free, arrives on the
lock screen and opens the right page. It is also the one channel where a mistake is visible to a bystander: a notification lies on a
locked phone, so what it says must be chosen with that in mind.

Five things had to be decided: how the app becomes installable and how the offer is made, how push is built (a library or the
protocol), who may be pushed to and how a device belongs to a person, which events notify whom, and what a notification may say.

## Decision

1. **Installable.** The manifest says name and short name `BMS`, a fixed `id` and start page `/staff`, standalone display, a theme
   colour, and icons of 192 and 512 pixels and a maskable pair, all PNG (`frontend/public/icons/`, made from the existing icon);
   `index.html` adds the iOS touch icon and the web-app meta tags. The generated service worker loads `public/push-sw.js` with
   `importScripts`, so installing and pushing share one worker and one update.
2. **The offer.** `frontend/src/install/` captures `beforeinstallprompt` when the module loads (it fires once and early) and replays
   it on a tap. The offer (`InstallPrompt`) shows after sign-in under the staff bar, on the sign-in page, on the invitation page where
   the first password is chosen (and after it), and on the forgot and reset password pages. It shows only when this device can
   install: the browser gave a prompt (Android, desktop Chrome and Edge), or it is an iPhone or iPad, where there is no prompt and the
   person is told the three taps (Share, Add to Home Screen, Add). It never shows to someone already running the installed app.
   "Not now" is remembered on this device in `localStorage` for 30 days (per device, not per account, since the question is about the
   device). The staff area also has a permanent control, Notifications and app (`/staff/notifications`), with the install button or
   the steps, whatever was dismissed.
3. **Web Push, written on the JDK.** The message encryption (RFC 8291 with the `aes128gcm` coding of RFC 8188, one record) and the
   VAPID proof (RFC 8292, an ES256 token over the push service's origin, valid 12 hours) are about 250 lines in `WebPushCrypto` and
   use only the JDK's EC, ECDH, HMAC and AES-GCM. A library was considered and not taken: the maintained Java ones carry an older
   BouncyCastle and HTTP stack than this build pins, add a dependency to a module that otherwise has none, and the protocol is small
   and has published test vectors, which the unit test uses. The sender is a port (`PushSender`): the real one posts with the JDK's
   HTTP client (redirects never followed, ten second budget); tests use a recording fake, so no test touches a network.
4. **VAPID keys come from the environment**: `BMS_PUSH_VAPID_PUBLIC_KEY`, `BMS_PUSH_VAPID_PRIVATE_KEY` (both unpadded base64url, the
   65 byte point and the 32 byte scalar, made once per environment with `npx web-push generate-vapid-keys`) and `BMS_PUSH_SUBJECT`
   (a `mailto:` or `https:` contact for the push services). They are checked as a pair at start (a mismatch or half-set pair stops
   the application) and never logged. With none set push is off: no channel, no queued row, the settings switch says it is not
   switched on. The dev profile generates an ephemeral pair at every start (`bms.push.ephemeral-keys`, refused when
   `BMS_ENVIRONMENT` is staging or production), and the integration tests do the same with a fake sender. Changing the pair later
   strands every existing subscription (the browser bound it to the old key); the app notices on the next staff page load and
   subscribes again without a new permission question.
5. **A subscription belongs to one user on one device.** Table `push_subscriptions` (migration V68): tenant, user, the endpoint and its SHA-256, the subscription's public key and secret, a short user agent,
   created and last used. Tenant-owned, forced row-level security with the standard policy, composite foreign key to the user. The
   endpoint is the device, so it is unique within the tenant: when a second person turns notifications on from a device the first
   person used, the row moves to them, and the first person stops hearing. A person keeps at most ten devices, the least recently
   used going first. Sign-out removes this device's row on the server (the browser keeps its subscription), and the staff area
   registers the device again, quietly, under whoever is signed in next, but only if that browser already holds a subscription and permission (so a shared device is registered for the next person only after someone on it turned notifications on; a person who never did is never registered without their own tap); turning the switch off removes both.
6. **Routes are the caller's own.** `GET /notifications/push/config`, `GET` and `POST /notifications/push/subscriptions`,
   `POST /notifications/push/subscriptions/unsubscribe`, `DELETE /notifications/push/subscriptions/{device_id}`: all
   `authenticated` for a staff principal with no permission, because the user is the principal and never a parameter. The API
   never returns an endpoint or a key; a device is shown by a label ("Chrome on Android"), when it was added and when it was last
   used. Subscribing and unsubscribing are audited (`core.push.subscribed`, `core.push.unsubscribed`) without the address.
7. **Where a message may be posted is a list.** The endpoint is typed by a browser but sent by whoever calls the API, so it is a
   server-side request forgery risk. It must be `https`, port 443, no credentials, a name and not an address, and a host that is one
   of the known push services or a subdomain of one (Chrome and Edge `fcm.googleapis.com`, Firefox `push.services.mozilla.com`,
   Safari and iOS `push.apple.com`, Windows `notify.windows.com`), or one named in `BMS_PUSH_EXTRA_ENDPOINT_HOSTS`. It is checked
   when a device subscribes and again before every send. The subscription's point is checked to be on the P-256 curve.
8. **A channel of the outbox, with its rules.** `push` is a new channel value (the CHECK widened in V68). A row is addressed
   `user:<id>`, carries its tenant, expires in 12 hours, and is sent by its own 10 second sender (the 30 second general sender would
   also pick it up). The channel sends to every device of the person in a short tenant transaction of its own, outside any
   transaction for the network call: sent when at least one device took it; a device the push service says is gone (404, 410) is
   deleted; a device it rejects for another reason is kept, so a key mismatch can never wipe the devices; every device answering
   with a temporary problem is a clean failure and the outbox retries at its own backoff (1 then 5 minutes) and marks the row
   failed on the third attempt; no device left is refused, never retried. The push service is told a time to live no longer than the
   row's expiry and never more than a day.
9. **Modules publish a notice, they do not call a channel.** `PushNotice` (in the kernel) is an application event published in the
   transaction of the cause; `core.notifications` handles it synchronously inside a savepoint, so a failure of any kind is skipped
   and the business event goes on, and a rolled-back event notifies nobody. An event is an outbox row per person who has a device,
   with the key `push:<template>:<event>:<user>`, so a retried cause never notifies twice. The event, not a call, also keeps the tenancy
   and approvals modules (which `core.notifications` depends on) free of a dependency cycle.
10. **Which events notify whom** (first set):
    - a sale voided: the active holders of `core.settings.manage` (the administrators), except the person who voided it;
    - a debt written off alone: the administrators who are told by email today, with the same people and the same key reference
      (the push rendering reuses the template key `retail.write_off_alone`);
    - an approval limit raised, or two-step sign-in no longer required of every user (the safeguards of FR-IAM-06 and ADR-015 loosened):
      the administrators, except the person who changed it;
    - a stock transfer sent to a branch with the transfer receipt capability: the holders of `retail.stock.transfer` at the
      destination branch, except the sender;
    - an approval waiting: the holders of the action's checker permission at the request's branch, except the maker.
11. **A notification says what happened and where to look, nothing more.** The title and body come from a push template per
    key, never carry a name, a phone number, an identity number or an amount (a test fails a template that asks for one), are cut to 80
    and 200 characters, and a tap opens an app route: one leading slash, no scheme, no second slash, else the staff home. The service
    worker enforces the same rule on the page it opens, and always shows a notification for a push it cannot read, because a browser
    requires it.
12. **Asked only on a tap.** The settings switch (Notifications on this device) asks the browser for permission only when it is
    tapped, never when a page loads. A refusal is a state shown in words ("Blocked in the browser settings for this site"), not an
    error. On an iPhone the switch explains that notifications need the app on the home screen first, which is how iOS works.
13. **Tours.** The Notifications and app page has a page tour (the install control, the switch, the device list), and the welcome tours
    of administrators, sellers and other staff have one step pointing at the Notifications tab.

## Consequences

**Better:**

- A phone shows BMS as an app, and a person hears within a minute of the five things that matter, with a tap that opens the right
  page. Nothing here costs per message.
- A push never touches a business transaction: it is an event handled in a savepoint, a row in the existing outbox with its retry,
  expiry and failed list, and a sender that owns no database connection while it waits on the network.
- The lock-screen text cannot leak a name or an amount by construction, and a stolen subscription address is useless without the
  server's VAPID key, while a forged one cannot make the server call an arbitrary host.
- One device, one person: a shared phone does not keep telling the last user's news to the next.

**Worse:**

- We own about 250 lines of cryptography. They follow two RFCs and are tested against the RFC's worked example and a round trip,
  but a protocol change at a push service would be ours to follow.
- Safari on iOS delivers push only to a web app that is on the home screen (iOS 16.4 and later), so on an iPhone the switch cannot be
  used from the browser tab, and there is no way for the server to know.
- Delivery is the push service's promise, not ours: a phone that is off or has the app's notifications muted hears nothing, and
  the outbox row reads sent because the push service accepted it.
- The allowed push hosts are a list that has to be extended (`BMS_PUSH_EXTRA_ENDPOINT_HOSTS`) when a vendor adds one.

**Watch for:**

- Rotating the VAPID pair: every device resubscribes on its next staff page load, and a device nobody opens stays silent until
  then. Do it outside working hours and tell the owners (runbook).
- A new notice must pass the lock-screen rule: no name, number or amount in its template; the test `everyPushTemplateRendersFromItsPlaceholdersAndFitsALockScreen`
  fails otherwise.
- Who is notified is read from roles at publish time. A person who holds the permission through a role with no branch holds it
  everywhere; a branch-scoped holder is matched by the event's branch.
- Deactivating a user deletes their devices in the same transaction, and a deactivated user is never selected as a recipient.
  The one notice that reaches a closed branch is `retail.transfer_dispatched`, because a closed branch still receives what is on
  its way; every other branch-scoped notice needs an active branch.
- **Release note:** the manifest `id` moved from `/` to `/staff`. A browser treats a changed `id` as a different app, so a phone
  that installed BMS earlier may show a second BMS icon after this release. Remove the old one; nothing is lost, since the account
  lives on the server.
- The service worker file is loaded by `importScripts`, which a browser may serve from its HTTP cache: the web container serves
  `/push-sw.js` with `Cache-Control: no-cache`, as it does `/sw.js`.
