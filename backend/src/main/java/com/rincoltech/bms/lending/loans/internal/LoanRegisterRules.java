package com.rincoltech.bms.lending.loans.internal;

import static com.rincoltech.bms.lending.loans.internal.LoanRegister.*;

import com.rincoltech.bms.core.imports.ImportTemplate.RowFinding;
import com.rincoltech.bms.core.imports.ImportTemplate.SetResult;
import com.rincoltech.bms.core.imports.ImportTemplate.SetRow;
import com.rincoltech.bms.core.imports.ImportTemplate.SetRules;
import com.rincoltech.bms.lending.members.MemberImports;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The rules of {@code lending_loans_v1} over the run's register rows (issue #136): the row classes of chapter 13 section
 * 13.5 (the mapping's {@code subheader_month} and {@code placeholder_rows}, and a row of amounts only), every normaliser of
 * 13.6 with its codes of 13.7, the dates read against the month of the sub-header above (13.6.3), and the borrowers linked
 * across the run and to the business's own members (13.9: {@code DUPLICATE_BORROWER}, {@code MEMBER_MATCHED_EXISTING},
 * {@code MEMBER_PHONE_CONFLICT}, {@code KIN_IS_MEMBER}, {@code KIN_PHONE_MATCH}, {@code MULTIPLE_ACTIVE_LOANS}). One per
 * validation. The business's rows are read only through {@link Directory}, inside the validation's transaction, so only the
 * business's own (row-level security). Each borrower is matched as the loader will match it: in the run's order, a key seen
 * before in the same file is that member, else {@link LoanRegister#match} against the members held and those the run's
 * earlier rows make.
 */
final class LoanRegisterRules implements SetRules {

    /** What the rules read of the business: its members, branches, loan products and pledged plates. */
    interface Directory {

        Optional<MemberImports.Held> memberByNin(String nin);

        List<MemberImports.Held> membersByPhone(String phoneE164);

        /** Whether the code names an open branch; an empty code is the head office, which every business has. */
        boolean branchOpen(String code);

        /** The branch a code names, as one key: an empty code is the head office's. */
        default String branchKey(String code) {
            return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        }

        boolean productActive(String code);

        boolean plateHeld(String plate);

        /** A business that holds nothing: every branch and product accepted, no member, no plate. */
        Directory NONE = new Directory() {
            @Override
            public Optional<MemberImports.Held> memberByNin(String nin) {
                return Optional.empty();
            }

            @Override
            public List<MemberImports.Held> membersByPhone(String phoneE164) {
                return List.of();
            }

            @Override
            public boolean branchOpen(String code) {
                return true;
            }

            @Override
            public boolean productActive(String code) {
                return true;
            }

            @Override
            public boolean plateHeld(String plate) {
                return false;
            }
        };
    }

    /** A data row of the run as pass 1 saw it, in the run's order. */
    private record Seen(
            UUID batch,
            int line,
            String key,
            String nin,
            String phone,
            String name,
            LocalDate disbursedOn,
            long returnAmount,
            long payment,
            LocalDate paidOn,
            String plate) {}

    /** What the run's order makes of a borrower: the code it gets, the line it names, and its member. */
    private record Assigned(String code, Integer other, UUID member) {}

    private final Directory directory;
    private final Map<UUID, TreeMap<Integer, MonthLabel>> subheaders = new HashMap<>();
    private final Map<UUID, TreeMap<Integer, Set<Integer>>> years = new HashMap<>();
    private final List<Seen> seen = new ArrayList<>();
    /** The first file of the run with a row of each branch: a branch's register is one file (one opening journal). */
    private final Map<String, UUID> branchFile = new HashMap<>();

    private Map<String, Assigned> assigned;

    LoanRegisterRules(Directory directory) {
        this.directory = directory;
    }

    // ---------------------------------------------------------------------------------------------
    // Pass 1.
    // ---------------------------------------------------------------------------------------------

    @Override
    public void index(SetRow row) {
        String cls = LoanRegister.classify(row.texts(), row.rowRules());
        if ("SUBHEADER_ROW".equals(cls)) {
            subheaders
                    .computeIfAbsent(row.batchId(), k -> new TreeMap<>())
                    .put(row.line(), subheaderMonth(row.texts()));
            return;
        }
        if (cls != null) {
            return;
        }
        branchFile.putIfAbsent(directory.branchKey(row.text(BRANCH)), row.batchId());
        Set<Integer> y = new HashSet<>();
        candidates(row.text(DISBURSED_ON)).forEach(c -> y.add(c.date().getYear()));
        years.computeIfAbsent(row.batchId(), k -> new TreeMap<>()).put(row.line(), y);
        Read r = read(row);
        Phone phone = phone(row.text(CUSTOMER_PHONE));
        Nin nin = nin(row.text(CUSTOMER_NIN));
        String n = nin == null ? null : nin.valid();
        String p = phone == null ? null : phone.e164();
        Collateral c = collateral(row.text(COLLATERAL), n);
        seen.add(new Seen(
                row.batchId(),
                row.line(),
                memberKey(n, p, row.batchId() + ":" + row.line()),
                n,
                p,
                row.text(CUSTOMER_NAME),
                r.dates().disbursedOn(),
                r.returnAmount() == null ? 0 : r.returnAmount(),
                r.payment(),
                r.paidOn(),
                c != null && "vehicle".equals(c.type()) ? c.reference() : null));
        assigned = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Pass 2.
    // ---------------------------------------------------------------------------------------------

    @Override
    public RowFinding classify(SetRow row) {
        String cls = LoanRegister.classify(row.texts(), row.rowRules());
        return cls == null ? null : new RowFinding(cls, null, Map.of());
    }

    @Override
    public SetResult check(SetRow row) {
        List<Note> notes = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        // 13.6.1 and 13.6.2: the borrower's phone and NIN, the marital status.
        Phone phone = phone(row.text(CUSTOMER_PHONE));
        if (phone == null) {
            notes.add(Note.of("PHONE_MISSING", CUSTOMER_PHONE));
        } else {
            phoneNotes(notes, phone, CUSTOMER_PHONE, "PHONE_UNPARSEABLE");
        }
        Nin nin = nin(row.text(CUSTOMER_NIN));
        if (nin == null) {
            notes.add(Note.of("NIN_MISSING", CUSTOMER_NIN));
        } else if (nin.valid() == null) {
            notes.add(Note.of("NIN_INVALID", CUSTOMER_NIN));
        }
        if (marital(row.text(MARITAL_STATUS)) == null) {
            notes.add(Note.of("MARITAL_UNMAPPED", MARITAL_STATUS));
        }
        // The next of kin (13.6.1, 13.6.2, 13.6.7), when the row names one.
        Phone kinPhone = null;
        Nin kinNin = null;
        if (row.text(KIN_NAME) != null) {
            kinPhone = phone(row.text(KIN_PHONE));
            if (kinPhone != null) {
                phoneNotes(notes, kinPhone, KIN_PHONE, "KIN_PHONE_UNPARSEABLE");
            }
            kinNin = nin(row.text(KIN_NIN));
            if (kinNin == null) {
                notes.add(Note.of("NIN_MISSING", KIN_NIN));
            } else if (kinNin.valid() == null) {
                notes.add(Note.of("NIN_INVALID", KIN_NIN));
            }
            if (relationship(row.text(KIN_RELATIONSHIP)) == null) {
                notes.add(Note.of("RELATIONSHIP_UNMAPPED", KIN_RELATIONSHIP));
            }
        }
        // The batch's branch and loan product.
        if (!directory.branchOpen(row.text(BRANCH))) {
            notes.add(Note.of("BRANCH_UNKNOWN", BRANCH));
        }
        UUID firstFile = branchFile.get(directory.branchKey(row.text(BRANCH)));
        if (firstFile != null && !firstFile.equals(row.batchId())) {
            notes.add(Note.of("BRANCH_IN_TWO_FILES", BRANCH));
        }
        if (row.text(PRODUCT) != null && !directory.productActive(row.text(PRODUCT))) {
            notes.add(Note.of("PRODUCT_UNKNOWN", PRODUCT));
        }
        // 13.6.4, 13.6.3, 13.6.5 and 13.6.6.
        Read r = read(row);
        notes.addAll(r.notes());
        if (r.dates().disbursedOn() != null) {
            values.put(DISBURSED_ON, r.dates().disbursedOn().toString());
            if (row.businessDate() != null && r.dates().disbursedOn().isAfter(row.businessDate())) {
                notes.add(Note.of("DATE_AFTER_CUTOVER", DISBURSED_ON));
            }
        }
        if (r.dates().dueOn() != null) {
            values.put(DUE_ON, r.dates().dueOn().toString());
        }
        if (r.paidOn() != null) {
            values.put(PAID_ON, r.paidOn().toString());
        }
        // 13.6.7: the status against the balance.
        String status = LoanRegister.status(row.text(STATUS));
        if (status == null) {
            notes.add(Note.of("STATUS_UNMAPPED", STATUS));
        } else if (r.returnAmount() != null) {
            long owed = Math.max(0, r.returnAmount() - r.payment());
            if (("settled".equals(status) && owed > 0) || ("open".equals(status) && owed == 0)) {
                notes.add(Note.of("STATUS_BALANCE_CONFLICT", STATUS));
            }
        }
        // 13.6.8.
        Collateral c = collateral(row.text(COLLATERAL), nin == null ? null : nin.valid());
        if (c != null) {
            notes.add(Note.of(c.recognised() ? "COLLATERAL_PARSED" : "COLLATERAL_UNRECOGNISED", COLLATERAL));
            if ("vehicle".equals(c.type()) && plateTwice(row, c.reference())) {
                notes.add(Note.of("COLLATERAL_PLEDGED_TWICE", COLLATERAL));
            }
        }
        // 13.9.
        link(row, notes, kinNin, kinPhone, phone);
        List<RowFinding> findings = new ArrayList<>();
        for (Note n : notes) {
            Map<String, String> params = n.other() == null ? Map.of() : Map.of("other", String.valueOf(n.other()));
            findings.add(new RowFinding(n.code(), n.field(), params, n.suggestion()));
        }
        return new SetResult(findings, values);
    }

    private static void phoneNotes(List<Note> notes, Phone p, String field, String unparseable) {
        if (p.e164() == null) {
            notes.add(Note.of(unparseable, field));
            return;
        }
        if (p.multiple()) {
            notes.add(Note.of("PHONE_MULTIPLE", field));
        }
        if (p.changed()) {
            notes.add(Note.of("PHONE_NORMALISED", field));
        }
        if (!p.mobile()) {
            notes.add(Note.of("PHONE_NOT_MOBILE", field));
        }
    }

    /** The term, dates, amounts, rate and pay date of a row, and their findings (13.6.3 to 13.6.6). */
    private record Read(Dates dates, Long returnAmount, long payment, LocalDate paidOn, List<Note> notes) {}

    private Read read(SetRow row) {
        List<Note> notes = new ArrayList<>();
        Duration du = duration(row.text(DURATION));
        if (du == null) {
            notes.add(Note.of("DURATION_UNPARSEABLE", DURATION));
        } else if (du.changed()) {
            notes.add(Note.of("DURATION_NORMALISED", DURATION));
        }
        Dates dates = LoanRegister.dates(
                row.text(DISBURSED_ON), row.text(DUE_ON), du, contextAt(row.batchId(), row.line()), row.businessDate());
        notes.addAll(dates.notes());
        Amount principal = amount(row.text(PRINCIPAL));
        if (principal == null) {
            notes.add(Note.of("PRINCIPAL_MISSING", PRINCIPAL));
        }
        Long p = amountNotes(notes, principal, PRINCIPAL);
        Long given = amountNotes(notes, amount(row.text(RETURN_AMOUNT)), RETURN_AMOUNT);
        Long pay = amountNotes(notes, amount(row.text(PAYMENT)), PAYMENT);
        Long outstanding = amountNotes(notes, amount(row.text(OUTSTANDING)), OUTSTANDING);
        Rate rate = LoanRegister.rate(row.text(RATE), p, given);
        if (rate.code() != null) {
            notes.add(new Note(rate.code(), RATE, null, rate.suggestion()));
        }
        Long ret = given;
        if (p != null && rate.bp() != null) {
            long expected = withRate(p, rate.bp());
            if (given == null) {
                notes.add(new Note("RETURN_AMOUNT_DERIVED", RETURN_AMOUNT, null, String.valueOf(expected)));
                ret = expected;
            } else if (expected != given) {
                notes.add(new Note("AMOUNT_MISMATCH", RETURN_AMOUNT, null, String.valueOf(expected)));
            }
        }
        long paid = pay == null ? 0 : pay;
        if (ret != null) {
            long computed = ret - paid;
            if (outstanding != null && outstanding != Math.max(0, computed)) {
                notes.add(new Note("BALANCE_MISMATCH", OUTSTANDING, null, String.valueOf(Math.max(0, computed))));
            }
            if (paid > ret) {
                notes.add(Note.of("PAYMENT_EXCEEDS_RETURN", PAYMENT));
            }
        }
        LocalDate paidOn = null;
        if (paid > 0) {
            PayDate pd = payDate(
                    row.text(PAID_ON),
                    dates.disbursedOn(),
                    kindOf(row.text(DISBURSED_ON), dates.disbursedOn()),
                    row.businessDate());
            paidOn = pd.paidOn();
            if (pd.note() != null) {
                notes.add(pd.note());
            }
        }
        return new Read(dates, ret, paid, paidOn, notes);
    }

    private static Long amountNotes(List<Note> notes, Amount a, String field) {
        if (a == null) {
            return null;
        }
        if (a.problem() != null) {
            notes.add(new Note(a.problem(), field, null, a.suggestion()));
        }
        return a.minor();
    }

    // ---------------------------------------------------------------------------------------------
    // The sub-header's month (13.5).
    // ---------------------------------------------------------------------------------------------

    /**
     * The month of the nearest sub-header above the line in its file. A month without a year takes the year of the next
     * data row's DATE when every reading of it has one year, else the year of the sub-header before it.
     */
    YearMonth contextAt(UUID batch, int line) {
        TreeMap<Integer, MonthLabel> heads = subheaders.get(batch);
        if (heads == null) {
            return null;
        }
        Map.Entry<Integer, MonthLabel> head = heads.lowerEntry(line);
        return head == null ? null : resolved(batch, heads, head.getKey());
    }

    private YearMonth resolved(UUID batch, TreeMap<Integer, MonthLabel> heads, int at) {
        MonthLabel label = heads.get(at);
        if (label == null) {
            return null;
        }
        if (label.year() != null) {
            return YearMonth.of(label.year(), label.month());
        }
        Map.Entry<Integer, Set<Integer>> next =
                years.getOrDefault(batch, new TreeMap<>()).higherEntry(at);
        if (next != null && next.getValue().size() == 1) {
            return YearMonth.of(next.getValue().iterator().next(), label.month());
        }
        Integer before = heads.lowerKey(at);
        YearMonth previous = before == null ? null : resolved(batch, heads, before);
        return previous == null ? null : YearMonth.of(previous.getYear(), label.month());
    }

    // ---------------------------------------------------------------------------------------------
    // 13.9 Linking.
    // ---------------------------------------------------------------------------------------------

    private void link(SetRow row, List<Note> notes, Nin kinNin, Phone kinPhone, Phone phone) {
        Assigned a = assignments().get(row.batchId() + ":" + row.line());
        if (a != null && a.code() != null) {
            notes.add(new Note(
                    a.code(), "MEMBER_PHONE_CONFLICT".equals(a.code()) ? CUSTOMER_PHONE : null, a.other(), null));
        }
        Nin ownNin = nin(row.text(CUSTOMER_NIN));
        String own = ownNin == null ? null : ownNin.valid();
        if (kinNin != null && kinNin.valid() != null && !kinNin.valid().equals(own)) {
            if (seen.stream().anyMatch(s -> kinNin.valid().equals(s.nin()))
                    || directory.memberByNin(kinNin.valid()).isPresent()) {
                notes.add(Note.of("KIN_IS_MEMBER", KIN_NAME));
                kinPhone = null;
            }
        }
        String ownPhone = phone == null ? null : phone.e164();
        if (kinPhone != null && kinPhone.e164() != null && !kinPhone.e164().equals(ownPhone)) {
            String kp = kinPhone.e164();
            if (seen.stream().anyMatch(s -> kp.equals(s.phone()))
                    || !directory.membersByPhone(kp).isEmpty()) {
                notes.add(Note.of("KIN_PHONE_MATCH", KIN_PHONE));
            }
        }
        if (a != null) {
            Seen self = seen.stream()
                    .filter(s -> s.batch().equals(row.batchId()) && s.line() == row.line())
                    .findFirst()
                    .orElse(null);
            if (self != null && self.disbursedOn() != null) {
                for (Seen s : seen) {
                    Assigned other = assignments().get(s.batch() + ":" + s.line());
                    if (s != self
                            && other != null
                            && other.member().equals(a.member())
                            && s.disbursedOn() != null
                            && s.disbursedOn().isBefore(self.disbursedOn())
                            && (s.payment() < s.returnAmount()
                                    || (s.paidOn() != null && s.paidOn().isAfter(self.disbursedOn())))) {
                        notes.add(new Note("MULTIPLE_ACTIVE_LOANS", null, s.line(), null));
                        break;
                    }
                }
            }
        }
    }

    /**
     * The member of every data row, in the run's order, as the loader assigns them: the same key earlier in the same file is
     * that member ({@code DUPLICATE_BORROWER}); else a member held, or made by an earlier row of the run, that
     * {@link LoanRegister#match} names (a held one {@code MEMBER_MATCHED_EXISTING}, one of the run {@code DUPLICATE_BORROWER});
     * a conflict ({@code MEMBER_PHONE_CONFLICT}) and no match make a new member.
     */
    private Map<String, Assigned> assignments() {
        if (assigned != null) {
            return assigned;
        }
        Map<String, Assigned> out = new HashMap<>();
        Map<String, MemberImports.Held> made = new HashMap<>();
        Map<UUID, Integer> madeAt = new HashMap<>();
        Map<String, List<MemberImports.Held>> madeByPhone = new HashMap<>();
        Map<String, UUID> keyInFile = new HashMap<>();
        Map<String, Integer> keyLine = new HashMap<>();
        for (Seen s : seen) {
            String fileKey = s.batch() + "|" + s.key();
            UUID earlier = keyInFile.get(fileKey);
            if (earlier != null) {
                out.put(s.batch() + ":" + s.line(), new Assigned("DUPLICATE_BORROWER", keyLine.get(fileKey), earlier));
                continue;
            }
            Optional<MemberImports.Held> byNin = s.nin() == null
                    ? Optional.empty()
                    : Optional.ofNullable(made.get(s.nin())).or(() -> directory.memberByNin(s.nin()));
            List<MemberImports.Held> byPhone = new ArrayList<>();
            if (s.phone() != null) {
                byPhone.addAll(directory.membersByPhone(s.phone()));
                byPhone.addAll(madeByPhone.getOrDefault(s.phone(), List.of()));
            }
            Match m = match(s.nin(), s.name(), byNin, byPhone);
            Assigned a;
            if (m.kind() == MatchKind.MATCHED) {
                Integer line = madeAt.get(m.member().id());
                a = line != null
                        ? new Assigned("DUPLICATE_BORROWER", line, m.member().id())
                        : new Assigned(
                                "MEMBER_MATCHED_EXISTING", null, m.member().id());
            } else {
                UUID id = UUID.nameUUIDFromBytes((s.batch() + ":" + s.line()).getBytes(StandardCharsets.UTF_8));
                MemberImports.Held held = new MemberImports.Held(id, null, s.nin(), s.phone(), s.name());
                if (s.nin() != null) {
                    made.put(s.nin(), held);
                }
                if (s.phone() != null) {
                    madeByPhone
                            .computeIfAbsent(s.phone(), k -> new ArrayList<>())
                            .add(held);
                }
                madeAt.put(id, s.line());
                a = new Assigned(m.kind() == MatchKind.CONFLICT ? "MEMBER_PHONE_CONFLICT" : null, null, id);
            }
            keyInFile.put(fileKey, a.member());
            keyLine.put(fileKey, s.line());
            out.put(s.batch() + ":" + s.line(), a);
        }
        assigned = out;
        return out;
    }

    /** A plate an earlier row of the run pledges, or one an open loan of the business holds. */
    private boolean plateTwice(SetRow row, String plate) {
        for (Seen s : seen) {
            if (s.batch().equals(row.batchId()) && s.line() == row.line()) {
                break;
            }
            if (plate.equals(s.plate())) {
                return true;
            }
        }
        return directory.plateHeld(plate);
    }
}
