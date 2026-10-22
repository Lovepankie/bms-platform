package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Barcode;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.BarcodeList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.BarcodeMatch;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.BarcodeRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The barcodes of products (ADR-070, issue #345; FR-RET-68): several per product, each for the base unit or a pack
 * size, unique per tenant among active products, looked up when a scanner or the camera reads one. A code held by a
 * product that is switched off is released, with an audit row, when an active product takes it. Barcodes are a part
 * of the product like its packs: changing them does not move the product's version.
 */
@Service
class BarcodeService {

    static final String BARCODE = "retail.barcode";
    static final String SEQUENCE = "retail_barcode";
    static final int MAX_GENERATE = 200;

    private final CatalogueRepository catalogue;
    private final BarcodeRepository repo;
    private final CatalogueService products;
    private final TenantSequences sequences;
    private final AuditLog audit;

    BarcodeService(
            CatalogueRepository catalogue,
            BarcodeRepository repo,
            CatalogueService products,
            TenantSequences sequences,
            AuditLog audit) {
        this.catalogue = catalogue;
        this.repo = repo;
        this.products = products;
        this.sequences = sequences;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    BarcodeList list(UUID productId) {
        catalogue.find(productId).orElseThrow(ApiException::notFound);
        return new BarcodeList(repo.of(productId));
    }

    @Transactional
    Barcode add(UUID productId, BarcodeRequest r) {
        Product product = catalogue.lock(productId).orElseThrow(ApiException::notFound);
        Barcodes.Result result = Barcodes.check(r.code(), r.format());
        if (!result.ok()) {
            throw ApiException.validation(List.of(new FieldProblem("code", "invalid_barcode", result.problem())));
        }
        if (r.unitId() != null) {
            boolean packOk = products.unitsOn()
                    && catalogue
                            .pack(productId, r.unitId())
                            .map(CatalogueApi.Pack::active)
                            .orElse(false);
            if (!packOk) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "unit_id", "unknown_pack", "This item has no pack of that unit switched on.")));
            }
        }
        Barcodes.Checked checked = result.checked();
        release(checked, product);
        UUID id = UUID.randomUUID();
        try {
            repo.insert(
                    id,
                    productId,
                    r.unitId(),
                    checked.code(),
                    checked.format().key,
                    false,
                    CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw taken();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("product_id", productId);
        after.put("code", checked.code());
        after.put("format", checked.format().key);
        if (r.unitId() != null) {
            after.put("unit_id", r.unitId());
        }
        audit.record(AuditLog.Entry.created("retail.barcode.added", BARCODE, id, null, after));
        return repo.of(productId).stream()
                .filter(b -> b.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Frees a code for {@code taker}: refused while an active product (or the taker itself) holds it, deleted with an
     * audit row while a product that is switched off holds it. The switched-off product loses it for good: switching it
     * back on does not bring the code back.
     */
    private void release(Barcodes.Checked code, Product taker) {
        BarcodeRepository.Held held = repo.byKey(Barcodes.key(code)).orElse(null);
        if (held == null) {
            return;
        }
        if (held.productId().equals(taker.id())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "duplicate_barcode", "Duplicate", "This item has this barcode already.");
        }
        if (held.productActive() || !taker.active()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "barcode_taken",
                    "Duplicate",
                    "This barcode is on another item already: " + held.productDescription() + " (code "
                            + held.productCode()
                            + "). Remove it there first, or switch that item off: a code taken from a"
                            + " switched-off item is removed there for good.");
        }
        repo.delete(held.barcode().id());
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("product_id", held.productId());
        was.put("code", held.barcode().code());
        audit.record(new AuditLog.Entry(
                "retail.barcode.released", BARCODE, held.barcode().id(), null, was, Map.of("product_id", taker.id())));
    }

    @Transactional
    void remove(UUID productId, UUID barcodeId) {
        catalogue.lock(productId).orElseThrow(ApiException::notFound);
        Barcode b = repo.of(productId).stream()
                .filter(x -> x.id().equals(barcodeId))
                .findFirst()
                .orElseThrow(ApiException::notFound);
        repo.delete(b.id());
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("product_id", productId);
        was.put("code", b.code());
        audit.record(new AuditLog.Entry("retail.barcode.removed", BARCODE, b.id(), null, was, Map.of()));
    }

    /**
     * A Code 128 code made by the system for each product, for its labels (IN and eight digits from the tenant's
     * sequence); a product that has a made code already keeps it, so a second run makes nothing new.
     */
    @Transactional
    BarcodeList generate(List<UUID> productIds) {
        if (productIds.size() > MAX_GENERATE) {
            throw ApiException.validation(List.of(
                    new FieldProblem("product_ids", "too_many", "At most " + MAX_GENERATE + " items at a time.")));
        }
        UUID by = CurrentPrincipal.require().userId();
        List<Barcode> made = new ArrayList<>();
        // Locked in a fixed order, so two runs over overlapping lists never deadlock.
        for (UUID productId : productIds.stream().distinct().sorted().toList()) {
            catalogue.lock(productId).orElseThrow(ApiException::notFound);
            Barcode existing = repo.generatedOf(productId).orElse(null);
            if (existing != null) {
                made.add(existing);
                continue;
            }
            // A person may have typed a code of the same shape by hand, or typed it a moment ago in another session;
            // the sequence then moves past it and the next number is tried.
            UUID id = UUID.randomUUID();
            String code;
            do {
                code = Barcodes.generated(sequences.next(SEQUENCE));
            } while (repo.byKey(code).isPresent()
                    || !repo.insertGenerated(id, productId, code, Barcodes.Format.CODE128.key, by));
            audit.record(AuditLog.Entry.created(
                    "retail.barcode.generated", BARCODE, id, null, Map.of("product_id", productId, "code", code)));
            made.add(repo.generatedOf(productId).orElseThrow());
        }
        return new BarcodeList(made);
    }

    /**
     * The active product a scanned code belongs to; with {@code branchId}, with that branch's balance. A code on a pack
     * that is switched off, or on any pack while the units capability is off, is 404 {@code pack_inactive}.
     */
    @Transactional(readOnly = true)
    BarcodeMatch lookup(String code, UUID branchId) {
        if (branchId != null && !CurrentPrincipal.require().may("retail.stock.read", branchId)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("branch_id", "unknown_branch", "No such branch in your scope.")));
        }
        String clean = code == null ? "" : code.strip();
        BarcodeRepository.Held held = clean.isEmpty() || clean.length() > Barcodes.MAX_LENGTH
                ? null
                : Barcodes.scanKeys(clean).stream()
                        .flatMap(k -> repo.byKey(k).stream())
                        .filter(BarcodeRepository.Held::productActive)
                        .findFirst()
                        .orElse(null);
        if (held == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "barcode_not_found", "Not found", "No item has this barcode.");
        }
        UUID unitId = held.barcode().unitId();
        if (unitId != null) {
            // A pack barcode answers only while its pack can be used; never as one base unit in its place.
            if (!products.unitsOn()) {
                throw packInactive("This barcode is on a pack size, and pack sizes are switched off for this business."
                        + " Scan the item's own barcode, or find it by name.");
            }
            boolean packOn = catalogue
                    .pack(held.productId(), unitId)
                    .map(CatalogueApi.Pack::active)
                    .orElse(false);
            if (!packOn) {
                throw packInactive(
                        "This barcode is on the " + held.barcode().unit() + " of " + held.productDescription()
                                + ", which is switched off. Switch the pack on, or scan the item's own barcode.");
            }
        }
        Product product = catalogue.findAt(held.productId(), branchId).orElseThrow(ApiException::notFound);
        return new BarcodeMatch(
                products.detailed(product), held.barcode(), held.barcode().unitId());
    }

    private static ApiException packInactive(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "pack_inactive", "Not found", detail);
    }

    private static ApiException taken() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "barcode_taken",
                "Duplicate",
                "Another change put this barcode on an item at the same moment. Look it up and try again.");
    }
}
