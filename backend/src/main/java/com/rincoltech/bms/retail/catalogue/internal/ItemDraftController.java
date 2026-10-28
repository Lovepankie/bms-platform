package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.internal.ItemDraftApi.ItemDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /api/v1/retail/products/import/draft} (chapter 7, issue #626, ADR-102): the setup helper reads a typed list,
 * a photo or a file into draft rows of the item import. Not transactional: the providers may take their budget, and no
 * connection is held meanwhile. It saves nothing; the import's own check and Add do.
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail")
class ItemDraftController {

    private final ItemDraftService service;

    ItemDraftController(ItemDraftService service) {
        this.service = service;
    }

    @PostMapping(path = "/products/import/draft", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary =
                    "Draft the item import from a typed list, a photo, or a CSV, Excel or PDF file (the setup helper);"
                            + " saves nothing; administrators only; 403 setup_ai_off while it is off for the business",
            operationId = "draftRetailProductImport")
    ItemDraft draft(
            @Parameter(description = "The list as typed; leave it out when a file is sent")
                    @RequestParam(name = "text", required = false)
                    String text,
            @Parameter(description = "A photo, or a CSV, Excel (.xlsx) or PDF file, 5 MB at most")
                    @RequestPart(name = "file", required = false)
                    MultipartFile file,
            @Parameter(description = "The kind of shop chosen in Set up your shop, such as Hardware")
                    @RequestParam(name = "shop_type", required = false)
                    String shopType) {
        return service.draft(text, file, shopType);
    }
}
