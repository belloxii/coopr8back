package com.invo.coopr8.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Beans every integration test gets, registered explicitly by
 * {@code AbstractIntegrationTest}'s {@code @SpringBootTest(classes = ...)}.
 *
 * <p>Only outbound side effects are replaced. Everything that Phase 2 is about --
 * the security filter chain, JWT handling, tenant context, repositories, Flyway's
 * real schema -- runs exactly as it does in production, because a stubbed version of
 * any of those would make the isolation tests meaningless.
 */
@TestConfiguration
public class TestSupportConfig {

    /**
     * Replaces the SMTP-backed {@code EmailServiceImpl}. Tests must never send real mail,
     * and several tenant assertions need to inspect what <em>would</em> have been sent --
     * in particular that an OTP mail carries only the requesting tenant's branding.
     */
    @Bean
    @Primary
    public RecordingEmailService recordingEmailService() {
        return new RecordingEmailService();
    }
}
