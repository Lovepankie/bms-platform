package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportBooks;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.lending.collateral.CollateralImports;
import com.rincoltech.bms.lending.members.MemberImports;
import com.rincoltech.bms.lending.products.ProductCatalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code lending_loans_v1}, the canonical template of lending loans (issue #136, spec section 4.1, chapter 13 sections 13.5
 * to 13.12): one entity set, {@code loans}, a row per loan of a register as chapter 13 section 13.8 maps its 25 columns, plus
 * the batch's branch and loan product (a column or the mapping's {@code defaults}); the codes of 13.7; the rules of 13.5,
 * 13.6 and 13.9 over the run's rows ({@link LoanRegisterRules}); the disbursement decision of 13.8 row 15 as a policy of the
 * run; the forecast and books of the preview and reconciliation; and its loader ({@link LoanRegisterWriter}), which writes
 * members, next of kin, collateral, loans, schedules and historic repayments and posts one opening journal per branch, ever
 * (13.10). The pilot register is its first mapping ({@code fixtures/imports/lending_loans_v1.pilot_register.mapping.json}).
 * The savings and investments templates of spec section 4.1 are later work.
 */
@Component
class LendingLoansV1Template implements ImportTemplate {

    static final String KEY = "lending_loans";

    static final int VERSION = 1;

    static final String LOANS = "loans";

    /** The opening journal kinds, one per branch, ever: the principal owed, and the members' credits. */
    static final String RECEIVABLE_KIND = "loans_receivable";

    static final String CREDIT_KIND = "member_credit";

    /** The policy decision of chapter 13 section 13.8 row 15: the register has no disbursed amount. */
    static final String DISBURSED = "disbursed_principal";

    static final String REQUESTED_AS_DISBURSED = "requested_as_disbursed";

    static final Policy DISBURSED_POLICY = new Policy(
            DISBURSED, List.of(REQUESTED_AS_DISBURSED), Set.of(), "DISBURSEMENT_DECISION_MISSING", LOANS, v -> true);

