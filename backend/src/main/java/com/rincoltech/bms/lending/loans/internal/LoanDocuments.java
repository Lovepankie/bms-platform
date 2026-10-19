package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.GeneratedObject;
import com.rincoltech.bms.core.documents.Documents.StoredDocument;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.Letterheads;
import com.rincoltech.bms.core.tenancy.Letterheads.Letterhead;
import com.rincoltech.bms.kernel.AmountInWords;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.LoanLedger.After;
import com.rincoltech.bms.lending.loans.internal.LoanLedger.Deduction;
import com.rincoltech.bms.lending.loans.internal.LoanLedger.Entry;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.AllocationRow;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.LoanBalances;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.LoanTransaction;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ScheduleRow;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.FeeCharge;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-DOC-01 for loans (ADR-034): the repayment receipt and the disbursement voucher, rendered on
 * first request and stored once (FR-DOC-02), and the repayment schedule and loan statement, rendered
 * as at the business date on each request. Every document carries the tenant's letterhead and the
 * branch of the loan; amounts appear in figures and in words.
 *
 * <p>No render and no upload happens inside a transaction or under the loan's row lock, which a
 * repayment needs: the data is read in a short read-only transaction, the PDF is rendered and
 * uploaded with no database connection held, and the lock is taken only to look again and record.
 */
@Service
class LoanDocuments {

    private static final Logger log = LoggerFactory.getLogger(LoanDocuments.class);

    static final String SUBJECT = "lending.loan";
    static final Set<String> RECEIPTED = Set.of("repayment", "recovery", "fee_upfront");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Map<String, String> METHODS = Map.of(
            "cash", "Cash",
            "bank", "Bank",
            "mtn_momo", "MTN Mobile Money",
            "airtel_money", "Airtel Money",
            "gateway", "Online payment (mobile money or card)");
    private static final Map<String, String> COMPONENTS = Map.of(
            "penalty", "Penalty",
            "fee", "Fees",
            "interest", "Interest",
            "principal", "Principal",
            "overpayment", "Credit held for the member");

    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final ServicingService servicing;
    private final MemberLookup members;
    private final ProductCatalog products;
    private final Letterheads letterheads;
    private final Documents documents;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final JdbcClient jdbc;
    private final com.rincoltech.bms.core.payments.PaymentCollections collections;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;

    LoanDocuments(
            LoanRepository loans,
            ServicingRepository repo,
            ServicingService servicing,
            MemberLookup members,
            ProductCatalog products,
            Letterheads letterheads,
            Documents documents,
            CurrentTenant currentTenant,
            BusinessClock clock,
            JdbcClient jdbc,
            com.rincoltech.bms.core.payments.PaymentCollections collections,
            PlatformTransactionManager transactions) {
        this.loans = loans;
        this.repo = repo;
        this.servicing = servicing;
        this.members = members;
        this.products = products;
        this.letterheads = letterheads;
        this.documents = documents;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.jdbc = jdbc;
        this.collections = collections;
        // One snapshot for everything a document reads: a statement is built from several queries, and a
        // repayment committing between them would otherwise show as a false "Other adjustments" line.
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writing = new TransactionTemplate(transactions);
    }

    /**
     * @param reversed the repayment or recovery was reversed after the receipt was issued: the stored
     *     copy is unchanged (it is what was issued), and this flag is how a client shows it
     */
    record Issued(
            UUID documentId,
            String docType,
            String docNumber,
            boolean created,
            boolean reversed,
            LocalDate reversedOn) {}

    /** The template every document read runs in: read only, REPEATABLE READ. */
    TransactionTemplate readingTemplate() {
        return reading;
    }

    /** What the read step found: the stored document, or the model to render and what to record. */
    private record Draft(Issued existing, String docType, String docNumber, Map<String, Object> model) {}

