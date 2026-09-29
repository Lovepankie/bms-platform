# API contract

`openapi.json` in this folder is the committed snapshot of the API's generated OpenAPI 3.1
document. CI regenerates it and fails when the committed file differs, so every contract
change is visible in review (`docs/sdd/07-api-design.md` section 7.3). The frontend's API
types are generated from this file.

The snapshot appears with the first API endpoints. Until then the contract is the
endpoint catalogue in chapter 7 section 7.11.

Hand-written contract drafts for endpoints not yet built may be added here as
`draft-<topic>.md` and are deleted when the endpoint lands.
