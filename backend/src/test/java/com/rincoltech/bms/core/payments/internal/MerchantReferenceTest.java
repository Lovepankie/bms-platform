package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The merchant reference (ADR-035 decision 2, spec 3.5, FR-PAY-08): the provider's alphabet and length limit hold for
 * every value, the reference round-trips to its intent id, a reference of another environment is not ours, and nothing
 * but the random id is in it.
 */
class MerchantReferenceTest {

    /** What the sandbox spike showed the provider accepts: letters, digits and - _ . : up to 50 characters. */
    static final Pattern PROVIDER_ALPHABET = Pattern.compile("^[A-Za-z0-9._:-]{1,50}$");

    @Test
    void aReferenceIs39CharactersFromTheProvidersAlphabetAndCarriesTheEnvironment() {
        UUID id = UUID.fromString("0f8e3c52-1b7a-4d3e-9a52-6c1d2e3f4a5b");
        assertThat(MerchantReference.of(id, "staging")).isEqualTo("BMS-ST-0f8e3c521b7a4d3e9a526c1d2e3f4a5b");
        assertThat(MerchantReference.of(id, "production")).startsWith("BMS-PR-");
        assertThat(MerchantReference.of(id, "")).startsWith("BMS-DV-");
        assertThat(MerchantReference.of(id, null)).startsWith("BMS-DV-");
        assertThat(MerchantReference.of(id, "staging")).hasSize(39).matches(PROVIDER_ALPHABET);
    }

    @Test
    void everyRandomIdGivesAnAcceptedReferenceThatRoundTripsAndNeverCollides() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            UUID id = UUID.randomUUID();
            String ref = MerchantReference.of(id, "production");
            assertThat(ref).hasSize(39).matches(PROVIDER_ALPHABET).matches(MerchantReference.SHAPE);
            assertThat(MerchantReference.parse(ref, "production")).contains(id);
            assertThat(seen.add(ref)).as("reference %s is new", ref).isTrue();
        }
    }

    @Test
    void theReferenceCarriesNoTenantAmountOrPersonJustTheIntentId() {
        UUID id = UUID.randomUUID();
        String ref = MerchantReference.of(id, "staging");
        assertThat(ref).isEqualTo("BMS-ST-" + id.toString().replace("-", ""));
    }

    @Test
    void aReferenceOfAnotherEnvironmentOrAnyOtherShapeIsNotOurs() {
        UUID id = UUID.randomUUID();
        String staging = MerchantReference.of(id, "staging");
        assertThat(MerchantReference.parse(staging, "production")).isEmpty();
        assertThat(MerchantReference.parse(staging, "")).isEmpty();
        for (String bad : new String[] {
            null,
            "",
            "BMS-ST-",
            "BMS-ST-ABCDEF0123456789ABCDEF0123456789",
            "BMS-ST-0f8e3c521b7a4d3e9a526c1d2e3f4a5",
            "BMS-ST-0f8e3c521b7a4d3e9a526c1d2e3f4a5b0",
            "bms-st-0f8e3c521b7a4d3e9a526c1d2e3f4a5b",
            "BMS-XX-0f8e3c521b7a4d3e9a526c1d2e3f4a5b",
            "BMS-ST-0f8e3c521b7a4d3e9a526c1d2e3f4a5b\n",
            " BMS-ST-0f8e3c521b7a4d3e9a526c1d2e3f4a5b",
            "BMS-ST-0f8e3c521b7a4d3e9a526c1d2e3f4a5g",
            "BMS-ST-0f8e3c52-1b7a-4d3e-9a52-6c1d2e3f4a5b"
        }) {
            assertThat(MerchantReference.parse(bad, "staging"))
                    .as("parse(%s)", bad)
                    .isEmpty();
        }
    }
}
