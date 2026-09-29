package com.rincoltech.bms.core.identity.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Signed tokens (chapter 7 section 7.4.1, ADR-014): ES256 JWS with a {@code kid} header. Two
 * purposes: the 15 minute access token ({@code sub, tid, knd, sid, iat, exp, iss}) and the
 * 5 minute MFA token handed out between the password step and the second factor
 * ({@code pur=mfa}, no session). Permissions and branch scope are never in a token.
 */
final class AccessTokens {

    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration MFA_TTL = Duration.ofMinutes(5);
    static final String PURPOSE_ACCESS = "access";
    static final String PURPOSE_MFA = "mfa";

    private final ECKey key;
    private final ECDSASigner signer;
    private final ECDSAVerifier verifier;

    AccessTokens(ECKey key) {
        if (key.getKeyID() == null || !key.isPrivate()) {
            throw new IllegalArgumentException("the signing key must be a private EC key with a kid");
        }
        try {
            this.key = key;
            this.signer = new ECDSASigner(key);
            this.verifier = new ECDSAVerifier(key.toPublicJWK());
        } catch (JOSEException e) {
            throw new IllegalArgumentException("unusable signing key", e);
        }
    }

    /** What a verified token says. {@code tenantId} is null for platform users. */
    record Claims(UUID userId, UUID tenantId, String kind, UUID sessionId, String purpose, Instant expiresAt) {}

    /** A token that failed verification; {@code code} is the API error code to answer with. */
    static final class Invalid extends Exception {
        private final String code;

        Invalid(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    String access(UUID userId, UUID tenantId, String kind, UUID sessionId, String issuer, Instant now) {
        return sign(userId, tenantId, kind, sessionId, PURPOSE_ACCESS, issuer, now, ACCESS_TTL);
    }

    String mfa(UUID userId, UUID tenantId, String kind, String issuer, Instant now) {
        return sign(userId, tenantId, kind, null, PURPOSE_MFA, issuer, now, MFA_TTL);
    }

    Claims verify(String token, String purpose, Instant now) throws Invalid {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())
                    || !key.getKeyID().equals(jwt.getHeader().getKeyID())
                    || !jwt.verify(verifier)) {
                throw new Invalid("unauthenticated");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!purpose.equals(c.getStringClaim("pur"))) {
                throw new Invalid("unauthenticated");
            }
            Instant exp =
                    c.getExpirationTime() == null ? null : c.getExpirationTime().toInstant();
            if (exp == null || !now.isBefore(exp)) {
                throw new Invalid("token_expired");
            }
            String tid = c.getStringClaim("tid");
            String sid = c.getStringClaim("sid");
            return new Claims(
                    UUID.fromString(c.getSubject()),
                    tid == null ? null : UUID.fromString(tid),
                    c.getStringClaim("knd"),
                    sid == null ? null : UUID.fromString(sid),
                    purpose,
                    exp);
        } catch (ParseException | JOSEException | IllegalArgumentException | NullPointerException e) {
            throw new Invalid("unauthenticated");
        }
    }

    private String sign(
            UUID userId,
            UUID tenantId,
            String kind,
            UUID sessionId,
            String purpose,
            String issuer,
            Instant now,
            Duration ttl) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("knd", kind)
                .claim("pur", purpose)
                .issuer(issuer)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)));
        if (tenantId != null) {
            claims.claim("tid", tenantId.toString());
        }
        if (sessionId != null) {
            claims.claim("sid", sessionId.toString());
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(key.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("token signing failed", e);
        }
        return jwt.serialize();
    }
}
