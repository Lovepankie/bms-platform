package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.savings.SavingsServicing;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Product;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Transaction;
import com.rincoltech.bms.lending.savings.internal.SavingsRepository.AccountRow;
import com.rincoltech.bms.lending.savings.internal.SavingsRepository.Txn;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money events of a savings account (FR-SAV-02 to FR-SAV-07): each locks the account row,
 * checks its rules, writes its transaction with the running balance, posts its journal through
 * {@code post_entry}, moves the balance and audits, all in the caller's transaction. Actors are
 * parameters, so the approval actions, the staff routes, the nightly end of day and
 * {@link SavingsServicing} share one implementation (ADR-032).
 */
@Service
class SavingsServicer implements SavingsServicing {

    static final String SUBJECT = "lending.savings_account";

    private static final Logger log = LoggerFactory.getLogger(SavingsServicer.class);

    private static final Set<String> REVERSIBLE = Set.of("deposit", "withdrawal");

    private final SavingsRepository repo;
    private final SavingsBooks books;
    private final SavingsNotices notices;
    private final MemberLookup members;
    private final Branches branches;
    private final TenantSequences sequences;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    SavingsServicer(
            SavingsRepository repo,
            SavingsBooks books,
            SavingsNotices notices,
            MemberLookup members,
            Branches branches,
            TenantSequences sequences,
            CurrentTenant currentTenant,
            BusinessClock clock,
            AuditLog audit) {
        this.repo = repo;
        this.books = books;
        this.notices = notices;
        this.members = members;
        this.branches = branches;
        this.sequences = sequences;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.audit = audit;
    }

    LocalDate today() {
        return clock.today(currentTenant.profile().timezone());
    }

