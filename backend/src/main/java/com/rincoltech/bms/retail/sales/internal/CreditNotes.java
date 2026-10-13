package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.GeneratedObject;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.AmountInWords;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.Place;
import com.rincoltech.bms.retail.sales.internal.DocumentsRepository.BranchInfo;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnLine;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.SaleReturn;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The credit note as a PDF (documents and returns capabilities; ADR-033 amendment of 2026-10-13, issue #197). A credit note has
 * ONE number, the return's own ({@code credit_note_no}, issue #201): this class numbers nothing. It renders the return, whose rows
 * are immutable (the lines are append-only, only a void changes the header), and stores the PDF once in the documents store under
 * that number (subject type {@code retail.credit_note}), like the other retail documents: rendered with no connection held and no
 * lock, recorded under an advisory lock on the return only to look again and write. A voided return is never printed clean: its
 * issued copy is no longer served, and the first request after the void stores a second version with a VOID banner, under the
 * number with {@link DocumentsService#VOID_SUFFIX}.
 */
@Service
class CreditNotes {

    static final String SUBJECT = "retail.credit_note";
    static final String TYPE = "credit_note";

    private static final Map<String, String> METHODS =
            Map.of("cash", "Cash", "mobile_money", "Mobile money", "bank", "Bank");
    private static final Map<String, String> REASONS = Map.of(
            "defective", "Faulty",
            "damaged", "Damaged",
            "wrong_item", "Wrong item",
            "not_as_described", "Not as described",
            "unwanted", "Customer does not want it",
            "price_error", "Price mistake",
            "other", "Another reason");

    private final ReturnsService returns;
    private final DocumentsRepository docRepo;
    private final DocumentModels models;
    private final Documents documents;
    private final RetailCapabilities capabilities;
    private final JdbcClient jdbc;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;

    CreditNotes(
            ReturnsService returns,
            DocumentsRepository docRepo,
            DocumentModels models,
            Documents documents,
            RetailCapabilities capabilities,
            JdbcClient jdbc,
            CurrentTenant tenant,
            BusinessClock clock,
            PlatformTransactionManager transactions) {
        this.returns = returns;
        this.docRepo = docRepo;
        this.models = models;
        this.documents = documents;
        this.capabilities = capabilities;
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.clock = clock;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writing = new TransactionTemplate(transactions);
    }

    /** The credit note of a return: its number, whether it is void, and the stored PDF. */
    record Issued(UUID returnId, String creditNoteNo, String status, UUID pdfDocumentId, boolean created) {}

    /** The facts of the document the return does not keep: who issued it, on which day, and the buyer as named then. */
    record Facts(UUID issuedBy, LocalDate issuedOn, String buyerName, String buyerContact) {}

    private record Draft(
            SaleReturn ret,
            String storedNumber,
            UUID pdfId,
            Facts facts,
            boolean firstIssue,
            Map<String, Object> model) {}

    /**
     * The credit note PDF of a return: the stored copy when there is one, else rendered now and stored once. Idempotent by return
     * and number; a return outside the caller's branch scope is a 404, as is one of a capability that is off. A void that lands
     * between reading the return and recording the copy is seen under the lock, and the copy is made again as the void one, so a
     * clean copy of a voided return is never stored.
     */
    Issued issue(UUID returnId) {
        capabilities.require(RetailCapabilities.DOCUMENTS);
        for (int attempt = 0; attempt < 3; attempt++) {
            Draft draft = reading.execute(status -> draft(returnId));
            UUID pdf = draft.pdfId();
            boolean created = false;
            if (pdf == null) {
                byte[] bytes = documents.renderPdf(TYPE, draft.model());
                GeneratedObject object = documents.putGenerated(bytes, TYPE);
                boolean recorded = false;
                try {
                    pdf = writing.execute(status -> record(draft, object));
                    recorded = pdf != null && pdf.equals(object.id());
                    created = recorded;
                } finally {
                    if (!recorded) {
                        documents.discardGenerated(object);
                    }
                }
                if (pdf == null) {
                    continue;
                }
            }
            boolean voided = draft.ret().status().equals("voided");
            return new Issued(returnId, draft.ret().creditNoteNo(), voided ? "void" : "issued", pdf, created);
        }
        throw new ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                "document_changed",
                "Document changed",
                "The credit note changed while it was being made. Try again.");
    }

    private Draft draft(UUID returnId) {
        // The return itself is read under the caller's scope of retail.sale.return (404 outside it, and while returns
        // are off).
        SaleReturn ret = returns.get(returnId);
        if (!CurrentPrincipal.require().may(DocumentsService.ISSUE, ret.branchId())) {
            throw ApiException.notFound();
        }
        boolean voided = ret.status().equals("voided");
        String number = voided ? ret.creditNoteNo() + DocumentsService.VOID_SUFFIX : ret.creditNoteNo();
        Optional<Documents.StoredDocument> stored = documents.findGenerated(SUBJECT, returnId, TYPE, number);
        if (stored.isPresent()) {
            return new Draft(ret, number, stored.get().id(), null, false, null);
        }
        // The issuer, the day and the buyer come from the first issue when there was one (the copy marked void is the
        // original's
        // facts plus the void), else from now.
        Optional<Facts> original = facts(returnId);
        Facts facts = original.orElseGet(() -> currentFacts(ret));
        return new Draft(ret, number, null, facts, original.isEmpty(), model(ret, voided, facts));
    }

    private Optional<Facts> facts(UUID returnId) {
        return jdbc.sql(
                        "SELECT issued_by, issued_on, buyer_name, buyer_contact FROM retail_credit_note_issues WHERE return_id = ?")
                .param(returnId)
                .query((rs, n) -> new Facts(
                        rs.getObject("issued_by", UUID.class),
                        rs.getDate("issued_on").toLocalDate(),
                        rs.getString("buyer_name"),
                        rs.getString("buyer_contact")))
                .optional();
    }

    private Facts currentFacts(SaleReturn r) {
        Optional<DocumentsRepository.BuyerInfo> buyer =
                r.customerId() == null ? Optional.empty() : docRepo.customer(r.customerId());
        String name = buyer.map(DocumentsRepository.BuyerInfo::name)
                .orElse(r.customerName() == null ? "Cash customer" : r.customerName());
        return new Facts(
                CurrentPrincipal.require().userId(),
                clock.today(tenant.profile().timezone()),
                name,
                buyer.map(DocumentsRepository.BuyerInfo::contact).orElse(null));
    }

    /** Under an advisory lock on the return: look again at the status and at the stored copy, then record. */
    private UUID record(Draft draft, GeneratedObject object) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('retail.credit_note:' || ?::text, 0))")
                .param(draft.ret().id())
                .query()
                .singleRow();
        // Share lock on the return, so a void that has not committed finishes first and one that starts after waits for
        // this copy.
        boolean voidedNow = jdbc.sql("SELECT status = 'voided' FROM retail_sale_returns WHERE id = ? FOR SHARE")
                .param(draft.ret().id())
                .query(Boolean.class)
                .single();
        if (voidedNow != draft.storedNumber().endsWith(DocumentsService.VOID_SUFFIX)) {
            return null;
        }
        Optional<Documents.StoredDocument> stored =
                documents.findGenerated(SUBJECT, draft.ret().id(), TYPE, draft.storedNumber());
        if (stored.isPresent()) {
            return stored.get().id();
        }
        if (draft.firstIssue() && !voidedNow) {
            jdbc.sql("""
                            INSERT INTO retail_credit_note_issues (tenant_id, return_id, issued_by, issued_on, buyer_name, buyer_contact)
                            VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, return_id) DO NOTHING
                            """)
                    .params(
                            draft.ret().id(),
                            draft.facts().issuedBy(),
                            java.sql.Date.valueOf(draft.facts().issuedOn()),
                            draft.facts().buyerName(),
                            draft.facts().buyerContact())
                    .update();
        }
        return documents
                .recordGenerated(
                        object,
                        draft.storedNumber(),
                        SUBJECT,
                        draft.ret().id(),
                        draft.ret().branchId())
                .id();
    }

    /** The model the template reads, from the return alone: what was credited is what the return recorded. */
    Map<String, Object> model(SaleReturn r, boolean voided, Facts facts) {
        BranchInfo branch = docRepo.branch(r.branchId()).orElseThrow();
        Map<String, Object> m = models.common(
                new Place(branch.name(), branch.location(), branch.code()),
                "CREDIT NOTE",
                r.creditNoteNo(),
                r.returnedOn());
        if (voided) {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) m.get("doc");
            doc.put(
                    "voided",
                    "VOID: voided on " + DocumentModels.date(r.voidedOn()) + " by " + docRepo.userName(r.voidedBy())
                            + (r.voidReason() == null ? "" : " (" + r.voidReason() + ")")
                            + ". Do not accept this document.");
        }
        String cur = r.currency();
        m.put("currency", cur);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", facts.buyerName());
        b.put("contact", facts.buyerContact());
        m.put("buyer", b);
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put(
                "invoice",
                docRepo.forSale(r.saleId(), "invoice")
                        .map(DocumentsRepository.Doc::number)
                        .orElse(null));
        ref.put("saleNo", r.saleNo());
        ref.put("date", DocumentModels.date(r.returnedOn()));
        ref.put("refund", refundWords(r, cur));
        ref.put("note", r.note());
        m.put("ref", ref);
        List<UUID> products =
                r.lines().stream().map(ReturnLine::productId).distinct().toList();
        Map<UUID, String> baseUnits = docRepo.baseUnits(products);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ReturnLine l : r.lines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            boolean pack = l.unitQty() != null;
            row.put("no", l.lineNo());
            row.put("code", l.code());
            row.put("description", l.description());
            row.put("qty", DocumentModels.plain(pack ? l.unitQty() : l.qty()));
            row.put("unit", pack && l.unit() != null ? l.unit() : baseUnits.get(l.productId()));
            row.put("pack", pack ? "= " + DocumentModels.plain(l.qty()) + " " + baseUnits.get(l.productId()) : null);
            row.put("reason", REASONS.getOrDefault(l.reason(), l.reason()));
            row.put("total", DocumentModels.money(l.lineTotalMinor(), cur));
            lines.add(row);
        }
        m.put("lines", lines);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("total", DocumentModels.money(r.totalMinor(), cur));
        totals.put("words", AmountInWords.of(r.totalMinor(), cur));
        m.put("totals", totals);
        m.put("notes", null);
        Map<String, Object> signs = new LinkedHashMap<>();
        signs.put("issuedBy", docRepo.userName(facts.issuedBy()));
        signs.put("issuedOn", DocumentModels.date(facts.issuedOn()));
        m.put("signs", signs);
        return m;
    }

    private static String refundWords(SaleReturn r, String cur) {
        String method = r.refundMethod() == null ? "" : METHODS.getOrDefault(r.refundMethod(), r.refundMethod());
        if (r.accountCreditMinor() > 0 && r.refundMinor() > 0) {
            return "Taken off what the buyer owes ("
                    + DocumentModels.money(r.accountCreditMinor(), cur) + "), the rest paid in " + method + " ("
                    + DocumentModels.money(r.refundMinor(), cur) + ")";
        }
        if (r.accountCreditMinor() > 0) {
            return "Taken off what the buyer owes";
        }
        return method;
    }
}
