package com.invo.coopr8.config;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.invo.coopr8.security.PlatformAdminPrincipal;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Authenticates Platform Super Admin requests to {@code /api/platform/**}.
 *
 * <p>Validates that the token was minted by {@link PlatformJwtProvider} with issuer
 * {@code coopr8-platform} and role {@code ROLE_PLATFORM_ADMIN}. Tenant tokens fail
 * verification here and are denied.
 */
@Slf4j
@RequiredArgsConstructor
public class PlatformJwtValidatorFilter extends OncePerRequestFilter {

    private final PlatformJwtProvider platformJwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Only process platform requests; let tenant requests pass through to JwtTokenValidator
        if (!request.getRequestURI().startsWith("/api/platform/") && !request.getRequestURI().equals("/api/platform")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String authHeader = request.getHeader(JwtConstant.JWT_HEADER);
            if (authHeader != null && authHeader.startsWith(JwtConstant.BEARER_PREFIX)) {
                try {
                    PlatformAdminPrincipal principal = platformJwtProvider.parse(authHeader);
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("Rejecting invalid platform token: {}", e.getMessage());
                    SecurityContextHolder.clearContext();
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // For /api/platform/ routes, clear the security context after execution
            SecurityContextHolder.clearContext();
        }
    }
}

