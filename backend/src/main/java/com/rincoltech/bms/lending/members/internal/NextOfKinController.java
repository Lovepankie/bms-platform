package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.CreateNextOfKinRequest;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.LinkDecisionRequest;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.NextOfKinList;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.NextOfKinResponse;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.Relationships;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.UpdateNextOfKinRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Next of kin and relationships (chapter 7 section 7.11.11; FR-MEM-06 to FR-MEM-08). */
@RestController
@RequestMapping(path = "/api/v1/lending", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-members")
class NextOfKinController {

    private final NextOfKinService service;

    NextOfKinController(NextOfKinService service) {
        this.service = service;
    }

    @GetMapping("/members/{member_id}/next-of-kin")
    @RequiresPermission("lending.members.read")
    @Operation(summary = "List a member's next of kin (FR-MEM-06)", operationId = "listNextOfKin")
    NextOfKinList list(@PathVariable("member_id") UUID memberId) {
        return service.list(memberId);
    }

    @PostMapping("/members/{member_id}/next-of-kin")
    @RequiresPermission("lending.members.update")
    @Operation(
            summary = "Add a next of kin; links a member by NIN, suggests by phone (FR-MEM-06, FR-MEM-07)",
            operationId = "createNextOfKin")
    ResponseEntity<NextOfKinResponse> create(
            @PathVariable("member_id") UUID memberId, @Valid @RequestBody CreateNextOfKinRequest request) {
        NextOfKinResponse created = service.create(memberId, request);
        return ResponseEntity.created(URI.create("/api/v1/lending/next-of-kin/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @PatchMapping("/next-of-kin/{kin_id}")
    @RequiresPermission("lending.members.update")
    @Operation(summary = "Edit a next of kin", operationId = "updateNextOfKin")
    ResponseEntity<NextOfKinResponse> update(
            @PathVariable("kin_id") UUID kinId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateNextOfKinRequest request) {
        return withETag(service.update(kinId, ifMatch, request));
    }

    @DeleteMapping("/next-of-kin/{kin_id}")
    @RequiresPermission("lending.members.update")
    @Operation(
            summary = "Remove a next of kin; the last one of a KYC-complete member stays",
            operationId = "deleteNextOfKin")
    ResponseEntity<Void> delete(
            @PathVariable("kin_id") UUID kinId, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        service.delete(kinId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/next-of-kin/{kin_id}/link")
    @RequiresPermission("lending.members.update")
    @Operation(summary = "Confirm or reject a suggested link (FR-MEM-07)", operationId = "decideNextOfKinLink")
    ResponseEntity<NextOfKinResponse> link(
            @PathVariable("kin_id") UUID kinId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody LinkDecisionRequest request) {
        return withETag(service.decideLink(kinId, ifMatch, request));
    }

    @GetMapping("/members/{member_id}/relationships")
    @RequiresPermission("lending.members.read")
    @Operation(summary = "The member's relationship panel (FR-MEM-08)", operationId = "getMemberRelationships")
    Relationships relationships(@PathVariable("member_id") UUID memberId) {
        return service.relationships(memberId);
    }

    private static ResponseEntity<NextOfKinResponse> withETag(NextOfKinResponse k) {
        return ResponseEntity.ok().eTag(String.valueOf(k.version())).body(k);
    }
}
