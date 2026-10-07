package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.CategoryPatch;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.CategoryRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseCategory;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseCategoryList;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseItem;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ItemPatch;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ItemRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.Party;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.PartyPage;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.PartyRequest;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.CategoryRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.ItemRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.PartyRow;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expense categories, items and cash parties (FR-RET-17): tenant data, never deleted (a row is
 * switched off), names unique ignoring case within their parent. A category names the ledger
 * expense account its expenses debit; none means operating expenses.
 */
@Service
class SetupService {

    static final Set<String> PARTY_KINDS = Set.of("owner", "staff", "related_entity", "supplier", "other");
    static final UUID NIL = new UUID(0, 0);

    private final CashbookRepository repo;
    private final CashbookSupport support;
    private final AuditLog audit;

    SetupService(CashbookRepository repo, CashbookSupport support, AuditLog audit) {
        this.repo = repo;
        this.support = support;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    ExpenseCategoryList categories(Boolean active) {
        Map<UUID, List<ItemRow>> items =
                repo.items(active).stream().collect(Collectors.groupingBy(ItemRow::categoryId));
        return new ExpenseCategoryList(repo.categories(active).stream()
                .map(c -> view(c, items.getOrDefault(c.id(), List.of())))
                .toList());
    }

    static ExpenseCategory view(CategoryRow c, List<ItemRow> items) {
        return new ExpenseCategory(
                c.id(),
                c.name(),
                c.expenseAccountId(),
                c.active(),
                c.sortOrder(),
                c.version(),
                items.stream().map(SetupService::view).toList());
    }

    static ExpenseItem view(ItemRow i) {
        return new ExpenseItem(i.id(), i.name(), i.requiresExplanation(), i.active(), i.version());
    }

    @Transactional
    ExpenseCategory createCategory(CategoryRequest r) {
        String name = r.name().trim();
        checkAccount(r.expenseAccountId());
        if (repo.categoryNameTaken(name, null)) {
            throw duplicate("duplicate_category", "A category with this name exists already.");
        }
        UUID id = UUID.randomUUID();
        repo.insertCategory(
                id, name, r.expenseAccountId(), 0, CurrentPrincipal.require().userId());
        audit.record(AuditLog.Entry.created(
                "retail.expense_category.created",
                "retail.expense_category",
                id,
                null,
                Map.of("name", name, "expense_account_id", String.valueOf(r.expenseAccountId()))));
        return view(repo.category(id, false).orElseThrow(), List.of());
    }

    @Transactional
    ExpenseCategory patchCategory(UUID id, String ifMatch, CategoryPatch p) {
        int expected = Versions.fromIfMatch(ifMatch);
        CategoryRow c = repo.category(id, true).orElseThrow(ApiException::notFound);
        if (c.version() != expected) {
            throw Versions.conflict(c.version());
        }
        String name = p.name() == null ? c.name() : p.name().trim();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "required", "A name is required.")));
        }
        UUID account = c.expenseAccountId();
        if (p.expenseAccountId() != null) {
            account = p.expenseAccountId().equals(NIL) ? null : p.expenseAccountId();
            checkAccount(account);
        }
        if (!name.equalsIgnoreCase(c.name()) && repo.categoryNameTaken(name, id)) {
            throw duplicate("duplicate_category", "A category with this name exists already.");
        }
        boolean active = p.active() == null ? c.active() : p.active();
        int sort = p.sortOrder() == null ? c.sortOrder() : p.sortOrder();
        repo.updateCategory(id, name, account, active, sort);
        audit.record(new AuditLog.Entry(
                "retail.expense_category.updated",
                "retail.expense_category",
                id,
                null,
                Map.of("name", c.name(), "active", c.active()),
                Map.of("name", name, "active", active)));
        List<ItemRow> items =
                repo.items(null).stream().filter(i -> i.categoryId().equals(id)).toList();
        return view(repo.category(id, false).orElseThrow(), items);
    }

    @Transactional
    ExpenseItem createItem(UUID categoryId, ItemRequest r) {
        CategoryRow c = repo.category(categoryId, true).orElseThrow(ApiException::notFound);
        String name = r.name().trim();
        if (repo.itemNameTaken(c.id(), name, null)) {
            throw duplicate("duplicate_item", "This category has an item with that name already.");
        }
        UUID id = UUID.randomUUID();
        boolean explain = Boolean.TRUE.equals(r.requiresExplanation());
        repo.insertItem(id, c.id(), name, explain, CurrentPrincipal.require().userId());
        audit.record(AuditLog.Entry.created(
                "retail.expense_item.created",
                "retail.expense_item",
                id,
                null,
                Map.of("category_id", c.id().toString(), "name", name, "requires_explanation", explain)));
        return view(repo.item(id, false).orElseThrow());
    }

    @Transactional
    ExpenseItem patchItem(UUID categoryId, UUID itemId, String ifMatch, ItemPatch p) {
        int expected = Versions.fromIfMatch(ifMatch);
        ItemRow i = repo.item(itemId, true)
                .filter(x -> x.categoryId().equals(categoryId))
                .orElseThrow(ApiException::notFound);
        if (i.version() != expected) {
            throw Versions.conflict(i.version());
        }
        String name = p.name() == null ? i.name() : p.name().trim();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "required", "A name is required.")));
        }
        if (!name.equalsIgnoreCase(i.name()) && repo.itemNameTaken(categoryId, name, itemId)) {
            throw duplicate("duplicate_item", "This category has an item with that name already.");
        }
        boolean explain = p.requiresExplanation() == null ? i.requiresExplanation() : p.requiresExplanation();
        boolean active = p.active() == null ? i.active() : p.active();
        repo.updateItem(itemId, name, explain, active);
        audit.record(new AuditLog.Entry(
                "retail.expense_item.updated",
                "retail.expense_item",
                itemId,
                null,
                Map.of("name", i.name(), "active", i.active(), "requires_explanation", i.requiresExplanation()),
                Map.of("name", name, "active", active, "requires_explanation", explain)));
        return view(repo.item(itemId, false).orElseThrow());
    }

    private void checkAccount(UUID accountId) {
        if (accountId != null && !repo.isExpenseAccount(accountId)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "expense_account_id",
                    "account_not_expense",
                    "Pick an active expense account that accepts postings.")));
        }
    }

    // ---------------------------------------------------------------------------------- parties

    @Transactional(readOnly = true)
    PartyPage parties(String query, String kind, Integer limit, String cursor) {
        int size = support.limit(limit);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<PartyRow> rows = repo.parties(
                query, kind, after == null ? null : after.at(), after == null ? null : after.id(), size + 1);
        boolean more = rows.size() > size;
        List<PartyRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new PartyPage(items.stream().map(SetupService::view).toList(), next);
    }

    static Party view(PartyRow p) {
        return new Party(p.id(), p.name(), p.contact(), p.kind(), p.active());
    }

    @Transactional
    Party createParty(PartyRequest r) {
        if (!PARTY_KINDS.contains(r.kind())) {
            throw ApiException.validation(List.of(
                    new FieldProblem("kind", "invalid", "Kind is owner, staff, related_entity, supplier or other.")));
        }
        String name = r.name().trim();
        if (repo.partyNameTaken(r.kind(), name)) {
            throw duplicate("duplicate_party", "A party of this kind with this name exists already.");
        }
        UUID id = UUID.randomUUID();
        repo.insertParty(
                id,
                name,
                CashbookSupport.blankToNull(r.contact()),
                r.kind(),
                CurrentPrincipal.require().userId());
        audit.record(AuditLog.Entry.created(
                "retail.cash_party.created", "retail.cash_party", id, null, Map.of("kind", r.kind())));
        return view(repo.party(id).orElseThrow());
    }

    private static ApiException duplicate(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "Duplicate", detail);
    }
}