    // ---- SavingsServicing -----------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID openAccount(UUID memberId, UUID productId, LocalDate openedOn, UUID openedBy) {
        MemberSummary member = members.lock(memberId).orElseThrow(ApiException::notFound);
        return open(member, productId, member.branchId(), openedOn, openedBy);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID deposit(UUID accountId, long amountMinor, LocalDate valueDate, String paymentMethodKey, UUID by) {
        AccountRow a = repo.lock(accountId).orElseThrow(ApiException::notFound);
        checkDeposit(a, amountMinor, valueDate, paymentMethodKey);
        return deposit(a, amountMinor, valueDate, paymentMethodKey, null, by, "system");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID withdraw(
            UUID accountId,
            long amountMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            UUID makerId,
            UUID checkerId) {
        if (makerId.equals(checkerId)) {
            throw ApiException.rule("self_approval_forbidden", "The checker is never the maker.");
        }
        AccountRow a = repo.lock(accountId).orElseThrow(ApiException::notFound);
        checkWithdrawal(a, amountMinor, valueDate, paymentMethodKey);
        return withdraw(a, amountMinor, valueDate, paymentMethodKey, null, null, makerId, checkerId, "system");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int endOfDay(LocalDate through) {
        int postings = 0;
        for (UUID id : repo.openAccountsBehind(through)) {
            postings += endOfDay(id, through);
        }
        markDormant(through.plusDays(1));
        return postings;
    }

    // ---- Opening (FR-SAV-02) --------------------------------------------------------------

    UUID open(MemberSummary member, UUID productId, UUID branchId, LocalDate openedOn, UUID by) {
        if (!member.status().equals("active")) {
            throw ApiException.rule("member_not_active", "Only an active member can open a savings account.");
        }
        Product product = repo.product(productId)
                .filter(p -> p.status().equals("active"))
                .orElseThrow(() -> ApiException.rule("product_not_active", "The savings product is not active."));
        if (openedOn.isAfter(today())) {
            throw ApiException.rule("value_date_in_future", "The opening date is today or earlier.");
        }
        UUID id = UUID.randomUUID();
        String accountNo = "SV%06d".formatted(sequences.next("savings_no"));
        repo.insertAccount(id, branchId, accountNo, member.id(), productId, product.currency(), openedOn, by);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("account_no", accountNo);
        after.put("member_id", member.id());
        after.put("product_code", product.code());
        after.put("opened_on", openedOn.toString());
        audit.record(AuditLog.Entry.created("lending.savings_account.opened", SUBJECT, id, branchId, after));
        return id;
    }

    // ---- Deposits (FR-SAV-03) -------------------------------------------------------------

    void checkDeposit(AccountRow a, long amountMinor, LocalDate valueDate, String paymentMethodKey) {
        if (a.status().equals("closed")) {
            throw closed();
        }
        checkValueDate(a, valueDate);
        if (a.txnCount() == 0 && amountMinor < a.minOpeningBalanceMinor()) {
            throw ApiException.rule(
                    "below_minimum_opening",
                    "The first deposit must be at least the product's minimum opening balance ("
                            + a.minOpeningBalanceMinor() + ").");
        }
        books.methodAccount(paymentMethodKey);
    }

    UUID deposit(
            AccountRow a,
            long amountMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            UUID by,
            String source) {
        String method = books.methodAccount(paymentMethodKey);
        String receipt = number("RC", "receipt", a.branchId());
        UUID txnId = UUID.randomUUID();
        PostedEntry entry = books.post(
                a.branchId(),
                valueDate,
                receipt,
                "Savings deposit " + a.accountNo(),
                txnId,
                a.id(),
                method,
                SavingsBooks.MEMBER_SAVINGS,
                amountMinor);
        long balance = Math.addExact(a.balanceMinor(), amountMinor);
        repo.insertTxn(new Txn(
                txnId,
                a.branchId(),
                a.id(),
                a.txnCount() + 1,
                "deposit",
                amountMinor,
                true,
                a.currency(),
                balance,
                valueDate,
                paymentMethodKey,
                externalReference,
                receipt,
                null,
                null,
                null,
                entry.entryId(),
                null,
                source,
                by));
        repo.moved(a.id(), balance, valueDate);
        audit.record(AuditLog.Entry.created(
                "lending.savings.deposit_recorded",
                SUBJECT,
                a.id(),
                a.branchId(),
                movement(txnId, amountMinor, valueDate, receipt)));
        if (source.equals("staff")) {
            notices.receipt("savings.deposit", a, txnId, amountMinor, balance, receipt);
        }
        return txnId;
    }

    // ---- Withdrawals (FR-SAV-03, FR-SAV-06) -----------------------------------------------

    /** The checks of a withdrawal request, run again at execution: status, limits, balance, method. */
    void checkWithdrawal(AccountRow a, long amountMinor, LocalDate valueDate, String paymentMethodKey) {
        requireActive(a);
        checkValueDate(a, valueDate);
        if (a.maxWithdrawalMinor() != null && amountMinor > a.maxWithdrawalMinor()) {
            throw ApiException.rule(
                    "withdrawal_limit_exceeded",
                    "One withdrawal may take at most " + a.maxWithdrawalMinor() + " on this product.");
        }
        if (a.maxWithdrawalsPerMonth() != null
                && repo.withdrawalsInMonth(a.id(), valueDate) >= a.maxWithdrawalsPerMonth()) {
            throw ApiException.rule(
                    "withdrawal_count_exceeded",
                    "The product allows " + a.maxWithdrawalsPerMonth() + " withdrawals in a calendar month.");
        }
        long available = available(a);
        if (amountMinor > available) {
            throw ApiException.rule(
                    "insufficient_balance",
                    "The most this account can pay out now is " + available
                            + " (balance less any hold, the minimum balance and the withdrawal fee).");
        }
        books.methodAccount(paymentMethodKey);
    }

    /** Balance less hold, minimum balance and the fee: the largest withdrawal allowed now. */
    static long available(AccountRow a) {
        return Math.max(0, a.balanceMinor() - a.holdMinor() - a.minBalanceMinor() - a.withdrawalFeeMinor());
    }

    UUID withdraw(
            AccountRow a,
            long amountMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            UUID approvalId,
            UUID makerId,
            UUID checkerId,
            String source) {
        UUID txnId =
                payOut(a, amountMinor, valueDate, paymentMethodKey, externalReference, approvalId, makerId, source);
        long balance = a.balanceMinor() - amountMinor;
        int seq = a.txnCount() + 1;
        long fee = a.withdrawalFeeMinor();
        if (fee > 0) {
            UUID feeId = UUID.randomUUID();
            PostedEntry feeEntry = books.post(
                    a.branchId(),
                    valueDate,
                    a.accountNo(),
                    "Savings withdrawal fee " + a.accountNo(),
                    feeId,
                    a.id(),
                    SavingsBooks.MEMBER_SAVINGS,
                    SavingsBooks.FEE_INCOME,
                    fee);
            balance -= fee;
            seq++;
            repo.insertTxn(new Txn(
                    feeId,
                    a.branchId(),
                    a.id(),
                    seq,
                    "fee",
                    fee,
                    false,
                    a.currency(),
                    balance,
                    valueDate,
                    null,
                    null,
                    null,
                    null,
                    txnId,
                    null,
                    feeEntry.entryId(),
                    approvalId,
                    source,
                    makerId));
            repo.moved(a.id(), balance, null);
        }
        Map<String, Object> after = movement(txnId, amountMinor, valueDate, null);
        after.put("fee_minor", fee);
        after.put("approval_request_id", approvalId);
        after.put("checker_id", checkerId);
        audit.record(
                AuditLog.Entry.created("lending.savings.withdrawal_recorded", SUBJECT, a.id(), a.branchId(), after));
        if (source.equals("staff")) {
            String voucher = repo.transaction(txnId).map(Transaction::receiptNo).orElse(null);
            notices.receipt("savings.withdrawal", a, txnId, amountMinor, balance, voucher);
        }
        return txnId;
    }

    /** One withdrawal row and its journal (no fee): the withdrawal itself, or the payout of a closure. */
    private UUID payOut(
            AccountRow a,
            long amountMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            UUID approvalId,
            UUID by,
            String source) {
        String method = books.methodAccount(paymentMethodKey);
        String voucher = number("VC", "voucher", a.branchId());
        UUID txnId = UUID.randomUUID();
        PostedEntry entry = books.post(
                a.branchId(),
                valueDate,
                voucher,
                "Savings withdrawal " + a.accountNo(),
                txnId,
                a.id(),
                SavingsBooks.MEMBER_SAVINGS,
                method,
                amountMinor);
        long balance = a.balanceMinor() - amountMinor;
        repo.insertTxn(new Txn(
                txnId,
                a.branchId(),
                a.id(),
                a.txnCount() + 1,
                "withdrawal",
                amountMinor,
                false,
                a.currency(),
                balance,
                valueDate,
                paymentMethodKey,
                externalReference,
                voucher,
                null,
                null,
                null,
                entry.entryId(),
                approvalId,
                source,
                by));
        repo.moved(a.id(), balance, valueDate);
        return txnId;
    }

    // ---- Reversal ---------------------------------------------------------------------------

    /**
     * The checks of a reversal request, run again at execution: a deposit or a withdrawal, not
     * reversed before, on an account that is not closed, and for a deposit enough balance left.
     */
    Transaction checkReversible(AccountRow a, Transaction t) {
        if (a.status().equals("closed")) {
            throw closed();
        }
        if (!REVERSIBLE.contains(t.txnType())) {
            throw ApiException.rule(
                    "not_reversible", "Only a deposit or a withdrawal can be reversed; its fee is reversed with it.");
        }
        if (t.reversedByTxnId() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "already_reversed",
                    "Already reversed",
                    "This transaction has already been reversed.");
        }
        if (t.credit() && t.amountMinor() > a.balanceMinor()) {
            throw ApiException.rule(
                    "insufficient_balance",
                    "The account no longer holds the deposit: its balance is " + a.balanceMinor() + ".");
        }
        return t;
    }

    void reverse(UUID txnId, String reason, UUID approvalId, UUID makerId, UUID checkerId) {
        Transaction t = repo.transaction(txnId).orElseThrow(ApiException::notFound);
        AccountRow a = repo.lock(repo.accountOfTxn(txnId).orElseThrow()).orElseThrow(ApiException::notFound);
        checkReversible(a, t);
        LocalDate today = today();
        UUID by = checkerId != null ? checkerId : makerId;
        AccountRow now = reverseOne(a, t, reason, today, approvalId, by);
        repo.feeOf(t.id())
                .filter(f -> f.reversedByTxnId() == null)
                .ifPresent(fee -> reverseOne(now, fee, reason, today, approvalId, by));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reversed_txn_id", t.id());
        after.put("txn_type", t.txnType());
        after.put("amount_minor", t.amountMinor());
        after.put("reason", reason);
        after.put("approval_request_id", approvalId);
        audit.record(
                AuditLog.Entry.created("lending.savings.transaction_reversed", SUBJECT, a.id(), a.branchId(), after));
    }

    private AccountRow reverseOne(AccountRow a, Transaction t, String reason, LocalDate on, UUID approvalId, UUID by) {
        UUID id = UUID.randomUUID();
        PostedEntry entry = books.reverse(repo.journalOf(t.id()).orElseThrow(), on, a.accountNo(), id);
        long balance = t.credit() ? a.balanceMinor() - t.amountMinor() : a.balanceMinor() + t.amountMinor();
        repo.insertTxn(new Txn(
                id,
                a.branchId(),
                a.id(),
                a.txnCount() + 1,
                "reversal",
                t.amountMinor(),
                !t.credit(),
                a.currency(),
                balance,
                on,
                null,
                null,
                null,
                reason,
                null,
                t.id(),
                entry.entryId(),
                approvalId,
                "staff",
                by));
        repo.moved(a.id(), balance, null);
        return repo.lock(a.id()).orElseThrow();
    }

    // ---- Status: freeze, dormancy, reactivation (FR-SAV-06) -------------------------------

    void freeze(AccountRow a, String reason) {
        if (!Set.of("active", "dormant").contains(a.status())) {
            throw transition(a, "frozen");
        }
        move(a, "frozen", reason, "lending.savings_account.frozen");
    }

    void unfreeze(AccountRow a, String reason) {
        if (!a.status().equals("frozen")) {
            throw transition(a, "active");
        }
        move(a, "active", reason, "lending.savings_account.unfrozen");
    }

    void reactivate(AccountRow a, String reason) {
        if (!a.status().equals("dormant")) {
            throw transition(a, "active");
        }
        move(a, "active", reason, "lending.savings_account.reactivated");
    }

    private void move(AccountRow a, String to, String reason, String action) {
        repo.status(a.id(), to, reason, null);
        audit.record(new AuditLog.Entry(
                action,
                SUBJECT,
                a.id(),
                a.branchId(),
                Map.of("status", a.status()),
                Map.of("status", to, "reason", reason)));
    }

    private void markDormant(LocalDate on) {
        for (UUID id : repo.dueForDormancy(on)) {
            AccountRow a = repo.lock(id).orElseThrow();
            if (a.status().equals("active")) {
                repo.status(id, "dormant", "No member deposit or withdrawal for " + a.dormancyDays() + " days", null);
                audit.record(
                        new AuditLog.Entry(
                                "lending.savings_account.dormant",
                                SUBJECT,
                                id,
                                a.branchId(),
                                Map.of("status", "active"),
                                Map.of("status", "dormant", "dormancy_days", a.dormancyDays())),
                        null,
                        "system");
            }
        }
    }

    // ---- Closure (FR-SAV-07) --------------------------------------------------------------

    /** The checks of a closure request, run again at execution; returns the amount it pays out now. */
    long checkClosable(AccountRow a, String paymentMethodKey) {
        requireActive(a);
        if (a.holdMinor() > 0) {
            throw ApiException.rule(
                    "account_on_hold", "The account has a hold of " + a.holdMinor() + "; it cannot be closed.");
        }
        books.methodAccount(paymentMethodKey);
        return a.balanceMinor();
    }

    /**
     * Posts interest to date (through yesterday, dated today), pays out the whole balance with no
     * fee, and closes the account.
     */
    void close(
            UUID accountId,
            String paymentMethodKey,
            String externalReference,
            String reason,
            UUID approvalId,
            UUID makerId,
            UUID checkerId) {
        AccountRow a = repo.lock(accountId).orElseThrow(ApiException::notFound);
        checkClosable(a, paymentMethodKey);
        LocalDate today = today();
        LocalDate yesterday = today.minusDays(1);
        if (!a.openedOn().isAfter(yesterday)) {
            endOfDay(a.id(), yesterday);
            a = repo.lock(accountId).orElseThrow();
            if (!a.interestFrom().isAfter(yesterday) && !a.interestCalc().equals("none")) {
                Map<LocalDate, Long> balances = repo.dailyBalances(a.id(), a.interestFrom(), yesterday);
                long interest = Interest.forPeriod(
                        a.interestCalc(),
                        a.interestRateBp(),
                        a.minBalanceForInterestMinor(),
                        a.openedOn(),
                        a.interestFrom(),
                        yesterday,
                        balances);
                postInterest(a, a.interestFrom(), yesterday, interest, today, today);
                a = repo.lock(accountId).orElseThrow();
            }
        }
        long paid = a.balanceMinor();
        UUID by = checkerId != null ? checkerId : makerId;
        UUID txnId = null;
        if (paid > 0) {
            txnId = payOut(a, paid, today, paymentMethodKey, externalReference, approvalId, makerId, "staff");
        }
        repo.status(a.id(), "closed", reason, today);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "closed");
        after.put("paid_out_minor", paid);
        after.put("reason", reason);
        after.put("approval_request_id", approvalId);
        after.put("checker_id", by);
        audit.record(new AuditLog.Entry(
                "lending.savings_account.closed", SUBJECT, a.id(), a.branchId(), Map.of("status", a.status()), after));
        if (txnId != null) {
            String voucher = repo.transaction(txnId).map(Transaction::receiptNo).orElse(null);
            notices.receipt("savings.withdrawal", a, txnId, paid, 0, voucher);
        }
    }

