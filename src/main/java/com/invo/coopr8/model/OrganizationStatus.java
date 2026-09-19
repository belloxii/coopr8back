package com.invo.coopr8.model;

/**
 * Lifecycle state of a tenant organization on the COOPR8 platform.
 *
 * Persisted as a STRING (never an ordinal) so new states can be added later
 * without risk of corrupting existing rows. Phase 1 only uses ACTIVE; richer
 * states (onboarding, billing) arrive in later phases.
 */
public enum OrganizationStatus {
    ACTIVE,
    SUSPENDED,
    PENDING_VERIFICATION,
    PENDING_ACTIVATION
}
