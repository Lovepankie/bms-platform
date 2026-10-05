package com.rincoltech.bms.lending.products.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import com.rincoltech.bms.lending.products.ScheduleCalculator.Item;
import com.rincoltech.bms.lending.products.internal.ProductApi.CreateProductRequest;
import com.rincoltech.bms.lending.products.internal.ProductApi.Fee;
import com.rincoltech.bms.lending.products.internal.ProductApi.Preview;
import com.rincoltech.bms.lending.products.internal.ProductApi.PreviewItem;
import com.rincoltech.bms.lending.products.internal.ProductApi.PreviewRequest;
import com.rincoltech.bms.lending.products.internal.ProductApi.Product;
import com.rincoltech.bms.lending.products.internal.ProductApi.ProductList;
import com.rincoltech.bms.lending.products.internal.ProductApi.Terms;
import com.rincoltech.bms.lending.products.internal.ProductApi.Version;
import com.rincoltech.bms.lending.products.internal.ProductRepository.Row;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Loan products (FR-PRD-01 to FR-PRD-05). Products are tenant-wide, so the route permission is
 * the whole check (no branch scope). Terms are validated against rules R-TERM, R-RATE and R-PEN
 * of chapter 3 section 3.4 before a version is written, and a version is never changed afterwards.
 */
@Service
class ProductService {

    static final List<String> DEFAULT_ALLOCATION = List.of("penalty", "fee", "interest", "principal");
    static final LocalDate EARLIEST_PREVIEW_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate LATEST_PREVIEW_DATE = LocalDate.of(2100, 12, 31);

    private final ProductRepository repo;
    private final CurrentTenant currentTenant;
    private final AuditLog audit;
    private final ObjectMapper mapper;