    // ---- End of day and interest (FR-SAV-05) ----------------------------------------------

    /**
     * Writes the end-of-day balance of each day from the day after the last one written through
     * {@code through}, and posts interest at each product period end on the way. A period's
     * interest is computed from the end-of-day balances before it is credited; the interest is
     * dated the period end, so that day's written balance includes it. Returns the postings written.
     */
    int endOfDay(UUID accountId, LocalDate through) {
        AccountRow a = repo.lock(accountId).orElseThrow();
        if (a.status().equals("closed")) {
            return 0;
        }
        LocalDate start = a.balancesThrough() != null ? a.balancesThrough().plusDays(1) : a.openedOn();
        if (start.isAfter(through)) {
            return 0;
        }
        LocalDate runDate = today();
        Map<LocalDate, Long> closing = repo.closingBalances(a.id(), start, through);
        Map<LocalDate, Long> written = new LinkedHashMap<>();
        long carried = 0;
        int postings = 0;
        for (Map.Entry<LocalDate, Long> day : closing.entrySet()) {
            LocalDate d = day.getKey();
            long balance = day.getValue() + carried;
            if (!a.interestCalc().equals("none")
                    && Interest.isPeriodEnd(a.interestPosting(), d)
                    && !a.interestFrom().isAfter(d)
                    && !repo.hasPosting(a.id(), d)) {
                Map<LocalDate, Long> balances = repo.dailyBalances(a.id(), a.interestFrom(), d);
                balances.putAll(written);
                balances.put(d, balance);
                long interest = Interest.forPeriod(
                        a.interestCalc(),
                        a.interestRateBp(),
                        a.minBalanceForInterestMinor(),
                        a.openedOn(),
                        a.interestFrom(),
                        d,
                        balances);
                postInterest(a, a.interestFrom(), d, interest, d, runDate);
                postings++;
                carried += interest;
                balance += interest;
                a = repo.lock(accountId).orElseThrow();
            }
            written.put(d, balance);
        }
        repo.insertDailyBalances(a.id(), written);
        repo.balancesThrough(a.id(), through);
        return postings;
    }

