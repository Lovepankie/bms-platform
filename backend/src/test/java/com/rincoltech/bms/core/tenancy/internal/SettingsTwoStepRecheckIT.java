package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.tenancy.internal.SettingsController.UpdateSettingsRequest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The two-step policy's checks that run inside the settings transaction (issue #377, from the review of #361; ADR-046
 * decision 6), called on the service as the controller calls it. The controller decides from an unlocked read whether the
 * administrator's proof is needed; {@link SettingsService#update} decides again on the locked row. Fabricated tenant and
 * people.
 */
class SettingsTwoStepRecheckIT extends IntegrationTest {

    @Autowired
    SettingsService service;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("twostep-recheck", true);
        UUID admin = Api.staff(t, Api.email("admin"), new Role("tenant_admin", null));
        TestDatabase.owner().sql("""
                        INSERT INTO tenant_settings (id, tenant_id, settings)
                        VALUES (?, ?, '{"require_mfa_all_staff": true}')
                        ON CONFLICT (tenant_id) DO UPDATE
                           SET settings = tenant_settings.settings || '{"require_mfa_all_staff": true}'
                        """).params(UUID.randomUUID(), t.tenantId()).update();
        TenantContext.bind(t.tenantId());
        CurrentPrincipal.set(Principal.uniform(admin, "staff", Set.of("core.settings.manage"), true, Set.of()));
    }

    @AfterEach
    void tearDown() {
        CurrentPrincipal.clear();
        TenantContext.clear();
    }

    UpdateSettingsRequest request(Map<String, Object> body) {
        return mapper.convertValue(body, UpdateSettingsRequest.class);
    }

    String ifMatch() {
        return "\"" + service.current().version() + "\"";
    }

    long warnings() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key = ?")
                .params(t.tenantId(), "identity.two_step_policy_weakened")
                .query(Long.class)
                .single();
    }

    /** The locked recheck: a relaxation the controller's unlocked read did not see (so no proof was checked) is refused. */
    @Test
    void theLockedRecheckRefusesARelaxationThatCameWithoutProof() {
        UpdateSettingsRequest off = request(Map.of("require_mfa_all_staff", false));
        assertThat(service.relaxesTwoStep(off)).isTrue();

        assertThatThrownBy(() -> service.update(ifMatch(), off, false))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("proof_required"));
        assertThat(service.current().requireMfaAllStaff()).isTrue();
        assertThat(warnings()).isZero();

        // With the proof checked, the same change is saved and the administrators are told.
        assertThat(service.update(ifMatch(), off, true).requireMfaAllStaff()).isFalse();
        assertThat(warnings()).isEqualTo(1);
    }

    /** ADR-046 decision 6: the warning email is written in the settings transaction, so it goes only if the change stays. */
    @Test
    void theWarningRollsBackWithTheSettingsWrite() {
        UpdateSettingsRequest off = request(Map.of("require_mfa_all_staff", false));
        String version = ifMatch();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            assertThat(service.update(version, off, true).requireMfaAllStaff()).isFalse();
            status.setRollbackOnly();
        });

        assertThat(service.current().requireMfaAllStaff()).isTrue();
        assertThat(warnings()).isZero();
    }
}
