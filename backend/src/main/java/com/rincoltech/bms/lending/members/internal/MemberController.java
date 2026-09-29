package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.members.internal.MemberApi.CreateMemberRequest;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberPage;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/lending/members} (chapter 7 section 7.11.11). */
@RestController
@RequestMapping(path = "/api/v1/lending/members", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-members")
class MemberController {

    private final MemberService service;

    MemberController(MemberService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermission("lending.members.create")
    @Operation(summary = "Register a member (FR-MEM-01)", operationId = "createMember")
    ResponseEntity<MemberResponse> create(@Valid @RequestBody CreateMemberRequest request) {
        MemberResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/lending/members/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping
    @RequiresPermission("lending.members.read")
    @Operation(summary = "List members in the caller's branch scope (FR-MEM-11)", operationId = "listMembers")
    MemberPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(branchIds, statuses == null ? List.of() : statuses, q, limit, cursor);
    }

    @GetMapping("/{member_id}")
    @RequiresPermission("lending.members.read")
    @Operation(summary = "Get one member", operationId = "getMember")
    ResponseEntity<MemberResponse> get(@PathVariable("member_id") UUID memberId) {
        MemberResponse member = service.get(memberId);
        return ResponseEntity.ok().eTag(String.valueOf(member.version())).body(member);
    }
}
