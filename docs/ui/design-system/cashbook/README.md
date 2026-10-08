# Cash book screens (#147)

Taken against the real API (dev profile, PostgreSQL at V34) with headless Chromium, on a fabricated tenant
("Sample Shop (fabricated)": one head office, three products, five days of sales, expenses, savings, bankings,
a withdrawal and an advance with a repayment). Viewport captures at 360px (`*-360`) and 1280px (`*-1280`).
Everything shown is fabricated. Negative cash appears because the fabricated tenant has no opening cash.

Only these ten captures are committed, so not every screen is shown at both widths: Savings, Banking, Expenses, Cash
summary and Home at 360px; Advances and Cash summary at both; the Banking report and the Expenses report at 1280px
only. The Cash withdrawals screen, the Expense lists and the Savings records have no capture.

`cash-summary-360.png` and `savings-360.png` were not captured at the same moment of the run: the summary shows
"Savings set aside UGX 0" for Head Office on 7 Oct while the savings capture shows a record for that shop and day.
The summary's read model was checked for this (`RetailCashbookSummaryIT`, savings recorded today show in today's
summary), so read the summary capture as taken before that day's savings were entered. It was not retaken.
