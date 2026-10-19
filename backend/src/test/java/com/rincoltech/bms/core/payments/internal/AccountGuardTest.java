package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.core.identity.SealedSecrets;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.payments.PaymentGateway.Account;
import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The own-account rule in code (ADR-035 amendment of 2026-10-18, issue #233): money owed to a tenant lands only in that
 * tenant's own merchant account. A tenant-scope payment can never be given the platform account, a tenant account can
 * never be read for another tenant, and with no account of its own a tenant gets nothing at all, not the platform's pair.
 * Every name and credential below is invented.
 */
class AccountGuardTest {

    static final UUID LENDER = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    static final BusinessClock CLOCK =
            new BusinessClock(Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC));
    static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    /** A reversible stand-in for the data key: it prefixes the key id, which is all the rotation logic looks at. */
    static final class FakeKeys implements DataKeys {
        final String current;
        final List<String> retired;

        FakeKeys(String current, String... retired) {
            this.current = current;
            this.retired = List.of(retired);
        }

        @Override
        public byte[] seal(byte[] plaintext) {
            return (current + "|" + new String(plaintext, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] open(byte[] box) {
            String s = new String(box, StandardCharsets.UTF_8);
            String id = s.substring(0, s.indexOf('|'));
            if (!id.equals(current) && !retired.contains(id)) {
                throw new IllegalStateException("ciphertext was sealed with another data key");
            }
            return s.substring(s.indexOf('|') + 1).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String currentKeyId() {
            return current;
        }

        @Override
        public java.util.List<String> blindIndexes(String purpose, String value) {
            java.util.List<String> all = new java.util.ArrayList<>(List.of(blindIndex(purpose, value)));
            retired.forEach(id -> all.add((purpose + "|" + id + "|" + value).replaceAll("[^a-z0-9]", "0")));
            return all;
        }

        @Override
        public String blindIndex(String purpose, String value) {
            return (purpose + "|" + current + "|" + value).replaceAll("[^a-z0-9]", "0");
        }

        @Override
        public String keyIdOf(byte[] box) {
            String s = new String(box, StandardCharsets.UTF_8);
            return s.substring(0, s.indexOf('|'));
        }
    }

    static PesapalSettings settingsWithPlatformPair() {
        return PesapalSettings.resolve("staging", PesapalSettingsTest.props("pesapal", null));
    }

    static PlatformTransactionManager transactions() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new SimpleTransactionStatus());
        return tm;
    }

    static CurrentTenant boundTo(UUID id) {
        CurrentTenant t = mock(CurrentTenant.class);
        when(t.profile())
                .thenReturn(new CurrentTenant.Profile(id, "lender", "Lender", "UGX", ZoneId.of("Africa/Kampala")));
        return t;
    }

    static AccountStore.Account row(DataKeys keys, String status, String key, String secret) {
        return new AccountStore.Account(
                UUID.randomUUID(),
                LENDER,
                UUID.randomUUID(),
                Instant.parse("2026-10-18T10:00:00Z"),
                keys.seal(key.getBytes(StandardCharsets.UTF_8)),
                keys.seal(secret.getBytes(StandardCharsets.UTF_8)),
                keys.blindIndex("payment-account-key", key),
                key.substring(key.length() - 4),
                keys.currentKeyId(),
                status,
                "verified".equals(status) ? "aaaaaaaa-0000-4000-8000-000000000009" : null,
                null,
                null,
                null);
    }

    // ---- the account itself ----------------------------------------------------------------------------------

    @Test
    void aTenantAccountMustNameATenantAndThePlatformAccountMustNameNone() {
        assertThatThrownBy(() -> new Account(Scope.TENANT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(Scope.PLATFORM, LENDER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(null, LENDER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.tenant(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Account.tenant(LENDER)).isNotEqualTo(Account.platform()).isNotEqualTo(Account.tenant(OTHER));
    }

    @Test
    void aTenantScopeIntentNeverGetsThePlatformAccountAndAPlatformScopeIntentNeedsAPlatformPurpose() {
        assertThat(Account.forIntent("tenant", LENDER, "lending.loan_repayment"))
                .isEqualTo(Account.tenant(LENDER));
        // Whatever purpose a tenant-scope row carries, even a platform-looking one, it is the tenant's own account.
        assertThat(Account.forIntent("tenant", LENDER, "platform.subscription")).isEqualTo(Account.tenant(LENDER));
        for (String lenderPurpose :
                Arrays.asList("lending.loan_repayment", "lending.savings_deposit", "test.payment", null)) {
            assertThatThrownBy(() -> Account.forIntent("platform", LENDER, lenderPurpose))
                    .as("%s", lenderPurpose)
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(Account.forIntent("platform", LENDER, "platform.subscription"))
                .isEqualTo(Account.platform());
        assertThatThrownBy(() -> Account.forIntent("somewhere", LENDER, "x.y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the credentials ---------------------------------------------------------------------------------------

    @Test
    void aTenantAccountResolvesToThatTenantsOwnDecryptedRowAndToNothingElse() {
        DataKeys keys = new FakeKeys("k1");
        AccountStore store = mock(AccountStore.class);
        when(store.find()).thenReturn(Optional.of(row(keys, "verified", "lender-key-000001", "lender-secret-000001")));
        AccountCredentials credentials =
                new AccountCredentials(settingsWithPlatformPair(), store, keys, boundTo(LENDER), CLOCK, transactions());

        PesapalSettings.Pair pair = credentials.find(Account.tenant(LENDER)).orElseThrow();
        assertThat(pair.key()).isEqualTo("lender-key-000001");
        assertThat(pair.secret()).isEqualTo("lender-secret-000001");
        assertThat(pair.ipnId()).isEqualTo("aaaaaaaa-0000-4000-8000-000000000009");
        // Never the platform's pair.
        assertThat(pair.key()).isNotEqualTo("invented-platform-key");
        // Nothing prints a credential.
        assertThat(pair.toString()).doesNotContain("lender-secret-000001").doesNotContain("lender-key-000001");
    }

    @Test
    void anotherTenantsAccountIsNeverReadFromThisTenantsSession() {
        DataKeys keys = new FakeKeys("k1");
        AccountStore store = mock(AccountStore.class);
        when(store.find()).thenReturn(Optional.of(row(keys, "verified", "lender-key-000001", "lender-secret-000001")));
        AccountCredentials credentials =
                new AccountCredentials(settingsWithPlatformPair(), store, keys, boundTo(OTHER), CLOCK, transactions());
        assertThat(credentials.find(Account.tenant(LENDER))).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void aTenantWithNoAccountOfItsOwnGetsNothingNotThePlatformsPair() {
        DataKeys keys = new FakeKeys("k1");
        AccountStore store = mock(AccountStore.class);
        when(store.find()).thenReturn(Optional.empty());
        PesapalSettings settings = settingsWithPlatformPair();
        assertThat(settings.platformPair().usable()).isTrue();
        AccountCredentials credentials =
                new AccountCredentials(settings, store, keys, boundTo(LENDER), CLOCK, transactions());
        assertThat(credentials.find(Account.tenant(LENDER))).isEmpty();
        // The platform account is the platform pair, for the platform account only.
        assertThat(credentials.find(Account.platform())).contains(settings.platformPair());
    }

    @Test
    void anUntestedOrFailedAccountCanBeTestedButCarriesNoRegistrationSoItCannotMakeAnOrder() {
        DataKeys keys = new FakeKeys("k1");
        AccountStore store = mock(AccountStore.class);
        for (String status : List.of("saved", "failed")) {
            when(store.find()).thenReturn(Optional.of(row(keys, status, "lender-key-000001", "lender-secret-000001")));
            AccountCredentials credentials = new AccountCredentials(
                    settingsWithPlatformPair(), store, keys, boundTo(LENDER), CLOCK, transactions());
            PesapalSettings.Pair pair = credentials.find(Account.tenant(LENDER)).orElseThrow();
            assertThat(pair.usable()).as(status).isTrue();
            assertThat(pair.ipnId()).as(status).isNull();
        }
    }

    @Test
    void aBoxThatCannotBeOpenedGivesNoCredentialsRatherThanAnError() {
        AccountStore store = mock(AccountStore.class);
        DataKeys sealedWith = new FakeKeys("dropped-key");
        when(store.find())
                .thenReturn(Optional.of(row(sealedWith, "verified", "lender-key-000001", "lender-secret-000001")));
        AccountCredentials credentials = new AccountCredentials(
                settingsWithPlatformPair(), store, new FakeKeys("k2"), boundTo(LENDER), CLOCK, transactions());
        assertThat(credentials.find(Account.tenant(LENDER))).isEmpty();
    }

    // ---- key rotation ------------------------------------------------------------------------------------------

    @Test
    void rotationOpensWithTheRetiredKeySealsWithTheCurrentOneAndKeepsTheCleartextOutOfTheStore() {
        DataKeys old = new FakeKeys("k1");
        DataKeys rotated = new FakeKeys("k2", "k1");
        AccountStore store = mock(AccountStore.class);
        AccountStore.Account before = row(old, "verified", "lender-key-000001", "lender-secret-000001");
        when(store.sealedWithOtherKey("k2")).thenReturn(List.of(before));
        BusinessClock clock = new BusinessClock(Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC));
        SealedSecrets secrets = mock(SealedSecrets.class);
        AccountKeyRotation rotation =
                new AccountKeyRotation(null, mock(TenantJobs.class), store, rotated, secrets, clock, transactions());

        assertThat(rotation.resealHere("k2")).isEqualTo(1);

        org.mockito.ArgumentCaptor<byte[]> key = org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.ArgumentCaptor<byte[]> secret = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(store)
                .reseal(
                        org.mockito.ArgumentMatchers.eq(before.id()),
                        org.mockito.ArgumentMatchers.any(),
                        key.capture(),
                        secret.capture(),
                        org.mockito.ArgumentMatchers.eq(rotated.blindIndex("payment-account-key", "lender-key-000001")),
                        org.mockito.ArgumentMatchers.eq("k2"));
        assertThat(rotated.keyIdOf(key.getValue())).isEqualTo("k2");
        assertThat(rotated.keyIdOf(secret.getValue())).isEqualTo("k2");
        assertThat(new String(rotated.open(secret.getValue()), StandardCharsets.UTF_8))
                .isEqualTo("lender-secret-000001");
        assertThat(Arrays.equals(key.getValue(), before.consumerKeyBox())).isFalse();
    }

    @Test
    void aRowWhoseKeyWasDroppedTooEarlyIsLeftAloneAndTheRunReportsAFailure() {
        DataKeys old = new FakeKeys("k1");
        DataKeys rotated = new FakeKeys("k2"); // k1 no longer configured
        AccountStore store = mock(AccountStore.class);
        when(store.sealedWithOtherKey("k2"))
                .thenReturn(List.of(row(old, "verified", "lender-key-000001", "x-secret-0000001")));
        BusinessClock clock = new BusinessClock(Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC));
        SealedSecrets secrets = mock(SealedSecrets.class);
        AccountKeyRotation rotation =
                new AccountKeyRotation(null, mock(TenantJobs.class), store, rotated, secrets, clock, transactions());
        assertThatThrownBy(() -> rotation.resealHere("k2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("x-secret-0000001");
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .reseal(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aProblemWithOnePaymentAccountNeverRollsBackTheSecondFactorSeedsOfTheSameTenant() {
        DataKeys old = new FakeKeys("k1");
        DataKeys rotated = new FakeKeys("k2", "k1");
        AccountStore store = mock(AccountStore.class);
        AccountStore.Account row = row(old, "verified", "lender-key-000001", "lender-secret-000001");
        when(store.sealedWithOtherKey("k2")).thenReturn(List.of(row));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
                .when(store)
                .reseal(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        TenantJobs jobs = mock(TenantJobs.class);
        when(jobs.callAs(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> ((java.util.function.Supplier<?>) i.getArgument(1)).get());
        SealedSecrets secrets = mock(SealedSecrets.class);
        when(secrets.resealBoundTenant()).thenReturn(3);
        AccountKeyRotation rotation =
                new AccountKeyRotation(null, jobs, store, rotated, secrets, CLOCK, transactions());

        AccountKeyRotation.Outcome outcome = rotation.resealTenant(LENDER, "k2");

        // The accounts piece failed and is counted; the seeds were still re-sealed, in a transaction of their own.
        assertThat(outcome.failures()).isEqualTo(1);
        assertThat(outcome.resealed()).isEqualTo(3);
        verify(secrets).resealBoundTenant();
    }

    @Test
    void theBlindIndexesCoverTheCurrentAndEveryRetiredKeySoARowSealedBeforeARotationStillClashes() {
        FakeKeys rotated = new FakeKeys("k2", "k1");
        List<String> all = rotated.blindIndexes("p", "key-0001");
        assertThat(all).hasSize(2).first().isEqualTo(rotated.blindIndex("p", "key-0001"));
        assertThat(all.get(1)).isEqualTo(new FakeKeys("k1").blindIndex("p", "key-0001"));
    }
}
