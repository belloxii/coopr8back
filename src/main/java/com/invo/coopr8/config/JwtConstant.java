package com.invo.coopr8.config;

/**
 * Non-secret JWT constants.
 *
 * <p>This class previously also held {@code SECRET_KEY} as a committed string literal and
 * read {@code JWT_EXPIRATION_MS} straight from the process environment. Both moved to
 * {@link JwtProperties}: the secret because a signing key in source control is readable by
 * everyone with repository access and stays in git history forever, and the expiry because
 * a static initialiser reading {@code System.getenv} is invisible to Spring's configuration
 * model (it could not be set from {@code .env}, a profile, or a test).
 *
 * <p>The retired literal has been rotated out of service. {@link JwtProvider} hashes the
 * configured key at startup and refuses to boot if it is the retired one, so a stale
 * deployment config cannot quietly keep using the compromised key.
 */
public final class JwtConstant {

    /** Request header carrying the bearer token. */
    public static final String JWT_HEADER = "Authorization";

    /** Scheme prefix, including the trailing space, as it appears in the header. */
    public static final String BEARER_PREFIX = "Bearer ";

    private JwtConstant() {
        // constants only
    }
}
