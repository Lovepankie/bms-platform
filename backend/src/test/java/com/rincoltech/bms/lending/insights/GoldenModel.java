package com.rincoltech.bms.lending.insights;

import com.rincoltech.bms.TestDatabase;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An independent recomputation of the insights metrics from the raw rows of one tenant, read as
 * the table owner and summed with plain Java loops: no SQL aggregation and no production code, so
 * a mistake in the insights queries cannot hide in both places. The formulas are those of
 * {@code docs/specs/lending-insights-metrics.md}.
 */
final class GoldenModel {

    record Loan(
            UUID id,
            UUID branch,
            UUID officer,
            UUID product,
            UUID member,
            String status,
            LocalDate disbursedOn,
            LocalDate writtenOffOn,
            LocalDate closedOn,
            LocalDate maturity) {}

    record Item(
            UUID id,
            UUID loan,
            LocalDate due,
            long pDue,
            long iDue,
            long fDue,
            long pPaid,
            long iPaid,
            long fPaid,
            long iWaived,
            long fWaived) {}

    record Txn(UUID id, UUID loan, String type, long amount, LocalDate valueDate, UUID reverses) {}

    record Alloc(UUID txn, UUID appliesTo, UUID item, String component, long amount) {}

    record Line(LocalDate date, UUID sourceTxn, String key, long debit, long credit) {}

    record Pos(UUID loan, long po, long io, long arrears, int dpd) {}

    final Map<UUID, Loan> loans = new HashMap<>();
    final List<Item> items = new ArrayList<>();
    final Map<UUID, Txn> txns = new HashMap<>();
    final List<Alloc> allocs = new ArrayList<>();
    final List<Line> lines = new ArrayList<>();
    final Set<UUID> reversed = new HashSet<>();