    /**
     * The receipt of a repayment, recovery or upfront fee, or the voucher of a disbursement: the
     * stored copy when there is one, else rendered now and stored.
     *
     * <p>A receipt is never first issued for a transaction that was reversed (422 {@code
     * transaction_reversed}); one issued before the reversal stays as it was, and the response says it
     * was reversed. Two first requests for the same transaction both render, but the loan's row lock
     * is taken only to record, after looking again, so one document is stored and the other request
     * gets it back (and drops its own upload).
     */
    Issued issue(UUID loanId, UUID txnId) {
        Draft draft = reading.execute(status -> draft(loanId, txnId));
        if (draft.existing() != null) {
            return draft.existing();
        }
        byte[] pdf = documents.renderPdf(draft.docType(), draft.model());
        GeneratedObject object = documents.putGenerated(pdf, draft.docType());
        boolean recorded = false;
        try {
            Issued issued = writing.execute(status -> record(loanId, txnId, draft, object));
            recorded = issued.created();
            return issued;
        } finally {
            if (!recorded) {
                documents.discardGenerated(object);
            }
        }
    }

    private Draft draft(UUID loanId, UUID txnId) {
        Loan loan = inScope(loanId);
        List<LoanTransaction> all = repo.transactions(loanId);
        LoanTransaction txn = find(all, txnId);
        boolean voucher = txn.txnType().equals("disbursement");
        if (!voucher && !RECEIPTED.contains(txn.txnType())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "no_document",
                    "No document",
                    "A " + txn.txnType() + " has no receipt or voucher; it shows on the loan statement.");
        }
        String docType = voucher ? "voucher" : "receipt";
        Optional<StoredDocument> stored = documents.findGenerated(SUBJECT, loanId, docType, txn.receiptNo());
        if (stored.isPresent()) {
            return new Draft(issued(stored.get(), txn, all, false), docType, txn.receiptNo(), null);
        }
        requireNotReversed(txn);
        Map<String, Object> model = common(loan, voucher ? "VOUCHER" : "RECEIPT", txn.receiptNo(), txn.valueDate());
        model.put("txn", txnModel(loan, txn));
        if (voucher) {
            voucherModel(loan, txn, model);
        } else {
            receiptModel(loan, all, txn, model);
        }
        return new Draft(null, docType, txn.receiptNo(), model);
    }

    /** Under the loan's row lock: look again, then record. Nothing slow happens here. */
    private Issued record(UUID loanId, UUID txnId, Draft draft, GeneratedObject object) {
        Principal principal = CurrentPrincipal.require();
        Loan loan = loans.lock(loanId)
                .filter(l -> principal.may("lending.loans.read", l.branchId()))
                .orElseThrow(ApiException::notFound);
        List<LoanTransaction> all = repo.transactions(loanId);
        LoanTransaction txn = find(all, txnId);
        Optional<StoredDocument> stored = documents.findGenerated(SUBJECT, loanId, draft.docType(), draft.docNumber());
        if (stored.isPresent()) {
            return issued(stored.get(), txn, all, false);
        }
        requireNotReversed(txn);
        StoredDocument doc = documents.recordGenerated(object, draft.docNumber(), SUBJECT, loanId, loan.branchId());
        return issued(doc, txn, all, true);
    }

    /** The receipts and vouchers stored for the loan, newest first, each marked when its transaction was reversed. */
    List<Issued> issuedDocuments(UUID loanId) {
        return reading.execute(status -> {
            inScope(loanId);
            List<LoanTransaction> all = repo.transactions(loanId);
            Map<String, LoanTransaction> byDocument = new LinkedHashMap<>();
            for (LoanTransaction t : all) {
                boolean voucher = t.txnType().equals("disbursement");
                if (t.receiptNo() != null && (voucher || RECEIPTED.contains(t.txnType()))) {
                    byDocument.put((voucher ? "voucher/" : "receipt/") + t.receiptNo(), t);
                }
            }
            List<Issued> out = new ArrayList<>();
            for (Documents.GeneratedDocument g : documents.listGenerated(SUBJECT, loanId)) {
                LoanTransaction t = byDocument.get(g.document().docType() + "/" + g.docNumber());
                if (t != null) {
                    out.add(issued(g.document(), t, all, false));
                }
            }
            return out;
        });
    }

    private static Issued issued(StoredDocument doc, LoanTransaction txn, List<LoanTransaction> all, boolean created) {
        LocalDate reversedOn = all.stream()
                .filter(r -> r.id().equals(txn.reversedByTxnId()))
                .map(LoanTransaction::valueDate)
                .findFirst()
                .orElse(null);
        return new Issued(doc.id(), doc.docType(), txn.receiptNo(), created, txn.reversedByTxnId() != null, reversedOn);
    }

    private static LoanTransaction find(List<LoanTransaction> all, UUID txnId) {
        return all.stream().filter(t -> t.id().equals(txnId)).findFirst().orElseThrow(ApiException::notFound);
    }

    private static void requireNotReversed(LoanTransaction txn) {
        if (txn.reversedByTxnId() != null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "transaction_reversed",
                    "Transaction reversed",
                    "This " + txn.txnType()
                            + " was reversed, so it has no receipt. The reversal shows on the loan statement.");
        }
    }

    /** The repayment schedule as at the business date (FR-DOC-01), never stored. */
    byte[] schedule(UUID loanId) {
        Map<String, Object> model = reading.execute(status -> scheduleModel(loanId));
        return documents.renderPdf("schedule", model);
    }

    private Map<String, Object> scheduleModel(UUID loanId) {
        Loan loan = inScope(loanId);
        requireDisbursed(loan);
        LocalDate today = today();
        Map<String, Object> model = common(loan, "LOAN SCHEDULE", series("SCH", loan, today), today);
        ServicingApi.Schedule schedule = servicing.schedule(loanId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScheduleRow r : schedule.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", r.no());
            row.put("dueDate", date(r.dueDate()));
            row.put("principal", money(r.principalDueMinor(), loan.currency()));
            row.put("interest", money(r.interestDueMinor(), loan.currency()));
            row.put("fees", money(r.feesDueMinor(), loan.currency()));
            row.put("total", money(r.totalDueMinor(), loan.currency()));
            row.put("paid", money(r.totalPaidMinor(), loan.currency()));
            row.put("outstanding", money(r.outstandingMinor(), loan.currency()));
            row.put("status", r.status() == null ? "" : r.status().replace('_', ' '));
            rows.add(row);
        }
        ScheduleRow t = schedule.totals();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("principal", money(t.principalDueMinor(), loan.currency()));
        totals.put("interest", money(t.interestDueMinor(), loan.currency()));
        totals.put("fees", money(t.feesDueMinor(), loan.currency()));
        totals.put("total", money(t.totalDueMinor(), loan.currency()));
        totals.put("paid", money(t.totalPaidMinor(), loan.currency()));
        totals.put("outstanding", money(t.outstandingMinor(), loan.currency()));
        totals.put("outstandingWords", AmountInWords.of(t.outstandingMinor(), loan.currency()));
        model.put("rows", rows);
        model.put("totals", totals);
        return model;
    }

    /**
     * The loan statement as at the business date (FR-DOC-01), never stored: the loan account from
     * disbursement to now with a running balance, whose closing balance is the loan's outstanding
     * ({@link LoanLedger}).
     */
    byte[] statement(UUID loanId) {
        Map<String, Object> model = reading.execute(status -> statementModel(loanId));
        return documents.renderPdf("loan_statement", model);
    }

    private Map<String, Object> statementModel(UUID loanId) {
        Loan loan = inScope(loanId);
        requireDisbursed(loan);
        String cur = loan.currency();
        LocalDate today = today();
        Map<String, Object> model = common(loan, "STATEMENT", series("ST", loan, today), today);
        List<LoanTransaction> txns = repo.transactions(loanId);
        LoanBalances b = repo.balances(loanId).orElseThrow();
        List<Deduction> deductions = txns.stream()
                .filter(t -> t.txnType().equals("disbursement"))
                .findFirst()
                .map(d -> deductions(loan, d))
                .orElse(List.of());
        LoanLedger.Statement st = LoanLedger.statement(
                txns, repo.scheduleRows(loanId), deductions, today, b.totalOutstandingMinor(), v -> money(v, cur));
        long charged = 0;
        long credited = 0;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Entry e : st.entries()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("date", date(e.date()));
            line.put("number", e.number());
            line.put("description", e.description());
            line.put("note", e.note());
            line.put("charged", e.chargedMinor() == 0 ? "-" : money(e.chargedMinor(), cur));
            line.put("credited", e.creditedMinor() == 0 ? "-" : money(e.creditedMinor(), cur));
            line.put("balance", money(e.balanceMinor(), cur));
            lines.add(line);
            charged = Math.addExact(charged, e.chargedMinor());
            credited = Math.addExact(credited, e.creditedMinor());
            if (e.description().startsWith("Other adjustments")) {
                log.warn("loan {} statement needed an adjustment line to reach the recorded outstanding", loanId);
            }
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("charged", money(charged, cur));
        totals.put("credited", money(credited, cur));
        totals.put("closing", money(st.closingMinor(), cur));
        totals.put("closingWords", AmountInWords.of(st.closingMinor(), cur));
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("principal", money(b.principalOutstandingMinor(), cur));
        position.put("interest", money(b.interestOutstandingMinor(), cur));
        position.put("fees", money(b.feesOutstandingMinor(), cur));
        position.put(
                "penalties", b.penaltiesOutstandingMinor() == 0 ? null : money(b.penaltiesOutstandingMinor(), cur));
        position.put("credit", b.creditBalanceMinor() == 0 ? null : money(b.creditBalanceMinor(), cur));
        position.put("dpd", b.daysPastDue());
        model.put("txns", lines);
        model.put("totals", totals);
        model.put("position", position);
        return model;
    }

    // ---- models ------------------------------------------------------------------------------

    private Map<String, Object> common(Loan loan, String title, String number, LocalDate on) {
        Letterhead lh = letterheads.current();
        Map<String, Object> letterhead = new LinkedHashMap<>();
        letterhead.put("name", lh.displayName());
        letterhead.put("colour", lh.brandColour());
        letterhead.put("onColour", lh.textOnBrand());
        letterhead.put("tint", tint(lh.brandColour()));
        letterhead.put("footer", lh.receiptFooter());
        letterhead.put(
                "logo",
                lh.logo()
                        .map(l -> "data:" + l.contentType() + ";base64,"
                                + Base64.getEncoder().encodeToString(l.bytes()))
                        .orElse(null));
        Map<String, Object> branch = jdbc.sql("SELECT name, location FROM branches WHERE id = ?")
                .param(loan.branchId())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("location", rs.getString("location"));
                    return m;
                })
                .single();
        MemberSummary member = members.find(loan.memberId()).orElseThrow();
        Map<String, Object> memberModel = new LinkedHashMap<>();
        memberModel.put("name", member.fullName());
        memberModel.put("number", member.memberNo());
        LoanBalances b = repo.balances(loan.id()).orElse(null);
        Map<String, Object> loanModel = new LinkedHashMap<>();
        loanModel.put("number", loan.loanNo());
        loanModel.put(
                "product",
                products.version(loan.productVersionId()).orElseThrow().productCode());
        loanModel.put("currency", loan.currency());
        loanModel.put("status", loan.status().replace('_', ' '));
        loanModel.put("terms", terms(loan));
        loanModel.put("disbursed", b == null || b.disbursedOn() == null ? "" : date(b.disbursedOn()));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", title);
        doc.put("number", number);
        doc.put("date", date(on));
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("lh", letterhead);
        model.put("branch", branch);
        model.put("member", memberModel);
        model.put("loan", loanModel);
        model.put("doc", doc);
        return model;
    }

    private Map<String, Object> txnModel(Loan loan, LoanTransaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", t.currency());
        m.put("amount", money(t.amountMinor(), t.currency()));
        m.put("words", AmountInWords.of(t.amountMinor(), t.currency()));
        m.put("valueDate", date(t.valueDate()));
        m.put("method", METHODS.getOrDefault(t.paymentMethodKey(), String.valueOf(t.paymentMethodKey())));
        m.put("reference", t.externalReference());
        if ("gateway".equals(t.paymentMethodKey())) {
            // The provider's code next to the order tracking id (the reference), for the payer who quotes it.
            m.put("confirmationCode", collections.confirmationCodeOf(t.id()).orElse(null));
        }
        m.put("recordedBy", userName(t.recordedBy()));
        m.put("recordedOn", date(dateOf(t.createdAt())));
        return m;
    }

    /**
     * The receipt as it stood when the payment was recorded: what the payment was applied to is its own
     * allocation rows, and the loan after it is computed from the transactions recorded up to it
     * ({@link LoanLedger#after}), never from today's balances, so the stored receipt is the same
     * whenever it is first opened.
     */
    private void receiptModel(Loan loan, List<LoanTransaction> all, LoanTransaction t, Map<String, Object> model) {
        Map<Integer, LocalDate> dueDates = new LinkedHashMap<>();
        List<ScheduleRow> items = repo.scheduleRows(loan.id());
        items.forEach(r -> dueDates.put(r.no(), r.dueDate()));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (t.txnType().equals("fee_upfront")) {
            rows.add(allocation("", "", "Upfront fee", money(t.amountMinor(), t.currency())));
        } else if (t.txnType().equals("recovery")) {
            rows.add(allocation("", "", "Recovery on a written-off loan", money(t.amountMinor(), t.currency())));
        } else {
            for (AllocationRow a : t.allocations()) {
                if (!COMPONENTS.containsKey(a.component()) || !t.id().equals(a.appliesToTxnId())) {
                    continue; // the early settlement rebate is a waiver, not money received
                }
                rows.add(allocation(
                        a.itemNo() == null ? "" : String.valueOf(a.itemNo()),
                        a.itemNo() == null ? "" : date(dueDates.get(a.itemNo())),
                        COMPONENTS.get(a.component()),
                        money(a.amountMinor(), t.currency())));
            }
        }
        model.put("allocations", rows);
        After now = LoanLedger.after(all, items, t.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put(
                "outstanding",
                money(now.outstandingMinor(), loan.currency()) + " " + loan.currency()
                        + (now.writtenOff() ? " (the loan is written off)" : ""));
        after.put("credit", now.creditMinor() == 0 ? null : money(now.creditMinor(), loan.currency()));
        after.put("nextDue", now.nextDue() == null ? "None" : date(now.nextDue()));
        model.put("after", after);
    }

    private void voucherModel(Loan loan, LoanTransaction t, Map<String, Object> model) {
        long principal = t.amountMinor();
        List<FeeCharge> fees = products.fees(loan.productVersionId(), principal);
        List<Map<String, Object>> deductions = new ArrayList<>();
        long deducted = 0;
        for (Deduction d : deductions(loan, t)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", d.name() + " (deducted)");
            row.put("amount", "-" + money(d.amountMinor(), t.currency()));
            deductions.add(row);
            deducted = Math.addExact(deducted, d.amountMinor());
        }
        long upfront = 0;
        long added = 0;
        for (FeeCharge f : fees) {
            switch (f.timing()) {
                case "paid_upfront" -> upfront = Math.addExact(upfront, f.amountMinor());
                case "added_to_loan" -> added = Math.addExact(added, f.amountMinor());
                default -> {
                    // deducted fees come from the posted journal, above
                }
            }
        }
        if (deducted >= principal) {
            throw ApiException.rule(
                    "fees_exceed_principal", "The deducted fees leave nothing paid out, so there is no voucher.");
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("principal", money(principal, t.currency()));
        v.put("deductions", deductions);
        v.put("net", money(principal - deducted, t.currency()));
        v.put("netWords", AmountInWords.of(principal - deducted, t.currency()));
        v.put("upfront", upfront == 0 ? null : money(upfront, t.currency()) + " " + t.currency());
        v.put("added", added == 0 ? null : money(added, t.currency()) + " " + t.currency());
        model.put("voucher", v);

        Map<String, Object> signs = new LinkedHashMap<>();
        signs.put("preparedBy", userName(t.recordedBy()));
        signs.put("preparedOn", date(dateOf(t.createdAt())));
        Optional<Map<String, Object>> decided = t.approvalRequestId() == null
                ? Optional.empty()
                : jdbc.sql("""
                                SELECT u.full_name, a.decided_by, a.decided_at FROM approval_requests a
                                  LEFT JOIN users u ON u.id = a.decided_by WHERE a.id = ?
                                """)
                        .param(t.approvalRequestId())
                        .query((rs, n) -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put(
                                    "name",
                                    rs.getString("full_name") != null
                                            ? rs.getString("full_name")
                                            : rs.getObject("decided_by") != null ? "a former user" : null);
                            m.put("at", rs.getTimestamp("decided_at"));
                            return m;
                        })
                        .optional();
        if (decided.isPresent() && decided.get().get("name") != null) {
            signs.put("approvedBy", decided.get().get("name"));
            java.sql.Timestamp at = (java.sql.Timestamp) decided.get().get("at");
            signs.put("approvedOn", at == null ? "" : date(dateOf(at.toInstant())));
        } else {
            // FR-APR-04: below the tenant's threshold the disbursement needed no checker.
            signs.put("approvedBy", "no checker needed (below the approval threshold)");
            signs.put("approvedOn", date(t.valueDate()));
        }
        model.put("signs", signs);
    }

    /**
     * The fees taken out of the proceeds, as the disbursement posted them: the total is the credit to fee
     * income on the disbursement's own journal entry, so it is what the ledger holds whatever the product
     * says now. The names come from the product version (immutable); when they do not add up to the
     * posted total, one line "Fees" carries the posted amount.
     */
    private List<Deduction> deductions(Loan loan, LoanTransaction disbursement) {
        List<Deduction> named = products.fees(loan.productVersionId(), disbursement.amountMinor()).stream()
                .filter(f -> f.timing().equals("deducted_at_disbursement"))
                .map(f -> new Deduction(f.name(), f.amountMinor()))
                .toList();
        if (disbursement.journalEntryId() == null) {
            return named;
        }
        long posted = jdbc.sql("""
                        SELECT coalesce(sum(l.credit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.entry_id = ? AND a.system_key = ?
                        """)
                .params(disbursement.journalEntryId(), LoanBooks.FEE_INCOME)
                .query(Long.class)
                .single();
        long sum = named.stream().mapToLong(Deduction::amountMinor).sum();
        if (posted == sum) {
            return named;
        }
        return posted == 0 ? List.of() : List.of(new Deduction("Fees", posted));
    }

    private static Map<String, Object> allocation(String item, String dueDate, String component, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("dueDate", dueDate);
        m.put("component", component);
        m.put("amount", amount);
        return m;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Loan inScope(UUID loanId) {
        Principal principal = CurrentPrincipal.require();
        return loans.find(loanId)
                .filter(l -> principal.may("lending.loans.read", l.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private static void requireDisbursed(Loan loan) {
        if (Set.of("draft", "submitted", "appraised", "approved", "rejected", "cancelled")
                .contains(loan.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Not disbursed",
                    "The loan is " + loan.status() + "; it has no schedule or statement yet.");
        }
    }

    /** A readable number for a document that is not stored: prefix, branch code and loan number (ADR-034, item 5). */
    private String series(String prefix, Loan loan, LocalDate on) {
        String code = jdbc.sql("SELECT code FROM branches WHERE id = ?")
                .param(loan.branchId())
                .query(String.class)
                .single();
        return prefix + "-" + code + "-" + loan.loanNo() + "-" + on.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String userName(UUID userId) {
        if (userId == null) {
            return "the system";
        }
        return jdbc.sql("SELECT full_name FROM users WHERE id = ?")
                .param(userId)
                .query(String.class)
                .optional()
                .orElse("a former user");
    }

    private LocalDate today() {
        return clock.today(zone());
    }

    private ZoneId zone() {
        return currentTenant.profile().timezone();
    }

    private LocalDate dateOf(Instant at) {
        return at == null ? today() : LocalDate.ofInstant(at, zone());
    }

    private static String terms(Loan loan) {
        int term = loan.approvedTermCount() != null ? loan.approvedTermCount() : loan.requestedTermCount();
        String unit = loan.termUnit() + (term == 1 ? "" : "s");
        String rate = String.format(Locale.ENGLISH, "%.2f%%", loan.interestRateBp() / 100.0) + " "
                + loan.rateUnit().replace('_', ' ');
        String pattern = loan.repaymentPattern().equals("bullet")
                ? "one payment at the end"
                : loan.instalmentFrequency() + " instalments";
        return term + " " + unit + ", " + loan.interestMethod() + " interest at " + rate + ", " + pattern;
    }

    static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    /** Thousands separated; currencies with a minor unit show two decimals (UGX has none). */
    static String money(long minor, String currency) {
        if ("UGX".equals(currency)) {
            return String.format(Locale.ENGLISH, "%,d", minor);
        }
        long abs = Math.abs(minor);
        return (minor < 0 ? "-" : "") + String.format(Locale.ENGLISH, "%,d.%02d", abs / 100, abs % 100);
    }

    /** A pale tint of the brand colour for alternate table rows: 88% white over it. */
    static String tint(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        int[] c = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        StringBuilder out = new StringBuilder("#");
        for (int v : c) {
            out.append(String.format("%02X", Math.round(v + (255 - v) * 0.88f)));
        }
        return out.toString();
    }
}
