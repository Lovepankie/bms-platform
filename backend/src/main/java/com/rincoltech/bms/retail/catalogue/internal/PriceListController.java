package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.EndItemRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.Group;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.GroupList;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.GroupRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.Item;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.ItemPage;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.ItemRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.PriceList;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.PriceListList;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.PriceListRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.UpdateGroupRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.UpdatePriceListRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/retail} price list and customer group routes (ADR-033, issue #195; chapter 7 section 7.11.20). All of
 * them answer 404 {@code capability_not_enabled} while the {@code price_lists} capability is off. Reading needs
 * {@code retail.pricelist.manage} too: a list is a customer's negotiated price, so a seller never lists them; the sale
 * asks {@code POST /retail/prices/resolve} for the price it needs.
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-price-lists")
class PriceListController {

    private final PriceListService service;

    PriceListController(PriceListService service) {
        this.service = service;
    }

    @GetMapping("/price-lists")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(summary = "The price lists (issue #195)", operationId = "listRetailPriceLists")
    PriceListList lists() {
        return service.lists();
    }

    @PostMapping("/price-lists")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(summary = "Create a price list (issue #195)", operationId = "createRetailPriceList")
    ResponseEntity<PriceList> createList(@Valid @RequestBody PriceListRequest request) {
        PriceList created = service.createList(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/price-lists/{price_list_id}")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(summary = "A price list", operationId = "getRetailPriceList")
    ResponseEntity<PriceList> list(@PathVariable("price_list_id") UUID id) {
        PriceList l = service.list(id);
        return ResponseEntity.ok().eTag(String.valueOf(l.version())).body(l);
    }

    @PatchMapping("/price-lists/{price_list_id}")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(
            summary = "Rename, make strict or switch a list off under If-Match; never deleted (issue #195)",
            operationId = "updateRetailPriceList")
    ResponseEntity<PriceList> updateList(
            @PathVariable("price_list_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdatePriceListRequest request) {
        PriceList l = service.updateList(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(l.version())).body(l);
    }

    @GetMapping("/price-lists/{price_list_id}/items")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(
            summary = "The items of a list, by product; ended items only with include_ended (issue #195)",
            operationId = "listRetailPriceListItems")
    ItemPage items(
            @PathVariable("price_list_id") UUID id,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "include_ended", required = false) Boolean includeEnded,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.items(id, productId, query, includeEnded, limit, cursor);
    }

    @PostMapping("/price-lists/{price_list_id}/items")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(
            summary = "Add a price: a product in a unit from a quantity and a date; an open ended one replaces the"
                    + " current one from its first day (issue #195)",
            operationId = "createRetailPriceListItem")
    ResponseEntity<Item> addItem(@PathVariable("price_list_id") UUID id, @Valid @RequestBody ItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addItem(id, request));
    }

    @PatchMapping("/price-lists/{price_list_id}/items/{item_id}")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(
            summary = "End an open price on a day: the only change an item takes (issue #195)",
            operationId = "endRetailPriceListItem")
    Item endItem(
            @PathVariable("price_list_id") UUID id,
            @PathVariable("item_id") UUID itemId,
            @Valid @RequestBody EndItemRequest request) {
        return service.endItem(id, itemId, request);
    }

    @GetMapping("/customer-groups")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(summary = "The customer groups (issue #195)", operationId = "listRetailCustomerGroups")
    GroupList groups() {
        return service.groups();
    }

    @PostMapping("/customer-groups")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(summary = "Create a customer group (issue #195)", operationId = "createRetailCustomerGroup")
    ResponseEntity<Group> createGroup(@Valid @RequestBody GroupRequest request) {
        Group created = service.createGroup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @PutMapping("/customer-groups/{group_id}")
    @RequiresPermission("retail.pricelist.manage")
    @Operation(
            summary = "Replace a group's name, list and switch under If-Match; never deleted (issue #195)",
            operationId = "updateRetailCustomerGroup")
    ResponseEntity<Group> updateGroup(
            @PathVariable("group_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateGroupRequest request) {
        Group g = service.updateGroup(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(g.version())).body(g);
    }
}
