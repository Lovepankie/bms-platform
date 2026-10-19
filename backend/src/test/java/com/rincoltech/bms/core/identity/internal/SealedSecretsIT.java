package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The data key rotation reaches the second-factor seeds too (issue #233, review of #308): a retired key may be dropped only when
 * nothing is sealed with it, so the job re-seals TOTP seeds as well as payment credentials, and the worklist and the remaining
 * count see them (suspended tenants included). Also the start-up refusals: a retired key with the current key's id, and two
 * retired keys with one id. Every key is invented.
 */
class SealedSecretsIT {

    static byte[] key(int n) {
        byte[] k = new byte[32];
        k[0] = (byte) n;
        return k;
    }

    @Test
    void totpSeedsSealedWithARetiredKeyAreResealedTheWorklistAndTheCountSeeThemAndTheyStillOpen() {
        TestDatabase.Fixture t = TestDatabase.tenant("seal-rotate", false);
        JdbcClient owner = TestDatabase.owner();
        UUID user = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Seal Test', ?, 'active')")
                .params(user, t.tenantId(), "seal-" + user + "@example.test")
                .update();
        SecretBox old = new SecretBox(key(1), "k1");
        byte[] seed = "JBSWY3DPEHPK3PXP".getBytes(StandardCharsets.UTF_8);
        owner.sql(
                        "INSERT INTO user_credentials (user_id, tenant_id, totp_secret_enc, totp_pending_secret_enc) VALUES (?, ?, ?, ?)")
                .params(user, t.tenantId(), old.seal(seed), old.seal(seed))
                .update();
        // A suspended tenant is still on the worklist.
        owner.sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(t.tenantId())
                .update();

        SecretBox rotated = new SecretBox(key(2), "k2", Map.of("k1", key(1)));
        JdbcClient app = JdbcClient.create(TestDatabase.appDataSource());
        assertThat(app.sql("SELECT app_tenants_with_sealed_secrets_to_reseal('k2', 1000)")
                        .query(UUID.class)
                        .list())
                .contains(t.tenantId());
        long before = app.sql("SELECT app_count_sealed_secrets_to_reseal('k2')")
                .query(Long.class)
                .single();
        assertThat(before).isGreaterThanOrEqualTo(2);

        // The service as the job drives it for the tenant: the owner's connection stands in for the tenant-bound one.
        AdminSecurityService service = new AdminSecurityService(null, null, owner, rotated);
        assertThat(service.resealBoundTenantOf(t.tenantId())).isEqualTo(2);
        assertThat(app.sql("SELECT app_tenants_with_sealed_secrets_to_reseal('k2', 1000)")
                        .query(UUID.class)
                        .list())
                .doesNotContain(t.tenantId());
        byte[] resealed = owner.sql("SELECT totp_secret_enc FROM user_credentials WHERE user_id = ?")
                .param(user)
                .query(byte[].class)
                .single();
        assertThat(rotated.keyIdOf(resealed)).isEqualTo("k2");
        // Opens with the current key alone: the retired key can be dropped for this row.
        assertThat(new SecretBox(key(2), "k2").open(resealed)).isEqualTo(seed);
        assertThat(app.sql("SELECT app_count_sealed_secrets_to_reseal('k2')")
                        .query(Long.class)
                        .single())
                .isEqualTo(before - 2);
    }

    @Test
    void aRetiredKeyWithTheCurrentKeysIdOrTwoRetiredKeysWithOneIdStopTheStart() {
        assertThatThrownBy(() -> new SecretBox(key(2), "k1", Map.of("k1", key(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BMS_DATA_KEY_ID");
        String b64 = java.util.Base64.getEncoder().encodeToString(key(1));
        AuthProperties twice = new AuthProperties("none", null, null, "k2", "k1:" + b64 + ",k1:" + b64, 4096, 2);
        assertThatThrownBy(() -> KeyMaterial.retiredKeys(twice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twice")
                .hasMessageNotContaining(b64);
        AuthProperties once = new AuthProperties("none", null, null, "k2", "k1:" + b64, 4096, 2);
        assertThat(KeyMaterial.retiredKeys(once)).containsOnlyKeys("k1");
    }

    @Test
    void aMalformedBoxIsAnErrorNotACrashOfAnotherKind() {
        SecretBox box = new SecretBox(key(1), "k1");
        for (byte[] bad :
                List.of(new byte[0], new byte[] {1}, new byte[] {1, (byte) 200, 1, 2, 3}, new byte[] {9, 1, 1})) {
            assertThatThrownBy(() -> box.open(bad)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> box.keyIdOf(bad)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void theBlindIndexIsStableForOneKeyDiffersByPurposeAndByKeyAndShowsNoInput() {
        SecretBox a = new SecretBox(key(1), "k1");
        String d = a.blindIndex("p", "consumer-key-0001");
        assertThat(d).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("consumer");
        assertThat(a.blindIndex("p", "consumer-key-0001")).isEqualTo(d);
        assertThat(a.blindIndex("q", "consumer-key-0001")).isNotEqualTo(d);
        assertThat(a.blindIndex("p", "consumer-key-0002")).isNotEqualTo(d);
        assertThat(new SecretBox(key(2), "k2").blindIndex("p", "consumer-key-0001"))
                .isNotEqualTo(d);
    }
}
