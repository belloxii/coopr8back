package com.invo.coopr8.dto.config;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationConfigAudit;

/**
 * One recorded configuration change.
 *
 * <p>One row per changed setting, not per request: an administrator who edits the interest rate and
 * the guarantor count in one submission produces two of these, because "what did this setting used
 * to be" is the question the trail has to answer.
 *
 * <p>{@code oldValue} is null only on the first-ever recorded value for a setting. A change from a
 * value to nothing -- clearing a bound -- records the empty string, not null, so "was never set"
 * stays distinguishable from "was unset by someone".
 *
 * <p>The actor is exposed as a membership number rather than an internal user id: the id means
 * nothing on an admin screen, and the trail is read by people.
 *
 * <p>{@code effectiveDate} is a record, not a control. Decision 4: configuration takes effect
 * immediately, and this column says when the change was made rather than scheduling it.
 *
 * <p>No organization field -- every row returned already belongs to the caller's cooperative,
 * because the only query used to find them is scoped by it.
 */
public record ConfigAuditResponse(
        Long id,
        String configDomain,
        String settingKey,
        String oldValue,
        String newValue,
        String actorLedgerId,
        LocalDate effectiveDate,
        String reason,
        LocalDateTime createdAt) {

    public static ConfigAuditResponse of(OrganizationConfigAudit entry) {
        return new ConfigAuditResponse(
                entry.getId(),
                entry.getConfigDomain() == null ? null : entry.getConfigDomain().name(),
                entry.getSettingKey(),
                entry.getOldValue(),
                entry.getNewValue(),
                entry.getActorLedgerId(),
                entry.getEffectiveDate(),
                entry.getReason(),
                entry.getCreatedAt());
    }
}
