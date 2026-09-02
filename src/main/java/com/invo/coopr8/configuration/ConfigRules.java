package com.invo.coopr8.configuration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.model.InterestMethod;
import com.invo.coopr8.model.OrganizationMembershipConfig;
import com.invo.coopr8.model.SavingsFrequency;

/**
 * The refusals every configuration endpoint shares.
 *
 * <p>All of these could be expressed as bean-validation annotations except that the interesting ones
 * are relationships between two fields, or membership of a set that is defined in Java rather than in
 * an annotation. Keeping them here means one wording per rule, and one place to read what an
 * administrator is and is not allowed to say.
 *
 * <h2>Everything here throws 400, deliberately -- with two exceptions</h2>
 * A duplicate name is {@code 409}, because "there is already one of these" is a conflict rather than
 * a malformed request, and an unknown id is {@code 404} even when it belongs to another cooperative
 * (see {@code CurrentAuth.requireSelf}: absent and not-yours are the same answer).
 */
final class ConfigRules {

    /** The statuses a new membership may start in. See {@code OrganizationMembershipConfig}. */
    private static final Set<String> MEMBER_STATUSES = Set.of(
            OrganizationMembershipConfig.STATUS_NEW,
            OrganizationMembershipConfig.STATUS_PENDING,
            OrganizationMembershipConfig.STATUS_ACTIVE);

    private ConfigRules() {
    }

    // ------------------------------------------------------------------------- enums

    /**
     * The interest method named, which must be one an administrator is allowed to choose.
     *
     * <p><strong>{@code REDUCING_BALANCE} is refused here.</strong> It exists in the enum as a
     * persisted value so that a future implementation has a name to store, and
     * {@code InterestMethod.isAdminSelectable()} is false for it because no code computes it yet.
     * Offering it on a settings screen would let a cooperative select an interest method the platform
     * would then silently ignore -- members would be charged something other than what their
     * cooperative agreed. Refusing is the only honest answer until Stage 4 or later implements it.
     */
    static InterestMethod requireSelectableInterestMethod(String raw) {
        InterestMethod method = parseInterestMethod(raw);
        if (!method.isAdminSelectable()) {
            throw refusal(method.name() + " is not available yet. Choose " + selectableMethods()
                    + ".");
        }
        return method;
    }

    /**
     * As {@link #requireSelectableInterestMethod}, but null and blank mean "inherit the
     * cooperative-wide setting" -- which is what a loan product leaving the field empty means.
     */
    static InterestMethod optionalSelectableInterestMethod(String raw) {
        return isBlank(raw) ? null : requireSelectableInterestMethod(raw);
    }

    static SavingsFrequency requireFrequency(String raw) {
        for (SavingsFrequency frequency : SavingsFrequency.values()) {
            if (frequency.name().equalsIgnoreCase(trim(raw))) {
                return frequency;
            }
        }
        throw refusal("\"" + trim(raw) + "\" is not a contribution frequency. Choose "
                + Arrays.stream(SavingsFrequency.values()).map(Enum::name)
                        .collect(Collectors.joining(", "))
                + ".");
    }

    /**
     * The member status named, which must be one the platform can act on.
     *
     * <p>A free-form status would create members no code path can unlock: {@code refuseByStatus}
     * recognises NEW and PENDING as awaiting approval and nothing else, so a member filed as
     * {@code "AWAITING_DOCS"} could neither sign in nor be approved.
     */
    static String requireMemberStatus(String raw) {
        String candidate = trim(raw).toUpperCase();
        if (!MEMBER_STATUSES.contains(candidate)) {
            throw refusal("\"" + trim(raw) + "\" is not a member status. Choose "
                    + String.join(", ", MEMBER_STATUSES.stream().sorted().toList()) + ".");
        }
        return candidate;
    }

    // ------------------------------------------------------------------------ ordering

    /**
     * Refuses a minimum above its maximum.
     *
     * <p>Either bound may be null, meaning "no bound"; only two present bounds can contradict each
     * other. A pair where minimum equals maximum is allowed -- "exactly this much" is a coherent
     * rule.
     */
    static void requireOrderedAmounts(BigDecimal minimum, BigDecimal maximum, String what) {
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw refusal("The minimum " + what + " cannot be more than the maximum " + what + ".");
        }
    }

    static void requireOrderedMonths(Integer minimum, Integer maximum, String what) {
        if (minimum != null && maximum != null && minimum > maximum) {
            throw refusal("The minimum " + what + " cannot be more than the maximum " + what + ".");
        }
    }

    // --------------------------------------------------------------------------- names

    /**
     * A loan product name as it must be stored.
     *
     * <p>{@code ux_organization_loan_type_org_name} is unique on
     * {@code lower(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g')))}, so "Real  Estate" and
     * "real estate" collide in the database. Normalizing before the duplicate check is what makes the
     * application's answer agree with the database's: without it the check passes, the insert fails,
     * and the administrator gets a 500 naming an index.
     */
    static String normalizedProductName(String raw) {
        return trim(raw).replaceAll("\\s+", " ");
    }

    /**
     * A savings plan name as it must be stored.
     *
     * <p>{@code ux_organization_savings_plan_org_name} is unique on {@code lower(btrim(name))} --
     * trimmed but not interior-collapsed, so this normalizes less than
     * {@link #normalizedProductName} does. The two differ because the two indexes differ, and
     * matching each one is the point.
     */
    static String normalizedPlanName(String raw) {
        return trim(raw);
    }

    // ---------------------------------------------------------------------- refusals

    /** A malformed or self-contradictory submission. */
    static ResponseStatusException refusal(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** "There is already one of these." */
    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    /**
     * Unknown, or belonging to another cooperative.
     *
     * <p>The same answer for both, on purpose. A distinct 403 for another cooperative's id would
     * confirm that the id exists somewhere on the platform, which is precisely the fact a tenant
     * boundary is meant to withhold.
     */
    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
    }

    // ---------------------------------------------------------------------- internals

    private static InterestMethod parseInterestMethod(String raw) {
        for (InterestMethod method : InterestMethod.values()) {
            if (method.name().equalsIgnoreCase(trim(raw))) {
                return method;
            }
        }
        throw refusal("\"" + trim(raw) + "\" is not an interest method. Choose "
                + selectableMethods() + ".");
    }

    private static String selectableMethods() {
        return Arrays.stream(InterestMethod.values())
                .filter(InterestMethod::isAdminSelectable)
                .map(Enum::name)
                .collect(Collectors.joining(" or "));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
