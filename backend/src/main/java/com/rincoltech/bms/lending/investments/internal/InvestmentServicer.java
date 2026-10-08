package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.investments.InvestmentServicing;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.InvestmentTransaction;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Product;
import com.rincoltech.bms.lending.investments.internal.InvestmentBooks.Leg;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Inv;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Item;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Txn;
import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.EarlySettlement;
import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.Period;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money events of an investment (FR-INV-03 to FR-INV-06, FR-INV-09, FR-INV-11): each locks the
 * investment, writes its transaction, posts its journal through {@code post_entry}, moves the
 * balance and status columns and audits, all in the caller's transaction. Actors are parameters,
 * so the approval actions, the staff routes, the nightly job and {@link InvestmentServicing} share
 * one implementation.
 */
@Service
class InvestmentServicer implements InvestmentServicing {

    static final String SUBJECT = "lending.investment";

    private final InvestmentRepository repo;
    private final MemberLookup members;
    private final InvestmentBooks books;
    private final Branches branches;
    private final TenantSequences sequences;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    InvestmentServicer(
            InvestmentRepository repo,
            MemberLookup members,
            InvestmentBooks books,
            Branches branches,
            TenantSequences sequences,
            CurrentTenant currentTenant,
            BusinessClock clock,
            AuditLog audit) {
        this.repo = repo;
        this.members = members;
        this.books = books;
        this.branches = branches;
        this.sequences = sequences;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.audit = audit;
    }

    LocalDate today() {
        return clock.today(currentTenant.profile().timezone());
    }

