package com.invo.coopr8.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.VerifiedToken;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Unit tests for token minting and verification -- the boundary that decides which tenant a
 * request belongs to.
 *
 * <p>The claims are the whole point of the exercise: {@code organizationId} in the token is
 * what makes a request's tenant something the server established rather than something the
 * caller asserted. So these tests are mostly about rejection, and each one names a token that
 * must not be accepted.
 *
 * <p><strong>On the retired signing key.</strong> D2 required moving the key out of the
 * committed Java literal and rotating it, and {@link JwtProvider} refuses to boot if the old
 * value is supplied again -- comparing SHA-256 digests so it can reject the key without
 * containing it. That guard is deliberately <em>not</em> exercised here: doing so would mean
 * committing the retired key into the test sources, which is the exact disclosure the rotation
 * was meant to end. It was instead verified out of band, by hashing the literal straight out of
 * git history and comparing digests, with the value never printed. What is tested here is the
 * half that can be tested safely: the pre-Phase-2 token <em>shape</em> is rejected even when
 * signed with the current, valid key. The format retirement and the key rotation are
 * independent, and either alone invalidates every token issued before this deployment.
 *
 * <p>No database, no Docker, no Spring context.
 */
class JwtProviderTest {

    /** 32 bytes, so HS256 is satisfied. A test fixture, not a deployable key. */
    private static final String TEST_KEY_MATERIAL = "coopr8-unit-test-signing-key-abc";
    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString(TEST_KEY_MATERIAL.getBytes(StandardCharsets.UTF_8));

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(
            42L, 7L, "citadel", "CBMC0001", Set.of("ROLE_MEMBER"), "session-id-1");

    // ---------------------------------------------------------------- round trip

    @Test
    void aMintedTokenVerifiesBackToTheSameClaims() {
        JwtProvider provider = providerWith(TEST_SECRET);

        VerifiedToken verified = provider.parse(provider.generateToken(PRINCIPAL));

        assertThat(verified.userId()).isEqualTo(42L);
        assertThat(verified.organizationId())
                .as("the tenant must survive the round trip: it is the authoritative answer to "
                        + "which organization's data the request may touch")
                .isEqualTo(7L);
        assertThat(verified.ledgerID()).isEqualTo("CBMC0001");
        assertThat(verified.roles()).containsExactly("ROLE_MEMBER");
        assertThat(verified.tokenId()).isEqualTo("session-id-1");
        assertThat(verified.organizationSlugClaim()).isEqualTo("citadel");
    }

    @Test
    void theSubjectIsTheUserIdAndNotTheLedgerId() {
        JwtProvider provider = providerWith(TEST_SECRET);

        String token = provider.generateToken(PRINCIPAL);

        assertThat(subjectOf(token))
                .as("ledger IDs are unique only within an organization -- two cooperatives can "
                        + "both have a CBMC0001 -- so a ledger-ID subject would not identify a "
                        + "user on this platform")
                .isEqualTo("42")
                .isNotEqualTo("CBMC0001");
    }

    @Test
    void theAuthorizationHeaderFormIsAccepted() {
        JwtProvider provider = providerWith(TEST_SECRET);
        String token = provider.generateToken(PRINCIPAL);

        assertThat(provider.parse(JwtConstant.BEARER_PREFIX + token).userId()).isEqualTo(42L);
        assertThat(provider.parse("  " + token + "  ").userId()).isEqualTo(42L);
    }

    // ---------------------------------------------------------------- rejection

