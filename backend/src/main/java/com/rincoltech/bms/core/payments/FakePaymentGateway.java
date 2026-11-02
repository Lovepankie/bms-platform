package com.rincoltech.bms.core.payments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process gateway for tests and development: it keeps orders in memory, behaves as the sandbox did where the spike
 * observed it (a repeated reference returns the original order and ignores the new body; an unpaid order reads
 * {@code PENDING}; an unknown id is an anomaly), records every call, and can be told to fail, to apply an order but lose
 * the answer, to change an order's state, or to run a hook just before it answers a status query (a barrier for a race
 * test). It is refused in production by {@code PesapalSettings}, because an answer it invented must never stand for a
 * payer's money. Nothing it holds is real: order ids are random, the redirect is on a reserved test domain.
 */
public final class FakePaymentGateway implements PaymentGateway {

    /** One order as the fake keeps it. */
    public static final class FakeOrder {
        public final String providerReference;
        public final String merchantReference;
        public final long orderedMinor;
        public final String currency;
        volatile Status status = Status.PENDING;
        volatile long reportedMinor;
        volatile String reportedCurrency;
        volatile String method = "";
        volatile String confirmation = "";
        volatile boolean cancelled;
        volatile String reportedReference;

        FakeOrder(String providerReference, String merchantReference, long orderedMinor, String currency) {
            this.providerReference = providerReference;
            this.merchantReference = merchantReference;
            this.orderedMinor = orderedMinor;
            this.currency = currency;
            this.reportedMinor = orderedMinor;
            this.reportedCurrency = currency;
        }

        public boolean cancelled() {
            return cancelled;
        }
    }

    private final Map<String, FakeOrder> byProviderReference = new LinkedHashMap<>();
    private final Map<String, FakeOrder> byMerchantReference = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final AtomicInteger queries = new AtomicInteger();
    private volatile boolean available = true;
    private volatile GatewayException createFailure;
    private volatile int loseCreateAnswers;
    private volatile GatewayException queryFailure;
    private volatile Runnable beforeQuery = () -> {};
    private volatile Status forcedStatus;
    private volatile Runnable afterCancel = () -> {};
    private final List<Account> accountsUsed = new ArrayList<>();
    private volatile GatewayException registerFailure;
    private final Map<Account, String> registeredUrls = new LinkedHashMap<>();

    @Override
    public String providerKey() {
        return "pesapal";
    }