    // ---- InvestmentServicing --------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void fundApproved(
            UUID investmentId, LocalDate valueDate, String paymentMethodKey, UUID makerId, UUID checkerId) {
        if (makerId.equals(checkerId)) {
            throw ApiException.rule("self_approval_forbidden", "The checker is never the maker.");
        }
        Inv inv = repo.lock(investmentId).orElseThrow(ApiException::notFound);
        checkFundable(inv, valueDate, paymentMethodKey);
        fund(inv, valueDate, paymentMethodKey, null, null, makerId, checkerId, "system");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID open(
            UUID memberId, UUID productId, long amountMinor, int termMonths, String instruction, UUID openedBy) {
        MemberSummary member = members.find(memberId).orElseThrow(ApiException::notFound);
        Product p = repo.product(productId).orElseThrow(ApiException::notFound);
        checkOpenable(member, p, amountMinor, termMonths);
        return insert(member, p, amountMinor, termMonths, instruction, "system", openedBy);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID payOutMatured(UUID investmentId, LocalDate valueDate, String paymentMethodKey, UUID recordedBy) {
        Inv inv = repo.lock(investmentId).orElseThrow(ApiException::notFound);
        return payout(inv, valueDate, paymentMethodKey, null, recordedBy, "system");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DailyRun runDaily(LocalDate date) {
        int accruals = 0;
        for (UUID id : repo.withAccrualDue(date)) {
            accruals += accrueDue(repo.lock(id).orElseThrow(), date);
        }
        int matured = 0;
        int rolled = 0;
        for (UUID id : repo.maturing(date)) {
            Inv inv = repo.lock(id).orElseThrow();
            if (!inv.status().equals("active")) {
                continue;
            }
            matured++;
            if (mature(inv, date) != null) {
                rolled++;
            }
        }
        // A rollover started on an earlier maturity date may already have periods ended.
        for (UUID id : repo.withAccrualDue(date)) {
            accruals += accrueDue(repo.lock(id).orElseThrow(), date);
        }
        int reminders = 0;
        for (boolean before : List.of(true, false)) {
            for (UUID id : repo.reminderDue(date, before)) {
                Inv inv = repo.lock(id).orElseThrow();
                repo.reminded(inv.id(), before, date);
                audit.record(
                        AuditLog.Entry.created(
                                "lending.investment.maturity_reminder",
                                SUBJECT,
                                inv.id(),
                                inv.branchId(),
                                Map.of(
                                        "kind",
                                        before ? "before_maturity" : "no_instruction_after_maturity",
                                        "maturity_date",
                                        inv.maturityDate().toString())),
                        null,
                        "system");
                reminders++;
            }
        }
        return new DailyRun(accruals, matured, rolled, reminders);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID payDueReturn(UUID investmentId, LocalDate valueDate, String paymentMethodKey, UUID recordedBy) {
        Inv inv = repo.lock(investmentId).orElseThrow(ApiException::notFound);
        return payReturn(inv, valueDate, paymentMethodKey, null, recordedBy, "system");
    }

    // ---- Opening (FR-INV-02) --------------------------------------------------------------

    /** FR-INV-02: an active member, an active product, a term it offers and an amount in its range. */
    static void checkOpenable(MemberSummary member, Product p, long amountMinor, int termMonths) {
        if (!member.status().equals("active")) {
            throw ApiException.rule("member_not_active", "The member is " + member.status() + ".");
        }
        if (!p.status().equals("active")) {
            throw ApiException.rule("product_archived", "The product is archived.");
        }
        if (!p.allowedTermsMonths().contains(termMonths)) {
            throw ApiException.rule(
                    "term_not_offered",
                    "The product offers terms of " + p.allowedTermsMonths() + " months, not " + termMonths + ".");
        }
        checkAmount(p, amountMinor);
    }

    /** Writes the {@code pending_funding} row at the member's branch on the product's terms, and audits. */
    UUID insert(
            MemberSummary member,
            Product p,
            long amountMinor,
            int termMonths,
            String instruction,
            String channel,
            UUID openedBy) {
        UUID id = UUID.randomUUID();
        String accountNo = "IV%06d".formatted(sequences.next("investment_no"));
        repo.insert(
                id,
                accountNo,
                member.branchId(),
                member.id(),
                p,
                amountMinor,
                termMonths,
                instruction,
                channel,
                openedBy);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("account_no", accountNo);
        after.put("member_id", member.id());
        after.put("product_id", p.id());
        after.put("principal_minor", amountMinor);
        after.put("term_months", termMonths);
        after.put("return_rate_bp", p.returnRateBp());
        after.put("maturity_instruction", instruction);
        after.put("status", "pending_funding");
        AuditLog.Entry entry =
                AuditLog.Entry.created("lending.investment.opened", SUBJECT, id, member.branchId(), after);
        if (channel.equals("system")) {
            audit.record(entry, openedBy, "system");
        } else {
            audit.record(entry);
        }
        return id;
    }

    // ---- Funding (FR-INV-03, FR-INV-09) ---------------------------------------------------

    /** The checks of a funding request, run again at execution. */
    void checkFundable(Inv inv, LocalDate valueDate, String paymentMethodKey) {
        requireStatus(inv, Set.of("pending_funding"));
        if (valueDate.isAfter(today())) {
            throw ApiException.rule("value_date_in_future", "The value date is today or earlier.");
        }
        books.methodAccount(paymentMethodKey);
    }

    /**
     * FR-INV-03: the start and maturity dates, the schedule of R-INV-1 to R-INV-4 and its agreed
     * return, the certificate number, the funding journal (PM / investments payable) and the
     * investment {@code active}.
     */
    void fund(
            Inv inv,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            UUID approvalId,
            UUID makerId,
            UUID checkerId,
            String source) {
        List<Period> periods = InvestmentReturns.schedule(terms(inv, inv.principalMinor()), valueDate);
        long agreed = InvestmentReturns.total(periods);
        LocalDate maturity = periods.getLast().end();
        String method = books.methodAccount(paymentMethodKey);
        String receipt = number("RC", "receipt", inv.branchId());
        String certificate = "IC%06d".formatted(sequences.next("investment_certificate"));
        UUID txnId = UUID.randomUUID();
        PostedEntry entry = books.post(
                inv.branchId(),
                valueDate,
                receipt,
                "Investment funding " + inv.accountNo(),
                txnId,
                "lending.investment.funding:" + inv.id(),
                List.of(
                        Leg.debit(method, inv.principalMinor()),
                        Leg.credit(InvestmentBooks.PAYABLE, inv.principalMinor())
                                .forInvestment(inv.id())));
        repo.insertTxn(new Txn(
                txnId,
                inv.branchId(),
                inv.id(),
                "funding",
                inv.principalMinor(),
                inv.principalMinor(),
                0,
                0,
                inv.currency(),
                valueDate,
                null,
                paymentMethodKey,
                externalReference,
                receipt,
                null,
                null,
                entry.entryId(),
                approvalId,
                source,
                makerId));
        repo.funded(inv.id(), valueDate, maturity, agreed, certificate);
        repo.insertItems(inv.id(), periods);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "active");
        after.put("start_date", valueDate.toString());
        after.put("maturity_date", maturity.toString());
        after.put("principal_minor", inv.principalMinor());
        after.put("agreed_return_minor", agreed);
        after.put("receipt_no", receipt);
        after.put("certificate_no", certificate);
        after.put("payment_method_key", paymentMethodKey);
        after.put("approval_request_id", approvalId);
        after.put("checker_id", checkerId);
        audit.record(new AuditLog.Entry(
                "lending.investment.funded",
                SUBJECT,
                inv.id(),
                inv.branchId(),
                Map.of("status", "pending_funding"),
                after));
    }

    // ---- Accrual (FR-INV-04, FR-INV-09) ---------------------------------------------------

    /**
     * Accrues every period of an active investment ended on or before {@code date}, oldest first,
     * each on its period end date: return expense / returns payable. A payout period makes the
     * return accrued so far due (R-INV-4). The caller holds the lock; a period already accrued is
     * skipped, and the unique accrual index refuses a second one, so the job is idempotent.
     */
    int accrueDue(Inv inv, LocalDate date) {
        if (!inv.status().equals("active")) {
            return 0;
        }
        long accrued = inv.returnAccruedMinor();
        long due = inv.returnDueMinor();
        int count = 0;
        for (Item item : repo.items(inv.id())) {
            if (!item.status().equals("scheduled") || item.periodEnd().isAfter(date)) {
                continue;
            }
            UUID txnId = null;
            if (item.returnMinor() > 0) {
                txnId = UUID.randomUUID();
                PostedEntry entry = books.post(
                        inv.branchId(),
                        item.periodEnd(),
                        inv.accountNo(),
                        "Investment return accrual " + inv.accountNo() + " month " + item.periodNo(),
                        txnId,
                        "lending.investment.accrual:" + inv.id() + ":" + item.periodNo(),
                        List.of(
                                Leg.debit(InvestmentBooks.RETURN_EXPENSE, item.returnMinor()),
                                Leg.credit(InvestmentBooks.RETURNS_PAYABLE, item.returnMinor())
                                        .forInvestment(inv.id())));
                repo.insertTxn(new Txn(
                        txnId,
                        inv.branchId(),
                        inv.id(),
                        "return_accrual",
                        item.returnMinor(),
                        0,
                        item.returnMinor(),
                        0,
                        inv.currency(),
                        item.periodEnd(),
                        item.periodNo(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        entry.entryId(),
                        null,
                        "system",
                        null));
            }
            repo.itemAccrued(item.id(), txnId);
            accrued += item.returnMinor();
            if (item.payout()) {
                due = accrued;
            }
            count++;
        }
        if (count > 0) {
            repo.balances(
                    inv.id(),
                    inv.status(),
                    inv.principalHeldMinor(),
                    accrued,
                    due,
                    inv.returnPaidMinor(),
                    inv.closedOn());
        }
        return count;
    }

    // ---- Maturity (FR-INV-05, FR-INV-08) --------------------------------------------------

    /**
     * Marks an active investment {@code matured} on its maturity date, after accruing its last
     * period, and applies the member's instruction: a rollover runs at once; a recurring product
     * with no instruction rolls principal and return over (FR-INV-08); otherwise it waits for the
     * cashier's payout. Returns the new investment of a rollover, or null.
     */
    UUID mature(Inv inv, LocalDate date) {
        accrueDue(inv, date);
        Inv current = repo.find(inv.id()).orElseThrow();
        repo.balances(
                current.id(),
                "matured",
                current.principalHeldMinor(),
                current.returnAccruedMinor(),
                current.returnAccruedMinor(),
                current.returnPaidMinor(),
                null);
        audit.record(
                new AuditLog.Entry(
                        "lending.investment.matured",
                        SUBJECT,
                        current.id(),
                        current.branchId(),
                        Map.of("status", "active"),
                        Map.of(
                                "status",
                                "matured",
                                "maturity_date",
                                current.maturityDate().toString(),
                                "return_minor",
                                current.returnAccruedMinor())),
                null,
                "system");
        String mode = current.maturityInstruction();
        if (mode == null && current.productType().equals("recurring")) {
            mode = "rollover_all";
        }
        if (mode == null || mode.equals("payout")) {
            return null;
        }
        Inv matured = repo.find(inv.id()).orElseThrow();
        try {
            checkRollover(matured, mode);
        } catch (ApiException e) {
            // The product changed under the instruction: the investment waits for staff (FR-INV-05).
            audit.record(
                    AuditLog.Entry.created(
                            "lending.investment.rollover_skipped",
                            SUBJECT,
                            matured.id(),
                            matured.branchId(),
                            Map.of("mode", mode, "reason", e.code())),
                    null,
                    "system");
            return null;
        }
        return rollover(matured, mode, null, "system");
    }

    // ---- Return payout (FR-INV-04) --------------------------------------------------------

    /** Pays the return due and unpaid in full: returns payable / PM. */
    UUID payReturn(
            Inv inv,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            UUID recordedBy,
            String source) {
        requireStatus(inv, Set.of("active", "matured", "rolled_over"));
        if (valueDate.isAfter(today())) {
            throw ApiException.rule("value_date_in_future", "The value date is today or earlier.");
        }
        long amount = inv.returnAvailableMinor();
        if (amount <= 0) {
            throw ApiException.rule("no_return_due", "No return is due for payout on this investment.");
        }
        String method = books.methodAccount(paymentMethodKey);
        String voucher = number("VC", "voucher", inv.branchId());
        UUID txnId = UUID.randomUUID();
        PostedEntry entry = books.post(
                inv.branchId(),
                valueDate,
                voucher,
                "Investment return payout " + inv.accountNo(),
                txnId,
                "lending.investment.txn:" + txnId,
                List.of(
                        Leg.debit(InvestmentBooks.RETURNS_PAYABLE, amount).forInvestment(inv.id()),
                        Leg.credit(method, amount)));
        repo.insertTxn(new Txn(
                txnId,
                inv.branchId(),
                inv.id(),
                "return_payout",
                amount,
                0,
                amount,
                0,
                inv.currency(),
                valueDate,
                null,
                paymentMethodKey,
                externalReference,
                voucher,
                null,
                null,
                entry.entryId(),
                null,
                source,
                recordedBy));
        repo.balances(
                inv.id(),
                inv.status(),
                inv.principalHeldMinor(),
                inv.returnAccruedMinor(),
                inv.returnDueMinor(),
                inv.returnPaidMinor() + amount,
                inv.closedOn());
        audit.record(AuditLog.Entry.created(
                "lending.investment.return_paid",
                SUBJECT,
                inv.id(),
                inv.branchId(),
                Map.of("amount_minor", amount, "voucher_no", voucher, "payment_method_key", paymentMethodKey)));
        return txnId;
    }

    // ---- Maturity payout (FR-INV-05) ------------------------------------------------------

    /** Principal plus the unpaid return: investments payable and returns payable / PM; the investment is paid out. */
    UUID payout(
            Inv inv,
            LocalDate today,
            String paymentMethodKey,
            String externalReference,
            UUID recordedBy,
            String source) {
        requireStatus(inv, Set.of("matured"));
        if (today.isAfter(today()) || today.isBefore(inv.maturityDate())) {
            throw ApiException.rule("value_date_out_of_range", "The value date is between maturity and today.");
        }
        long principal = inv.principalHeldMinor();
        long ret = inv.returnPayableMinor();
        long total = principal + ret;
        String method = books.methodAccount(paymentMethodKey);
        String voucher = number("VC", "voucher", inv.branchId());
        UUID txnId = UUID.randomUUID();
        PostedEntry entry = books.post(
                inv.branchId(),
                today,
                voucher,
                "Investment maturity payout " + inv.accountNo(),
                txnId,
                "lending.investment.txn:" + txnId,
                List.of(
                        Leg.debit(InvestmentBooks.PAYABLE, principal).forInvestment(inv.id()),
                        Leg.debit(InvestmentBooks.RETURNS_PAYABLE, ret).forInvestment(inv.id()),
                        Leg.credit(method, total)));
        repo.insertTxn(new Txn(
                txnId,
                inv.branchId(),
                inv.id(),
                "maturity_payout",
                total,
                principal,
                ret,
                0,
                inv.currency(),
                today,
                null,
                paymentMethodKey,
                externalReference,
                voucher,
                null,
                null,
                entry.entryId(),
                null,
                source,
                recordedBy));
        repo.balances(
                inv.id(),
                "paid_out",
                0,
                inv.returnAccruedMinor(),
                inv.returnAccruedMinor(),
                inv.returnAccruedMinor(),
                today);
        audit.record(new AuditLog.Entry(
                "lending.investment.paid_out",
                SUBJECT,
                inv.id(),
                inv.branchId(),
                Map.of("status", "matured"),
                Map.of(
                        "status", "paid_out",
                        "principal_minor", principal,
                        "return_minor", ret,
                        "voucher_no", voucher,
                        "payment_method_key", paymentMethodKey)));
        return txnId;
    }

    // ---- Rollover (FR-INV-05) -------------------------------------------------------------

    /** The product a rollover takes its current terms from; refused with the rule that fails. */
    Product checkRollover(Inv inv, String mode) {
        requireStatus(inv, Set.of("matured"));
        Product p = repo.product(inv.productId()).orElseThrow();
        if (!p.status().equals("active")) {
            throw ApiException.rule("product_archived", "The product is archived; pay the investment out instead.");
        }
        if (!p.allowedTermsMonths().contains(inv.termMonths())) {
            throw ApiException.rule(
                    "term_not_offered", "The product no longer offers a " + inv.termMonths() + " month term.");
        }
        checkAmount(p, rolloverAmount(inv, mode));
        return p;
    }

    private static long rolloverAmount(Inv inv, String mode) {
        return inv.principalHeldMinor() + (mode.equals("rollover_all") ? inv.returnPayableMinor() : 0);
    }

    /**
     * A new investment from the maturity date on the product's current terms, funded by moving the
     * principal (and with {@code rollover_all} the unpaid return) from the old investment: one
     * journal, investments payable (old) and returns payable / investments payable (new).
     */
    UUID rollover(Inv inv, String mode, UUID by, String source) {
        Product p = checkRollover(inv, mode);
        long ret = mode.equals("rollover_all") ? inv.returnPayableMinor() : 0;
        long amount = inv.principalHeldMinor() + ret;
        LocalDate start = inv.maturityDate();
        UUID newId = UUID.randomUUID();
        repo.insert(
                newId,
                "IV%06d".formatted(sequences.next("investment_no")),
                inv.branchId(),
                inv.memberId(),
                p,
                amount,
                inv.termMonths(),
                inv.maturityInstruction(),
                "system".equals(source) ? "system" : "staff",
                by);
        Inv next = repo.lock(newId).orElseThrow();
        List<Period> periods = InvestmentReturns.schedule(terms(next, amount), start);
        long agreed = InvestmentReturns.total(periods);
        String certificate = "IC%06d".formatted(sequences.next("investment_certificate"));
        UUID outId = UUID.randomUUID();
        UUID inId = UUID.randomUUID();
        PostedEntry entry = books.post(
                inv.branchId(),
                start,
                next.accountNo(),
                "Investment rollover " + inv.accountNo() + " to " + next.accountNo(),
                outId,
                "lending.investment.rollover:" + inv.id(),
                List.of(
                        Leg.debit(InvestmentBooks.PAYABLE, inv.principalHeldMinor())
                                .forInvestment(inv.id()),
                        Leg.debit(InvestmentBooks.RETURNS_PAYABLE, ret).forInvestment(inv.id()),
                        Leg.credit(InvestmentBooks.PAYABLE, amount).forInvestment(newId)));
        repo.insertTxn(new Txn(
                outId,
                inv.branchId(),
                inv.id(),
                "rollover_out",
                amount,
                inv.principalHeldMinor(),
                ret,
                0,
                inv.currency(),
                start,
                null,
                null,
                null,
                null,
                mode,
                null,
                entry.entryId(),
                null,
                source,
                by));
        repo.insertTxn(new Txn(
                inId,
                inv.branchId(),
                newId,
                "rollover_in",
                amount,
                amount,
                0,
                0,
                next.currency(),
                start,
                null,
                null,
                null,
                null,
                mode,
                null,
                entry.entryId(),
                null,
                source,
                by));
        repo.funded(newId, start, periods.getLast().end(), agreed, certificate);
        repo.insertItems(newId, periods);
        repo.balances(
                inv.id(),
                "rolled_over",
                0,
                inv.returnAccruedMinor(),
                inv.returnDueMinor(),
                inv.returnPaidMinor() + ret,
                start);
        repo.rolledOver(inv.id(), newId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "rolled_over");
        after.put("mode", mode);
        after.put("amount_minor", amount);
        after.put("new_investment_id", newId);
        after.put("new_account_no", next.accountNo());
        after.put("return_rate_bp", p.returnRateBp());
        after.put("agreed_return_minor", agreed);
        AuditLog.Entry entryLog = new AuditLog.Entry(
                "lending.investment.rolled_over",
                SUBJECT,
                inv.id(),
                inv.branchId(),
                Map.of("status", "matured"),
                after);
        if (by == null) {
            audit.record(entryLog, null, "system");
        } else {
            audit.record(entryLog);
        }
        return newId;
    }

    // ---- Early withdrawal (FR-INV-06) -----------------------------------------------------

    /** R-INV-6 as at {@code date}, after accruing the periods already ended; refused when not allowed. */
    EarlySettlement earlyQuote(Inv inv, LocalDate date) {
        requireStatus(inv, Set.of("active"));
        if (!inv.earlyWithdrawalAllowed()) {
            throw ApiException.rule(
                    "early_withdrawal_not_allowed", "The investment's product does not allow early withdrawal.");
        }
        if (!date.isBefore(inv.maturityDate())) {
            throw ApiException.rule("investment_matured", "The investment has reached maturity; pay it out instead.");
        }
        if (date.isBefore(inv.startDate())) {
            throw ApiException.rule("before_start", "The date is before the investment started.");
        }
        long accrued = inv.returnAccruedMinor()
                + repo.items(inv.id()).stream()
                        .filter(i ->
                                i.status().equals("scheduled") && !i.periodEnd().isAfter(date))
                        .mapToLong(Item::returnMinor)
                        .sum();
        EarlySettlement s = InvestmentReturns.early(
                inv.principalHeldMinor(),
                inv.earlyWithdrawalRule(),
                inv.earlyWithdrawalRateBp(),
                inv.earlyWithdrawalPenaltyBp(),
                inv.startDate(),
                date,
                accrued,
                inv.returnPaidMinor());
        if (s.cashMinor() <= 0) {
            throw ApiException.rule(
                    "nothing_to_pay", "The returns already paid exceed what early withdrawal leaves to pay.");
        }
        return s;
    }

    /**
     * FR-INV-06 on approval, on the business date: accrues the ended periods, cancels the rest,
     * and posts one entry that clears the investment's two liabilities, trues the return expense
     * up or down to the earned return, takes the penalty to income and pays the cash.
     */
    UUID earlyWithdraw(
            UUID investmentId,
            String paymentMethodKey,
            String externalReference,
            String reason,
            UUID approvalId,
            UUID makerId,
            UUID checkerId) {
        LocalDate today = today();
        Inv locked = repo.lock(investmentId).orElseThrow(ApiException::notFound);
        earlyQuote(locked, today);
        accrueDue(locked, today);
        Inv inv = repo.find(investmentId).orElseThrow();
        EarlySettlement s = earlyQuote(inv, today);
        String method = books.methodAccount(paymentMethodKey);
        String voucher = number("VC", "voucher", inv.branchId());
        UUID txnId = UUID.randomUUID();
        long accrued = s.accruedMinor();
        long earned = s.earnedMinor();
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(InvestmentBooks.PAYABLE, s.principalMinor()).forInvestment(inv.id()));
        legs.add(Leg.debit(InvestmentBooks.RETURNS_PAYABLE, accrued - s.paidMinor())
                .forInvestment(inv.id()));
        if (earned > accrued) {
            legs.add(Leg.debit(InvestmentBooks.RETURN_EXPENSE, earned - accrued));
        } else if (accrued > earned) {
            legs.add(Leg.credit(InvestmentBooks.RETURN_EXPENSE, accrued - earned));
        }
        legs.add(Leg.credit(method, s.cashMinor()));
        legs.add(Leg.credit(InvestmentBooks.PENALTY_INCOME, s.penaltyMinor()));
        PostedEntry entry = books.post(
                inv.branchId(),
                today,
                voucher,
                "Investment early withdrawal " + inv.accountNo(),
                txnId,
                "lending.investment.txn:" + txnId,
                legs);
        repo.insertTxn(new Txn(
                txnId,
                inv.branchId(),
                inv.id(),
                "early_withdrawal",
                s.cashMinor(),
                s.principalMinor(),
                s.returnNowMinor(),
                s.penaltyMinor(),
                inv.currency(),
                today,
                null,
                paymentMethodKey,
                externalReference,
                voucher,
                reason,
                null,
                entry.entryId(),
                approvalId,
                "staff",
                makerId));
        repo.cancelScheduled(inv.id());
        repo.balances(inv.id(), "withdrawn_early", 0, earned, earned, earned, today);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "withdrawn_early");
        after.put("rule", inv.earlyWithdrawalRule());
        after.put("principal_minor", s.principalMinor());
        after.put("earned_return_minor", earned);
        after.put("accrued_return_minor", accrued);
        after.put("paid_return_minor", s.paidMinor());
        after.put("penalty_minor", s.penaltyMinor());
        after.put("cash_minor", s.cashMinor());
        after.put("voucher_no", voucher);
        after.put("reason", reason);
        after.put("approval_request_id", approvalId);
        after.put("checker_id", checkerId);
        audit.record(new AuditLog.Entry(
                "lending.investment.withdrawn_early",
                SUBJECT,
                inv.id(),
                inv.branchId(),
                Map.of("status", "active"),
                after));
        return txnId;
    }

    // ---- Reversal (FR-INV-11) -------------------------------------------------------------

    /**
     * The reversal rules: a funding with nothing after it, a return payout, or a maturity payout,
     * each once. Accruals, rollovers and early withdrawals are not reversed here (chapter 3
     * section 3.25.1).
     */
    InvestmentTransaction checkReversible(Inv inv, InvestmentTransaction txn) {
        if (txn.reversedByTxnId() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "already_reversed",
                    "Already reversed",
                    "The transaction is reversed already.");
        }
        switch (txn.txnType()) {
            case "funding" -> {
                requireStatus(inv, Set.of("active"));
                if (repo.hasOtherTxns(inv.id(), txn.id())) {
                    throw ApiException.rule(
                            "not_reversible",
                            "A funding is reversed only before any return accrues or any other money moves.");
                }
            }
            case "return_payout" -> requireStatus(inv, Set.of("active", "matured", "rolled_over"));
            case "maturity_payout" -> requireStatus(inv, Set.of("paid_out"));
            default ->
                throw ApiException.rule(
                        "not_reversible", "Only a funding, a return payout or a maturity payout is reversed.");
        }
        return txn;
    }

    /** The mirror of the original journal, on the business date, and the balances and status as before it. */
    void reverse(UUID txnId, String reason, UUID approvalId, UUID makerId, UUID checkerId) {
        InvestmentTransaction txn = repo.txn(txnId).orElseThrow(ApiException::notFound);
        Inv inv = repo.lock(txn.investmentId()).orElseThrow(ApiException::notFound);
        checkReversible(inv, txn);
        LocalDate today = today();
        UUID reversalId = UUID.randomUUID();
        PostedEntry entry =
                books.reverse(txn.journalEntryId(), today, txn.receiptNo(), "lending.investment.txn:" + reversalId);
        repo.insertTxn(new Txn(
                reversalId,
                inv.branchId(),
                inv.id(),
                "reversal",
                txn.amountMinor(),
                txn.principalMinor(),
                txn.returnMinor(),
                txn.penaltyMinor(),
                txn.currency(),
                today,
                null,
                txn.paymentMethodKey(),
                null,
                null,
                reason,
                txn.id(),
                entry.entryId(),
                approvalId,
                "staff",
                makerId));
        String status = inv.status();
        switch (txn.txnType()) {
            case "funding" -> {
                repo.cancelScheduled(inv.id());
                repo.balances(inv.id(), "cancelled", 0, 0, 0, 0, today);
                status = "cancelled";
            }
            case "return_payout" ->
                repo.balances(
                        inv.id(),
                        inv.status(),
                        inv.principalHeldMinor(),
                        inv.returnAccruedMinor(),
                        inv.returnDueMinor(),
                        inv.returnPaidMinor() - txn.returnMinor(),
                        inv.closedOn());
            case "maturity_payout" -> {
                repo.balances(
                        inv.id(),
                        "matured",
                        txn.principalMinor(),
                        inv.returnAccruedMinor(),
                        inv.returnDueMinor(),
                        inv.returnPaidMinor() - txn.returnMinor(),
                        null);
                status = "matured";
            }
            default -> throw new IllegalStateException(txn.txnType());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reversed_txn_id", txn.id());
        after.put("txn_type", txn.txnType());
        after.put("amount_minor", txn.amountMinor());
        after.put("status", status);
        after.put("reason", reason);
        after.put("approval_request_id", approvalId);
        after.put("checker_id", checkerId);
        audit.record(new AuditLog.Entry(
                "lending.investment.transaction_reversed",
                SUBJECT,
                inv.id(),
                inv.branchId(),
                Map.of("status", inv.status()),
                after));
    }

    // ---- Shared ---------------------------------------------------------------------------

    /** FR-GL-08: the payment method maps to an active account. */
    void checkMethod(String paymentMethodKey) {
        books.methodAccount(paymentMethodKey);
    }

    static InvestmentReturns.Terms terms(Inv inv, long principal) {
        return new InvestmentReturns.Terms(
                principal, inv.returnRateBp(), inv.returnMethod(), inv.termMonths(), inv.payoutFrequency());
    }

    /** FR-INV-01: the amount within the product's minimum and maximum. */
    static void checkAmount(Product p, long amount) {
        if (amount < p.minAmountMinor() || (p.maxAmountMinor() != null && amount > p.maxAmountMinor())) {
            throw ApiException.rule(
                    "amount_out_of_range",
                    "The amount is outside the product's range of " + p.minAmountMinor() + " to "
                            + (p.maxAmountMinor() == null ? "no maximum" : p.maxAmountMinor()) + ".");
        }
    }

    /** FR-DOC-04: {@code VC-HQ-000123}, gap-free per branch, shared with the loan receipts and vouchers. */
    private String number(String prefix, String sequence, UUID branchId) {
        String code = branches.all().stream()
                .filter(b -> b.id().equals(branchId))
                .findFirst()
                .orElseThrow()
                .code();
        return "%s-%s-%06d".formatted(prefix, code, sequences.next(sequence + ":" + code));
    }

    static void requireStatus(Inv inv, Set<String> allowed) {
        if (!allowed.contains(inv.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "The investment is " + inv.status() + ".");
        }
    }
}
