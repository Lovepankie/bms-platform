package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Advance;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.AdvancePage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.AdvanceRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Repayment;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.RepaymentRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.VoidRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.AdvanceRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.PartyRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.RepaymentRow;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailIdempotency;
import com.rincoltech.bms.retail.stock.RetailIdempotency.Outcome;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances to the owner or a related party and their repayments (FR-RET-26; ADR-022 decisions 2
 * and 6). Not lending: no schedule, interest or member. An advance is a receivable; its balance is
 * the principal less {@code repaid_minor}, a stored running total that moves only with a repayment
 * insert or void, in the same transaction and under the advance's row lock.
 */
@Service
class AdvanceService {

    static final String PATH = "/api/v1/retail/advances";
    static final Set<String> ADVANCE_PARTY_KINDS = Set.of("owner", "staff", "related_entity");
    static final Set<String> METHODS = Set.of("cash", "mobile_money", "bank");

    private final CashbookRepository repo;
    private final CashbookSupport support;
    private final RetailBooks books;
    private final RetailIdempotency idempotency;
    private final RetailBranchContext branchContext;
    private final TenantSequences sequences;
    private final AuditLog audit;

    AdvanceService(
            CashbookRepository repo,
            CashbookSupport support,
            RetailBooks books,
            RetailIdempotency idempotency,
            RetailBranchContext branchContext,
            TenantSequences sequences,
            AuditLog audit) {
        this.repo = repo;
        this.support = support;
        this.books = books;
        this.idempotency = idempotency;
        this.branchContext = branchContext;
        this.sequences = sequences;
        this.audit = audit;
    }

    @Transactional
    Outcome<Advance> create(String key, AdvanceRequest r) {
        return idempotency.once(key, "POST", PATH, r, Advance.class, () -> record(r));
    }

