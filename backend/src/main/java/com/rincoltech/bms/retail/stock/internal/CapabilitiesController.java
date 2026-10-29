package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/retail/settings/capabilities} (ADR-033, docs/specs/trade-capabilities.md section
 * 1). The group is stored by the core and validated by {@code retail.manifest}; these routes are
 * retail's and call the core settings write through its public interface.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/settings/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class CapabilitiesController {

    private final RetailCapabilities capabilities;
    private final Approvals approvals;

    CapabilitiesController(RetailCapabilities capabilities, Approvals approvals) {
        this.capabilities = capabilities;
        this.approvals = approvals;
    }

    @Schema(name = "RetailCapabilities")
    record CapabilitiesResponse(
            @Schema(
                    allowableValues = {"shops", "stores_and_shops", "wholesale", "wholesale_and_shops"},
                    description =
                            "What kind of business this is (issue #424, ADR-092): a preset of the switches, never a mode;"
                                    + " shops (the default), stores_and_shops, wholesale or wholesale_and_shops")
            String profile,

            boolean branchRoles,
            boolean units,
            boolean priceLists,
            boolean customerAccounts,
            boolean documents,
            boolean purchasing,
            boolean transferReceipt,
            boolean returns,

            @Schema(
                    allowableValues = {"on_sale", "on_payment"},
                    description =
                            "How credit sales count in the reports, the dashboard and the daily savings (ADR-043):"
                                    + " on_sale (the default) counts them when they are made, on_payment only when the buyer pays."
                                    + " One way: on_payment starts the business day after it is asked for and cannot be switched off"
                                    + " once it has started (422 credit_rule_cannot_be_switched_off); until then asking for on_sale"
                                    + " cancels it")
            String creditSaleRecognition,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Read only: the first business day of the rule on_payment (the day after it was asked for), when it was"
                                    + " chosen; it can lie in the future. Credit sales dated before it keep counting the way they did"
                                    + " (ADR-043); absent while the rule was never chosen")
            LocalDate creditRecognitionEffectiveFrom,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Read only: the amount from which a customer return needs a second administrator (action"
                                    + " retail_sale_return, ADR-091), present only while returns ask for approval (0: every return);"
                                    + " while it is absent a return needs no second administrator")
            Long returnsApprovalThresholdMinor,

            @Schema(
                    description =
                            "Stock health (issue #414): an item that sold in the last 90 days is a slow mover when its stock"
                                    + " lasts more than this many months at that pace; 1 to 24, default 4")
            int slowMoverMonths,

            @Schema(
                    description =
                            "Stock health (issue #414): an item is also a slow mover when fewer than this many units sold"
                                    + " in the branch in the last 90 days; 0 (the default) switches this rule off")
            int slowMoverMinSold) {}

    /** Omitted fields are unchanged; choosing a profile first writes its preset of the flags. */
    @Schema(name = "UpdateRetailCapabilitiesRequest")
    record UpdateRequest(
            @Schema(
                    allowableValues = {"shops", "stores_and_shops", "wholesale", "wholesale_and_shops"},
                    description =
                            "The kind of business: sets units, price_lists, customer_accounts, documents, purchasing and"
                                    + " transfer_receipt to its preset (branch_roles and returns are left as they are);"
                                    + " a flag sent in the same change wins")
            String profile,

            Boolean branchRoles,
            Boolean units,
            Boolean priceLists,
            Boolean customerAccounts,
            Boolean documents,
            Boolean purchasing,
            Boolean transferReceipt,
            Boolean returns,

            @Schema(allowableValues = {"on_sale", "on_payment"})
            String creditSaleRecognition,

            @Schema(description = "1 to 24") Integer slowMoverMonths,

            @Schema(description = "0 to 1,000,000; 0 is off")
            Integer slowMoverMinSold) {}

    @GetMapping
    @RequiresPermission("core.settings.manage")
    @Operation(summary = "The tenant's retail capabilities, absent keys off", operationId = "getRetailCapabilities")
    ResponseEntity<CapabilitiesResponse> get() {
        return ResponseEntity.ok().eTag(String.valueOf(capabilities.version())).body(response(capabilities.group()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Switch retail capabilities or choose the kind of business; audited with old and new values",
            operationId = "updateRetailCapabilities")
    ResponseEntity<CapabilitiesResponse> update(
            @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody UpdateRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Map<String, Object> proposed = new LinkedHashMap<>();
        put(proposed, "profile", r.profile());
        put(proposed, "branch_roles", r.branchRoles());
        put(proposed, "units", r.units());
        put(proposed, "price_lists", r.priceLists());
        put(proposed, "customer_accounts", r.customerAccounts());
        put(proposed, "documents", r.documents());
        put(proposed, "purchasing", r.purchasing());
        put(proposed, "transfer_receipt", r.transferReceipt());
        put(proposed, "returns", r.returns());
        put(proposed, RetailCapabilities.CREDIT_RECOGNITION, r.creditSaleRecognition());
        put(proposed, "slow_mover_months", r.slowMoverMonths());
        put(proposed, "slow_mover_min_sold", r.slowMoverMinSold());
        // Switching transfer_receipt off while stock is on its way is refused inside the change
        // (TransferService.check).
        CapabilitiesResponse saved = response(capabilities.change(proposed, expected));
        return ResponseEntity.ok().eTag(String.valueOf(capabilities.version())).body(saved);
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private CapabilitiesResponse response(Map<String, Object> g) {
        Approvals.Rule returns = approvals.rule("retail_sale_return");
        return new CapabilitiesResponse(
                (String) g.get("profile"),
                flag(g, "branch_roles"),
                flag(g, "units"),
                flag(g, "price_lists"),
                flag(g, "customer_accounts"),
                flag(g, "documents"),
                flag(g, "purchasing"),
                flag(g, "transfer_receipt"),
                flag(g, "returns"),
                g.get(RetailCapabilities.CREDIT_RECOGNITION) instanceof String c ? c : RetailCapabilities.ON_SALE,
                g.get("credit_recognition_effective_from") instanceof String d ? LocalDate.parse(d) : null,
                returns.on() ? returns.thresholdMinor() : null,
                g.get("slow_mover_months") instanceof Number m ? m.intValue() : 4,
                g.get("slow_mover_min_sold") instanceof Number n ? n.intValue() : 0);
    }

    private static boolean flag(Map<String, Object> g, String key) {
        return Boolean.TRUE.equals(g.get(key));
    }
}
