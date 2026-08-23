package com.invo.coopr8.model;

/**
 * Which area of a cooperative's business configuration an audit record describes.
 *
 * <p>Persisted as a STRING (never an ordinal), matching every other enum in this package,
 * and mirrored by {@code ck_organization_config_audit_domain} in the V8 migration. Adding
 * a constant here requires altering that CHECK deliberately — Hibernate's
 * {@code ddl-auto=validate} does not inspect CHECK constraints and will not warn.
 *
 * <p>There are seven constants for eight Phase 4 tables. The audit table itself is absent on
 * purpose: it never audits itself, because it cannot be changed.
 */
public enum ConfigDomain {

    /** {@code organization_loan_config} — interest, amount and tenure bounds, guarantors. */
    LOAN_CONFIG,

    /** {@code organization_loan_type} — a named loan product and its overrides. */
    LOAN_TYPE,

    /**
     * {@code organization_loan_type_exclusion} — a pair of products a member may not hold at once.
     *
     * <p>Separate from {@link #LOAN_TYPE} because an exclusion is a rule about two types. Recording
     * it against either one alone would misstate what was changed, and there is no third type to
     * attribute it to.
     */
    LOAN_TYPE_EXCLUSION,

    /** {@code organization_savings_plan} — a named contribution plan. */
    SAVINGS_PLAN,

    /** {@code organization_shares_config} — share price, purchase and withdrawal rules. */
    SHARES_CONFIG,

    /** {@code organization_repayment_config} — partial payment, overpayment, tolerance. */
    REPAYMENT_CONFIG,

    /** {@code organization_membership_config} — onboarding requirements and activation. */
    MEMBERSHIP_CONFIG
}
