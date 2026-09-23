package com.invo.coopr8.dto.config;

import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationMembershipConfig;

/** A cooperative's membership rules, as an administrator reads them back. No organization field. */
public record MembershipConfigResponse(
        Long id,
        Boolean requireEmail,
        Boolean requirePhone,
        Boolean requirePsn,
        Boolean requirePassport,
        Boolean requireNextOfKin,
        Boolean autoActivateMembers,
        Boolean requireInitialPasswordChange,
        String defaultMemberStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static MembershipConfigResponse of(OrganizationMembershipConfig configuration) {
        return new MembershipConfigResponse(
                configuration.getId(),
                configuration.getRequireEmail(),
                configuration.getRequirePhone(),
                configuration.getRequirePsn(),
                configuration.getRequirePassport(),
                configuration.getRequireNextOfKin(),
                configuration.getAutoActivateMembers(),
                configuration.getRequireInitialPasswordChange(),
                configuration.getDefaultMemberStatus(),
                configuration.getCreatedAt(),
                configuration.getUpdatedAt());
    }
}