    /**
     * Records one period's posting, idempotent on the account and period end; a period with no
     * interest writes the row without a transaction. The entry is dated {@code valueDate} when its
     * accounting period is open, else {@code fallback} (the run date), so a closed month never
     * blocks the job (ADR-032).
     */
    private void postInterest(
            AccountRow a, LocalDate from, LocalDate to, long interest, LocalDate valueDate, LocalDate fallback) {
        UUID txnId = null;
        if (interest > 0) {
            txnId = UUID.randomUUID();
            LocalDate entryDate = books.periodOpen(valueDate) ? valueDate : fallback;
            if (!entryDate.equals(valueDate)) {
                log.warn("savings interest for a closed period posted on the run date: period_end={}", to);
            }
            PostedEntry entry = books.post(
                    a.branchId(),
                    entryDate,
                    a.accountNo(),
                    "Savings interest " + from + " to " + to,
                    txnId,
                    a.id(),
                    SavingsBooks.INTEREST_EXPENSE,
                    SavingsBooks.MEMBER_SAVINGS,
                    interest);
            long balance = Math.addExact(a.balanceMinor(), interest);
            repo.insertTxn(new Txn(
                    txnId,
                    a.branchId(),
                    a.id(),
                    a.txnCount() + 1,
                    "interest",
                    interest,
                    true,
                    a.currency(),
                    balance,
                    valueDate,
                    null,
                    null,
                    null,
                    "Interest " + from + " to " + to,
                    null,
                    null,
                    entry.entryId(),
                    null,
                    "system",
                    null));
            repo.moved(a.id(), balance, null);
        }
        repo.insertPosting(a.id(), from, to, interest, txnId);
        repo.interestPostedTo(a.id(), to);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("period_start", from.toString());
        after.put("period_end", to.toString());
        after.put("interest_minor", interest);
        audit.record(
                AuditLog.Entry.created("lending.savings.interest_posted", SUBJECT, a.id(), a.branchId(), after),
                null,
                "system");
    }

