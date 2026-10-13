package com.rincoltech.bms.retail.sales.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the customer return routes (returns capability; ADR-033, issue #201;
 * docs/specs/trade-capabilities.md section 5.3). Amounts are integer minor units; quantities are decimal strings with
 * three places. Cost fields are absent without {@code retail.profit.read}.
 */
final class ReturnsApi {

    static final String REASONS = "defective|damaged|wrong_item|not_as_described|unwanted|price_error|other";
    static final String DISPOSITIONS = "restock|write_off";
    static final String MONEY_METHODS = "cash|mobile_money|bank";
    static final String REFUND_METHODS = "cash|mobile_money|bank|account";
    static final int MAX_LINES = 200;

    private ReturnsApi() {}

    @Schema(name = "RetailReturnLineRequest")
    record ReturnLineRequest(
            @NotNull @Schema(description = "The line of the sale that comes back")
            UUID saleLineId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "2.000")
            BigDecimal qty,

            @Schema(
                    description =
                            "The unit qty is in: the line's own unit, the product's base unit or a pack unit of the"
                                    + " product (units capability). Absent means the base unit. The quantity must convert to"
                                    + " thousandths of the base unit exactly")
            UUID unitId,

            @NotBlank @Pattern(regexp = REASONS) @Schema(description = REASONS)
            String reason,

            @NotBlank
            @Pattern(regexp = DISPOSITIONS)
            @Schema(
                    description =
                            "restock puts the goods back on the shelf; write_off is for damaged goods, which never enter stock")
            String disposition,

            @Schema(
                    description =
                            "Where restocked goods go: any active branch or store in the caller's scope. Absent means the sale's branch")
            UUID restockBranchId) {}

    @Schema(name = "RetailReturnRequest")
    record ReturnRequest(
            @NotEmpty @Size(max = MAX_LINES) @Valid List<ReturnLineRequest> lines,

            @NotBlank
            @Pattern(regexp = REFUND_METHODS)
            @Schema(
                    description =
                            "cash, mobile_money or bank pay the customer back out of the sale's branch. account takes the value"
                                    + " off what the customer owes on this credit sale, never below zero; any rest is refunded in money_method")
            String refundMethod,

            @Pattern(regexp = MONEY_METHODS)
            @Schema(
                    description =
                            "How any part that cannot be taken off the balance is paid out; required for it when refund_method is account")
            String moneyMethod,

            @Schema(
                    description =
                            "The branch whose till pays the money out, inside the caller's retail.sale.return scope; absent means the"
                                    + " caller's own branch when their scope is one branch, else the sale's branch. Not used when nothing is paid out in money")
            UUID refundBranchId,

            @Size(max = 300) String note) {}

    @Schema(name = "RetailReturnVoidRequest")
    record VoidReturnRequest(
            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailReturnLine")
    record ReturnLine(
            UUID id,
            int lineNo,
            UUID saleLineId,
            UUID productId,
            String code,
            String description,
            @Schema(description = "In base units") String qty,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID unitId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String unit,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "In the unit it was entered in")
            String unitQty,

            @Schema(description = "The credit for this line: its share of what the sale line was charged")
            long lineTotalMinor,

            String reason,
            String disposition,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID restockBranchId,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Present only with retail.profit.read")
            Long unitCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Present only with retail.profit.read")
            Long lineCostMinor) {}

    @Schema(name = "RetailSaleReturn", description = "A customer return and its numbered credit note")
    record SaleReturn(
            UUID id,
            String creditNoteNo,
            UUID saleId,
            String saleNo,
            UUID branchId,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID customerId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String customerName,
            LocalDate returnedOn,
            String currency,

            @Schema(description = "What the customer is credited: the returned lines at the price they were charged")
            long totalMinor,

            @Schema(description = "The part taken off what the customer owes on the credit sale")
            long accountCreditMinor,

            @Schema(description = "The part paid out in money")
            long refundMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) String refundMethod,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The branch whose till paid the money out; present with refund_method")
            UUID refundBranchId,

            @Schema(description = "What the caller asked for: cash, mobile_money, bank or account")
            String refundChoice,

            @JsonInclude(JsonInclude.Include.NON_NULL) String note,
            String status,
            List<ReturnLine> lines,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Present only with retail.profit.read")
            Long costMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) UUID approvalId,
            Instant createdAt,
            UUID createdBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID approvedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant voidedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate voidedOn,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID voidedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) String voidReason,
            int version) {}

    @Schema(
            name = "RetailReturnOutcome",
            description =
                    "201 (200 for a void) with the return when it was done; 202 with approval_id when it waits for a checker"
                            + " (the tenant's approval threshold for retail_sale_return was reached by the returns on the sale)")
    record ReturnOutcome(
            boolean executed,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID approvalId,
            @JsonInclude(JsonInclude.Include.NON_NULL) SaleReturn saleReturn) {}

    @Schema(name = "RetailReturnList")
    record ReturnList(
            List<SaleReturn> items,
            @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {}
}
