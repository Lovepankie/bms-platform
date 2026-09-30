package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.members.internal.MemberDocumentService.MemberDocument;
import com.rincoltech.bms.lending.members.internal.MemberDocumentService.MemberDocumentList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /api/v1/lending/members/{member_id}/documents} (chapter 7 section 7.11.11; FR-MEM-09). */
@RestController
@RequestMapping(path = "/api/v1/lending/members/{member_id}/documents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-members")
class MemberDocumentController {

    private final MemberDocumentService service;

    MemberDocumentController(MemberDocumentService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("lending.members.read")
    @Operation(summary = "List a member's documents", operationId = "listMemberDocuments")
    MemberDocumentList list(@PathVariable("member_id") UUID memberId) {
        return service.list(memberId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("lending.members.update")
    @Operation(
            summary = "Upload a member document: JPEG, PNG or PDF, 5 MB (FR-MEM-09)",
            operationId = "uploadMemberDocument")
    ResponseEntity<MemberDocument> upload(
            @PathVariable("member_id") UUID memberId,
            @RequestParam("doc_kind") String docKind,
            @RequestPart("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "The upload could not be read.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(memberId, docKind, bytes));
    }
}
