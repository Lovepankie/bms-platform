# Pilot data dictionary

**Status:** Draft · **Owner:** Hillary · **Source:** observation of the pilot tenant's loan register (shape only)

The pilot tenant's loan register is one flat sheet with 25 columns, one row per loan. This
document records what each column means, what was observed about its quality (in generic
terms, with no values), and where it lands in the data model. No value from the real file
appears in this repository; the fabricated fixture
`fixtures/pilot_loan_register_sample.csv` reproduces every observed problem.

Import rules are normative in `docs/sdd/13-data-migration-and-import.md`; this document is
the descriptive companion.

## 1. Columns

| # | Column (exact header) | Meaning | Observed type | Observed quality issues | Maps to (chapter 13 section 13.8) |
|---|---|---|---|---|---|
| 1 | `DATE` | Date the loan was given | Mixed: real dates and text | Some real dates have day and month swapped (typed day first, read month first); some are text in month/day/year form | `lending_loans.disbursed_on` |
| 2 | `CUSTOMER NAME` | Borrower's full name | Text | Placeholder values in some rows; the same borrower appears on several rows (repeat loans) | `lending_members.full_name` |
| 3 | `LOCATION` | Borrower's village or area | Text | Free text, inconsistent spelling | `lending_members.location` |
| 4 | `CUSTOMER PHONE` | Borrower's phone | Mostly numbers | Stored as numbers, so the leading `0` or the `256` country code is lost; some text with spaces | `lending_members.phone_e164` |
| 5 | `CUSTOMER ID` | National ID number (NIN) | Text | Format `CM` or `CF` plus 12 characters; occasional blanks | `lending_members.national_id` |
| 6 | `MARITAL STATUS` | Borrower's marital status | Text | Upper case words; some blanks | `lending_members.marital_status` |
| 7 | `NEXT OF KIN` | Next of kin's full name | Text | Some next of kin are themselves borrowers on other rows | `lending_next_of_kin.full_name` |
| 8 | `NEXT OF KIN PHONE` | Next of kin's phone | Mostly numbers | Same number-storage problem as column 4 | `lending_next_of_kin.phone_e164` |
| 9 | `NEXT OF KIN ID` | Next of kin's NIN | Text | Occasional blanks | `lending_next_of_kin.national_id` |
| 10 | `RELATIONSHIP OF NEXT OF KIN` | Relationship to the borrower | Text | Free words (wife, brother, friend) | `lending_next_of_kin.relationship` |
| 11 | `LOCATION OF NEXT OF KIN` | Next of kin's village or area | Text | Free text | `lending_next_of_kin.location` |
| 12 | `OCCUPATION` | Borrower's occupation | Text | Free text | `lending_members.occupation` |
| 13 | `OTHER SOURCE OF INCOME` | Secondary income | Text | Free text; often `NONE` | `lending_members.other_income_source` |
| 14 | `REASON` | Loan purpose | Text | Free text | `lending_loans.purpose_text` |
| 15 | `PRINCIPAL REQUESTED` | Amount lent | Number | Some rows hold only amounts and no borrower | `lending_loans` principal fields |
| 16 | `PERCENTAGE` | Interest rate for the whole term, as a fraction (0.2 means 20 percent) | Number | A typo where a value far above 1 was entered for a fraction (the amounts imply the intended rate) | `interest_rate_bp`, `rate_unit = per_term` |
| 17 | `DURATION` | Loan term | Text | Free text with and without spaces and plurals (`1 Month`, `1Month`, `2 Week`) | `term_count`, `term_unit` |
| 18 | `EXPECTED DATE OF RETURN` | Due date of the single repayment | Mixed | Same date problems as column 1; must equal DATE plus DURATION | Schedule item due date |
| 19 | `AMOUNT TO BE RETURNED` | Principal plus interest, one payment | Number | Equals principal times (1 + rate) when both are right; a mismatch reveals an error in one of them | Schedule item interest |
| 20 | `COLLATERAL/ PLEDGE` | What was pledged | Text | Free text: land, ID, car logbook, or a car identified by plate | `lending_collateral_items` |
| 21 | `CURRENT PAYMENT` | Amount paid so far | Number | Empty on most rows (repayments are not tracked today) | Historic repayment |
| 22 | `DATE OF CURRENT PAY` | Date of that payment | Mixed | Mostly empty | Historic repayment value date |
| 23 | `OUTSTANDING BALANCE` | Amount still owed | Number | Should equal amount to be returned minus current payment | Check only |
| 24 | `STATUS` | Loan status in words | Text | Free words; not always consistent with the balance | Check only; status is derived |
| 25 | `REMARKS` | Notes | Text | Free text | Loan note |

## 2. Row-level observations

| Observation | Handling (chapter 13) |
|---|---|
| A month name row (for example a month and year) appears inside the data as a section heading | Classified `subheader`, excluded with a visible reason, and used as date context for the rows below |
| A placeholder row with dummy text and zero amounts | Classified `placeholder`, blocking until a reviewer excludes or reclassifies it |
| Rows with amounts but no borrower | Classified `amounts_only`, blocking until a reviewer excludes it or links it to a borrower row |
| The same borrower on several rows | One member, several loans (`DUPLICATE_BORROWER`) |
| A borrower named as another borrower's next of kin | Linked by NIN (`KIN_IS_MEMBER`); feeds the relationship graph and exposure in appraisal |

## 3. What the register does not contain

- No repayment history beyond one "current payment" per row.
- No approval, disbursement or fee records; the requested principal is assumed disbursed
  in full (open question 7 in `docs/specs/lending-mvp-scope.md`).
- No branch or officer; the import assigns the batch's branch and default officer.
- No gender, date of birth or declared income; members are imported with KYC
  `incomplete`.
- No savings or investment data.

## 4. Fixture conventions

`fixtures/pilot_loan_register_sample.csv` has the same 25 headers and 12 fabricated rows.
In that CSV:

- a value that was a real date cell in the spreadsheet is written as ISO `YYYY-MM-DD`
  (including the swapped ones, exactly as the spreadsheet would read them);
- a text date is written as typed (`MM/DD/YYYY`);
- a phone that was a number cell is written without its leading `0` or `+`;
- names are `Test Borrower NN` and `Test Kin NN`, phones are in the `2567000000NN` range,
  NINs are `CMTEST00000NNA` or `CFTEST00000NNA`, villages are `Test Village X`, and the
  plate is `UXX 001X`. All are invented.

The expected outcome of importing the fixture is in chapter 13 section 13.12.
