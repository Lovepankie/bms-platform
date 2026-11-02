package com.rincoltech.bms.retail.sales.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The limits of the public receipt link routes (issue #760, ADR-116; review of #762 item 7): opens from one address and of
 * one link are counted and refill over time, and only one link's PDF is rendered at a time, so a shared render place is
 * always left for a seller's own receipt.
 */
class ReceiptLinkOpensTest {

    /** A clock the test moves by hand. */
    static final class Moving extends Clock {
        Instant at = Instant.parse("2026-11-02T09:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
    }

    final Moving clock = new Moving();
    final ReceiptLinks.Opens opens = new ReceiptLinks.Opens(new BusinessClock(clock));

    @Test
    void oneAddressFollowsAFewLinksAMinute() {
        for (int i = 0; i < ReceiptLinks.Opens.PER_ADDRESS; i++) {
            assertThat(opens.follow("192.0.2.10")).as("follow %d", i).isTrue();
        }
        assertThat(opens.follow("192.0.2.10")).isFalse();
        assertThat(opens.follow("192.0.2.11")).as("another address").isTrue();
        clock.at = clock.at.plus(Duration.ofMinutes(1));
        assertThat(opens.follow("192.0.2.10")).as("refilled after a minute").isTrue();
    }

    @Test
    void anIpv6ClientIsCountedByItsNetwork() {
        for (int i = 0; i < ReceiptLinks.Opens.PER_ADDRESS; i++) {
            assertThat(opens.follow("2001:db8:1:1::" + (i + 1))).isTrue();
        }
        assertThat(opens.follow("2001:db8:1:1::ffff")).isFalse();
        assertThat(opens.follow("2001:db8:1:2::1")).isTrue();
    }

    @Test
    void oneLinkOpensAFewTimesAnHourFromAnyAddress() {
        String link = ReceiptLinks.hash("abcdefghijklmnopqrstuvwxyz");
        for (int i = 0; i < ReceiptLinks.Opens.PER_LINK; i++) {
            assertThat(opens.open("192.0.2." + (i + 1), link)).as("open %d", i).isTrue();
        }
        assertThat(opens.open("192.0.2.200", link)).isFalse();
        assertThat(opens.open("192.0.2.200", ReceiptLinks.hash("bcdefghijklmnopqrstuvwxyza")))
                .as("another link")
                .isTrue();
        clock.at = clock.at.plus(Duration.ofHours(1));
        assertThat(opens.open("192.0.2.200", link)).isTrue();
    }

    @Test
    void onlyOneLinkIsRenderedAtATime() {
        assertThat(opens.startRender()).isTrue();
        assertThat(opens.startRender()).isFalse();
        opens.endRender();
        assertThat(opens.startRender()).isTrue();
        opens.endRender();
    }
}
