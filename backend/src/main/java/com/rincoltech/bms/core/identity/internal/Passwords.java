package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing and the password rules (chapter 8 section 8.2, FR-IAM-04, ADR-014): argon2id
 * with 64 MiB, 3 iterations, parallelism 1, a 16 byte salt and a 32 byte hash, stored in PHC
 * string form so the parameters travel with each hash. Passwords are 10 to 128 characters and
 * may not be on the common password list.
 */
@Component
class Passwords {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 128;

    private final Argon2PasswordEncoder encoder;
    private final Set<String> common;
    /** Compared against when the account does not exist, so both paths cost one hash. */
    private final String dummyHash;

    Passwords(AuthProperties properties) {
        this.encoder =
                new Argon2PasswordEncoder(16, 32, 1, properties.argon2MemoryKib(), properties.argon2Iterations());
        this.common = load();
        this.dummyHash = encoder.encode("not-a-real-password-0000");
    }

    String hash(String password) {
        return encoder.encode(password);
    }

    /** Constant work whether or not the account exists: pass {@code null} for a missing hash. */
    boolean matches(String password, String hash) {
        boolean ok = encoder.matches(password, hash == null ? dummyHash : hash);
        return ok && hash != null;
    }

    /** Refuses a weak password with 422 {@code weak_password}. */
    void checkStrength(String password, String... identifiers) {
        String reason = null;
        if (password == null || password.length() < MIN_LENGTH) {
            reason = "Use at least " + MIN_LENGTH + " characters.";
        } else if (password.length() > MAX_LENGTH) {
            reason = "Use at most " + MAX_LENGTH + " characters.";
        } else if (common.contains(password.toLowerCase(Locale.ROOT))) {
            reason = "This password is too common.";
        } else {
            for (String id : identifiers) {
                if (id != null && password.equalsIgnoreCase(id)) {
                    reason = "Do not use your sign-in name as the password.";
                }
            }
        }
        if (reason != null) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "weak_password",
                    "Weak password",
                    reason,
                    List.of(new FieldProblem("password", "weak_password", reason)));
        }
    }

    private static Set<String> load() {
        try (var in = Objects.requireNonNull(Passwords.class.getResourceAsStream("/common-passwords.txt"));
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
