# ADR-053: Item photos are stored through core.documents and served from the same origin

## Status

Proposed (2026-10-21, issue #384, slice 2). Builds on ADR-051 (an item with sizes and colours is a group of products) and on the
documents module of chapter 12 section 12.5 (FR-DOC-02, FR-DOC-03). The migration is `V71` (above `V69` of the variants, PR #388,
and `V70` of PR #389; renamed at merge if they land in another order).

## Context

The baby clothes shop sells one romper in several prints. In a sale the cashier picks "Baby romper (0-3m, Stars)" from a list of
names, and a photo is the quickest way to pick the right print. The owner wants a photo on the item, which every size shows, and
now and then a photo of one print alone.

The repository already stores files: `core.documents` keeps uploads in a private object storage bucket (R2 in production, an
in-memory fake in tests) under `tenants/<tenant_id>/upload/`, records them in `documents` under row-level security, checks a file by
its bytes, re-encodes images so no camera metadata survives, and hands out five minute signed URLs after the owning module's
`DocumentAccess` says yes. The tenant logo is an image read from the same origin. Two facts shape the choice: the content security
policy of the PWA (`img-src 'self' data: blob:`) allows no image from the bucket's host, and every signed URL issued is audited,
which a list of fifty item cards would turn into fifty audit rows on every visit.

## Decision

- **A photo is a document of `core.documents`.** It is uploaded through `Documents.prepareImage` with its own policy (PNG, JPEG or
  WebP by content, never SVG, at most 5 MB in, at least 64 px on the short side, scaled down to 1200 px on the long edge, at most 24
  megapixels decoded, re-encoded without metadata) and stored with `Documents.store` under the subject type `retail.item_photo`.
  The bucket stays private: nothing lists it and no URL to it is ever public. No new storage, no new bucket, no new secret.
- **Two nullable columns point at it** (`V71`): `retail_products.photo_document_id` (a product's own photo, also a size's own) and
  `retail_product_families.photo_document_id` (the item's). Both are composite foreign keys on `(tenant_id, id)` of `documents`.
  A product's `photo_id` is its own photo and `item_photo_id` its item's, two fields, each absent when there is none, so an item
  without a photo reads exactly as before and Take the photo off is offered for a size's own photo only (a size shows its own,
  else its item's).
- **A small copy for the cards and pickers.** At upload a second image of 240 px on the long edge is stored as its own document
  (subject type `retail.item_photo_small`, its subject the full photo) and served by `?size=small`; it is never made on a read. A
  photo with no small copy serves its full image.
- **Served from the same origin.** `GET /retail/photos/{photo_id}` (`retail.stock.read`) returns the image bytes through the new
  `Documents.readImage(subjectType, id)` (and `readImageOf(subjectType, subjectId)` for the small copy), which reads the metadata
  and checks access in a short read-only transaction and fetches the bytes from storage after it, so no connection waits on the
  object store: 404 unless the document is an image of that subject type (so the route is no way into any
  other document), 403 unless the owning module's `DocumentAccess` allows it (whoever holds `retail.stock.read`, the permission of the
  route itself), and another tenant's photo is not found under row-level security. The response is `private` and may be
  kept a day: a photo never changes under its id, since a new photo is a new document. A read is not audited; the upload, the
  change and the removal are (`retail.product.photo_changed`, `retail.product_family.photo_changed`, `core.document.uploaded`).
- **Routes.** `POST` and `DELETE /retail/products/{id}/photo` and `/retail/product-families/{id}/photo`, multipart with one `file`,
  `retail.catalogue.manage`. Taking a photo off keeps the file, as every document is kept.
- **The PWA** fetches the image with the session's token as a blob and shows it through a `blob:` address, which the policy allows;
  in the item list, the item's page, each size, and the item picker of the sale, restock and stock screens.

## Consequences

**Better:**
- One storage path, one set of checks and one tenant boundary for every file the product keeps; nothing new to secure or back up.
- Photos show on the phone without loosening the content security policy, and a list of items does not flood the audit log.

**Worse:**
- Photo bytes pass through the API instead of straight from the bucket; a long list of photos costs the server a read of a small
  copy per photo (cached a day by the browser), and every upload stores two images.
- A photo replaced or taken off stays in the bucket, as every document does.

**Watch for:**
- The 5 MB request limit of the server (`spring.servlet.multipart.max-file-size`) is the photo limit; raising one means the other.
- Another size would be one more stored copy, never a resize on each read.
