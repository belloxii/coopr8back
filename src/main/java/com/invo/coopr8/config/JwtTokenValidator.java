package com.invo.coopr8.config;

import java.io.IOException;
import java.util.Optional;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.security.VerifiedToken;
import com.invo.coopr8.tenant.ActiveTenant;
import com.invo.coopr8.tenant.TenantContext;
import com.invo.coopr8.tenant.TenantResolver;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Establishes -- for the duration of one request -- who the caller is and which tenant they
 * may act within.
 *
 * <p>The sequence is: verify the token's signature and required claims, resolve the
 * {@code organizationId} claim against the database and confirm the organization is active,
 * bind {@link TenantContext}, publish an {@link AuthPrincipal} to the security context, run the
 * request, then clear both in a {@code finally} regardless of outcome.
 *
 * <p><strong>Tenant comes from the token, never the request.</strong> Nothing else in the
 * request influences the binding. A caller can put any {@code organizationId} they like in a
 * body or query string and it will be ignored.
 *
 * <p><strong>Why the organization is re-checked every request.</strong> A signature stays valid
 * until the token expires, so without this an organization suspended now would keep serving
 * requests for hours. The cost is one indexed primary-key read per authenticated request.
 *
 * <p><strong>An unusable token does not fail the request here.</strong> It results in no
 * authentication and no tenant, and the request continues so that Spring Security decides:
 * protected endpoints reject it, genuinely public ones (login, signup, OTP) still work. That
 * distinction matters in practice -- the browser attaches whatever token it has cached to every
 * call, so rejecting outright would leave a user holding a stale token unable to reach
 * {@code /api/auth/login} to obtain a fresh one. Failing closed means granting nothing, which
 * this does; it does not require breaking the way back in.
 *
 * <p><strong>Deliberately not a {@code @Component}.</strong> Boot auto-registers {@code Filter}
 * beans with the servlet container, which would run this <em>outside</em> the Spring Security
 * chain as well. {@code SecurityContextHolderFilter} then loads its own (empty) context inside
 * the chain and the authentication set outside it would be discarded. {@code AppConfig}
 * constructs this filter and places it in the chain instead.
 */
@RequiredArgsConstructor
@Slf4j
public class JwtTokenValidator extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final TenantResolver tenantResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Platform administration routes are authenticated by PlatformJwtValidatorFilter.
        // Tenant token validation and tenant binding must not run for platform routes.
        if (request.getRequestURI().startsWith("/api/platform/") || request.getRequestURI().equals("/api/platform")) {
            filterChain.doFilter(request, response);
            return;
        }

        // A tenant bound before authentication can only mean a previous request on this pooled
        // thread failed to clear. Clearing is the safe state, so recover rather than serve the
        // request under a stale tenant -- but say so loudly, because it is a bug.
        if (TenantContext.isBound()) {
            log.error("Tenant context was already bound on thread '{}' before authentication "
                            + "(organizationId={}). A previous request did not clear it. Clearing now.",
                    Thread.currentThread().getName(), TenantContext.getOrganizationId());
            TenantContext.clear();
        }

        try {
            authenticate(request.getHeader(JwtConstant.JWT_HEADER));
            if (CurrentAuth.principal().map(AuthPrincipal::passwordChangeRequired).orElse(false)
                    && !allowsPasswordSetup(request.getRequestURI())) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "Change the temporary password before using the application.");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            // Unconditional: the next request to reuse this thread must start with no identity
            // and no tenant, whatever happened above -- including an exception thrown by a
            // handler after the tenant was bound.
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean allowsPasswordSetup(String path) {
        return "/api/auth/profile".equals(path)
                || "/api/auth/change-default-pass".equals(path)
                || "/api/auth/changepass".equals(path);
    }

    private void authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(JwtConstant.BEARER_PREFIX)) {
            return;
        }

        VerifiedToken token;
        try {
            token = jwtProvider.parse(authorizationHeader);
        } catch (JwtException | IllegalArgumentException e) {
            // Expired, tampered, signed with the retired key, or in the pre-Phase-2 format.
            // The message is safe to log (JJWT does not include the token in it); the token
            // itself deliberately is not logged.
            log.debug("Rejecting bearer token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            return;
        }

        Optional<ActiveTenant> tenant = tenantResolver.activeTenantById(token.organizationId());
        if (tenant.isEmpty()) {
            log.debug("Rejecting token for user {}: organization {} is unknown or not active.",
                    token.userId(), token.organizationId());
            SecurityContextHolder.clearContext();
            return;
        }

        ActiveTenant activeTenant = tenant.get();

        // The slug comes from the database, not from the token, so a stale or tampered
        // organizationSlug claim cannot influence anything downstream.
        AuthPrincipal principal = new AuthPrincipal(
                token.userId(),
                activeTenant.id(),
                activeTenant.slug(),
                token.ledgerID(),
                token.roles(),
                token.tokenId(),
                token.passwordChangeRequired());

        TenantContext.bind(activeTenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }
}