    private final ObjectProvider<LoanRegisterWriter> writer;
    private final MemberImports members;
    private final CollateralImports collateral;
    private final ProductCatalog products;
    private final Branches branches;
    private final CurrentTenant tenant;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    LendingLoansV1Template(
            ObjectProvider<LoanRegisterWriter> writer,
            MemberImports members,
            CollateralImports collateral,
            ProductCatalog products,
            Branches branches,
            CurrentTenant tenant,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.writer = writer;
        this.members = members;
        this.collateral = collateral;
        this.products = products;
        this.branches = branches;
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.readOnly = transactions == null ? null : new TransactionTemplate(transactions);
        if (this.readOnly != null) {
            this.readOnly.setReadOnly(true);
        }
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public List<EntitySet> entitySets() {
        return ENTITY_SETS;
    }

    private static Field text(String name, int length) {
        return Field.optional(name, FieldType.text(length));
    }

    /** A free text of the register: trimmed, inner spaces collapsed, case kept (13.6.9), personal. */
    private static Field words(String name, int length) {
        return text(name, length).normalisedBy(Normaliser.COLLAPSE_SPACES).personalData();
    }

    /**
     * Chapter 13 section 13.8, column by column. Every cell is read as text and typed by the rules of 13.6, which raise the
     * codes of 13.7: a sub-header or a row of amounts only has no record, so only the borrower's name and the loan product
     * are required of a data row.
     */
    static final List<EntitySet> ENTITY_SETS = List.of(new EntitySet(
            LOANS,
            List.of(
                    text(LoanRegister.DISBURSED_ON, 40),
                    Field.required(LoanRegister.CUSTOMER_NAME, FieldType.text(200))
                            .normalisedBy(Normaliser.COLLAPSE_SPACES)
                            .personalData(),
                    words(LoanRegister.LOCATION, 200),
                    text(LoanRegister.CUSTOMER_PHONE, 60).personalData(),
                    text(LoanRegister.CUSTOMER_NIN, 40).personalData(),
                    text(LoanRegister.MARITAL_STATUS, 40),
                    words(LoanRegister.KIN_NAME, 200),
                    text(LoanRegister.KIN_PHONE, 60).personalData(),
                    text(LoanRegister.KIN_NIN, 40).personalData(),
                    text(LoanRegister.KIN_RELATIONSHIP, 60),
                    words(LoanRegister.KIN_LOCATION, 200),
                    words(LoanRegister.OCCUPATION, 120),
                    words(LoanRegister.OTHER_INCOME, 200),
                    words(LoanRegister.PURPOSE, 300),
                    text(LoanRegister.PRINCIPAL, 40),
                    text(LoanRegister.RATE, 40),
                    text(LoanRegister.DURATION, 40),
                    text(LoanRegister.DUE_ON, 40),
                    text(LoanRegister.RETURN_AMOUNT, 40),
                    text(LoanRegister.COLLATERAL, 200).personalData(),
                    text(LoanRegister.PAYMENT, 40),
                    text(LoanRegister.PAID_ON, 40),
                    text(LoanRegister.OUTSTANDING, 40),
                    text(LoanRegister.STATUS, 40),
                    words(LoanRegister.REMARKS, 500),
                    text(LoanRegister.BRANCH, 10).normalisedBy(Normaliser.UPPER),
                    Field.required(LoanRegister.PRODUCT, FieldType.text(40))),
            Set.of(RowRule.SUBHEADER_MONTH, RowRule.PLACEHOLDER_ROWS)));

    // ---------------------------------------------------------------------------------------------
    // The catalogue of chapter 13 section 13.7.
    // ---------------------------------------------------------------------------------------------

    @Override
    public List<IssueCode> issueCodes() {
        return CODES;
    }

    private static IssueCode info(String code, String message) {
        return new IssueCode(code, Severity.INFO, message);
    }

    private static IssueCode warning(String code, String message) {
        return new IssueCode(code, Severity.WARNING, message);
    }

    private static IssueCode blocking(String code, String message) {
        return new IssueCode(code, Severity.BLOCKING, message);
    }

    /**
     * 13.7 without {@code BLANK_ROW}, {@code SUBHEADER_ROW} and {@code PLACEHOLDER_ROW}, which are shared, with
     * {@code KIN_PHONE_UNPARSEABLE} as the kin's warning of {@code PHONE_UNPARSEABLE} (a code has one severity), the
     * branch and product of a row, and the disbursement decision.
     */
    static final List<IssueCode> CODES = List.of(
            blocking(
                    "AMOUNTS_ONLY_ROW",
                    "Line {line} of {file} has amounts but no borrower name, phone or ID; leave it out, or add the"
                            + " borrower."),
            info(
                    "PHONE_NORMALISED",
                    "{field} on {file} line {line} is read as a Uganda number in the +256 form; the original is kept"
                            + " with this note."),
            info(
                    "PHONE_MULTIPLE",
                    "{field} on {file} line {line} holds two numbers; the first is the phone and the second the other"
                            + " phone."),
            warning("PHONE_NOT_MOBILE", "{field} on {file} line {line} is not a mobile number; SMS will not reach it."),
            blocking("PHONE_UNPARSEABLE", "{field} on {file} line {line} is not a Uganda phone number."),
            warning(
                    "KIN_PHONE_UNPARSEABLE",
                    "{field} on {file} line {line} is not a Uganda phone number; the next of kin is kept without one."),
            blocking("PHONE_MISSING", "{field} on {file} line {line} is empty; a member needs a phone."),
            warning(
                    "NIN_INVALID",
                    "{field} on {file} line {line} is not a valid national ID number; it is kept as another ID."),
            info("NIN_MISSING", "{field} on {file} line {line} is empty; the record is kept without a national ID."),
            info("DATE_TEXT_PARSED", "{field} on {file} line {line} was written as text and read as a date."),
            warning(
                    "DATE_SWAP_REPAIRED",
                    "{field} on {file} line {line} reads with day and month exchanged to agree with the term; accept"
                            + " the repaired date or edit it."),
            info(
                    "DATE_MONTH_AS_30_DAYS",
                    "{field} on {file} line {line} agrees with the term only when a month counts as 30 days."),
            blocking(
                    "DATE_AMBIGUOUS",
                    "{field} on {file} line {line} can be read as more than one date; edit it to the right one."),
            blocking(
                    "DATE_INCONSISTENT",
                    "{field} on {file} line {line} does not agree with the expected date of return and the term; edit"
                            + " the dates or the term, or leave the row out."),
            warning(
                    "DATE_UNVERIFIED",
                    "{field} on {file} line {line} cannot be checked against the other date and the term; check it."),
            blocking("DATE_UNPARSEABLE", "{field} on {file} line {line} is empty or not a date."),
            blocking(
                    "DATE_OUT_OF_RANGE",
                    "{field} on {file} line {line} is before 2015 or too far after the cutover day for a loan."),
            warning(
                    "PAY_DATE_AMBIGUOUS",
                    "{field} on {file} line {line} can be read two ways; the suggestion reads it as the row's other"
                            + " dates are read."),
            warning(
                    "PAY_DATE_OUT_OF_RANGE",
                    "{field} on {file} line {line} is before the loan's date or after the cutover day."),
            info("DURATION_NORMALISED", "{field} on {file} line {line} was tidied into a count and a unit."),
            blocking(
                    "DURATION_UNPARSEABLE",
                    "{field} on {file} line {line} is not a whole number of days, weeks or months."),
            info("RATE_TEXT_PARSED", "{field} on {file} line {line} was written as a percentage and read as one."),
            warning("RATE_ZERO", "{field} on {file} line {line} is zero; the loan carries no interest."),
            warning(
                    "RATE_PERCENT_FORM",
                    "{field} on {file} line {line} looks like a percentage, not a fraction; the suggestion divides it"
                            + " by 100."),
            blocking(
                    "RATE_OUT_OF_RANGE",
                    "{field} on {file} line {line} is above 100; the suggestion is the rate the amounts imply."),
            blocking(
                    "RATE_MISSING",
                    "{field} on {file} line {line} is empty or not a rate; the suggestion is the rate the amounts"
                            + " imply."),
            warning(
                    "AMOUNT_MISMATCH",
                    "{field} on {file} line {line} is not the principal with the rate added; accept the corrected"
                            + " amount, or edit the rate to the one the amounts imply."),
            warning(
                    "AMOUNT_NOT_INTEGER",
                    "{field} on {file} line {line} has a fraction of a shilling; the suggestion is rounded."),
            blocking("AMOUNT_UNPARSEABLE", "{field} on {file} line {line} is not an amount."),
            blocking("NEGATIVE_AMOUNT", "{field} on {file} line {line} is below zero."),
            blocking("PRINCIPAL_MISSING", "{field} on {file} line {line} is empty; a loan needs its principal."),
            warning(
                    "RETURN_AMOUNT_DERIVED",
                    "{field} on {file} line {line} is empty; the principal with the rate added is used."),
            warning(
                    "BALANCE_MISMATCH",
                    "{field} on {file} line {line} is not the amount to be returned less the payment; the computed"
                            + " balance is used."),
            warning(
                    "PAYMENT_EXCEEDS_RETURN",
                    "{field} on {file} line {line} is more than the amount to be returned; the excess becomes a"
                            + " credit."),
            info(
                    "STATUS_UNMAPPED",
                    "{field} on {file} line {line} is not a status the rules know; the loan's status comes from its"
                            + " balance."),
            warning(
                    "STATUS_BALANCE_CONFLICT",
                    "{field} on {file} line {line} does not agree with the balance; the loan's status comes from the"
                            + " balance."),
            info(
                    "MARITAL_UNMAPPED",
                    "{field} on {file} line {line} is not a marital status the rules know; it is kept as unknown."),
            info(
                    "RELATIONSHIP_UNMAPPED",
                    "{field} on {file} line {line} is not a relationship the rules know; it is kept as other with its"
                            + " text."),
            info(
                    "COLLATERAL_PARSED",
                    "{field} on {file} line {line} was read as a kind of collateral; verify its custody and value."),
            info(
                    "COLLATERAL_UNRECOGNISED",
                    "{field} on {file} line {line} is not a kind of collateral the rules know; it is kept as other."),
            warning(
                    "COLLATERAL_PLEDGED_TWICE",
                    "{field} on {file} line {line} names a plate already pledged to another open loan."),
            info(
                    "DUPLICATE_BORROWER",
                    "Line {line} of {file} is the borrower of line {other}; they become one member, the latest row by"
                            + " date giving the details."),
            info(
                    "MEMBER_MATCHED_EXISTING",
                    "Line {line} of {file} is a member the business already has; the loan is added to that member,"
                            + " whose details stay as they are."),
            warning(
                    "MEMBER_PHONE_CONFLICT",
                    "{field} on {file} line {line} is the phone of another borrower or member with another ID or"
                            + " name; link the row to that member, or override to keep them apart."),
            info(
                    "KIN_IS_MEMBER",
                    "The next of kin on {file} line {line} has the national ID number of a borrower or member, and is"
                            + " linked to that member."),
            warning(
                    "KIN_PHONE_MATCH",
                    "{field} on {file} line {line} is the phone of a borrower or member; the link is kept as suggested"
                            + " for the member's screen to confirm."),
            info(
                    "MULTIPLE_ACTIVE_LOANS",
                    "Line {line} of {file} is a loan of a borrower whose loan on line {other} was still open on its"
                            + " date."),
            blocking("BRANCH_UNKNOWN", "{field} on {file} line {line} is not an open branch of the business."),
            blocking(
                    "BRANCH_IN_TWO_FILES",
                    "{field} on {file} line {line} is a branch an earlier file of this import has; a branch's register"
                            + " is one file, so its one opening journal holds every loan."),
            blocking("PRODUCT_UNKNOWN", "{field} on {file} line {line} is not an active loan product of the business."),
            new IssueCode(
                    "DISBURSEMENT_DECISION_MISSING",
                    Severity.REFUSED,
                    "The register gives no disbursed amount; record the decision the customer confirmed that the"
                            + " requested principal was disbursed in full."));

    // ---------------------------------------------------------------------------------------------
    // The rules over the run's rows, the review's link, the policy and the loader.
    // ---------------------------------------------------------------------------------------------

    @Override
    public SetRules setRules(String entitySet) {
        return LOANS.equals(entitySet) ? new LoanRegisterRules(directory()) : null;
    }

    /** What the rules read of the business, inside the validation's transaction. */
    LoanRegisterRules.Directory directory() {
        return new LoanRegisterRules.Directory() {
            @Override
            public Optional<MemberImports.Held> memberByNin(String nin) {
                return members.byNin(nin);
            }

            @Override
            public List<MemberImports.Held> membersByPhone(String phoneE164) {
                return members.byPhone(phoneE164);
            }

            @Override
            public boolean branchOpen(String code) {
                return code == null
                        || branches.all().stream()
                                .anyMatch(b -> b.active() && b.code().equalsIgnoreCase(code.strip()));
            }

            @Override
            public String branchKey(String code) {
                String named = code == null
                        ? branches.all().stream()
                                .filter(Branches.Branch::headOffice)
                                .map(Branches.Branch::code)
                                .findFirst()
                                .orElse("")
                        : code;
                return named.strip().toUpperCase(Locale.ROOT);
            }

            @Override
            public boolean productActive(String code) {
                return products.currentByCode(code.strip())
                        .map(p -> "active".equals(p.productStatus()))
                        .orElse(false);
            }

            @Override
            public boolean plateHeld(String plate) {
                return collateral.referenceHeld("vehicle", plate);
            }
        };
    }

    /** The review's {@code link_member}: a member the business holds. */
    @Override
    public boolean recordExists(String entitySet, UUID id) {
        return LOANS.equals(entitySet) && members.exists(id);
    }

    @Override
    public Set<String> readyBlockingCodes() {
        return Set.of("BRANCH_IN_TWO_FILES");
    }

    @Override
    public List<Policy> policies() {
        return List.of(DISBURSED_POLICY);
    }

    @Override
    public BatchWriter loader(String entitySet) {
        return writer.getObject().loader(entitySet);
    }

    // ---------------------------------------------------------------------------------------------
    // The forecast and the books (issues #132 to #134), by the rules the loader writes by.
    // ---------------------------------------------------------------------------------------------

    @Override
    public ImportForecast forecast() {
        return forecast(Map.of(), null);
    }

    @Override
    public ImportForecast forecast(Map<String, String> policy) {
        return forecast(policy, null);
    }

    /**
     * The loans the rows give and the opening journals the loader posts, as at the run's cutover day: each row by
     * {@link LoanRegister#plan}, the function the loader writes by; a branch takes its journal in the first batch with its
     * rows unless another run already gave it one, for every loan imported into it still owing, those of other runs read
     * from the loan book and this run's from its rows ({@link LoanRegisterWriter#openingLoans}, the loader's one rule).
     */
    @Override
    public ImportForecast forecast(Map<String, String> policy, UUID runId) {
        Loaded l = readOnly.execute(s -> load(runId));
        return new LoanForecast(l.orders(), l.codes(), l.headOffice(), l.asAt(), l.opened(), l.others());
    }

    private record Loaded(
            Map<String, List<String>> orders,
            Map<String, String> codes,
            String headOffice,
            LocalDate asAt,
            Set<String> opened,
            Map<String, long[]> others) {}

    private Loaded load(UUID runId) {
        Map<String, List<String>> orders = new HashMap<>();
        jdbc.sql("SELECT code FROM lending_loan_products")
                .query(String.class)
                .list()
                .forEach(code -> products.currentByCode(code).ifPresent(p -> orders.put(code, p.allocationOrder())));
        Map<String, String> codes = new HashMap<>();
        String headOffice = null;
        for (Branches.Branch b : branches.all()) {
            codes.put(b.code().toUpperCase(Locale.ROOT), b.code());
            if (b.headOffice()) {
                headOffice = b.code();
            }
        }
        LocalDate asAt = runId == null ? null : LoanRegisterWriter.cutoverDay(jdbc, runId, tenant);
        Set<String> opened = new HashSet<>();
        for (String kind : List.of(RECEIVABLE_KIND, CREDIT_KIND)) {
            LoanRegisterWriter.openedBranches(jdbc, kind, runId).forEach(code -> opened.add(kind + "|" + code));
        }
        return new Loaded(orders, codes, headOffice, asAt, opened, LoanRegisterWriter.importedLoans(jdbc, runId));
    }

    /** The forecast of one run: see {@link #forecast(Map, UUID)}. */
    static final class LoanForecast implements ImportForecast {

        private final Map<String, List<String>> orders;
        private final Map<String, String> codes;
        private final String headOffice;
        private final LocalDate asAt;
        private final Set<String> opened;
        private final Map<String, long[]> others;
        private final Map<String, long[]> run = new TreeMap<>();
        private final Set<String> inBatch = new HashSet<>();
        private final Map<String, Long> principal = new TreeMap<>();
        private final Map<String, Map<String, long[]>> buckets = new TreeMap<>();
        private final List<Journal> journals = new ArrayList<>();

        /**
         * @param orders the allocation order of each loan product by code
         * @param codes each branch's code by its upper case, as a row may write it
         * @param headOffice the head office's code, the branch of a row without one
         * @param asAt the cutover day the loans are aged to (null: today's position is not aged)
         * @param opened {@code kind|BRANCH} for each branch another run already gave that opening journal
         * @param others per branch the principal and credit other runs' imported loans still hold
         */
        LoanForecast(
                Map<String, List<String>> orders,
                Map<String, String> codes,
                String headOffice,
                LocalDate asAt,
                Set<String> opened,
                Map<String, long[]> others) {
            this.orders = Map.copyOf(orders);
            this.codes = Map.copyOf(codes);
            this.headOffice = headOffice;
            this.asAt = asAt;
            this.opened = new HashSet<>(opened);
            this.others = new TreeMap<>(others);
        }

        @Override
        public void batch(int seq) {
            close();
        }

        @Override
        public void row(String entitySet, Map<String, Object> values) {
            if (!LOANS.equals(entitySet)) {
                return;
            }
            LoanRegister.Row r;
            try {
                r = LoanRegister.row(values);
            } catch (IllegalArgumentException e) {
                return;
            }
            String branch = r.branch() == null
                    ? headOffice
                    : codes.getOrDefault(r.branch().toUpperCase(Locale.ROOT), r.branch());
            BringIn.Plan plan = LoanRegister.plan(r, orders.get(r.product()), asAt == null ? r.dueOn() : asAt);
            long owed = plan.position().principalOutstandingMinor();
            long credit = LoanRegister.credit(plan);
            long[] sums = run.computeIfAbsent(branch, k -> new long[2]);
            sums[0] += owed;
            sums[1] += credit;
            inBatch.add(branch);
            principal.merge(branch, owed, Long::sum);
            if (owed > 0) {
                long[] b = buckets.computeIfAbsent(branch, k -> new TreeMap<>())
                        .computeIfAbsent(LoanRegister.bucket(plan.position().daysPastDue()), k -> new long[2]);
                b[0]++;
                b[1] += owed;
            }
        }

        /** Closes a batch: each branch it held that is not yet opened takes the loader's journals. */
        private void close() {
            List<String> held = inBatch.stream().sorted().toList();
            for (String branch : held) {
                long[] sums = run.get(branch);
                long[] other = others.getOrDefault(branch, new long[2]);
                journal(branch, RECEIVABLE_KIND, other[0] + sums[0]);
                journal(branch, CREDIT_KIND, other[1] + sums[1]);
            }
            inBatch.clear();
        }

        private void journal(String branch, String kind, long amount) {
            if (amount <= 0 || !opened.add(kind + "|" + branch)) {
                return;
            }
            journals.add(new Journal(branch, kind, amount, amount));
        }

        @Override
        public Balances balances() {
            close();
            List<Amount> loans = new ArrayList<>();
            principal.forEach((branch, owed) -> loans.add(new Amount(branch, owed)));
            List<ArrearsBucket> arrears = new ArrayList<>();
            buckets.forEach((branch, byBucket) -> byBucket.forEach(
                    (bucket, b) -> arrears.add(new ArrearsBucket(branch, bucket, Math.toIntExact(b[0]), b[1]))));
            return new Balances(List.of(), List.of(), loans, arrears, List.copyOf(journals));
        }
    }

    /**
     * The books (issue #134): the run's loans, as the loan book holds them, by branch and days-past-due bucket as at the
     * cutover day: each loan's schedule items aged by {@link Servicing#position}, a loan still owing principal only.
     */
    @Override
    public ImportBooks books() {
        return new ImportBooks() {
            @Override
            public List<ImportForecast.ArrearsBucket> arrears(Scope scope, LocalDate asAt) {
                return writer.getObject().arrears(scope.of(LoanRegisterWriter.LOAN), asAt);
            }
        };
    }
}