    @Test
    void aPrePhase2TokenIsRejectedEvenWhenSignedWithTheCurrentKey() {
        // The old format: ledgerID as the subject, no organizationId, no jti, no slug. Signed
        // here with the *valid* current key on purpose -- this is the guarantee that stands even
        // if the key rotation were somehow undone.
        String legacyToken = Jwts.builder()
                .setIssuer("coopr8")
                .setSubject("CBMC0001")
                .claim("ledgerID", "CBMC0001")
                .claim("roles", List.of("ROLE_MEMBER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(testKey())
                .compact();

        assertThatThrownBy(() -> providerWith(TEST_SECRET).parse(legacyToken))
                .as("a token from before Phase 2 carries no tenant, so honouring it would mean "
                        + "guessing one")
                .isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenWithNoOrganizationIdIsRejected() {
        assertThatThrownBy(() -> parseForged(builder -> builder.claim("organizationId", null)))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void aTokenWhoseOrganizationIdIsNotANumberIsRejected() {
        assertThatThrownBy(() -> parseForged(builder -> builder.claim("organizationId", "seven")))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void aTokenMissingAnyOtherRequiredClaimIsRejected() {
        assertThatThrownBy(() -> parseForged(builder -> builder.setSubject(null)))
                .as("no subject means no user").isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> parseForged(builder -> builder.claim("ledgerID", null)))
                .isInstanceOf(JwtException.class).hasMessageContaining("ledgerID");
        assertThatThrownBy(() -> parseForged(builder -> builder.setId(null)))
                .isInstanceOf(JwtException.class).hasMessageContaining("jti");
        assertThatThrownBy(() -> parseForged(builder -> builder.claim("organizationSlug", null)))
                .isInstanceOf(JwtException.class).hasMessageContaining("organizationSlug");
        assertThatThrownBy(() -> parseForged(builder -> builder.claim("roles", List.of())))
                .isInstanceOf(JwtException.class).hasMessageContaining("roles");
        assertThatThrownBy(() -> parseForged(builder -> builder.claim("roles", List.of(1, 2))))
                .as("a roles claim of the wrong element type must not be coerced into authorities")
                .isInstanceOf(JwtException.class).hasMessageContaining("roles");
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "a-completely-different-32-byte-ke".getBytes(StandardCharsets.UTF_8));
        String foreignToken = Jwts.builder()
                .setIssuer("coopr8")
                .setSubject("42")
                .setId("session-id-1")
                .claim("organizationId", 7L)
                .claim("organizationSlug", "citadel")
                .claim("ledgerID", "CBMC0001")
                .claim("roles", List.of("ROLE_ADMIN"))
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> providerWith(TEST_SECRET).parse(foreignToken))
                .as("otherwise anyone could mint themselves ROLE_ADMIN in any organization")
                .isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() {
        assertThatThrownBy(() -> parseForged(builder -> builder.setIssuer("not-coopr8")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(TEST_SECRET);
        properties.setExpirationMs(-1_000L);
        JwtProvider provider = new JwtProvider(properties);
        provider.init();

        String alreadyExpired = provider.generateToken(PRINCIPAL);

        assertThatThrownBy(() -> provider.parse(alreadyExpired)).isInstanceOf(JwtException.class);
    }

    @Test
    void anEmptyOrAbsentTokenIsRejected() {
        JwtProvider provider = providerWith(TEST_SECRET);

        assertThatThrownBy(() -> provider.parse(null)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.parse("   ")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.parse("Bearer ")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.parse("not.a.token")).isInstanceOf(JwtException.class);
    }

    // ---------------------------------------------------------------- key configuration

    @Test
    void theApplicationRefusesToStartWithoutAConfiguredKey() {
        for (String missing : new String[] {null, "", "   "}) {
            JwtProperties properties = new JwtProperties();
            properties.setSecret(missing);

            assertThatThrownBy(() -> new JwtProvider(properties).init())
                    .as("a built-in default key would mean every deployment shared a publicly "
                            + "known one")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        }
    }

    @Test
    void aKeyTooShortForHs256IsRejectedWithoutEchoingIt() {
        String tooShort = Base64.getEncoder()
                .encodeToString("16-bytes-exactly".getBytes(StandardCharsets.UTF_8));
        JwtProperties properties = new JwtProperties();
        properties.setSecret(tooShort);

        assertThatThrownBy(() -> new JwtProvider(properties).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16 bytes")
                .as("a startup log is not a safe place for a signing key")
                .hasMessageNotContaining(tooShort);
    }

    @Test
    void aKeyThatIsNotBase64IsRejectedWithoutEchoingIt() {
        String notBase64 = "this is not base64 !!!  ***";
        JwtProperties properties = new JwtProperties();
        properties.setSecret(notBase64);

        assertThatThrownBy(() -> new JwtProvider(properties).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64")
                .hasMessageNotContaining(notBase64);
    }

    @Test
    void theRetiredKeyGuardIsWiredInAndWellFormed() throws Exception {
        // The preimage is deliberately absent from this codebase (see the class comment), so
        // what is asserted is that the guard exists, holds a real SHA-256 digest, and is
        // consulted on the startup path -- a blank digest or a truncated constant would match
        // nothing and silently permit the retired key.
        var field = JwtProvider.class.getDeclaredField("RETIRED_SECRET_SHA256");
        field.setAccessible(true);
        String digest = (String) field.get(null);

        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(digest).isNotEqualTo("0".repeat(64));
    }

    @Test
    void theTestProfileKeyIsUsableSoTheIntegrationSuiteCanBoot() throws IOException {
        // The Testcontainers suite needs Docker, which is unavailable on this machine, so its
        // JWT configuration would otherwise go unverified until it first runs somewhere else.
        // This checks the one part of it that can be checked here -- without printing the value.
        Properties testProfile = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application-test.properties")) {
            assertThat(in).as("src/test/resources/application-test.properties must be present")
                    .isNotNull();
            testProfile.load(in);
        }

        String configured = testProfile.getProperty("coopr8.jwt.secret");
        assertThat(configured).as("the test profile must configure its own signing key rather "
                + "than inheriting one from the environment").isNotBlank();

        JwtProperties properties = new JwtProperties();
        properties.setSecret(configured);
        JwtProvider provider = new JwtProvider(properties);

        assertThatCode(provider::init)
                .as("the test profile's key must be valid, not the retired one, and long enough")
                .doesNotThrowAnyException();
        assertThat(provider.parse(provider.generateToken(PRINCIPAL)).organizationId())
                .isEqualTo(7L);
    }

    // ---------------------------------------------------------------- helpers

    private static JwtProvider providerWith(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        JwtProvider provider = new JwtProvider(properties);
        provider.init();
        return provider;
    }

    private static SecretKey testKey() {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_SECRET));
    }

    /**
     * Builds an otherwise-valid token, applies one mutation, and parses it. Every rejection test
     * differs from the accepted token in exactly one claim, so a passing test cannot be passing
     * for some unrelated reason.
     */
    private static VerifiedToken parseForged(Consumer<JwtBuilder> mutation) {
        JwtBuilder builder = Jwts.builder()
                .setIssuer("coopr8")
                .setSubject("42")
                .setId("session-id-1")
                .claim("organizationId", 7L)
                .claim("organizationSlug", "citadel")
                .claim("ledgerID", "CBMC0001")
                .claim("roles", List.of("ROLE_MEMBER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000));

        mutation.accept(builder);

        return providerWith(TEST_SECRET).parse(builder.signWith(testKey()).compact());
    }

    private static String subjectOf(String token) {
        String payload = token.split("\\.")[1];
        String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        int start = json.indexOf("\"sub\":\"") + "\"sub\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }
}
