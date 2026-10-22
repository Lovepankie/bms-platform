package com.rincoltech.bms.lending.loans.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The request and response bodies of collections (chapter 7 section 7.11.17, FR-CLN-01 to FR-CLN-05). */
final class CollectionsApi {

    private CollectionsApi() {}

    @Schema(name = "LoanOfficerRequest")
    record OfficerRequest(
            @NotNull UUID officerUserId,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in the loan's audit row")
            String reason) {}

    @Schema(name = "LoanReassignRequest", description = "FR-CLN-01: all or none of the loans move")
    record ReassignRequest(
            @NotEmpty @Size(max = 500) List<@NotNull UUID> loanIds,
            @NotNull UUID officerUserId,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in each loan's audit row")
            String reason) {}

    @Schema(name = "LoanReassignResult")
    record ReassignResult(UUID officerUserId, int reassigned) {}

    @Schema(name = "CollectionActionRequest", description = "FR-CLN-04: a promise to pay carries a date and an amount")
    record ActionRequest(
            @Schema(description = "call, visit, sms, letter, promise_to_pay or other") @NotBlank
            String actionType,

            @Size(max = 2000) String note,
            LocalDate promisedDate,
            @Positive Long promisedAmountMinor) {}

    @Schema(name = "CollectionAction")
    record Action(
            UUID id,
            UUID loanId,
            String actionType,
            String note,
            LocalDate promisedDate,
            Long promisedAmountMinor,

            @Schema(description = "pending, kept or broken; null unless a promise to pay")
            String promiseStatus,

            UUID performedBy,
            String performedByName,
            Instant performedAt) {}

    @Schema(name = "CollectionActionList", description = "FR-CLN-04: the loan's timeline, newest first")
    record ActionList(List<Action> items) {}

    @Schema(name = "DueListLine")
    record DueLine(
            UUID loanId,
            String loanNo,
            UUID branchId,
            String memberNo,
            String memberName,

            @Schema(
                    description =
                            "Masked (+256*****0001) unless the caller holds lending.members.contact in the loan's branch")
            String phone,

            int itemNo,
            LocalDate dueDate,

            @Schema(description = "principal, interest, fees and penalties due")
            long dueMinor,

            long paidMinor,
            long waivedMinor,
            long outstandingMinor,
            UUID officerUserId,
            String officerName) {

        DueLine withPhone(String shown) {
            return new DueLine(
                    loanId,
                    loanNo,
                    branchId,
                    memberNo,
                    memberName,
                    shown,
                    itemNo,
                    dueDate,
                    dueMinor,
                    paidMinor,
                    waivedMinor,
                    outstandingMinor,
                    officerUserId,
                    officerName);
        }
    }

    @Schema(name = "DueList", description = "FR-CLN-02, chapter 14 lending.due_list: sorted by officer then due date")
    record DueList(
            LocalDate dateFrom,
            LocalDate dateTo,
            List<DueLine> items,

            @Schema(description = "More lines matched than the limit; narrow by officer, branch or dates")
            boolean truncated) {}

    @Schema(name = "ArrearsListLine")
    record ArrearsLine(
            UUID loanId,
            String loanNo,
            UUID branchId,
            String memberNo,
            String memberName,

            @Schema(
                    description =
                            "Masked (+256*****0001) unless the caller holds lending.members.contact in the loan's branch")
            String phone,

            UUID officerUserId,
            String officerName,
            int daysPastDue,
            String parBucket,
            long arrearsMinor,
            long penaltiesOutstandingMinor,
            long principalOutstandingMinor,
            LocalDate lastRepaymentOn,
            String lastActionType,
            Instant lastActionAt,

            @Schema(description = "The earliest pending promise to pay, if any")
            LocalDate promisedDate,

            Long promisedAmountMinor) {

        ArrearsLine withPhone(String shown) {
            return new ArrearsLine(
                    loanId,
                    loanNo,
                    branchId,
                    memberNo,
                    memberName,
                    shown,
                    officerUserId,
                    officerName,
                    daysPastDue,
                    parBucket,
                    arrearsMinor,
                    penaltiesOutstandingMinor,
                    principalOutstandingMinor,
                    lastRepaymentOn,
                    lastActionType,
                    lastActionAt,
                    promisedDate,
                    promisedAmountMinor);
        }
    }

    @Schema(name = "ArrearsBucket")
    record ArrearsBucket(String parBucket, int loans, long arrearsMinor, List<ArrearsLine> items) {}

    @Schema(
            name = "ArrearsList",
            description = "FR-CLN-03, chapter 14 lending.arrears_list: loans with DPD above 0 by PAR bucket")
    record ArrearsList(
            List<ArrearsBucket> buckets,

            @Schema(description = "More loans matched than the limit; the bucket totals still count every loan")
            boolean truncated) {}

    @Schema(name = "PromiseListLine")
    record PromiseLine(
            UUID actionId,
            UUID loanId,
            String loanNo,
            UUID branchId,
            String memberName,
            UUID officerUserId,
            LocalDate promisedDate,
            long promisedAmountMinor,
            String promiseStatus,
            UUID performedBy,
            Instant performedAt) {}

    @Schema(name = "PromiseList")
    record PromiseList(
            List<PromiseLine> items,

            @Schema(description = "More promises matched than the limit")
            boolean truncated) {}
}