    ProductService(ProductRepository repo, CurrentTenant currentTenant, AuditLog audit, ObjectMapper mapper) {
        this.repo = repo;
        this.currentTenant = currentTenant;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    ProductList list() {
        return new ProductList(repo.list().stream().map(r -> assemble(r, false)).toList());
    }

    @Transactional(readOnly = true)
    Product get(UUID id) {
        return assemble(repo.find(id).orElseThrow(ApiException::notFound), true);
    }

    /** FR-PRD-01: the product and its version 1, in one transaction. */
    @Transactional
    Product create(CreateProductRequest r) {
        if (repo.codeExists(r.code())) {
            throw duplicateCode(r.code());
        }
        UUID user = CurrentPrincipal.require().userId();
        UUID id = UUID.randomUUID();
        Version v = toVersion(r.terms(), 1);
        try {
            repo.insertProduct(id, r.code(), r.name().trim(), user);
        } catch (DuplicateKeyException e) {
            // Two creates of the same code at once: the unique key decides, with the same answer.
            throw duplicateCode(r.code());
        }
        repo.insertVersion(id, v, user);
        repo.linkFirstVersion(id, v.id());
        audit.record(
                AuditLog.Entry.created("lending.product.created", "lending.product", id, null, terms(r.code(), v)));
        return get(id);
    }

    private static ApiException duplicateCode(String code) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "duplicate_product_code",
                "Duplicate product code",
                "A loan product with code " + code + " already exists.");
    }

    /** FR-PRD-04: editing a product writes a new version; loans keep the version they were created with. */
    @Transactional
    Product newVersion(UUID id, String ifMatch, Terms terms) {
        Row product = lockForChange(id, ifMatch);
        Version v = toVersion(terms, repo.nextVersionNo(id));
        UUID user = CurrentPrincipal.require().userId();
        repo.insertVersion(id, v, user);
        repo.setCurrentVersion(id, v.id());
        audit.record(AuditLog.Entry.created(
                "lending.product.version_created", "lending.product", id, null, terms(product.code(), v)));
        return get(id);
    }

    /** FR-PRD-05: no new applications; existing loans are untouched. */
    @Transactional
    Product archive(UUID id, String ifMatch) {
        lockForChange(id, ifMatch);
        repo.archive(id);
        audit.record(new AuditLog.Entry(
                "lending.product.archived",
                "lending.product",
                id,
                null,
                Map.of("status", "active"),
                Map.of("status", "archived")));
        return get(id);
    }

    /** FR-PRD-03: the same calculator as real schedules, on terms that need not be saved yet. */
    Preview preview(PreviewRequest r) {
        if (r.disbursementDate().isBefore(EARLIEST_PREVIEW_DATE)
                || r.disbursementDate().isAfter(LATEST_PREVIEW_DATE)) {
            throw ApiException.validation(List.of(
                    new FieldProblem("disbursement_date", "invalid", "Must be between 2000-01-01 and 2100-12-31.")));
        }
        try {
            return previewOf(r);
        } catch (ArithmeticException e) {
            // The request bounds keep real inputs far from overflow; this is the last line of defence.
            throw ApiException.rule("amount_out_of_range", "The amounts are too large to compute.");
        }
    }

    private Preview previewOf(PreviewRequest r) {
        List<Fee> fees = r.fees() == null ? List.of() : r.fees();
        checkFees(fees);
        ScheduleCalculator.Terms terms = new ScheduleCalculator.Terms(
                r.interestMethod(),
                r.interestRateBp(),
                r.rateUnit(),
                r.termUnit(),
                r.termCount(),
                r.repaymentPattern(),
                frequency(r.repaymentPattern(), r.instalmentFrequency(), r.termUnit()));
        long added = feeTotal(fees, "added_to_loan", r.principalMinor());
        long deducted = feeTotal(fees, "deducted_at_disbursement", r.principalMinor());
        long upfront = feeTotal(fees, "paid_upfront", r.principalMinor());
        if (deducted >= r.principalMinor()) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "fees", "invalid", "Fees deducted at disbursement must be less than the principal.")));
        }
        List<Item> items = ScheduleCalculator.schedule(terms, r.principalMinor(), added, r.disbursementDate());
        long interest = items.stream().mapToLong(Item::interestMinor).reduce(0, Math::addExact);
        return new Preview(
                items.stream()
                        .map(i -> new PreviewItem(
                                i.no(),
                                i.dueDate(),
                                i.principalMinor(),
                                i.interestMinor(),
                                i.feeMinor(),
                                i.totalMinor()))
                        .toList(),
                r.principalMinor(),
                interest,
                added,
                Math.addExact(Math.addExact(r.principalMinor(), interest), added),
                deducted,
                upfront,
                r.principalMinor() - deducted);
    }

    private static long feeTotal(List<Fee> fees, String timing, long principalMinor) {
        return fees.stream()
                .filter(f -> f.timing().equals(timing))
                .mapToLong(f -> f.calcMethod().equals("flat")
                        ? f.amountMinor()
                        : ScheduleCalculator.percentOf(principalMinor, f.rateBp()))
                .reduce(0, Math::addExact);
    }

    private Product assemble(Row row, boolean withHistory) {
        List<Version> versions = repo.versions(row.id());
        Version current = versions.stream()
                .filter(v -> v.id().equals(row.currentVersionId()))
                .findFirst()
                .orElse(null);
        return row.with(current, withHistory ? versions : null);
    }

    private Row lockForChange(UUID id, String ifMatch) {
        int expected = Versions.fromIfMatch(ifMatch);
        Row product = repo.lock(id).orElseThrow(ApiException::notFound);
        if (product.version() != expected) {
            throw Versions.conflict(product.version());
        }
        if (product.status().equals("archived")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The product is archived.");
        }
        return product;
    }

    /** Validates the terms and fills the defaults; every refusal names its field or its rule code. */
    private Version toVersion(Terms t, int versionNo) {
        List<FieldProblem> problems = new ArrayList<>();
        ScheduleCalculator.requireRateUnitFits(t.rateUnit(), t.termUnit());
        String frequency = frequency(t.repaymentPattern(), t.instalmentFrequency(), t.termUnit());
        if (t.minTermCount() > t.defaultTermCount() || t.defaultTermCount() > t.maxTermCount()) {
            problems.add(new FieldProblem(
                    "default_term_count", "invalid", "Must satisfy min_term_count <= default <= max_term_count."));
        } else {
            // The default term must make whole instalments (for example fortnightly needs an even number of weeks).
            ScheduleCalculator.instalments(new ScheduleCalculator.Terms(
                    t.interestMethod(),
                    t.interestRateBp(),
                    t.rateUnit(),
                    t.termUnit(),
                    t.defaultTermCount(),
                    t.repaymentPattern(),
                    frequency));
        }
        if (t.minPrincipalMinor() > t.maxPrincipalMinor()) {
            problems.add(new FieldProblem("max_principal_minor", "invalid", "Must be at least min_principal_minor."));
        }
        List<String> allocation = t.allocationOrder() == null ? DEFAULT_ALLOCATION : t.allocationOrder();
        if (allocation.size() != 4 || !Set.copyOf(allocation).equals(Set.copyOf(DEFAULT_ALLOCATION))) {
            problems.add(new FieldProblem(
                    "allocation_order", "invalid", "A permutation of penalty, fee, interest, principal."));
        }
        String penalty = t.penaltyMethod() == null ? "none" : t.penaltyMethod();
        if (!penalty.equals("none") && t.penaltyPeriodUnit() == null) {
            problems.add(new FieldProblem("penalty_period_unit", "required", "Required with a penalty method."));
        }
        if (penalty.equals("flat_per_period") && t.penaltyFlatMinor() == null) {
            problems.add(new FieldProblem("penalty_flat_minor", "required", "Required for flat_per_period."));
        }
        if (penalty.equals("percent_of_overdue_per_period") && t.penaltyRateBp() == null) {
            problems.add(
                    new FieldProblem("penalty_rate_bp", "required", "Required for percent_of_overdue_per_period."));
        }
        boolean collateral = Boolean.TRUE.equals(t.requiresCollateral());
        if (collateral && t.minCollateralCoverBp() == null) {
            problems.add(new FieldProblem(
                    "min_collateral_cover_bp", "required", "Required when the product requires collateral."));
        }
        List<Fee> fees = t.fees() == null ? List.of() : t.fees();
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        checkFees(fees);
        checkFeesLeaveADisbursement(fees, t.minPrincipalMinor());
        return new Version(
                UUID.randomUUID(),
                versionNo,
                currentTenant.profile().currency(),
                t.interestMethod(),
                t.interestRateBp(),
                t.rateUnit(),
                t.termUnit(),
                t.minTermCount(),
                t.maxTermCount(),
                t.defaultTermCount(),
                t.repaymentPattern(),
                frequency,
                t.minPrincipalMinor(),
                t.maxPrincipalMinor(),
                List.copyOf(allocation),
                penalty,
                t.penaltyGraceDays() == null ? 0 : t.penaltyGraceDays(),
                penalty.equals("none") ? null : t.penaltyPeriodUnit(),
                penalty.equals("flat_per_period") ? t.penaltyFlatMinor() : null,
                penalty.equals("percent_of_overdue_per_period") ? t.penaltyRateBp() : null,
                penalty.equals("none") ? null : t.penaltyCapBp(),
                Boolean.TRUE.equals(t.flatEarlySettlementRebate()),
                collateral,
                collateral ? t.minCollateralCoverBp() : null,
                Boolean.TRUE.equals(t.requiresGuarantor()),
                List.copyOf(fees),
                null);
    }

    /** R-TERM: instalments need a frequency that fits the term unit; a bullet has none. */
    private static String frequency(String pattern, String frequency, String termUnit) {
        if (pattern.equals("bullet")) {
            return null;
        }
        if (frequency == null || !ScheduleCalculator.frequencyFitsUnit(termUnit, frequency)) {
            throw ApiException.rule(
                    "invalid_term_frequency",
                    "Instalments on a term in " + termUnit + "s cannot be " + frequency + ".");
        }
        return frequency;
    }

    /** FR-PRD-02: a flat fee carries an amount, a percentage fee carries a rate, never both. */
    private static void checkFees(List<Fee> fees) {
        List<FieldProblem> problems = new ArrayList<>();
        for (int i = 0; i < fees.size(); i++) {
            Fee f = fees.get(i);
            boolean flat = f.calcMethod().equals("flat");
            if (flat
                    ? (f.amountMinor() == null || f.rateBp() != null)
                    : (f.rateBp() == null || f.amountMinor() != null)) {
                problems.add(new FieldProblem(
                        "fees[" + i + "]",
                        "invalid",
                        flat ? "A flat fee needs amount_minor only." : "A percentage fee needs rate_bp only."));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
    }

    /**
     * FR-PRD-02: fees deducted at disbursement must leave something to disburse. The smallest
     * principal is the worst case (a flat fee does not shrink with it), so the check runs there
     * and names the fee that takes the total to the principal or past it.
     */
    private static void checkFeesLeaveADisbursement(List<Fee> fees, long minPrincipalMinor) {
        long deducted = 0;
        for (int i = 0; i < fees.size(); i++) {
            Fee f = fees.get(i);
            if (!f.timing().equals("deducted_at_disbursement")) {
                continue;
            }
            deducted = Math.addExact(
                    deducted,
                    f.calcMethod().equals("flat")
                            ? f.amountMinor()
                            : ScheduleCalculator.percentOf(minPrincipalMinor, f.rateBp()));
            if (deducted >= minPrincipalMinor) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "fees[" + i + "]",
                        "fees_exceed_principal",
                        "Fees deducted at disbursement must total less than min_principal_minor.")));
            }
        }
    }

    /** The whole version in the audit row, so a later reader can see exactly which term or fee changed. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> terms(String code, Version v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.putAll(mapper.convertValue(v, Map.class));
        m.remove("created_at");
        return m;
    }
}
