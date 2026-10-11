package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

    CapabilitiesController(RetailCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    @Schema(name = "RetailCapabilities")
    record CapabilitiesResponse(
            @Schema(allowableValues = {"shop", "trade"}) String profile,
            boolean branchRoles,
            boolean units,
            boolean priceLists,
            boolean customerAccounts,
            boolean documents,
            boolean purchasing,
            boolean transferReceipt,
            boolean returns) {}

    /** Omitted fields are unchanged; choosing a profile first writes its preset of the flags. */
    @Schema(name = "UpdateRetailCapabilitiesRequest")
    record UpdateRequest(
            @Schema(allowableValues = {"shop", "trade"}) String profile,
            Boolean branchRoles,
            Boolean units,
            Boolean priceLists,
            Boolean customerAccounts,
            Boolean documents,
            Boolean purchasing,
            Boolean transferReceipt,
            Boolean returns) {}

    @GetMapping
    @RequiresPermission("core.settings.manage")
    @Operation(summary = "The tenant's retail capabilities, absent keys off", operationId = "getRetailCapabilities")
    ResponseEntity<CapabilitiesResponse> get() {
        return ResponseEntity.ok().eTag(String.valueOf(capabilities.version())).body(response(capabilities.group()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Switch retail capabilities or choose the shop or trade profile; audited with old and new values",
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
        CapabilitiesResponse saved = response(capabilities.change(proposed, expected));
        return ResponseEntity.ok().eTag(String.valueOf(capabilities.version())).body(saved);
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static CapabilitiesResponse response(Map<String, Object> g) {
        return new CapabilitiesResponse(
                (String) g.get("profile"),
                flag(g, "branch_roles"),
                flag(g, "units"),
                flag(g, "price_lists"),
                flag(g, "customer_accounts"),
                flag(g, "documents"),
                flag(g, "purchasing"),
                flag(g, "transfer_receipt"),
                flag(g, "returns"));
    }

    private static boolean flag(Map<String, Object> g, String key) {
        return Boolean.TRUE.equals(g.get(key));
    }
}
