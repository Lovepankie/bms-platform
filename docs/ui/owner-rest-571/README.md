# Issue #571: the billing contact, closing the business and businesses with no owner

Phone, 412x900, taken with the fabricated retail mock (`VITE_RETAIL_MOCK=1`) and the new API answers stubbed in the browser with
fabricated values. The welcome tour, which starts by itself for the mock session, is hidden. The read-only notice every member of
staff sees is not drawn in mock mode; `plan.dom.test.tsx` covers its words.

| File | Screen |
|---|---|
| `01-billing-who-gets-the-bills-412.png` | Plan and billing, Who gets the bills: the owner, before anyone else is named |
| `02-billing-contact-form-412.png` | The Owner names an accountant: name, email, optional mobile number |
| `03-billing-contact-saved-412.png` | Saved: the bills go to the accountant; Send them to me again |
| `04-close-what-it-means-412.png` | Close the business, step 1: only for stopping for good; cancelling keeps the data |
| `05-close-review-step-412.png` | Step 2, the review: exactly what happens, in order, with the primary action at the bottom |
| `05b-close-review-proof-412.png` | Step 2, the Owner's own proof (the password, or the authenticator code with two-step sign-in) |
| `06-close-requested-cancel-412.png` | After the request: read only, the day of removal, the copy of the data, Cancel the closing |
| `08-operator-businesses-closing-412.png` | Operator: Businesses closing, removable, waiting and cancelled |
| `09-operator-remove-confirm-412.png` | Operator: the removal confirm with what is deleted and kept, the code and a reason |
| `10-operator-no-owner-412.png` | Operator: Businesses with no owner and their administrators |
| `11-operator-set-the-owner-412.png` | Operator: Set as the owner, with the code and a reason |
