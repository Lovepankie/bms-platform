# ADR-068: Each shop can have its own logo

## Status: Accepted

Accepted (owner request of 22 Oct 2026, issue #461, FR-BR-09). Builds on the tenant branding of FR-TEN-08 (the logo as a core
document, its upload rules and its public route) and on ADR-034 (generated documents and their letterhead). ADR-066 stands unchanged:
the installed app, the browser tab and the manifest keep the business logo. Numbered ADR-068 because pending ADR-067 is claimed by the open
offline sales pull request; renumbered at merge if needed.

## Context

A business has one logo today. It is the installed app's icon (ADR-066), the browser tab icon, the sign-in page's picture and the
letterhead of every receipt, invoice, delivery note, statement and loan document. Some businesses run several shops under one name, each
with its own look (its own sign, bags and social media page), and want each shop's receipts and screens to carry that shop's logo
while the business itself stays one business with one installed app.

## Decision

1. **An optional logo per branch.** `branches.logo_document_id` (migration `V85`), null by default, points at a document of the
   tenant (the same `core.tenant` subject as the business logo, with the branch id on the document row). A branch without one shows the
   business logo everywhere, so nothing changes for a business that never sets one.
2. **The rules and processing of the business logo.** `PUT /api/v1/branches/{branch_id}/logo` (multipart `file`) takes PNG, JPEG or
   WebP up to 1 MB, 128 px or more on the short side and at most 4 megapixels decoded, and re-encodes and scales it to 512 px exactly as
   `PUT /settings/logo` does (one image policy, `BrandingService.LOGO`, for both). `DELETE` clears it and keeps the file (documents are
   never deleted). Both bump the branch `version` and are audited as `core.branch.logo_changed` with was and now.
3. **Who sets it.** The owner and the administrators: permission `core.branches.manage`, in the caller's branch scope (a branch out of
   scope is a 404, and a closed branch a 409 `invalid_status_transition` for an upload or a removal, before anything is stored). There
   is no new permission.
4. **Served like the business logo.** `GET /api/v1/branches/{branch_id}/logo?v=<document id>` is public and answers exactly as
   `/api/v1/branding/logo` does: the stored re-encoded type only (PNG or JPEG), the checksum as the ETag and a 304 on a match,
   `nosniff`, a sandboxing CSP, one hour of public caching with `Vary: Host`. It is public for the reason the business logo is: an image
   tag in the app shell sends no token. The business is the one the host resolves; a branch of another business is hidden by row-level
   security and is a 404, as is a branch without a logo. The branch response and each branch of `GET /me` carry `logo_url` (the versioned
   path, or null), so a new logo is a new URL.
5. **Where it shows.** A document made at one shop (the receipt to share, an invoice, a delivery note, a quotation, a credit note, a loan
   document of that branch) carries that shop's logo on its letterhead, else the business logo (`Letterheads.forBranch` and
   `forBranchCode`; an issued retail document keeps the branch code in its snapshot, and the code never changes). The logo is read when the
   document is rendered, as the business logo already is; a shop logo whose file cannot be read falls back to the business logo. A
   customer statement spans branches and keeps the business logo. In the staff app the shop's logo shows beside the branch picker in the
   staff bar and on the sale screen header for the shop the seller is selling in, and the side menu and the phone drawer show the shop in
   use with its logo beside its name, falling back to the business logo and then to no image (Hillary's decision on issue #461). While
   the shop's logo loads, an empty space the size of the logo holds its place, so the business logo never shows for a moment first.
6. **What stays the business's.** The installed app's icon and name, the manifest, the browser tab icon, the sign-in page and the email
   header keep the business logo (ADR-066): one business, one installed app.

## Consequences

**Better:**

- A business with several shops gives each one its own face on receipts and screens, with nothing to set up for a business that does not.
- No new permission, no new public surface beyond one more image route with the existing headers, and one nullable column.

**Worse:**

- A receipt reprinted after a shop's logo changed shows the new logo, as it already does for the business logo.
- The other signed-in staff see a new shop logo in the staff bar, the side menu and the sale screen on their next `/me` refresh (the
  next token refresh or page load), not at once.
- One more public image per shop: anyone who knows a branch id and the business's address can fetch its logo, as anyone can fetch the
  business logo. The image is meant to be seen by customers, so this is accepted.
