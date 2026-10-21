package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.Header;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOff;
import com.rincoltech.bms.retail.sales.internal.WriteOffsRepository.Row;
import com.rincoltech.bms.retail.sales.internal.WriteOffsRepository.Standing;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The write-off of the unpaid part of a credit sale whose buyer never pays (ADR-043, issue #340): the checks, the entry and
 * the record, shared by the route and by the maker-checker action so that both do exactly the same thing. It never deletes
 * anything. The entry clears trade debtors for the unpaid amount {@code U}. The loss the profit report shows is {@code L}:
 * under {@code credit_sale_recognition} {@code on_payment} the unpaid share of the cost of goods (the profit of the unpaid part
 * was never counted), under {@code on_sale} the whole {@code U} (the sale and its profit were counted when it was made). The
 * entry debits bad debt loss for {@code L}, debits sales revenue for {@code U - L} (credits it for {@code L - U} when the sale
 * was sold below cost) and credits trade debtors for {@code U}, so it balances and the accounts keep balancing. The sale's paid
 * amount rises by {@code U}, the way the account credit of a return does, so every reader of total less paid sees a sale that
 * owes nothing; the record says why. Locks the sale first.
 */
@Service
class WriteOffsServicer {

    /** Its own permission, administrators only: a debt written off cannot be taken back in this release (ADR-043). */
    static final String PERMISSION = "retail.credit.write_off";

    static final int MIN_REASON = 5;
    private static final String SALE = "retail.sale";

    private final SalesRepository sales;
    private final WriteOffsRepository repo;
    private final RetailBooks books;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    WriteOffsServicer(
            SalesRepository sales,
            WriteOffsRepository repo,
            RetailBooks books,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.sales = sales;
        this.repo = repo;
        this.books = books;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    /** What a write-off would do to a sale as it is now. */
    record Plan(
            Sale sale,
            long unpaidMinor,
            long lossMinor,
            long costShareMinor,
            String recognition,
            long itemsUnpaidMinor,
            long feeMinor,
            UUID feeOrderId) {}

    Plan plan(UUID saleId) {
        Principal principal = CurrentPrincipal.require();
        Header h = sales.find(saleId, true)
                .filter(x -> principal.may(PERMISSION, x.sale().branchId()))
                .orElseThrow(ApiException::notFound);
        Sale s = h.sale();
        if (s.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "sale_voided",
                    "Sale voided",
                    "This sale has been voided, so nothing is owed.");
        }
        if (!s.paymentMethod().equals("credit")) {
            throw ApiException.rule("sale_not_credit", "Only a sale on credit can be written off.");
        }
        if (s.historical()) {
            throw ApiException.rule(
                    "sale_historical", "An imported sale was settled outside the system and cannot be written off.");
        }
        if (s.writeOff() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "already_written_off",
                    "Already written off",
                    "The unpaid part of this sale has been written off already.");
        }
        long itemsUnpaid = Math.subtractExact(s.totalMinor(), s.paidMinor());
        // Issue #385: a delivery fee of a customer order left on the account and not yet paid is owed with the sale and
        // written off with it; its income was taken at the delivery, so all of it is a loss.
        SalesRepository.OrderFee order = sales.orderFee(saleId).orElse(null);
        long fee = order != null && "account".equals(order.feeMethod()) && !order.feePaidLater() ? order.feeMinor() : 0;
        long unpaid = Math.addExact(itemsUnpaid, fee);
        if (unpaid <= 0) {
            throw ApiException.rule("nothing_owing", "This sale has nothing left to pay.");
        }
        // A sale is written off the way it was counted: by its counting day (the later of its date and the day it was
        // entered)
        // against the start day of the rule (ADR-043).
        String recognition = capabilities.onPaymentPeriods().counts(s.saleDate(), s.createdAt())
                ? RetailCapabilities.ON_PAYMENT
                : RetailCapabilities.ON_SALE;
        long costShare = unrecognisedCost(s);
        long itemsLoss = recognition.equals(RetailCapabilities.ON_PAYMENT) ? costShare : itemsUnpaid;
        return new Plan(
                s,
                unpaid,
                Math.addExact(itemsLoss, fee),
                costShare,
                recognition,
                itemsUnpaid,
                fee,
                fee > 0 ? order.orderId() : null);
    }

    /**
     * The cost of the goods that no payment has carried into the profit yet: what the sale still holds after its returns, less the
     * share the money kept has recognised. It is exactly {@code CreditRecognition.state} of the reports (the cost share is
     * {@code revenue x remaining cost / net}, rounded half up once), so the cost the reports recognised plus this loss is the
     * sale's cost to the unit, with or without returns. With no return it is the unpaid share of the cost of goods.
     */
    private long unrecognisedCost(Sale s) {
        Standing h = repo.standing(s.id());
        long net = Math.subtractExact(s.totalMinor(), h.returned());
        long remainingCost = Math.max(0, Math.subtractExact(s.costTotalMinor(), h.returnedCost()));
        long kept = Math.subtractExact(h.paid(), h.refunded());
        long revenue = Math.max(0, Math.min(net, kept));
        long recognised = net <= 0 || revenue == 0
                ? 0
                : BigDecimal.valueOf(revenue)
                        .multiply(BigDecimal.valueOf(remainingCost))
                        .divide(BigDecimal.valueOf(net), 0, RoundingMode.HALF_UP)
                        .longValueExact();
        return Math.max(0, remainingCost - recognised);
    }

    /** A write-off done without a second person: why ({@code only_one_active_administrator} or {@code below_threshold}) and how many were told. */
    record Alone(String why, int notified) {}

    /** Posts the entry, records the write-off and clears the debt on the sale. */
    WriteOff record(Plan plan, String reason, UUID makerId, UUID approvalId, UUID checkerId, Alone alone) {
        Sale s = plan.sale();
        UUID id = UUID.randomUUID();
        LocalDate today = clock.today(tenant.profile().timezone());
        long unpaid = plan.unpaidMinor();
        long loss = plan.lossMinor();
        // The fee is all loss; only the items' part moves sales revenue (the profit a sale counted when paid never
        // took).
        long itemsUnpaid = plan.itemsUnpaidMinor();
        long itemsLoss = loss - plan.feeMinor();
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit("bad_debt_loss", loss));
        if (itemsUnpaid > itemsLoss) {
            legs.add(Leg.debit("sales_revenue", itemsUnpaid - itemsLoss));
        } else if (itemsLoss > itemsUnpaid) {
            legs.add(Leg.credit("sales_revenue", itemsLoss - itemsUnpaid));
        }
        legs.add(Leg.credit("trade_debtors", unpaid).withSubledger(SALE, s.id()));
        PostedEntry entry = books.post(new Posting(
                        s.branchId(),
                        today,
                        s.saleNo(),
                        "Write-off of " + s.saleNo(),
                        "retail.sale_writeoff",
                        id,
                        "retail.sale_writeoff:" + id,
                        legs))
                .orElseThrow();
        Row row = new Row(
                id,
                s.id(),
                s.saleNo(),
                s.branchId(),
                today,
                s.currency(),
                unpaid,
                loss,
                plan.costShareMinor(),
                plan.recognition(),
                reason.trim(),
                entry.entryId(),
                approvalId,
                checkerId,
                makerId,
                clock.now(),
                plan.feeMinor());
        repo.insert(row);
        if (itemsUnpaid > 0) {
            sales.addPaid(s.id(), itemsUnpaid);
        }
        if (plan.feeOrderId() != null) {
            // The fee on the account is settled by this entry: no longer owed, no longer holding the branch open.
            if (sales.markOrderFeeWrittenOff(plan.feeOrderId(), makerId, today, entry.entryId()) != 1) {
                // The fee was paid or settled by someone else after the plan was made: nothing is written off twice.
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "write_off_changed",
                        "The debt changed",
                        "The delivery fee of this sale was paid or settled meanwhile. Open the sale again and check what is owed.");
            }
            sales.orderEvent(
                    plan.feeOrderId(),
                    "fee_written_off",
                    "Delivery fee of " + plan.feeMinor() + " written off with sale " + s.saleNo() + ".",
                    makerId);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("write_off_id", id);
        after.put("unpaid_minor", unpaid);
        if (plan.feeMinor() > 0) {
            after.put("delivery_fee_minor", plan.feeMinor());
        }
        after.put("reason", row.reason());
        after.put("approved", approvalId != null);
        if (alone != null) {
            // Done without a second person: the audit row says why, and how many administrators were told (ADR-043; the
            // default
            // checker rule awaits Hillary's confirmation).
            after.put("no_second_person", alone.why());
            after.put("notified_admins", alone.notified());
        }
        audit.record(new AuditLog.Entry(
                "retail.sale.written_off", SALE, s.id(), s.branchId(), Map.of("paid_minor", s.paidMinor()), after));
        return view(row);
    }

    /** The loss figure is cost derived, so it travels only with profit read in the sale's branch (ADR-017). */
    static WriteOff view(Row w) {
        boolean cost = CurrentPrincipal.require().may("retail.profit.read", w.branchId());
        return new WriteOff(
                w.id(),
                w.saleId(),
                w.saleNo(),
                w.branchId(),
                w.writtenOffOn(),
                w.currency(),
                w.unpaidMinor(),
                cost ? w.lossMinor() : null,
                w.recognition(),
                w.reason(),
                w.createdAt(),
                w.createdBy(),
                w.approvalId());
    }
}