    /** Interest earned since the last posting through the last written day, rounded for display. */
    long accrued(AccountRow a) {
        if (a.interestCalc().equals("none")
                || a.balancesThrough() == null
                || a.interestFrom().isAfter(a.balancesThrough())) {
            return 0;
        }
        Map<LocalDate, Long> balances = repo.dailyBalances(a.id(), a.interestFrom(), a.balancesThrough());
        if (balances.size() < a.interestFrom().until(a.balancesThrough()).getDays() + 1) {
            return 0;
        }
        return Interest.forPeriod(
                a.interestCalc(),
                a.interestRateBp(),
                a.minBalanceForInterestMinor(),
                a.openedOn(),
                a.interestFrom(),
                a.balancesThrough(),
                balances);
    }

    // ---- Shared rules -----------------------------------------------------------------------

    /**
     * A movement is dated today or earlier, not before the account opened, and after the last day
     * whose end-of-day balance is written, so the balances interest is computed on never change.
     */
    void checkValueDate(AccountRow a, LocalDate valueDate) {
        if (valueDate.isAfter(today())) {
            throw ApiException.rule("value_date_in_future", "The value date is today or earlier.");
        }
        if (valueDate.isBefore(a.openedOn())) {
            throw ApiException.rule("before_opening", "The value date is before the account was opened.");
        }
        if (a.balancesThrough() != null && !valueDate.isAfter(a.balancesThrough())) {
            throw ApiException.rule(
                    "value_date_closed",
                    "The end of day has run for " + a.balancesThrough() + "; date the movement after it.");
        }
    }

    static void requireActive(AccountRow a) {
        switch (a.status()) {
            case "active" -> {}
            case "dormant" ->
                throw ApiException.rule(
                        "account_dormant", "The account is dormant: a branch manager must reactivate it first.");
            case "frozen" -> throw ApiException.rule("account_frozen", "The account is frozen.");
            default -> throw closed();
        }
    }

    private static ApiException closed() {
        return ApiException.rule("account_closed", "The account is closed.");
    }

    private static ApiException transition(AccountRow a, String to) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "invalid_status_transition",
                "Invalid status transition",
                "A " + a.status() + " account cannot become " + to + ".");
    }

    private static Map<String, Object> movement(UUID txnId, long amountMinor, LocalDate valueDate, String receipt) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("transaction_id", txnId);
        after.put("amount_minor", amountMinor);
        after.put("value_date", valueDate.toString());
        if (receipt != null) {
            after.put("receipt_no", receipt);
        }
        return after;
    }

    private String number(String prefix, String sequence, UUID branchId) {
        String code = branches.all().stream()
                .filter(b -> b.id().equals(branchId))
                .findFirst()
                .orElseThrow()
                .code();
        return "%s-%s-%06d".formatted(prefix, code, sequences.next(sequence + ":" + code));
    }
}
