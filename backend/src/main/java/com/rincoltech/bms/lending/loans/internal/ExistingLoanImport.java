package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CsvTable;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ImportResult;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.ImportRow;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.ProductTerms;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CSV import of existing loans (FR-EXL-06, ADR-071), on the check-then-commit pattern of the retail item import
 * (#146): a check reads the file and reports, row by row, what would come in, what is skipped and what is wrong; a
 * commit does the same and records every loan of the file, each waiting for its checker, in one transaction. A commit
 * with any error row records nothing, so a half-read file never lands. A loan whose register number ({@code ref}) is
 * already in the app is skipped, so a file committed twice records each loan once. Every loan passes exactly the checks
 * of the form ({@link ExistingLoanService#check}). The import never guesses: dates are year-month-day only, and a
 * repayment row names its loan by {@code ref}.
 */
@Service
class ExistingLoanImport {

    static final int MAX_CHARS = 1_000_000;
    static final int MAX_ROWS = 2_000;

    static final List<String> LOAN_COLUMNS =
            List.of("member_no", "product_code", "principal", "disbursement_date", "term", "agreed_total", "purpose");
    static final List<String> REPAYMENT_COLUMNS = List.of("repayment_date", "amount", "method", "note");

    private static final Pattern ITEM = Pattern.compile("repayments\\[(\\d+)]\\..*");

    private final ExistingLoanService service;
    private final ExistingLoanRepository repo;
    private final MemberLookup members;
    private final ProductCatalog products;
    private final CurrentTenant tenant;
    private final AuditLog audit;

    ExistingLoanImport(
            ExistingLoanService service,
            ExistingLoanRepository repo,
            MemberLookup members,
            ProductCatalog products,
            CurrentTenant tenant,
            AuditLog audit) {
        this.service = service;
        this.repo = repo;
        this.members = members;
        this.products = products;
        this.tenant = tenant;
        this.audit = audit;
    }

    /** One loan of the file: its row, its parsed fields and its repayment rows in file order. */
    private static final class Pending {
        final int line;
        final String ref;
        String problem;
        MemberSummary member;
        ProductTerms product;
        long principal;
        LocalDate disbursed;
        Integer term;
        Long agreedTotal;
        String purpose;
        final List<int[]> repaymentLines = new ArrayList<>();
        final List<BringIn.Line> repayments = new ArrayList<>();
        final Map<Integer, String> repaymentProblems = new HashMap<>();
        ExistingLoanService.Checked checked;

        Pending(int line, String ref) {
            this.line = line;
            this.ref = ref;
        }
    }

    @Transactional
    ImportResult run(String text, boolean dryRun) {
        Principal principal = CurrentPrincipal.require();
        List<List<String>> table = CsvTable.parse(text);
        if (table.isEmpty()) {
            throw problem("The file is empty.");
        }
        Map<String, Integer> columns = columns(table.getFirst());
        for (String required : List.of("kind", "ref")) {
            if (!columns.containsKey(required)) {
                throw problem("The file needs a column called " + required
                        + ". Download the template for the columns and their order.");
            }
        }
        List<List<String>> data = table.subList(1, table.size());
        if (data.size() > MAX_ROWS) {
            throw problem("The file has more than " + MAX_ROWS + " rows. Split it into smaller files.");
        }
        int exponent = repo.currencyExponent(tenant.profile().currency());
        Map<String, Pending> loans = new LinkedHashMap<>();
        Map<Integer, ImportRow> rowErrors = new LinkedHashMap<>();
        List<int[]> orphanRows = new ArrayList<>();
        int read = 0;
        int repaymentRows = 0;
        int line = 1;
        for (List<String> cells : data) {
            line++;
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            read++;
            String kind = cell(cells, columns, "kind").strip().toLowerCase(Locale.ROOT);
            String ref = cell(cells, columns, "ref").strip();
            if (ref.isEmpty() || ref.length() > 40) {
                rowErrors.put(
                        line,
                        error(
                                line,
                                ref,
                                "The ref (the loan's number in the register) is empty or longer than 40 characters."));
                continue;
            }
            String key = ref.toLowerCase(Locale.ROOT);
            if (kind.equals("loan")) {
                if (loans.containsKey(key)) {
                    rowErrors.put(line, error(line, ref, "This ref names a second loan row; each loan has one."));
                    continue;
                }
                Pending p = new Pending(line, ref);
                loans.put(key, p);
                p.problem = readLoan(p, cells, columns, exponent, principal);
            } else if (kind.equals("repayment")) {
                repaymentRows++;
                Pending p = loans.get(key);
                if (p == null) {
                    rowErrors.put(line, error(line, ref, "No loan row with this ref comes before this repayment row."));
                    continue;
                }
                String problem = readRepayment(p, line, cells, columns, exponent);
                if (problem != null) {
                    rowErrors.put(line, error(line, ref, problem));
                }
            } else {
                rowErrors.put(line, error(line, ref, "The kind is loan or repayment."));
            }
        }
        // Each loan that read cleanly passes the form's checks; a refusal lands on the row it is about.
        int skipped = 0;
        List<ImportRow> report = new ArrayList<>();
        Map<Integer, ImportRow> byLine = new java.util.TreeMap<>(rowErrors);
        for (Pending p : loans.values()) {
            if (p.problem != null) {
                byLine.put(p.line, error(p.line, p.ref, p.problem));
                continue;
            }
            if (!p.repaymentProblems.isEmpty()
                    || p.repaymentLines.stream().anyMatch(l -> rowErrors.containsKey(l[0]))) {
                byLine.put(p.line, error(p.line, p.ref, "A repayment row of this loan has an error."));
                continue;
            }
            if (repo.registerRefTaken(p.ref)) {
                skipped++;
                byLine.put(
                        p.line,
                        new ImportRow(p.line, p.ref, "skipped", "A loan with this ref is already in the app.", null));
                for (int[] r : p.repaymentLines) {
                    byLine.put(r[0], new ImportRow(r[0], p.ref, "skipped", "Its loan is already in the app.", null));
                }
                continue;
            }
            try {
                p.checked = service.check(new ExistingLoanService.Draft(
                        p.member,
                        p.product,
                        p.principal,
                        p.disbursed,
                        p.term == null ? p.product.defaults().termCount() : p.term,
                        p.agreedTotal,
                        p.purpose,
                        p.ref,
                        List.copyOf(p.repayments),
                        "import"));
            } catch (ApiException e) {
                FieldProblem first = e.errors().isEmpty() ? null : e.errors().getFirst();
                String message = first == null ? e.getMessage() : first.message();
                int at = p.line;
                if (first != null) {
                    Matcher m = ITEM.matcher(first.field());
                    if (m.matches()) {
                        at = p.repaymentLines.get(Integer.parseInt(m.group(1)))[0];
                    }
                }
                byLine.put(at, error(at, p.ref, message));
                if (at != p.line) {
                    byLine.put(p.line, error(p.line, p.ref, "A repayment row of this loan has an error."));
                }
            }
        }
        int errors = (int) byLine.values().stream()
                .filter(r -> r.outcome().equals("error"))
                .count();
        int added = 0;
        for (Pending p : loans.values()) {
            if (p.checked == null || byLine.containsKey(p.line)) {
                continue;
            }
            UUID loanId = null;
            if (!dryRun && errors == 0) {
                loanId = service.create(p.checked);
            }
            if (errors == 0) {
                added++;
            }
            String outcome = dryRun || errors > 0 ? "ready" : "added";
            byLine.put(
                    p.line,
                    new ImportRow(
                            p.line,
                            p.ref,
                            outcome,
                            p.repayments.size() + " repayment(s); "
                                    + p.checked.plan().position().daysPastDue() + " day(s) past due",
                            loanId));
            for (int[] r : p.repaymentLines) {
                byLine.put(r[0], new ImportRow(r[0], p.ref, outcome, null, null));
            }
        }
        report.addAll(byLine.values());
        if (!dryRun && errors == 0) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("rows_read", read);
            after.put("loans_recorded", added);
            after.put("loans_skipped", skipped);
            audit.record(
                    AuditLog.Entry.created("lending.loan.existing_imported", "lending.loan_import", null, null, after));
        }
        return new ImportResult(
                dryRun, read, loans.size(), repaymentRows, errors > 0 ? 0 : added, skipped, errors, report);
    }

    /** The loan columns of a loan row; the first problem, or null when it reads cleanly. */
    private String readLoan(
            Pending p, List<String> cells, Map<String, Integer> columns, int exponent, Principal principal) {
        for (String c : REPAYMENT_COLUMNS) {
            if (!cell(cells, columns, c).isBlank()) {
                return "A loan row leaves the repayment columns empty; put each repayment on a row of its own.";
            }
        }
        String memberNo = cell(cells, columns, "member_no").strip();
        Optional<MemberSummary> member = memberNo.isEmpty() ? Optional.empty() : members.findByNumber(memberNo);
        if (member.isEmpty()
                || !principal.may(ExistingLoanService.PERMISSION, member.get().branchId())) {
            return "No member with this member number in your branches.";
        }
        p.member = member.get();
        String code = cell(cells, columns, "product_code").strip();
        Optional<ProductTerms> product = code.isEmpty() ? Optional.empty() : products.currentByCode(code);
        if (product.isEmpty()) {
            return "No loan product with this code.";
        }
        p.product = product.get();
        Long principalMinor = CsvTable.amountMinor(cell(cells, columns, "principal"), exponent);
        if (principalMinor == null || principalMinor <= 0 || principalMinor > LoanApi.MAX_MONEY_MINOR) {
            return "The principal must be " + example(exponent) + ".";
        }
        p.principal = principalMinor;
        p.disbursed = date(cell(cells, columns, "disbursement_date"));
        if (p.disbursed == null) {
            return "The disbursement date must be year-month-day, for example 2026-03-31.";
        }
        // The product's terms in force on the disbursement date (ADR-071 decision 2), as the form takes them.
        p.product = products.inForceOn(
                        p.product.productId(), p.disbursed, tenant.profile().timezone())
                .orElse(p.product);
        String term = cell(cells, columns, "term").strip();
        if (!term.isEmpty()) {
            if (!term.matches("\\d{1,4}") || Integer.parseInt(term) < 1) {
                return "The term must be a whole number of the product's term units, or empty for its default.";
            }
            p.term = Integer.parseInt(term);
        }
        String total = cell(cells, columns, "agreed_total").strip();
        if (!total.isEmpty()) {
            p.agreedTotal = CsvTable.amountMinor(total, exponent);
            if (p.agreedTotal == null || p.agreedTotal <= 0 || p.agreedTotal > LoanApi.MAX_MONEY_MINOR) {
                return "The agreed total must be " + example(exponent) + ", or empty to use the product's rate.";
            }
        }
        String purpose = cell(cells, columns, "purpose").strip().toLowerCase(Locale.ROOT);
        if (!purpose.isEmpty() && !purpose.matches(LoanApi.PURPOSES)) {
            return "The purpose is one of " + LoanApi.PURPOSES.replace('|', ' ') + ", or empty for other.";
        }
        p.purpose = purpose.isEmpty() ? "other" : purpose;
        return null;
    }

    /** One repayment row of a loan already read; the problem, or null. */
    private String readRepayment(Pending p, int line, List<String> cells, Map<String, Integer> columns, int exponent) {
        p.repaymentLines.add(new int[] {line});
        String problem = null;
        for (String c : LOAN_COLUMNS) {
            if (!cell(cells, columns, c).isBlank()) {
                problem = "A repayment row leaves the loan columns empty.";
            }
        }
        LocalDate date = date(cell(cells, columns, "repayment_date"));
        Long amount = CsvTable.amountMinor(cell(cells, columns, "amount"), exponent);
        String method =
                cell(cells, columns, "method").strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        String note = cell(cells, columns, "note").strip();
        if (problem != null) {
            // reported below
        } else if (date == null) {
            problem = "The repayment date must be year-month-day, for example 2026-04-30.";
        } else if (amount == null || amount <= 0 || amount > LoanApi.MAX_MONEY_MINOR) {
            problem = "The amount must be " + example(exponent) + ".";
        } else if (!Set.of("cash", "bank", "mtn_momo", "airtel_money").contains(method)) {
            problem = "The method is cash, bank, mtn_momo or airtel_money.";
        } else if (note.length() > 100) {
            problem = "The note is longer than 100 characters.";
        }
        if (problem != null) {
            p.repaymentProblems.put(line, problem);
            return problem;
        }
        p.repayments.add(new BringIn.Line(p.repayments.size() + 1, date, amount, method, note.isEmpty() ? null : note));
        return null;
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).strip().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]+", "_");
            columns.putIfAbsent(name, i);
        }
        return columns;
    }

    private static String cell(List<String> cells, Map<String, Integer> columns, String name) {
        Integer i = columns.get(name);
        return i == null || i >= cells.size() ? "" : cells.get(i);
    }

    private static LocalDate date(String text) {
        String t = text.strip();
        if (!t.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String example(int exponent) {
        return exponent == 0
                ? "a whole amount above 0, for example 500000"
                : "an amount above 0 with at most " + exponent + " decimals, for example 500000 or 500000.50";
    }

    private static ImportRow error(int line, String ref, String message) {
        return new ImportRow(line, ref, "error", message, null);
    }

    private static ApiException problem(String message) {
        return ApiException.validation(List.of(new FieldProblem("csv", "invalid", message)));
    }
}
