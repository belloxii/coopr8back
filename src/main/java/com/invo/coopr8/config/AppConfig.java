package com.invo.coopr8.config;

import java.util.Arrays;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import com.invo.coopr8.tenant.TenantResolver;
import com.invo.coopr8.security.RequestRateLimitFilter;

import jakarta.servlet.http.HttpServletRequest;

@Configuration
@EnableWebSecurity
public class AppConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity,
            JwtProvider jwtProvider, PlatformJwtProvider platformJwtProvider,
            TenantResolver tenantResolver) throws Exception {
        httpSecurity
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(management -> management.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // Tenant discovery and credential entry. Each of these is reachable
                        // without a token by necessity; each one resolves its own organization
                        // and is individually responsible for staying tenant-scoped.
                        .requestMatchers(
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/api/auth/forgot-password/**",
                                "/api/organization/public/**",
                                "/api/otp/**",
                                "/api/images/upload",
                                "/api/webhook/paystack",
                                "/api/plans")
                        .permitAll()

                        // Platform super administration: login is public, everything else requires
                        // ROLE_PLATFORM_ADMIN. Placed before /api/** so tenant rules do not evaluate platform routes.
                        .requestMatchers("/api/platform/auth/login").permitAll()
                        .requestMatchers("/api/platform/**").hasRole("PLATFORM_ADMIN")

                        // Approving or declining a share withdrawal moves a member's money. It
                        // is an administrative act, enforced here at the route and again in the
                        // service, so neither layer is the only thing standing between a member
                        // and their own approval.
                        .requestMatchers(HttpMethod.PUT, "/api/shares/*/approve").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/shares/*/decline").hasRole("ADMIN")

                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/user/**").hasAnyRole("MEMBER", "ADMIN")
                        .requestMatchers("/api/**").authenticated()

                        // Container-dispatched error pages must stay reachable: Spring Security
                        // authorizes ERROR dispatches too, so denying /error would turn every
                        // handled exception into an opaque 403.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        // Anything not named above is refused rather than served. Previously
                        // this was permitAll, which meant a new endpoint outside /api was public
                        // by default -- the wrong default for a multi-tenant platform.
                        .anyRequest().denyAll())

                // Distinguishes "not authenticated" from "authenticated but not allowed": no
                // token gives 401, a member reaching an admin route gives 403. Without this,
                // Spring Security's default entry point would answer 403 for both, and the
                // authorization tests could not tell the two apart.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .addFilterBefore(new JwtTokenValidator(jwtProvider, tenantResolver),
                        BasicAuthenticationFilter.class)
                .addFilterBefore(new PlatformJwtValidatorFilter(platformJwtProvider),
                        JwtTokenValidator.class)
                .addFilterBefore(new RequestRateLimitFilter(),
                        PlatformJwtValidatorFilter.class);

        return httpSecurity.build();
    }

    @Value("${frontend.url}")
    private String frontendURL;

    private CorsConfigurationSource corsConfigurationSource() {
        return new CorsConfigurationSource() {

            @Override
            public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
                CorsConfiguration cfg = new CorsConfiguration();
                cfg.setAllowedOrigins(Arrays.asList(frontendURL));
                cfg.setAllowedMethods(Collections.singletonList("*"));
                cfg.setAllowCredentials(true);
                cfg.setAllowedHeaders(Collections.singletonList("*"));
                cfg.setExposedHeaders(Arrays.asList("Authorization"));
                cfg.setMaxAge(3600L);

                return cfg;
            }

        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
