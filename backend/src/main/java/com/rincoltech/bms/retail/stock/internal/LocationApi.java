package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferLine;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of {@code GET /retail/reports/stock-by-location} and {@code GET
 * /retail/reports/transfers-in-flight} (docs/specs/trade-capabilities.md section 8). Cost and value
 * fields are absent, never null, without {@code retail.profit.read} in the branches they cover.
 */
final class LocationApi {

    static final String COST_ONLY = "Present only with retail.profit.read in every branch of the report";

    private LocationApi() {}

    @Schema(name = "RetailLocationBranch", description = "A column of the report; ordered stores, shops, then both")
    record LocationBranch(
            UUID id,
            String code,
            String name,
            boolean headOffice,

            @Schema(
                    allowableValues = {"store", "shop", "both"},
                    description = "both for every branch while the branch_roles capability is off")
            String role,

            @Schema(description = "A closed branch that still holds stock; its history stays in this report (#329)")
            boolean closed) {}

    @Schema(name = "RetailLocationBalance")
    record LocationBalance(
            UUID branchId,
            String qty,

            @Schema(description = "Below zero in this branch")
            boolean negative,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Quantity at the current cost; present only with retail.profit.read in this branch")
            Long valueMinor) {}

    @Schema(name = "RetailRoleQuantity")
    record RoleQuantity(
            @Schema(allowableValues = {"store", "shop", "both"})
            String role,

            String qty) {}

    @Schema(
            name = "RetailLocationRow",
            description = "One product: its balance in every branch, by role, and in transit")
    record LocationRow(
            UUID productId,
            String code,
            String description,
            UUID categoryId,
            String category,
            String unit,

            @Schema(description = "One entry per branch of the report, in its order")
            List<LocationBalance> balances,

            @Schema(description = "The sum of the balances of each role present in the report")
            List<RoleQuantity> subtotals,

            @Schema(
                    description =
                            "Dispatched and not yet received, from or to a branch of the report; in neither balance")
            String inTransitQty,

            @Schema(description = "All the branches' balances plus the quantity in transit")
            String combinedQty,

            @Schema(description = "True when any branch's balance is below zero")
            boolean negative,

            long sellMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; the balances at the current cost")
            Long valueMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; the quantity in transit at the cost it was dispatched at")
            Long inTransitValueMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long combinedValueMinor) {}

    @Schema(name = "RetailLocationBranchValue")
    record BranchValue(UUID branchId, long valueMinor) {}

    @Schema(name = "RetailLocationRoleValue")
    record RoleValue(String role, long valueMinor) {}

    @Schema(
            name = "RetailLocationTotals",
            description = "Over every product in the caller's branches, not only the page; cost fields as the rows")
    record LocationTotals(
            @Schema(description = "Only the branches where the caller holds retail.profit.read")
            List<BranchValue> byBranch,

            @Schema(description = "Only the roles whose branches in the report all show cost")
            List<RoleValue> byRole,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long valueMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; the value of the dispatches still on their way")
            Long inTransitValueMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long combinedValueMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; the inventory account of those branches")
            Long inventoryLedgerMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; value at cost less the inventory account, as in the valuation")
            Long revaluationDifferenceMinor) {}

    @Schema(name = "RetailStockByLocation")
    record LocationReport(
            String currency,
            List<LocationBranch> branches,
            List<LocationRow> items,
            LocationTotals totals,
            String nextCursor) {}

    @Schema(name = "RetailInFlightTransfer")
    record InFlightTransfer(
            UUID id,
            UUID fromBranchId,
            UUID toBranchId,
            LocalDate transferDate,
            Instant dispatchedAt,

            @Schema(description = "Days from the transfer date to today")
            int ageDays,

            String note,
            List<TransferLine> lines,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = TransferApi.COST_ONLY)
            Long costTotalMinor) {}

    @Schema(name = "RetailTransfersInFlight")
    record InFlightReport(
            String currency,

            @Schema(description = "Dispatched and not received or cancelled, the longest wait first")
            List<InFlightTransfer> items,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "The sum of the open dispatches, built from the transfers (never read from the ledger)")
            Long inTransitValueMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Tenant-wide net of inter-branch clearing over retail transfer entries only (account 1190 is shared"
                                    + " with lending); present for a caller who reads every branch with cost")
            Long clearingMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "True when clearing_minor equals the value of every open dispatch")
            Boolean reconciled) {}
}
