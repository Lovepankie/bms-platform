package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.approvals.Approvals.Outcome;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.StoredDocument;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.collateral.CollateralPledges;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralDetail;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralPage;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralResponse;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CreateCollateralRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.Event;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.EventRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.ReleaseOutcome;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.ReleaseRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.UpdateCollateralRequest;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.Valuation;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.ValuationRequest;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The collateral register (FR-COL-01 to FR-COL-05). Every change checks the item's branch against
 * the route's permission; an item outside scope is a 404. Release is requested here and executed by
 * {@link CollateralReleaseAction} once a checker approves (chapter 8 section 8.4).
 */
@Service
class CollateralService {

    static final String SUBJECT = "lending.collateral";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    /** Statuses an item can be released from; released, seized and disposed items cannot. */
    static final Set<String> RELEASABLE = Set.of("pledged", "in_custody");

    @Schema(name = "CollateralDocument")
    record CollateralDocument(UUID documentId, String contentType, long sizeBytes, Instant createdAt) {}

    @Schema(name = "CollateralDocumentList")
    record CollateralDocumentList(List<CollateralDocument> items) {}

    private final CollateralRepository repo;
    private final MemberLookup members;
    private final TenantSettings settings;
    private final CurrentTenant currentTenant;
    private final Approvals approvals;
    private final Documents documents;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final List<CollateralPledges> pledges;

    CollateralService(
            CollateralRepository repo,
            MemberLookup members,
            TenantSettings settings,
            CurrentTenant currentTenant,
            Approvals approvals,
            Documents documents,
            AuditLog audit,
            BusinessClock clock,
            List<CollateralPledges> pledges) {
        this.repo = repo;
        this.members = members;
        this.settings = settings;
        this.currentTenant = currentTenant;
        this.approvals = approvals;
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
        this.pledges = List.copyOf(pledges);
    }

