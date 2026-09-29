package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import com.rincoltech.bms.kernel.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests of the credential building blocks (chapter 8 section 8.2, ADR-014). */
class CredentialsTest {

    /** RFC 6238 appendix B, SHA-1 vectors, truncated to the 6 digits authenticator apps use. */
    @Test
    void totpMatchesTheRfc6238TestVectors() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(1111111109))))
                .isEqualTo("081804");
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(1234567890))))
                .isEqualTo("005924");
        assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(2000000000))))
                .isEqualTo("279037");
    }

    @Test
    void totpAcceptsOneStepOfDriftAndNeverTheSameStepTwice() {
        byte[] secret = Totp.newSecret();
        Instant now = Instant.parse("2026-09-29T10:00:15Z");
        long step = Totp.step(now);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 1), now, null)).hasValue(step - 1);
        assertThat(Totp.verify(secret, Totp.code(secret, step + 1), now, null)).hasValue(step + 1);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 2), now, null)).isEmpty();
        assertThat(Totp.verify(secret, Totp.code(secret, step), now, step)).isEmpty();
        assertThat(Totp.verify(secret, "12345", now, null)).isEmpty();
        assertThat(Totp.uri("BMS demo", "owner@example.test", secret))
                .startsWith("otpauth://totp/BMS%20demo:owner%40example.test?secret=")
                .contains("&digits=6&period=30");
    }

    @Test
    void theSecretBoxRoundTripsAndRefusesAnotherKeyOrATamperedBox() {
        byte[] key = new byte[32];
        key[0] = 7;
        SecretBox box = new SecretBox(key, "k1");
        byte[] sealed = box.seal("fabricated secret".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(box.open(sealed), StandardCharsets.UTF_8)).isEqualTo("fabricated secret");
        assertThat(box.seal(new byte[] {1})).isNotEqualTo(box.seal(new byte[] {1}));

        assertThatThrownBy(() -> new SecretBox(key, "k2").open(sealed)).hasMessageContaining("another data key");
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> box.open(sealed)).hasMessageContaining("decryption failed");
        assertThatThrownBy(() -> new SecretBox(new byte[16], "k1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordsAreArgon2idAndWeakOnesAreRefused() {
        Passwords passwords = new Passwords(new AuthProperties("none", null, null, null, 4096, 2));
        String hash = passwords.hash("Fabricated-Pass-2026");
        assertThat(hash).startsWith("$argon2id$v=19$m=4096,t=2,p=1$");
        assertThat(passwords.matches("Fabricated-Pass-2026", hash)).isTrue();
        assertThat(passwords.matches("fabricated-pass-2026", hash)).isFalse();
        assertThat(passwords.matches("Fabricated-Pass-2026", null)).isFalse();

        for (String weak : List.of("short", "Password123", "qwertyuiop", "1234567890")) {
            assertThatThrownBy(() -> passwords.checkStrength(weak))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo("weak_password");
        }
        assertThatThrownBy(() -> passwords.checkStrength("owner@example.test", "owner@example.test"))
                .isInstanceOf(ApiException.class);
        passwords.checkStrength("Fabricated-Pass-2026", "owner@example.test");
    }

    @Test
    void recoveryCodesAreTenDistinctAndHashIgnoringCaseAndDashes() {
        List<String> codes = Secrets.recoveryCodes();
        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allMatch(c -> c.matches("^[2-9A-HJ-NP-Z]{5}-[2-9A-HJ-NP-Z]{5}$"));
        String code = codes.getFirst();
        assertThat(Secrets.recoveryCodeHash(code.toLowerCase().replace("-", " ")))
                .isEqualTo(Secrets.recoveryCodeHash(code))
                .hasSize(64);
        assertThat(Secrets.looksLikeRecoveryCode("123456")).isFalse();
        assertThat(Secrets.looksLikeRecoveryCode(code)).isTrue();
        assertThat(Secrets.token()).hasSize(43).isNotEqualTo(Secrets.token());
    }

    @Test
    void accessTokensVerifyOnlyForTheirPurposeAndBeforeExpiry() throws Exception {
        ECKey key = KeyMaterial.generateSigningKey();
        AccessTokens tokens = new AccessTokens(key);
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-29T10:00:00Z");
        String access = tokens.access(user, tenant, "staff", session, "demo.bms.test", now);

        AccessTokens.Claims claims = tokens.verify(access, AccessTokens.PURPOSE_ACCESS, now.plusSeconds(60));
        assertThat(claims.userId()).isEqualTo(user);
        assertThat(claims.tenantId()).isEqualTo(tenant);
        assertThat(claims.sessionId()).isEqualTo(session);
        assertThat(claims.kind()).isEqualTo("staff");

        assertThatThrownBy(() -> tokens.verify(access, AccessTokens.PURPOSE_ACCESS, now.plus(Duration.ofMinutes(15))))
                .extracting(e -> ((AccessTokens.Invalid) e).code())
                .isEqualTo("token_expired");
        assertThatThrownBy(() -> tokens.verify(access, AccessTokens.PURPOSE_MFA, now))
                .extracting(e -> ((AccessTokens.Invalid) e).code())
                .isEqualTo("unauthenticated");
        AccessTokens other = new AccessTokens(KeyMaterial.generateSigningKey());
        assertThatThrownBy(() -> other.verify(access, AccessTokens.PURPOSE_ACCESS, now))
                .isInstanceOf(AccessTokens.Invalid.class);
        assertThatThrownBy(() -> tokens.verify("not.a.token", AccessTokens.PURPOSE_ACCESS, now))
                .isInstanceOf(AccessTokens.Invalid.class);

        String mfa = tokens.mfa(user, tenant, "staff", "demo.bms.test", now);
        assertThat(tokens.verify(mfa, AccessTokens.PURPOSE_MFA, now.plusSeconds(299))
                        .sessionId())
                .isNull();
        assertThatThrownBy(() -> tokens.verify(mfa, AccessTokens.PURPOSE_MFA, now.plusSeconds(300)))
                .isInstanceOf(AccessTokens.Invalid.class);
    }
}
