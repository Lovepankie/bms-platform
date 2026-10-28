package com.rincoltech.bms.lending.loans.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of {@code /api/v1/lending/existing-loans} (chapter 7 section 7.11.13; ADR-071). */
final class ExistingLoanApi {

    /** The repayments one existing loan may bring in. */
    static final int MAX_REPAYMENTS = 500;

    static final String METHODS = "cash|bank|mtn_momo|airtel_money";

    private ExistingLoanApi() {}

    @Schema(name = "ExistingLoanRepayment", description = "One repayment the register shows, before the go-live date")
    record Repayment(
            @NotNull LocalDate valueDate,
            @NotNull @Positive @Max(LoanApi.MAX_MONEY_MINOR) Long amountMinor,
            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,

            @Size(max = 100) @Schema(description = "The paper receipt number or a note; optional")
            String note) {}

    @Schema(name = "RecordExistingLoanRequest")
    record RecordRequest(
            @NotNull UUID memberId,
            @NotNull UUID productId,
            @NotNull @Positive @Max(LoanApi.MAX_MONEY_MINOR) Long principalMinor,

            @NotNull @Schema(description = "The real disbursement date, before the business's go-live date")
            LocalDate disbursementDate,

            @Positive @Schema(description = "Defaults to the product's default term; within the product's limits")
            Integer termCount,

            @Positive
            @Max(LoanApi.MAX_MONEY_MINOR)
            @Schema(
                    description =
                            "The total the borrower agreed to repay (principal and interest), when the register records that instead of the product's rate; the difference is spread as flat interest and no fee is added")
            Long agreedTotalMinor,

            @Pattern(regexp = LoanApi.PURPOSES) @Schema(description = "Defaults to other")
            String purposeCategory,

            @Size(max = 40) @Schema(description = "The loan's number in the paper register; one live loan per number")
            String registerRef,

            @NotNull @Valid @Size(max = MAX_REPAYMENTS) List<Repayment> repayments) {}

    @Schema(name = "ExistingLoanScheduleItem")
    record ScheduleRow(
            int no,
            LocalDate dueDate,
            long principalDueMinor,
            long interestDueMinor,
            long feesDueMinor,
            long paidMinor,
            long unpaidMinor,
            String status) {}

    @Schema(name = "ExistingLoanPosition", description = "As the register leaves the loan, on today's business date")
    record Position(
            long principalOutstandingMinor,
            long interestOutstandingMinor,
            long feesOutstandingMinor,
            long totalOutstandingMinor,
            long arrearsMinor,

            @Schema(description = "Counted from the real due date of the oldest unpaid instalment (R-DPD)")
            int daysPastDue,

            LocalDate nextDueDate,
            long totalPaidMinor,
            LocalDate lastRepaymentOn,

            @Schema(description = "True when the register shows the loan repaid in full; it is closed on approval")
            boolean settled) {}

    @Schema(name = "ExistingLoan")
    record ExistingLoan(
            @Schema(description = "Null on a check (dry run), which saves nothing")
            UUID loanId,

            String loanNo,

            @Schema(description = "brought_in (waiting for a checker), then active or closed; cancelled if withdrawn")
            String status,

            UUID branchId,
            UUID memberId,
            String memberNo,
            UUID productId,
            String productCode,
            String currency,
            long principalMinor,
            LocalDate disbursementDate,
            int termCount,
            Long agreedTotalMinor,
            String registerRef,
            LocalDate goLiveDate,

            @Schema(description = "Set on approval: the first business date the app charges penalties for")
            LocalDate broughtInOn,

            @Schema(
                    description =
                            "The pending request a second person decides: the bring-in while the loan is brought_in, the reversal of the bring-in (loan_bring_in_reversal) once it is active or closed")
            UUID approvalRequestId,

            List<Repayment> repayments,
            List<ScheduleRow> schedule,
            Position position,

            @Schema(
                    description =
                            "Things to check before recording or approving, none of which stops it: a loan of the same member for the same amount and date already in the app when no register number tells them apart")
            List<String> warnings) {}

