package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.CreateProductRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Preview;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.PreviewRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Product;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.ProductList;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.ProductTerms;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.SchedulePeriod;
import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.Period;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Investment products (FR-INV-01, FR-INV-08): create, edit with {@code If-Match}, archive, and the
 * return preview of R-INV-1 to R-INV-4 for terms before anything is opened. An edit changes only
 * investments opened afterwards; every investment keeps the terms it was opened on.
 */
@Service
class InvestmentProductService {

    private final InvestmentRepository repo;
    private final CurrentTenant currentTenant;
    private final InvestmentServicer servicer;
    private final AuditLog audit;

    InvestmentProductService(
            InvestmentRepository repo, CurrentTenant currentTenant, InvestmentServicer servicer, AuditLog audit) {
        this.repo = repo;
        this.currentTenant = currentTenant;
        this.servicer = servicer;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    ProductList list() {
        return new ProductList(repo.products());
    }

    @Transactional(readOnly = true)
    Product get(UUID id) {
        return repo.product(id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    Product create(CreateProductRequest r) {
        validate(r.terms());
        String code = r.code().trim();
        if (repo.productCodeTaken(code)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "duplicate_code",
                    "Duplicate code",
                    "A product already has code " + code + ".");
        }
        UUID id = UUID.randomUUID();
        repo.insertProduct(
                id,
                code,
                r.name().trim(),
                currentTenant.profile().currency(),
                r.terms(),
                CurrentPrincipal.require().userId());
        Product p = repo.product(id).orElseThrow();
        audit.record(AuditLog.Entry.created(
                "lending.investment_product.created", "lending.investment_product", id, null, snapshot(p)));
        return p;
    }

    @Transactional
    Product update(UUID id, String ifMatch, InvestmentApi.UpdateProductRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Product before = repo.lockProduct(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        if (!before.status().equals("active")) {
            throw ApiException.rule("product_archived", "An archived product is not edited.");
        }
        validate(r.terms());
        repo.updateProduct(id, r.name().trim(), r.terms());
        Product after = repo.product(id).orElseThrow();
        audit.record(new AuditLog.Entry(
                "lending.investment_product.updated",
                "lending.investment_product",
                id,
                null,
                snapshot(before),
                snapshot(after)));
        return after;
    }

    @Transactional
    Product archive(UUID id, String ifMatch) {
        int expected = Versions.fromIfMatch(ifMatch);
        Product before = repo.lockProduct(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        if (before.status().equals("archived")) {
            return before;
        }
        repo.archiveProduct(id);
        audit.record(new AuditLog.Entry(
                "lending.investment_product.archived",
                "lending.investment_product",
                id,
                null,
                Map.of("status", "active"),
                Map.of("status", "archived")));
        return repo.product(id).orElseThrow();
    }

    /** R-INV-1 to R-INV-4 for an amount and term on a product, without opening anything. */
    @Transactional(readOnly = true)
    Preview preview(PreviewRequest r) {
        Product p = repo.product(r.productId()).orElseThrow(ApiException::notFound);
        checkTerm(p, r.termMonths());
        InvestmentServicer.checkAmount(p, r.amountMinor());
        LocalDate start = r.startDate() != null ? r.startDate() : servicer.today();
        List<Period> periods = InvestmentReturns.schedule(
                new InvestmentReturns.Terms(
                        r.amountMinor(), p.returnRateBp(), p.returnMethod(), r.termMonths(), p.payoutFrequency()),
                start);
        long total = InvestmentReturns.total(periods);
        return new Preview(
                p.currency(),
                r.amountMinor(),
                r.termMonths(),
                p.returnRateBp(),
                p.returnMethod(),
                p.payoutFrequency(),
                start,
                periods.getLast().end(),
                total,
                r.amountMinor() + total,
                rows(periods, null));
    }

    /** Schedule rows with their running total; {@code statuses} by period number, or null for a preview. */
    static List<SchedulePeriod> rows(List<Period> periods, Map<Integer, String> statuses) {
        List<SchedulePeriod> rows = new ArrayList<>();
        long cumulative = 0;
        for (Period p : periods) {
            cumulative += p.returnMinor();
            rows.add(new SchedulePeriod(
                    p.no(),
                    p.start(),
                    p.end(),
                    p.openingBalanceMinor(),
                    p.returnMinor(),
                    cumulative,
                    p.payout(),
                    statuses == null ? "scheduled" : statuses.get(p.no())));
        }
        return rows;
    }

    static void checkTerm(Product p, int termMonths) {
        if (!p.allowedTermsMonths().contains(termMonths)) {
            throw ApiException.rule(
                    "term_not_offered",
                    "The product offers terms of " + p.allowedTermsMonths() + " months, not " + termMonths + ".");
        }
    }

    /** FR-INV-01 combinations a field annotation cannot express, each with its rule code. */
    private static void validate(ProductTerms t) {
        List<FieldProblem> problems = new ArrayList<>();
        if (t.maxAmountMinor() != null && t.maxAmountMinor() < t.minAmountMinor()) {
            problems.add(new FieldProblem("terms.max_amount_minor", "invalid", "The maximum is below the minimum."));
        }
        if (t.returnMethod().equals("compound") && !t.payoutFrequency().equals("at_maturity")) {
            problems.add(new FieldProblem(
                    "terms.return_method",
                    "compounding_needs_maturity_payout",
                    "A compounding return is paid at maturity (R-INV-3)."));
        }
        if (t.earlyWithdrawalAllowed()) {
            if (t.earlyWithdrawalRule() == null) {
                problems.add(new FieldProblem(
                        "terms.early_withdrawal_rule", "required", "Name the rule when early withdrawal is allowed."));
            } else if (t.earlyWithdrawalRule().equals("reduced_rate")) {
                if (t.earlyWithdrawalRateBp() == null) {
                    problems.add(new FieldProblem(
                            "terms.early_withdrawal_rate_bp", "required", "The reduced rate is required."));
                } else if (t.earlyWithdrawalRateBp() > t.returnRateBp()) {
                    problems.add(new FieldProblem(
                            "terms.early_withdrawal_rate_bp",
                            "invalid",
                            "The reduced rate is at most the return rate."));
                }
            }
        } else if (t.earlyWithdrawalRule() != null
                || t.earlyWithdrawalRateBp() != null
                || (t.earlyWithdrawalPenaltyBp() != null && t.earlyWithdrawalPenaltyBp() > 0)) {
            problems.add(new FieldProblem(
                    "terms.early_withdrawal_rule",
                    "invalid",
                    "Early withdrawal terms need early_withdrawal_allowed true."));
        }
        if (t.allowedTermsMonths().stream().distinct().count()
                != t.allowedTermsMonths().size()) {
            problems.add(new FieldProblem("terms.allowed_terms_months", "invalid", "Each term once."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
    }

    private static Map<String, Object> snapshot(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("product_type", p.productType());
        m.put("allowed_terms_months", p.allowedTermsMonths());
        m.put("return_rate_bp", p.returnRateBp());
        m.put("return_method", p.returnMethod());
        m.put("payout_frequency", p.payoutFrequency());
        m.put("min_amount_minor", p.minAmountMinor());
        m.put("max_amount_minor", p.maxAmountMinor());
        m.put("early_withdrawal_allowed", p.earlyWithdrawalAllowed());
        m.put("early_withdrawal_rule", p.earlyWithdrawalRule());
        m.put("early_withdrawal_rate_bp", p.earlyWithdrawalRateBp());
        m.put("early_withdrawal_penalty_bp", p.earlyWithdrawalPenaltyBp());
        m.put("status", p.status());
        return m;
    }
}