    @Override
    public boolean available(Account account) {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public synchronized OrderResult createOrder(Account account, OrderRequest request) throws GatewayException {
        calls.add("create:" + request.merchantReference());
        accountsUsed.add(account);
        if (createFailure != null) {
            throw createFailure;
        }
        FakeOrder order = byMerchantReference.get(request.merchantReference());
        if (order == null) {
            String id = UUID.randomUUID().toString();
            order = new FakeOrder(id, request.merchantReference(), request.amountMinor(), request.currency());
            byProviderReference.put(id, order);
            byMerchantReference.put(request.merchantReference(), order);
        }
        if (loseCreateAnswers > 0) {
            loseCreateAnswers--;
            throw new GatewayException(GatewayException.Kind.TRANSIENT, 0, null);
        }
        return new OrderResult(
                order.providerReference,
                order.merchantReference,
                "https://pay.fake.example.test/pay?OrderTrackingId=" + order.providerReference);
    }

    @Override
    public StatusResult queryStatus(Account account, String providerReference) throws GatewayException {
        beforeQuery.run();
        queries.incrementAndGet();
        synchronized (this) {
            calls.add("status:" + providerReference);
            if (queryFailure != null) {
                throw queryFailure;
            }
            FakeOrder order = byProviderReference.get(providerReference);
            if (order == null) {
                return new StatusResult(
                        Status.ANOMALY, providerReference, null, null, null, null, null, "invalid_order_tracking_id");
            }
            Status status = forcedStatus != null ? forcedStatus : order.status;
            boolean paidShape = status == Status.COMPLETED || status == Status.REVERSED;
            return new StatusResult(
                    status,
                    order.providerReference,
                    order.reportedReference != null ? order.reportedReference : order.merchantReference,
                    paidShape ? order.reportedMinor : order.orderedMinor,
                    paidShape ? order.reportedCurrency : order.currency,
                    paidShape ? order.method : "",
                    paidShape ? order.confirmation : "",
                    null);
        }
    }

    @Override
    public synchronized void cancelOrder(Account account, String providerReference) throws GatewayException {
        calls.add("cancel:" + providerReference);
        FakeOrder order = byProviderReference.get(providerReference);
        if (order == null || order.status != Status.PENDING) {
            throw new GatewayException(GatewayException.Kind.REFUSED, 200, "cancel_refused");
        }
        order.cancelled = true;
        afterCancel.run();
    }

    @Override
    public synchronized void checkCredentials(Account account) throws GatewayException {
        calls.add("check");
        accountsUsed.add(account);
        if (registerFailure != null) {
            throw registerFailure;
        }
    }

    @Override
    public synchronized IpnRegistration registerIpn(Account account, String url, IpnType type) throws GatewayException {
        calls.add("register:" + type);
        accountsUsed.add(account);
        if (registerFailure != null) {
            throw registerFailure;
        }
        registeredUrls.put(account, url);
        return new IpnRegistration("aaaaaaaa-0000-4000-8000-0000000000f0", url, type);
    }

    // ---- test controls ------------------------------------------------------------------------

    /** The order made for a merchant reference. */
    public synchronized FakeOrder orderFor(String merchantReference) {
        return byMerchantReference.get(merchantReference);
    }

    /** The payer completed the order: the status answers COMPLETED with this amount and currency. */
    public synchronized void complete(
            String providerReference, long paidMinor, String paidCurrency, String confirmationCode) {
        FakeOrder o = byProviderReference.get(providerReference);
        o.reportedMinor = paidMinor;
        o.reportedCurrency = paidCurrency;
        o.method = "MTN";
        o.confirmation = confirmationCode;
        o.status = Status.COMPLETED;
    }

    /** As {@link #complete}, reported as paid by this method (a card, say) instead of the default wallet. */
    public synchronized void complete(
            String providerReference, long paidMinor, String paidCurrency, String confirmationCode, String method) {
        complete(providerReference, paidMinor, paidCurrency, confirmationCode);
        byProviderReference.get(providerReference).method = method;
    }

    /** The status answer names this merchant reference instead of the order's own (a confused or hostile provider). */
    public synchronized void reportReference(String providerReference, String merchantReference) {
        byProviderReference.get(providerReference).reportedReference = merchantReference;
    }

    public synchronized void fail(String providerReference) {
        byProviderReference.get(providerReference).status = Status.FAILED;
    }

    public synchronized void reverse(String providerReference) {
        byProviderReference.get(providerReference).status = Status.REVERSED;
    }

    /** Every status answer is this, whatever the order (a hostile or confused provider). */
    public void forceStatus(Status status) {
        this.forcedStatus = status;
    }

    /** The next registrations (Test connection) fail like this, until cleared with null. */
    public void failRegister(GatewayException failure) {
        this.registerFailure = failure;
    }

    /** Every account an order or a registration was made for, in order: a test asserts which merchant was used. */
    public synchronized List<Account> accountsUsed() {
        return List.copyOf(accountsUsed);
    }

    /** The notification url last registered for an account (it ends in the account's path token), or null. */
    public synchronized String registeredUrl(Account account) {
        return registeredUrls.get(account);
    }

    public void failCreate(GatewayException failure) {
        this.createFailure = failure;
    }

    /** The next {@code n} order creations are applied at the provider and then answer a timeout. */
    public void loseNextCreateAnswers(int n) {
        this.loseCreateAnswers = n;
    }

    public void failQuery(GatewayException failure) {
        this.queryFailure = failure;
    }

    /** Runs just before each status query answers: a latch for a race, a clock move for a slow provider. */
    public void beforeQuery(Runnable hook) {
        this.beforeQuery = hook == null ? () -> {} : hook;
    }

    /** Runs just after a cancel was accepted: a payment that lands in the gap between the provider's answer and ours. */
    public void afterCancel(Runnable hook) {
        this.afterCancel = hook == null ? () -> {} : hook;
    }

    public int queryCount() {
        return queries.get();
    }

    public synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    public synchronized long creates() {
        return calls.stream().filter(c -> c.startsWith("create:")).count();
    }

    /** Back to a clean provider between tests. */
    public synchronized void reset() {
        byProviderReference.clear();
        byMerchantReference.clear();
        calls.clear();
        accountsUsed.clear();
        registeredUrls.clear();
        registerFailure = null;
        queries.set(0);
        createFailure = null;
        loseCreateAnswers = 0;
        queryFailure = null;
        beforeQuery = () -> {};
        forcedStatus = null;
        afterCancel = () -> {};
        available = true;
    }
}
