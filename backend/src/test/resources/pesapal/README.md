# Pesapal v3 recorded fixtures (slice #211)

Response bodies of the Pesapal API 3.0 sandbox, consumed by the contract tests of slice #212. Spec:
`docs/specs/client-extras.md` section 3.12. Decision: ADR-035 and its 2026-10-14 amendment.

Everything here is fabricated or scrubbed: no key, secret or token, no phone number, no real order id.
Every tracking id is `aaaaaaaa-0000-4000-8000-...`, every merchant reference is `BMS-ST-` plus a counter, every
token is `FABRICATED.TOKEN.NOT.A.REAL.JWT`.

## How to read the files

`manifest.json` has one entry per file: `provenance`, the `httpStatus` the provider sent, the call, and a note.

| Provenance | Meaning |
|---|---|
| `observed` | The body shape was returned by the sandbox on 2026-10-13; only identifiers were replaced. The body is the real shape, including the provider's own oddities (`ipn_status_decription`, a wrapper whose `message` holds escaped JSON). |
| `documented` | **Documented, not observed.** Built from the official reference (developer.pesapal.com) because the sandbox needs a browser to pay (completed, failed, reversed), a refund needs a completed payment, or the IPN comes from the provider to a public url. Treat as the contract the docs promise, not as proof. |

Files whose name starts `status-completed`, `status-failed`, `status-reversed`, `status-invalid`, `refund-ok`,
`ipn-notification-post-body`, `ipn-acknowledgement`, `callback-query` and `ipn-list` are `documented`
(`ipn-list` has the observed shape and fabricated rows). Re-record the documented ones the first time a
sandbox payment is completed in a browser (a manual step of slice #212 or #213) and replace the file, setting
`provenance` to `observed`.

## What the contract tests must assert

- A business error is usually **http 200** with a body `status` of `"500"` and an `error` object; read the
  body, not only the http code.
- An unpaid order is `status_code` 0, `INVALID`, error code `payment_details_not_found` (see
  `status-pending.json`): it is pending, never failed.
- `payment_status_description` casing is not stable between the docs (`Failed`) and the sandbox (`INVALID`):
  decide on `status_code`, compare descriptions case-insensitively.
- Amounts arrive as JSON numbers with a decimal point (`1000.0`).
