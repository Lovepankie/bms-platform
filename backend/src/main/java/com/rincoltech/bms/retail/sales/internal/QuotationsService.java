package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.Buyer;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.Line;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.Place;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.QuotationRef;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.CancelRequest;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.ConvertRequest;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.Converted;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.IssuedDocument;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.Quotation;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.QuotationLine;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.QuotationPage;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.QuotationRequest;
import com.rincoltech.bms.retail.sales.internal.DocumentsRepository.BranchInfo;
import com.rincoltech.bms.retail.sales.internal.DocumentsRepository.Doc;
import com.rincoltech.bms.retail.sales.internal.DocumentsService.Ref;
import com.rincoltech.bms.retail.sales.internal.QuotationsRepository.Header;
import com.rincoltech.bms.retail.sales.internal.QuotationsRepository.StoredLine;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Customer;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLineRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleRequest;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.NewLine;
import com.rincoltech.bms.retail.sales.internal.SalesService.Priced;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Quotations (documents capability; ADR-033, issue #197; docs/specs/trade-capabilities.md section 6.2): prices offered
 * to a buyer until a date. A quotation reserves no stock and posts nothing; it is priced the way a sale would be
 * (units, price lists, the price floor), numbered in its branch's series, and its PDF is stored once like every
 * document. It is converted to a sale through the ordinary sale path, so every rule of a sale (stock, the credit limit,
 * the price floor, the branch role) holds, and optionally to the invoice of that sale. It cannot be edited: what was
 * offered stays what was offered, and a change is a new quotation.
 */
@Service
class QuotationsService {

    static final String PATH = "/api/v1/retail/quotations";
    private static final String PACKS_OFF =
            "Pack sizes are switched off, so the lines quoted in packs were sold in single units.";

    private final QuotationsRepository repo;
    private final DocumentsRepository docRepo;
    private final DocumentsService docs;
    private final SalesService sales;
    private final SalesRepository salesRepo;
    private final RetailCapabilities capabilities;
    private final RetailBranchContext branches;
    private final Idempotency idempotency;
    private final CurrentTenant tenant;
    private final AuditLog audit;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;

    QuotationsService(
            QuotationsRepository repo,
            DocumentsRepository docRepo,
            DocumentsService docs,
            SalesService sales,
            SalesRepository salesRepo,
            RetailCapabilities capabilities,
            RetailBranchContext branches,
            Idempotency idempotency,
            CurrentTenant tenant,
            AuditLog audit,
            PlatformTransactionManager transactions) {
        this.repo = repo;
        this.docRepo = docRepo;
        this.docs = docs;
        this.sales = sales;
        this.salesRepo = salesRepo;
        this.capabilities = capabilities;
        this.branches = branches;
        this.idempotency = idempotency;
        this.tenant = tenant;
        this.audit = audit;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writing = new TransactionTemplate(transactions);
    }

    /** What the creating transaction answers, stored for replays. */
    record Made(UUID id, UUID documentId) {}

    /** What the converting transaction answers, stored for replays. */
    record Done(UUID quotationId, UUID saleId, String saleNo, UUID invoiceId, String note) {}

    // ---- Creating ----------------------------------------------------------------------------------

    Outcome<Quotation> create(String idempotencyKey, QuotationRequest r) {
        docs.requireOn();
        Outcome<Made> made = writing.execute(
                status -> idempotency.once(idempotencyKey, "POST", PATH, r, Made.class, () -> record(r)));
        docs.withPdf(made.body().documentId(), true);
        return new Outcome<>(get(made.body().id()), made.replayed());
    }

    private Made record(QuotationRequest r) {
        Principal principal = CurrentPrincipal.require();
        // A branch that keeps no goods has nothing to quote from (ADR-041, ADR-104), and a store never sells
        // (ADR-048), so it does not quote either.
        UUID branch = branches.resolveStocking(DocumentsService.ISSUE, r.branchId());
        branches.requireSells(branch, "branch_id");
        LocalDate today = docs.today();
        List<FieldProblem> problems = new ArrayList<>();
        if (r.validUntil().isBefore(today)) {
            problems.add(new FieldProblem(
                    "valid_until", "invalid", "A quotation cannot be valid until a day that has passed."));
        }
        Customer buyer = r.customerId() == null
                ? null
                : salesRepo.customer(r.customerId()).orElse(null);
        if (r.customerId() != null && buyer == null) {
            problems.add(new FieldProblem("customer_id", "unknown_customer", "No such customer."));
        }
        String buyerName = blankToNull(r.buyerName());
        if (r.customerId() == null && buyerName == null) {
            problems.add(new FieldProblem("buyer_name", "required", "Name the buyer."));
        }
        List<SaleLineRequest> lines = r.lines().stream()
                .map(l -> new SaleLineRequest(
                        l.productId(), l.qty(), l.unitPriceMinor(), l.unitId(), l.priceOverrideReason()))
                .toList();
        Priced priced = sales.priceLines(branch, today, buyer, r.priceListId(), lines, problems);
        long total = priced.lines().stream().mapToLong(NewLine::lineTotalMinor).reduce(0, Math::addExact);
        if (total > RetailCatalogue.MAX_AMOUNT_MINOR) {
            throw ApiException.rule("amount_out_of_range", "The quotation is too large to compute.");
        }
        UUID id = UUID.randomUUID();
        repo.insert(
                id,
                branch,
                today,
                r.validUntil(),
                r.customerId(),
                r.customerId() == null ? buyerName : null,
                r.customerId() == null ? blankToNull(r.buyerContact()) : null,
                priced.list() == null ? null : priced.list().id(),
                tenant.profile().currency(),
                total,
                blankToNull(r.notes()),
                principal.userId());
        priced.lines().forEach(l -> repo.insertLine(id, l));
        Header header = repo.find(id, false).orElseThrow();
        String number;
        int seq;
        docRepo.lockSeries("quotation", branch);
        number = docs.nextNumber("quotation", branch);
        seq = Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
        DocumentSnapshot snapshot = snapshot(header, repo.lines(id), number, today);
        UUID documentId = UUID.randomUUID();
        docs.insert(documentId, snapshot, seq, branch, null, null, id, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("number", number);
        after.put("total_minor", total);
        after.put("valid_until", r.validUntil());
        after.put("lines", priced.lines().size());
        audit.record(AuditLog.Entry.created("retail.quotation.created", "retail.quotation", id, branch, after));
        return new Made(id, documentId);
    }

    private DocumentSnapshot snapshot(Header h, List<StoredLine> stored, String number, LocalDate today) {
        BranchInfo branch = docRepo.branch(h.branchId()).orElseThrow();
        List<Line> lines = new ArrayList<>();
        for (StoredLine l : stored) {
            boolean pack = l.saleUnitId() != null;
            lines.add(new Line(
                    l.lineNo(),
                    l.code(),
                    l.description(),
                    (pack ? l.saleQty() : l.qty()).toPlainString(),
                    pack ? l.saleUnit() : l.baseUnit(),
                    pack ? l.saleUnitPriceMinor() : l.unitPriceMinor(),
                    l.lineTotalMinor(),
                    pack ? l.qty().toPlainString() : null,
                    pack ? l.baseUnit() : null,
                    null));
        }
        return new DocumentSnapshot(
                DocumentSnapshot.VERSION,
                "quotation",
                number,
                today,
                h.currency(),
                tenant.profile().name(),
                new Place(branch.name(), branch.location(), branch.code()),
                new Buyer(h.buyerName(), h.buyerContact()),
                null,
                null,
                null,
                new QuotationRef(number, h.quoteDate(), h.validUntil()),
                null,
                h.priceList(),
                lines,
                h.totalMinor(),
                docRepo.userName(CurrentPrincipal.require().userId()),
                true,
                h.notes());
    }

    // ---- Reading -----------------------------------------------------------------------------------

    Quotation get(UUID id) {
        docs.requireOn();
        return reading.execute(status -> view(visible(id, false)));
    }

    private Header visible(UUID id, boolean lock) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, lock)
                .filter(h -> principal.may(DocumentsService.ISSUE, h.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    QuotationPage page(List<UUID> branchIds, String status, String query, Integer limit, String cursor) {
        docs.requireOn();
        if (status != null && !List.of("open", "converted", "cancelled").contains(status)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is open, converted or cancelled.")));
        }
        return reading.execute(tx -> {
            List<UUID> filter = CurrentPrincipal.require().branchFilter(DocumentsService.ISSUE, branchIds);
            int size =
                    limit == null ? DocumentsService.DEFAULT_LIMIT : Math.clamp(limit, 1, DocumentsService.MAX_LIMIT);
            Search.Query search = Search.parse(query);
            Cursor.Key after =
                    Cursor.decodeKey(Search.unwrap(search, cursor).cursor()).orElse(null);
            List<Header> rows = repo.page(
                    filter,
                    status,
                    search,
                    after == null ? null : after.at(),
                    after == null ? null : after.id(),
                    size + 1);
            boolean more = rows.size() > size;
            List<Header> items = more ? rows.subList(0, size) : rows;
            String next = more
                    ? Search.wrap(
                            search,
                            0,
                            Cursor.encode(items.getLast().createdAt() + "|"
                                    + items.getLast().id()))
                    : null;
            return new QuotationPage(items.stream().map(this::view).toList(), next);
        });
    }

    private Quotation view(Header h) {
        List<QuotationLine> lines = new ArrayList<>();
        for (StoredLine l : repo.lines(h.id())) {
            boolean pack = l.saleUnitId() != null;
            lines.add(new QuotationLine(
                    l.lineNo(),
                    l.productId(),
                    l.code(),
                    l.description(),
                    DocumentModels.plain((pack ? l.saleQty() : l.qty()).toPlainString()),
                    pack ? l.saleUnit() : l.baseUnit(),
                    pack ? l.saleUnitPriceMinor() : l.unitPriceMinor(),
                    l.lineTotalMinor(),
                    pack ? DocumentModels.plain(l.qty().toPlainString()) : null,
                    pack ? l.baseUnit() : null));
        }
        Doc doc = docRepo.find(h.documentId()).orElse(null);
        return new Quotation(
                h.id(),
                h.number(),
                h.documentId(),
                h.branchId(),
                h.quoteDate(),
                h.validUntil(),
                h.customerId(),
                h.buyerName(),
                h.buyerContact(),
                h.priceListId(),
                h.priceList(),
                h.currency(),
                h.totalMinor(),
                h.notes(),
                h.status(),
                h.status().equals("open") && h.validUntil().isBefore(docs.today()),
                h.convertedSaleId(),
                h.saleNo(),
                h.cancelReason(),
                doc == null ? null : doc.pdfId(),
                lines,
                h.createdAt(),
                h.version());
    }

    // ---- Cancelling and converting -----------------------------------------------------------------

    Quotation cancel(UUID id, CancelRequest r) {
        docs.requireOn();
        writing.executeWithoutResult(status -> {
            Header h = visible(id, true);
            requireOpen(h);
            String reason = r.reason().trim();
            if (repo.markCancelled(id, reason, CurrentPrincipal.require().userId()) == 0) {
                throw notOpen(h);
            }
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", "open");
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", "cancelled");
            after.put("reason", reason);
            audit.record(new AuditLog.Entry(
                    "retail.quotation.cancelled", "retail.quotation", id, h.branchId(), before, after));
        });
        return get(id);
    }

    /**
     * Converts an open quotation to a sale through the ordinary sale path, in one transaction, and optionally issues
     * the invoice of that sale linked to the quotation. A quotation converts once. It converts only while valid. The
     * prices are today's unless {@code keepQuotedPrices}; a kept price that differs from today's is a typed price on a
     * sale and follows its rules.
     */
    Outcome<Converted> convert(String idempotencyKey, UUID id, ConvertRequest r) {
        docs.requireOn();
        Outcome<Done> done = writing.execute(status -> idempotency.once(
                idempotencyKey, "POST", PATH + "/" + id + "/convert", r, Done.class, () -> doConvert(id, r)));
        IssuedDocument invoice = done.body().invoiceId() == null
                ? null
                : docs.withPdf(done.body().invoiceId(), true);
        return new Outcome<>(
                new Converted(
                        done.body().quotationId(),
                        done.body().saleId(),
                        done.body().saleNo(),
                        invoice,
                        done.body().note()),
                done.replayed());
    }

    private Done doConvert(UUID id, ConvertRequest r) {
        Principal principal = CurrentPrincipal.require();
        Header h = visible(id, true);
        requireOpen(h);
        if (h.validUntil().isBefore(docs.today())) {
            throw ApiException.rule(
                    "quotation_expired",
                    "This quotation was valid until " + DocumentModels.date(h.validUntil()) + ". Make a new one.");
        }
        boolean keep = Boolean.TRUE.equals(r.keepQuotedPrices());
        // A capability switched off since the quotation was made is not an error (spec 14.1: a quotation keeps its
        // stored
        // prices; conversion re-prices at the product price when lists are off, and says so). The sale takes the
        // plain path: no list, and a line quoted in a pack is sold in base units.
        boolean listsOn = capabilities.enabled(RetailCapabilities.PRICE_LISTS);
        boolean unitsOn = capabilities.enabled(RetailCapabilities.UNITS);
        List<String> notes = new ArrayList<>();
        if (!listsOn && h.priceListId() != null && !keep) {
            notes.add(
                    "Price lists are switched off, so the lines were priced at the product's price and not by the list.");
        }
        List<SaleLineRequest> lines = new ArrayList<>();
        for (StoredLine l : repo.lines(id)) {
            boolean pack = l.saleUnitId() != null && unitsOn;
            if (l.saleUnitId() != null && !unitsOn && !notes.contains(PACKS_OFF)) {
                notes.add(PACKS_OFF);
            }
            Long price = !keep ? null : pack ? l.saleUnitPriceMinor() : l.unitPriceMinor();
            lines.add(new SaleLineRequest(
                    l.productId(),
                    pack ? l.saleQty() : l.qty(),
                    price,
                    pack ? l.saleUnitId() : null,
                    keep && listsOn ? "Quoted on " + h.number() : null));
        }
        Sale sale = sales.record(new SaleRequest(
                h.branchId(),
                null,
                r.paymentMethod(),
                h.customerId(),
                h.customerId() == null ? h.buyerName() : null,
                h.customerId() == null ? h.buyerContact() : null,
                r.dueDate(),
                listsOn ? h.priceListId() : null,
                r.creditOverrideReason(),
                lines));
        if (repo.markConverted(id, sale.id(), principal.userId()) == 0) {
            throw notOpen(h);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "open");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "converted");
        after.put("sale_id", sale.id());
        after.put("sale_no", sale.saleNo());
        after.put("kept_quoted_prices", keep);
        audit.record(
                new AuditLog.Entry("retail.quotation.converted", "retail.quotation", id, h.branchId(), before, after));
        UUID invoice = null;
        if (Boolean.TRUE.equals(r.issueInvoice())) {
            Doc quotationDoc = docRepo.forQuotation(id).orElseThrow();
            Ref ref = docs.recordSaleDocument(sale, "invoice", true, quotationDoc);
            invoice = ref.id();
        }
        return new Done(id, sale.id(), sale.saleNo(), invoice, notes.isEmpty() ? null : String.join(" ", notes));
    }

    private void requireOpen(Header h) {
        if (!h.status().equals("open")) {
            throw notOpen(h);
        }
    }

    private static ApiException notOpen(Header h) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "quotation_not_open",
                "Quotation not open",
                "This quotation is " + h.status() + " already.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
