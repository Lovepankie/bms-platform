package com.rincoltech.bms.retail.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The retail.capabilities settings group (ADR-033): defaults, presets and refusals. */
class RetailCapabilityGroupTest {

    /** A group whose business clock stands on {@code day} (noon in Kampala), the day the credit rule is changed on. */
    static RetailCapabilityGroup groupOn(String day) {
        java.time.ZoneId zone = java.time.ZoneId.of("Africa/Kampala");
        java.time.Clock fixed = java.time.Clock.fixed(
                java.time.LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant(), java.time.ZoneOffset.UTC);
        CurrentTenant tenant =
                () -> new CurrentTenant.Profile(java.util.UUID.randomUUID(), "t", "Test Tenant", "UGX", zone);
        return new RetailCapabilityGroup(new BusinessClock(fixed), tenant);
    }

    final RetailCapabilityGroup group = groupOn("2026-10-20");

    @Test
    void absentKeysAreOffAndTheProfileIsShop() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of());

        assertThat(g.get("profile")).isEqualTo("shop");
        assertThat(RetailCapabilityGroup.CAPABILITIES)
                .allSatisfy(k -> assertThat(g.get(k)).isEqualTo(false));
        assertThat(group.enabledCapabilities(g)).isEmpty();
    }

    @Test
    void theTradeProfileSwitchesOnItsBundleAndLeavesDocumentsAndPurchasingOff() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of("profile", "trade"));

        assertThat(group.enabledCapabilities(g))
                .containsExactly(
                        "branch_roles", "customer_accounts", "price_lists", "returns", "transfer_receipt", "units");
        assertThat(g.get("documents")).isEqualTo(false);
        assertThat(g.get("purchasing")).isEqualTo(false);
    }

    @Test
    void aFlagInTheSameChangeWinsOverThePreset() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of("profile", "trade", "returns", false));

        assertThat(g.get("returns")).isEqualTo(false);
        assertThat(g.get("branch_roles")).isEqualTo(true);
    }

    @Test
    void aFlagChangeKeepsTheOthersAndTheShopProfileSwitchesEverythingOff() {
        Map<String, Object> on = group.normalise(Map.of(), Map.of("profile", "trade"));
        Map<String, Object> documents = group.normalise(on, Map.of("documents", true));

        assertThat(documents.get("documents")).isEqualTo(true);
        assertThat(documents.get("branch_roles")).isEqualTo(true);
        assertThat(group.enabledCapabilities(group.normalise(documents, Map.of("profile", "shop"))))
                .isEmpty();
    }

    @Test
    void theSlowMoverRulesDefaultToFourMonthsAndNoThresholdAndTakeWholeNumbersInRange() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of());
        assertThat(g.get("slow_mover_months")).isEqualTo(4);
        assertThat(g.get("slow_mover_min_sold")).isEqualTo(0);

        Map<String, Object> set = group.normalise(g, Map.of("slow_mover_months", 6, "slow_mover_min_sold", 12));
        assertThat(set.get("slow_mover_months")).isEqualTo(6);
        assertThat(set.get("slow_mover_min_sold")).isEqualTo(12);
        assertThat(group.normalise(set, Map.of("units", true)).get("slow_mover_months"))
                .isEqualTo(6);
        assertThat(group.enabledCapabilities(set)).isEmpty();

        for (Object bad : new Object[] {0, 25, 2.5, "4", true}) {
            assertThatThrownBy(() -> group.normalise(g, Map.of("slow_mover_months", bad)))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> group.normalise(g, Map.of("slow_mover_min_sold", -1)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> group.normalise(g, Map.of("slow_mover_min_sold", 1_000_001)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anUnknownKeyOrAWrongTypeIsRefused() {
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("wholesale", true)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("units", "yes")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("profile", "wholesale")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void creditSaleRecognitionDefaultsToOnSaleSoNothingChangesForAnyTenant() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of());

        assertThat(g.get("credit_sale_recognition")).isEqualTo("on_sale");
        assertThat(group.enabledCapabilities(g)).doesNotContain("credit_sale_recognition");
        // A group saved before the setting existed reads on_sale too.
        assertThat(group.normalise(Map.of("profile", "trade", "returns", true), Map.of())
                        .get("credit_sale_recognition"))
                .isEqualTo("on_sale");
    }

    @Test
    void creditSaleRecognitionTakesOnlyTheTwoValuesAndSurvivesProfileAndFlagChanges() {
        Map<String, Object> on = group.normalise(Map.of(), Map.of("credit_sale_recognition", "on_payment"));

        assertThat(on.get("credit_sale_recognition")).isEqualTo("on_payment");
        assertThat(group.normalise(on, Map.of("profile", "trade")).get("credit_sale_recognition"))
                .isEqualTo("on_payment");
        assertThat(group.normalise(on, Map.of("profile", "shop")).get("credit_sale_recognition"))
                .isEqualTo("on_payment");
        assertThat(group.normalise(on, Map.of("returns", true)).get("credit_sale_recognition"))
                .isEqualTo("on_payment");
        assertThat(group.normalise(on, Map.of("credit_sale_recognition", "on_sale"))
                        .get("credit_sale_recognition"))
                .isEqualTo("on_sale");
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("credit_sale_recognition", "later")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("credit_sale_recognition", true)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void switchingOnCountsFromTheNextBusinessDayAndReadingAgainChangesNothing() {
        Map<String, Object> on = group.normalise(Map.of(), Map.of("credit_sale_recognition", "on_payment"));
        assertThat(on.get("credit_recognition_effective_from")).isEqualTo("2026-10-21");
        assertThat(on).doesNotContainKey("credit_recognition_changes");
        assertThat(group.enabledCapabilities(on)).contains("credit_on_payment");
        // Reading it again changes nothing, and a profile or flag change leaves the day alone.
        assertThat(group.normalise(on, Map.of())).isEqualTo(on);
        assertThat(group.normalise(on, Map.of("profile", "trade")).get("credit_recognition_effective_from"))
                .isEqualTo("2026-10-21");
        // Asking for on_payment again, even on a later day, does not move the day.
        assertThat(groupOn("2026-10-22")
                        .normalise(on, Map.of("credit_sale_recognition", "on_payment"))
                        .get("credit_recognition_effective_from"))
                .isEqualTo("2026-10-21");
    }

    @Test
    void theBusinessDateIsReadWhereTheChangeIsMadeNotGivenByTheCaller() {
        // The group reads its own clock; a key a caller adds is refused, so the start day cannot be chosen or
        // backdated.
        Map<String, Object> onTheTenth =
                groupOn("2026-10-10").normalise(Map.of(), Map.of("credit_sale_recognition", "on_payment"));
        assertThat(onTheTenth.get("credit_recognition_effective_from")).isEqualTo("2026-10-11");
        assertThatThrownBy(() -> group.normalise(
                        Map.of(),
                        Map.of(
                                "credit_sale_recognition",
                                "on_payment",
                                "credit_recognition_effective_from",
                                "2020-01-01")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> group.normalise(
                        Map.of(),
                        Map.of("credit_sale_recognition", "on_payment", "credit_recognition_today", "2020-01-01")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void cancellingBeforeTheDayHasComeClearsItAndNothingAfterwardGoesBack() {
        Map<String, Object> on = group.normalise(Map.of(), Map.of("credit_sale_recognition", "on_payment"));
        Map<String, Object> back = group.normalise(on, Map.of("credit_sale_recognition", "on_sale"));
        assertThat(back.get("credit_sale_recognition")).isEqualTo("on_sale");
        assertThat(back).doesNotContainKey("credit_recognition_effective_from");
        assertThat(group.enabledCapabilities(back)).doesNotContain("credit_on_payment");
        // From the start day on it cannot go back, decided on the stored state and on the day the group's own clock
        // reads.
        assertThatThrownBy(() -> groupOn("2026-10-21").normalise(on, Map.of("credit_sale_recognition", "on_sale")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be switched off");
        assertThatThrownBy(() -> groupOn("2026-11-30").normalise(on, Map.of("credit_sale_recognition", "on_sale")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aStoredOnPaymentWithNoStartDayIsNoRuleAndNeverTheBeginningOfTime() {
        Map<String, Object> g = group.normalise(Map.of("credit_sale_recognition", "on_payment"), Map.of());
        assertThat(g.get("credit_sale_recognition")).isEqualTo("on_sale");
        assertThat(g).doesNotContainKey("credit_recognition_effective_from");
        assertThat(group.enabledCapabilities(g)).doesNotContain("credit_on_payment");
        // Reading it many times does not fail or change it.
        assertThat(group.normalise(g, Map.of())).isEqualTo(g);
        assertThat(group.normalise(Map.of("credit_sale_recognition", "on_payment"), Map.of()))
                .isEqualTo(g);
    }
}
