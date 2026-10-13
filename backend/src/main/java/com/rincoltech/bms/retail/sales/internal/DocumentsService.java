package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.GeneratedObject;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.Statement;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.Buyer;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.Line;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.Place;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.QuotationRef;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.SaleRef;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.TransferRef;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.DocumentPage;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.IssueRequest;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.IssuedDocument;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.Series;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.SeriesBranch;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.SeriesList;
import com.rincoltech.bms.retail.sales.internal.DocumentsRepository.BranchInfo;
import com.rincoltech.bms.retail.sales.internal.DocumentsRepository.Doc;
import com.rincoltech.bms.retail.sales.internal.DocumentsRepository.TransferInfo;
import com.rincoltech.bms.retail.sales.internal.DocumentsRepository.TransferLineInfo;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLine;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Optional documents (documents capability; ADR-033 decision 7, ADR-034; docs/specs/trade-capabilities.md section 6):
 * the invoice and the delivery note of a sale, the delivery note of a stock transfer, their numbered series, the
 * stored PDF of every document, and the customer statement PDF. A document is an after-step: the sale, the transfer
 * and the restock never read, wait for or fail because of one.
 *
 * <p>Issuing is two steps, so that nothing slow runs while a lock is held. The first is a short write transaction that
 * takes the series' counter row under a lock, looks again for the document (issuing is idempotent by sale and type),
 * takes the next number and writes the document with its snapshot; a failure rolls the counter back, so a series has
 * no gaps. The second renders the PDF from the snapshot with no connection held and stores it once (the same
 * {@link Documents} calls as the loan documents), under a lock on the document only to look again and record. A render
 * that fails leaves a numbered document without a PDF, and the next request for it renders from the same snapshot.
 */
@Service
class DocumentsService {

    static final String ISSUE = "retail.document.issue";
    static final String SALES_PATH = "/api/v1/retail/sales";
    static final String TRANSFERS_PATH = "/api/v1/retail/transfers";
    static final String SETTINGS = "core.settings.manage";
    // A credit note has no series here: the return numbers it, and its PDF carries that number (ADR-033 amendment).
    static final List<String> TYPES = List.of("quotation", "invoice", "delivery_note");
    static final Map<String, String> DEFAULT_PREFIX =
            Map.of("quotation", "QT", "invoice", "INV", "delivery_note", "DN");
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    /** A statement PDF is held in memory while it is laid out; the heap of the smallest host is about 224 MB. */
    static final int STATEMENT_PDF_MAX_LINES = 800;

    private final DocumentsRepository repo;
    private final SalesService sales;
    private final AccountsService accounts;
    private final SalesRepository salesRepo;
    private final DocumentModels models;
    private final Documents documents;
    private final RetailCapabilities capabilities;
    private final Idempotency idempotency;
    private final RetailBranchContext branches;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;

    DocumentsService(
            DocumentsRepository repo,
            SalesService sales,
            AccountsService accounts,
            SalesRepository salesRepo,
            DocumentModels models,
            Documents documents,
            RetailCapabilities capabilities,
            Idempotency idempotency,
            RetailBranchContext branches,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            JdbcClient jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.repo = repo;
        this.sales = sales;
        this.accounts = accounts;
        this.salesRepo = salesRepo;
        this.models = models;
        this.documents = documents;
        this.capabilities = capabilities;
        this.idempotency = idempotency;
        this.branches = branches;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.jdbc = jdbc;
        this.mapper = mapper;
        // One snapshot for everything a document reads, as the loan documents do (ADR-034).
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writing = new TransactionTemplate(transactions);
    }

    /** What the issuing transaction answers: the document, and whether this call made it. Stored for replays. */
    record Ref(UUID id, boolean created) {}

    /** 404 {@code capability_not_enabled} while the documents capability is off. */
    void requireOn() {
        capabilities.require(RetailCapabilities.DOCUMENTS);
    }

    // ---- Issuing ---------------------------------------------------------------------------------------