    /** FR-COL-01, FR-COL-05. The item sits in the member's home branch. */
    @Transactional
    CollateralResponse create(CreateCollateralRequest r) {
        Principal principal = CurrentPrincipal.require();
        MemberSummary member = members.find(r.memberId())
                .filter(m -> principal.may("lending.collateral.manage", m.branchId()))
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("member_id", "unknown_member", "No such member in your scope."))));
        if (settings.disabledCollateralTypes().contains(r.collateralType())) {
            throw ApiException.rule(
                    "collateral_type_disabled",
                    "This tenant does not accept " + r.collateralType() + " as collateral.");
        }
        String reference = normalise(r.referenceNo());
        requireVehiclePlate(r.collateralType(), reference);
        checkNotPledged(r.collateralType(), reference, null);
        String custody = r.custodyStatus() == null ? "pledged" : r.custodyStatus();
        String location = blankToNull(r.storageLocation());
        if (custody.equals("in_custody") && location == null) {
            throw required("storage_location", "Required when the tenant holds the item.");
        }
        CollateralResponse c = new CollateralResponse(
                UUID.randomUUID(),
                member.branchId(),
                member.id(),
                r.collateralType(),
                r.description().trim(),
                blankToNull(r.referenceNo()),
                reference,
                blankToNull(r.ownerName()),
                r.ownerRelationship() == null ? "self" : r.ownerRelationship(),
                r.estimatedValueMinor(),
                currentTenant.profile().currency(),
                custody,
                location,
                null,
                null,
                null,
                1);
        try {
            repo.insert(c, principal.userId());
        } catch (DuplicateKeyException e) {
            throw alreadyPledged();
        }
        repo.insertEvent(c.id(), event("registered", null, custody, location, null, null, clock.now(), null));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("member_id", member.id());
        after.put("collateral_type", c.collateralType());
        after.put("reference_no", c.referenceNo());
        after.put("custody_status", custody);
        audit.record(AuditLog.Entry.created("lending.collateral.registered", SUBJECT, c.id(), c.branchId(), after));
        return repo.find(c.id()).orElseThrow();
    }

    @Transactional(readOnly = true)
    CollateralPage list(
            List<UUID> branchIds,
            UUID memberId,
            List<String> types,
            List<String> statuses,
            Integer limit,
            String cursor) {
        Principal principal = CurrentPrincipal.require();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<UUID> branchFilter = principal.branchFilter("lending.collateral.read", branchIds);
        Instant afterCreated = null;
        UUID afterId = null;
        String after = Cursor.decode(cursor).orElse(null);
        if (after != null) {
            String[] parts = after.split("\\|", 2);
            afterCreated = Instant.parse(parts[0]);
            afterId = UUID.fromString(parts[1]);
        }
        List<CollateralResponse> rows =
                repo.page(branchFilter, memberId, types, statuses, afterCreated, afterId, size + 1);
        boolean more = rows.size() > size;
        List<CollateralResponse> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new CollateralPage(List.copyOf(items), next);
    }

    @Transactional(readOnly = true)
    CollateralDetail get(UUID id) {
        CollateralResponse c = inScope(id, "lending.collateral.read");
        return new CollateralDetail(c, repo.valuations(id), repo.events(id));
    }

    /** Omitted fields are unchanged; a new reference is checked again (FR-COL-01). */
    @Transactional
    CollateralResponse update(UUID id, String ifMatch, UpdateCollateralRequest r) {
        CollateralResponse before = lockForChange(id, "lending.collateral.manage", ifMatch);
        String referenceNo = r.referenceNo() == null ? before.referenceNo() : blankToNull(r.referenceNo());
        String reference = r.referenceNo() == null ? before.referenceNoNormalised() : normalise(r.referenceNo());
        if (!Objects.equals(reference, before.referenceNoNormalised())) {
            requireVehiclePlate(before.collateralType(), reference);
            checkNotPledged(before.collateralType(), reference, id);
        }
        CollateralResponse after = new CollateralResponse(
                id,
                before.branchId(),
                before.memberId(),
                before.collateralType(),
                r.description() == null ? before.description() : r.description().trim(),
                referenceNo,
                reference,
                r.ownerName() == null ? before.ownerName() : blankToNull(r.ownerName()),
                r.ownerRelationship() == null ? before.ownerRelationship() : r.ownerRelationship(),
                r.estimatedValueMinor() == null ? before.estimatedValueMinor() : r.estimatedValueMinor(),
                before.currency(),
                before.custodyStatus(),
                before.storageLocation(),
                before.collateralValueMinor(),
                before.createdAt(),
                before.updatedAt(),
                before.version());
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "description", before.description(), after.description());
        diff(was, now, "reference_no", before.referenceNo(), after.referenceNo());
        diff(was, now, "owner_name", before.ownerName(), after.ownerName());
        diff(was, now, "owner_relationship", before.ownerRelationship(), after.ownerRelationship());
        diff(was, now, "estimated_value_minor", before.estimatedValueMinor(), after.estimatedValueMinor());
        if (now.isEmpty()) {
            return before;
        }
        try {
            repo.update(after);
        } catch (DuplicateKeyException e) {
            throw alreadyPledged();
        }
        audit.record(new AuditLog.Entry("lending.collateral.updated", SUBJECT, id, before.branchId(), was, now));
        return repo.find(id).orElseThrow();
    }

    /** FR-COL-02: a valuation never lies in the future and its forced sale value never exceeds the market value. */
    @Transactional
    Valuation addValuation(UUID id, ValuationRequest r) {
        CollateralResponse c = inScope(id, "lending.collateral.manage");
        if (c.custodyStatus().equals("disposed")) {
            throw invalidTransition("A disposed item is not valued again.");
        }
        if (r.valuedOn().isAfter(clock.today(currentTenant.profile().timezone()))) {
            throw ApiException.validation(
                    List.of(new FieldProblem("valued_on", "invalid", "A valuation cannot be dated in the future.")));
        }
        if (r.forcedSaleValueMinor() != null && r.forcedSaleValueMinor() > r.marketValueMinor()) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "forced_sale_value_minor", "invalid", "The forced sale value cannot exceed the market value.")));
        }
        UUID valuationId = UUID.randomUUID();
        repo.insertValuation(valuationId, id, r, CurrentPrincipal.require().userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("valued_on", r.valuedOn().toString());
        after.put("market_value_minor", r.marketValueMinor());
        after.put("forced_sale_value_minor", r.forcedSaleValueMinor());
        audit.record(AuditLog.Entry.created("lending.collateral.valued", SUBJECT, id, c.branchId(), after));
        return repo.valuations(id).stream()
                .filter(v -> v.id().equals(valuationId))
                .findFirst()
                .orElseThrow();
    }

    /**
     * FR-COL-03: custody changes other than release. received_into_custody: pledged to in_custody;
     * moved: in_custody to a new location; seized: pledged or in_custody to seized; disposed: seized
     * to disposed; note: no change.
     */
    @Transactional
    CollateralDetail recordEvent(UUID id, String ifMatch, EventRequest r) {
        CollateralResponse before = lockForChange(id, "lending.collateral.manage", ifMatch);
        String from = before.custodyStatus();
        String location = blankToNull(r.location());
        String note = blankToNull(r.note());
        String to = switch (r.eventType()) {
            case "received_into_custody" -> transition(from, Set.of("pledged"), "in_custody");
            case "moved" -> transition(from, Set.of("in_custody"), "in_custody");
            case "seized" -> transition(from, Set.of("pledged", "in_custody"), "seized");
            case "disposed" -> transition(from, Set.of("seized"), "disposed");
            default -> from;
        };
        if ((r.eventType().equals("received_into_custody") || r.eventType().equals("moved")) && location == null) {
            throw required("location", "Where the item is now kept.");
        }
        if (r.eventType().equals("note") && note == null) {
            throw required("note", "A note event needs its note.");
        }
        Instant occurredAt = r.occurredAt() == null ? clock.now() : r.occurredAt();
        if (occurredAt.isAfter(clock.now())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("occurred_at", "invalid", "An event cannot be in the future.")));
        }
        String storage = to.equals("in_custody") ? (location != null ? location : before.storageLocation()) : null;
        if (!to.equals(from) || !Objects.equals(storage, before.storageLocation())) {
            repo.update(withCustody(before, to, storage));
        }
        repo.insertEvent(
                id,
                event(r.eventType(), from, to, location, blankToNull(r.counterpartyName()), note, occurredAt, null));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("event_type", r.eventType());
        after.put("custody_status", to);
        after.put("location", location);
        audit.record(new AuditLog.Entry(
                "lending.collateral." + r.eventType(),
                SUBJECT,
                id,
                before.branchId(),
                Map.of("custody_status", from),
                after));
        return get(id);
    }

    /**
     * FR-COL-04: release is maker-checker with no threshold. Every loan the item secures must be
     * closed, cancelled or rejected; loans arrive with increment 4, so today no loan can hold it.
     */
    @Transactional
    ReleaseOutcome requestRelease(UUID id, String ifMatch, ReleaseRequest r) {
        CollateralResponse c = lockForChange(id, "lending.collateral.release_request", ifMatch);
        if (!RELEASABLE.contains(c.custodyStatus())) {
            throw invalidTransition("An item that is " + c.custodyStatus() + " cannot be released.");
        }
        if (pledges.stream().anyMatch(p -> p.securesOpenLoan(id))) {
            throw ApiException.rule(
                    "collateral_secures_open_loan",
                    "The item secures a loan that is not closed, cancelled or rejected.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("collected_by", r.collectedBy().trim());
        if (blankToNull(r.note()) != null) {
            payload.put("note", r.note().trim());
        }
        Outcome outcome = approvals.request(
                new ActionRequest(CollateralReleaseAction.TYPE, c.branchId(), id, c.version(), null, null, payload));
        return new ReleaseOutcome(outcome.executed(), outcome.approvalId());
    }

    /** FR-COL-01: photos and scans, through the documents module. */
    @Transactional
    CollateralDocument uploadDocument(UUID id, byte[] bytes) {
        CollateralResponse c = inScope(id, "lending.collateral.manage");
        StoredDocument d = documents.upload(new Documents.Upload(SUBJECT, id, c.branchId(), bytes));
        repo.linkDocument(id, d.id());
        audit.record(AuditLog.Entry.created(
                "lending.collateral.document_added", SUBJECT, id, c.branchId(), Map.of("document_id", d.id())));
        return new CollateralDocument(d.id(), d.contentType(), d.sizeBytes(), d.createdAt());
    }

    @Transactional(readOnly = true)
    CollateralDocumentList listDocuments(UUID id) {
        inScope(id, "lending.collateral.read");
        return new CollateralDocumentList(repo.documentIds(id).stream()
                .map(documents::find)
                .flatMap(java.util.Optional::stream)
                .map(d -> new CollateralDocument(d.id(), d.contentType(), d.sizeBytes(), d.createdAt()))
                .toList());
    }

    static CollateralResponse withCustody(CollateralResponse c, String status, String storageLocation) {
        return new CollateralResponse(
                c.id(),
                c.branchId(),
                c.memberId(),
                c.collateralType(),
                c.description(),
                c.referenceNo(),
                c.referenceNoNormalised(),
                c.ownerName(),
                c.ownerRelationship(),
                c.estimatedValueMinor(),
                c.currency(),
                status,
                storageLocation,
                c.collateralValueMinor(),
                c.createdAt(),
                c.updatedAt(),
                c.version());
    }

    static Event event(
            String type,
            String from,
            String to,
            String location,
            String counterparty,
            String note,
            Instant occurredAt,
            UUID approvalId) {
        return new Event(
                UUID.randomUUID(),
                type,
                from,
                to,
                location,
                counterparty,
                note,
                occurredAt,
                CurrentPrincipal.require().userId(),
                approvalId);
    }

    private CollateralResponse inScope(UUID id, String permission) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id)
                .filter(c -> principal.may(permission, c.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    /** If-Match first, then scope (404 outside it), then version. */
    private CollateralResponse lockForChange(UUID id, String permission, String ifMatch) {
        int expected = Versions.fromIfMatch(ifMatch);
        Principal principal = CurrentPrincipal.require();
        CollateralResponse c = repo.lock(id)
                .filter(x -> principal.may(permission, x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (c.version() != expected) {
            throw Versions.conflict(c.version());
        }
        return c;
    }

    private void checkNotPledged(String type, String reference, UUID excludeId) {
        if (reference != null
                && repo.activeDuplicate(type, reference, excludeId).isPresent()) {
            throw alreadyPledged();
        }
    }

    /** Also the answer when the unique index catches a concurrent registration (FR-COL-01). */
    private static ApiException alreadyPledged() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "collateral_already_pledged",
                "Collateral already pledged",
                "An item of this type with this reference is already registered and not released.");
    }

    private static void requireVehiclePlate(String type, String reference) {
        if (type.equals("vehicle") && reference == null) {
            throw required("reference_no", "A vehicle needs its plate number.");
        }
    }

    private static String transition(String from, Set<String> allowed, String to) {
        if (!allowed.contains(from)) {
            throw invalidTransition("The item is " + from + ".");
        }
        return to;
    }

    /** Upper case, no spaces: {@code uxx 001x} and {@code UXX001X} are the same plate (FR-COL-01). */
    static String normalise(String reference) {
        String s = blankToNull(reference);
        return s == null ? null : s.replaceAll("\\s", "").toUpperCase();
    }

    private static void diff(Map<String, Object> was, Map<String, Object> now, String key, Object x, Object y) {
        if (!Objects.equals(x, y)) {
            was.put(key, x);
            now.put(key, y);
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static ApiException required(String field, String message) {
        return ApiException.validation(List.of(new FieldProblem(field, "required", message)));
    }

    static ApiException invalidTransition(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_status_transition", "Invalid status transition", detail);
    }
}
