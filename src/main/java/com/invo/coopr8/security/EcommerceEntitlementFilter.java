package com.invo.coopr8.security;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.invo.coopr8.service.EntitlementService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central gate enforcing eCommerce entitlement on {@code /api/store/**} and {@code /api/admin/store/**}.
 *
 * <p><strong>Why ecommerce business functionality is absent in Phase 1:</strong>
 * Full ecommerce (products, catalog, orders, inventory) is designed for a dedicated implementation phase.
 * The Enterprise commercial plan is seeded inactive in V15, and this filter establishes the security boundary
 * and architectural gating ahead of time: any request reaching store paths must belong to an organization
 * entitled to eCommerce (via plan tier or platform admin override). Unentitled organizations receive 403 Forbidden.
 */
@Slf4j
@RequiredArgsConstructor
public class EcommerceEntitlementFilter extends OncePerRequestFilter {

    private final EntitlementService entitlementService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (isStorePath(path)) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                try {
                    entitlementService.requireEcommerce();
                } catch (AccessDeniedException e) {
                    log.debug("Rejecting store request for unentitled tenant on {}: {}", path, e.getMessage());
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"responseCode\":\"403\",\"responseMessage\":\"" + e.getMessage() + "\"}");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isStorePath(String path) {
        return path.startsWith("/api/store/") || path.equals("/api/store")
                || path.startsWith("/api/admin/store/") || path.equals("/api/admin/store");
    }
}
