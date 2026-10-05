package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.ledger.LedgerPosting.ReversalRequest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.stock.RetailBooks;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcRetailBooks implements RetailBooks {

    static final String SOURCE_MODULE = "retail";

    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;

    JdbcRetailBooks(LedgerPosting ledger, LedgerAccounts accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PostedEntry> post(Posting p) {
        List<Leg> legs = p.legs().stream().filter(l -> l.amountMinor() != 0).toList();
        if (legs.isEmpty()) {
            return Optional.empty();
        }
        List<Line> lines = legs.stream().map(this::resolve).toList();
        return Optional.of(ledger.post(new EntryRequest(
                p.branchId(),
                p.date(),
                p.reference(),
                p.memo(),
                SOURCE_MODULE,
                p.sourceType(),
                p.sourceId(),
                p.idempotencyKey(),
                lines)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedEntry reverse(UUID entryId, LocalDate date, String reference, String idempotencyKey) {
        return ledger.reverse(new ReversalRequest(entryId, date, reference, "Reversal", idempotencyKey));
    }

    private Line resolve(Leg leg) {
        LedgerAccounts.Account account = accounts.bySystemKey(leg.systemKey())
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
