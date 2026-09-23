package com.invo.coopr8.dto.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A cooperative's membership rules: which member details it insists on, and what a new
 * membership starts as.
 *
 * <p><strong>What is deliberately absent.</strong> None of the Class C settings appear here --
 * nothing about tenant isolation, {@code organization_id} assignment, JWT or security rules, the
 * password floor, subscription enforcement, or batch limits. Those are platform properties, not a
 * cooperative's to choose, and an endpoint that accepted one would be the hole.
 *
 * <p>No organization field -- see {@link LoanConfigRequest}.
 *
 * @param defaultMemberStatus what a newly created membership starts as. Constrained to the
 *                            statuses the platform already understands; anything else would put a
 *                            member in a state no code path can move them out of.
 */
public record MembershipConfigRequest(

        @NotNull(message = "Say whether an email address is required.")
        Boolean requireEmail,

        @NotNull(message = "Say whether a phone number is required.")
        Boolean requirePhone,

        @NotNull(message = "Say whether a PSN is required.")
        Boolean requirePsn,

        @NotNull(message = "Say whether a passport photograph is required.")
        Boolean requirePassport,

        @NotNull(message = "Say whether next-of-kin details are required.")
        Boolean requireNextOfKin,

        @NotNull(message = "Say whether new members are activated automatically.")
        Boolean autoActivateMembers,

        @NotNull(message = "Say whether new members must change their temporary password.")
        Boolean requireInitialPasswordChange,

        @NotBlank(message = "Choose the status a new membership starts in.")
        @Size(max = 32, message = "Keep the status under 32 characters.")
        String defaultMemberStatus,

        @Size(max = 512, message = "Keep the reason under 512 characters.")
        String reason) {
}