    @Schema(name = "ExistingLoanPage")
    record ExistingLoanPage(List<ExistingLoanSummary> items) {}

    @Schema(name = "ExistingLoanSummary")
    record ExistingLoanSummary(
            UUID loanId,
            String loanNo,
            String status,
            UUID memberId,
            String memberNo,
            long principalMinor,
            LocalDate disbursementDate,
            String registerRef,
            UUID approvalRequestId) {}

    @Schema(name = "GoLiveDate")
    record GoLive(
            @Schema(description = "The business's go-live date, or null while none is known")
            LocalDate goLiveDate,

            @Schema(
                    description =
                            "set (by the Owner or the operator), first_admin_sign_in (the default) or none (nobody has signed in yet)")
            String source) {}

    @Schema(name = "CancelExistingLoanRequest")
    record CancelRequest(@NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "ExistingLoanImportRequest")
    record ImportRequest(
            @NotBlank
            @Size(max = ExistingLoanImport.MAX_CHARS)
            @Schema(
                    description =
                            "The CSV text: a header row, then rows of kind loan (one per loan) and kind repayment (any number per loan), tied by ref. Comma, semicolon or tab separated; amounts in the currency's major units. A loan row names its member by member_no, or by member_name and member_phone (issue #626): the member with that phone, else a new member added with the loan")
            String csv,

            @Schema(
                    description =
                            "The branch members new to the app join (issue #626); empty for the only branch the caller may add members in",
                    nullable = true)
            UUID branchId) {}

    @Schema(name = "ExistingLoanImportRow")
    record ImportRow(
            int line,
            String ref,

            @Schema(
                    description =
                            "ready (a check found nothing wrong), added, skipped (the register number is in use) or error")
            String outcome,

            String message,
            UUID loanId) {}

    @Schema(name = "ExistingLoanImportResult")
    record ImportResult(
            boolean dryRun,
            int rowsRead,
            int loans,
            int repayments,

            @Schema(description = "Loans recorded, or on a check the loans that would be; 0 while any row is an error")
            int added,

            int skipped,
            int errors,

            @Schema(
                    description =
                            "Members new to the app that come in with the loans (issue #626); 0 while any row is an error")
            int membersAdded,

            List<ImportRow> rows) {}

    @Schema(name = "LoanRegisterDraft")
    record RegisterDraft(
            @Schema(
                    description = "Null when loans were drafted; else why there are none: no_provider, all_failed,"
                            + " daily_cap or unreadable. The screen then offers to type it or import a file",
                    nullable = true)
            String reason,

            @Schema(description = "The drafted loans, for the import's check; nothing is saved")
            List<RegisterDraftLoan> loans,

            @Schema(description = "Decimal places of the currency, to write the amounts in the import file")
            int currencyExponent) {}

    @Schema(name = "LoanRegisterDraftLoan")
    record RegisterDraftLoan(
            @Schema(description = "The register's number for the loan, or one the app made when it has none")
            String ref,

            @Schema(
                    description = "The member who has this phone already, or null for a member new to the app",
                    nullable = true)
            String memberNo,

            @Schema(description = "That member's name in the app, or null", nullable = true)
            String memberFullName,

            @Schema(description = "The borrower's name as the register writes it", nullable = true)
            String memberName,

            @Schema(description = "The phone, in E.164 when it is a Uganda number, else as written", nullable = true)
            String memberPhone,

            @Schema(nullable = true) Long principalMinor,
            @Schema(nullable = true) LocalDate disbursementDate,
            @Schema(nullable = true) Integer termMonths,

            @Schema(description = "The total to repay, as written or worked out", nullable = true)
            Long agreedTotalMinor,

            @Schema(description = "The monthly rate in percent as written, or null", nullable = true)
            String ratePercent,

            @Schema(
                    description =
                            "The total was worked out as flat interest from the rate and the term, for the owner to check")
            boolean totalWorkedOut,

            List<RegisterDraftPayment> payments) {}

    @Schema(name = "LoanRegisterDraftPayment")
    record RegisterDraftPayment(
            @Schema(nullable = true) LocalDate date,
            @Schema(nullable = true) Long amountMinor) {}
}