    static GoldenModel load(UUID tenant) {
        JdbcClient db = TestDatabase.owner();
        GoldenModel m = new GoldenModel();
        db.sql("""
                        SELECT l.id, l.branch_id, l.officer_user_id, v.product_id, l.member_id, l.status, l.disbursed_on,
                               l.written_off_on, l.closed_on, l.maturity_date
                          FROM lending_loans l JOIN lending_loan_product_versions v ON v.id = l.product_version_id
                         WHERE l.tenant_id = ?
                        """)
                .param(tenant)
                .query((rs, n) -> m.loans.put(
                        rs.getObject(1, UUID.class),
                        new Loan(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class),
                                rs.getObject(3, UUID.class),
                                rs.getObject(4, UUID.class),
                                rs.getObject(5, UUID.class),
                                rs.getString(6),
                                date(rs.getDate(7)),
                                date(rs.getDate(8)),
                                date(rs.getDate(9)),
                                date(rs.getDate(10)))))
                .list();
        db.sql("""
                        SELECT id, loan_id, due_date, principal_due_minor, interest_due_minor, fees_due_minor,
                               principal_paid_minor, interest_paid_minor, fees_paid_minor, interest_waived_minor,
                               fees_waived_minor
                          FROM lending_schedule_items WHERE tenant_id = ?
                        """)
                .param(tenant)
                .query((rs, n) -> m.items.add(new Item(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getDate(3).toLocalDate(),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getLong(6),
                        rs.getLong(7),
                        rs.getLong(8),
                        rs.getLong(9),
                        rs.getLong(10),
                        rs.getLong(11))))
                .list();
        db.sql("""
                        SELECT id, loan_id, txn_type, amount_minor, value_date, reverses_txn_id
                          FROM lending_loan_transactions WHERE tenant_id = ?
                        """)
                .param(tenant)
                .query((rs, n) -> m.txns.put(
                        rs.getObject(1, UUID.class),
                        new Txn(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class),
                                rs.getString(3),
                                rs.getLong(4),
                                rs.getDate(5).toLocalDate(),
                                rs.getObject(6, UUID.class))))
                .list();
        m.txns.values().stream().filter(t -> t.reverses() != null).forEach(t -> m.reversed.add(t.reverses()));
        db.sql("""
                        SELECT transaction_id, applies_to_txn_id, schedule_item_id, component, amount_minor
                          FROM lending_repayment_allocations WHERE tenant_id = ?
                        """)
                .param(tenant)
                .query((rs, n) -> m.allocs.add(new Alloc(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getString(4),
                        rs.getLong(5))))
                .list();
        db.sql("""
                        SELECT e.entry_date, e.source_id, a.system_key, l.debit, l.credit
                          FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND e.source_module = 'lending' AND e.source_type = 'loan_transaction'
                        """)
                .param(tenant)
                .query((rs, n) -> m.lines.add(new Line(
                        rs.getDate(1).toLocalDate(),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getLong(5))))
                .list();
        return m;
    }

    private static LocalDate date(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    static boolean between(LocalDate d, LocalDate from, LocalDate to) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    // ---- Positions --------------------------------------------------------------------------

    /** Active loans today, from the paid columns. */
    List<Pos> live(LocalDate today, Predicate<Loan> filter) {
        List<Pos> out = new ArrayList<>();
        for (Loan l : loans.values()) {
            if (!l.status().equals("active") || !filter.test(l)) {
                continue;
            }
            long po = 0, io = 0, arrears = 0;
            LocalDate oldest = null;
            for (Item i : items) {
                if (!i.loan().equals(l.id())) {
                    continue;
                }
                long p = i.pDue() - i.pPaid();
                long in = i.iDue() - i.iPaid() - i.iWaived();
                long f = i.fDue() - i.fPaid() - i.fWaived();
                po += p;
                io += in;
                if (i.due().isBefore(today) && p + in + f > 0) {
                    arrears += p + in + f;
                    if (oldest == null || i.due().isBefore(oldest)) {
                        oldest = i.due();
                    }
                }
            }
            out.add(new Pos(
                    l.id(), po, io, arrears, oldest == null ? 0 : (int) ChronoUnit.DAYS.between(oldest, today)));
        }
        return out;
    }

    /** Loans active at the end of {@code d}, replaying the allocations dated on or before it. */
    Map<UUID, Pos> asOf(LocalDate d) {
        Map<UUID, long[]> byItem = new HashMap<>();
        for (Alloc a : allocs) {
            if (a.item() == null) {
                continue;
            }
            long[] v = byItem.computeIfAbsent(a.item(), k -> new long[5]);
            boolean dated = !txns.get(a.txn()).valueDate().isAfter(d);
            switch (a.component()) {
                case "principal" -> v[0] += dated ? a.amount() : 0;
                case "interest" -> v[1] += dated ? a.amount() : 0;
                case "interest_rebate" -> {
                    v[2] += dated ? a.amount() : 0;
                    v[3] += a.amount();
                }
                case "fee" -> v[4] += dated ? a.amount() : 0;
                default -> {}
            }
        }
        Map<UUID, Pos> out = new HashMap<>();
        for (Loan l : loans.values()) {
            if (l.disbursedOn() == null
                    || l.disbursedOn().isAfter(d)
                    || (l.writtenOffOn() != null && !l.writtenOffOn().isAfter(d))) {
                continue;
            }
            long po = 0, io = 0, fees = 0, arrears = 0;
            LocalDate oldest = null;
            for (Item i : items) {
                if (!i.loan().equals(l.id())) {
                    continue;
                }
                long[] v = byItem.getOrDefault(i.id(), new long[5]);
                long p = i.pDue() - v[0];
                long in = i.iDue() - (i.iWaived() - v[3] + v[2]) - v[1];
                long f = i.fDue() - i.fWaived() - v[4];
                po += p;
                io += in;
                fees += f;
                if (i.due().isBefore(d) && p + in + f > 0) {
                    arrears += p + in + f;
                    if (oldest == null || i.due().isBefore(oldest)) {
                        oldest = i.due();
                    }
                }
            }
            if (po + io + fees > 0) {
                out.put(
                        l.id(),
                        new Pos(
                                l.id(),
                                po,
                                io,
                                arrears,
                                oldest == null ? 0 : (int) ChronoUnit.DAYS.between(oldest, d)));
            }
        }
        return out;
    }

    static long po(List<Pos> ps) {
        return ps.stream().mapToLong(Pos::po).sum();
    }

    static long parAmount(List<Pos> ps, int over) {
        return ps.stream().filter(p -> p.dpd() > over).mapToLong(Pos::po).sum();
    }

    // ---- Flows ------------------------------------------------------------------------------

    long sumTxns(Set<String> types, LocalDate from, LocalDate to, Predicate<Loan> filter, boolean excludeReversed) {
        long total = 0;
        for (Txn t : txns.values()) {
            if (types.contains(t.type())
                    && between(t.valueDate(), from, to)
                    && filter.test(loans.get(t.loan()))
                    && !(excludeReversed && reversed.contains(t.id()))) {
                total += t.amount();
            }
        }
        return total;
    }

    long countTxns(String type, LocalDate from, LocalDate to, Predicate<Loan> filter) {
        return txns.values().stream()
                .filter(t ->
                        t.type().equals(type) && between(t.valueDate(), from, to) && filter.test(loans.get(t.loan())))
                .count();
    }

    private boolean activeInRange(Loan l, LocalDate from) {
        return l.disbursedOn() != null
                && (l.writtenOffOn() == null || !l.writtenOffOn().isBefore(from))
                && (l.closedOn() == null || !l.closedOn().isBefore(from));
    }

    long expected(LocalDate from, LocalDate to, Predicate<Loan> filter) {
        long total = 0;
        for (Item i : items) {
            Loan l = loans.get(i.loan());
            if (between(i.due(), from, to) && activeInRange(l, from) && filter.test(l)) {
                total += i.pDue() + i.iDue() + i.fDue();
            }
        }
        return total;
    }

    long collectedOnDue(LocalDate from, LocalDate to, Predicate<Loan> filter) {
        Map<UUID, Item> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        long total = 0;
        for (Alloc a : allocs) {
            if (a.item() == null || !Set.of("principal", "interest", "fee").contains(a.component())) {
                continue;
            }
            Item i = byId.get(a.item());
            Loan l = loans.get(i.loan());
            if (between(i.due(), from, to)
                    && activeInRange(l, from)
                    && filter.test(l)
                    && !txns.get(a.appliesTo()).valueDate().isAfter(to)) {
                total += a.amount();
            }
        }
        return total;
    }

    /** interest, fees, penalties, recovered, write-off expense, contribution. */
    long[] revenue(LocalDate from, LocalDate to, Predicate<Loan> filter) {
        long[] c = new long[6];
        for (Line line : lines) {
            Txn t = txns.get(line.sourceTxn());
            if (t == null || !between(line.date(), from, to) || !filter.test(loans.get(t.loan()))) {
                continue;
            }
            long net = line.credit() - line.debit();
            switch (line.key()) {
                case "loan_interest_income" -> c[0] += net;
                case "loan_fee_income" -> c[1] += net;
                case "loan_penalty_income" -> c[2] += net;
                case "bad_debt_recovered" -> c[3] += net;
                case "loan_write_off_expense" -> c[4] -= net;
                default -> {}
            }
        }
        c[5] = c[0] + c[1] + c[2] + c[3] - c[4];
        return c;
    }

    long forecast(LocalDate today, int days, Predicate<Loan> filter) {
        long total = 0;
        for (Item i : items) {
            Loan l = loans.get(i.loan());
            if (l.status().equals("active")
                    && i.due().isAfter(today)
                    && !i.due().isAfter(today.plusDays(days))
                    && filter.test(l)) {
                total += (i.pDue() - i.pPaid())
                        + (i.iDue() - i.iPaid() - i.iWaived())
                        + (i.fDue() - i.fPaid() - i.fWaived());
            }
        }
        return total;
    }

    /** Members disbursed in the range, and those of them with an earlier disbursed loan. */
    long[] repeat(LocalDate from, LocalDate to, Predicate<Loan> filter) {
        Map<UUID, LocalDate> first = new HashMap<>();
        for (Txn t : txns.values()) {
            Loan l = loans.get(t.loan());
            if (t.type().equals("disbursement") && between(t.valueDate(), from, to) && filter.test(l)) {
                first.merge(l.member(), t.valueDate(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        long repeat = first.entrySet().stream()
                .filter(e -> loans.values().stream()
                        .anyMatch(o -> o.member().equals(e.getKey())
                                && o.disbursedOn() != null
                                && o.disbursedOn().isBefore(e.getValue())))
                .count();
        return new long[] {first.size(), repeat};
    }

    static long bp(long part, long whole) {
        // Half up, the rule of chapter 14 section 14.1, written differently from the production code.
        return java.math.BigDecimal.valueOf(part)
                .multiply(java.math.BigDecimal.valueOf(10_000))
                .divide(java.math.BigDecimal.valueOf(whole), 0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }
}
