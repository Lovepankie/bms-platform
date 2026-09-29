package com.rincoltech.bms.core.operations.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** NFR-SEC-03, chapter 15 section 15.4 item 6: an over-privileged role cannot start the API. */
class DatabaseRoleGuardIT {

    @Test
    void theApplicationRolePasses() {
        assertThatCode(() -> DatabaseRoleGuard.check(JdbcClient.create(TestDatabase.appDataSource())))
                .doesNotThrowAnyException();
    }

    @Test
    void theOwnerRoleIsRefused() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(JdbcClient.create(TestDatabase.ownerDataSource())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bms_owner");
    }
}
