package com.invo.coopr8.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.invo.coopr8.model.PlatformAdmin;
import com.invo.coopr8.repository.PlatformAdminRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bootstraps the initial Platform Super Admin account on first start.
 *
 * <p>Reads credentials from environment variables {@code PLATFORM_ADMIN_EMAIL} and
 * {@code PLATFORM_ADMIN_INITIAL_PASSWORD} only if the {@code platform_admins} table is empty.
 * No hardcoded default credentials.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformAdminBootstrap {

    private final PlatformAdminRepository platformAdminRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${platform.admin.email:${PLATFORM_ADMIN_EMAIL:}}")
    private String platformAdminEmail;

    @Value("${platform.admin.initial-password:${PLATFORM_ADMIN_INITIAL_PASSWORD:}}")
    private String platformAdminInitialPassword;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapIfEmpty() {
        if (platformAdminRepository.count() > 0) {
            return;
        }

        String email = platformAdminEmail != null ? platformAdminEmail.trim().toLowerCase() : "";
        String password = platformAdminInitialPassword != null ? platformAdminInitialPassword.trim() : "";

        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            log.warn("No platform administrators exist and PLATFORM_ADMIN_EMAIL / "
                    + "PLATFORM_ADMIN_INITIAL_PASSWORD are not configured. "
                    + "Platform administration endpoints will be unreachable until seeded.");
            return;
        }

        PlatformAdmin admin = PlatformAdmin.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .status("ACTIVE")
                .build();

        platformAdminRepository.save(admin);
        log.info("Bootstrapped initial platform super administrator: {}", email);
    }
}

