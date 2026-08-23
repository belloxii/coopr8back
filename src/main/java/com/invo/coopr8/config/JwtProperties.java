package com.invo.coopr8.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * JWT signing configuration, bound from {@code coopr8.jwt.*}.
 *
 * <p>The signing key is supplied by the {@code JWT_SECRET} environment variable. It used
 * to be a literal in {@code JwtConstant}, which meant the key that authenticates every
 * request was readable by anyone with repository access -- and remains readable in git
 * history forever. Moving it here is only half the fix; the value itself had to be
 * rotated, and {@link JwtProvider} refuses to start if the retired key is supplied again.
 *
 * <p>Nothing in this class or its users ever logs, returns, or reports the secret.
 * Validation failures describe the <em>shape</em> problem (missing, not Base64, too
 * short) and never echo the value.
 */
@Component
@ConfigurationProperties(prefix = "coopr8.jwt")
@Getter
@Setter
public class JwtProperties {

    /**
     * Base64-encoded HMAC-SHA signing key. Must decode to at least 32 bytes: HS256 keys
     * shorter than the 256-bit hash output weaken the MAC, and JJWT rejects them outright.
     * Generate one with:
     * <pre>openssl rand -base64 32</pre>
     */
    private String secret;

    /** Token lifetime in milliseconds. */
    private long expirationMs = Duration.ofHours(3).toMillis();
}
