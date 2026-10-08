package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.retail.cashbook.CashBookHistory;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailHistory;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the imported cash book rows straight into the tables, flagged historical, with no journal
 * (ADR-022 decision 18). Every method runs in the caller's transaction; the importer commits one
 * transaction per file, and an advance and its repayments stay consistent because each commit holds
 * a {@code repaid_minor} equal to the sum of the repayments written with it.
 */
@Service
class JdbcCashBookHistory implements CashBookHistory {

    private static final UUID BY = RetailHistory.IMPORT_ACTOR;

    private final JdbcClient jdbc;
    private final CashFigures figures;
    private final RetailBooks books;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;

    JdbcCashBookHistory(
            JdbcClient jdbc, CashFigures figures, RetailBooks books, TenantSequences sequences, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.figures = figures;
        this.books = books;
        this.sequences = sequences;
        this.tenant = tenant;
    }

    private String currency() {
        return tenant.profile().currency();
    }

    // ------------------------------------------------------------------------------------ lists

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureCategory(String name) {
        Optional<UUID> existing = jdbc.sql("SELECT id FROM retail_expense_categories WHERE lower(name) = lower(?)")
                .param(name)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return new Ensured(existing.get(), false);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_expense_categories (id, tenant_id, name, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                        """).params(id, name, BY).update();
        return new Ensured(id, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseItem ensureExpenseItem(String category, String item, boolean requiresExplanation) {
        Ensured cat = ensureCategory(category);
        String categoryName = jdbc.sql("SELECT name FROM retail_expense_categories WHERE id = ?")
                .param(cat.id())
                .query(String.class)
                .single();
        record Found(UUID id, String name, boolean requires) {}
        Optional<Found> existing = jdbc.sql("""
                        SELECT id, name, requires_explanation FROM retail_expense_items
                         WHERE category_id = ? AND lower(name) = lower(?)
                        """)
                .params(cat.id(), item)
                .query((rs, n) -> new Found(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)))
                .optional();
        if (existing.isPresent()) {
            Found f = existing.get();
            return new ExpenseItem(cat.id(), f.id(), categoryName, f.name(), f.requires(), cat.created(), false);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_expense_items (id, tenant_id, category_id, name, requires_explanation, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(id, cat.id(), item, requiresExplanation, BY).update();
        return new ExpenseItem(cat.id(), id, categoryName, item, requiresExplanation, cat.created(), true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ensured ensureParty(String kind, String name, String contact) {
        Optional<UUID> existing = jdbc.sql(
                        "SELECT id FROM retail_cash_parties WHERE kind = ? AND lower(name) = lower(?)")
                .params(kind, name)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return new Ensured(existing.get(), false);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_cash_parties (id, tenant_id, name, contact, kind, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(id, name, contact, kind, BY).update();
        return new Ensured(id, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Party> findParty(String name, Collection<String> kinds) {
        String filter = kinds.isEmpty() ? "" : " AND kind IN (:kinds)";
        var query = jdbc.sql("SELECT id, name, kind FROM retail_cash_parties WHERE lower(name) = lower(:name)" + filter
                        + " ORDER BY created_at, id LIMIT 1")
                .param("name", name);
        if (!kinds.isEmpty()) {
            query = query.param("kinds", List.copyOf(kinds));
        }
        return query.query((rs, n) -> new Party(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional();
    }

    // ---------------------------------------------------------------------------------- records

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> importSavings(
            UUID branchId, LocalDate date, long amountMinor, long totalSoldMinor, java.time.Instant at) {
        long active = jdbc.sql("""
                        SELECT count(*) FROM retail_daily_savings
                         WHERE branch_id = ? AND business_date = ? AND voided_at IS NULL
                        """)
                .params(branchId, Date.valueOf(date))
                .query(Long.class)
                .single();
        if (active > 0) {
            return Optional.empty();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_daily_savings (id, tenant_id, created_at, branch_id, business_date, amount_minor,
                            currency, suggested_minor, overwritten, total_sold_minor, occurred_at, recorded_by, historical)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :branch, :date, :amount, :currency,
                            NULL, false, :sold, :at, :by, true)
                        """)
                .param("id", id)
                .param("at", Timestamp.from(at))
                .param("branch", branchId)
                .param("date", Date.valueOf(date))
                .param("amount", amountMinor)
                .param("currency", currency())
                .param("sold", totalSoldMinor)
                .param("by", BY)
                .update();
        return Optional.of(id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID importExpense(Expense e) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_expenses (id, tenant_id, created_at, branch_id, business_date, category_id,
                            item_id, category_name, item_name, party_id, amount_minor, currency, explanation, occurred_at,
                            recorded_by, historical)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :branch, :date, :category, :item,
                            :categoryName, :itemName, :party, :amount, :currency, :explanation, :at, :by, true)
                        """)
                .param("id", id)
                .param("at", Timestamp.from(e.at()))
                .param("branch", e.branchId())
                .param("date", Date.valueOf(e.date()))
                .param("category", e.item().categoryId())
                .param("item", e.item().itemId())
                .param("categoryName", e.item().categoryName())
                .param("itemName", e.item().itemName())
                .param("party", e.partyId())
                .param("amount", e.amountMinor())
                .param("currency", currency())
                .param("explanation", e.explanation())
                .param("by", BY)
                .update();
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Banked importBanking(UUID branchId, LocalDate date, long amountMinor, java.time.Instant bankedAt) {
        long expected = figures.day(branchId, date).expectedToBank();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_cash_bankings (id, tenant_id, created_at, branch_id, business_date, amount_minor,
                            currency, expected_minor, banked_at, recorded_by, historical)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :branch, :date, :amount, :currency,
                            :expected, :at, :by, true)
                        """)
                .param("id", id)
                .param("at", Timestamp.from(bankedAt))
                .param("branch", branchId)
                .param("date", Date.valueOf(date))
                .param("amount", amountMinor)
                .param("currency", currency())
                .param("expected", expected)
                .param("by", BY)
                .update();
        return new Banked(id, expected);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID importWithdrawal(UUID branchId, LocalDate date, long amountMinor, java.time.Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_cash_withdrawals (id, tenant_id, created_at, branch_id, business_date,
                            amount_minor, currency, withdrawn_at, recorded_by, historical)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :branch, :date, :amount, :currency,
                            :at, :by, true)
                        """)
                .param("id", id)
                .param("at", Timestamp.from(at))
                .param("branch", branchId)
                .param("date", Date.valueOf(date))
                .param("amount", amountMinor)
                .param("currency", currency())
                .param("by", BY)
                .update();
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Advanced importAdvance(Advance a) {
        UUID id = UUID.randomUUID();
        String no = "RA%08d".formatted(sequences.next("retail_advance_no"));
        jdbc.sql("""
                        INSERT INTO retail_advances (id, tenant_id, created_at, advance_no, branch_id, party_id,
                            taken_by_party_id, principal_minor, currency, purpose, business_date, repaid_minor, note,
                            occurred_at, recorded_by, historical)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :no, :branch, :party, :takenBy,
                            :principal, :currency, :purpose, :date, 0, :note, :at, :by, true)
                        """)
                .param("id", id)
                .param("at", Timestamp.from(a.at()))
                .param("no", no)
                .param("branch", a.branchId())
                .param("party", a.partyId())
                .param("takenBy", a.takenByPartyId())
                .param("principal", a.principalMinor())
                .param("currency", currency())
                .param("purpose", a.purpose())
                .param("date", Date.valueOf(a.date()))
                .param("note", a.note())
                .param("by", BY)
                .update();
        return new Advanced(id, no);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> importRepayment(
            UUID advanceId, long amountMinor, String method, LocalDate paidOn, java.time.Instant at) {
        record Row(UUID branch, long principal, long repaid) {}
        Row advance = jdbc.sql(
                        "SELECT branch_id, principal_minor, repaid_minor FROM retail_advances WHERE id = ? FOR UPDATE")
                .param(advanceId)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)))
                .single();
        if (amountMinor > advance.principal() - advance.repaid()) {
            return Optional.empty();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO retail_advance_repayments (id, tenant_id, created_at, advance_id, branch_id,
                            amount_minor, currency, method, paid_on, recorded_by, historical)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :at, :advance, :branch, :amount, :currency,
                            :method, :paidOn, :by, true)
                        """)
                .param("id", id)
                .param("at", Timestamp.from(at))
                .param("advance", advanceId)
                .param("branch", advance.branch())
                .param("amount", amountMinor)
                .param("currency", currency())
                .param("method", method)
                .param("paidOn", Date.valueOf(paidOn))
                .param("by", BY)
                .update();
        jdbc.sql("UPDATE retail_advances SET repaid_minor = repaid_minor + ? WHERE id = ?")
                .params(amountMinor, advanceId)
                .update();
        return Optional.of(id);
    }

    // ---------------------------------------------------------------------------------- opening

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OpeningPosted> postOpening(
            UUID branchId, LocalDate day, long cashMinor, long bankMinor, long savingsMinor) {
        Map<UUID, Long> outstanding = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT a.id, %s FROM retail_advances a
                         WHERE a.branch_id = ? AND a.historical AND a.voided_at IS NULL AND %s > 0
                           AND NOT EXISTS (%s)
                         ORDER BY a.business_date, a.advance_no
                        """.formatted(HISTORIC_REMAINING, HISTORIC_REMAINING, OPENED_LINE))
                .param(branchId)
                .query((rs, n) -> outstanding.put(rs.getObject(1, UUID.class), rs.getLong(2)))
                .list();
        long advances = outstanding.values().stream().mapToLong(Long::longValue).sum();
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit("cash_on_hand", cashMinor));
        // An overdrawn bank is a credit line; the equity line then carries the net.
        legs.add(bankMinor >= 0 ? Leg.debit("bank", bankMinor) : Leg.credit("bank", -bankMinor));
        legs.add(Leg.debit("savings_reserve", savingsMinor));
        outstanding.forEach((id, remaining) ->
                legs.add(Leg.debit("owner_advances", remaining).withSubledger(CashPostings.ADVANCE_SUBLEDGER, id)));
        long total = Math.addExact(Math.addExact(Math.addExact(cashMinor, bankMinor), savingsMinor), advances);
        legs.add(
                total >= 0 ? Leg.credit("opening_balance_equity", total) : Leg.debit("opening_balance_equity", -total));
        return books.post(new Posting(
                        branchId,
                        day,
                        "Cash book opening",
                        "Opening cash, bank, savings reserve and advances, from the import",
                        CashPostings.OPENING,
                        null,
                        CashPostings.key(CashPostings.OPENING, branchId),
                        legs))
                .map(e -> new OpeningPosted(
                        e.entryId(), e.entryNo(), cashMinor, bankMinor, savingsMinor, outstanding.size(), advances));
    }

    /**
     * An opening line of the advance {@code a.id}: a debit of owner advances on an opening entry. A live
     * repayment also names the advance as subledger (a credit), so the test must not be "any line".
     */
    private static final String OPENED_LINE = """
            SELECT 1 FROM journal_lines l
              JOIN journal_entries e ON e.id = l.entry_id
              JOIN gl_accounts g ON g.id = l.account_id
             WHERE l.subledger_type = 'retail.advance' AND l.subledger_id = a.id
               AND l.debit > 0 AND g.system_key = 'owner_advances'
               AND e.source_type IN ('retail.cash_opening', 'retail.advance_opening')
            """;

    /**
     * What the opening carries: the principal less the imported repayments only. A live repayment posts
     * its own credit, so counting it here would take it off the ledger twice.
     */
    private static final String HISTORIC_REMAINING = """
            (a.principal_minor - coalesce((SELECT sum(r.amount_minor) FROM retail_advance_repayments r
                                            WHERE r.advance_id = a.id AND r.historical), 0))""";

    private static final String UNOPENED = """
            FROM retail_advances a
             WHERE a.historical AND a.voided_at IS NULL AND %s > 0
               AND NOT EXISTS (%s)
            """.formatted(HISTORIC_REMAINING, OPENED_LINE);

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public java.util.Set<UUID> branchesWithUnopenedAdvances() {
        return new java.util.LinkedHashSet<>(jdbc.sql("SELECT DISTINCT a.branch_id " + UNOPENED)
                .query(UUID.class)
                .list());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean advanceOpened(UUID advanceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_advances a WHERE a.id = ? AND EXISTS (" + OPENED_LINE
                        + "))")
                .param(advanceId)
                .query(Boolean.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AdvanceOpened> postAdvanceOpenings(LocalDate day) {
        record Pending(UUID id, UUID branch, String no, long remaining) {}
        List<Pending> pending = jdbc.sql("SELECT a.id, a.branch_id, a.advance_no, " + HISTORIC_REMAINING
                        + " "
                        + UNOPENED + " ORDER BY a.branch_id, a.business_date, a.advance_no")
                .query((rs, n) -> new Pending(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4)))
                .list();
        List<AdvanceOpened> out = new ArrayList<>();
        for (Pending p : pending) {
            books.post(new Posting(
                            p.branch(),
                            day,
                            "Advance opening " + p.no(),
                            "Opening balance of an imported advance not covered by the branch's opening entry",
                            CashPostings.ADVANCE_OPENING,
                            p.id(),
                            CashPostings.key(CashPostings.ADVANCE_OPENING, p.id()),
                            List.of(
                                    Leg.debit("owner_advances", p.remaining())
                                            .withSubledger(CashPostings.ADVANCE_SUBLEDGER, p.id()),
                                    Leg.credit("opening_balance_equity", p.remaining()))))
                    .ifPresent(e -> out.add(
                            new AdvanceOpened(p.branch(), p.id(), p.no(), p.remaining(), e.entryId(), e.entryNo())));
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> balanceOpenedRepayment(UUID advanceId, UUID repaymentId, long amountMinor, LocalDate day) {
        UUID branchId = jdbc.sql("SELECT branch_id FROM retail_advances WHERE id = ?")
                .param(advanceId)
                .query(UUID.class)
                .single();
        return books.post(new Posting(
                        branchId,
                        day,
                        "Advance opening correction",
                        "Imported repayment of an advance already opened for the earlier balance",
                        CashPostings.REPAYMENT_OPENING,
                        repaymentId,
                        CashPostings.key(CashPostings.REPAYMENT_OPENING, repaymentId),
                        List.of(
                                Leg.debit("opening_balance_equity", amountMinor),
                                Leg.credit("owner_advances", amountMinor)
                                        .withSubledger(CashPostings.ADVANCE_SUBLEDGER, advanceId))))
                .map(e -> e.entryNo());
    }
}
