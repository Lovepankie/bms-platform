package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.ledger.LedgerPosting.ReversalRequest;
import com.rincoltech.bms.core.ledger.PaymentMethods;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.stock.RetailBooks;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcRetailBooks implements RetailBooks {

    static final String SOURCE_MODULE = "retail";

    /** ADR-105: the seeded accounts that stand for a payment method, by the method's key in {@code payment_methods}. */
    static final Map<String, String> METHOD_OF = Map.of("cash_on_hand", "cash", "bank", "bank");

    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;
    private final PaymentMethods methods;
    private final CurrentTenant tenant;

    JdbcRetailBooks(LedgerPosting ledger, LedgerAccounts accounts, PaymentMethods methods, CurrentTenant tenant) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.methods = methods;
        this.tenant = tenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PostedEntry> post(Posting p) {
        List<Leg> legs = p.legs().stream().filter(l -> l.amountMinor() != 0).toList();
        if (legs.isEmpty()) {
            return Optional.empty();
        }
        List<Line> lines = legs.stream().map(l -> resolve(l, p.branchId())).toList();
        return Optional.of(ledger.post(new EntryRequest(
                p.branchId(),
                p.date(),
                p.reference(),
                p.memo(),
                SOURCE_MODULE,
                p.sourceType(),
                p.sourceId(),
                p.idempotencyKey(),
                lines,
                p.closedBranchOk())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedEntry reverse(UUID entryId, LocalDate date, String reference, String idempotencyKey) {
        return ledger.reverse(new ReversalRequest(entryId, date, reference, "Reversal", idempotencyKey));
    }

    @Override
    @Transactional(readOnly = true)
    public String accountRef(String systemKey, UUID branchId) {
        String method = METHOD_OF.get(systemKey);
        // Retail has no switch-off: a method switched off, or mapped to an account no longer postable, keeps
        // taking retail money on its seeded account, as before the mapping existed.
        return method == null
                ? systemKey
                : methods.accountRefIfMapped(method, branchId).orElse(systemKey);
    }

    private Line resolve(Leg leg, UUID branchId) {
        if (leg.accountId() != null) {
            // A tenant-chosen account: the ledger refuses it unless it is postable and active, and
            // an account is in the tenant's one currency.
            return new Line(
                    leg.accountId(),
                    leg.debit() ? leg.amountMinor() : 0,
                    leg.debit() ? 0 : leg.amountMinor(),
                    tenant.profile().currency(),
                    leg.subledgerType(),
                    leg.subledgerId());
        }
        LedgerAccounts.Account account = accounts.byRef(accountRef(leg.systemKey(), branchId))
                .orElseThrow(() -> ApiException.rule(
                        "retail_chart_missing",
                        "The tenant has no active account with system key " + leg.systemKey() + "."));
        return new Line(
                account.id(),
                leg.debit() ? leg.amountMinor() : 0,
                leg.debit() ? 0 : leg.amountMinor(),
                account.currency(),
                leg.subledgerType(),
                leg.subledgerId());
    }
}