    /** The invoice or delivery note of a sale: issued once (idempotent by sale and type), its PDF stored once. */
    Outcome<IssuedDocument> issueForSale(String idempotencyKey, UUID saleId, IssueRequest r) {
        requireOn();
        Outcome<Ref> ref = writing.execute(status -> idempotency.once(
                idempotencyKey,
                "POST",
                SALES_PATH + "/" + saleId + "/documents",
                r,
                Ref.class,
                () -> recordForSale(saleId, r)));
        return new Outcome<>(withPdf(ref.body().id(), ref.body().created()), ref.replayed());
    }

    private Ref recordForSale(UUID saleId, IssueRequest r) {
        Principal principal = CurrentPrincipal.require();
        // A void that has not committed yet finishes first, and one that starts after waits for the document: no
        // invoice is ever numbered for a sale that is voided as it is issued.
        repo.lockSaleShared(saleId);
        Sale sale = sales.get(saleId);
        if (!principal.may(ISSUE, sale.branchId())) {
            throw ApiException.notFound();
        }
        if (!sale.status().equals("completed")) {
            throw ApiException.rule("sale_voided", "A voided sale takes no new document.");
        }
        boolean showPrices = r.type().equals("invoice") || Boolean.TRUE.equals(r.showPrices());
        return recordSaleDocument(sale, r.type(), showPrices, null);
    }

    /**
     * Issues the document of a sale in the caller's transaction: the series lock first, then a second look for the
     * document, then the number. Used by the issue route and by the conversion of a quotation, which passes the
     * quotation document the invoice is made from.
     */
    Ref recordSaleDocument(Sale sale, String type, boolean showPrices, Doc quotation) {
        repo.lockSeries(type, sale.branchId());
        Optional<Doc> existing = repo.forSale(sale.id(), type);
        if (existing.isPresent()) {
            return new Ref(existing.get().id(), false);
        }
        LocalDate today = today();
        String number = nextNumber(type, sale.branchId());
        int seq = Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
        DocumentSnapshot snapshot = saleSnapshot(sale, type, number, today, showPrices, quotation);
        UUID id = UUID.randomUUID();
        insert(id, snapshot, seq, sale.branchId(), sale.id(), null, null, quotation == null ? null : quotation.id());
        return new Ref(id, true);
    }

    /** The delivery note of a stock transfer: quantities only, from the source branch, to the destination. */
    Outcome<IssuedDocument> issueForTransfer(String idempotencyKey, UUID transferId, IssueRequest r) {
        requireOn();
        if (!r.type().equals("delivery_note")) {
            throw ApiException.validation(
                    List.of(new FieldProblem("type", "invalid", "A stock move takes a delivery note only.")));
        }
        Outcome<Ref> ref = writing.execute(status -> idempotency.once(
                idempotencyKey,
                "POST",
                TRANSFERS_PATH + "/" + transferId + "/documents",
                r,
                Ref.class,
                () -> recordForTransfer(transferId)));
        return new Outcome<>(withPdf(ref.body().id(), ref.body().created()), ref.replayed());
    }

    private Ref recordForTransfer(UUID transferId) {
        Principal principal = CurrentPrincipal.require();
        TransferInfo transfer = repo.transfer(transferId).orElseThrow(ApiException::notFound);
        if (!principal.may(ISSUE, transfer.fromBranchId())) {
            throw ApiException.notFound();
        }
        if (transfer.status().equals("voided") || transfer.status().equals("cancelled")) {
            throw ApiException.rule(
                    "transfer_not_active", "A stock move that was " + transfer.status() + " takes no new document.");
        }
        String type = "delivery_note";
        repo.lockSeries(type, transfer.fromBranchId());
        Optional<Doc> existing = repo.forTransfer(transferId, type);
        if (existing.isPresent()) {
            return new Ref(existing.get().id(), false);
        }
        LocalDate today = today();
        String number = nextNumber(type, transfer.fromBranchId());
        int seq = Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
        BranchInfo from = repo.branch(transfer.fromBranchId()).orElseThrow();
        BranchInfo to = repo.branch(transfer.toBranchId()).orElseThrow();
        List<Line> lines = new ArrayList<>();
        for (TransferLineInfo l : repo.transferLines(transferId)) {
            lines.add(new Line(
                    l.lineNo(),
                    l.code(),
                    l.description(),
                    l.qty().toPlainString(),
                    l.unit(),
                    null,
                    0,
                    null,
                    null,
                    null));
        }
        DocumentSnapshot snapshot = new DocumentSnapshot(
                DocumentSnapshot.VERSION,
                type,
                number,
                today,
                tenant.profile().currency(),
                tenant.profile().name(),
                new Place(from.name(), from.location(), from.code()),
                null,
                new Place(to.name(), to.location(), to.code()),
                null,
                new TransferRef(transfer.date(), transfer.note()),
                null,
                null,
                null,
                lines,
                0,
                repo.userName(principal.userId()),
                false,
                null);
        UUID id = UUID.randomUUID();
        insert(id, snapshot, seq, transfer.fromBranchId(), null, transferId, null, null);
        return new Ref(id, true);
    }

