package com.invo.coopr8.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.VerifiedToken;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * Mints and verifies COOPR8 access tokens.
 *
 * <p><strong>Claims.</strong> {@code sub} is the numeric user id; {@code organizationId} is
 * the tenant and the authoritative answer to "whose data may this request touch";
 * {@code ledgerID} is the member-facing membership number; {@code roles} carries the
 * authorities; {@code organizationSlug} is informational; {@code jti} identifies the session
 * so a token can be named in an audit trail without reproducing it.
 *
 * <p><strong>Verification is strict and fails closed.</strong> Every claim above is
 * required, {@code iss} must be {@code coopr8}, and anything missing, mistyped or malformed
 * is a {@link JwtException} rather than a partially-trusted token. That strictness is also
 * what retires the previous token format: tokens issued before Phase 2 carry only
 * {@code ledgerID} and {@code roles}, so they cannot satisfy verification even if the old
 * signing key were somehow still in use. Combined with the rotated key, every token issued
 * before this deployment is invalid -- users authenticate again, with no compatibility path,
 * by design.
 */
@Service
@RequiredArgsConstructor
public class JwtProvider {

    static final String ISSUER = "coopr8";
    static final String CLAIM_ORGANIZATION_ID = "organizationId";
    static final String CLAIM_ORGANIZATION_SLUG = "organizationSlug";
    static final String CLAIM_LEDGER_ID = "ledgerID";
    static final String CLAIM_ROLES = "roles";

    /** HS256's hash output is 256 bits; a shorter key weakens the MAC. */
    private static final int MINIMUM_KEY_BYTES = 32;

    /**
     * SHA-256 of the signing key that used to be committed as a literal in
     * {@code JwtConstant}. Stored as a digest so this file can reject the compromised key
     * without containing it. Any deployment still configured with that value fails to
     * start, which is the only reliable way to be sure the rotation actually took effect --
     * a config file left untouched would otherwise keep a key that is public in git history.
     */
    private static final String RETIRED_SECRET_SHA256 =
            "53a02d4be0b5810966ce7a296ffaffb12de2cace77fa4ba07a52f98012d4c45c";

    private final JwtProperties properties;

    private SecretKey key;
    private JwtParser parser;

    @PostConstruct
    void init() {
        byte[] decoded = decodeConfiguredSecret();
        this.key = Keys.hmacShaKeyFor(decoded);
        this.parser = Jwts.parserBuilder()
                .setSigningKey(this.key)
                .requireIssuer(ISSUER)
                .build();
    }

    /**
     * Validates the configured key and returns its bytes.
     *
     * <p>Every failure message describes the shape of the problem and never echoes the
     * value: a startup log is not a safe place for a signing key.
     */
    private byte[] decodeConfiguredSecret() {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing key is not configured. Set the JWT_SECRET environment variable "
                            + "(coopr8.jwt.secret) to a fresh Base64-encoded 32-byte key, e.g. "
                            + "`openssl rand -base64 32`. The application will not start without one, "
                            + "because falling back to a built-in default would mean every deployment "
                            + "shared a publicly known key.");
        }

        if (sha256Hex(secret).equals(RETIRED_SECRET_SHA256)) {
            throw new IllegalStateException(
                    "JWT signing key is the RETIRED key that was previously committed to source "
                            + "control. It is public in git history and must never be used again. "
                            + "Generate a new one (`openssl rand -base64 32`) and set JWT_SECRET to it. "
                            + "Every existing token becomes invalid, which is intended: users "
                            + "authenticate again after this deployment.");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "JWT signing key is not valid Base64. Set JWT_SECRET to the output of "
                            + "`openssl rand -base64 32` (value withheld from this message).", e);
        }

        if (decoded.length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT signing key decodes to " + decoded.length + " bytes; at least "
                            + MINIMUM_KEY_BYTES + " are required for HS256. Generate one with "
                            + "`openssl rand -base64 32`.");
        }

        return decoded;
    }

    /**
     * A fresh session identifier, to be carried by both the {@link AuthPrincipal} and the
     * token's {@code jti}. Minted before the principal exists so the two agree.
     */
    public String newTokenId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Issues a token for a member who has just authenticated.
     *
     * <p>The {@code jti} is the principal's own {@code tokenId}, so the session identifier in
     * logs and audit records is the same string the token carries -- one session, one id.
     */
    public String generateToken(AuthPrincipal principal) {
        String tokenId = requiredString(principal.tokenId(), "jti");
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setIssuer(ISSUER)
                .setId(tokenId)
                .setSubject(String.valueOf(principal.userId()))
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + properties.getExpirationMs()))
                .claim(CLAIM_ORGANIZATION_ID, principal.organizationId())
                .claim(CLAIM_ORGANIZATION_SLUG, principal.organizationSlug())
                .claim(CLAIM_LEDGER_ID, principal.ledgerID())
                .claim(CLAIM_ROLES, principal.roleList())
                .signWith(key)
                .compact();
    }

    /**
     * Verifies a token and extracts its claims.
     *
     * @param authorizationHeaderOrToken the raw {@code Authorization} header value or a bare
     *                                   token
     * @return the verified claims -- never a partially-populated result
     * @throws JwtException if the signature, issuer, expiry or any required claim is
     *                      missing or malformed
     */
    public VerifiedToken parse(String authorizationHeaderOrToken) {
        if (authorizationHeaderOrToken == null || authorizationHeaderOrToken.isBlank()) {
            throw new JwtException("No token supplied.");
        }

        String token = authorizationHeaderOrToken.trim();
        if (token.startsWith(JwtConstant.BEARER_PREFIX)) {
            token = token.substring(JwtConstant.BEARER_PREFIX.length()).trim();
        }

        Claims claims = parser.parseClaimsJws(token).getBody();

        Long userId = requiredLong(claims.getSubject(), "sub");
        Long organizationId = requiredOrganizationId(claims);
        String ledgerID = requiredString(claims.get(CLAIM_LEDGER_ID, String.class), CLAIM_LEDGER_ID);
        String tokenId = requiredString(claims.getId(), "jti");
        String slugClaim = requiredString(claims.get(CLAIM_ORGANIZATION_SLUG, String.class),
                CLAIM_ORGANIZATION_SLUG);
        Set<String> roles = requiredRoles(claims);

        return new VerifiedToken(userId, organizationId, ledgerID, roles, tokenId, slugClaim);
    }

    private static Long requiredOrganizationId(Claims claims) {
        Object raw = claims.get(CLAIM_ORGANIZATION_ID);
        if (raw == null) {
            throw new JwtException("Token carries no " + CLAIM_ORGANIZATION_ID
                    + " claim, so the tenant is unknown. Rejecting rather than guessing one.");
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return requiredLong(String.valueOf(raw), CLAIM_ORGANIZATION_ID);
    }

    private static Long requiredLong(String raw, String claimName) {
        if (raw == null || raw.isBlank()) {
            throw new JwtException("Token is missing the required '" + claimName + "' claim.");
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new JwtException("Token claim '" + claimName + "' is not a number.", e);
        }
    }

    private static String requiredString(String raw, String claimName) {
        if (raw == null || raw.isBlank()) {
            throw new JwtException("Token is missing the required '" + claimName + "' claim.");
        }
        return raw.trim();
    }

    private static Set<String> requiredRoles(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new JwtException("Token is missing the required '" + CLAIM_ROLES + "' claim.");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof String role) || role.isBlank()) {
                throw new JwtException("Token '" + CLAIM_ROLES + "' claim contains a non-role entry.");
            }
            roles.add(role.trim());
        }
        return roles;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to validate the JWT key.", e);
        }
    }
}
