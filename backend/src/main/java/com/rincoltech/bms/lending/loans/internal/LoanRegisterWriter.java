package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.lending.collateral.CollateralImports;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.ServicingRepository.Txn;
import com.rincoltech.bms.lending.members.MemberImports;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.ProductTerms;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The loader of {@code lending_loans_v1} (issue #136, chapter 13 section 13.10 steps 1 to 7): one batch of register rows,
 * in the batch's transaction. First every borrower is matched or made, in line order, as the validation's rules
 * ({@link LoanRegisterRules}) assigned them: the member the review linked a row of the key to (every row of the key takes
 * it; a key linked to two members is refused), the member an earlier row of the file with the same key made, a member
 * {@link LoanRegister#match} finds, or a new one with the details of the key's latest row by date. Then each row's next of kin (a duplicate collapses), collateral item, loan with its one bullet item, historic
 * disbursement and repayment with its allocations, and the remark as a collection note. Every record is registered in
 * {@code import_refs}. Last, one opening journal per branch, ever: a branch with no import opening journal of loans yet takes
 * one for every imported loan of it still owing principal, one {@code loans_receivable} line per loan; a branch that has one
 * takes none and its new loans are listed for a correcting entry. Imported loans send no SMS, start no task and post no
 * other journal: the historic transactions have none (ADR-071, ADR-004).
 *
 * <p>"Ever" does not rest on {@link Branches#lockBranch}, which reads {@code FOR SHARE}: every batch of the business first
 * takes the business's import key ({@code ImportRunService.LOCK_KEY}, with {@code pg_try_advisory_xact_lock}, held to the
 * batch's commit), so no two batches check and post a branch's journal at once; and the rules refuse a run with two files
 * of one branch ({@code BRANCH_IN_TWO_FILES}, ready-blocking), so a run's journal of a branch holds all its loans.
 *
 * <p>Branch limits are this writer's (BatchWriter): every row's branch is an open branch of the business, read with
 * {@link Branches#lockBranch}, or the batch is refused.
 */
@Component
class LoanRegisterWriter {

    static final String MEMBER = "lending.member";
    static final String KIN = "lending.next_of_kin";
    static final String COLLATERAL = "lending.collateral";
    static final String LOAN = "lending.loan";
    static final String TXN = "lending.loan_transaction";

    /** The rows of one loan besides its transactions, by target type: what the loan of a row also made. */
    private static final String CHILD_ROWS = """
            SELECT 'lending.loan_status_history', id FROM lending_loan_status_history WHERE loan_id = ?
            UNION ALL SELECT 'lending.schedule_item', id FROM lending_schedule_items WHERE loan_id = ?
            UNION ALL SELECT 'lending.repayment_allocation', a.id FROM lending_repayment_allocations a
                       JOIN lending_loan_transactions t ON t.id = a.transaction_id WHERE t.loan_id = ?
            UNION ALL SELECT 'lending.loan_collateral', id FROM lending_loan_collateral WHERE loan_id = ?
            UNION ALL SELECT 'lending.collection_action', id FROM lending_collection_actions WHERE loan_id = ?
            """;

    private final JdbcClient jdbc;
    private final LoanRepository loans;
    private final ServicingRepository servicing;
    private final MemberImports members;
    private final CollateralImports collateral;
    private final ProductCatalog products;
    private final Branches branches;
    private final CurrentTenant tenant;
    private final TenantSequences sequences;
    private final AuditLog audit;

    LoanRegisterWriter(
            JdbcClient jdbc,
            LoanRepository loans,
            ServicingRepository servicing,
            MemberImports members,
            CollateralImports collateral,
            ProductCatalog products,
            Branches branches,
            CurrentTenant tenant,
            TenantSequences sequences,
            AuditLog audit) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.servicing = servicing;
        this.members = members;
        this.collateral = collateral;
        this.products = products;
        this.branches = branches;
        this.tenant = tenant;
        this.sequences = sequences;
        this.audit = audit;
    }

    /** The loader of the entity set; it keeps nothing from one batch to the next. */
    BatchWriter loader(String entitySet) {
        if (!LendingLoansV1Template.LOANS.equals(entitySet)) {
            throw new IllegalArgumentException("lending_loans_v1 has no entity set " + entitySet);
        }
        return new BatchWriter() {
            @Override
            public Map<String, Object> write(UUID runId, UUID batchId, int seq, Checkpoint checkpoint) {
                return LoanRegisterWriter.this.write(runId, checkpoint);
            }

            @Override
            public String identity() {
                return LoanRegisterWriter.class.getName() + " " + LendingLoansV1Template.KEY + "_v"
                        + LendingLoansV1Template.VERSION;
            }
        };
    }

    /** A row read for writing: its staged row, what it gives, its branch, product and member key. */
    private record Planned(
            ImportRefs.StagedRow staged,
            LoanRegister.Row row,
            Branches.Branch branch,
            ProductTerms product,
            String key) {}

    Map<String, Object> write(UUID runId, BatchWriter.Checkpoint checkpoint) {
        // 13.8 row 15: the requested principal is taken as disbursed in full. The run records the customer's decision
        // (DISBURSED_POLICY); ready, the commit's request and the commit refuse a run without it, so the loader only
        // writes by it.
        LocalDate asAt = cutoverDay(jdbc, runId, tenant);
        if (asAt == null) {
            throw new IllegalStateException("the run has no cutover moment to age the loans to");
        }
        UUID officer = jdbc.sql("SELECT created_by FROM import_runs WHERE id = ?")
                .param(runId)
                .query(UUID.class)
                .single();
        boolean officerIsStaff = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)")
                .param(officer)
                .query(Boolean.class)
                .single();
        Map<String, Branches.Branch> byCode = new HashMap<>();
        Branches.Branch headOffice = null;
        for (Branches.Branch b : branches.all()) {
            byCode.put(b.code().toUpperCase(Locale.ROOT), b);
            if (b.headOffice()) {
                headOffice = b;
            }
        }
        // The rows, read once: the new ones planned, the ones the business's imports hold counted as existing.
        List<Planned> planned = new ArrayList<>();
        int read = 0;
        int existing = 0;
        int after = 0;
        Map<UUID, Branches.Branch> locked = new HashMap<>();
        Map<String, ProductTerms> productByCode = new HashMap<>();
        while (true) {
            List<ImportRefs.StagedRow> chunk = checkpoint.rows(after, 1_000);
            for (ImportRefs.StagedRow s : chunk) {
                after = s.line();
                read++;
                if (s.status() == ImportRefs.Status.EXISTING) {
                    existing++;
                    continue;
                }
                LoanRegister.Row r;
                try {
                    r = LoanRegister.row(s.values());
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("line " + s.line() + " is not a loan the register's rules can"
                            + " write; leave it out or complete it");
                }
                Branches.Branch branch =
                        r.branch() == null ? headOffice : byCode.get(r.branch().toUpperCase(Locale.ROOT));
                if (branch == null) {
                    throw new IllegalStateException("line " + s.line() + " names no branch of the business");
                }
                Branches.Branch open = locked.computeIfAbsent(
                        branch.id(), id -> branches.lockBranch(id).orElse(null));
                if (open == null || !open.active()) {
                    throw new IllegalStateException(
                            "line " + s.line() + " is for a branch the import may not write to");
                }
                ProductTerms product = productByCode.computeIfAbsent(
                        r.product(), code -> products.currentByCode(code).orElse(null));
                if (product == null || !"active".equals(product.productStatus())) {
                    throw new IllegalStateException("line " + s.line() + " names no active loan product");
                }
                planned.add(new Planned(
                        s, r, open, product, LoanRegister.memberKey(r.nin(), r.phone(), "line:" + s.line())));
            }
            if (chunk.size() < 1_000) {
                break;
            }
        }
        // Step 1: the members, in line order; a key's details are its latest row's by date (13.9).
        Map<String, LoanRegister.Row> latest = new HashMap<>();
        for (Planned p : planned) {
            latest.merge(p.key(), p.row(), (a, b) -> b.disbursedOn().isBefore(a.disbursedOn()) ? a : b);
        }
        // A review's link_member is the borrower's, so every row of the same key takes that member; a key linked to two
        // members is refused, the reviewer to link its rows alike.
        Map<String, UUID> byKey = new HashMap<>();
        for (Planned p : planned) {
            UUID linked = p.staged().linkedMemberId();
            UUID held = linked == null ? null : byKey.putIfAbsent(p.key(), linked);
            if (held != null && !held.equals(linked)) {
                throw new IllegalStateException("line " + p.staged().line() + " is linked to another member than an"
                        + " earlier row of the same borrower; link both rows to one member");
            }
        }
        Map<Integer, UUID> memberOf = new HashMap<>();
        int made = 0;
        for (Planned p : planned) {
            UUID member;
            if (p.staged().linkedMemberId() != null) {
                member = p.staged().linkedMemberId();
            } else if (byKey.containsKey(p.key())) {
                member = byKey.get(p.key());
            } else {
                LoanRegister.Row r = p.row();
                LoanRegister.Match m = LoanRegister.match(
                        r.nin(),
                        r.name(),
                        r.nin() == null ? Optional.empty() : members.byNin(r.nin()),
                        members.byPhone(r.phone()));
                if (m.kind() == LoanRegister.MatchKind.MATCHED) {
                    member = m.member().id();
                } else {
                    LoanRegister.Row d = latest.get(p.key());
                    member = members.create(new MemberImports.NewMember(
                            p.branch().id(),
                            d.name(),
                            d.phone(),
                            d.altPhone(),
                            d.idType(),
                            d.nin(),
                            d.otherId(),
                            d.marital(),
                            d.location(),
                            d.occupation(),
                            d.otherIncome(),
                            p.product().currency(),
                            officer,
                            officer));
                    checkpoint.register(p.staged().sourceRef(), MEMBER, member);
                    made++;
                }
                byKey.put(p.key(), member);
            }
            memberOf.put(p.staged().line(), member);
        }
        // Steps 2 to 6, row by row.
        Map<String, long[]> written = new TreeMap<>();
        Map<String, Map<String, long[]>> buckets = new TreeMap<>();
        Map<String, Branches.Branch> touched = new TreeMap<>();
        int loansMade = 0;
        int kinMade = 0;
        int itemsMade = 0;
        for (Planned p : planned) {
            UUID member = memberOf.get(p.staged().line());
            String ref = p.staged().sourceRef();
            LoanRegister.Row r = p.row();
            if (r.kinName() != null) {
                MemberImports.KinHeld held = members.kinOf(member, r.kinNin(), r.kinPhone(), r.kinName());
                if (held.kinId() == null) {
                    UUID linked = null;
                    String method = null;
                    String status = "none";
                    Optional<MemberImports.Held> byNin =
                            r.kinNin() == null ? Optional.empty() : members.byNin(r.kinNin());
                    List<MemberImports.Held> byPhone = r.kinPhone() == null ? List.of() : members.byPhone(r.kinPhone());
                    if (byNin.isPresent() && !byNin.get().id().equals(member)) {
                        linked = byNin.get().id();
                        method = "nin";
                        status = "confirmed";
                    } else if (byNin.isEmpty()
                            && byPhone.size() == 1
                            && !byPhone.getFirst().id().equals(member)) {
                        linked = byPhone.getFirst().id();
                        method = "phone";
                        status = "suggested";
                    }
                    UUID kin = members.addKin(new MemberImports.NewKin(
                            member,
                            r.kinName(),
                            r.kinPhone(),
                            r.kinNin(),
                            r.kinRelationship(),
                            r.kinRelationshipText(),
                            r.kinLocation(),
                            !held.hasPrimary(),
                            linked,
                            method,
                            status,
                            officer));
                    checkpoint.register(ref, KIN, kin);
                    kinMade++;
                }
            }
            UUID item = null;
            if (r.collateral() != null) {
                LoanRegister.Collateral c = r.collateral();
                item = collateral.create(
                        p.branch().id(),
                        member,
                        c.type(),
                        c.description(),
                        c.reference(),
                        p.product().currency(),
                        LoanRegister.plan(r, p.product().allocationOrder(), asAt)
                                .position()
                                .settled(),
                        officer);
                checkpoint.register(ref, COLLATERAL, item);
                itemsMade++;
            }
            BringIn.Plan plan = writeLoan(p, member, item, officer, officerIsStaff, asAt, checkpoint);
            loansMade++;
            String code = p.branch().code();
            touched.put(code, p.branch());
            long owed = plan.position().principalOutstandingMinor();
            written.computeIfAbsent(code, k -> new long[2])[0] += owed;
            written.get(code)[1]++;
            if (owed > 0) {
                long[] b = buckets.computeIfAbsent(code, k -> new TreeMap<>())
                        .computeIfAbsent(LoanRegister.bucket(plan.position().daysPastDue()), k -> new long[2]);
                b[0]++;
                b[1] += owed;
            }
            checkpoint.rowWritten();
        }
        // Step 7: one opening journal per branch, ever.
        for (Branches.Branch b : touched.values()) {
            openingLoans(b, asAt, checkpoint);
        }
        written.forEach((code, s) -> checkpoint.loanPrincipal(code, s[0]));
        buckets.forEach((code, byBucket) ->
                byBucket.forEach((bucket, s) -> checkpoint.arrears(code, bucket, Math.toIntExact(s[0]), s[1])));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("read", read);
        counts.put("written", planned.size());
        counts.put("existing", existing);
        counts.put("skipped", 0);
        counts.put("members", made);
        counts.put("next_of_kin", kinMade);
        counts.put("collateral", itemsMade);
        counts.put("loans", loansMade);
        Map<String, Object> after2 = new LinkedHashMap<>(counts);
        after2.put("run_id", runId.toString());
        audit.record(AuditLog.Entry.created("lending.loan.register_imported", "import_run", runId, null, after2));
        return counts;
    }

    /** Step 4 to 6 for one row: the loan, its item, the historic transactions, its position as at the cutover day. */
    private BringIn.Plan writeLoan(
            Planned p,
            UUID member,
            UUID item,
            UUID officer,
            boolean officerIsStaff,
            LocalDate asAt,
            BatchWriter.Checkpoint checkpoint) {
        LoanRegister.Row r = p.row();
        ProductTerms product = p.product();
        String ref = p.staged().sourceRef();
        // The plan by the one function the forecast predicts by, on the stored items so its rows name them.
        BringIn.Plan predicted = LoanRegister.plan(r, product.allocationOrder(), asAt);
        String status = predicted.position().settled() ? "closed" : "active";
        UUID loanId = UUID.randomUUID();
        Loan loan = new Loan(
                loanId,
                "LN%06d".formatted(sequences.next("loan_no")),
                p.branch().id(),
                member,
                product.versionId(),
                officer,
                // A draft first: a pledge is added only to a draft (the trigger lending_loan_collateral_guard, V8).
                "draft",
                "import",
                "other",
                r.purpose(),
                product.currency(),
                r.principal(),
                r.termCount(),
                r.principal(),
                r.termCount(),
                r.termUnit(),
                "flat",
                r.rateBp(),
                "per_term",
                "bullet",
                null,
                r.disbursedOn(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                officer,
                null,
                null,
                1);
        loans.insert(loan);
        // JUSTIFICATION-A3: the pledge insert moved up from below, while the loan is still a draft.
        if (item != null) {
            jdbc.sql("""
                            INSERT INTO lending_loan_collateral (id, tenant_id, loan_id, collateral_id, pledged_value_minor,
                                released_at)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, CASE WHEN ? THEN now() END)
                            """)
                    .params(
                            UUID.randomUUID(),
                            loanId,
                            item,
                            r.principal(),
                            predicted.position().settled())
                    .update();
        }
        // 13.8 row 15, the run's decision: the requested principal was disbursed in full; the loan leaves the draft.
        jdbc.sql("""
                        UPDATE lending_loans SET status = ?, approved_principal_minor = ?, approved_term_count = ?
                         WHERE id = ?
                        """).params(status, r.principal(), r.termCount(), loanId).update();
        jdbc.sql("""
                        INSERT INTO lending_loan_status_history (id, tenant_id, loan_id, from_status, to_status, changed_by,
                            reason)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, NULL, ?, ?, 'imported')
                        """).params(UUID.randomUUID(), loanId, status, officer).update();
        checkpoint.register(ref, LOAN, loanId);
        servicing.insertItems(loanId, LoanRegister.schedule(r), asAt);
        List<Item> items = servicing.items(loanId);
        BringIn.Plan plan = BringIn.apply(items, LoanRegister.lines(r), "flat", false, product.allocationOrder(), asAt);
        Txn disbursement = historic(loan, "disbursement", r.principal(), r.disbursedOn(), officer);
        servicing.insertHistoricTxn(disbursement);
        checkpoint.register(ref, TXN, disbursement.id());
        for (BringIn.Paid paid : plan.paid()) {
            Txn txn = historic(
                    loan, "repayment", paid.line().amountMinor(), paid.line().valueDate(), officer);
            servicing.insertHistoricTxn(txn);
            servicing.insertAllocations(txn.id(), txn.id(), paid.applied().allocations());
            checkpoint.register(ref, TXN, txn.id());
        }
        servicing.saveItems(items, asAt);
        servicing.disbursed(loanId, r.disbursedOn(), r.dueOn(), r.principal());
        servicing.position(loanId, plan.position());
        servicing.receipts(loanId, plan.totalPaidMinor(), plan.lastRepaymentOn(), LoanRegister.credit(plan));
        if (plan.position().settled()) {
            servicing.closedOn(loanId, plan.settledOn());
        }
        if (r.remarks() != null && officerIsStaff) {
            jdbc.sql("""
                            INSERT INTO lending_collection_actions (id, tenant_id, loan_id, action_type, note, performed_by,
                                performed_at)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, 'other', ?, ?, now())
                            """)
                    .params(UUID.randomUUID(), loanId, r.remarks(), officer)
                    .update();
        }
        if (!predicted.position().equals(plan.position())) {
            throw new IllegalStateException("the loan of line " + p.staged().line() + " differs from its forecast");
        }
        // Rule 4: every record the row made is in import_refs, the loan's own rows too (its status history, schedule
        // items, allocations, pledge and collection note).
        jdbc.sql(CHILD_ROWS)
                .params(loanId, loanId, loanId, loanId, loanId)
                .query((rs, n) -> {
                    checkpoint.register(ref, rs.getString(1), rs.getObject(2, UUID.class));
                    return null;
                })
                .list();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("loan_no", loan.loanNo());
        after.put("status", status);
        after.put("channel", "import");
        after.put("principal_outstanding_minor", plan.position().principalOutstandingMinor());
        after.put("days_past_due", plan.position().daysPastDue());
        audit.record(
                AuditLog.Entry.created("lending.loan.imported", LoanServicer.SUBJECT, loanId, loan.branchId(), after));
        return plan;
    }

    private static Txn historic(Loan loan, String type, long amount, LocalDate valueDate, UUID by) {
        return new Txn(
                UUID.randomUUID(),
                loan.branchId(),
                loan.id(),
                type,
                amount,
                loan.currency(),
                valueDate,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "import",
                by,
                null);
    }

    // ---------------------------------------------------------------------------------------------
    // Step 7: one opening journal per branch, ever. The one rule the loader posts by and the forecast predicts by.
    // ---------------------------------------------------------------------------------------------

    /**
     * The branch's opening journals, when it has none of the kind yet (13.10 step 7): debit {@code loans_receivable}, one
     * line per imported loan of the branch still owing principal (subledger the loan), credit equity; and credit
     * {@code member_overpayments} for their members' credits. A branch that already has one takes none, and the batch's
     * own loans of it are listed for a correcting entry.
     */
    void openingLoans(Branches.Branch branch, LocalDate asAt, BatchWriter.Checkpoint checkpoint) {
        for (String kind : List.of(LendingLoansV1Template.RECEIVABLE_KIND, LendingLoansV1Template.CREDIT_KIND)) {
            boolean credit = LendingLoansV1Template.CREDIT_KIND.equals(kind);
            List<ImportRefs.OpeningLine> lines = new ArrayList<>();
            jdbc.sql("""
                            SELECT l.id, l.principal_outstanding_minor, l.credit_balance_minor FROM lending_loans l
                             WHERE l.branch_id = ?
                               AND EXISTS (SELECT 1 FROM import_refs r WHERE r.target_id = l.id AND r.target_type = ?)
                             ORDER BY l.loan_no
                            """)
                    .params(branch.id(), LOAN)
                    .query((rs, n) -> {
                        long amount = rs.getLong(credit ? 3 : 2);
                        if (amount > 0) {
                            lines.add(new ImportRefs.OpeningLine(
                                    LoanBooks.SUBLEDGER, rs.getObject(1, UUID.class), amount));
                        }
                        return null;
                    })
                    .list();
            long total = lines.stream()
                    .mapToLong(ImportRefs.OpeningLine::amountMinor)
                    .sum();
            if (total == 0) {
                continue;
            }
            if (!openedBranches(jdbc, kind, null).contains(branch.code())) {
                checkpoint.openingJournal(new ImportRefs.Opening(
                        branch.id(),
                        branch.code(),
                        kind,
                        asAt,
                        credit ? "member_overpayments" : LoanBooks.LOANS_RECEIVABLE,
                        !credit,
                        total,
                        null,
                        lines));
            } else {
                checkpoint.note("Branch " + branch.code() + " already has its import opening journal of " + kind
                        + "; the loans this file adds there are not in it: post a correcting entry for them.");
            }
        }
    }

    /**
     * The codes of the branches that already have an import opening journal of the kind ({@code opening|<kind>|...} in
     * {@code import_refs}); one of {@code runId} itself is left out (null for none), so the forecast of a committed run
     * still predicts the journals it posted.
     */
    static List<String> openedBranches(JdbcClient jdbc, String kind, UUID runId) {
        return jdbc.sql("""
                        SELECT DISTINCT b.code FROM import_refs r
                          JOIN journal_entries e ON e.id = r.target_id
                          JOIN branches b ON b.id = e.branch_id
                         WHERE r.target_type = 'ledger.journal_entry' AND r.source_key LIKE ?
                           AND r.run_id IS DISTINCT FROM CAST(? AS uuid)
                        """)
                .params("opening|" + kind + "|%", runId)
                .query(String.class)
                .list();
    }

    /**
     * Per branch code, the principal and the credit that loans imported by runs other than {@code runId} still hold: what
     * a branch's first opening journal values besides the run's own rows.
     */
    static Map<String, long[]> importedLoans(JdbcClient jdbc, UUID runId) {
        Map<String, long[]> out = new TreeMap<>();
        jdbc.sql("""
                        SELECT b.code, sum(l.principal_outstanding_minor), sum(l.credit_balance_minor)
                          FROM lending_loans l JOIN branches b ON b.id = l.branch_id
                         WHERE EXISTS (SELECT 1 FROM import_refs r WHERE r.target_id = l.id AND r.target_type = ?
                                         AND r.run_id IS DISTINCT FROM CAST(? AS uuid))
                         GROUP BY b.code
                        """)
                .params(LOAN, runId)
                .query((rs, n) -> out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)}))
                .list();
        return out;
    }

    /** The day of the run's cutover moment in the business's zone, or null when none is agreed. */
    static LocalDate cutoverDay(JdbcClient jdbc, UUID runId, CurrentTenant tenant) {
        Timestamp at = jdbc.sql("SELECT cutover_at FROM import_runs WHERE id = ?")
                .param(runId)
                .query(Timestamp.class)
                .optional()
                .orElse(null);
        return at == null
                ? null
                : LocalDate.ofInstant(at.toInstant(), tenant.profile().timezone());
    }

    /**
     * The books' arrears (issue #134): the loans given, by branch and days-past-due bucket as at {@code asAt}, from their
     * schedule items in the loan book ({@link Servicing#position}); a loan owing no principal is in none.
     */
    List<ImportForecast.ArrearsBucket> arrears(List<UUID> loanIds, LocalDate asAt) {
        Map<String, Map<String, long[]>> buckets = new TreeMap<>();
        if (loanIds.isEmpty()) {
            return List.of();
        }
        jdbc.sql("""
                        SELECT l.id, b.code FROM lending_loans l JOIN branches b ON b.id = l.branch_id
                         WHERE l.id = ANY(?) ORDER BY b.code, l.loan_no
                        """)
                .param(loanIds.toArray(UUID[]::new))
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)})
                .list()
                .forEach(row -> {
                    Servicing.Position p = Servicing.position(servicing.items((UUID) row[0]), asAt);
                    if (p.principalOutstandingMinor() > 0) {
                        long[] s = buckets.computeIfAbsent((String) row[1], k -> new TreeMap<>())
                                .computeIfAbsent(LoanRegister.bucket(p.daysPastDue()), k -> new long[2]);
                        s[0]++;
                        s[1] += p.principalOutstandingMinor();
                    }
                });
        List<ImportForecast.ArrearsBucket> out = new ArrayList<>();
        buckets.forEach((code, byBucket) -> byBucket.forEach(
                (bucket, s) -> out.add(new ImportForecast.ArrearsBucket(code, bucket, Math.toIntExact(s[0]), s[1]))));
        out.sort(Comparator.comparing(ImportForecast.ArrearsBucket::branch)
                .thenComparing(ImportForecast.ArrearsBucket::bucket));
        return out;
    }
}