    /** Takes the next number of a series whose row the caller has locked: prefix, branch code, six digit counter. */
    String nextNumber(String type, UUID branchId) {
        int seq = repo.bump(type, branchId);
        return format(type, repo.branchCode(branchId), seq);
    }

    String format(String type, String branchCode, int seq) {
        String prefix = repo.prefixes().getOrDefault(type, DEFAULT_PREFIX.get(type));
        return "%s-%s-%06d".formatted(prefix, branchCode, seq);
    }

    /** Writes the document and its audit entry in the caller's transaction. */
    void insert(
            UUID id,
            DocumentSnapshot snapshot,
            int seq,
            UUID branchId,
            UUID saleId,
            UUID transferId,
            UUID quotationId,
            UUID sourceDocumentId) {
        Principal principal = CurrentPrincipal.require();
        String buyer = snapshot.buyer() == null ? null : snapshot.buyer().name();
        Long total = snapshot.type().equals("delivery_note") && !snapshot.showPrices() ? null : snapshot.totalMinor();
        repo.insert(
                id,
                snapshot.type(),
                snapshot.number(),
                seq,
                branchId,
                snapshot.issueDate(),
                saleId,
                transferId,
                quotationId,
                sourceDocumentId,
                buyer,
                snapshot.currency(),
                total,
                mapper.writeValueAsString(snapshot),
                principal.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", snapshot.type());
        after.put("number", snapshot.number());
        if (saleId != null) {
            after.put("sale_id", saleId);
        }
        if (transferId != null) {
            after.put("transfer_id", transferId);
        }
        if (quotationId != null) {
            after.put("quotation_id", quotationId);
        }
        if (total != null) {
            after.put("total_minor", total);
        }
        audit.record(AuditLog.Entry.created("retail.document.issued", "retail.document", id, branchId, after));
    }

    private DocumentSnapshot saleSnapshot(
            Sale sale, String type, String number, LocalDate today, boolean showPrices, Doc quotation) {
        BranchInfo branch = repo.branch(sale.branchId()).orElseThrow();
        String name = sale.buyerName();
        String contact = sale.buyerContact();
        if (sale.customerId() != null) {
            DocumentsRepository.BuyerInfo c = repo.customer(sale.customerId()).orElse(null);
            if (c != null) {
                name = c.name();
                contact = c.contact();
            }
        }
        Map<UUID, String> baseUnits = repo.baseUnits(
                sale.lines().stream().map(SaleLine::productId).distinct().toList());
        List<Line> lines = new ArrayList<>();
        for (SaleLine l : sale.lines()) {
            boolean pack = l.saleUnitId() != null;
            String listName = l.price() != null && "list".equals(l.price().source())
                    ? l.price().priceList()
                    : null;
            lines.add(new Line(
                    l.lineNo(),
                    l.code(),
                    l.description(),
                    pack ? l.saleQty() : l.qty(),
                    pack ? l.saleUnit() : baseUnits.get(l.productId()),
                    pack ? l.saleUnitPriceMinor() : l.unitPriceMinor(),
                    l.lineTotalMinor(),
                    pack ? l.qty() : null,
                    pack ? baseUnits.get(l.productId()) : null,
                    listName != null && !listName.equals(sale.priceList()) ? listName : null));
        }
        QuotationRef quotationRef = null;
        if (quotation != null) {
            DocumentSnapshot q = snapshot(quotation.id());
            quotationRef = q.quotation();
        }
        String invoiceNumber = null;
        if (type.equals("delivery_note")) {
            invoiceNumber = repo.forSale(sale.id(), "invoice").map(Doc::number).orElse(null);
        }
        return new DocumentSnapshot(
                DocumentSnapshot.VERSION,
                type,
                number,
                today,
                sale.currency(),
                tenant.profile().name(),
                new Place(branch.name(), branch.location(), branch.code()),
                new Buyer(name == null || name.isBlank() ? "Cash customer" : name, contact),
                null,
                new SaleRef(sale.saleNo(), sale.saleDate(), sale.paymentMethod(), sale.dueDate(), sale.paidMinor()),
                null,
                quotationRef,
                invoiceNumber,
                sale.priceList(),
                lines,
                sale.totalMinor(),
                repo.userName(CurrentPrincipal.require().userId()),
                showPrices,
                null);
    }

    // ---- The stored PDF ----------------------------------------------------------------------------------

    /** What the read step found: the stored PDF, or the model to render. */
    private record Draft(Doc doc, String storedNumber, UUID pdfId, Map<String, Object> model) {}

    /** A void document is served only as a second stored version marked void: the number with this suffix. */
    static final String VOID_SUFFIX = " VOID";

    /** The number a document's PDF is stored under: its own, or for a void document the void version's. */
    static String storedNumber(Doc doc) {
        return doc.status().equals("void") ? doc.number() + VOID_SUFFIX : doc.number();
    }

    /**
     * The document with its PDF: the stored copy when there is one, else rendered now from the snapshot and stored
     * once. The render and the upload hold no database connection and no lock; two concurrent first requests both
     * render, one records, and the other gets it back and drops its own upload.
     */
    IssuedDocument withPdf(UUID id, boolean created) {
        // A void that lands between the read and the record is seen under the lock (recordPdf answers null), and the
        // copy is
        // made again as the void one: a clean copy of a document that has become void is never stored and returned.
        for (int attempt = 0; attempt < 3; attempt++) {
            Draft draft = reading.execute(status -> draft(id));
            UUID pdf = draft.pdfId();
            if (pdf == null) {
                byte[] bytes = documents.renderPdf(draft.doc().type(), draft.model());
                GeneratedObject object =
                        documents.putGenerated(bytes, draft.doc().type());
                boolean recorded = false;
                try {
                    pdf = writing.execute(status -> recordPdf(draft.doc(), draft.storedNumber(), object));
                    recorded = pdf != null && pdf.equals(object.id());
                } finally {
                    if (!recorded) {
                        documents.discardGenerated(object);
                    }
                }
                if (pdf == null) {
                    continue;
                }
            }
            UUID stored = pdf;
            return reading.execute(status -> response(repo.find(id).orElseThrow(), stored, created));
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "document_changed",
                "Document changed",
                "The document changed while it was being made. Try again.");
    }

    private Draft draft(UUID id) {
        Doc doc = visible(id);
        // A void document never gets a clean copy: only a version with the VOID banner (the issued copy stays in
        // storage
        // as it was, and is no longer served).
        String number = storedNumber(doc);
        Optional<Documents.StoredDocument> stored =
                documents.findGenerated(DocumentsRepository.SUBJECT, doc.id(), doc.type(), number);
        if (stored.isPresent()) {
            return new Draft(doc, number, stored.get().id(), null);
        }
        return new Draft(
                doc, number, null, models.model(snapshot(id), doc.status().equals("void") ? doc.voidReason() : null));
    }

    /** Under an advisory lock on the document: look again, then record. Nothing slow happens here. */
    private UUID recordPdf(Doc doc, String number, GeneratedObject object) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('retail.document:' || ?::text, 0))")
                .param(doc.id())
                .query()
                .singleRow();
        // Share lock on the sale, so a void that has not committed finishes first and one that starts after waits for
        // this copy.
        if (doc.saleId() != null) {
            repo.lockSaleShared(doc.saleId());
        }
        Doc current = repo.find(doc.id()).orElseThrow();
        if (!storedNumber(current).equals(number)) {
            return null;
        }
        Optional<Documents.StoredDocument> stored =
                documents.findGenerated(DocumentsRepository.SUBJECT, doc.id(), doc.type(), number);
        if (stored.isPresent()) {
            return stored.get().id();
        }
        return documents
                .recordGenerated(object, number, DocumentsRepository.SUBJECT, doc.id(), doc.branchId())
                .id();
    }

    DocumentSnapshot snapshot(UUID id) {
        String json = repo.snapshot(id).orElseThrow(ApiException::notFound);
        return mapper.readValue(json, DocumentSnapshot.class);
    }

    // ---- Reading ---------------------------------------------------------------------------------------

    /** The document, 404 outside the caller's scope of {@code retail.document.issue}. */
    Doc visible(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id).filter(d -> principal.may(ISSUE, d.branchId())).orElseThrow(ApiException::notFound);
    }

    IssuedDocument get(UUID id) {
        requireOn();
        return reading.execute(status -> {
            Doc d = visible(id);
            return response(d, d.pdfId(), false);
        });
    }

    /** The PDF of a document that was issued, rendered now when it was never stored. */
    IssuedDocument pdf(UUID id) {
        requireOn();
        reading.executeWithoutResult(status -> visible(id));
        return withPdf(id, false);
    }

    List<IssuedDocument> forSale(UUID saleId) {
        requireOn();
        return reading.execute(status -> {
            Principal principal = CurrentPrincipal.require();
            // The sale must be one the caller may see; documents of other branches are left out.
            sales.get(saleId);
            return repo.listForSale(saleId).stream()
                    .filter(d -> principal.may(ISSUE, d.branchId()))
                    .map(d -> response(d, d.pdfId(), false))
                    .toList();
        });
    }

    DocumentPage list(
            List<UUID> branchIds,
            String type,
            LocalDate from,
            LocalDate to,
            String query,
            Integer limit,
            String cursor) {
        requireOn();
        if (type != null && !TYPES.contains(type)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("type", "invalid", "type is one of " + TYPES + ".")));
        }
        return reading.execute(status -> {
            List<UUID> filter = CurrentPrincipal.require().branchFilter(ISSUE, branchIds);
            int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
            Search.Query search = Search.parse(query);
            Cursor.Key after =
                    Cursor.decodeKey(Search.unwrap(search, cursor).cursor()).orElse(null);
            List<Doc> rows = repo.page(
                    filter,
                    type,
                    from,
                    to,
                    search,
                    after == null ? null : after.at(),
                    after == null ? null : after.id(),
                    size + 1);
            boolean more = rows.size() > size;
            List<Doc> items = more ? rows.subList(0, size) : rows;
            String next = more
                    ? Search.wrap(
                            search,
                            0,
                            Cursor.encode(items.getLast().createdAt() + "|"
                                    + items.getLast().id()))
                    : null;
            return new DocumentPage(
                    items.stream().map(d -> response(d, d.pdfId(), false)).toList(), next);
        });
    }

    IssuedDocument response(Doc d, UUID pdfId, boolean created) {
        String quotationStatus = d.quotationStatus();
        if ("open".equals(quotationStatus) && d.validUntil().isBefore(today())) {
            quotationStatus = "expired";
        }
        return new IssuedDocument(
                d.id(),
                d.type(),
                d.number(),
                d.branchId(),
                d.issueDate(),
                d.saleId(),
                d.transferId(),
                d.quotationId(),
                d.sourceDocumentId(),
                d.buyerName(),
                d.currency(),
                d.totalMinor(),
                d.status(),
                d.validUntil(),
                quotationStatus,
                pdfId,
                created,
                d.createdAt());
    }

    // ---- Series ------------------------------------------------------------------------------------------

    SeriesList series() {
        requireOn();
        return reading.execute(status -> {
            List<com.rincoltech.bms.core.tenancy.Branches.Branch> visible = branches.visible(ISSUE);
            Map<String, String> prefixes = repo.prefixes();
            List<DocumentsRepository.SeriesRow> rows =
                    repo.series(visible.stream().map(b -> b.id()).toList());
            List<Series> out = new ArrayList<>();
            for (String type : TYPES) {
                String prefix = prefixes.getOrDefault(type, DEFAULT_PREFIX.get(type));
                List<SeriesBranch> perBranch = new ArrayList<>();
                for (var b : visible) {
                    int last = rows.stream()
                            .filter(r -> r.type().equals(type) && r.branchId().equals(b.id()))
                            .mapToInt(DocumentsRepository.SeriesRow::lastNo)
                            .findFirst()
                            .orElse(0);
                    perBranch.add(new SeriesBranch(b.id(), b.code(), last, format(type, b.code(), last + 1)));
                }
                out.add(new Series(type, prefix, DEFAULT_PREFIX.get(type), perBranch));
            }
            return new SeriesList(out);
        });
    }

    /** Changes the prefix of a type for the documents issued from now on; audited, administrators only. */
    SeriesList setPrefix(String type, String prefix) {
        requireOn();
        if (!TYPES.contains(type)) {
            throw ApiException.notFound();
        }
        writing.executeWithoutResult(status -> {
            Principal principal = CurrentPrincipal.require();
            Optional<String> before = repo.setPrefix(type, prefix, principal.userId());
            Map<String, Object> was = new LinkedHashMap<>();
            was.put("prefix", before.orElse(DEFAULT_PREFIX.get(type)));
            Map<String, Object> now = new LinkedHashMap<>();
            now.put("prefix", prefix);
            audit.record(new AuditLog.Entry(
                    "retail.document.prefix_changed", "retail.document_series", null, null, was, now));
        });
        return series();
    }

    // ---- The customer statement as a PDF -----------------------------------------------------------------

    /**
     * The statement of a credit buyer as at the business date (ADR-034): rendered on each request and never stored,
     * because it changes with every payment. It is the on-screen statement, so it carries the same scope: the
     * caller's branches, the ledger's closing balance, imported credit sales apart.
     */
    byte[] statementPdf(UUID customerId, LocalDate from, LocalDate to) {
        requireOn();
        // The lines are the buyer's sales in the caller's scope of retail.sale.read, so the document needs that
        // permission too.
        if (!CurrentPrincipal.require().hasPermission("retail.sale.read")) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "A statement shows the buyer's sales, which needs permission to read sales.");
        }
        Map<String, Object> model = reading.execute(status -> {
            Statement st = accounts.statement(customerId, from, to);
            if (st.lines().size() > STATEMENT_PDF_MAX_LINES) {
                throw ApiException.rule(
                        "statement_too_long",
                        "Choose a shorter range: more than " + STATEMENT_PDF_MAX_LINES
                                + " lines do not fit one document.");
            }
            DocumentsRepository.BuyerInfo buyer = repo.customer(customerId).orElseThrow(ApiException::notFound);
            AccountsApi.Account account = accounts.account(customerId);
            Integer terms = account.termsDays();
            Principal principal = CurrentPrincipal.require();
            List<UUID> filter = principal.branchFilter("retail.sale.read", null);
            String scope = filter == null
                    ? "All branches"
                    : branches.visible("retail.sale.read").stream()
                            .map(b -> b.name())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("No branch");
            LocalDate today = today();
            String number = "ST-" + today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-"
                    + customerId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            String termsText = terms == null ? null : terms == 0 ? "Due on the day of the sale" : terms + " days";
            return models.statement(st, buyer.contact(), termsText, account.creditLimitMinor(), scope, today, number);
        });
        return documents.renderPdf("customer_statement", model);
    }

    LocalDate today() {
        return clock.today(tenant.profile().timezone());
    }
}
