package com.invo.coopr8.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.service.EntitlementService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

class EcommerceEntitlementFilterTest {

    private FakeEntitlementService entitlementService;
    private EcommerceEntitlementFilter filter;

    static class FakeEntitlementService implements EntitlementService {
        boolean ecommerceEntitled = false;
        boolean aiScanningEntitled = false;

        @Override
        public boolean isAiScanningEntitled() {
            return aiScanningEntitled;
        }

        @Override
        public boolean isEcommerceEntitled() {
            return ecommerceEntitled;
        }

        @Override
        public boolean isAiScanningEntitled(Organization organization) {
            return aiScanningEntitled;
        }

        @Override
        public boolean isEcommerceEntitled(Organization organization) {
            return ecommerceEntitled;
        }

        @Override
        public void requireAiScanning() {
            if (!aiScanningEntitled) {
                throw new AccessDeniedException("This organization is not entitled to AI form scanning.");
            }
        }

        @Override
        public void requireEcommerce() {
            if (!ecommerceEntitled) {
                throw new AccessDeniedException("This organization is not entitled to eCommerce.");
            }
        }
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        entitlementService = new FakeEntitlementService();
        filter = new EcommerceEntitlementFilter(entitlementService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateTenantUser() {
        AuthPrincipal principal = new AuthPrincipal(
                1L, 10L, "citadel", "CBMC0001", Collections.singleton("ROLE_MEMBER"), "tok-1");
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_MEMBER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void unentitledTenantRequestToStoreEndpointIsRejectedWith403() throws ServletException, IOException {
        authenticateTenantUser();
        entitlementService.ecommerceEntitled = false;

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/store/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("403");
        assertThat(response.getContentAsString()).contains("not entitled to eCommerce");
    }

    @Test
    void entitledTenantRequestToStoreEndpointPassesThrough() throws ServletException, IOException {
        authenticateTenantUser();
        entitlementService.ecommerceEntitled = true;

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/store/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void adminStorePathIsAlsoGated() throws ServletException, IOException {
        authenticateTenantUser();
        entitlementService.ecommerceEntitled = false;

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/store/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void nonStorePathPassesThroughEvenWhenUnentitled() throws ServletException, IOException {
        authenticateTenantUser();
        entitlementService.ecommerceEntitled = false;

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shares");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void unauthenticatedStoreRequestPassesThroughForSpringSecurityToHandle() throws ServletException, IOException {
        // No authentication set in SecurityContext
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/store/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        // Filter chain continues so Spring Security can reject with 401 Unauthorized
        assertThat(chainInvoked.get()).isTrue();
    }
}
