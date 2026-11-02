package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.core.tenancy.ShortLinkTarget;
import com.rincoltech.bms.core.tenancy.ShortNames;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receipt links (issue #760, ADR-116): a short link {@code rincoltech.com/<token>} on the short link host of ADR-108 that
 * opens the receipt PDF of one sale, read only and with no sign-in, for 90 days. It goes in the message a seller sends to
 * a typed WhatsApp number, since a wa.me link cannot carry a file. The token is 26 random characters of lower case
 * base32 (130 bits), longer than any new short name. A sale has one link at a time: made on the first tap of WhatsApp
 * customer and given again while it has more than {@link #REUSE_MARGIN} to run. It is found by its SHA-256 and kept only
 * sealed with the application data key, so it can be given again. On the platform host the short link finds the shop
 * with V128's {@code retail_receipt_link_shop} and goes on to the shop's own host, where the receipt is rendered under the
 * shop's row level security. A wrong or expired link shows {@link #GONE_PAGE}, which says nothing about the business or
 * the sale.
 *
 * <p>The public routes are limited here, per instance and in memory ({@link Opens}): each client address may follow and
 * open a few links a minute, each link may be opened a few times an hour, and only one link's PDF is rendered at a time,
 * so the shared receipt render places of ADR-061 always keep one for the sellers' own receipts.
 */
@Component
class ReceiptLinks implements ShortLinkTarget {

    private static final Logger log = LoggerFactory.getLogger(ReceiptLinks.class);

    static final Duration LIFETIME = Duration.ofDays(90);

    /** A sale's link is given again while it has more than this to run, so the customer has time to open it. */
    static final Duration REUSE_MARGIN = Duration.ofDays(7);

    /** Where the shop's host serves the receipt of a link. */
    static final String PATH = "/api/v1/retail/receipt-links/";

    static final String GONE_PAGE = page(
            "Receipt link expired", "This receipt link has expired", "Ask the shop to send you the receipt again.");

    static final String BUSY_PAGE = page(
            "Receipt busy",
            "This receipt is busy",
            "Too many people opened receipts just now. Wait a minute and open the link again.");

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final Pattern TOKEN = Pattern.compile("[a-z2-7]{26}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final ShortNames names;
    private final DataKeys keys;
    private final Opens opens;

    ReceiptLinks(JdbcClient jdbc, BusinessClock clock, ShortNames names, DataKeys keys) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.names = names;
        this.keys = keys;
        this.opens = new Opens(clock);
    }

    /** The limits of the public routes; a method, since this bean is reached through its transaction proxy. */
    Opens opens() {
        return opens;
    }

    /** A sale's link: {@code link} as a person reads it, {@code url} with https:// for the message. */
    record Minted(String url, String link, Instant expiresAt, boolean created) {}

    /** A live link of the bound business: the sale and who made the link. */
    record Live(UUID saleId, UUID createdBy) {}

    private record Kept(byte[] sealed, Instant expiresAt) {}

    /**
     * The sale's link, in the caller's write transaction with the business bound: the one it has while that has more than
     * {@link #REUSE_MARGIN} to run, else a new one made by {@code by}. Two taps at once give the same link.
     */
    Minted forSale(UUID saleId, UUID by) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('bms.receipt-link:' || ?))")
                .param(saleId.toString())
                .query()
                .listOfRows();
        Instant now = clock.now();
        Optional<Kept> kept = jdbc.sql("""
                        SELECT token_sealed, expires_at FROM retail_receipt_links
                         WHERE sale_id = ? AND expires_at > ?
                         ORDER BY expires_at DESC LIMIT 1
                        """)
                .params(saleId, Timestamp.from(now.plus(REUSE_MARGIN)))
                .query((rs, n) -> new Kept(
                        rs.getBytes("token_sealed"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
        Optional<String> again = kept.flatMap(k -> opened(k.sealed()));
        if (again.isPresent()) {
            return minted(again.get(), kept.get().expiresAt(), false);
        }
        StringBuilder token = new StringBuilder(26);
        for (int i = 0; i < 26; i++) {
            token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        Instant expires = now.plus(LIFETIME);
        jdbc.sql("""
                        INSERT INTO retail_receipt_links (id, tenant_id, sale_id, token_hash, token_sealed, created_by,
                                                          created_at, expires_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        saleId,
                        hash(token.toString()),
                        keys.seal(token.toString().getBytes(StandardCharsets.UTF_8)),
                        by,
                        Timestamp.from(now),
                        Timestamp.from(expires))
                .update();
        return minted(token.toString(), expires, true);
    }

    /** The sealed token, or empty when its key is no longer configured (a new link is made then). */
    private Optional<String> opened(byte[] sealed) {
        try {
            return Optional.of(new String(keys.open(sealed), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.warn("A receipt link could not be opened with the data keys configured; a new one is made");
            return Optional.empty();
        }
    }

    private Minted minted(String token, Instant expires, boolean created) {
        String link = names.link(token);
        return new Minted("https://" + link, link, expires, created);
    }

    /** The live link of the bound business with this token, in the caller's transaction; empty when wrong or expired. */
    Optional<Live> live(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT sale_id, created_by FROM retail_receipt_links WHERE token_hash = ? AND expires_at > ?")
                .params(hash(token), Timestamp.from(clock.now()))
                .query((rs, n) -> new Live(rs.getObject("sale_id", UUID.class), rs.getObject("created_by", UUID.class)))
                .optional();
    }

    /** On the platform host: a name shaped like a token is a receipt link, live (to the shop's host) or gone. */
    @Override
    @Transactional(readOnly = true)
    public Optional<Answer> follow(String name) {
        String token = ShortNames.normalise(name);
        if (!TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        if (!opens.follow(RequestContext.clientIp())) {
            return Optional.of(Answer.busy(BUSY_PAGE));
        }
        Optional<String> shop = jdbc.sql("SELECT retail_receipt_link_shop(?, ?)")
                .params(hash(token), Timestamp.from(clock.now()))
                .query(String.class)
                .optional();
        return Optional.of(shop.map(slug -> Answer.to(slug, PATH + token)).orElse(Answer.gone(GONE_PAGE)));
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String page(String title, String heading, String text) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex">
                <title>%s</title>
                <style>
                body { margin: 0; padding: 32px 16px; font: 16px/1.5 system-ui, sans-serif; color: #1f2328; background: #ffffff; }
                main { max-width: 32rem; margin: 0 auto; }
                h1 { font-size: 1.4rem; }
                </style>
                </head>
                <body>
                <main>
                <h1>%s</h1>
                <p>%s</p>
                </main>
                </body>
                </html>
                """.formatted(title, heading, text);
    }

    /**
     * The limits of the public routes, per instance and in memory, bounded to {@link #MAX_KEYS} buckets with the least
     * recently used dropped first: {@link #PER_ADDRESS} follows and opens from one client address (an IPv6 client by its
     * /64), {@link #PER_LINK} opens of one link, and one link render at a time. A flood can evict a bucket; the render
     * place is the bound that must hold, so a seller's own receipt always finds a shared render place.
     */
    static final class Opens {

        /** 30 a minute from one address, refilled evenly. */
        static final int PER_ADDRESS = 30;

        static final Duration ADDRESS_WINDOW = Duration.ofMinutes(1);

        /** 20 opens of one link an hour, refilled evenly: a customer opens their receipt a few times. */
        static final int PER_LINK = 20;

        static final Duration LINK_WINDOW = Duration.ofHours(1);

        static final int MAX_KEYS = 10_000;

        private final BusinessClock clock;
        private final Semaphore rendering = new Semaphore(1);
        private final Map<String, double[]> buckets = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                return size() > MAX_KEYS;
            }
        };

        Opens(BusinessClock clock) {
            this.clock = clock;
        }

        /** A short link follow on the platform host from this address. */
        boolean follow(String address) {
            return take("a:" + addressKey(address), PER_ADDRESS, ADDRESS_WINDOW);
        }

        /** An open of the link with this token hash on the shop's host from this address. */
        boolean open(String address, String tokenHash) {
            return take("a:" + addressKey(address), PER_ADDRESS, ADDRESS_WINDOW)
                    && take("l:" + tokenHash, PER_LINK, LINK_WINDOW);
        }

        /** The one place a link's PDF is rendered in, or false at once when another link holds it. */
        boolean startRender() {
            return rendering.tryAcquire();
        }

        void endRender() {
            rendering.release();
        }

        synchronized boolean take(String key, int capacity, Duration per) {
            double now = clock.now().toEpochMilli();
            double[] b = buckets.computeIfAbsent(key, k -> new double[] {capacity, now});
            b[0] = Math.min(capacity, b[0] + Math.max(0, now - b[1]) * capacity / per.toMillis());
            b[1] = now;
            if (b[0] < 1) {
                return false;
            }
            b[0] -= 1;
            return true;
        }

        static String addressKey(String address) {
            if (address == null || address.isBlank()) {
                return "unknown";
            }
            try {
                java.net.InetAddress parsed = java.net.InetAddress.ofLiteral(address.trim());
                if (parsed instanceof java.net.Inet6Address) {
                    return HexFormat.of().formatHex(parsed.getAddress(), 0, 8) + "::/64";
                }
                return parsed.getHostAddress();
            } catch (IllegalArgumentException e) {
                return "raw:" + address;
            }
        }
    }
}
