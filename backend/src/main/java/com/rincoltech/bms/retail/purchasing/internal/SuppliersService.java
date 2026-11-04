package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Supplier;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierDetails;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierList;
import com.rincoltech.bms.retail.purchasing.internal.SuppliersApi.Delivery;
import com.rincoltech.bms.retail.purchasing.internal.SuppliersApi.DeliveryPage;
import com.rincoltech.bms.retail.purchasing.internal.SuppliersApi.SupplierTypes;
import com.rincoltech.bms.retail.purchasing.internal.SuppliersRepository.DeliveryKey;
import com.rincoltech.bms.retail.purchasing.internal.SuppliersRepository.DeliveryRow;
import com.rincoltech.bms.retail.purchasing.internal.SuppliersRepository.Scope;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A supplier's details, its page and its deliveries (issue #820, ADR-125, FR-RET-90). Who may read and edit suppliers is
 * unchanged: {@code retail.purchase.create} reads and adds, {@code retail.catalogue.manage} edits. The bank account number
 * is shown only to those who may edit suppliers. A delivery is a restock naming the supplier, listed by the rule of the
 * restock list: the caller's branches for {@code retail.purchase.create}, and only their own without
 * {@code retail.profit.read} (ADR-055).
 */
@Service
class SuppliersService {

    /** Who may edit suppliers, and so read their bank account number. */
    static final String EDIT = "retail.catalogue.manage";

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");

    private final SuppliersRepository repo;

    SuppliersService(SuppliersRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    SupplierList list(String query) {
        Scope scope = scope();
        return new SupplierList(repo.suppliers(Search.parse(query), scope).stream()
                .map(SuppliersService::shown)
                .toList());
    }

    @Transactional(readOnly = true)
    Supplier get(UUID id) {
        return repo.supplier(id, scope()).map(SuppliersService::shown).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    SupplierTypes types() {
        return new SupplierTypes(repo.types());
    }

    /** The supplier's deliveries, the newest day first; 404 for a supplier that is not this business's. */
    @Transactional(readOnly = true)
    DeliveryPage deliveries(UUID supplierId, Integer limit, String cursor) {
        Scope scope = scope();
        repo.supplier(supplierId, scope).orElseThrow(ApiException::notFound);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<DeliveryRow> rows = repo.deliveries(supplierId, scope, after(cursor), size + 1);
        boolean more = rows.size() > size;
        List<DeliveryRow> page = more ? rows.subList(0, size) : rows;
        String next = null;
        if (more) {
            DeliveryRow last = page.getLast();
            next = Cursor.encode(last.purchasedOn() + "|" + last.createdAt() + "|" + last.id());
        }
        return new DeliveryPage(
                page.stream()
                        .map(r -> new Delivery(r.id(), r.purchaseNo(), r.purchasedOn(), r.voided(), r.lines()))
                        .toList(),
                next);
    }

    private static DeliveryKey after(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = Cursor.decode(cursor).map(s -> s.split("\\|")).orElse(new String[0]);
        try {
            return new DeliveryKey(LocalDate.parse(parts[0]), Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException e) {
            throw ApiException.validation(List.of(new FieldProblem("cursor", "invalid", "The cursor is not valid.")));
        }
    }

    /** The restocks the caller may list, as {@code GET /retail/purchases} lists them. */
    private static Scope scope() {
        Principal principal = CurrentPrincipal.require();
        return new Scope(
                principal.branchFilter(PurchasingService.PERMISSION, null), PurchasingService.ownRecordsOnly());
    }

    /** The supplier as the caller may read it: the bank account only for those who may edit suppliers. */
    static Supplier shown(Supplier s) {
        SupplierDetails d = s.details() == null ? SupplierDetails.NONE : s.details();
        return s.withDetails(CurrentPrincipal.require().hasPermission(EDIT) ? d : d.withoutBankAccount());
    }

    /**
     * The details a write leaves: {@code typed} over {@code before}, an omitted field unchanged and an empty one cleared,
     * each trimmed, the email and the website checked. A bank account from someone who may not edit suppliers is refused,
     * since they could never read it back.
     */
    static SupplierDetails merged(SupplierDetails before, SupplierDetails typed) {
        SupplierDetails was = before == null ? SupplierDetails.NONE : before;
        if (typed == null) {
            return was;
        }
        if (typed.bankAccount() != null
                && !Objects.equals(PurchasingService.blankToNull(typed.bankAccount()), was.bankAccount())
                && !CurrentPrincipal.require().hasPermission(EDIT)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "details.bank_account",
                    "not_allowed",
                    "Only someone who may edit suppliers can save a bank account number.")));
        }
        SupplierDetails d = new SupplierDetails(
                pick(was.email(), typed.email()),
                pick(was.address(), typed.address()),
                pick(was.country(), typed.country()),
                pick(was.website(), typed.website()),
                pick(was.paymentTerms(), typed.paymentTerms()),
                pick(was.bankAccount(), typed.bankAccount()),
                pick(was.supplierType(), typed.supplierType()),
                pick(was.notes(), typed.notes()));
        List<FieldProblem> problems = new ArrayList<>();
        if (d.email() != null
                && !Objects.equals(d.email(), was.email())
                && !EMAIL.matcher(d.email()).matches()) {
            problems.add(new FieldProblem(
                    "details.email", "invalid", "That email does not look right. Write it like name@example.com."));
        }
        if (d.website() != null && !Objects.equals(d.website(), was.website()) && !website(d.website())) {
            problems.add(new FieldProblem(
                    "details.website", "invalid", "That web address does not look right. Write it like example.com."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return d;
    }

    /** The names of the details that differ, for the audit log, which never holds their values. */
    static List<String> changed(SupplierDetails before, SupplierDetails after) {
        SupplierDetails a = before == null ? SupplierDetails.NONE : before;
        List<String> names = new ArrayList<>();
        addIf(names, "email", a.email(), after.email());
        addIf(names, "address", a.address(), after.address());
        addIf(names, "country", a.country(), after.country());
        addIf(names, "website", a.website(), after.website());
        addIf(names, "payment_terms", a.paymentTerms(), after.paymentTerms());
        addIf(names, "bank_account", a.bankAccount(), after.bankAccount());
        addIf(names, "supplier_type", a.supplierType(), after.supplierType());
        addIf(names, "notes", a.notes(), after.notes());
        return names;
    }

    private static void addIf(List<String> names, String name, String x, String y) {
        if (!Objects.equals(x, y)) {
            names.add(name);
        }
    }

    private static String pick(String before, String typed) {
        return typed == null ? before : PurchasingService.blankToNull(typed);
    }

    /** A web address with or without http or https: a host with a dot, no spaces. */
    static boolean website(String text) {
        String t = text.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        String full = lower.startsWith("http://") || lower.startsWith("https://") ? t : "https://" + t;
        try {
            URI uri = new URI(full);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https"))
                    && uri.getHost() != null
                    && HOST.matcher(uri.getHost()).matches()
                    && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
