package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.EntryLocations;
import com.rincoltech.bms.retail.stock.EntryLocations.Fix;
import com.rincoltech.bms.retail.stock.internal.EntryLocationService.Distance;
import com.rincoltech.bms.retail.stock.internal.EntryLocationService.Shop;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail/entry-locations} and {@code /api/v1/retail/shop-locations} (issue #826, ADR-122). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class EntryLocationController {

    /** The most entries one read may ask for: a page of the usage list. */
    static final int MAX_IDS = 100;

    private final EntryLocationService service;

    EntryLocationController(EntryLocationService service) {
        this.service = service;
    }

    @Schema(name = "RetailEntryDistance")
    record EntryDistance(
            UUID id,

            @Schema(description = "located, no_location or shop_not_set")
            String state,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Metres from the shop, rounded as the words say; only when located")
            Long distanceM,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "How sure the phone was, in metres; absent with no location")
            Long accuracyM,

            @Schema(description = "40 m from the shop, 3.2 km from the shop, no location or shop location not set")
            String words) {
        static EntryDistance of(Distance d) {
            return new EntryDistance(d.id(), d.state(), d.distanceM(), d.accuracyM(), d.words());
        }
    }

    @Schema(name = "RetailEntryDistanceList")
    record EntryDistanceList(List<EntryDistance> items) {}

    @Schema(name = "RetailShopLocation")
    record ShopLocation(
            UUID branchId,
            String code,
            String name,
            boolean hasLocation,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long accuracyM,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant setAt) {
        static ShopLocation of(Shop s) {
            return new ShopLocation(s.branchId(), s.code(), s.name(), s.hasLocation(), s.accuracyM(), s.setAt());
        }
    }

    @Schema(name = "RetailShopLocationList")
    record ShopLocationList(List<ShopLocation> items) {}

    @GetMapping("/entry-locations")
    @RequiresPermission(EntryLocationService.READ)
    @Operation(
            summary =
                    "How far from the shop each of these sales or usage and damage entries was made, in plain words;"
                            + " never the coordinates. An entry with a location at a branch outside the caller's scope is left out",
            operationId = "listRetailEntryDistances")
    EntryDistanceList distances(
            @Parameter(description = "sale or usage") @RequestParam("source") String source,
            @Parameter(description = "The entries' ids, at most 100") @RequestParam("id") List<UUID> ids) {
        String kind = switch (source) {
            case "sale" -> EntryLocations.SALE;
            case "usage" -> EntryLocations.USAGE;
            default ->
                throw ApiException.validation(List.of(new FieldProblem("source", "invalid", "Use sale or usage.")));
        };
        if (ids.size() > MAX_IDS) {
            throw ApiException.validation(List.of(new FieldProblem("id", "too_many", "At most 100 ids.")));
        }
        return new EntryDistanceList(
                service.distances(kind, ids.stream().distinct().toList()).stream()
                        .map(EntryDistance::of)
                        .toList());
    }

    @GetMapping("/shop-locations")
    @RequiresPermission(EntryLocationService.MANAGE)
    @Operation(
            summary = "The active branches with whether each has a location set; never the coordinates",
            operationId = "listRetailShopLocations")
    ShopLocationList shops() {
        return new ShopLocationList(
                service.shops().stream().map(ShopLocation::of).toList());
    }

    @PutMapping(path = "/shop-locations/{branch_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(EntryLocationService.MANAGE)
    @Operation(
            summary =
                    "Set a shop's location to where the owner's phone is now (Use where I am now); audited without the"
                            + " coordinates; 422 invalid_location out of range",
            operationId = "setRetailShopLocation")
    ShopLocation set(@PathVariable("branch_id") UUID branchId, @Valid @RequestBody Fix request) {
        return ShopLocation.of(service.set(branchId, request));
    }

    @DeleteMapping("/shop-locations/{branch_id}")
    @RequiresPermission(EntryLocationService.MANAGE)
    @Operation(summary = "Clear a shop's location; audited", operationId = "clearRetailShopLocation")
    ResponseEntity<Void> clear(@PathVariable("branch_id") UUID branchId) {
        service.clear(branchId);
        return ResponseEntity.noContent().build();
    }
}
