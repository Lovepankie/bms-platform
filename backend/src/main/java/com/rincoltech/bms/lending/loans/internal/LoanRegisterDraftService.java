package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.help.SetupDrafts;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.RegisterDraft;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.RegisterDraftLoan;
import com.rincoltech.bms.lending.loans.internal.ExistingLoanApi.RegisterDraftPayment;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The setup helper's step for a lender (issue #626, owner decision D62, ADR-102): a photo, PDF or spreadsheet of a loan
 * register is read by the free tier models into draft loans of the existing loans import, each with its member's name and
 * phone and the payments made so far, as was done by hand for the first lender. A member is matched by phone on this side,
 * never by the model: the member list is not sent. Nothing is saved here: the loans go into the import's check on the
 * screen, and only the tap on Record there brings them in, each waiting for a second person's approval as before.
 */
@Service
class LoanRegisterDraftService {

    static final String INSTRUCTIONS = """
            You read a money lender's loan register for the BMS app: typed text, a photo of the register book, a PDF, or \
            the cells of a spreadsheet. Draft one entry per loan. Use ONLY what the register says: never invent a loan, a \
            name, a phone, an amount, a date or a payment. For each loan give: ref, the loan's number in the register, or \
            null; member_name, the borrower's name as written; member_phone as written, or null; principal, the amount \
            lent as a plain number; date, the day the money was given out as YYYY-MM-DD (a register written day first, \
            such as 23/10/26, is 2026-10-23), or null when it is not clear; term_months, or null; \
            rate_percent_per_month, or null; total_to_repay, only when the register writes it, else null; and payments, \
            each payment made so far with its date as YYYY-MM-DD and its amount. Leave out totals, balances carried \
            forward and anything that is not a loan. Reply with one JSON object only: {"loans": [{"ref": null, \
            "member_name": "", "member_phone": null, "principal": 0, "date": null, "term_months": null, \
            "rate_percent_per_month": null, "total_to_repay": null, "payments": [{"date": "", "amount": 0}]}]}""";

    private final SetupDrafts drafts;
    private final MemberLookup members;
    private final ExistingLoanRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate reads;

    LoanRegisterDraftService(
            SetupDrafts drafts,
            MemberLookup members,
            ExistingLoanRepository repo,
            CurrentTenant tenant,
            BusinessClock clock,
            ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.drafts = drafts;
        this.members = members;
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
        this.mapper = mapper;
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    RegisterDraft draft(String text, MultipartFile file) {
        drafts.requireOn();
        SetupDrafts.Upload upload = SetupDrafts.Upload.of(file);
        Principal principal = CurrentPrincipal.require();
        LocalDate today = clock.today(tenant.profile().timezone());
        String currency = tenant.profile().currency();
        Integer exponent = reads.execute(tx -> repo.currencyExponent(currency));
        String context = "Currency: " + currency + ". Today: " + today + ".";
        String prefix = "R" + today.format(DateTimeFormatter.ofPattern("yyMMdd"));
        SetupDrafts.Result<List<LoanRegisterReader.Loan>> result = drafts.draft(
                new SetupDrafts.Request("lending", INSTRUCTIONS, context, text, upload),
                new LoanRegisterReader(mapper, exponent, prefix));
        List<LoanRegisterReader.Loan> loans = result.drafted().orElse(List.of());
        // The member each phone already belongs to, read here and not by the model.
        List<RegisterDraftLoan> rows = reads.execute(tx -> loans.stream()
                .map(l -> {
                    List<MemberSummary> holders =
                            l.memberPhone() == null ? List.of() : members.findByPhone(l.memberPhone());
                    // Only a member in a branch the caller may bring loans into is named; any other stays a new row,
                    // which the import refuses in words that name no member, so neither tells who holds a phone
                    // elsewhere.
                    MemberSummary member = holders.size() == 1
                                    && principal.may(
                                            ExistingLoanService.PERMISSION,
                                            holders.getFirst().branchId())
                            ? holders.getFirst()
                            : null;
                    return new RegisterDraftLoan(
                            l.ref(),
                            member == null ? null : member.memberNo(),
                            member == null ? null : member.fullName(),
                            l.memberName(),
                            l.memberPhone(),
                            l.principalMinor(),
                            l.disbursementDate(),
                            l.termMonths(),
                            l.agreedTotalMinor(),
                            l.ratePercent(),
                            l.totalWorkedOut(),
                            l.payments().stream()
                                    .map(p -> new RegisterDraftPayment(p.date(), p.amountMinor()))
                                    .toList());
                })
                .toList());
        return new RegisterDraft(result.reason(), rows, exponent);
    }
}
