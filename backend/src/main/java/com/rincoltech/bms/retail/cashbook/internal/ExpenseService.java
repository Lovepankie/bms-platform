package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.cashbook.CashBookHistory;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Expense;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpensePage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.VoidRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.CategoryRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.ExpenseRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.ItemRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.PartyRow;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company expenses (FR-RET-24; ADR-022 decision 5): a debit to the category's expense account
 * (operating expenses when unmapped) and a credit to cash on hand. The category and item names are
 * snapshots, so a rename never changes an old record.
 */
@Service
class ExpenseService {

    /** The subject type of a document uploaded as an expense receipt; member and collateral files never fit. */
    static final String RECEIPT_SUBJECT = "retail.expense_receipt";

    static final String PATH = "/api/v1/retail/expenses";
    static final int MIN_EXPLANATION = CashBookHistory.MIN_EXPLANATION;

    private final CashbookRepository repo;
    private final CashbookSupport support;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailBranchContext branchContext;
    private final Documents documents;
    private final AuditLog audit;

    ExpenseService(
            CashbookRepository repo,
            CashbookSupport support,
            RetailBooks books,
            Idempotency idempotency,
            RetailBranchContext branchContext,
            Documents documents,
            AuditLog audit) {
        this.repo = repo;
        this.support = support;
        this.books = books;
        this.idempotency = idempotency;
        this.branchContext = branchContext;
        this.documents = documents;
        this.audit = audit;
    }

    @Transactional
    Outcome<Expense> create(String key, ExpenseRequest r) {
        return idempotency.once(key, "POST", PATH, r, Expense.class, () -> record(r));
    }

    private Expense record(ExpenseRequest r) {
        UUID branch = branchContext.resolve(CashbookSupport.EXPENSE_RECORD, r.branchId());
        LocalDate date = support.businessDate(r.businessDate(), "business_date");
        CategoryRow category = repo.category(r.categoryId(), false)
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("category_id", "unknown_category", "No such expense category."))));
        ItemRow item = repo.item(r.itemId(), false)
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("item_id", "unknown_item", "No such expense item."))));
        if (!item.categoryId().equals(category.id())) {
            throw ApiException.rule("item_not_in_category", "This item belongs to another category.");
        }
        if (!category.active() || !item.active()) {
            throw ApiException.rule("category_inactive", "This category or item is switched off. Pick another one.");
        }
        String explanation = CashbookSupport.blankToNull(r.explanation());
        if (item.requiresExplanation() && (explanation == null || explanation.length() < MIN_EXPLANATION)) {
            throw ApiException.rule("explanation_required", "Say what this expense was for (at least 3 characters).");
        }
        PartyRow party = null;
        if (r.partyId() != null) {
            party = repo.party(r.partyId())
                    .filter(PartyRow::active)
                    .orElseThrow(() -> ApiException.validation(
                            List.of(new FieldProblem("party_id", "unknown_party", "No such active beneficiary."))));
        }
        if (r.receiptDocumentId() != null
                && documents
                        .find(r.receiptDocumentId())
                        .filter(d -> RECEIPT_SUBJECT.equals(d.subjectType()))
                        .isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("receipt_document_id", "unknown_document", "No such uploaded receipt.")));
        }
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        long amount = r.amountMinor();
        UUID entry = books.post(CashPostings.expense(branch, date, id, amount, category.expenseAccountId()))
                .map(PostedEntry::entryId)
                .orElseThrow();
        repo.insertExpense(
                id,
                branch,
                date,
                category.id(),
                item.id(),
                category.name(),
                item.name(),
                party == null ? null : party.id(),
                amount,
                support.currency(),
                explanation,
                r.receiptDocumentId(),
                support.now(),
                by,
                entry);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("business_date", date.toString());
        after.put("category", category.name());
        after.put("item", item.name());
        after.put("amount_minor", amount);
        audit.record(AuditLog.Entry.created("retail.expense.created", "retail.expense", id, branch, after));
        return view(repo.expense(id, false).orElseThrow());
    }

    @Transactional(readOnly = true)
    ExpensePage list(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID categoryId,
            UUID itemId,
            boolean includeVoided,
            Integer limit,
            String cursor) {
        List<UUID> filter = CurrentPrincipal.require().branchFilter(CashbookSupport.READ, branchIds);
        int size = support.limit(limit);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<ExpenseRow> rows = repo.expensePage(
                filter,
                from,
                to,
                categoryId,
                itemId,
                includeVoided,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<ExpenseRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new ExpensePage(items.stream().map(ExpenseService::view).toList(), next);
    }

    static Expense view(ExpenseRow e) {
        return new Expense(
                e.id(),
                e.branchId(),
                e.businessDate(),
                e.categoryId(),
                e.categoryName(),
                e.itemId(),
                e.itemName(),
                e.partyId(),
                e.partyName(),
                e.currency(),
                e.amountMinor(),
                e.explanation(),
                e.receiptDocumentId(),
                e.by(),
                e.byName(),
                e.createdAt(),
                e.voided().voided(),
                e.voided().at(),
                e.voided().reason(),
                e.historical());
    }

    @Transactional
    Outcome<Expense> voidExpense(String key, UUID id, VoidRequest r) {
        return idempotency.once(key, "POST", PATH + "/" + id + "/void", r, Expense.class, () -> {
            Principal principal = CurrentPrincipal.require();
            ExpenseRow row = repo.expense(id, true)
                    .filter(x -> principal.may(CashbookSupport.VOID, x.branchId()))
                    .orElseThrow(ApiException::notFound);
            if (row.voided().voided()) {
                throw CashbookSupport.alreadyVoided();
            }
            if (row.historical()) {
                throw CashbookSupport.historicalRecord();
            }
            String reason = CashbookSupport.reason(r);
            Instant at = support.now();
            if (row.journalEntryId() != null) {
                books.reverse(
                        row.journalEntryId(),
                        support.dayOf(at),
                        CashPostings.ref("Void expense", id),
                        CashPostings.voidKey(CashPostings.EXPENSE, id));
            }
            repo.markVoided("retail_expenses", id, principal.userId(), reason, at);
            audit.record(new AuditLog.Entry(
                    "retail.expense.voided",
                    "retail.expense",
                    id,
                    row.branchId(),
                    Map.of("business_date", row.businessDate().toString()),
                    Map.of("reason", reason)));
            return view(repo.expense(id, false).orElseThrow());
        });
    }
}
