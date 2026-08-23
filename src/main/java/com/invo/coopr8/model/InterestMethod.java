package com.invo.coopr8.model;

/**
 * How a cooperative charges interest on a loan.
 *
 * <p>Persisted as a STRING (never an ordinal), matching every other enum in this
 * package, so a constant can be added without corrupting existing rows.
 *
 * <p><strong>Only {@link #NONE} and {@link #FLAT} may be offered to an
 * administrator.</strong> {@link #REDUCING_BALANCE} exists as a persisted value so the
 * schema does not need redesigning when reducing-balance support is introduced, but no
 * engine can compute it yet. A value the platform cannot calculate must not be reachable
 * from a settings screen, so each constant carries {@link #isAdminSelectable()} and the
 * admin surface is built from that rather than from {@code values()}.
 *
 * <p>The rate a cooperative configures is always an <strong>annual</strong> percentage.
 * There is no configurable rate basis: the same stored figure {@code 12} means anywhere
 * between ₦18,000 and ₦864,000 of interest on a ₦300,000 loan depending on how it is
 * read, so the basis is a platform constant rather than a per-tenant setting.
 *
 * <p>For {@link #FLAT}, interest is
 * {@code principal × annual_rate × (tenure_months / 12)} — charged on the original
 * principal for the whole tenure, regardless of what has been repaid.
 */
public enum InterestMethod {

    /**
     * No interest. Total repayable equals the principal.
     *
     * <p>This reproduces what the platform does today, and is what every existing
     * organization is seeded with, so shipping interest support changes no cooperative's
     * economics until an administrator changes them deliberately.
     */
    NONE(true),

    /**
     * Flat interest on the original principal:
     * {@code interest = principal × annual_rate × (tenure_months / 12)}.
     *
     * <p>Simple to explain to a member and simple to verify by hand, which is why it is
     * the first method supported. Note that it is materially more expensive than
     * reducing-balance at the same headline rate — ₦300,000 over 12 months at 12% p.a. is
     * ₦36,000 flat against ₦19,855.68 reducing-balance, about 1.8×. A cooperative
     * migrating from a reducing-balance product must expect to quote a lower number here
     * to charge the same money.
     */
    FLAT(true),

    /**
     * Interest on the outstanding balance, recomputed as the principal amortises.
     *
     * <p><strong>Not implemented and not selectable.</strong> The constant is reserved so
     * that adding the calculation later is a code change rather than a schema migration.
     * Nothing may offer this to an administrator while {@code isAdminSelectable()} is
     * false, and no engine may be asked to compute it.
     */
    REDUCING_BALANCE(false);

    private final boolean adminSelectable;

    InterestMethod(boolean adminSelectable) {
        this.adminSelectable = adminSelectable;
    }

    /**
     * Whether an organization administrator may choose this method.
     *
     * <p>False means the platform persists and understands the value but cannot currently
     * calculate with it. Build every admin-facing list of methods from this flag; never
     * from {@code values()}.
     */
    public boolean isAdminSelectable() {
        return adminSelectable;
    }
}
