package com.invo.coopr8.model;

/**
 * How often a savings plan expects a contribution.
 *
 * <p>Persisted as a STRING (never an ordinal), matching every other enum in this package.
 *
 * <p><strong>This is a label, not a schedule.</strong> COOPR8 has no due-date engine, no
 * arrears calculation and no dunning, and nothing in the platform will chase a member who
 * misses a contribution. The value is recorded because a plan amount without a period is
 * meaningless to the member reading it — "₦5,000" is not a plan, "₦5,000 monthly" is — and
 * because it is snapshotted onto a savings posting so a historical contribution keeps the
 * terms it was made under.
 */
public enum SavingsFrequency {
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    ANNUALLY
}
