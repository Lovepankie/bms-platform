# Recorded Africa's Talking delivery reports (fabricated)

Bodies of delivery report callbacks as the provider's public reference describes them (a form POST with `id`,
`status`, `phoneNumber`, `networkCode`, `retryCount` and, for a failure, `failureReason`), issue #215. Every value is
fabricated: the ids are placeholders, and `MESSAGE_ID` is replaced by the test with a fresh id; the numbers are the
fabricated `+256700000xxx` range. The field names and status words are confirmed against the sandbox by the manual
contract job (spec section 8); if the sandbox shows another shape, these files and the reader change together.
