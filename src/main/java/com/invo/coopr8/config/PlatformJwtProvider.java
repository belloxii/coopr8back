package com.invo.coopr8.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.invo.coopr8.model.PlatformAdmin;
import com.invo.coopr8.security.PlatformAdminPrincipal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Mints and verifies JWTs for Platform Super Administrators.
 *
 * <p>Uses a distinct issuer ({@code coopr8-platform}) and audience ({@code coopr8-platform-admin})
 * so platform tokens cannot be presented on tenant endpoints, and tenant tokens cannot be
 * presented on platform endpoints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformJwtProvider {

    public static final String ISSUER = "coopr8-platform";
    public static final String AUDIENCE = "coopr8-platform-admin";

    private final JwtProperties jwtProperties;
    private SecretKey signingKey;
    private JwtParser parser;

    @PostConstruct
    void init() {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(jwtProperties.getSecret());
        } catch (IllegalArgumentException e) {
            keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.parser = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build();
    }

    public String generateToken(PlatformAdmin admin) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationMs());
        String tokenId = UUID.randomUUID().toString();

        return Jwts.builder()
                .setSubject(String.valueOf(admin.getId()))
                .setIssuer(ISSUER)
                .setAudience(AUDIENCE)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .setId(tokenId)
                .claim("email", admin.getEmail())
                .claim("role", PlatformAdminPrincipal.ROLE)
                .signWith(signingKey)
                .compact();
    }

    public PlatformAdminPrincipal parse(String bearerToken) {
        String token = bearerToken.startsWith(JwtConstant.BEARER_PREFIX)
                ? bearerToken.substring(JwtConstant.BEARER_PREFIX.length()).trim()
                : bearerToken.trim();

        Claims claims = parser.parseClaimsJws(token).getBody();
        String role = claims.get("role", String.class);
        if (!PlatformAdminPrincipal.ROLE.equals(role)) {
            throw new JwtException("Token does not carry the platform admin role.");
        }

        Long id = Long.valueOf(claims.getSubject());
        String email = claims.get("email", String.class);
        String tokenId = claims.getId();

        return new PlatformAdminPrincipal(id, email, tokenId);
    }
}

