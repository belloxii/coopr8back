package com.invo.coopr8.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.invo.coopr8.config.JwtConstant;
import com.invo.coopr8.config.JwtProperties;
import com.invo.coopr8.config.JwtProvider;
import com.invo.coopr8.config.JwtTokenValidator;
import com.invo.coopr8.config.PlatformJwtProvider;
import com.invo.coopr8.config.PlatformJwtValidatorFilter;
import com.invo.coopr8.model.PlatformAdmin;
import com.invo.coopr8.tenant.ActiveTenant;
import com.invo.coopr8.tenant.TenantContext;
import com.invo.coopr8.tenant.TenantResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

/**
 * Verifies bidirectional isolation between platform and tenant security boundaries:
 * 1. A tenant token presented on a platform endpoint (/api/platform/**) is rejected.
 * 2. A platform token presented on a tenant endpoint (/api/**) is refused.
 * 3. A platform token on /api/platform/** authenticates as ROLE_PLATFORM_ADMIN.
 * 4. Tenant JwtTokenValidator cleanly skips /api/platform/**.
 */
class PlatformSecurityWiringTest {

    private static final String SECRET_BYTES = "coopr8-super-secret-key-32-bytes-long!";
    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString(SECRET_BYTES.getBytes(StandardCharsets.UTF_8));

    private JwtProvider tenantJwtProvider;
    private PlatformJwtProvider platformJwtProvider;
    private TenantResolver tenantResolver;
    private PlatformJwtValidatorFilter platformFilter;
    private JwtTokenValidator tenantFilter;

    static class FakeTenantResolver extends TenantResolver {
        FakeTenantResolver() {
            super(null);
        }

        @Override
        public Optional<ActiveTenant> activeTenantById(Long organizationId) {
            if (organizationId != null && organizationId > 0) {
                return Optional.of(new ActiveTenant(organizationId, "citadel"));
            }
            return Optional.empty();
        }
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();

        JwtProperties properties = new JwtProperties();
        properties.setSecret(TEST_SECRET);
        properties.setExpirationMs(3600_000L);

        tenantJwtProvider = new JwtProvider(properties);
        try {
            var tenantInit = JwtProvider.class.getDeclaredMethod("init");
            tenantInit.setAccessible(true);
            tenantInit.invoke(tenantJwtProvider);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        platformJwtProvider = new PlatformJwtProvider(properties);
        try {
            var initMethod = PlatformJwtProvider.class.getDeclaredMethod("init");
            initMethod.setAccessible(true);
            initMethod.invoke(platformJwtProvider);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        tenantResolver = new FakeTenantResolver();

        platformFilter = new PlatformJwtValidatorFilter(platformJwtProvider);
        tenantFilter = new JwtTokenValidator(tenantJwtProvider, tenantResolver);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void tenantTokenIsRefusedOnPlatformEndpoints() throws ServletException, IOException {
        AuthPrincipal tenantPrincipal = new AuthPrincipal(
                10L, 1L, "citadel", "CBMC0001", Set.of("ROLE_ADMIN"), "token-1");
        String tenantToken = tenantJwtProvider.generateToken(tenantPrincipal);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/platform/plans");
        request.addHeader(JwtConstant.JWT_HEADER, JwtConstant.BEARER_PREFIX + tenantToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Chain runs platformFilter, then tenantFilter
        FilterChain chain = (req, res) -> {
            tenantFilter.doFilter(req, res, (innerReq, innerRes) -> {
                // Assert that inside the platform request handler, no authentication is granted
                assertThat(SecurityContextHolder.getContext().getAuthentication())
                        .as("Tenant token must not grant authentication on platform routes")
                        .isNull();
            });
        };

        platformFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(500);
    }

    @Test
    void platformTokenIsRefusedOnTenantEndpoints() throws ServletException, IOException {
        PlatformAdmin admin = PlatformAdmin.builder()
                .id(99L)
                .email("superadmin@coopr8.com")
                .passwordHash("hash")
                .build();
        String platformToken = platformJwtProvider.generateToken(admin);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/organization/current");
        request.addHeader(JwtConstant.JWT_HEADER, JwtConstant.BEARER_PREFIX + platformToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            tenantFilter.doFilter(req, res, (innerReq, innerRes) -> {
                // Assert that inside the tenant request handler, no tenant is bound and no auth is granted
                assertThat(TenantContext.isBound())
                        .as("Platform token must never bind a tenant")
                        .isFalse();
                assertThat(SecurityContextHolder.getContext().getAuthentication())
                        .as("Platform token must not authenticate on tenant endpoints")
                        .isNull();
            });
        };

        platformFilter.doFilter(request, response, chain);

        assertThat(TenantContext.isBound()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void platformTokenAuthenticatesOnPlatformEndpoints() throws ServletException, IOException {
        PlatformAdmin admin = PlatformAdmin.builder()
                .id(99L)
                .email("superadmin@coopr8.com")
                .passwordHash("hash")
                .build();
        String platformToken = platformJwtProvider.generateToken(admin);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/platform/plans");
        request.addHeader(JwtConstant.JWT_HEADER, JwtConstant.BEARER_PREFIX + platformToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            tenantFilter.doFilter(req, res, (innerReq, innerRes) -> {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                assertThat(auth).isNotNull();
                assertThat(auth.getPrincipal()).isInstanceOf(PlatformAdminPrincipal.class);
                PlatformAdminPrincipal principal = (PlatformAdminPrincipal) auth.getPrincipal();
                assertThat(principal.id()).isEqualTo(99L);
                assertThat(principal.email()).isEqualTo("superadmin@coopr8.com");
                assertThat(auth.getAuthorities())
                        .extracting("authority")
                        .containsExactly("ROLE_PLATFORM_ADMIN");
            });
        };

        platformFilter.doFilter(request, response, chain);

        // Outside filter execution, security context is cleared
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
