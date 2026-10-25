package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.kernel.ApiException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;

/**
 * Which unique key refused a staff write (issue #323, ADR-085): read from the server's constraint name, never the message,
 * and only the two contact indexes are a 409. All values fabricated.
 */
class DuplicateContactTest {

    static DuplicateKeyException refusedBy(String constraint) {
        var server = new ServerErrorMessage(
                "SERROR\0C23505\0Mduplicate key value violates unique constraint\0n" + constraint
                        + "\0DKey (tenant_id, phone_e164)=(00000000-0000-0000-0000-000000000001, +256700000046) already exists.\0");
        return new DuplicateKeyException("insert", new PSQLException(server));
    }

    @Test
    void thePhoneIndexIsDuplicatePhone() {
        assertThat(UserService.duplicateContact(refusedBy("users_staff_phone")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("duplicate_phone"));
    }

    @Test
    void theEmailIndexIsDuplicateEmail() {
        assertThat(UserService.duplicateContact(refusedBy("users_staff_email")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("duplicate_email"));
    }

    @Test
    void anyOtherKeyIsAFaultThatNamesOnlyTheConstraint() {
        RuntimeException e = UserService.duplicateContact(refusedBy("users_pkey"));
        assertThat(e).isNotInstanceOf(ApiException.class).hasNoCause();
        assertThat(e.getMessage()).contains("users_pkey").doesNotContain("700000046");
        // A message that only mentions the phone index, with no server error, is not trusted either.
        assertThat(UserService.duplicateContact(new DuplicateKeyException("users_staff_phone")))
                .isNotInstanceOf(ApiException.class);
    }
}