    private Advance record(AdvanceRequest r) {
        UUID branch = branchContext.resolve(CashbookSupport.ADVANCE_CREATE, r.branchId());
        LocalDate date = support.businessDate(r.businessDate(), "business_date");
        PartyRow party = repo.party(r.partyId())
                .filter(PartyRow::active)
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("party_id", "unknown_party", "No such active party."))));
        if (!ADVANCE_PARTY_KINDS.contains(party.kind())) {
            throw ApiException.rule(
                    "party_kind_not_allowed", "An advance goes to the owner, a staff member or a related entity.");
        }
        if (r.takenByPartyId() != null
                && repo.party(r.takenByPartyId()).filter(PartyRow::active).isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("taken_by_party_id", "unknown_party", "No such active party.")));
        }
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        long principal = r.principalMinor();
        String no = "RA%08d".formatted(sequences.next("retail_advance_no"));
        UUID entry = books.post(CashPostings.advance(branch, date, id, principal))
                .map(PostedEntry::entryId)
                .orElseThrow();
        repo.insertAdvance(
                id,
                no,
                branch,
                date,
                party.id(),
                r.takenByPartyId(),
                principal,
                support.currency(),
                CashbookSupport.blankToNull(r.purpose()),
                support.now(),
                by,
                entry);
        audit.record(AuditLog.Entry.created(
                "retail.advance.created",
                "retail.advance",
                id,
                branch,
                Map.of("advance_no", no, "business_date", date.toString(), "principal_minor", principal)));
        return view(repo.advance(id, false).orElseThrow(), null);
    }

    @Transactional(readOnly = true)
    AdvancePage list(
            List<UUID> branchIds,
            UUID partyId,
            boolean openOnly,
            LocalDate from,
            LocalDate to,
            Integer limit,
            String cursor) {
        List<UUID> filter = CurrentPrincipal.require().branchFilter(CashbookSupport.READ, branchIds);
        int size = support.limit(limit);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<AdvanceRow> rows = repo.advancePage(
                filter,
                partyId,
                openOnly,
                from,
                to,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<AdvanceRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new AdvancePage(items.stream().map(a -> view(a, null)).toList(), next);
    }

    @Transactional(readOnly = true)
    Advance get(UUID id) {
        Principal principal = CurrentPrincipal.require();
        AdvanceRow row = repo.advance(id, false)
                .filter(a -> principal.may(CashbookSupport.READ, a.branchId()))
                .orElseThrow(ApiException::notFound);
        return view(row, repo.repayments(id).stream().map(x -> view(x, null)).toList());
    }

    static Advance view(AdvanceRow a, List<Repayment> repayments) {
        return new Advance(
                a.id(),
                a.advanceNo(),
                a.branchId(),
                a.businessDate(),
                a.partyId(),
                a.partyName(),
                a.takenByPartyId(),
                a.takenByName(),
                a.currency(),
                a.principalMinor(),
                a.repaidMinor(),
                a.principalMinor() - a.repaidMinor(),
                a.purpose(),
                a.note(),
                repayments,
                a.by(),
                a.byName(),
                a.createdAt(),
                a.voided().voided(),
                a.voided().at(),
                a.voided().reason(),
                a.historical());
    }

    static Repayment view(RepaymentRow p, Long balance) {
        return new Repayment(
                p.id(),
                p.advanceId(),
                p.branchId(),
                p.currency(),
                p.amountMinor(),
                p.method(),
                p.paidOn(),
                balance,
                p.by(),
                p.byName(),
                p.createdAt(),
                p.voided().voided(),
                p.voided().at(),
                p.voided().reason(),
                p.historical());
    }

    // ----------------------------------------------------------------------------- repayments

    @Transactional
    Outcome<Repayment> repay(String key, UUID advanceId, RepaymentRequest r) {
        return idempotency.once(
                key, "POST", PATH + "/" + advanceId + "/repayments", r, Repayment.class, () -> doRepay(advanceId, r));
    }

    private Repayment doRepay(UUID advanceId, RepaymentRequest r) {
        Principal principal = CurrentPrincipal.require();
        if (!METHODS.contains(r.method())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("method", "invalid", "Method is cash, mobile_money or bank.")));
        }
        // The advance's row lock serialises concurrent repayments: the balance is read after it.
        AdvanceRow advance = repo.advance(advanceId, true)
                .filter(a -> principal.canSeeBranch(a.branchId())
                        && (principal.may(CashbookSupport.READ, a.branchId())
                                || principal.may(CashbookSupport.ADVANCE_REPAY, a.branchId())))
                .orElseThrow(ApiException::notFound);
        UUID branch = branchContext.resolve(
                CashbookSupport.ADVANCE_REPAY, r.branchId() == null ? advance.branchId() : r.branchId());
        if (advance.voided().voided()) {
            throw CashbookSupport.alreadyVoided();
        }
        LocalDate paidOn = support.businessDate(r.paidOn(), "paid_on");
        if (paidOn.isBefore(advance.businessDate())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("paid_on", "invalid", "A repayment cannot be dated before the advance.")));
        }
        long balance = advance.principalMinor() - advance.repaidMinor();
        if (balance <= 0) {
            throw ApiException.rule("advance_settled", "This advance has been repaid in full.");
        }
        long amount = r.amountMinor();
        if (amount > balance) {
            throw ApiException.rule(
                    "repayment_exceeds_balance", "The repayment is more than the balance still owed on this advance.");
        }
        UUID id = UUID.randomUUID();
        UUID entry = books.post(CashPostings.repayment(branch, paidOn, id, advanceId, r.method(), amount))
                .map(PostedEntry::entryId)
                .orElseThrow();
        repo.insertRepayment(
                id, advanceId, branch, amount, support.currency(), r.method(), paidOn, principal.userId(), entry);
        repo.addRepaid(advanceId, amount);
        audit.record(AuditLog.Entry.created(
                "retail.advance_repayment.created",
                "retail.advance_repayment",
                id,
                branch,
                Map.of(
                        "advance_no", advance.advanceNo(),
                        "method", r.method(),
                        "paid_on", paidOn.toString(),
                        "amount_minor", amount)));
        return view(repo.repayment(id, false).orElseThrow(), balance - amount);
    }

    @Transactional
    Outcome<Advance> voidAdvance(String key, UUID id, VoidRequest r) {
        return idempotency.once(key, "POST", PATH + "/" + id + "/void", r, Advance.class, () -> {
            Principal principal = CurrentPrincipal.require();
            AdvanceRow row = repo.advance(id, true)
                    .filter(x -> principal.may(CashbookSupport.VOID, x.branchId()))
                    .orElseThrow(ApiException::notFound);
            if (row.voided().voided()) {
                throw CashbookSupport.alreadyVoided();
            }
            if (repo.nonVoidedRepayments(id) > 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "advance_has_repayments",
                        "Advance has repayments",
                        "Void its repayments first, then this advance.");
            }
            String reason = CashbookSupport.reason(r);
            if (row.journalEntryId() != null) {
                books.reverse(
                        row.journalEntryId(),
                        support.today(),
                        CashPostings.ref("Void advance", id),
                        CashPostings.voidKey(CashPostings.ADVANCE, id));
            }
            repo.markVoided("retail_advances", id, principal.userId(), reason);
            audit.record(new AuditLog.Entry(
                    "retail.advance.voided",
                    "retail.advance",
                    id,
                    row.branchId(),
                    Map.of("advance_no", row.advanceNo()),
                    Map.of("reason", reason)));
            return view(repo.advance(id, false).orElseThrow(), null);
        });
    }

    @Transactional
    Outcome<Repayment> voidRepayment(String key, UUID advanceId, UUID repaymentId, VoidRequest r) {
        return idempotency.once(
                key,
                "POST",
                PATH + "/" + advanceId + "/repayments/" + repaymentId + "/void",
                r,
                Repayment.class,
                () -> {
                    Principal principal = CurrentPrincipal.require();
                    // Lock order is always the advance, then its repayment.
                    repo.advance(advanceId, true).orElseThrow(ApiException::notFound);
                    RepaymentRow row = repo.repayment(repaymentId, true)
                            .filter(x -> x.advanceId().equals(advanceId)
                                    && principal.may(CashbookSupport.VOID, x.branchId()))
                            .orElseThrow(ApiException::notFound);
                    if (row.voided().voided()) {
                        throw CashbookSupport.alreadyVoided();
                    }
                    String reason = CashbookSupport.reason(r);
                    if (row.journalEntryId() != null) {
                        books.reverse(
                                row.journalEntryId(),
                                support.today(),
                                CashPostings.ref("Void repayment", repaymentId),
                                CashPostings.voidKey(CashPostings.REPAYMENT, repaymentId));
                    }
                    repo.markVoided("retail_advance_repayments", repaymentId, principal.userId(), reason);
                    repo.addRepaid(advanceId, -row.amountMinor());
                    audit.record(new AuditLog.Entry(
                            "retail.advance_repayment.voided",
                            "retail.advance_repayment",
                            repaymentId,
                            row.branchId(),
                            Map.of("advance_id", advanceId.toString()),
                            Map.of("reason", reason)));
                    return view(repo.repayment(repaymentId, false).orElseThrow(), null);
                });
    }
}
