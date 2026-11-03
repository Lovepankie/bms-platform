package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Search;
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

    /** The item a create answers with, and whether it is new (201) or an existing one picked again (200). */
    record AddedItem(ExpenseItem item, boolean created) {}

    // ADR-118: whoever records expenses may add an item in place; an inactive category and the explanation flag stay
    // with retail.expense.manage. With reuse_existing an active item of the same name is picked, a switched off one
    // refused.
    // JUSTIFICATION-A3: the permission split and the reuse rule are the issue's two rules, inside the one create.
    @Transactional
    AddedItem createItem(UUID categoryId, ItemRequest r) {
        // The category row is locked (FOR UPDATE) for the whole transaction, so two tills adding the same name to one
        // category run one after the other: the second sees the first's item and reuses it (unique index V34 as
        // backstop).
        CategoryRow c = repo.category(categoryId, true).orElseThrow(ApiException::notFound);
        boolean manager = CurrentPrincipal.require().hasPermission(CashbookSupport.EXPENSE_MANAGE);
        if (!manager && !c.active()) {
            throw ApiException.rule("category_inactive", "This category is switched off. Pick another one.");
        }
        if (!manager && Boolean.TRUE.equals(r.requiresExplanation())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Asking for an explanation on an item needs permission to manage expenses.");
        }
        // Spaces inside the name are one space, so "Generator  fuel" is "Generator fuel" (ADR-118 decision 2).
        String name = r.name().strip().replaceAll("\\s+", " ");
        boolean reuse = Boolean.TRUE.equals(r.reuseExisting());
        var same = reuse ? repo.itemNamed(c.id(), name) : java.util.Optional.<ItemRow>empty();
        if (same.isPresent()) {
            return existing(same.get());
        }
        if (repo.itemNameTaken(c.id(), name, null)) {
            throw duplicate("duplicate_item", "This category has an item with that name already.");
        }
        UUID id = UUID.randomUUID();
        boolean explain = Boolean.TRUE.equals(r.requiresExplanation());
        if (!repo.insertItem(
                id, c.id(), name, explain, CurrentPrincipal.require().userId())) {
            // The loser of a race with a writer that did not take the category lock: answer with the winner, never a
            // 500.
            ItemRow winner = repo.itemNamed(c.id(), name).orElseThrow();
            if (reuse) {
                return existing(winner);
            }
            throw duplicate("duplicate_item", "This category has an item with that name already.");
        }
        audit.record(AuditLog.Entry.created(
                "retail.expense_item.created",
                "retail.expense_item",
                id,
                null,
                Map.of("category_id", c.id().toString(), "name", name, "requires_explanation", explain)));
        return new AddedItem(view(repo.item(id, false).orElseThrow()), true);
    }

    /** An item of the same name, picked again when it is active (200) and refused in plain words when switched off. */
    private AddedItem existing(ItemRow same) {
        if (!same.active()) {
            throw duplicate(
                    "item_switched_off",
                    "This category has an item with that name that the owner switched off. Ask the owner to switch it on again.");
        }
        return new AddedItem(view(same), false);
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
        Search.Query search = Search.parse(query);
        Search.After position = Search.unwrap(search, cursor);
        Cursor.Key after = Cursor.decodeKey(position.cursor()).orElse(null);
        List<Search.Ranked<PartyRow>> rows = repo.parties(
                search,
                kind,
                position.rank(),
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<Search.Ranked<PartyRow>> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        items.getLast().rank(),
                        Cursor.encode(items.getLast().row().createdAt() + "|"
                                + items.getLast().row().id()))
                : null;
        return new PartyPage(items.stream().map(r -> view(r.row())).toList(), next);
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
        // Beneficiaries (supplier, other) are added on the fly with retail.expense.record; the people an advance
        // can name (owner, staff, related_entity) need retail.expense.manage (ADR-022 decision 13).
        if (AdvanceService.ADVANCE_PARTY_KINDS.contains(r.kind())
                && !CurrentPrincipal.require().hasPermission(CashbookSupport.EXPENSE_MANAGE)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Adding an owner, staff or related party needs permission to manage expenses.");
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
