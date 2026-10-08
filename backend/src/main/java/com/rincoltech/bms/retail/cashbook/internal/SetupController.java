package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.CategoryPatch;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.CategoryRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseCategory;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseCategoryList;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseItem;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ItemPatch;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ItemRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Party;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.PartyPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.PartyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Expense setup and cash parties (chapter 7 section 7.11.21; FR-RET-17). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-cashbook")
class SetupController {

    private final SetupService service;

    SetupController(SetupService service) {
        this.service = service;
    }

    @GetMapping("/expense-categories")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Expense categories with their items", operationId = "listRetailExpenseCategories")
    ExpenseCategoryList categories(@RequestParam(name = "active", required = false) Boolean active) {
        return service.categories(active);
    }

    @PostMapping("/expense-categories")
    @RequiresPermission("retail.expense.manage")
    @Operation(summary = "Add an expense category (FR-RET-17)", operationId = "createRetailExpenseCategory")
    ResponseEntity<ExpenseCategory> createCategory(@Valid @RequestBody CategoryRequest request) {
        ExpenseCategory c = service.createCategory(request);
        return ResponseEntity.created(URI.create("/api/v1/retail/expense-categories/" + c.id()))
                .eTag("\"" + c.version() + "\"")
                .body(c);
    }

    @PatchMapping("/expense-categories/{category_id}")
    @RequiresPermission("retail.expense.manage")
    @Operation(
            summary = "Rename, remap, switch off or reorder a category; needs If-Match",
            operationId = "updateRetailExpenseCategory")
    ResponseEntity<ExpenseCategory> patchCategory(
            @PathVariable("category_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody CategoryPatch patch) {
        ExpenseCategory c = service.patchCategory(id, ifMatch, patch);
        return ResponseEntity.ok().eTag("\"" + c.version() + "\"").body(c);
    }

    @PostMapping("/expense-categories/{category_id}/items")
    @RequiresPermission("retail.expense.manage")
    @Operation(summary = "Add an item to a category", operationId = "createRetailExpenseItem")
    ResponseEntity<ExpenseItem> createItem(
            @PathVariable("category_id") UUID categoryId, @Valid @RequestBody ItemRequest request) {
        ExpenseItem i = service.createItem(categoryId, request);
        return ResponseEntity.created(
                        URI.create("/api/v1/retail/expense-categories/" + categoryId + "/items/" + i.id()))
                .eTag("\"" + i.version() + "\"")
                .body(i);
    }

    @PatchMapping("/expense-categories/{category_id}/items/{item_id}")
    @RequiresPermission("retail.expense.manage")
    @Operation(
            summary = "Rename, switch off or flag an item as needing an explanation; needs If-Match",
            operationId = "updateRetailExpenseItem")
    ResponseEntity<ExpenseItem> patchItem(
            @PathVariable("category_id") UUID categoryId,
            @PathVariable("item_id") UUID itemId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ItemPatch patch) {
        ExpenseItem i = service.patchItem(categoryId, itemId, ifMatch, patch);
        return ResponseEntity.ok().eTag("\"" + i.version() + "\"").body(i);
    }

    @GetMapping("/cash-parties")
    @RequiresPermission("retail.cashbook.read")
    @Operation(summary = "Beneficiaries and advance parties", operationId = "listRetailCashParties")
    PartyPage parties(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.parties(query, kind, limit, cursor);
    }

    @PostMapping("/cash-parties")
    @RequiresPermission("retail.expense.record")
    @Operation(summary = "Add a beneficiary or advance party on the fly", operationId = "createRetailCashParty")
    ResponseEntity<Party> createParty(@Valid @RequestBody PartyRequest request) {
        Party p = service.createParty(request);
        return ResponseEntity.created(URI.create("/api/v1/retail/cash-parties/" + p.id()))
                .body(p);
    }
}
