package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.approvals.ApprovalWaiting;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

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
    // Lazy: the approvals module holds the expense action, which calls back into this service on approval.
    private final Approvals approvals;
    private final ObjectMapper mapper;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    ExpenseService(
            CashbookRepository repo,
            CashbookSupport support,
            RetailBooks books,
            Idempotency idempotency,
            RetailBranchContext branchContext,
            Documents documents,
            AuditLog audit,
            @Lazy Approvals approvals,
            ObjectMapper mapper) {
        this.approvals = approvals;
        this.mapper = mapper;
        this.repo = repo;
        this.support = support;
        this.books = books;
        this.idempotency = idempotency;
        this.branchContext = branchContext;
        this.documents = documents;
        this.audit = audit;
    }

    /**
     * An expense recorded at once, or the request that now waits for an administrator (ADR-091). Stored as the idempotent answer;
     * an answer stored before ADR-091 was the bare expense, which still replays as this.
     */
    @JsonDeserialize(using = ExpenseOutcome.Reader.class)
    record ExpenseOutcome(Expense expense, ApprovalWaiting waiting) {

        /** Reads both shapes: {@code {expense, waiting}}, or the old flat expense (an object with {@code id}). */
        static final class Reader extends ValueDeserializer<ExpenseOutcome> {
            @Override
            public ExpenseOutcome deserialize(JsonParser p, DeserializationContext ctxt) {
                JsonNode n = ctxt.readTree(p);
                if (n.has("id")) {
                    return new ExpenseOutcome(ctxt.readTreeAsValue(n, Expense.class), null);
                }
                JsonNode expense = n.get("expense");
                JsonNode waiting = n.get("waiting");
                return new ExpenseOutcome(
                        expense == null || expense.isNull() ? null : ctxt.readTreeAsValue(expense, Expense.class),
                        waiting == null || waiting.isNull()
                                ? null
                                : ctxt.readTreeAsValue(waiting, ApprovalWaiting.class));
            }
        }
    }

    /** An expense checked and ready to write, under the branch-day lock. */
    private record Checked(
            UUID branch, LocalDate date, CategoryRow category, ItemRow item, PartyRow party, String explanation) {}

    @Transactional
    Outcome<ExpenseOutcome> create(String key, ExpenseRequest r) {
        return idempotency.once(key, "POST", PATH, r, ExpenseOutcome.class, () -> recordOrAsk(r));
    }

    /**
     * While expenses ask for approval (ADR-091) and the amount reaches the business's amount, the expense waits for an
     * administrator with the branch and the business date fixed, and is not in the cash book until it is approved.
     */
    private ExpenseOutcome recordOrAsk(ExpenseRequest r) {
        UUID branch = branchContext.resolve(CashbookSupport.EXPENSE_RECORD, r.branchId());
        Checked c = check(branch, r);
        Approvals.Rule rule = approvals.rule(ExpenseAction.TYPE);
        if (rule.asks(r.amountMinor())) {
            ExpenseRequest fixed = new ExpenseRequest(
                    branch,
                    c.date(),
                    r.categoryId(),
                    r.itemId(),
                    r.partyId(),
                    r.amountMinor(),
                    r.explanation(),
                    r.receiptDocumentId());
            UUID id = UUID.randomUUID();
            Approvals.Outcome o = approvals.request(
                    new ActionRequest(
                            ExpenseAction.TYPE,
                            branch,
                            id,
                            null,
                            r.amountMinor(),
                            support.currency(),
                            mapper.convertValue(fixed, MAP)),
                    rule);
            // Run at once when the switch went off between the module's read and the request: answer with the expense
            // made.
            if (o.executed()) {
                return new ExpenseOutcome(view(repo.expense(id, false).orElseThrow()), null);
            }
            return new ExpenseOutcome(
                    null, new ApprovalWaiting(o.approvalId(), ExpenseAction.TYPE, r.amountMinor(), support.currency()));
        }
        return new ExpenseOutcome(
                write(UUID.randomUUID(), c, r, CurrentPrincipal.require().userId()), null);
    }

    /** On approval (ADR-091): checked again as it stands now, recorded under the request's id in the maker's name. */
    void recordApproved(UUID id, Map<String, Object> payload, UUID makerId) {
        ExpenseRequest r = mapper.convertValue(payload, ExpenseRequest.class);
        // A refusal here makes the request stale with its reason: the approvals module's one stale policy (ADR-091).
        write(id, check(r.branchId(), r), r, makerId);
    }

    private Checked check(UUID branch, ExpenseRequest r) {
        LocalDate date = support.businessDate(r.businessDate(), "business_date");
        // The savings cap reads the day's expenses under the same lock (ADR-080 decision 3).
        repo.lockBranchDay(branch, date);
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
        return new Checked(branch, date, category, item, party, explanation);
    }

    private Expense write(UUID id, Checked c, ExpenseRequest r, UUID by) {
        UUID branch = c.branch();
        LocalDate date = c.date();
        CategoryRow category = c.category();
        ItemRow item = c.item();
        PartyRow party = c.party();
        String explanation = c.explanation();
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
            // The same branch-day lock as the savings create and the expense create (ADR-080 decision 3).
            repo.lockBranchDay(row.branchId(), row.businessDate());
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
